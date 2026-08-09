package blue.coordination.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
