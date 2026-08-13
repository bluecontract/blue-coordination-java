package blue.coordination.internal;

import blue.coordination.api.CoordinationMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the thread-safe diagnostic accumulator. */
final class EngineMetricsTest {
    @Test
    void absentMeasurementsReadAsZeroAndNamesAreValidated() {
        EngineMetrics metrics = new EngineMetrics();

        assertEquals(0L, metrics.counter("missing"));
        assertEquals(0L, metrics.phaseNanos("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.increment(" "));
        assertThrows(NullPointerException.class,
                () -> metrics.addNanos(null, 1L));
    }

    @Test
    void negativeCounterAndTimerDeltasFailClosed() {
        EngineMetrics metrics = new EngineMetrics();

        assertThrows(IllegalArgumentException.class,
                () -> metrics.add("work", -1L));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.addNanos("phase", -1L));
    }

    @Test
    void timedRecordsSuccessfulAndFailedWork() {
        EngineMetrics metrics = new EngineMetrics();

        assertEquals("done", metrics.timed("success", () -> "done"));
        assertThrows(IllegalStateException.class,
                () -> metrics.timed("failure", () -> {
                    throw new IllegalStateException("expected");
                }));

        assertTrue(metrics.phaseNanos("success") >= 0L);
        assertTrue(metrics.phaseNanos("failure") >= 0L);
    }

    @Test
    void snapshotsAreImmutableAndUnaffectedByLaterUpdates() {
        EngineMetrics metrics = new EngineMetrics();
        metrics.add("work", 2L);
        metrics.addNanos("phase", 3L);
        EngineMetrics.MetricsSnapshot snapshot = metrics.snapshot();
        metrics.increment("work");
        metrics.addNanos("phase", 4L);

        assertEquals(2L, snapshot.counters().get("work"));
        assertEquals(3L, snapshot.phaseNanos().get("phase"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.counters().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.phaseNanos().put("other", 1L));
        assertEquals(3L, metrics.counter("work"));
        assertEquals(7L, metrics.phaseNanos("phase"));
    }

    @Test
    void snapshotsProjectCanonicalCountersAndRetainInternalDeltas() {
        EngineMetrics metrics = new EngineMetrics();
        metrics.add("journal.entriesStoredWhole", 2L);
        metrics.add("routing.lookups", 3L);
        metrics.add("temporal.graphSnapshotsReused", 4L);
        metrics.add("temporal.graphReconciliations", 5L);
        metrics.add("documentStart.sessionsInitialized", 6L);
        metrics.add("deliveryReceiptsCommitted", 7L);
        metrics.add("process.embeddedEpochProcessCalls", 8L);
        metrics.add("temporal.childEpochsCommitted", 9L);
        metrics.add("process.documentRevisionsCommitted", 90L);
        metrics.add("embedding.parentEpochApplications", 10L);
        metrics.add("journal.historicalWindowsOpened", 12L);
        metrics.add("temporal.catchUpBarriersCreated", 11L);
        metrics.add("temporal.catchUpBarriersExtended", 20L);
        metrics.add("temporal.historicalEntriesReplayed", 13L);
        metrics.add("temporal.catchUpBarriersCompleted", 14L);
        metrics.add("diagnostic.internalDelta", 15L);

        Map<String, Long> snapshot = metrics.snapshot().counters();

        assertEquals(2L, snapshot.get("ENTRIES_STORED_WHOLE"));
        assertEquals(3L, snapshot.get("ROUTE_INDEX_LOOKUPS"));
        assertEquals(4L, snapshot.get("GRAPH_SNAPSHOTS_REUSED"));
        assertEquals(5L, snapshot.get("GRAPH_RECONCILIATIONS"));
        assertEquals(6L, snapshot.get("DOCUMENT_INITIALIZATIONS"));
        assertEquals(7L, snapshot.get("EXTERNAL_PROCESS_CALLS"));
        assertEquals(8L, snapshot.get("EMBEDDED_EPOCH_PROCESS_CALLS"));
        assertEquals(9L, snapshot.get("CHILD_EPOCHS_COMMITTED"));
        assertEquals(10L, snapshot.get("PARENT_EPOCH_APPLICATIONS"));
        assertEquals(12L, snapshot.get("HISTORICAL_WINDOWS_OPENED"));
        assertEquals(13L, snapshot.get("HISTORICAL_ENTRIES_REPLAYED"));
        assertEquals(11L, snapshot.get("CATCH_UP_BARRIERS_CREATED"));
        assertEquals(14L, snapshot.get("CATCH_UP_BARRIERS_COMPLETED"));
        assertEquals(15L, snapshot.get("diagnostic.internalDelta"));
        for (CoordinationMetrics.Counter counter
                : CoordinationMetrics.Counter.values()) {
            assertTrue(snapshot.containsKey(counter.name()),
                    () -> "Missing canonical counter " + counter);
        }
        assertEquals(0L, snapshot.get("REQUEST_FRAGMENTS"));
        assertEquals(0L, snapshot.get("FULL_ENVIRONMENT_SCANS"));
        assertEquals(0L, snapshot.get("POST_PROCESS_FULL_PROJECTIONS"));
    }

    @Test
    void forbiddenWorkSourcesCannotDisappearBehindDefaultZero() {
        EngineMetrics metrics = new EngineMetrics();
        Map<String, CoordinationMetrics.Counter> sources = Map.of(
                "temporal.unrelatedDocumentReads",
                CoordinationMetrics.Counter.UNRELATED_DOCUMENT_READS,
                "append.requestFragments",
                CoordinationMetrics.Counter.REQUEST_FRAGMENTS,
                "append.eventFragments",
                CoordinationMetrics.Counter.TIMELINE_ENTRY_FRAGMENTS,
                "layout.ordinaryNodeFragments",
                CoordinationMetrics.Counter.ORDINARY_NODE_FRAGMENTS,
                "temporal.fullEnvironmentScans",
                CoordinationMetrics.Counter.FULL_ENVIRONMENT_SCANS,
                "temporal.sourceReplaysPerParent",
                CoordinationMetrics.Counter.SOURCE_REPLAYS_PER_PARENT,
                "process.postProcessFullProjections",
                CoordinationMetrics.Counter.POST_PROCESS_FULL_PROJECTIONS,
                "temporal.parentProcessRerunsOnGraphRetry",
                CoordinationMetrics.Counter
                        .PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY,
                "temporal.childProcessRerunsOnParentRetry",
                CoordinationMetrics.Counter
                        .CHILD_PROCESS_RERUNS_ON_PARENT_RETRY);

        sources.forEach((source, ignored) -> metrics.increment(source));
        Map<String, Long> snapshot = metrics.publicSnapshot().counters();

        sources.values().forEach(counter -> assertEquals(
                1L, snapshot.get(counter.name()), counter.name()));
    }

    @Test
    void concurrentUpdatesAreNotLost() throws Exception {
        EngineMetrics metrics = new EngineMetrics();
        int workers = 8;
        int increments = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < increments; index++) {
                        metrics.increment("concurrent");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals((long) workers * increments,
                metrics.counter("concurrent"));
    }
}
