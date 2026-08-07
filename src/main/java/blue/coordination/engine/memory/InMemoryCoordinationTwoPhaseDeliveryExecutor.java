package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.CoordinationTransitionPublicationGuard;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.Objects;
import java.util.concurrent.locks.Lock;

/** In-memory two-phase adapter: parallel compute, short ordered publication. */
public final class InMemoryCoordinationTwoPhaseDeliveryExecutor
        implements CoordinationTwoPhaseDeliveryExecutor<
                InMemoryPreparedRootDelivery> {

    private final CoordinationProcessingEngine engine;
    private final InMemorySessionIndexPublisher publisher;
    private final InMemoryCoordinationSessionStore sessionStore;
    private final CoordinationTransitionPublicationGuard publicationGuard;
    private final Lock lifecycleReadLock;
    private final Runnable requireOpen;

    public InMemoryCoordinationTwoPhaseDeliveryExecutor(
            CoordinationProcessingEngine engine,
            InMemorySessionIndexPublisher publisher,
            InMemoryCoordinationSessionStore sessionStore,
            CoordinationTransitionPublicationGuard publicationGuard) {
        this(
                engine,
                publisher,
                sessionStore,
                publicationGuard,
                null,
                null);
    }

    InMemoryCoordinationTwoPhaseDeliveryExecutor(
            CoordinationProcessingEngine engine,
            InMemorySessionIndexPublisher publisher,
            InMemoryCoordinationSessionStore sessionStore,
            CoordinationTransitionPublicationGuard publicationGuard,
            Lock lifecycleReadLock,
            Runnable requireOpen) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.sessionStore = Objects.requireNonNull(
                sessionStore, "sessionStore");
        this.publicationGuard = Objects.requireNonNull(
                publicationGuard, "publicationGuard");
        if ((lifecycleReadLock == null) != (requireOpen == null)) {
            throw new IllegalArgumentException(
                    "Lifecycle lock and open check must be supplied together");
        }
        this.lifecycleReadLock = lifecycleReadLock;
        this.requireOpen = requireOpen;
    }

    @Override
    public InMemoryPreparedRootDelivery prepare(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            PrefetchPolicy prefetchPolicy) {
        enterLifecycle();
        try {
            return prepareGuarded(event, target, prefetchPolicy);
        } finally {
            exitLifecycle();
        }
    }

    private InMemoryPreparedRootDelivery prepareGuarded(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            PrefetchPolicy prefetchPolicy) {
        StoredCoordinationEvent checkedEvent = Objects.requireNonNull(
                event, "event");
        IndexedSessionCandidates checkedTarget = Objects.requireNonNull(
                target, "target");
        ManagedDocumentSnapshot current = engine.session(
                checkedTarget.sessionId());
        requireCurrent(current, checkedTarget);

        CoordinationProcessingPlan plan = engine.planIndexed(
                checkedTarget.sessionId(),
                checkedTarget.plannedEpoch(),
                checkedEvent,
                checkedTarget.orderedOccurrenceKeys(),
                Objects.requireNonNull(prefetchPolicy, "prefetchPolicy"));
        CoordinationTransition transition = engine.execute(plan);
        return new InMemoryPreparedRootDelivery(
                checkedEvent, checkedTarget, transition);
    }

    @Override
    public CoordinationCommittedDelivery commit(
            InMemoryPreparedRootDelivery prepared) {
        enterLifecycle();
        try {
            return commitGuarded(prepared);
        } finally {
            exitLifecycle();
        }
    }

    private CoordinationCommittedDelivery commitGuarded(
            InMemoryPreparedRootDelivery prepared) {
        InMemoryPreparedRootDelivery checked = Objects.requireNonNull(
                prepared, "prepared");
        publicationGuard.validate(checked.transition());
        DemoTransition committed = publisher.commitAndPublish(
                checked.transition());
        checked.recordCommittedTransition(committed);
        return sessionStore.committedDeliveries().require(
                checked.event().eventBlueId(),
                checked.target().sessionId());
    }

    private void enterLifecycle() {
        if (lifecycleReadLock == null) {
            return;
        }
        lifecycleReadLock.lock();
        boolean entered = false;
        try {
            requireOpen.run();
            entered = true;
        } finally {
            if (!entered) {
                lifecycleReadLock.unlock();
            }
        }
    }

    private void exitLifecycle() {
        if (lifecycleReadLock != null) {
            lifecycleReadLock.unlock();
        }
    }

    private static void requireCurrent(
            ManagedDocumentSnapshot current,
            IndexedSessionCandidates target) {
        if (current.currentEpoch() != target.plannedEpoch()
                || !current.currentRootBlueId().equals(
                        target.plannedRootBlueId())
                || !current.subscriptions().digest().equals(
                        target.subscriptionSnapshotIdentity())) {
            throw new IllegalStateException(
                    "Frozen route target is stale for "
                            + target.sessionId());
        }
    }
}
