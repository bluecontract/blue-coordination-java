package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.language.api.BlueCacheStats;

import java.util.List;
import java.util.Map;
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

    /** Reconstructs transient scheduling/index state from retained stores. */
    public void restartFromStores() {
        engine.restartFromStores();
    }

    /** Makes bounded historical reads defer without reporting false absence. */
    public void makeHistoricalUnavailable(String diagnostic) {
        engine.makeHistoricalUnavailable(diagnostic);
    }

    /** Restores the deterministic historical feeder after a test deferral. */
    public void makeHistoricalAvailable() {
        engine.makeHistoricalAvailable();
    }

    /** Makes bounded historical reads fail closed on invalid evidence. */
    public void invalidateHistoricalEvidence(String diagnostic) {
        engine.invalidateHistoricalEvidence(diagnostic);
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

    /** Raw diagnostic metrics for test deltas; never exposed by public API. */
    public MetricsSnapshot metricsSnapshot() {
        EngineMetrics.MetricsSnapshot snapshot = engine.metricsSnapshot();
        return new MetricsSnapshot(
                snapshot.counters(), snapshot.phaseNanos());
    }

    /** Returns immutable catch-up evidence without exposing mutable plans. */
    public List<CatchUpEvidence> catchUpEvidence() {
        return engine.catchUpEvidence().stream()
                .map(evidence -> new CatchUpEvidence(
                        evidence.parentDocumentId().value(),
                        evidence.childDocumentId().value(),
                        evidence.occurrencePath(),
                        evidence.appliedChildEpoch(),
                        evidence.status(),
                        evidence.activationGeneration()))
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
            String status,
            long activationGeneration) {
    }

    /** Immutable raw metrics used only by test-fixture consumers. */
    public record MetricsSnapshot(
            Map<String, Long> counters,
            Map<String, Long> phaseNanos) {
        public MetricsSnapshot {
            counters = Map.copyOf(Objects.requireNonNull(
                    counters, "counters"));
            phaseNanos = Map.copyOf(Objects.requireNonNull(
                    phaseNanos, "phaseNanos"));
        }
    }
}
