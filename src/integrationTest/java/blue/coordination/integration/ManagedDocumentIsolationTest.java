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
final class ManagedDocumentIsolationTest {
    @Test
    void sharedOperationExecutesOncePerManagedDocumentThenOneEpochPropagation()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
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

            // when
            engine.appendAndDispatch(
                    shared,
                    Operation.yaml("collide", "sharedChannel", "{}"));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
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
            assertEquals(3L, work.counter(
                    "process.referenceOnlyEventInputs"));
            assertEquals(0L, work.counter(
                    "POST_PROCESS_FULL_PROJECTIONS"));
            assertEquals(3L, work.counter(
                    "process.commitCompanionDeltasApplied"));
            assertEquals(0L, work.counter(
                    "layout.externalManagedChildMutationsRejected"));
            assertEquals(
                    engine.session("root-isolation-parent").layout().rootBlueId(),
                    engine.session("root-isolation-parent").layout()
                            .stored("/").blueId());
            assertEquals(
                    engine.session("root-isolation-child").layout().rootBlueId(),
                    engine.session("root-isolation-child").layout()
                            .stored("/").blueId());
            assertEquals(3L, work.counter(
                    "temporal.graphIdentityMatches"));
            assertEquals(2L, work.counter(
                    "temporal.graphIdentityOccurrencesCompared"));
            assertEquals(0L, work.counter(
                    "temporal.graphDeltaPreviews"));
            assertEquals(0L, work.counter(
                    "temporal.graphForwardBucketsUpdated"));
            assertEquals(0L, work.counter(
                    "temporal.graphReconciliations"));
            assertEquals(3L, work.counter(
                    "routing.surfacePublicationsSkipped"));
            assertEquals(0L, work.counter(
                    "routing.surfaceCompilations"));
            assertEquals(3L, work.counter("layout.plansReused"));
            assertEquals(0L, work.counter("layout.catalogCompilations"));
            assertEquals(3L, work.counter("GRAPH_SNAPSHOTS_REUSED"));
            assertNoGenericSplitting(work);
        }
    }
}
