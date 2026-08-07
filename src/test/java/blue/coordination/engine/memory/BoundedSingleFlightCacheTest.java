package blue.coordination.engine.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BoundedSingleFlightCacheTest {

    @Test
    void compilesOneValueOnceUnderConcurrentContention() throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(8);
        AtomicInteger compilations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<Future<String>>();
            for (int index = 0; index < 32; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.compute("entry", ignored -> {
                        compilations.incrementAndGet();
                        return "compiled";
                    });
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("compiled", future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, compilations.get());
        assertEquals(1, cache.size());
    }

    @Test
    void failedCompilationIsEvictedAndCanBeRetried() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(2);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> cache.compute(
                "entry",
                ignored -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("injected");
                }));

        assertEquals("ok", cache.compute(
                "entry",
                ignored -> {
                    attempts.incrementAndGet();
                    return "ok";
                }));
        assertEquals(2, attempts.get());
    }

    @Test
    void evictsCompletedLeastRecentlyUsedEntriesAtTheHardBound() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(2);
        cache.compute("a", key -> key);
        cache.compute("b", key -> key);
        cache.compute("a", key -> "unexpected");
        cache.compute("c", key -> key);
        assertEquals(2, cache.size());

        AtomicInteger recompiled = new AtomicInteger();
        assertEquals("b2", cache.compute("b", ignored -> {
            recompiled.incrementAndGet();
            return "b2";
        }));
        assertEquals(1, recompiled.get());
    }

    @Test
    void shouldEvictByRetainedWeightInDeterministicAccessOrder() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        8, 6L, String::length);
        cache.compute("a", ignored -> "aa");
        cache.compute("b", ignored -> "bbb");
        cache.compute("a", ignored -> "unexpected");

        cache.compute("c", ignored -> "ccc");

        assertEquals(2, cache.size());
        assertEquals(5L, cache.retainedWeight());
        AtomicInteger recompiled = new AtomicInteger();
        assertEquals("b", cache.compute("b", ignored -> {
            recompiled.incrementAndGet();
            return "b";
        }));
        assertEquals(1, recompiled.get());
        assertEquals(1L, cache.metrics().evictions());
    }

    @Test
    void shouldReturnButNotRetainAnOversizedArtifact() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        4, 4L, String::length);
        AtomicInteger compilations = new AtomicInteger();

        assertEquals("oversized", cache.compute("entry", ignored -> {
            compilations.incrementAndGet();
            return "oversized";
        }));
        assertEquals("oversized", cache.compute("entry", ignored -> {
            compilations.incrementAndGet();
            return "oversized";
        }));

        assertEquals(2, compilations.get());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedWeight());
        assertEquals(2L, cache.metrics().evictions());
    }

    @Test
    void shouldEvictCancelledCompilationAndPermitRetry() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 16L, String::length);

        assertThrows(CancellationException.class, () -> cache.compute(
                "entry",
                ignored -> {
                    throw new CancellationException("injected");
                }));

        assertEquals("retry", cache.compute(
                "entry", ignored -> "retry"));
        assertEquals(1, cache.size());
        assertEquals(1L, cache.metrics().failures());
    }

    @Test
    void shouldNeverEvictAnInFlightCompilation() throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        1, 1L, ignored -> 1L);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = pool.submit(() -> cache.compute(
                    "slow",
                    ignored -> {
                        slowStarted.countDown();
                        await(releaseSlow);
                        return "slow";
                    }));
            slowStarted.await();
            assertEquals("fast", cache.compute(
                    "fast", ignored -> "fast"));
            assertEquals(1, cache.size(),
                    "the completed value, not in-flight work, is evicted");

            AtomicInteger duplicateLoads = new AtomicInteger();
            Future<String> coalesced = pool.submit(() -> cache.compute(
                    "slow",
                    ignored -> {
                        duplicateLoads.incrementAndGet();
                        return "duplicate";
                    }));
            awaitCoalesced(cache);
            releaseSlow.countDown();

            assertEquals("slow", slow.get());
            assertEquals("slow", coalesced.get());
            assertEquals(0, duplicateLoads.get());
        } finally {
            releaseSlow.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", failure);
        }
    }

    private static void awaitCoalesced(
            BoundedSingleFlightCache<String, String> cache) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (cache.metrics().coalesced() == 0L
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1L, cache.metrics().coalesced());
    }
}
