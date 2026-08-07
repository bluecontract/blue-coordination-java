package blue.coordination.engine.performance;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.CacheState;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.CellKey;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.ComparisonMode;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Metric;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Phase;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Profile;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Sample;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Scenario;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.ScenarioAdapter;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.SemanticFingerprint;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.processor.PlatformProcessingResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for strict, non-estimated engine performance evidence. */
final class CoordinationEnginePerformanceEvidenceTest {

    private static final String ADAPTER_PROPERTY =
            "coordination.performance.adapter";
    private static final String SEMANTIC_GATE_PROPERTY =
            "coordination.performance.semanticGatesGreen";
    private static final String RECEIPT_PROPERTY =
            "coordination.performance.receipt";

    @Test
    void shouldDeclareTheExactNineByThreeByTwoMatrix() {
        // given
        List<CellKey> cells =
                CoordinationEnginePerformanceHarness.requiredCells();

        // when
        Set<String> identities = new LinkedHashSet<String>();
        for (CellKey cell : cells) {
            identities.add(cell.id());
        }

        // then
        assertEquals(9, Scenario.values().length);
        assertEquals(3, ComparisonMode.values().length);
        assertEquals(2, CacheState.values().length);
        assertEquals(54, cells.size());
        assertEquals(cells.size(), identities.size());
    }

    @Test
    void shouldCalculateNearestRankP50P95AndP99() {
        // given
        List<Long> samples = new ArrayList<Long>();
        for (long value = 100L; value >= 1L; value--) {
            samples.add(value);
        }

        // when
        long p50 = CoordinationEnginePerformanceHarness.percentile(
                samples, 50.0d);
        long p95 = CoordinationEnginePerformanceHarness.percentile(
                samples, 95.0d);
        long p99 = CoordinationEnginePerformanceHarness.percentile(
                samples, 99.0d);

        // then
        assertEquals(50L, p50);
        assertEquals(95L, p95);
        assertEquals(99L, p99);
    }

    @Test
    void shouldRequireEveryMeasurementOrAnExplicitUnavailableReason() {
        // given
        Sample.Builder incomplete = Sample.builder(
                "dataset-sha256", fingerprint("stable"));
        for (Phase phase : Phase.values()) {
            incomplete.phase(phase, 1L);
        }
        for (Metric metric : Metric.values()) {
            if (metric != Metric.RETAINED_HEAP_BYTES) {
                incomplete.metric(metric, 1L);
            }
        }

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                incomplete::build);

