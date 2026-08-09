package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Parent and child may share an operation without double-processing the child. */
final class AutonomousRootIsolationTest {
    @Test
    void sharedOperationExecutesOncePerAutonomousRootThenOneRevisionPropagation()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline shared = engine.timeline(
                    "examples/root-isolation/shared", "alice");
            engine.start(
                    "root-isolation-parent",
                    resource("examples/clean/root-isolation-parent.yaml"));
            engine.appendAndDispatch(
                    shared,
                    Operation.exact(
                            "attachChild",
                            "sharedChannel",
                            engine.embeddedDocumentRequest(resource(
                                    "examples/clean/root-isolation-child.yaml"))));

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            engine.appendAndDispatch(
                    shared,
                    Operation.yaml("collide", "sharedChannel", "{}"));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(1L, integer(
                    engine, "root-isolation-parent", "/rootCount"));
            assertEquals(1L, integer(
                    engine, "root-isolation-child", "/childCount"));
            assertEquals(1L, integer(
                    engine,
                    "root-isolation-parent",
                    "/child/childCount"));
            assertEquals(2L, integer(
                    engine,
                    "root-isolation-parent",
                    "/childRevisionApplications"));
            assertEquals(3L, work.counter(
                    "process.frozenContractsInvocations"),
                    "parent external + child external + parent revision");
            assertEquals(3L, work.counter(
                    "process.concreteOwnershipRootInputs"));
            assertEquals(0L, work.counter(
                    "process.referenceOnlyRootInputs"));
            assertEquals(3L, work.counter(
                    "process.referenceOnlyEventInputs"));
            assertEquals(0L, work.counter(
                    "process.concreteSubscriptionProjections"));
            assertEquals(3L, work.counter(
                    "process.commitCompanionDeltasApplied"));
            assertEquals(0L, work.counter(
                    "layout.externalAutonomousChildMutationsRejected"));
            assertEquals(
                    engine.session("root-isolation-parent").layout().rootBlueId(),
                    engine.session("root-isolation-parent").layout()
                            .stored("/").blueId());
            assertEquals(
                    engine.session("root-isolation-child").layout().rootBlueId(),
                    engine.session("root-isolation-child").layout()
                            .stored("/").blueId());
            assertEquals(0L, work.nanos(
                    "process.reconstructEmbeddedOnlyRoot"));
            assertEquals(0L, work.nanos(
                    "process.refreshChangedSubscriptionSurface"));
            assertNoGenericSplitting(work);
        }
    }
}
