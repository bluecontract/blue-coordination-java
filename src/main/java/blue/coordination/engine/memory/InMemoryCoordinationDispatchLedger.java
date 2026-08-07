package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryReceipt;
import blue.coordination.engine.api.CoordinationDeliveryStatus;
import blue.coordination.engine.api.CoordinationDispatchPage;
import blue.coordination.engine.api.CoordinationDispatchPlan;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe reference dispatch ledger and page-addressable frozen plan store.
 * Target pages are admitted before any PROCESS call and are the sole source for
 * retries; the dispatcher never needs a second complete target vector.
 */
public final class InMemoryCoordinationDispatchLedger {

    private final Map<String, MutableDispatch> dispatches =
            new LinkedHashMap<String, MutableDispatch>();
    private static final int DISPATCH_MONITOR_STRIPES = 256;

    private final Object[] dispatchMonitors =
            new Object[DISPATCH_MONITOR_STRIPES];
    private long nextFreezeToken;

    public InMemoryCoordinationDispatchLedger() {
        for (int index = 0; index < dispatchMonitors.length; index++) {
            dispatchMonitors[index] = new Object();
        }
    }

    /**
     * Starts admission of bounded, canonical cursor pages. If an equivalent
     * plan was sealed by a racing caller, the returned admission is reusable
     * and no pages may be appended through it.
     */
    public synchronized FreezeAdmission beginFreeze(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            long routeIndexGeneration,
            int maximumRootsPerChunk) {
        CoordinationDispatchPlan header = CoordinationDispatchPlan.fromPages(
                Objects.requireNonNull(event, "event"),
                Objects.requireNonNull(
                        exactEventSubscriptionKeys,
                        "exactEventSubscriptionKeys"),
                Objects.requireNonNull(sourceChannel, "sourceChannel"),
                routeIndexGeneration,
                Collections.<List<IndexedSessionCandidates>>emptyList(),
                maximumRootsPerChunk);
        MutableDispatch existing = dispatches.get(event.eventBlueId());
        if (existing != null) {
            existing.requireSameRequest(header);
            if (!existing.sealed()) {
                throw new IllegalStateException(
                        "Dispatch target freeze is already in progress for "
                                + event.eventBlueId());
            }
            return new FreezeAdmission(event.eventBlueId(), 0L, true);
        }
        nextFreezeToken = Math.addExact(nextFreezeToken, 1L);
        MutableDispatch created = new MutableDispatch(
                header, nextFreezeToken);
        dispatches.put(event.eventBlueId(), created);
        return new FreezeAdmission(
                event.eventBlueId(), nextFreezeToken, false);
    }

    /** Appends one page without ever accepting a split or oversized Root page. */
    public synchronized void appendFrozenPage(
            FreezeAdmission admission,
            List<IndexedSessionCandidates> targetPage) {
        MutableDispatch dispatch = requireFreeze(admission);
        dispatch.appendPage(targetPage);
    }

    /** Seals the complete route before execution and returns its paged plan. */
    public synchronized CoordinationDispatchPlan sealFreeze(
            FreezeAdmission admission) {
        FreezeAdmission checked = Objects.requireNonNull(
                admission, "admission");
        if (checked.reusedSealedPlan()) {
            return requirePlan(checked.eventBlueId());
        }
        MutableDispatch dispatch = requireFreeze(checked);
        return dispatch.seal();
    }

    /** Removes only this caller's incomplete freeze; no target was executable. */
    public synchronized void abortFreeze(FreezeAdmission admission) {
        FreezeAdmission checked = Objects.requireNonNull(
                admission, "admission");
        if (checked.reusedSealedPlan()) return;
        MutableDispatch current = dispatches.get(checked.eventBlueId());
        if (current != null
                && !current.sealed()
                && current.freezeToken == checked.freezeToken) {
            dispatches.remove(checked.eventBlueId());
        }
    }

