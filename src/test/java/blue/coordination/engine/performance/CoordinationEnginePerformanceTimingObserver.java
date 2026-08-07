package blue.coordination.engine.performance;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Metric;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Phase;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Sample;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.SemanticFingerprint;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.processor.PlatformProcessingResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-invocation bridge from exact engine callbacks to one strict sample.
 *
 * <p>The bridge deliberately leaves physical values unavailable when the
 * engine has no authoritative counter. Scenario adapters may supply those
 * optional values from profilers, but must never infer them.</p>
 */
final class CoordinationEnginePerformanceTimingObserver
        implements CoordinationProcessingEngineObserver {

    private static final String NOT_OBSERVED =
            "not-observed-for-this-invocation";
    private static final String PROFILER_NOT_ATTACHED =
            "authoritative-profiler-not-attached";

    private final Map<Phase, Long> phaseNanos =
            new EnumMap<Phase, Long>(Phase.class);
    private final Map<Metric, Long> metrics =
            new EnumMap<Metric, Long>(Metric.class);

    @Override
    public synchronized void onPlanTiming(
            ProcessRequest request,
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordPhase(Phase.PLAN, elapsedNanos);
    }

    @Override
    public synchronized void onBundleLoadTiming(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle,
            long elapsedNanos) {
        recordPhase(Phase.BUNDLE_LOAD, elapsedNanos);
    }

    @Override
    public synchronized void onPlatformProcessTiming(
            CoordinationProcessingPlan plan,
            PlatformProcessingResult result,
            long elapsedNanos) {
        recordPhase(Phase.PROCESS, elapsedNanos);
    }

    @Override
    public synchronized void onSubscriptionAndFragmentTransitionTiming(
            CoordinationProcessingPlan plan,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            CoordinationFragmentTransition fragmentTransition,
            long elapsedNanos) {
        recordPhase(Phase.FRAGMENT_TRANSITION, elapsedNanos);
    }

    @Override
    public synchronized void onProcessComplete(
            CoordinationTransition transition) {
        LocalityDiagnostics locality = Objects.requireNonNull(
                transition, "transition").locality();
        recordMetric(
                Metric.PROVIDER_REQUEST_COUNT,
                locality.requestedBlueIds().size());
        recordMetric(Metric.BATCH_COUNT, locality.batchCount());
        recordMetric(Metric.FALLBACK_COUNT, locality.fallbackReadCount());
        recordMetric(Metric.LOADED_BYTES, locality.loadedBytes());
    }

    @Override
    public synchronized void onCommitTiming(
            CoordinationTransition transition,
            CommitOutcome outcome,
            long elapsedNanos) {
        recordPhase(Phase.COMMIT, elapsedNanos);
    }

    @Override
    public synchronized void onProcessAndCommitTiming(
            ProcessRequest request,
            CommitOutcome outcome,
            long elapsedNanos) {
        recordPhase(Phase.END_TO_END, elapsedNanos);
    }

    synchronized void recordEndToEnd(long elapsedNanos) {
        recordPhase(Phase.END_TO_END, elapsedNanos);
    }

    synchronized void recordAuthoritativeMetric(
            Metric metric,
            long value) {
        recordMetric(metric, value);
    }

    synchronized Sample sample(
            String datasetSha256,
            SemanticFingerprint semantics) {
        Sample.Builder builder = Sample.builder(datasetSha256, semantics);
        for (Phase phase : Phase.values()) {
            Long value = phaseNanos.get(phase);
            if (value == null) {
                builder.unavailable(phase, NOT_OBSERVED);
            } else {
                builder.phase(phase, value.longValue());
            }
        }
        for (Metric metric : Metric.values()) {
            Long value = metrics.get(metric);
            if (value == null) {
                builder.unavailable(metric, unavailableReason(metric));
            } else {
                builder.metric(metric, value.longValue());
            }
        }
        return builder.build();
    }

    synchronized void reset() {
        phaseNanos.clear();
        metrics.clear();
    }

    private void recordPhase(Phase phase, long elapsedNanos) {
        requireNonNegative(elapsedNanos, "elapsedNanos");
        phaseNanos.put(
                phase,
                addExact(
                        phaseNanos.get(phase),
                        elapsedNanos,
                        phase.id()));
    }

    private void recordMetric(Metric metric, long value) {
        requireNonNegative(value, "metric");
        metrics.put(
                metric,
                addExact(metrics.get(metric), value, metric.id()));
    }

    private static String unavailableReason(Metric metric) {
        if (metric == Metric.MATERIALIZED_NODE_COUNT
                || metric == Metric.ALLOCATION_BYTES
                || metric == Metric.RETAINED_HEAP_BYTES) {
            return PROFILER_NOT_ATTACHED;
        }
        if (metric == Metric.SELECTED_BODY_COUNT) {
            return "authoritative-selected-body-counter-not-exposed";
        }
        return NOT_OBSERVED;
    }

    private static long addExact(
            Long current,
            long value,
            String label) {
        long previous = current == null ? 0L : current.longValue();
        if (Long.MAX_VALUE - previous < value) {
            throw new IllegalStateException(
                    "Performance counter overflow for " + label);
        }
        return previous + value;
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
    }
}
