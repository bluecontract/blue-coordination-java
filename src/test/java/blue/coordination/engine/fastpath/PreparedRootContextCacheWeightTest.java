package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreparedRootContextCacheWeightTest {

    @Test
    void shouldEvictByRetainedBytesInDeterministicAccessOrder() {
        PreparedRootExecutionContext first = context(
                "session-first", 0L, "aaaaa");
        PreparedRootExecutionContext second = context(
                "session-second", 0L, "bbbbb");
        PreparedRootExecutionContext third = context(
                "session-third", 0L, "ccccc");
        long oneContext = first.approximateRetainedWeightBytes();
        PreparedRootContextCache cache = new PreparedRootContextCache(
                8, Math.multiplyExact(oneContext, 2L));
        assertTrue(cache.installIfNotOlder(first));
        assertTrue(cache.installIfNotOlder(second));
        assertSame(first, get(cache, first));

        assertTrue(cache.installIfNotOlder(third));

        assertEquals(2, cache.size());
        assertEquals(oneContext * 2L, cache.retainedWeightBytes());
        assertNull(get(cache, second));
        assertSame(first, get(cache, first));
        assertSame(third, get(cache, third));
        assertEquals(1L, cache.evictions());
    }

    @Test
    void shouldReturnButNeverRetainAnOversizedBuiltContext() {
        PreparedRootExecutionContext context = context(
                "session-oversized", 0L, "oversized");
        PreparedRootContextCache cache = new PreparedRootContextCache(
                4, context.approximateRetainedWeightBytes() - 1L);
        AtomicInteger builds = new AtomicInteger();

        assertSame(context, build(cache, context, builds));
        assertSame(context, build(cache, context, builds));

        assertEquals(2, builds.get());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedWeightBytes());
        assertFalse(cache.installIfNotOlder(context));
    }

    @Test
    void shouldBuildOneExactGenerationUnderContention() throws Exception {
        PreparedRootExecutionContext context = context(
                "session-flight", 0L, "single-flight");
        PreparedRootContextCache cache = new PreparedRootContextCache(
                4, context.approximateRetainedWeightBytes() * 2L);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PreparedRootExecutionContext> first = pool.submit(() ->
                    cache.getOrBuild(
                            context.sessionId(),
                            context.epoch(),
                            context.rootBlueId(),
                            context.inventoryIdentity(),
                            () -> {
                                builds.incrementAndGet();
                                started.countDown();
                                await(release);
                                return context;
                            }));
            started.await();
            Future<PreparedRootExecutionContext> second = pool.submit(() ->
                    cache.getOrBuild(
                            context.sessionId(),
                            context.epoch(),
                            context.rootBlueId(),
                            context.inventoryIdentity(),
                            () -> {
                                builds.incrementAndGet();
                                return context;
                            }));
            release.countDown();

            assertSame(context, first.get());
            assertSame(context, second.get());
            assertEquals(1, builds.get());
            assertEquals(1, cache.size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldFailFastWhenAllPhysicalSlotsAreBuilding()
            throws Exception {
        PreparedRootExecutionContext first = context(
                "session-flight-first", 0L, "first");
        PreparedRootExecutionContext second = context(
                "session-flight-second", 0L, "second");
        PreparedRootExecutionContext third = context(
                "session-flight-third", 0L, "third");
        PreparedRootContextCache cache = new PreparedRootContextCache(
                2, Long.MAX_VALUE);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejectedBuilds = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<PreparedRootExecutionContext> firstResult =
                    workers.submit(() -> cache.getOrBuild(
                            first.sessionId(),
                            first.epoch(),
                            first.rootBlueId(),
                            first.inventoryIdentity(),
                            () -> {
                                entered.countDown();
                                await(release);
                                return first;
                            }));
            Future<PreparedRootExecutionContext> secondResult =
                    workers.submit(() -> cache.getOrBuild(
                            second.sessionId(),
                            second.epoch(),
                            second.rootBlueId(),
                            second.inventoryIdentity(),
                            () -> {
                                entered.countDown();
                                await(release);
                                return second;
                            }));
            entered.await();

            PreparedRootContextCache.Snapshot saturated = cache.snapshot();
            assertEquals(2, saturated.inFlight());
            assertEquals(2, saturated.totalSize());
            assertThrows(RejectedExecutionException.class, () ->
                    cache.getOrBuild(
                            third.sessionId(),
                            third.epoch(),
                            third.rootBlueId(),
                            third.inventoryIdentity(),
                            () -> {
                                rejectedBuilds.incrementAndGet();
                                return third;
                            }));
            assertEquals(0, rejectedBuilds.get());
            assertEquals(1L, cache.snapshot().rejections());
            assertEquals(2, cache.snapshot().peakInFlight());
            assertEquals(2, cache.snapshot().peakTotalSize());

            release.countDown();
            assertSame(first, firstResult.get());
            assertSame(second, secondResult.get());
            assertEquals(0, cache.snapshot().inFlight());
            assertEquals(2, cache.snapshot().size());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void invalidatedFlightCannotRemoveOrReplaceANewerExactGeneration()
            throws Exception {
        PreparedRootExecutionContext old = context(
                "session-replaced-flight", 0L, "same-root");
        PreparedRootExecutionContext replacement = context(
                "session-replaced-flight", 0L, "same-root");
        PreparedRootContextCache cache = new PreparedRootContextCache(
                2, Long.MAX_VALUE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<PreparedRootExecutionContext> oldResult =
                    worker.submit(() -> cache.getOrBuild(
                            old.sessionId(),
                            old.epoch(),
                            old.rootBlueId(),
                            old.inventoryIdentity(),
                            () -> {
                                entered.countDown();
                                await(release);
                                return old;
                            }));
            entered.await();
            cache.removeSession(old.sessionId());

            assertSame(replacement, cache.getOrBuild(
                    replacement.sessionId(),
                    replacement.epoch(),
                    replacement.rootBlueId(),
                    replacement.inventoryIdentity(),
                    () -> replacement));
            assertEquals(2, cache.snapshot().peakInFlight());

            release.countDown();
            assertSame(old, oldResult.get());
            assertSame(replacement, get(cache, replacement));
            assertEquals(1, cache.size());
            assertEquals(0, cache.inFlightCount());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void invalidatedBuildFailureCannotRemoveANewerExactGeneration()
            throws Exception {
        PreparedRootExecutionContext key = context(
                "session-replaced-failure", 0L, "same-root");
        PreparedRootExecutionContext replacement = context(
                "session-replaced-failure", 0L, "same-root");
        PreparedRootContextCache cache = new PreparedRootContextCache(
                2, Long.MAX_VALUE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<PreparedRootExecutionContext> failedOld =
                    worker.submit(() -> cache.getOrBuild(
                            key.sessionId(),
                            key.epoch(),
                            key.rootBlueId(),
                            key.inventoryIdentity(),
                            () -> {
                                entered.countDown();
                                await(release);
                                throw new IllegalStateException(
                                        "old failed");
                            }));
            entered.await();
            cache.removeSession(key.sessionId());
            assertSame(replacement, cache.getOrBuild(
                    replacement.sessionId(),
                    replacement.epoch(),
                    replacement.rootBlueId(),
                    replacement.inventoryIdentity(),
                    () -> replacement));

            release.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class, failedOld::get);
            assertTrue(failure.getCause()
                    instanceof IllegalStateException);
            assertSame(replacement, get(cache, replacement));
            assertEquals(1, cache.size());
            assertEquals(0, cache.inFlightCount());
            assertEquals(1L, cache.snapshot().failures());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void retainedContextSnapshotIsImmutableBoundedAndNeverBuilds() {
        PreparedRootExecutionContext first = context(
                "snapshot-first", 0L, "first");
        PreparedRootExecutionContext second = context(
                "snapshot-second", 0L, "second");
        PreparedRootExecutionContext third = context(
                "snapshot-third", 0L, "third");
        PreparedRootContextCache cache = new PreparedRootContextCache(
                2, Long.MAX_VALUE);
        assertTrue(cache.installIfNotOlder(first));
        assertTrue(cache.installIfNotOlder(second));

        List<PreparedRootExecutionContext> retained =
                cache.retainedContextsSnapshot();
        assertEquals(2, retained.size());
        assertTrue(retained.contains(first));
        assertTrue(retained.contains(second));
        assertThrows(UnsupportedOperationException.class, () ->
                retained.add(third));

        assertTrue(cache.installIfNotOlder(third));
        assertEquals(2, retained.size(),
                "a checkpoint snapshot is a stable copy");
        assertEquals(2, cache.retainedContextsSnapshot().size());
        assertTrue(cache.snapshot().size() <= cache.snapshot().maximumSize());
        assertTrue(cache.snapshot().retainedWeightBytes()
                <= cache.snapshot().maximumWeightBytes());
        assertEquals(0L, cache.snapshot().builds());
    }

    @Test
    void shouldRejectNonPositiveBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedRootContextCache(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedRootContextCache(1, 0L));
    }

    private static PreparedRootExecutionContext build(
            PreparedRootContextCache cache,
            PreparedRootExecutionContext context,
            AtomicInteger builds) {
        return cache.getOrBuild(
                context.sessionId(),
                context.epoch(),
                context.rootBlueId(),
                context.inventoryIdentity(),
                () -> {
                    builds.incrementAndGet();
                    return context;
                });
    }

    private static PreparedRootExecutionContext get(
            PreparedRootContextCache cache,
            PreparedRootExecutionContext context) {
        return cache.get(
                context.sessionId(),
                context.epoch(),
                context.rootBlueId(),
                context.inventoryIdentity());
    }

    private static PreparedRootExecutionContext context(
            String sessionId, long epoch, String value) {
        Node root = new Node().properties(
                "value", new Node().value(value));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter
                                .EDGE_METADATA_SCHEMA_ID,
                        rootBlueId,
                        Collections.singletonList(rootBlueId),
                        Collections.singletonList(new FragmentRootRecord(
                                rootBlueId,
                                CoordinationDocumentSplitter.FragmentRootKind
                                        .DOCUMENT,
                                "")),
                        Collections.emptyList(),
                        Collections.emptyList());
        Object owner = new Object();
        ExactNodeHandle rootHandle = ExactNodeHandle.copyAndVerify(
                rootBlueId, root, owner);
        RetainedReferenceIndex references =
                RetainedReferenceIndex.scanOnce(
                        rootHandle,
                        owner,
                        new RequestDigestMemo());
        return new PreparedRootExecutionContext(
                sessionId,
                epoch,
                inventory,
                rootHandle,
                references,
                Collections.<String, ExactNodeHandle>emptyMap(),
                owner);
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
