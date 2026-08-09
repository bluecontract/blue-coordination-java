package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** One autonomous child advances once and publishes one revision to each parent. */
final class SharedAutonomousChildTwoParentsTest {
    @Test
    void oneChildRevisionConvergesTwoParentsWithoutReprocessingTheChild()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline firstParentTimeline = engine.timeline(
                    "examples/embedded/parent-one", "bob-one");
            Timeline secondParentTimeline = engine.timeline(
                    "examples/embedded/parent-two", "bob-two");

            engine.start("embedded-counter-A", childInitial);
            engine.appendAndDispatch(
                    childTimeline,
                    BasicOperation.of(
                            "increment", "ownerChannel", "amount: 1"));
            engine.appendAndDispatch(
                    childTimeline,
                    BasicOperation.of(
                            "increment", "ownerChannel", "amount: 1"));

            engine.start(
                    "embedded-parent-one",
                    parentDefinition(
                            "embedded-parent-one",
                            "examples/embedded/parent-one",
                            "bob-one"));
            engine.appendAndDispatch(
                    firstParentTimeline,
                    BasicOperation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            engine.start(
                    "embedded-parent-two",
                    parentDefinition(
                            "embedded-parent-two",
                            "examples/embedded/parent-two",
                            "bob-two"));
            engine.appendAndDispatch(
                    secondParentTimeline,
                    BasicOperation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            assertEquals(2L, integer(
                    engine, "embedded-parent-one", "/child/counter"));
            assertEquals(2L, integer(
                    engine, "embedded-parent-two", "/child/counter"));
            int childHistoryBefore = engine.history("embedded-counter-A").size();
            int firstParentHistoryBefore = engine.history(
                    "embedded-parent-one").size();
            int secondParentHistoryBefore = engine.history(
                    "embedded-parent-two").size();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.appendAndDispatch(
                    childTimeline,
                    BasicOperation.of(
                            "increment", "ownerChannel", "amount: 5"));
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(7L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(7L, integer(
                    engine, "embedded-parent-one", "/child/counter"));
            assertEquals(7L, integer(
                    engine, "embedded-parent-two", "/child/counter"));
            assertEquals(childHistoryBefore + 1,
                    engine.history("embedded-counter-A").size());
            assertEquals(firstParentHistoryBefore + 1,
                    engine.history("embedded-parent-one").size());
            assertEquals(secondParentHistoryBefore + 1,
                    engine.history("embedded-parent-two").size());
            assertEquals(1L, work.counter(
                    "process.frozenContractsInvocations"),
                    "the child source operation executes once");
            assertEquals(2L, work.counter(
                    "catchUp.parentRevisionApplications"));
            assertEquals(2L, work.counter("childRevisionApplications"));
            assertEquals(0L, work.counter("childHistoricalProcessCalls"));
        }
    }

    private static String parentDefinition(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return resource("examples/clean/embedded-state-parent.yaml")
                .replace("documentId: embedded-state-parent",
                        "documentId: " + documentId)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: " + timelineId)
                .replace("accountId: bob", "accountId: " + actorId);
    }
}
