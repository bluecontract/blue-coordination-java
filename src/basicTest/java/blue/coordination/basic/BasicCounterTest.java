package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Minimal acceptance proof for the clean processor. */
final class BasicCounterTest {
    @Test
    void aliceAddsThreeAndBobSubtractsOneWithOneProcessCallPerEntry()
            throws Exception {
        try (BasicTestMetrics report = BasicTestMetrics.start(
                "clean-counter", "Clean Counter");
             BasicTestMetrics.MeasuredResource<BasicCoordinationEngine> managed =
                     report.manage(
                             "09 close environment",
                             report.measure(
                                     "01 start environment",
                                     BasicCoordinationEngine::create))) {
            BasicCoordinationEngine engine = managed.value();
            Timeline alice = report.measure(
                    "02 add Alice timeline",
                    () -> engine.timeline(
                            "examples/clean-counter/alice", "alice"));
            Timeline bob = report.measure(
                    "03 add Bob timeline",
                    () -> engine.timeline(
                            "examples/clean-counter/bob", "bob"));
            report.measure(
                    "04 start Counter",
                    () -> engine.start(
                            "counter",
                            resource("examples/clean/counter.yaml")));

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            report.measure(
                    "05 append and process Alice +3",
                    () -> engine.appendAndDispatch(
                            alice,
                            BasicOperation.of(
                                    "increment", "aliceChannel", "amount: 3")));
            report.measure(
                    "06 append and process Bob -1",
                    () -> engine.appendAndDispatch(
                            bob,
                            BasicOperation.of(
                                    "decrement", "bobChannel", "amount: 1")));
            EngineMetrics.MetricsSnapshot after = engine.metricsSnapshot();
            BasicEngineTestSupport.MetricDelta work = delta(before, after);

            report.measure("07 verify exact result", () -> {
                assertEquals(2L, integer(engine, "counter", "/counter"));
                assertEquals(2L, engine.session("counter").epoch());
                assertEquals(2, engine.journalSize());
                assertEquals(2L, work.counter(
                        "process.frozenContractsInvocations"));
                assertEquals(2L, work.counter(
                        "process.concreteOwnershipRootInputs"));
                assertEquals(0L, work.counter(
                        "process.referenceOnlyRootInputs"));
                assertEquals(2L, work.counter(
                        "process.referenceOnlyEventInputs"));
                assertEquals(0L, work.counter(
                        "process.concreteSubscriptionProjections"));
                assertEquals(2L, work.counter(
                        "process.commitCompanionDeltasApplied"));
                assertEquals(4L, work.counter(
                        "process.subscriptionIntervalsReused"));
                assertEquals(4L, work.counter(
                        "process.companionReplacementsRetained"));
                assertEquals(2L, work.counter(
                        "process.routingSurfaceReused"));
                assertEquals(0L, work.counter(
                        "process.routingSurfaceChanges"));
                assertEquals(
                        engine.session("counter").layout().rootBlueId(),
                        engine.session("counter").layout().stored("/").blueId());
                assertNoGenericSplitting(work);
            });
            report.detail(
                            "clean-counter-engine-work",
                            "08 publish engine metrics")
                    .counter("frozen Contracts PROCESS invocations",
                            work.counter("process.frozenContractsInvocations"))
                    .counter("routing surface reuses",
                            work.counter("process.routingSurfaceReused"))
                    .counter("generic request fragments",
                            work.counter("append.requestFragments"))
                    .counter("generic event fragments",
                            work.counter("append.eventFragments"));
            report.measure("08 publish engine metrics", () -> {
                // The detail section is published with the enclosing report.
            });
        }
    }
}
