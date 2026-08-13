package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.SessionStatus;
import blue.language.api.BlueCacheStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.LongStream;

/**
 * Failure-injection and deep diagnostic controls published only in the test
 * fixtures artifact. Production consumers never receive this surface.
 */
public final class CoordinationTestControl {
    private final DefaultCoordinationEngine engine;
    private final List<TransitionTrace> transitionTraces = new ArrayList<>();

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

    /**
     * Simulates a pre-Round-11 retained READY marker for restart-repair tests.
     * The composite application-read proof remains authoritative.
     */
    public void forceLegacyReadyMarker(String documentId) {
        DocumentSession session = engine.session(Objects.requireNonNull(
                documentId, "documentId"));
        session.restoreCoordinationState(
                SessionStatus.READY,
                session.readyThrough(),
                session.epoch(),
                session.epoch());
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

    /** Starts a closed, in-memory trace of successful document transitions. */
    public void beginTransitionTrace() {
        transitionTraces.clear();
        engine.observeTransitions(this::recordTransition);
    }

    /** Returns the successful transitions observed since tracing began. */
    public List<TransitionTrace> transitionTrace() {
        return List.copyOf(transitionTraces);
    }

    private void recordTransition(
            SequentialDrainCoordinator.TransitionTrace trace) {
        Map<String, Long> phases = new LinkedHashMap<>();
        trace.after().phaseNanos().forEach((name, value) -> phases.put(
                name,
                value - trace.before().phaseNanos().getOrDefault(name, 0L)));
        transitionTraces.add(new TransitionTrace(
                trace.documentId().value(),
                trace.revision().epoch(),
                trace.revision().kind().name(),
                trace.revision().sourceEntry()
                        .map(entry -> entry.blueId()).orElse(null),
                trace.revision().causalEntryBlueId().orElse(null),
                phases,
                trace.totalNanos()));
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

    /**
     * Returns active occurrence identities and their committed embedded-input
     * receipts. This derives the receipt identity from the production binding
     * contract without exposing mutable graph or cursor objects.
     */
    public List<EmbeddedOccurrenceEvidence> embeddedOccurrenceEvidence() {
        return engine.catchUpEvidence().stream()
                .map(evidence -> {
                    String bindingId = SequentialDrainCoordinator.bindingId(
                            evidence.parentDocumentId(),
                            evidence.occurrencePath(),
                            evidence.activationGeneration());
                    List<String> committedReceipts = evidence.appliedChildEpoch()
                            < 0L
                            ? List.of()
                            : LongStream.rangeClosed(
                                            0L, evidence.appliedChildEpoch())
                                    .mapToObj(epoch ->
                                            SequentialDrainCoordinator
                                                    .embeddedTransitionReceiptId(
                                                            bindingId, epoch))
                                    .filter(engine.session(
                                            evidence.parentDocumentId().value())
                                            ::hasTransitionReceipt)
                                    .toList();
                    return new EmbeddedOccurrenceEvidence(
                            evidence.parentDocumentId().value(),
                            evidence.childDocumentId().value(),
                            evidence.occurrencePath(),
                            bindingId,
                            evidence.appliedChildEpoch(),
                            evidence.activationGeneration(),
                            committedReceipts);
                })
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

    /** Immutable binding/cursor/receipt evidence for one active occurrence. */
    public record EmbeddedOccurrenceEvidence(
            String parentDocumentId,
            String childDocumentId,
            String occurrencePath,
            String bindingId,
            long appliedChildEpoch,
            long activationGeneration,
            List<String> committedReceiptIds) {
        public EmbeddedOccurrenceEvidence {
            parentDocumentId = Objects.requireNonNull(
                    parentDocumentId, "parentDocumentId");
            childDocumentId = Objects.requireNonNull(
                    childDocumentId, "childDocumentId");
            occurrencePath = Objects.requireNonNull(
                    occurrencePath, "occurrencePath");
            bindingId = Objects.requireNonNull(bindingId, "bindingId");
            committedReceiptIds = List.copyOf(Objects.requireNonNull(
                    committedReceiptIds, "committedReceiptIds"));
        }
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

    /** Immutable test-only timing projection; production API stays closed. */
    public record TransitionTrace(
            String documentId,
            long epoch,
            String kind,
            String sourceEntryBlueId,
            String causalEntryBlueId,
            Map<String, Long> phaseNanos,
            long totalNanos) {
        public TransitionTrace {
            phaseNanos = Map.copyOf(Objects.requireNonNull(
                    phaseNanos, "phaseNanos"));
        }
    }
}
