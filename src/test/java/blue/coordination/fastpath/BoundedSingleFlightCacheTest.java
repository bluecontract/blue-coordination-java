package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedSingleFlightCacheTest {
    @Test
    void classifiedComputationReportsExactLeaderWaiterAndHit()
            throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        8, 1_024L, String::length);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<BoundedSingleFlightCache.Computation<String>> leader =
                    worker.submit(() -> cache.getOrComputeClassified(
                            "same",
                            key -> {
                                entered.countDown();
                                await(release);
                                return "value";
                            }));
            entered.await();

            BoundedSingleFlightCache.Computation<String> waiter =
                    cache.getOrComputeClassified(
                            "same", key -> "unexpected");
            assertEquals(BoundedSingleFlightCache.Classification.WAITER,
                    waiter.classification());
            release.countDown();

            BoundedSingleFlightCache.Computation<String> loaded =
                    leader.get();
            assertEquals(BoundedSingleFlightCache.Classification.LEADER,
                    loaded.classification());
            assertEquals("value", loaded.value());
            assertEquals("value", waiter.value());
            assertTrue(loaded.loadNanos() > 0L);
            assertEquals(0, loaded.evictions());
            assertTrue(loaded.retainedAfterLoad());
            assertEquals(0L, waiter.loadNanos());
            assertEquals(0, waiter.evictions());

            BoundedSingleFlightCache.Computation<String> hit =
                    cache.getOrComputeClassified(
                            "same", key -> "unexpected");
            assertEquals(BoundedSingleFlightCache.Classification.HIT,
                    hit.classification());
            assertEquals("value", hit.value());
            assertEquals(1L, cache.metrics().hits());
            assertEquals(1L, cache.metrics().misses());
            assertEquals(1L, cache.metrics().loads());
            assertEquals(1L, cache.metrics().coalesced());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

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
    void distinctFlightsFailFastAtThePhysicalEntryBound()
            throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 64L, String::length);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejectedLoaderCalls = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(() ->
                    cache.getOrCompute("first", ignored -> {
                        entered.countDown();
                        await(release);
                        return "first";
                    }));
            Future<String> second = workers.submit(() ->
                    cache.getOrCompute("second", ignored -> {
                        entered.countDown();
                        await(release);
                        return "second";
                    }));
            entered.await();

            CacheMetrics saturated = cache.metrics();
            assertEquals(2, saturated.inFlight());
            assertEquals(2, saturated.totalEntries());
            assertThrows(RejectedExecutionException.class, () ->
                    cache.getOrCompute("third", ignored -> {
                        rejectedLoaderCalls.incrementAndGet();
                        return "third";
                    }));
            assertEquals(0, rejectedLoaderCalls.get());
            assertEquals(1L, cache.metrics().rejections());
            assertEquals(2, cache.metrics().peakInFlight());
            assertEquals(2, cache.metrics().peakTotalEntries());

            release.countDown();
            assertEquals("first", first.get());
            assertEquals("second", second.get());
            assertEquals(0, cache.metrics().inFlight());
            assertEquals(2, cache.metrics().entries());
            assertTrue(cache.metrics().totalEntries()
                    <= cache.metrics().maximumEntries());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void invalidationDetachesOldFlightWithoutRemovingNewGeneration()
            throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 64L, String::length);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> oldLeader = worker.submit(() ->
                    cache.getOrCompute("key", ignored -> {
                        entered.countDown();
                        await(release);
                        return "old";
                    }));
            entered.await();
            BoundedSingleFlightCache.Computation<String> oldWaiter =
                    cache.getOrComputeClassified(
                            "key", ignored -> "unexpected");

            assertEquals(1, cache.invalidateIf("key"::equals));
            assertEquals("new", cache.getOrCompute(
                    "key", ignored -> "new"));
            assertEquals(2, cache.metrics().peakInFlight());
            assertEquals(2, cache.metrics().totalEntries());

            release.countDown();
            assertEquals("old", oldLeader.get());
            assertEquals("old", oldWaiter.value());
            assertEquals("new", cache.find("key"));
            assertEquals(1, cache.metrics().entries());
            assertEquals(0, cache.metrics().inFlight());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void invalidatedFailureCannotRemoveANewerExactGeneration()
            throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 64L, String::length);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> failedOld = worker.submit(() ->
                    cache.getOrCompute("key", ignored -> {
                        entered.countDown();
                        await(release);
                        throw new IllegalStateException("old failed");
                    }));
            entered.await();
            assertEquals(1, cache.invalidateIf("key"::equals));
            assertEquals("new", cache.getOrCompute(
                    "key", ignored -> "new"));

            release.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class, failedOld::get);
            assertTrue(failure.getCause()
                    instanceof IllegalStateException);
            assertEquals("new", cache.find("key"));
            assertEquals(1, cache.metrics().entries());
            assertEquals(0, cache.metrics().inFlight());
            assertEquals(1L, cache.metrics().failures());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void clearRejectsWhileAnyPhysicalFlightIsRunning() throws Exception {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 64L, String::length);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() ->
                    cache.getOrCompute("key", ignored -> {
                        entered.countDown();
                        await(release);
                        return "value";
                    }));
            entered.await();
            assertThrows(IllegalStateException.class, cache::clear);
            assertEquals(1, cache.metrics().inFlight());
            release.countDown();
            assertEquals("value", result.get());
            cache.clear();
            assertEquals(0, cache.metrics().entries());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void keyAwareWeigherChargesRetainedKeyMemory() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        4,
                        7L,
                        (key, value) -> key.length() + value.length());

        assertEquals("v", cache.getOrCompute("key", ignored -> "v"));
        assertEquals(4L, cache.currentWeight());
        assertEquals("z", cache.getOrCompute("long", ignored -> "z"));

        assertEquals(5L, cache.currentWeight());
        assertNull(cache.find("key"));
        assertEquals("z", cache.find("long"));
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
        assertEquals(2, cache.metrics().maximumEntries());
        assertEquals(7L, cache.metrics().maximumWeight());
        assertEquals(2, cache.metrics().peakEntries());
        assertTrue(cache.metrics().peakWeight() <= 7L);
    }

    @Test
    void oversizedClassifiedLoadIsReturnedButNeverRetained() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 5L, String::length);
        assertEquals("small", cache.getOrCompute(
                "small", ignored -> "small"));

        BoundedSingleFlightCache.Computation<String> oversized =
                cache.getOrComputeClassified(
                        "large", ignored -> "oversized");

        assertEquals("oversized", oversized.value());
        assertEquals(BoundedSingleFlightCache.Classification.LEADER,
                oversized.classification());
        assertEquals(1, oversized.evictions());
        assertFalse(oversized.retainedAfterLoad());
        assertEquals(1, cache.retainedSize());
        assertEquals(5L, cache.currentWeight());
        assertEquals("small", cache.getOrCompute(
                "small", ignored -> "unexpected"));

        BoundedSingleFlightCache.Computation<String> repeated =
                cache.getOrComputeClassified(
                        "large", ignored -> "oversized");
        assertEquals(BoundedSingleFlightCache.Classification.LEADER,
                repeated.classification());
        assertEquals("oversized", repeated.value());
        assertEquals(1, repeated.evictions());
        assertEquals(2L, cache.metrics().evictions());
        assertEquals(1, cache.retainedSize());
        assertEquals(5L, cache.currentWeight());
    }

    @Test
    void classifiedFailureCanBeObservedAndRetried() {
        BoundedSingleFlightCache<String, String> cache =
                new BoundedSingleFlightCache<String, String>(
                        2, 16L, String::length);

        BoundedSingleFlightCache.Computation<String> failed =
                cache.getOrComputeClassified("key", ignored -> {
                    throw new IllegalStateException("transient");
                });

        assertEquals(BoundedSingleFlightCache.Classification.LEADER,
                failed.classification());
        assertThrows(IllegalStateException.class, failed::value);
        assertFalse(failed.retainedAfterLoad());
        assertEquals(0, failed.evictions());
        assertEquals(0, cache.retainedSize());
        BoundedSingleFlightCache.Computation<String> retry =
                cache.getOrComputeClassified(
                        "key", ignored -> "recovered");
        assertEquals(BoundedSingleFlightCache.Classification.LEADER,
                retry.classification());
        assertEquals("recovered", retry.value());
        assertTrue(retry.retainedAfterLoad());
        assertEquals(2L, cache.metrics().loads());
        assertEquals(1L, cache.metrics().failures());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", failure);
        }
    }
}
