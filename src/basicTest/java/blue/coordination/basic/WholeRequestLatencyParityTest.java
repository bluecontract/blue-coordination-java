package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.ExactNodeValue;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.payloadRequest;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole-request append parity with no matching autonomous Root. */
@Tag("performance")
final class WholeRequestLatencyParityTest {
    private static final int WARMUP_PAIRS = 50;
    private static final int MEASURED_PAIRS = 200;

    @Test
    void tinyAndPayNoteRequestsHaveOneIdenticalWholeAppendShape()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timeline timeline = engine.timeline(
                    "examples/append-only/alice", "alice");
            ExactNodeValue tiny = engine.exactRequest("amount: 1");
            ExactNodeValue payNote = engine.exactRequest(payloadRequest(
                    resource("examples/clean/package-paynote.yaml")));
            BasicOperation tinyOperation = BasicOperation.exact(
                    "ignored", "ownerChannel", tiny);
            BasicOperation payNoteOperation = BasicOperation.exact(
                    "ignored", "ownerChannel", payNote);

            for (int index = 0; index < WARMUP_PAIRS; index++) {
                if ((index & 1) == 0) {
                    engine.append(timeline, tinyOperation);
                    engine.append(timeline, payNoteOperation);
                } else {
                    engine.append(timeline, payNoteOperation);
                    engine.append(timeline, tinyOperation);
                }
            }

            LatencySeries tinyNanos = new LatencySeries();
            LatencySeries payNoteNanos = new LatencySeries();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            for (int index = 0; index < MEASURED_PAIRS; index++) {
                if ((index & 1) == 0) {
                    sample(engine, timeline, tinyOperation, tinyNanos);
                    sample(engine, timeline, payNoteOperation, payNoteNanos);
                } else {
                    sample(engine, timeline, payNoteOperation, payNoteNanos);
                    sample(engine, timeline, tinyOperation, tinyNanos);
                }
            }
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            print("tiny Counter request append", tinyNanos);
            print("PayNote-sized request append", payNoteNanos);
            assertEquals(MEASURED_PAIRS * 2L,
                    work.counter("requestsStoredWhole"));
            assertEquals(MEASURED_PAIRS * 2L,
                    work.counter("append.exactRequestsReused"));
            assertEquals(MEASURED_PAIRS * 2L,
                    work.counter("append.eventTemplateHits"));
            assertEquals(MEASURED_PAIRS * 2L,
                    work.counter("append.entriesBuilt"));
            assertEquals(MEASURED_PAIRS * 2L,
                    work.counter("append.journalOperations"));
            assertEquals(0L, work.counter("wholeObjectStore.reads"));
            assertEquals(0L, work.counter("routeTargets"));
            assertEquals(0L, work.counter("frozenProcessCalls"));
            assertEquals(0L, work.counter("requestSplitterCalls"));
            assertEquals(0L, work.counter("entrySplitterCalls"));
            assertEquals(0L, work.counter("ordinaryNodeSplitterCalls"));
            assertEquals(0L, work.counter("broadSubscriptionProjectionCalls"));
            assertNoGenericSplitting(work);

            if (Boolean.getBoolean("basic.strictPerformance")) {
                assertTrue(tinyNanos.p95Nanos() <= 5_000_000L,
                        () -> "tiny append p95=" + ms(
                                tinyNanos.p95Nanos()) + " ms");
                assertTrue(payNoteNanos.p95Nanos() <= 15_000_000L,
                        () -> "PayNote append p95=" + ms(
                                payNoteNanos.p95Nanos()) + " ms");
                assertTrue(payNoteNanos.p95Nanos()
                                <= tinyNanos.p95Nanos() * 5L,
                        "PayNote/tiny append p95 hard ratio exceeded");
            }
        }
    }

    private static void sample(
            BasicCoordinationEngine engine,
            Timeline timeline,
            BasicOperation operation,
            LatencySeries destination) {
        long started = System.nanoTime();
        engine.append(timeline, operation);
        destination.add(System.nanoTime() - started);
    }

    private static void print(String label, LatencySeries values) {
        System.out.printf(Locale.ROOT,
                "%-32s p50=%7.3f ms p95=%7.3f ms p99=%7.3f ms max=%7.3f ms n=%d%n",
                label,
                ms(values.medianNanos()),
                ms(values.p95Nanos()),
                ms(values.p99Nanos()),
                ms(values.maxNanos()),
                values.size());
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
