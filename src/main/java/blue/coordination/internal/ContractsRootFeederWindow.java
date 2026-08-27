package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureResourceDemand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Durable progress model for one or more independent Root feeder lanes.
 *
 * <p>A journal event may select disconnected affected closures. A suspended
 * closure holds the ordered window for only its own public-Root lane; it does
 * not prevent a disconnected lane from reaching a later event. Completed
 * closures are retained as terminal progress and are never selected again
 * when the event is recaptured after resource acquisition.</p>
 *
 * <p>This class owns no scheduling semantics for cyclic components. It sees
 * each affected closure as one opaque Contracts invocation and delegates all
 * document/component scheduling to Contracts.</p>
 */
final class ContractsRootFeederWindow {
    private final DurableState durableState;
    private final Map<LaneId, PendingProgress> pendingByLane;
    private final Map<EventLaneKey, TerminalProgress> terminalByEventLane;
    private final Map<AttemptKey, AttemptTicket> selectedAttempts =
            new LinkedHashMap<>();
    private final Map<LaneId, ExternalOrderKey> terminalFrontierByLane;

    ContractsRootFeederWindow() {
        this(new DurableState());
    }

    ContractsRootFeederWindow(DurableState durableState) {
        this.durableState = Objects.requireNonNull(
                durableState, "durableState");
        this.pendingByLane = durableState.pendingByLane;
        this.terminalByEventLane = durableState.terminalByEventLane;
        this.terminalFrontierByLane =
                durableState.terminalFrontierByLane;
    }

    /** Selects only cohorts that are not behind an earlier lane-local block. */
    synchronized List<AttemptTicket> select(
            ContractsClosureAdapter.FrozenBatch batch) {
        ContractsClosureAdapter.FrozenBatch frozen = Objects.requireNonNull(
                batch, "batch");
        List<AttemptTicket> selected = new ArrayList<>();
        Set<LaneId> lanesInBatch = new LinkedHashSet<>();
        for (int index = 0; index < frozen.invocations().size(); index++) {
            ContractsClosureAdapter.CohortInvocation invocation =
                    frozen.invocations().get(index);
            LaneId lane = lane(invocation);
            if (!lanesInBatch.add(lane)) {
                throw new IllegalStateException(
                        "One frozen event selected a Root lane more than once: "
                                + lane);
            }
            AttemptTicket ticket = ticket(frozen, index, lane, invocation);
            EventLaneKey eventLane = ticket.eventLaneKey();
            if (terminalByEventLane.containsKey(eventLane)) {
                continue;
            }
            PendingProgress pending = pendingByLane.get(lane);
            if (pending != null) {
                int order = ticket.sourceOrder().compareTo(
                        pending.ticket().sourceOrder());
                if (order < 0) {
                    throw new IllegalStateException(
                            "Root lane encountered an event before its retained "
                                    + "resource barrier");
                }
                if (!pending.ticket().eventLaneKey().equals(eventLane)) {
                    continue;
                }
                if (!pending.ticket().invocationIdentity().equals(
                        ticket.invocationIdentity())) {
                    throw new IllegalStateException(
                            "NeedsResources retry changed invocation identity "
                                    + "for " + eventLane);
                }
            } else {
                ExternalOrderKey frontier = terminalFrontierByLane.get(lane);
                if (frontier != null
                        && ticket.sourceOrder().compareTo(frontier) <= 0) {
                    throw new IllegalStateException(
                            "Root lane attempted non-monotonic event progress "
                                    + ticket.sourceOrder());
                }
            }
            AttemptTicket duplicate = selectedAttempts.putIfAbsent(
                    ticket.attemptKey(), ticket);
            if (duplicate != null && !duplicate.equals(ticket)) {
                throw new IllegalStateException(
                        "Conflicting selected feeder attempt "
                                + ticket.attemptKey());
            }
            selected.add(ticket);
        }
        return List.copyOf(selected);
    }

    /** Records one adapter outcome and advances only that outcome's lane. */
    synchronized void record(
            AttemptTicket ticket,
            ContractsClosureAdapter.CohortOutcome outcome) {
        AttemptTicket selected = requireSelected(ticket);
        ContractsClosureAdapter.CohortOutcome actual = Objects.requireNonNull(
                outcome, "outcome");
        if (!selected.members().equals(actual.members())) {
            throw new IllegalArgumentException(
                    "Cohort outcome members disagree with the selected lane");
        }
        ClosureAttemptResult attempt = actual.attempt();
        if (!attempt.isComplete()) {
            recordNeedsResources(
                    selected,
                    actual.members(),
                    attempt.resourceDemands());
            return;
        }
        recordTerminal(
                selected,
                actual.members(),
                attempt.processResult().commits(),
                actual.published());
    }

