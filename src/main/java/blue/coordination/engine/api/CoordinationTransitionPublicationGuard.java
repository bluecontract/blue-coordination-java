package blue.coordination.engine.api;

/**
 * Side-effect-free host validation performed after transition execution and
 * before authoritative session, route-index, receipt, or outbox publication.
 *
 * <p>Throwing rejects publication. Immutable content admitted while building
 * the transition may remain safely deduplicated, but no mutable session state
 * is advanced.</p>
 */
@FunctionalInterface
public interface CoordinationTransitionPublicationGuard {

    /** Validates one complete transition before its session CAS. */
    void validate(CoordinationTransition transition);
}
