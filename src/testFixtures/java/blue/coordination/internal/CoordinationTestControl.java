package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.language.api.BlueCacheStats;

import java.util.List;
import java.util.Objects;

/**
 * Failure-injection and deep diagnostic controls published only in the test
 * fixtures artifact. Production consumers never receive this surface.
 */
public final class CoordinationTestControl {
    private final DefaultCoordinationEngine engine;

    private CoordinationTestControl(DefaultCoordinationEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Attaches controls to the production in-memory implementation. */
    public static CoordinationTestControl attach(CoordinationEngine engine) {
        if (!(Objects.requireNonNull(engine, "engine")
                instanceof DefaultCoordinationEngine implementation)) {
            throw new IllegalArgumentException(
                    "Test controls require the in-memory implementation");
        }
        return new CoordinationTestControl(implementation);
    }

    /** Injects exactly one failure at the named transactional boundary. */
    public void failOnceAt(FailurePoint point) {
        engine.failOnceAt(DefaultCoordinationEngine.FailurePoint.valueOf(
                Objects.requireNonNull(point, "point").name()));
    }

    /** Clears a pending failure injection. */
    public void clearFailureInjection() {
        engine.clearFailureInjection();
    }

    /** Returns whether a failure came from this deterministic fixture. */
    public boolean isInjectedFailure(Throwable failure) {
        return failure instanceof DefaultCoordinationEngine
                .InjectedFailureException;
    }

    /** Returns immutable Language cache evidence for performance campaigns. */
    public BlueCacheStats languageCacheStats() {
        return engine.languageCacheStats();
    }

    /** Returns immutable catch-up evidence without exposing mutable plans. */
    public List<CatchUpEvidence> catchUpEvidence() {
        return engine.catchUpPlans().stream()
                .map(plan -> new CatchUpEvidence(
                        plan.link().parentDocumentId().value(),
                        plan.link().childDocumentId().value(),
                        plan.link().occurrencePath(),
                        plan.link().appliedChildEpoch(),
                        plan.status().name()))
                .toList();
    }

    /** Transactional boundaries available to external acceptance tests. */
    public enum FailurePoint {
        BEFORE_FROZEN_PROCESS,
        AFTER_FROZEN_BEFORE_STAGE,
        AFTER_STAGING_CHILD_SESSION,
        AFTER_APPLYING_CHILD_REVISION,
        BEFORE_COMMIT_VALIDATION,
        AFTER_STATE_SWAP_BEFORE_RETURN
    }

    /** One stable read-only catch-up projection. */
    public record CatchUpEvidence(
            String parentDocumentId,
            String childDocumentId,
            String occurrencePath,
            long appliedChildEpoch,
            String status) {
    }
}
