package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReferenceCutRootCacheTest {

    @Test
    void keySeparatesStorageAuthorityAndAlgorithmVersion() {
        ReferenceCutRootCacheKey baseline = key(
                "root", "storage-a", "algorithm-a");

        assertNotEquals(
                baseline,
                key("root", "storage-b", "algorithm-a"));
        assertNotEquals(
                baseline,
                key("root", "storage-a", "algorithm-b"));
        assertNotEquals(
                baseline,
                key("root", "storage-a", "algorithm-a",
                        "subscriptions-b", "runtime-a"));
        assertNotEquals(
                baseline,
                key("root", "storage-a", "algorithm-a",
                        "subscriptions-a", "runtime-b"));
        assertEquals("storage-a",
                baseline.providerStorageGenerationAuthority());
        assertEquals("algorithm-a", baseline.algorithmVersion());
    }

    @Test
    void weightIncludesKeyPathsCutMetadataAndEntryOverhead() {
        ReferenceCutRootArtifact plain = artifact("weight-evidence");
        ReferenceCutRootCacheKey compact = key(
                plain.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCacheKey pathHeavy = new ReferenceCutRootCacheKey(
                plain.rootBlueId(),
                "inventory",
                Collections.singletonList("/" + largeLabel(8_192)),
                "environment",
                "gas",
                "subscriptions-a",
                "runtime-a",
                "storage",
                "algorithm");
        Node root = plain.copyForFrozenBoundary();
        NodeGraphStats stats = NodeGraphStats.measure(root);
        ReferenceCutRootArtifact withCut = new ReferenceCutRootArtifact(
                plain.rootBlueId(),
                plain.inventoryIdentity(),
                root,
                Collections.singletonList(new ReferenceCutPlan.Cut(
                        "/cold/branch",
                        largeLabel(512))),
                stats,
                stats);

        long compactWeight = retainedWeight(compact, plain);
        long pathHeavyWeight = retainedWeight(pathHeavy, plain);
        long cutWeight = retainedWeight(compact, withCut);

        assertTrue(compactWeight
                > compact.approximateRetainedWeightBytes()
                        + plain.approximateRetainedWeightBytes(),
                "cache-entry structures must be accounted");
        assertTrue(pathHeavyWeight > compactWeight + 8_192L,
                "active-path strings must contribute to admission weight");
        assertTrue(cutWeight > compactWeight,
                "retained cut evidence must contribute to value weight");
    }

    @Test
    void sharedBackingReusesOneArtifactWithPerFacadeAttribution() {
        ReferenceCutRootArtifact artifact = artifact("checkpoint-shared");
        ReferenceCutRootCacheKey key = key(
                artifact.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCache.SharedBacking backing =
                ReferenceCutRootCache.sharedBacking(
                        4,
                        retainedWeight(key, artifact) * 4L);
        ReferenceCutMetrics firstMetrics = new ReferenceCutMetrics();
        ReferenceCutMetrics secondMetrics = new ReferenceCutMetrics();
        ReferenceCutRootCache first = new ReferenceCutRootCache(
                backing, firstMetrics);
        ReferenceCutRootCache second = new ReferenceCutRootCache(
                backing, secondMetrics);
        AtomicInteger builds = new AtomicInteger();

        assertSame(artifact, first.getOrBuild(key, () -> {
            builds.incrementAndGet();
            return artifact;
        }));
        assertSame(artifact, second.getOrBuild(key, () -> {
            builds.incrementAndGet();
            return artifact("must-not-compile");
        }));

        assertEquals(1, builds.get());
        assertEquals(1L, firstMetrics.snapshot().cacheMisses());
        assertEquals(1L, firstMetrics.snapshot().cacheFlightLeaders());
        assertEquals(0L, firstMetrics.snapshot().cacheHits());
        assertEquals(0L, secondMetrics.snapshot().cacheMisses());
        assertEquals(0L, secondMetrics.snapshot().cacheFlightLeaders());
        assertEquals(1L, secondMetrics.snapshot().cacheHits());
    }

    @Test
    void retainedPeekHitsWithoutInvokingPreflightOrCompiler() {
        ReferenceCutRootArtifact artifact = artifact("peek-hit");
        ReferenceCutRootCacheKey key = key(
                artifact.rootBlueId(), "storage", "algorithm");
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                retainedWeight(key, artifact) * 2L,
                metrics);
        AtomicInteger compilerCalls = new AtomicInteger();
        cache.getOrBuild(key, () -> {
            compilerCalls.incrementAndGet();
            return artifact;
        });
        ReferenceCutMetrics.Snapshot beforeHit = metrics.snapshot();

        assertSame(artifact, cache.peek(key));

        ReferenceCutMetrics.Snapshot hit = metrics.snapshot().minus(beforeHit);
        assertEquals(1, compilerCalls.get());
        assertEquals(1L, hit.cacheHits());
        assertEquals(0L, hit.cacheMisses());
        assertEquals(0L, hit.cacheFlightLeaders());
        assertEquals(0L, hit.compilations());
    }

    @Test
    void absentPeekDefersTheSingleMeasuredMissToGetOrBuild() {
        ReferenceCutRootArtifact artifact = artifact("peek-miss");
        ReferenceCutRootCacheKey key = key(
                artifact.rootBlueId(), "storage", "algorithm");
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                retainedWeight(key, artifact) * 2L,
                metrics);

        assertEquals(null, cache.peek(key));
        assertSame(artifact, cache.getOrBuild(key, () -> artifact));

        assertEquals(0L, metrics.snapshot().cacheHits());
        assertEquals(1L, metrics.snapshot().cacheMisses());
        assertEquals(1L, metrics.snapshot().cacheFlightLeaders());
    }

    @Test
    void sharedBackingForcedEvictionRebuildsEquivalentArtifact() {
        ReferenceCutRootArtifact firstArtifact = artifact(
                "shared-eviction-first");
        ReferenceCutRootArtifact secondArtifact = artifact(
                "shared-eviction-second");
        ReferenceCutRootCacheKey firstKey = key(
                firstArtifact.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCacheKey secondKey = key(
                secondArtifact.rootBlueId(), "storage", "algorithm");
        long maximumWeight = Math.addExact(
                retainedWeight(firstKey, firstArtifact),
                retainedWeight(secondKey, secondArtifact)) * 2L;
        ReferenceCutRootCache.SharedBacking backing =
                ReferenceCutRootCache.sharedBacking(1, maximumWeight);
        ReferenceCutMetrics firstMetrics = new ReferenceCutMetrics();
        ReferenceCutMetrics secondMetrics = new ReferenceCutMetrics();
        ReferenceCutRootCache first = new ReferenceCutRootCache(
                backing, firstMetrics);
        ReferenceCutRootCache second = new ReferenceCutRootCache(
                backing, secondMetrics);

        first.getOrBuild(firstKey, () -> firstArtifact);
        second.getOrBuild(secondKey, () -> secondArtifact);
        ReferenceCutRootArtifact rebuilt = first.getOrBuild(
                firstKey, () -> artifact("shared-eviction-first"));

        assertEquals(firstArtifact.rootBlueId(), rebuilt.rootBlueId());
        assertEquals(
                NodeWireForm.get(firstArtifact.copyForFrozenBoundary()),
                NodeWireForm.get(rebuilt.copyForFrozenBoundary()));
        assertEquals(1L, firstMetrics.snapshot().cacheEvictions());
        assertEquals(1L, secondMetrics.snapshot().cacheEvictions());
        assertEquals(1, first.size());
        assertTrue(first.currentWeightBytes() <= maximumWeight);
    }

    @Test
    void concurrentEquivalentRequestsUseOneMeasuredFlight()
            throws Exception {
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootArtifact artifact = artifact("flight");
        ReferenceCutRootCacheKey key = key(
                artifact.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                retainedWeight(key, artifact) * 4L,
                metrics);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReferenceCutRootArtifact> leader = pool.submit(() ->
                    cache.getOrBuild(key, () -> {
                        builds.incrementAndGet();
                        started.countDown();
                        await(release);
                        return artifact;
                    }));
            started.await();
            Future<ReferenceCutRootArtifact> waiter = pool.submit(() ->
                    cache.getOrBuild(key, () -> {
                        builds.incrementAndGet();
                        return artifact;
                    }));
            awaitWaiter(metrics);
            release.countDown();

            assertSame(artifact, leader.get());
            assertSame(artifact, waiter.get());
            assertSame(artifact,
                    cache.getOrBuild(key, () -> artifact("unexpected")));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        ReferenceCutMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1, builds.get());
        assertEquals(1L, snapshot.cacheHits());
        assertEquals(2L, snapshot.cacheMisses());
        assertEquals(1L, snapshot.cacheFlightLeaders());
        assertEquals(1L, snapshot.cacheFlightWaiters());
        assertEquals(0L, snapshot.cacheFailures());
        assertTrue(snapshot.cacheLoadNanos() > 0L);
    }

    @Test
    void weightedEvictionIsMeasuredAndSemanticallyInvisible() {
        ReferenceCutRootArtifact first = artifact("first");
        ReferenceCutRootArtifact second = artifact("second");
        ReferenceCutRootCacheKey firstKey = key(
                first.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCacheKey secondKey = key(
                second.rootBlueId(), "storage", "algorithm");
        long maximumWeight = Math.max(
                retainedWeight(firstKey, first),
                retainedWeight(secondKey, second));
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                maximumWeight, metrics);
        AtomicInteger builds = new AtomicInteger();

        cache.getOrBuild(firstKey, () -> {
            builds.incrementAndGet();
            return first;
        });
        cache.getOrBuild(secondKey, () -> {
            builds.incrementAndGet();
            return second;
        });
        assertEquals(1, cache.size());
        assertEquals(1L, metrics.snapshot().cacheEvictions());

        assertSame(first, cache.getOrBuild(firstKey, () -> {
            builds.incrementAndGet();
            return first;
        }));
        assertEquals(3, builds.get());
        assertEquals(2L, metrics.snapshot().cacheEvictions());
    }

    @Test
    void failedConcurrentFlightIsMeasuredOnceAndRetrySucceeds()
            throws Exception {
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootArtifact recovered = artifact("recovered");
        ReferenceCutRootCacheKey key = key(
                recovered.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                retainedWeight(key, recovered) * 4L,
                metrics);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReferenceCutRootArtifact> leader = pool.submit(() ->
                    cache.getOrBuild(key, () -> {
                        builds.incrementAndGet();
                        started.countDown();
                        await(release);
                        throw new IllegalStateException("transient");
                    }));
            started.await();
            Future<ReferenceCutRootArtifact> waiter = pool.submit(() ->
                    cache.getOrBuild(key, () -> {
                        builds.incrementAndGet();
                        return artifact("unexpected");
                    }));
            awaitWaiter(metrics);
            release.countDown();

            ExecutionException leaderFailure = assertThrows(
                    ExecutionException.class, leader::get);
            ExecutionException waiterFailure = assertThrows(
                    ExecutionException.class, waiter::get);
            assertTrue(leaderFailure.getCause()
                    instanceof IllegalStateException);
            assertTrue(waiterFailure.getCause()
                    instanceof IllegalStateException);

            assertSame(recovered, cache.getOrBuild(key, () -> {
                builds.incrementAndGet();
                return recovered;
            }));
            assertSame(recovered,
                    cache.getOrBuild(key, () -> artifact("unexpected-hit")));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        ReferenceCutMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2, builds.get());
        assertEquals(1, cache.size());
        assertEquals(1L, snapshot.cacheHits());
        assertEquals(3L, snapshot.cacheMisses());
        assertEquals(2L, snapshot.cacheFlightLeaders());
        assertEquals(1L, snapshot.cacheFlightWaiters());
        assertEquals(1L, snapshot.cacheFailures());
        assertEquals(0L, snapshot.cacheEvictions());
        assertTrue(snapshot.cacheLoadNanos() > 0L);
    }

    @Test
    void oversizedArtifactIsReturnedWithoutDisplacingRetainedEntry() {
        ReferenceCutRootArtifact small = artifact("small");
        ReferenceCutRootArtifact oversized = artifact(
                largeLabel(16_384));
        ReferenceCutRootCacheKey smallKey = key(
                small.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCacheKey oversizedKey = key(
                oversized.rootBlueId(), "storage", "algorithm");
        long maximumWeight = retainedWeight(smallKey, small);
        assertTrue(retainedWeight(oversizedKey, oversized)
                > maximumWeight);
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                maximumWeight, metrics);
        AtomicInteger builds = new AtomicInteger();

        assertSame(small, cache.getOrBuild(smallKey, () -> {
            builds.incrementAndGet();
            return small;
        }));
        assertSame(oversized, cache.getOrBuild(oversizedKey, () -> {
            builds.incrementAndGet();
            return oversized;
        }));
        assertEquals(1, cache.size());
        assertEquals(maximumWeight, cache.currentWeightBytes());
        assertSame(small,
                cache.getOrBuild(smallKey, () -> artifact("unexpected")));
        assertSame(oversized, cache.getOrBuild(oversizedKey, () -> {
            builds.incrementAndGet();
            return oversized;
        }));

        ReferenceCutMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(3, builds.get());
        assertEquals(1, cache.size());
        assertEquals(maximumWeight, cache.currentWeightBytes());
        assertEquals(1L, snapshot.cacheHits());
        assertEquals(3L, snapshot.cacheMisses());
        assertEquals(3L, snapshot.cacheFlightLeaders());
        assertEquals(2L, snapshot.cacheEvictions());
    }

    @Test
    void entryBoundEvictsEldestEvenWhenWeightHasCapacity() {
        ReferenceCutRootArtifact first = artifact("entry-first");
        ReferenceCutRootArtifact second = artifact("entry-second");
        ReferenceCutRootArtifact third = artifact("entry-third");
        ReferenceCutRootCacheKey firstKey = key(
                first.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCacheKey secondKey = key(
                second.rootBlueId(), "storage", "algorithm");
        ReferenceCutRootCacheKey thirdKey = key(
                third.rootBlueId(), "storage", "algorithm");
        long maximumWeight = Math.addExact(
                Math.addExact(
                        retainedWeight(firstKey, first),
                        retainedWeight(secondKey, second)),
                retainedWeight(thirdKey, third)) * 2L;
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                2, maximumWeight, metrics);
        AtomicInteger builds = new AtomicInteger();
        cache.getOrBuild(firstKey, () -> counted(builds, first));
        cache.getOrBuild(
                secondKey,
                () -> counted(builds, second));
        cache.getOrBuild(
                thirdKey,
                () -> counted(builds, third));

        assertEquals(2, cache.size());
        assertTrue(cache.currentWeightBytes() <= maximumWeight);
        assertEquals(1L, metrics.snapshot().cacheEvictions());
        assertSame(first, cache.getOrBuild(
                firstKey, () -> counted(builds, first)));
        assertEquals(4, builds.get());
        assertEquals(2, cache.size());
        assertEquals(2L, metrics.snapshot().cacheEvictions());
    }

    private static ReferenceCutRootCacheKey key(
            String rootBlueId,
            String storageAuthority,
            String algorithmVersion) {
        return key(
                rootBlueId,
                storageAuthority,
                algorithmVersion,
                "subscriptions-a",
                "runtime-a");
    }

    private static ReferenceCutRootCacheKey key(
            String rootBlueId,
            String storageAuthority,
            String algorithmVersion,
            String subscriptionDigest,
            String runtimeIdentity) {
        return new ReferenceCutRootCacheKey(
                rootBlueId,
                "inventory",
                Collections.singletonList("/active"),
                "environment",
                "gas",
                subscriptionDigest,
                runtimeIdentity,
                storageAuthority,
                algorithmVersion);
    }

    private static ReferenceCutRootArtifact artifact(String label) {
        Node root = new Node().properties(
                "label", new Node().value(label));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        NodeGraphStats stats = NodeGraphStats.measure(root);
        return new ReferenceCutRootArtifact(
                rootBlueId,
                "inventory-" + label,
                root,
                Collections.<ReferenceCutPlan.Cut>emptyList(),
                stats,
                stats);
    }

    private static ReferenceCutRootArtifact counted(
            AtomicInteger builds,
            ReferenceCutRootArtifact artifact) {
        builds.incrementAndGet();
        return artifact;
    }

    private static long retainedWeight(
            ReferenceCutRootCacheKey key,
            ReferenceCutRootArtifact artifact) {
        return ReferenceCutRootCache.estimatedRetainedWeightBytes(
                key, artifact);
    }

    private static String largeLabel(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append((char) ('a' + (index % 26)));
        }
        return result.toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", failure);
        }
    }

    private static void awaitWaiter(ReferenceCutMetrics metrics) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (metrics.snapshot().cacheFlightWaiters() == 0L
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1L, metrics.snapshot().cacheFlightWaiters());
    }
}