        // then
        assertTrue(failure.getMessage().contains("RETAINED_HEAP_BYTES"));
    }

    @Test
    void shouldAcceptExplicitUnavailabilityWithoutEstimatingAValue() {
        // given
        Sample.Builder complete = Sample.builder(
                "dataset-sha256", fingerprint("stable"));
        for (Phase phase : Phase.values()) {
            complete.unavailable(phase, "not-observed");
        }
        for (Metric metric : Metric.values()) {
            complete.unavailable(metric, "not-observed");
        }

        // when
        Sample sample = complete.build();

        // then
        assertTrue(sample.phaseNanos().isEmpty());
        assertEquals(Phase.values().length,
                sample.unavailablePhases().size());
        assertTrue(sample.metrics().isEmpty());
        assertEquals(Metric.values().length,
                sample.unavailableMetrics().size());
    }

    @Test
    void shouldRejectSemanticDriftAcrossRepresentationsAndCacheStates() {
        // given
        Profile profile = profile(0, 1);
        ScenarioAdapter drifting = new ScenarioAdapter() {
            @Override
            public void warmUp(CellKey cell, int iteration) {
            }

            @Override
            public Sample measure(CellKey cell, int iteration) {
                boolean drift = cell.scenario() == Scenario.SIMPLE_ROOT_EVENT
                        && cell.mode()
                        == ComparisonMode.CURRENT_ROOT_COMPATIBILITY
                        && cell.cache() == CacheState.WARM;
                return measuredSample(
                        cell,
                        iteration,
                        drift ? 2L : 1L);
            }
        };

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> CoordinationEnginePerformanceHarness.capture(
                        profile, drifting));

        // then
        assertTrue(failure.getMessage().contains("Semantic or dataset drift"));
    }

    @Test
    void shouldKeepComparableSyntheticSamplesFreeOfSpeedupClaims()
            throws Exception {
        // given
        Profile profile = profile(1, 2);
        ScenarioAdapter stable = new ScenarioAdapter() {
            @Override
            public void warmUp(CellKey cell, int iteration) {
            }

            @Override
            public Sample measure(CellKey cell, int iteration) {
                return measuredSample(cell, iteration, 1L);
            }
        };

        // when
        Map<String, Object> receipt =
                CoordinationEnginePerformanceHarness.capture(
                        profile, stable);

        // then
        assertEquals("verified", receipt.get("status"));
        assertEquals(Boolean.TRUE, receipt.get("performanceReady"));
        assertEquals("verified", receipt.get("semanticEquivalence"));
        assertEquals(Boolean.TRUE, receipt.get("comparisonEligible"));
        assertEquals(Collections.emptyList(), receipt.get("speedupClaims"));
        assertEquals(54L, matrix(receipt).get("completedCells"));
    }

    @Test
    void shouldMeasureTheRealSimpleScenarioAcrossEveryModeAndCacheState()
            throws Exception {
        // given
        ScenarioAdapter adapter =
                new RealCoordinationEnginePerformanceScenarioAdapter();
        List<Sample> samples = new ArrayList<Sample>();

        // when
        for (ComparisonMode mode : ComparisonMode.values()) {
            for (CacheState cache : CacheState.values()) {
                samples.add(adapter.measure(
                        new CellKey(
                                Scenario.SIMPLE_ROOT_EVENT,
                                mode,
                                cache),
                        0));
            }
        }

        // then
        SemanticFingerprint expected = samples.get(0).semantics();
        for (Sample sample : samples) {
            assertEquals(expected, sample.semantics());
            assertTrue(sample.metrics().containsKey(
                    Metric.SELECTED_BODY_COUNT));
            assertTrue(sample.unavailableMetrics().containsKey(
                    Metric.MATERIALIZED_NODE_COUNT));
            assertTrue(sample.metrics().containsKey(Metric.ALLOCATION_BYTES)
                    ^ sample.unavailableMetrics().containsKey(
                    Metric.ALLOCATION_BYTES));
            assertTrue(sample.unavailableMetrics().containsKey(
                    Metric.RETAINED_HEAP_BYTES));
        }
    }

    @Test
    void shouldExposeBinaryCompatibleDefaultTimingCallbacks()
            throws Exception {
        // given
        Class<CoordinationProcessingEngineObserver> observer =
                CoordinationProcessingEngineObserver.class;

        // when
        List<Method> callbacks = Arrays.asList(
                observer.getMethod(
                        "onPlanTiming",
                        ProcessRequest.class,
                        CoordinationProcessingPlan.class,
                        long.class),
                observer.getMethod(
                        "onBundleLoadTiming",
                        CoordinationProcessingPlan.class,
                        LoadedProcessingBundle.class,
                        long.class),
                observer.getMethod(
                        "onPlatformProcessTiming",
                        CoordinationProcessingPlan.class,
                        PlatformProcessingResult.class,
                        long.class),
                observer.getMethod(
                        "onSubscriptionAndFragmentTransitionTiming",
                        CoordinationProcessingPlan.class,
                        CoordinationSubscriptionUpdate.class,
                        CoordinationFragmentTransition.class,
                        long.class),
                observer.getMethod(
                        "onCommitTiming",
                        CoordinationTransition.class,
                        CommitOutcome.class,
                        long.class),
                observer.getMethod(
                        "onProcessAndCommitTiming",
                        ProcessRequest.class,
                        CommitOutcome.class,
                        long.class));

        // then
        for (Method callback : callbacks) {
            assertTrue(callback.isDefault(), callback.getName());
        }
    }

    @Test
    void shouldPublishAnExplicitSameRunReceiptWithoutPrematureMeasurement()
            throws Exception {
        // given
        Profile profile = Profile.fromSystemProperties();
        String adapterClass = System.getProperty(ADAPTER_PROPERTY);
        boolean semanticGatesGreen = Boolean.parseBoolean(
                System.getProperty(SEMANTIC_GATE_PROPERTY, "false"));

        // when
        Map<String, Object> receipt;
        if (adapterClass == null || adapterClass.trim().isEmpty()) {
            receipt = CoordinationEnginePerformanceHarness
                    .unavailableReceipt(
                            profile,
                            "scenario-adapter-not-configured; "
                                    + "measurements-were-not-run");
        } else if (!semanticGatesGreen) {
            receipt = CoordinationEnginePerformanceHarness
                    .unavailableReceipt(
                            profile,
                            "semantic-gates-not-confirmed; "
                                    + "scenario-adapter-was-not-loaded");
        } else {
            receipt = CoordinationEnginePerformanceHarness.capture(
                    profile,
                    CoordinationEnginePerformanceHarness.loadAdapter(
                            adapterClass));
        }
        String target = System.getProperty(RECEIPT_PROPERTY);
        if (target != null && !target.trim().isEmpty()) {
            Path receiptPath = Paths.get(target);
            CoordinationEnginePerformanceHarness.write(
                    receiptPath, receipt);
        }

        // then
        assertEquals(CoordinationEnginePerformanceHarness.SCHEMA,
                receipt.get("schema"));
        assertEquals(54L, matrix(receipt).get("requiredCells"));
        assertEquals(Collections.emptyList(), receipt.get("speedupClaims"));
        if (!semanticGatesGreen
                || adapterClass == null
                || adapterClass.trim().isEmpty()) {
            assertEquals("unavailable", receipt.get("status"));
            assertEquals(Boolean.FALSE, receipt.get("performanceReady"));
            assertEquals(0L, matrix(receipt).get("completedCells"));
            assertFalse((Boolean) receipt.get("comparisonEligible"));
            assertTrue(cells(receipt).stream().allMatch(cell ->
                    "not-executed".equals(cell.get("status"))
                            && explicitlyUnavailable(
                            castMap(cell.get("phases")),
                            Phase.values().length)
                            && explicitlyUnavailable(
                            castMap(cell.get("metrics")),
                            Metric.values().length)));
        }
    }

    private static Sample measuredSample(
            CellKey cell,
            int iteration,
            long semanticGas) {
        Sample.Builder builder = Sample.builder(
                "dataset-" + cell.scenario().id(),
                fingerprint(cell.scenario().id(), semanticGas));
        for (Phase phase : Phase.values()) {
            builder.phase(phase, phase.ordinal() + iteration + 1L);
        }
        for (Metric metric : Metric.values()) {
            builder.metric(metric, metric.ordinal() + iteration + 1L);
        }
        return builder.build();
    }

    private static SemanticFingerprint fingerprint(String identity) {
        return fingerprint(identity, 1L);
    }

    private static SemanticFingerprint fingerprint(
            String identity,
            long gas) {
        return new SemanticFingerprint(
                "success",
                "root-" + identity,
                "root-value-" + identity,
                "events-" + identity,
                gas,
                "trace-" + identity,
                "checkpoints-" + identity,
                "subscriptions-" + identity);
    }

    private static Profile profile(int warmups, int measurements) {
        return new Profile(
                "test-run",
                "coordination-commit",
                "language-commit",
                "bex-commit",
                "coordination-source-sha256",
                "dependency-lock-sha256",
                "dataset-generator",
                "semantic-environment",
                warmups,
                measurements,
                Collections.<String, Object>singletonMap(
                        "machine", "test"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> matrix(
            Map<String, Object> receipt) {
        return (Map<String, Object>) receipt.get("matrix");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cells(
            Map<String, Object> receipt) {
        return (List<Map<String, Object>>) receipt.get("cells");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static boolean explicitlyUnavailable(
            Map<String, Object> inventory,
            int expectedSize) {
        if (inventory.size() != expectedSize) {
            return false;
        }
        for (Object value : inventory.values()) {
            Map<String, Object> evidence = castMap(value);
            if (!"unavailable".equals(evidence.get("status"))
                    || !(evidence.get("reason") instanceof String)
                    || ((String) evidence.get("reason")).isEmpty()
                    || !Collections.emptyList().equals(
                    evidence.get("samples"))) {
                return false;
            }
        }
        return true;
    }
}
