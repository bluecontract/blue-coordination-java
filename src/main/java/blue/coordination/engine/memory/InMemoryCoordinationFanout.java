package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryReceipt;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.spi.CoordinationSubscriptionIndex;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.language.processor.ExternalOrderKey;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/**
 * Operation-neutral all-Root fan-out with frozen targets and exact resume.
 *
 * <p>Public constructors freeze the supplied index generation directly and
 * rely on the executor's exact session-revision check to reject a stale target.
 * Environment-owned factories additionally open that generation through the
 * combined session/index publication boundary.</p>
 */
public final class InMemoryCoordinationFanout {

    private final CoordinationSubscriptionIndex subscriptionIndex;
    private final TargetCursorSource targetCursorSource;
    private final InMemoryCoordinationDispatchLedger ledger;
    private final CoordinationIndexedDeliveryExecutor executor;
    private final BoundedCoordinationRootScheduler<?> parallelScheduler;
    private final CoordinationCommittedDeliveryProbe committedDeliveryProbe;

    public InMemoryCoordinationFanout(
            CoordinationSubscriptionIndex subscriptionIndex,
            InMemoryCoordinationDispatchLedger ledger,
            CoordinationIndexedDeliveryExecutor executor) {
        this(subscriptionIndex, ledger, executor,
                CoordinationCommittedDeliveryProbe.none());
    }

    public InMemoryCoordinationFanout(
            CoordinationSubscriptionIndex subscriptionIndex,
            InMemoryCoordinationDispatchLedger ledger,
            CoordinationIndexedDeliveryExecutor executor,
            CoordinationCommittedDeliveryProbe committedDeliveryProbe) {
        this(
                subscriptionIndex,
                ledger,
                executor,
                committedDeliveryProbe,
                null,
                Objects.requireNonNull(
                        subscriptionIndex,
                        "subscriptionIndex")::openCandidates);
    }

    InMemoryCoordinationFanout(
            CoordinationSubscriptionIndex subscriptionIndex,
            InMemoryCoordinationDispatchLedger ledger,
            CoordinationIndexedDeliveryExecutor executor,
            CoordinationCommittedDeliveryProbe committedDeliveryProbe,
            TargetCursorSource targetCursorSource) {
        this(
                subscriptionIndex,
                ledger,
                executor,
                committedDeliveryProbe,
                null,
                targetCursorSource);
    }

    private InMemoryCoordinationFanout(
            CoordinationSubscriptionIndex subscriptionIndex,
            InMemoryCoordinationDispatchLedger ledger,
            CoordinationIndexedDeliveryExecutor executor,
            CoordinationCommittedDeliveryProbe committedDeliveryProbe,
            BoundedCoordinationRootScheduler<?> parallelScheduler,
            TargetCursorSource targetCursorSource) {
        this.subscriptionIndex = Objects.requireNonNull(
                subscriptionIndex, "subscriptionIndex");
        this.targetCursorSource = Objects.requireNonNull(
                targetCursorSource, "targetCursorSource");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.executor = executor;
        this.parallelScheduler = parallelScheduler;
        if ((executor == null) == (parallelScheduler == null)) {
            throw new IllegalArgumentException(
                    "Exactly one delivery execution mode is required");
        }
        this.committedDeliveryProbe = Objects.requireNonNull(
                committedDeliveryProbe, "committedDeliveryProbe");
    }

    /**
     * Creates a fan-out whose expensive Root preparations may overlap while
     * authoritative publication remains in frozen canonical target order.
     */
    public static <P> InMemoryCoordinationFanout parallel(
            CoordinationSubscriptionIndex subscriptionIndex,
            InMemoryCoordinationDispatchLedger ledger,
            BoundedCoordinationRootScheduler<P> scheduler,
            CoordinationCommittedDeliveryProbe committedDeliveryProbe) {
        return new InMemoryCoordinationFanout(
                subscriptionIndex,
                ledger,
                null,
                committedDeliveryProbe,
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(
                        subscriptionIndex,
                        "subscriptionIndex")::openCandidates);
    }

    static <P> InMemoryCoordinationFanout parallel(
            CoordinationSubscriptionIndex subscriptionIndex,
            InMemoryCoordinationDispatchLedger ledger,
            BoundedCoordinationRootScheduler<P> scheduler,
            CoordinationCommittedDeliveryProbe committedDeliveryProbe,
            TargetCursorSource targetCursorSource) {
        return new InMemoryCoordinationFanout(
                subscriptionIndex,
                ledger,
                null,
                committedDeliveryProbe,
                Objects.requireNonNull(scheduler, "scheduler"),
                targetCursorSource);
    }