    /**
     * Compatibility path for callers that already materialized all targets.
     * Environment fan-out uses begin/append/seal instead.
     */
    public synchronized CoordinationDispatchSnapshot beginOrResume(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            long routeIndexGeneration,
            List<IndexedSessionCandidates> targets,
            int maximumRootsPerChunk) {
        CoordinationDispatchPlan supplied = new CoordinationDispatchPlan(
                Objects.requireNonNull(event, "event"),
                Objects.requireNonNull(
                        exactEventSubscriptionKeys,
                        "exactEventSubscriptionKeys"),
                Objects.requireNonNull(sourceChannel, "sourceChannel"),
                routeIndexGeneration,
                Objects.requireNonNull(targets, "targets"),
                maximumRootsPerChunk);
        MutableDispatch existing = dispatches.get(event.eventBlueId());
        if (existing == null) {
            nextFreezeToken = Math.addExact(nextFreezeToken, 1L);
            MutableDispatch created = new MutableDispatch(
                    CoordinationDispatchPlan.fromPages(
                            supplied.event(),
                            supplied.exactEventSubscriptionKeys(),
                            supplied.sourceChannel(),
                            supplied.routeIndexGeneration(),
                            Collections
                                    .<List<IndexedSessionCandidates>>emptyList(),
                            supplied.maximumRootsPerChunk()),
                    nextFreezeToken);
            for (List<IndexedSessionCandidates> page : supplied.pages()) {
                created.appendPage(page);
            }
            created.seal();
            dispatches.put(event.eventBlueId(), created);
            return created.snapshot();
        }
        if (!existing.sealed()) {
            throw new IllegalStateException(
                    "Dispatch target freeze is already in progress for "
                            + event.eventBlueId());
        }
        existing.requireSamePlan(supplied);
        return existing.snapshot();
    }

    public synchronized Optional<CoordinationDispatchSnapshot> find(
            String eventBlueId) {
        MutableDispatch found = dispatches.get(
                Objects.requireNonNull(eventBlueId, "eventBlueId"));
        if (found == null) {
            return Optional.empty();
        }
        found.requireSealed();
        return Optional.of(found.snapshot());
    }

    /** Finds only the paged plan, avoiding an eager complete receipt snapshot. */
    public synchronized Optional<CoordinationDispatchPlan> findPlan(
            String eventBlueId) {
        MutableDispatch found = dispatches.get(
                Objects.requireNonNull(eventBlueId, "eventBlueId"));
        if (found == null) {
            return Optional.empty();
        }
        found.requireSealed();
        return Optional.of(found.plan);
    }

    /** Whether a complete frozen plan is available for exact replay. */
    public synchronized boolean containsSealedDispatch(String eventBlueId) {
        MutableDispatch found = dispatches.get(
                Objects.requireNonNull(eventBlueId, "eventBlueId"));
        return found != null && found.sealed();
    }

    /** Validates retry metadata without exposing all stored target pages. */
    public synchronized void requireSameDispatchRequest(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            int maximumRootsPerChunk) {
        MutableDispatch dispatch = requireDispatch(
                Objects.requireNonNull(event, "event").eventBlueId());
        dispatch.requireSealed();
        CoordinationDispatchPlan suppliedHeader =
                CoordinationDispatchPlan.fromPages(
                        event,
                        exactEventSubscriptionKeys,
                        sourceChannel,
                        dispatch.routeIndexGeneration,
                        Collections
                                .<List<IndexedSessionCandidates>>emptyList(),
                        maximumRootsPerChunk);
        dispatch.requireSameRequest(suppliedHeader);
    }

    /** Canonical event header for page-by-page execution and resume. */
    public synchronized StoredCoordinationEvent storedEvent(
            String eventBlueId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.event;
    }

    public synchronized int maximumRootsPerChunk(String eventBlueId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.maximumRootsPerChunk;
    }

    public synchronized CoordinationDispatchPlan requirePlan(
            String eventBlueId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.plan;
    }

    public synchronized int frozenPageCount(String eventBlueId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.pages.size();
    }

    /** Largest single page admitted for this plan; useful for host budgets. */
    public synchronized int maximumFrozenPageSize(String eventBlueId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.maximumFrozenPageSize;
    }

