package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Indexed dispatch remains local with one target among 1,000 managed documents. */
@Tag("scenario")
final class ThousandDocumentLocalityScenarioTest {
    @Test
    void oneTargetDoesNotOpenUnrelatedDocumentsOrTimelines() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            for (int index = 1; index < 1_000; index++) {
                String id = "locality-unrelated-" + index;
                engine.timeline("examples/locality/" + index, id);
                engine.start(id, "documentId: " + id + "\nmarker: 0\n");
            }
            Timeline target = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.timeline("examples/clean-counter/bob", "bob");
            engine.start("counter", resource("examples/clean/counter.yaml"));
            assertEquals(1_000, engine.documentCount());

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            ProcessingDrainReceipt receipt = engine.appendAndDispatch(
                    target, Operation.yaml(
                            "increment", "aliceChannel", "amount: 1"));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(1, receipt.outcomes().size());
            assertEquals("counter", receipt.onlyOutcome().documentId().value());
            assertEquals(1L, work.counter("ROUTE_INDEX_LOOKUPS"));
            assertEquals(1L, work.counter("routing.rowsInspected"));
            assertEquals(1L, work.counter("EXTERNAL_PROCESS_CALLS"));
            assertEquals(0L, work.counter("HISTORICAL_WINDOWS_OPENED"));
            assertEquals(0L, work.counter("UNRELATED_DOCUMENT_READS"));
            assertNoGenericSplitting(work);
            assertEquals(1, engine.journalSize());
            assertEquals(1L, integer(engine, "counter", "/counter"));
        }
    }
}
