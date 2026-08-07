package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance proof for bounded, generation-static planning artifacts. */
final class RootStaticPlanningArtifactTest {

    @Test
    void shouldBuildOneRootArtifactUnderContentionAndReuseStaticClosures()
            throws Exception {
        // given
        ProjectionGenerationCache cache = new ProjectionGenerationCache(
                8, 1_000_000L);
        ProjectionGenerationKey generation = FastPathFixtures.generation(5L);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        List<Future<AdmittedProjection>> futures = new ArrayList<>();

        // when
        try {
            for (int index = 0; index < 8; index++) {
                futures.add(workers.submit(() -> cache.getOrCompile(
                        generation,
                        exact -> {
                            builds.incrementAndGet();
                            entered.countDown();
                            await(release);
                            return projection(exact, 128);
                        })));
            }
            assertTrue(entered.await(10L, TimeUnit.SECONDS));
            release.countDown();
            AdmittedProjection winner = futures.get(0).get();
            for (Future<AdmittedProjection> future : futures) {
                assertSame(winner, future.get());
            }
            AdmittedProjection.SelectedSurface first = winner.select(
                    Arrays.asList("public-10", "public-11"));
            AdmittedProjection.SelectedSurface second = winner.select(
                    Arrays.asList("public-10", "public-11"));
            assertSame(
                    first.occurrences().get(0), second.occurrences().get(0));
            assertSame(
                    first.scopeChains().get("/orders/order-10"),
                    second.scopeChains().get("/orders/order-10"));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }

        // then
        assertEquals(1, builds.get());
        assertEquals(1L, cache.metrics().loads());
        assertEquals(7L, cache.metrics().coalesced());
    }

    @Test
    void shouldInvalidateOnlyOnAnExactGenerationChange() {
        // given
        ProjectionGenerationCache cache = new ProjectionGenerationCache(
                16, 1_000_000L);
        ProjectionGenerationKey initial = FastPathFixtures.generation(1L);
        ProjectionGenerationKey rootChanged = FastPathFixtures.generation(2L);
        ProjectionGenerationKey subscriptionsChanged = new ProjectionGenerationKey(
                initial.environmentIdentity(),
                initial.sessionId(),
                initial.rootBlueId(),
                initial.rootRevision(),
                initial.inventoryIdentity(),
                "subscriptions-new",
                initial.runtimeIdentity());
        ProjectionGenerationKey runtimeChanged = new ProjectionGenerationKey(
                initial.environmentIdentity(),
                initial.sessionId(),
                initial.rootBlueId(),
                initial.rootRevision(),
                initial.inventoryIdentity(),
                initial.subscriptionDigest(),
                "runtime-new");
        ProjectionGenerationKey otherSession = key(
                "other-session", "root-independent", 1L);
        cache.getOrCompile(initial, key -> projection(key, 8));
        cache.getOrCompile(rootChanged, key -> projection(key, 8));
        cache.getOrCompile(subscriptionsChanged, key -> projection(key, 8));
        cache.getOrCompile(runtimeChanged, key -> projection(key, 8));
        AdmittedProjection independent = cache.getOrCompile(
                otherSession, key -> projection(key, 8));

        // when
        int removed = cache.retainOnly(runtimeChanged);

        // then
        assertEquals(3, removed,
                "only other generations of the same session are obsolete");
        assertNull(cache.find(initial));
        assertNull(cache.find(rootChanged));
        assertNull(cache.find(subscriptionsChanged));
        assertSame(
                cache.getOrCompile(runtimeChanged, key -> projection(key, 8)),
                cache.find(runtimeChanged));
        assertSame(independent, cache.find(otherSession),
                "another session remains independent");
    }

    @Test
    void shouldEnforceWeightBoundsWithDeterministicLruEviction() {
        // given
        ProjectionGenerationKey firstKey = key("session", "root-a", 1L);
        ProjectionGenerationKey secondKey = key("session", "root-b", 2L);
        ProjectionGenerationKey thirdKey = key("session", "root-c", 3L);
        AdmittedProjection first = projection(firstKey, 4);
        AdmittedProjection second = projection(secondKey, 4);
        AdmittedProjection third = projection(thirdKey, 4);
        long twoEntries = first.estimatedWeight()
                + second.estimatedWeight();
        ProjectionGenerationCache cache = new ProjectionGenerationCache(
                3, twoEntries);
        cache.getOrCompile(firstKey, ignored -> first);
        cache.getOrCompile(secondKey, ignored -> second);

        // when
        cache.getOrCompile(thirdKey, ignored -> third);

        // then
        assertNull(cache.find(firstKey), "the eldest completed entry is evicted");
        assertSame(second, cache.find(secondKey));
        assertSame(third, cache.find(thirdKey));
        assertEquals(1L, cache.metrics().evictions());
        assertEquals(2, cache.metrics().entries());
        assertTrue(cache.metrics().weight() <= twoEntries);
    }

    private static ProjectionGenerationKey key(
            String session, String root, long revision) {
        return new ProjectionGenerationKey(
                "environment",
                session,
                root,
                revision,
                "inventory-" + root,
                "subscriptions-" + revision,
                "runtime");
    }

    private static AdmittedProjection projection(
            ProjectionGenerationKey generation, int count) {
        List<AdmittedOccurrence> occurrences = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            occurrences.add(FastPathFixtures.occurrence(
                    index, "/orders/order-" + index));
        }
        return new AdmittedProjection(generation, occurrences);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