    public CoordinationDispatchSnapshot dispatch(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            int maximumRootsPerChunk,
            PrefetchPolicy prefetchPolicy) {
        StoredCoordinationEvent checkedEvent = Objects.requireNonNull(
                event, "event");
        List<String> checkedKeys = Objects.requireNonNull(
                exactEventSubscriptionKeys,
                "exactEventSubscriptionKeys");
        String checkedSource = requireText(sourceChannel, "sourceChannel");
        PrefetchPolicy checkedPolicy = Objects.requireNonNull(
                prefetchPolicy, "prefetchPolicy");
        synchronized (ledger.dispatchMonitor(checkedEvent.eventBlueId())) {
            String dispatchIdentity = existingOrFreeze(
                    checkedEvent,
                    checkedKeys,
                    checkedSource,
                    maximumRootsPerChunk);
            return executeRemaining(dispatchIdentity, checkedPolicy);
        }
    }

    /** Resumes only from the immutable plan already stored in the ledger. */
    public CoordinationDispatchSnapshot resume(
            String dispatchIdentity,
            PrefetchPolicy prefetchPolicy) {
        String checkedIdentity = requireText(
                dispatchIdentity, "dispatchIdentity");
        PrefetchPolicy checkedPolicy = Objects.requireNonNull(
                prefetchPolicy, "prefetchPolicy");
        synchronized (ledger.dispatchMonitor(checkedIdentity)) {
            return executeRemaining(checkedIdentity, checkedPolicy);
        }
    }

    public InMemoryCoordinationDispatchLedger ledger() { return ledger; }

