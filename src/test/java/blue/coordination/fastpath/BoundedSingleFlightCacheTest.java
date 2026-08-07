package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BoundedSingleFlightCacheTest {
    @Test
    void concurrentDuplicateLoadsAreCoalescedExactlyOnce() throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(8, 1024L, String::length);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<Future<String>>();
            for (int index = 0; index < 8; index++) {
                results.add(workers.submit(() -> cache.getOrCompute("same", key -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return "value";
                })));
            }
            entered.await();
            release.countDown();
            for (Future<String> result : results) assertEquals("value", result.get());
            assertEquals(1, calls.get());
            assertEquals(1L, cache.metrics().loads());
            assertEquals(7L, cache.metrics().coalesced() + cache.metrics().hits());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void failedLoadDoesNotPoisonRetry() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(4, 64L, String::length);
        assertThrows(IllegalStateException.class,
                () -> cache.getOrCompute("key", ignored -> {
                    throw new IllegalStateException("transient");
                }));
        assertEquals("recovered", cache.getOrCompute("key", ignored -> "recovered"));
        assertEquals(2L, cache.metrics().loads());
        assertEquals(1L, cache.metrics().failures());
    }

    @Test
    void invalidatedInFlightLoadServesWaitersButIsNotRetained() throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(4, 64L, String::length);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() -> cache.getOrCompute(
                    "obsolete",
                    ignored -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return "value";
                    }));
            entered.await();

            assertEquals(1, cache.invalidateIf("obsolete"::equals));
            release.countDown();

            assertEquals("value", result.get());
            assertNull(cache.find("obsolete"));
            assertEquals(0, cache.metrics().entries());
            assertEquals(0L, cache.metrics().weight());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void weightAndEntryBoundsEvictEldestCompletedEntries() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(2, 7L, String::length);
        cache.getOrCompute("a", ignored -> "aaa");
        cache.getOrCompute("b", ignored -> "bbb");
        cache.getOrCompute("c", ignored -> "ccc");
        assertNull(cache.find("a"));
        assertEquals(2, cache.metrics().entries());
        assertEquals(1L, cache.metrics().evictions());
    }
}
