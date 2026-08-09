package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares one selected workflow with a three-document type chain containing
 * twenty unrelated workflows at every level.
 */
@Tag("performance")
final class WorkflowInheritanceScalingTest {
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 12;

    @Test
    void sixtyInheritedUnrelatedWorkflowsDoNotCreateHostLinearWork()
            throws Exception {
        Measurement one = measure(false);
        Measurement sixty = measure(true);

        print("one effective operation", one);
        print("61 effective operations", sixty);

        assertEquals(1, one.operationCount());
        assertEquals(61, sixty.operationCount());
        assertEquals(SAMPLES, one.processCalls());
        assertEquals(SAMPLES, sixty.processCalls());
        assertEquals(0L, one.hostWork().counter("layout.catalogCompilations"));
        assertEquals(0L, sixty.hostWork().counter("layout.catalogCompilations"));
        assertEquals(SAMPLES, one.hostWork().counter(
                "process.routingSurfaceReused"));
        assertEquals(SAMPLES, sixty.hostWork().counter(
                "process.routingSurfaceReused"));
        assertEquals(SAMPLES, one.hostWork().counter(
                "process.commitCompanionDeltasApplied"));
        assertEquals(SAMPLES, sixty.hostWork().counter(
                "process.commitCompanionDeltasApplied"));
        assertEquals(0L, one.hostWork().counter(
                "process.concreteSubscriptionProjections"));
        assertEquals(0L, sixty.hostWork().counter(
                "process.concreteSubscriptionProjections"));
        assertNoGenericSplitting(one.hostWork());
        assertNoGenericSplitting(sixty.hostWork());

        assertEquals(0L, one.hostWork().counter(
                "workflowBodiesScannedOnHotPath"));
        assertEquals(0L, sixty.hostWork().counter(
                "workflowBodiesScannedOnHotPath"));

        if (Boolean.getBoolean("basic.strictPerformance")) {
            assertTrue(one.route().p95Nanos() <= 1_000_000L);
            assertTrue(sixty.route().p95Nanos() <= 1_000_000L);
            assertTrue(one.host().p95Nanos() <= 40_000_000L,
                    () -> "1-workflow host p95="
                            + millis(one.host().p95Nanos()) + " ms");
            assertTrue(sixty.host().p95Nanos() <= 90_000_000L,
                    () -> "61-workflow host p95="
                            + millis(sixty.host().p95Nanos()) + " ms");
            long hostDelta = Math.abs(
                    sixty.host().p95Nanos() - one.host().p95Nanos());
            assertTrue(hostDelta <= 60_000_000L,
                    () -> "1-vs-61 host p95 delta="
                            + millis(hostDelta) + " ms; frozen floors are "
                            + millis(one.frozen().p95Nanos()) + "/"
                            + millis(sixty.frozen().p95Nanos()) + " ms");
        }
    }

    static Measurement measure(boolean deep) throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            WorkflowInheritanceFixture.RegisteredHierarchy hierarchy = deep
                    ? WorkflowInheritanceFixture.registerThreeByTwenty(engine)
                    : WorkflowInheritanceFixture.registerOneWorkflow(engine);
            String documentId = deep ? "workflow-sixty" : "workflow-one";
            Timeline alice = engine.timeline(
                    "examples/workflow-scale/alice", "alice");
            if (hierarchy.inheritanceProbeYaml() != null) {
                engine.start(
                        "workflow-inheritance-probe",
                        hierarchy.inheritanceProbeYaml());
            }
            engine.start(documentId, hierarchy.instanceYaml());
            assertEquals(
                    hierarchy.effectiveOperationCount(),
                    engine.session(documentId)
                            .layout()
                            .routingSurface()
                            .definitions()
                            .size(),
                    "The measured Root must contain the real frozen effective "
                            + "operation surface");
            if (hierarchy.inheritanceProbeYaml() != null) {
                assertEquals(
                        hierarchy.effectiveOperationCount(),
                        engine.session("workflow-inheritance-probe")
                                .layout()
                                .routingSurface()
                                .definitions()
                                .size(),
                        "The admitted 3x20 type hierarchy must resolve to the "
                                + "same real operation surface");
            }

            for (int index = 0; index < WARMUPS; index++) {
                engine.appendAndDispatch(
                        alice,
                        BasicOperation.of(
                                "selected", "benchmarkChannel", "{}"));
            }
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            LatencySeries complete = new LatencySeries();
            LatencySeries route = new LatencySeries();
            LatencySeries frozen = new LatencySeries();
            LatencySeries host = new LatencySeries();
            for (int index = 0; index < SAMPLES; index++) {
                var entry = engine.append(
                        alice,
                        BasicOperation.of(
                                "selected", "benchmarkChannel", "{}"));
                EngineMetrics.MetricsSnapshot sampleBefore =
                        engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.dispatch(entry);
                long elapsed = System.nanoTime() - started;
                BasicEngineTestSupport.MetricDelta sampleWork = delta(
                        sampleBefore, engine.metricsSnapshot());
                long routeNanos = sampleWork.nanos("process.routeLookup");
                long frozenNanos = sampleWork.nanos("process.frozen");
                complete.add(elapsed);
                route.add(routeNanos);
                frozen.add(frozenNanos);
                host.add(Math.max(0L,
                        elapsed - routeNanos - frozenNanos));
            }
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());
            assertEquals(
                    WARMUPS + SAMPLES,
                    BasicEngineTestSupport.integer(
                            engine, documentId, "/counter"));
            return new Measurement(
                    hierarchy.effectiveOperationCount(),
                    complete,
                    route,
                    frozen,
                    host,
                    Math.toIntExact(work.counter(
                            "process.frozenContractsInvocations")),
                    work);
        }
    }

    private static void print(String label, Measurement measurement) {
        System.out.printf(Locale.ROOT,
                "%-24s complete p95=%8.3f ms frozen=%8.3f ms host=%7.3f ms route=%6.3f ms ops=%d%n",
                label,
                millis(measurement.complete().p95Nanos()),
                millis(measurement.frozen().p95Nanos()),
                millis(measurement.host().p95Nanos()),
                millis(measurement.route().p95Nanos()),
                measurement.operationCount());
        System.out.printf(Locale.ROOT,
                "  host avg: beforeFrozen=%7.3f ms afterFrozen=%7.3f ms commit=%6.3f ms total=%7.3f ms%n",
                millis(measurement.hostWork().nanos(
                        "process.hostBeforeFrozen") / SAMPLES),
                millis(measurement.hostWork().nanos(
                        "process.hostAfterFrozen") / SAMPLES),
                millis(measurement.hostWork().nanos(
                        "transaction.commit") / SAMPLES),
                millis(measurement.hostWork().nanos(
                        "process.total") / SAMPLES));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    record Measurement(
            int operationCount,
            LatencySeries complete,
            LatencySeries route,
            LatencySeries frozen,
            LatencySeries host,
            int processCalls,
            BasicEngineTestSupport.MetricDelta hostWork) {
    }
}
