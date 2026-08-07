package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