    /** Releases one transient ticket which was selected but not executed. */
    synchronized void releaseUnexecuted(AttemptTicket ticket) {
        AttemptTicket selected = requireSelected(ticket);
        if (!selectedAttempts.remove(
                selected.attemptKey(), selected)) {
            throw new IllegalStateException(
                    "Unexecuted feeder ticket was not retained");
        }
    }

    synchronized void recordNeedsResources(
            AttemptTicket ticket,
            List<DocumentId> members,
            List<ClosureResourceDemand> resourceDemands) {
        AttemptTicket selected = requireSelected(ticket);
        requireMembers(selected, members);
        PendingProgress replacement = new PendingProgress(
                selected, resourceDemands);
        PendingProgress existing = pendingByLane.put(
                selected.lane(), replacement);
        if (existing != null
                && !existing.ticket().attemptKey().equals(
                        selected.attemptKey())) {
            throw new IllegalStateException(
                    "Root lane already has a different resource barrier");
        }
        selectedAttempts.remove(selected.attemptKey());
    }

    synchronized void recordTerminal(
            AttemptTicket ticket,
            List<DocumentId> members,
            boolean commits,
            boolean published) {
        AttemptTicket selected = requireSelected(ticket);
        requireMembers(selected, members);
        if (commits != published) {
            throw new IllegalStateException(commits
                    ? "A committing closure result was not published"
                    : "A non-committing closure result was published");
        }
        EventLaneKey eventLane = selected.eventLaneKey();
        TerminalProgress terminal = new TerminalProgress(
                selected, commits, published);
        TerminalProgress duplicate = terminalByEventLane.putIfAbsent(
                eventLane, terminal);
        if (duplicate != null && !duplicate.equals(terminal)) {
            throw new IllegalStateException(
                    "Conflicting terminal feeder progress for " + eventLane);
        }
        PendingProgress pending = pendingByLane.get(selected.lane());
        if (pending != null
                && pending.ticket().attemptKey().equals(
                        selected.attemptKey())) {
            pendingByLane.remove(selected.lane());
        }
        terminalFrontierByLane.merge(
                selected.lane(),
                selected.sourceOrder(),
                (left, right) -> left.compareTo(right) >= 0 ? left : right);
        selectedAttempts.remove(selected.attemptKey());
    }

