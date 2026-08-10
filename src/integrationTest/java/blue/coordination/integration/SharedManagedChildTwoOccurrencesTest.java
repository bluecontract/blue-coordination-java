package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

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
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachTwice",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(child)));

            assertEquals("embedded-counter-A", engine.embeddedDocuments(
                    "shared-child-two-occurrences-parent").get("/left"));
            assertEquals("embedded-counter-A", engine.embeddedDocuments(
                    "shared-child-two-occurrences-parent").get("/right"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 3"));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

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
        }
    }
}