    /** Returns the immutable stored page; callers cannot mutate plan evidence. */
    public synchronized List<IndexedSessionCandidates> frozenTargetPage(
            String eventBlueId,
            int pageIndex) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.pages.get(pageIndex).targets();
    }

    /** Returns current evidence for one target without scanning all receipts. */
    public synchronized CoordinationDeliveryReceipt receipt(
            String eventBlueId,
            DocumentSessionId sessionId) {
        MutableReceipt receipt = requireReceipt(eventBlueId, sessionId);
        return receipt.snapshot(eventBlueId, sessionId);
    }

    public synchronized CoordinationDeliveryAdmission beginAttempt(
            String eventBlueId,
            DocumentSessionId sessionId) {
        MutableReceipt receipt = requireReceipt(eventBlueId, sessionId);
        if (receipt.status == CoordinationDeliveryStatus.COMMITTED) {
            throw new IllegalStateException(
                    "Delivery is already committed for "
                            + eventBlueId + " -> " + sessionId);
        }
        if (receipt.status == CoordinationDeliveryStatus.IN_FLIGHT) {
            throw new IllegalStateException(
                    "Delivery is already in flight for "
                            + eventBlueId + " -> " + sessionId);
        }
        receipt.attemptCount = Math.addExact(receipt.attemptCount, 1);
        receipt.status = CoordinationDeliveryStatus.IN_FLIGHT;
        receipt.failureClass = null;
        return new CoordinationDeliveryAdmission(
                eventBlueId, sessionId, receipt.attemptCount);
    }

    public synchronized CoordinationDeliveryReceipt commit(
            CoordinationDeliveryAdmission admission,
            CoordinationCommittedDelivery committed) {
        CoordinationDeliveryAdmission checked = Objects.requireNonNull(
                admission, "admission");
        MutableReceipt receipt = requireCurrentAttempt(checked);
        CoordinationCommittedDelivery committedDelivery =
                Objects.requireNonNull(committed, "committed");
        if (!checked.eventBlueId().equals(
                committedDelivery.eventBlueId())) {
            throw new IllegalStateException(
                    "Committed delivery belongs to another event");
        }
        receipt.requireCompatible(committedDelivery);
        receipt.status = CoordinationDeliveryStatus.COMMITTED;
        receipt.resultingEpoch = Long.valueOf(
                committedDelivery.resultingEpoch());
        receipt.resultingRootBlueId =
                committedDelivery.resultingRootBlueId();
        receipt.transitionIdentity =
                committedDelivery.transitionIdentity();
        receipt.committedOutboxEventBlueIds =
                committedDelivery.rootOutboxEventBlueIds();
        receipt.failureClass = null;
        return receipt.snapshot(
                checked.eventBlueId(), checked.sessionId());
    }

    /** Reconciles from evidence committed atomically with authoritative state. */
    public synchronized CoordinationDeliveryReceipt recoverCommitted(
            String eventBlueId,
            DocumentSessionId sessionId,
            CoordinationCommittedDelivery committed) {
        MutableReceipt receipt = requireReceipt(eventBlueId, sessionId);
        CoordinationCommittedDelivery checked = Objects.requireNonNull(
                committed, "committed");
        if (!eventBlueId.equals(checked.eventBlueId())) {
            throw new IllegalStateException(
                    "Committed delivery belongs to another event");
        }
        receipt.requireCompatible(checked);
        if (receipt.status == CoordinationDeliveryStatus.COMMITTED) {
            receipt.requireSameTerminal(checked);
            return receipt.snapshot(eventBlueId, sessionId);
        }
        receipt.attemptCount = Math.max(1, receipt.attemptCount);
        receipt.status = CoordinationDeliveryStatus.COMMITTED;
        receipt.resultingEpoch = Long.valueOf(checked.resultingEpoch());
        receipt.resultingRootBlueId = checked.resultingRootBlueId();
        receipt.transitionIdentity = checked.transitionIdentity();
        receipt.committedOutboxEventBlueIds =
                checked.rootOutboxEventBlueIds();
        receipt.failureClass = null;
        return receipt.snapshot(eventBlueId, sessionId);
    }

    public synchronized CoordinationDeliveryReceipt fail(
            CoordinationDeliveryAdmission admission,
            Throwable failure) {
        CoordinationDeliveryAdmission checked = Objects.requireNonNull(
                admission, "admission");
        MutableReceipt receipt = requireCurrentAttempt(checked);
        receipt.status = CoordinationDeliveryStatus.FAILED;
        receipt.resultingEpoch = null;
        receipt.resultingRootBlueId = null;
        receipt.transitionIdentity = null;
        receipt.committedOutboxEventBlueIds = Collections.emptyList();
        receipt.failureClass = Objects.requireNonNull(failure, "failure")
                .getClass().getName();
        return receipt.snapshot(
                checked.eventBlueId(), checked.sessionId());
    }

    public synchronized CoordinationDispatchSnapshot require(
            String eventBlueId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        return dispatch.snapshot();
    }

    public synchronized int dispatchCount() { return dispatches.size(); }

    /**
     * Captures a quiescent isolated ledger copy for an in-process checkpoint.
     *
     * <p>An incomplete target freeze or an in-flight Root claim is rejected
     * rather than being turned into ambiguous retry state. Sealed target pages
     * and terminal/pending/failed receipts are copied exactly; immutable plan
     * values are reconstructed from their canonical stored pages.</p>
     */
    public synchronized InMemoryCoordinationDispatchLedger copyAtQuiescence() {
        InMemoryCoordinationDispatchLedger result =
                new InMemoryCoordinationDispatchLedger();
        for (Map.Entry<String, MutableDispatch> entry
                : dispatches.entrySet()) {
            MutableDispatch source = entry.getValue();
            source.requireCheckpointable();
            result.dispatches.put(entry.getKey(), source.copy());
        }
        result.nextFreezeToken = nextFreezeToken;
        return result;
    }

    /** Stable digest of sealed pages, targets, attempts and terminal state. */
    public synchronized String stateFingerprint() {
        List<String> eventBlueIds = new ArrayList<String>(
                dispatches.keySet());
        Collections.sort(eventBlueIds);
        StringBuilder canonical = new StringBuilder();
        for (String eventBlueId : eventBlueIds) {
            MutableDispatch dispatch = dispatches.get(eventBlueId);
            dispatch.requireCheckpointable();
            canonical.append(eventBlueId).append('\u0000')
                    .append(dispatch.sourceChannel).append('\u0000')
                    .append(dispatch.routeIndexGeneration).append('\u0000')
                    .append(dispatch.maximumRootsPerChunk).append('\n');
            for (CoordinationDispatchPage page : dispatch.pages) {
                canonical.append("page:");
                for (IndexedSessionCandidates target : page.targets()) {
                    MutableReceipt receipt = dispatch.receipts.get(
                            target.sessionId());
                    canonical.append(target.sessionId().value())
                            .append(',').append(target.plannedEpoch())
                            .append(',').append(target.plannedRootBlueId())
                            .append(',').append(
                                    target.subscriptionSnapshotIdentity())
                            .append(',').append(
                                    target.orderedOccurrenceKeys())
                            .append(',').append(receipt.status)
                            .append(',').append(receipt.attemptCount)
                            .append(',').append(receipt.resultingEpoch)
                            .append(',').append(receipt.resultingRootBlueId)
                            .append(',').append(receipt.transitionIdentity)
                            .append(',').append(
                                    receipt.committedOutboxEventBlueIds)
                            .append(',').append(receipt.failureClass)
                            .append(';');
                }
                canonical.append('\n');
            }
        }
        return InMemoryCheckpointFingerprint.sha256(canonical.toString());
    }

    /** Bounded per-event stripe shared by every fan-out using this ledger. */
    Object dispatchMonitor(String eventBlueId) {
        String checked = Objects.requireNonNull(eventBlueId, "eventBlueId");
        int hash = checked.hashCode();
        hash ^= hash >>> 16;
        return dispatchMonitors[hash & (DISPATCH_MONITOR_STRIPES - 1)];
    }

    private MutableDispatch requireFreeze(FreezeAdmission admission) {
        FreezeAdmission checked = Objects.requireNonNull(
                admission, "admission");
        if (checked.reusedSealedPlan()) {
            throw new IllegalStateException(
                    "An already sealed plan cannot accept target pages");
        }
        MutableDispatch dispatch = requireDispatch(checked.eventBlueId());
        if (dispatch.sealed() || dispatch.freezeToken != checked.freezeToken) {
            throw new IllegalStateException(
                    "Dispatch freeze admission is no longer current for "
                            + checked.eventBlueId());
        }
        return dispatch;
    }

    private MutableReceipt requireCurrentAttempt(
            CoordinationDeliveryAdmission admission) {
        MutableReceipt receipt = requireReceipt(
                admission.eventBlueId(), admission.sessionId());
        if (receipt.status != CoordinationDeliveryStatus.IN_FLIGHT
                || receipt.attemptCount != admission.attemptNumber()) {
            throw new IllegalStateException(
                    "Delivery admission is no longer current for "
                            + admission.eventBlueId() + " -> "
                            + admission.sessionId());
        }
        return receipt;
    }

    private MutableReceipt requireReceipt(
            String eventBlueId,
            DocumentSessionId sessionId) {
        MutableDispatch dispatch = requireDispatch(eventBlueId);
        dispatch.requireSealed();
        MutableReceipt receipt = dispatch.receipts.get(
                Objects.requireNonNull(sessionId, "sessionId"));
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "Session is not in frozen dispatch: " + sessionId);
        }
        return receipt;
    }

    private MutableDispatch requireDispatch(String eventBlueId) {
        MutableDispatch dispatch = dispatches.get(
                Objects.requireNonNull(eventBlueId, "eventBlueId"));
        if (dispatch == null) {
            throw new IllegalArgumentException(
                    "Unknown dispatch " + eventBlueId);
        }
        return dispatch;
    }

    /** Opaque token proving ownership of an incomplete target freeze. */
    public static final class FreezeAdmission {
        private final String eventBlueId;
        private final long freezeToken;
        private final boolean reusedSealedPlan;

        private FreezeAdmission(
                String eventBlueId,
                long freezeToken,
                boolean reusedSealedPlan) {
            this.eventBlueId = eventBlueId;
            this.freezeToken = freezeToken;
            this.reusedSealedPlan = reusedSealedPlan;
        }

        public String eventBlueId() { return eventBlueId; }
        public boolean reusedSealedPlan() { return reusedSealedPlan; }
    }

    private static final class MutableDispatch {
        private final StoredCoordinationEvent event;
        private final List<String> exactEventSubscriptionKeys;
        private final String sourceChannel;
        private final long routeIndexGeneration;
        private final int maximumRootsPerChunk;
        private final long freezeToken;
        private final List<CoordinationDispatchPage> pages =
                new ArrayList<CoordinationDispatchPage>();
        private final Map<DocumentSessionId, MutableReceipt> receipts =
                new LinkedHashMap<DocumentSessionId, MutableReceipt>();
        private IndexedSessionCandidates lastTarget;
        private int maximumFrozenPageSize;
        private CoordinationDispatchPlan plan;

        private MutableDispatch(
                CoordinationDispatchPlan header,
                long freezeToken) {
            this.event = header.event();
            this.exactEventSubscriptionKeys =
                    header.exactEventSubscriptionKeys();
            this.sourceChannel = header.sourceChannel();
            this.routeIndexGeneration = header.routeIndexGeneration();
            this.maximumRootsPerChunk = header.maximumRootsPerChunk();
            this.freezeToken = freezeToken;
        }

        private boolean sealed() { return plan != null; }

        private void appendPage(List<IndexedSessionCandidates> suppliedPage) {
            requireUnsealed();
            List<IndexedSessionCandidates> checked = Objects.requireNonNull(
                    suppliedPage, "targetPage");
            if (checked.isEmpty()) {
                throw new IllegalArgumentException(
                        "Frozen target page must not be empty");
            }
            if (checked.size() > maximumRootsPerChunk) {
                throw new IllegalArgumentException(
                        "Frozen target page exceeds maximumRootsPerChunk");
            }
            IndexedSessionCandidates previous = lastTarget;
            for (IndexedSessionCandidates target : checked) {
                Objects.requireNonNull(target, "target");
                if (previous != null && previous.compareTo(target) >= 0) {
                    throw new IllegalArgumentException(
                            "Frozen target cursor is not in unique canonical "
                                    + "session order at " + target.sessionId());
                }
                if (receipts.containsKey(target.sessionId())) {
                    throw new IllegalArgumentException(
                            "Duplicate target session " + target.sessionId());
                }
                previous = target;
            }
            CoordinationDispatchPage page =
                    new CoordinationDispatchPage(checked);
            for (IndexedSessionCandidates target : page.targets()) {
                receipts.put(target.sessionId(), new MutableReceipt(target));
            }
            pages.add(page);
            maximumFrozenPageSize = Math.max(
                    maximumFrozenPageSize, page.size());
            lastTarget = previous;
        }

        private CoordinationDispatchPlan seal() {
            requireUnsealed();
            CoordinationDispatchPlan sealedPlan =
                    CoordinationDispatchPlan.fromFrozenPages(
                            event,
                            exactEventSubscriptionKeys,
                            sourceChannel,
                            routeIndexGeneration,
                            pages,
                            maximumRootsPerChunk);
            plan = sealedPlan;
            return plan;
        }

        private void requireSameRequest(CoordinationDispatchPlan supplied) {
            if (!event.fragmentInventoryIdentity().equals(
                    supplied.event().fragmentInventoryIdentity())
                    || !event.orderKey().equals(
                            supplied.event().orderKey())
                    || !exactEventSubscriptionKeys.equals(
                            supplied.exactEventSubscriptionKeys())
                    || !sourceChannel.equals(supplied.sourceChannel())
                    || maximumRootsPerChunk
                            != supplied.maximumRootsPerChunk()) {
                throw new IllegalStateException(
                        "Conflicting canonical dispatch for event "
                                + event.eventBlueId());
            }
        }

        private void requireSameHeader(CoordinationDispatchPlan supplied) {
            requireSameRequest(supplied);
            if (routeIndexGeneration != supplied.routeIndexGeneration()) {
                throw conflictingPlan();
            }
        }

        private void requireSamePlan(CoordinationDispatchPlan supplied) {
            requireSameHeader(supplied);
            if (!sameTargetStream(plan, supplied)) {
                throw conflictingPlan();
            }
        }

        private IllegalStateException conflictingPlan() {
            return new IllegalStateException(
                    "Conflicting canonical dispatch for event "
                            + event.eventBlueId());
        }

        private CoordinationDispatchSnapshot snapshot() {
            requireSealed();
            List<List<CoordinationDeliveryReceipt>> pageSource =
                    new AbstractList<List<CoordinationDeliveryReceipt>>() {
                @Override
                public List<CoordinationDeliveryReceipt> get(int pageIndex) {
                    List<IndexedSessionCandidates> targetPage =
                            pages.get(pageIndex).targets();
                    List<CoordinationDeliveryReceipt> receiptPage =
                            new ArrayList<CoordinationDeliveryReceipt>(
                                    targetPage.size());
                    for (IndexedSessionCandidates target : targetPage) {
                        receiptPage.add(receipts.get(target.sessionId())
                                .snapshot(
                                        event.eventBlueId(),
                                        target.sessionId()));
                    }
                    return receiptPage;
                }

                @Override
                public int size() { return pages.size(); }
            };
            return CoordinationDispatchSnapshot.fromReceiptPages(
                    plan, pageSource);
        }

        private void requireCheckpointable() {
            requireSealed();
            for (MutableReceipt receipt : receipts.values()) {
                if (receipt.status == CoordinationDeliveryStatus.IN_FLIGHT) {
                    throw new IllegalStateException(
                            "Cannot checkpoint an in-flight dispatch for "
                                    + event.eventBlueId());
                }
            }
        }

        private MutableDispatch copy() {
            CoordinationDispatchPlan header =
                    CoordinationDispatchPlan.fromPages(
                            event,
                            exactEventSubscriptionKeys,
                            sourceChannel,
                            routeIndexGeneration,
                            Collections
                                    .<List<IndexedSessionCandidates>>emptyList(),
                            maximumRootsPerChunk);
            MutableDispatch result = new MutableDispatch(
                    header, freezeToken);
            for (CoordinationDispatchPage page : pages) {
                result.appendPage(page.targets());
            }
            result.seal();
            for (Map.Entry<DocumentSessionId, MutableReceipt> entry
                    : receipts.entrySet()) {
                result.receipts.get(entry.getKey()).copyStateFrom(
                        entry.getValue());
            }
            return result;
        }

        private void requireUnsealed() {
            if (sealed()) {
                throw new IllegalStateException(
                        "Dispatch target plan is already sealed for "
                                + event.eventBlueId());
            }
        }

        private void requireSealed() {
            if (!sealed()) {
                throw new IllegalStateException(
                        "Dispatch target plan is not sealed for "
                                + event.eventBlueId());
            }
        }

        private static boolean sameTargetStream(
                CoordinationDispatchPlan left,
                CoordinationDispatchPlan right) {
            if (left.targetCount() != right.targetCount()) return false;
            java.util.Iterator<IndexedSessionCandidates> leftTargets =
                    left.targets().iterator();
            java.util.Iterator<IndexedSessionCandidates> rightTargets =
                    right.targets().iterator();
            while (leftTargets.hasNext() && rightTargets.hasNext()) {
                IndexedSessionCandidates a = leftTargets.next();
                IndexedSessionCandidates b = rightTargets.next();
                if (!a.sessionId().equals(b.sessionId())
                        || a.plannedEpoch() != b.plannedEpoch()
                        || !a.plannedRootBlueId().equals(
                                b.plannedRootBlueId())
                        || !a.subscriptionSnapshotIdentity().equals(
                                b.subscriptionSnapshotIdentity())
                        || !a.orderedOccurrenceKeys().equals(
                                b.orderedOccurrenceKeys())) {
                    return false;
                }
            }
            return !leftTargets.hasNext() && !rightTargets.hasNext();
        }
    }

    private static final class MutableReceipt {
        private final IndexedSessionCandidates target;
        private CoordinationDeliveryStatus status =
                CoordinationDeliveryStatus.PENDING;
        private int attemptCount;
        private Long resultingEpoch;
        private String resultingRootBlueId;
        private String transitionIdentity;
        private List<String> committedOutboxEventBlueIds =
                Collections.emptyList();
        private String failureClass;

        private MutableReceipt(IndexedSessionCandidates target) {
            this.target = Objects.requireNonNull(target, "target");
        }

        private void requireCompatible(CoordinationCommittedDelivery value) {
            if (!target.sessionId().equals(value.sessionId())
                    || target.plannedEpoch() != value.plannedEpoch()
                    || !target.plannedRootBlueId().equals(
                            value.plannedRootBlueId())) {
                throw new IllegalStateException(
                        "Committed delivery differs from its frozen target");
            }
        }

        private void requireSameTerminal(CoordinationCommittedDelivery value) {
            if (!Objects.equals(resultingEpoch,
                    Long.valueOf(value.resultingEpoch()))
                    || !Objects.equals(resultingRootBlueId,
                            value.resultingRootBlueId())
                    || !Objects.equals(transitionIdentity,
                            value.transitionIdentity())
                    || !committedOutboxEventBlueIds.equals(
                            value.rootOutboxEventBlueIds())) {
                throw new IllegalStateException(
                        "Committed delivery terminal evidence conflicts");
            }
        }

        private CoordinationDeliveryReceipt snapshot(
                String eventBlueId,
                DocumentSessionId sessionId) {
            return new CoordinationDeliveryReceipt(
                    eventBlueId,
                    sessionId,
                    status,
                    attemptCount,
                    target.plannedEpoch(),
                    target.plannedRootBlueId(),
                    target.subscriptionSnapshotIdentity(),
                    target.orderedOccurrenceKeys(),
                    resultingEpoch,
                    resultingRootBlueId,
                    transitionIdentity,
                    committedOutboxEventBlueIds,
                    failureClass);
        }

        private void copyStateFrom(MutableReceipt source) {
            MutableReceipt checked = Objects.requireNonNull(source, "source");
            if (!target.sessionId().equals(checked.target.sessionId())
                    || target.plannedEpoch()
                    != checked.target.plannedEpoch()
                    || !target.plannedRootBlueId().equals(
                            checked.target.plannedRootBlueId())
                    || !target.subscriptionSnapshotIdentity().equals(
                            checked.target.subscriptionSnapshotIdentity())
                    || !target.orderedOccurrenceKeys().equals(
                            checked.target.orderedOccurrenceKeys())) {
                throw new IllegalStateException(
                        "Cannot copy receipt across different frozen targets");
            }
            status = checked.status;
            attemptCount = checked.attemptCount;
            resultingEpoch = checked.resultingEpoch;
            resultingRootBlueId = checked.resultingRootBlueId;
            transitionIdentity = checked.transitionIdentity;
            committedOutboxEventBlueIds =
                    checked.committedOutboxEventBlueIds;
            failureClass = checked.failureClass;
        }
    }
}
