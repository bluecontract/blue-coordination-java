package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** One child transition is reused across two occurrences in the same parent. */
final class SharedManagedChildTwoOccurrencesTest {
    @Test
    void oneDirectChildProcessAdvancesBothOccurrenceCursors()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String child = resource("examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/two-occurrences-parent", "bob");
            engine.start("embedded-counter-A", child);
            engine.start(
                    "shared-child-two-occurrences-parent",
                    resource("examples/clean/"
                            + "shared-child-two-occurrences-parent.yaml"));
            EngineMetrics.MetricsSnapshot beforeAttachment =
                    engine.metricsSnapshot();
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachTwice",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(child)));
            EngineTestSupport.MetricDelta attachment = delta(
                    beforeAttachment, engine.metricsSnapshot());

            assertEquals("embedded-counter-A", engine.embeddedDocuments(
                    "shared-child-two-occurrences-parent").get("/left"));
            assertEquals("embedded-counter-A", engine.embeddedDocuments(
                    "shared-child-two-occurrences-parent").get("/right"));
            assertEquals(1L, engine.history("embedded-counter-A").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count());
            assertEquals(2L, engine.history(
                            "shared-child-two-occurrences-parent").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind
                            .EMBEDDED_REVISION_APPLICATION)
                    .count(),
                    "each occurrence applies child initialization once");
            assertEquals(2L, attachment.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(2L, attachment.counter(
                    "temporal.parentEpochApplications"));
            assertOccurrenceCursors(engine, 0L);
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 3"));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(3L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(3L, integer(
                    engine,
                    "shared-child-two-occurrences-parent",
                    "/left/counter"));
            assertEquals(3L, integer(
                    engine,
                    "shared-child-two-occurrences-parent",
                    "/right/counter"));
            assertEquals(1L, work.counter("temporal.externalProcessCalls"));
            assertEquals(2L, work.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(2L, work.counter(
                    "temporal.parentEpochApplications"));
            assertOccurrenceCursors(engine, 1L);
        }
    }

    private static void assertOccurrenceCursors(
            TestEngine engine,
            long expectedChildEpoch) {
        List<CatchUpPlan> plans = engine.catchUpPlans().stream()
                .filter(plan -> plan.link().parentDocumentId().value().equals(
                        "shared-child-two-occurrences-parent"))
                .filter(plan -> plan.link().childDocumentId().value().equals(
                        "embedded-counter-A"))
                .sorted((left, right) -> left.link().occurrencePath()
                        .compareTo(right.link().occurrencePath()))
                .toList();

        assertEquals(List.of("/left", "/right"), plans.stream()
                .map(plan -> plan.link().occurrencePath())
                .toList());
        assertEquals(List.of(expectedChildEpoch, expectedChildEpoch),
                plans.stream()
                        .map(plan -> plan.link().appliedChildEpoch())
                        .toList(),
                "each occurrence owns an independent child-epoch cursor");
        assertEquals(List.of(
                        CatchUpPlan.Status.COMPLETE,
                        CatchUpPlan.Status.COMPLETE),
                plans.stream().map(CatchUpPlan::status).toList());
    }
}
