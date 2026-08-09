package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.SessionStatus;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RemovalCycleAndReattachmentTest {
    @Test
    void detachedParentStopsMovingAndReattachConsumesOnlyNewRevision()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            engine.appendAndDispatch(
                    childTimeline,
                    BasicOperation.of(
                            "increment", "ownerChannel", "amount: 1"));

            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            engine.appendAndDispatch(
                    parentTimeline,
                    BasicOperation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            engine.appendAndDispatch(
                    parentTimeline,
                    BasicOperation.of(
                            "detachChild", "ownerChannel", "{}"));
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());

            engine.appendAndDispatch(
                    childTimeline,
                    BasicOperation.of(
                            "increment", "ownerChannel", "amount: 2"));
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty(),
                    "removed inverse edge must not receive live revisions");

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            engine.appendAndDispatch(
                    parentTimeline,
                    BasicOperation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());
            assertEquals(3L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(1L, work.counter("childRevisionApplications"),
                    "reattachment resumes after the detached cursor");
            assertEquals(0L, work.counter("childHistoricalProcessCalls"));
        }
    }

    @Test
    void directCycleFailsBeforeAnySessionLinkCursorOrReceiptPublishes()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            String parentInitial = resource(
                    "examples/clean/embedded-state-parent.yaml");
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start("embedded-state-parent", parentInitial);
            var cycle = engine.append(
                    parentTimeline,
                    BasicOperation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(parentInitial)));

            assertThrows(
                    IllegalStateException.class,
                    () -> engine.dispatch(cycle));
            assertEquals(0L, engine.session(
                    "embedded-state-parent").epoch());
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status());
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());
        }
    }

    @Test
    void threeRootCycleFailsBeforeAttemptedEdgeOrReceiptPublishes()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            String template = resource(
                    "examples/clean/embedded-state-parent.yaml");
            String first = parent(template, "a");
            String second = parent(template, "b");
            String third = parent(template, "c");
            engine.start("embedded-state-parent-a", first);
            engine.start("embedded-state-parent-b", second);
            engine.start("embedded-state-parent-c", third);
            Timeline firstTimeline = engine.timeline(
                    "examples/embedded/state-parent-a", "bob-a");
            Timeline secondTimeline = engine.timeline(
                    "examples/embedded/state-parent-b", "bob-b");
            Timeline thirdTimeline = engine.timeline(
                    "examples/embedded/state-parent-c", "bob-c");
            engine.appendAndDispatch(firstTimeline, BasicOperation.exact(
                    "attachChild", "ownerChannel",
                    engine.embeddedDocumentRequest(second)));
            engine.appendAndDispatch(secondTimeline, BasicOperation.exact(
                    "attachChild", "ownerChannel",
                    engine.embeddedDocumentRequest(third)));

            var closingEdge = engine.append(
                    thirdTimeline,
                    BasicOperation.exact(
                            "attachChild", "ownerChannel",
                            engine.embeddedDocumentRequest(first)));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            assertThrows(IllegalStateException.class,
                    () -> engine.dispatch(closingEdge));
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent-c").isEmpty());
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent-c").status());
            assertEquals(0L, engine.session(
                    "embedded-state-parent-c").epoch());
            assertEquals(0L, work.counter("deliveryReceiptsCommitted"));
            assertEquals(0L, work.counter(
                    "revisionApplicationReceiptsCommitted"));
        }
    }

    private static String parent(String template, String suffix) {
        return template
                .replace("embedded-state-parent",
                        "embedded-state-parent-" + suffix)
                .replace("examples/embedded/state-parent",
                        "examples/embedded/state-parent-" + suffix)
                .replace("accountId: bob", "accountId: bob-" + suffix);
    }
}
