package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;

/**
 * Separates expensive, mutation-free Root preparation from the short
 * authoritative publication step.
 *
 * <p>Implementations must make {@link #prepare} side-effect free with respect
 * to sessions, route indexes, outboxes, delivery receipts and externally
 * visible fragment state. Prepared values may be computed concurrently for
 * distinct sessions. {@link #commit} is called in frozen target order and is
 * the only method allowed to publish authoritative state.</p>
 *
 * @param <P> an immutable, exact-epoch-bound prepared delivery
 */
public interface CoordinationTwoPhaseDeliveryExecutor<P> {

    P prepare(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            PrefetchPolicy prefetchPolicy);

    CoordinationCommittedDelivery commit(P prepared);

    /**
     * Called only when prepared work is discarded before commit. The default
     * is appropriate for immutable heap-only preparations.
     */
    default void discard(P prepared) {
        // No resources by default.
    }
}