    /** Whether every selected cohort for this exact capture is terminal. */
    synchronized boolean isTerminal(
            ContractsClosureAdapter.FrozenBatch batch) {
        ContractsClosureAdapter.FrozenBatch frozen = Objects.requireNonNull(
                batch, "batch");
        for (ContractsClosureAdapter.CohortInvocation invocation
                : frozen.invocations()) {
            LaneId lane = lane(invocation);
            EventLaneKey key = new EventLaneKey(
                    frozen.entry().blueId(),
                    frozen.entry().sourceOrderKey(),
                    lane);
            if (!terminalByEventLane.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    /** Canonical typed resource demands retained for each blocked Root lane. */
    synchronized Map<LaneId, List<ClosureResourceDemand>>
            requiredResourcesByLane() {
        Map<LaneId, List<ClosureResourceDemand>> result =
                new LinkedHashMap<>();
        pendingByLane.forEach((lane, progress) -> result.put(
                lane, progress.resourceDemands()));
        return Collections.unmodifiableMap(result);
    }

    /** Terminal event/lane progress retained across event recapture. */
    synchronized List<TerminalProgress> terminalProgress() {
        return List.copyOf(terminalByEventLane.values());
    }

    /** Restart-safe lane progress; in-flight execution tickets are excluded. */
    synchronized DurableState durableState() {
        return durableState;
    }

    private AttemptTicket requireSelected(AttemptTicket ticket) {
        AttemptTicket checked = Objects.requireNonNull(ticket, "ticket");
        AttemptTicket selected = selectedAttempts.get(checked.attemptKey());
        if (!checked.equals(selected)) {
            throw new IllegalArgumentException(
                    "Outcome does not belong to a selected feeder attempt");
        }
        return selected;
    }

    private static void requireMembers(
            AttemptTicket selected,
            List<DocumentId> members) {
        if (!selected.members().equals(Objects.requireNonNull(
                members, "members"))) {
            throw new IllegalArgumentException(
                    "Cohort outcome members disagree with the selected lane");
        }
    }

    private static AttemptTicket ticket(
            ContractsClosureAdapter.FrozenBatch batch,
            int cohortIndex,
            LaneId lane,
            ContractsClosureAdapter.CohortInvocation invocation) {
        return new AttemptTicket(
                batch.entry().blueId(),
                batch.entry().sourceOrderKey(),
                cohortIndex,
                lane,
                invocation.input().invocationIdentity(),
                invocation.members());
    }

    private static LaneId lane(
            ContractsClosureAdapter.CohortInvocation invocation) {
        List<DocumentId> publicRoots = invocation.input().snapshot()
                .publicRootDocumentIds().stream()
                .map(documentId -> DocumentId.of(documentId.value()))
                .toList();
        return publicRoots.isEmpty()
                ? LaneId.internal(invocation.members())
                : LaneId.publicRoots(publicRoots);
    }

    /** Stable feeder-lane identity; public Roots never expose container data. */
    record LaneId(boolean publicLane, List<DocumentId> roots) {
        LaneId {
            roots = Objects.requireNonNull(roots, "roots").stream()
                    .map(root -> Objects.requireNonNull(root, "root"))
                    .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                    .toList();
            if (roots.isEmpty()) {
                throw new IllegalArgumentException(
                        "A feeder lane must identify at least one document");
            }
            if (new LinkedHashSet<>(roots).size() != roots.size()) {
                throw new IllegalArgumentException(
                        "A feeder lane cannot repeat a document");
            }
        }

        static LaneId publicRoots(List<DocumentId> roots) {
            return new LaneId(true, roots);
        }

        static LaneId internal(List<DocumentId> members) {
            return new LaneId(false, members);
        }
    }

    /** One exact cohort invocation currently eligible for adapter execution. */
    record AttemptTicket(
            String entryBlueId,
            ExternalOrderKey sourceOrder,
            int cohortIndex,
            LaneId lane,
            String invocationIdentity,
            List<DocumentId> members) {
        AttemptTicket {
            entryBlueId = requireText(entryBlueId, "entryBlueId");
            sourceOrder = Objects.requireNonNull(sourceOrder, "sourceOrder");
            if (cohortIndex < 0) {
                throw new IllegalArgumentException(
                        "cohortIndex must be non-negative");
            }
            lane = Objects.requireNonNull(lane, "lane");
            invocationIdentity = requireText(
                    invocationIdentity, "invocationIdentity");
            members = Objects.requireNonNull(members, "members").stream()
                    .map(member -> Objects.requireNonNull(member, "member"))
                    .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                    .toList();
        }

        EventLaneKey eventLaneKey() {
            return new EventLaneKey(entryBlueId, sourceOrder, lane);
        }

        AttemptKey attemptKey() {
            return new AttemptKey(eventLaneKey(), invocationIdentity);
        }
    }

    /** Retained exact resource suspension for one lane. */
    record PendingProgress(
            AttemptTicket ticket,
            List<ClosureResourceDemand> resourceDemands) {
        PendingProgress {
            ticket = Objects.requireNonNull(ticket, "ticket");
            resourceDemands = List.copyOf(Objects.requireNonNull(
                    resourceDemands, "resourceDemands"));
            if (resourceDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "Pending progress must retain a typed resource demand");
            }
        }
    }

    /** Retained terminal completion, including deterministic non-commit. */
    record TerminalProgress(
            AttemptTicket ticket,
            boolean commits,
            boolean published) {
        TerminalProgress {
            ticket = Objects.requireNonNull(ticket, "ticket");
            if (commits != published) {
                throw new IllegalArgumentException(
                        "Terminal publication must agree with commit status");
            }
        }
    }

    private record EventLaneKey(
            String entryBlueId,
            ExternalOrderKey sourceOrder,
            LaneId lane) {
        private EventLaneKey {
            entryBlueId = requireText(entryBlueId, "entryBlueId");
            sourceOrder = Objects.requireNonNull(sourceOrder, "sourceOrder");
            lane = Objects.requireNonNull(lane, "lane");
        }
    }

    private record AttemptKey(
            EventLaneKey eventLane,
            String invocationIdentity) {
        private AttemptKey {
            eventLane = Objects.requireNonNull(eventLane, "eventLane");
            invocationIdentity = requireText(
                    invocationIdentity, "invocationIdentity");
        }
    }

    /**
     * Restart boundary for feeder progress.
     *
     * <p>Selected-but-unrecorded executions are deliberately not durable: a
     * restart retries them against Contracts/COW fences. Terminal and resource
     * progress is durable and therefore cannot be overtaken or re-driven.</p>
     */
    static final class DurableState {
        private final Map<LaneId, PendingProgress> pendingByLane;
        private final Map<EventLaneKey, TerminalProgress> terminalByEventLane;
        private final Map<LaneId, ExternalOrderKey> terminalFrontierByLane;

        DurableState() {
            this(new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>());
        }

        private DurableState(
                Map<LaneId, PendingProgress> pendingByLane,
                Map<EventLaneKey, TerminalProgress> terminalByEventLane,
                Map<LaneId, ExternalOrderKey> terminalFrontierByLane) {
            this.pendingByLane = pendingByLane;
            this.terminalByEventLane = terminalByEventLane;
            this.terminalFrontierByLane = terminalFrontierByLane;
        }

        synchronized DurableState copy() {
            return new DurableState(
                    new LinkedHashMap<>(pendingByLane),
                    new LinkedHashMap<>(terminalByEventLane),
                    new LinkedHashMap<>(terminalFrontierByLane));
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