    private CoordinationDispatchSnapshot executeRemaining(
            String dispatchIdentity,
            PrefetchPolicy prefetchPolicy) {
        StoredCoordinationEvent event = ledger.storedEvent(dispatchIdentity);
        int maximumRootsPerChunk = ledger.maximumRootsPerChunk(
                dispatchIdentity);
        int pageCount = ledger.frozenPageCount(dispatchIdentity);
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            List<IndexedSessionCandidates> page =
                    ledger.frozenTargetPage(dispatchIdentity, pageIndex);
            if (page.size() > maximumRootsPerChunk) {
                throw new IllegalStateException(
                        "Frozen target store returned an oversized page");
            }
            if (parallelScheduler != null) {
                executeParallelPage(
                        dispatchIdentity,
                        event,
                        page,
                        prefetchPolicy,
                        parallelScheduler);
                continue;
            }
            for (IndexedSessionCandidates target : page) {
                CoordinationDeliveryReceipt receipt = ledger.receipt(
                        dispatchIdentity, target.sessionId());
                if (receipt.committed()) {
                    continue;
                }
                Optional<CoordinationCommittedDelivery> authoritative =
                        committedDeliveryProbe.committedDelivery(
                                event, target.sessionId());
                if (authoritative.isPresent()) {
                    ledger.recoverCommitted(
                            event.eventBlueId(),
                            target.sessionId(),
                            authoritative.get());
                    continue;
                }

                CoordinationDeliveryAdmission admission =
                        ledger.beginAttempt(
                                event.eventBlueId(), target.sessionId());
                try {
                    CoordinationCommittedDelivery committed = executor.deliver(
                            event, target, prefetchPolicy);
                    requireEvent(event, committed);
                    ledger.commit(admission, committed);
                } catch (RuntimeException failure) {
                    try {
                        Optional<CoordinationCommittedDelivery> afterFailure =
                                committedDeliveryProbe.committedDelivery(
                                        event, target.sessionId());
                        if (afterFailure.isPresent()) {
                            ledger.recoverCommitted(
                                    event.eventBlueId(),
                                    target.sessionId(),
                                    afterFailure.get());
                            continue;
                        }
                    } catch (RuntimeException reconciliationFailure) {
                        if (reconciliationFailure != failure) {
                            failure.addSuppressed(reconciliationFailure);
                        }
                    }
                    ledger.fail(admission, failure);
                    throw new CoordinationFanoutException(
                            target.sessionId(),
                            ledger.require(event.eventBlueId()),
                            failure);
                } catch (Error fatal) {
                    ledger.fail(admission, fatal);
                    throw fatal;
                }
            }
        }
        return ledger.require(event.eventBlueId());
    }

    private <P> void executeParallelPage(
            String dispatchIdentity,
            StoredCoordinationEvent event,
            List<IndexedSessionCandidates> page,
            PrefetchPolicy prefetchPolicy,
            BoundedCoordinationRootScheduler<P> scheduler) {
        List<IndexedSessionCandidates> remaining = new ArrayList<>();
        for (IndexedSessionCandidates target : page) {
            CoordinationDeliveryReceipt receipt = ledger.receipt(
                    dispatchIdentity, target.sessionId());
            if (receipt.committed()) {
                continue;
            }
            Optional<CoordinationCommittedDelivery> authoritative =
                    committedDeliveryProbe.committedDelivery(
                            event, target.sessionId());
            if (authoritative.isPresent()) {
                ledger.recoverCommitted(
                        event.eventBlueId(),
                        target.sessionId(),
                        authoritative.get());
            } else {
                remaining.add(target);
            }
        }
        List<BoundedCoordinationRootScheduler.Result<P>> scheduled =
                scheduler.schedule(event, remaining, prefetchPolicy);
        for (int index = 0; index < scheduled.size(); index++) {
            BoundedCoordinationRootScheduler.Result<P> result =
                    scheduled.get(index);
            IndexedSessionCandidates target = result.target();
            CoordinationDeliveryAdmission admission = null;
            try {
                result.awaitPrepared();
                admission = ledger.beginAttempt(
                        event.eventBlueId(), target.sessionId());
                CoordinationCommittedDelivery committed = result.commit();
                requireEvent(event, committed);
                ledger.commit(admission, committed);
            } catch (RuntimeException failure) {
                if (admission == null) {
                    admission = ledger.beginAttempt(
                            event.eventBlueId(), target.sessionId());
                }
                Optional<CoordinationCommittedDelivery> recovered =
                        reconcileAfterFailure(
                                event, target, failure);
                if (recovered.isPresent()) {
                    result.settleCommittedAfterReconciliation(
                            recovered.get());
                    continue;
                }
                ledger.fail(admission, failure);
                discardFrom(scheduled, index);
                throw new CoordinationFanoutException(
                        target.sessionId(),
                        ledger.require(event.eventBlueId()),
                        failure);
            } catch (Error fatal) {
                if (admission == null) {
                    admission = ledger.beginAttempt(
                            event.eventBlueId(), target.sessionId());
                }
                ledger.fail(admission, fatal);
                discardFrom(scheduled, index);
                throw fatal;
            }
        }
    }

    private Optional<CoordinationCommittedDelivery> reconcileAfterFailure(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            RuntimeException failure) {
        try {
            Optional<CoordinationCommittedDelivery> authoritative =
                    committedDeliveryProbe.committedDelivery(
                            event, target.sessionId());
            if (!authoritative.isPresent()) {
                return Optional.empty();
            }
            ledger.recoverCommitted(
                    event.eventBlueId(),
                    target.sessionId(),
                    authoritative.get());
            return authoritative;
        } catch (RuntimeException reconciliationFailure) {
            if (reconciliationFailure != failure) {
                failure.addSuppressed(reconciliationFailure);
            }
            return Optional.empty();
        }
    }

    private static <P> void discardFrom(
            List<BoundedCoordinationRootScheduler.Result<P>> scheduled,
            int first) {
        for (int index = first; index < scheduled.size(); index++) {
            BoundedCoordinationRootScheduler.Result<P> result =
                    scheduled.get(index);
            try {
                result.discard();
            } catch (RuntimeException ignored) {
                // The primary preparation/commit failure remains authoritative.
            }
        }
    }

    private String existingOrFreeze(
            StoredCoordinationEvent event,
            List<String> keys,
            String sourceChannel,
            int maximumRootsPerChunk) {
        if (ledger.containsSealedDispatch(event.eventBlueId())) {
            ledger.requireSameDispatchRequest(
                    event,
                    keys,
                    sourceChannel,
                    maximumRootsPerChunk);
            return event.eventBlueId();
        }
        try (CoordinationTargetCursor cursor =
                targetCursorSource.openCandidates(
                        keys, sourceChannel, event.orderKey())) {
            InMemoryCoordinationDispatchLedger.FreezeAdmission admission =
                    ledger.beginFreeze(
                            event,
                            keys,
                            sourceChannel,
                            cursor.generation(),
                            maximumRootsPerChunk);
            if (admission.reusedSealedPlan()) {
                ledger.requireSameDispatchRequest(
                        event,
                        keys,
                        sourceChannel,
                        maximumRootsPerChunk);
                return event.eventBlueId();
            }
            boolean sealed = false;
            try {
                while (!cursor.exhausted()) {
                    List<IndexedSessionCandidates> page = cursor.nextPage(
                            maximumRootsPerChunk);
                    if (page.isEmpty() && !cursor.exhausted()) {
                        throw new IllegalStateException(
                                "Route cursor made no progress");
                    }
                    if (!page.isEmpty()) {
                        ledger.appendFrozenPage(admission, page);
                    }
                }
                ledger.sealFreeze(admission);
                sealed = true;
                return event.eventBlueId();
            } finally {
                if (!sealed) {
                    ledger.abortFreeze(admission);
                }
            }
        }
    }

    private static void requireEvent(
            StoredCoordinationEvent event,
            CoordinationCommittedDelivery committed) {
        if (!event.eventBlueId().equals(committed.eventBlueId())) {
            throw new IllegalStateException(
                    "Executor committed evidence for another event");
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }

    /** Opens one immutable target generation at the configured boundary. */
    @FunctionalInterface
    interface TargetCursorSource {
        CoordinationTargetCursor openCandidates(
                List<String> exactEventSubscriptionKeys,
                String sourceChannel,
                ExternalOrderKey eventOrderKey);
    }
}
