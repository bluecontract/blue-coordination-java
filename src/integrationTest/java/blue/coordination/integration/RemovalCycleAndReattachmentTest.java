package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RemovalCycleAndReattachmentTest {
    @Test
    void detachedParentStopsMovingAndReattachUsesFreshCursorWithoutReplay()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            long firstGeneration = activationGeneration(engine);
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.yaml(
                            "detachChild", "ownerChannel", "{}"));
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());

            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"));
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty(),
                    "removed inverse edge must not receive live revisions");
            int childHistorySize = engine.history(
                    "embedded-counter-A").size();

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(3L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(3L, work.counter("childRevisionApplications"),
                    "reattachment creates a fresh generation and applies "
                            + "initialization plus both known child epochs");
            assertEquals(0L, work.counter("childHistoricalProcessCalls"));
            assertTrue(activationGeneration(engine) > firstGeneration);
            assertEquals(childHistorySize,
                    engine.history("embedded-counter-A").size());
            assertEquals(1L, engine.history("embedded-counter-A").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count());
        }
    }

    @Test
    void directCycleFailsBeforeAnySessionLinkCursorOrReceiptPublishes()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String parentInitial = resource(
                    "examples/clean/embedded-state-parent.yaml");
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start("embedded-state-parent", parentInitial);
            var cycle = engine.append(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(parentInitial)));

            // when
            assertThrows(
                    IllegalStateException.class,
                    () -> engine.dispatch(cycle));

            // then
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
        try (TestEngine engine = TestEngine.create()) {
            // given
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
            engine.appendAndDispatch(firstTimeline, Operation.exact(
                    "attachChild", "ownerChannel",
                    engine.embeddedDocumentRequest(second)));
            engine.appendAndDispatch(secondTimeline, Operation.exact(
                    "attachChild", "ownerChannel",
                    engine.embeddedDocumentRequest(third)));

            var closingEdge = engine.append(
                    thirdTimeline,
                    Operation.exact(
                            "attachChild", "ownerChannel",
                            engine.embeddedDocumentRequest(first)));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            assertThrows(IllegalStateException.class,
                    () -> engine.dispatch(closingEdge));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
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

    private static long activationGeneration(TestEngine engine) {
        return engine.catchUpPlans().stream()
                .filter(plan -> plan.link().parentDocumentId().value().equals(
                        "embedded-state-parent"))
                .filter(plan -> plan.link().occurrencePath().equals("/child"))
                .mapToLong(plan -> plan.link().activationGeneration())
                .max().orElseThrow();
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
