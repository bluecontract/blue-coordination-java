package blue.coordination.processor.workflow;

import blue.bex.api.BexProgramSource;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeProgramPlanCacheTest {
    private static final String VERSION = "test-normalization-v1";

    @Test
    void shouldReusePublishedPlanForEquivalentFrozenIdentityAfterInitialMiss() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(8, 1_000_000L, metrics);
        FrozenNode rawStep = step("same");
        ComputeProgramPlan expected = plan(rawStep, null);
        AtomicInteger builds = new AtomicInteger();
        ComputeProgramPlanCache.Key firstKey = key(rawStep, null, null);

        // When
        ComputeProgramPlanCache.Lookup first = cache.lookup(firstKey, () -> {
            builds.incrementAndGet();
            return expected;
        });
        cache.publish(first);

        // A distinct FrozenNode with exactly equivalent resolved content must
        // prove the same key without relying on Java object identity.
        ComputeProgramPlanCache.Lookup second = cache.lookup(
                key(step("same"), null, null),
                () -> {
                    throw new AssertionError("warm lookup rebuilt the plan");
                });

        // Then
        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertSame(expected, second.plan());
        assertEquals(1, builds.get());
        assertEquals(1L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlanCacheHits());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(1, cache.size());
        assertTrue(cache.weightBytes() > 0L);
        assertEquals(cache.weightBytes(), metrics.computePlanWeightBytes());
        assertEquals(expected.sourceIdentity(),
                blue.bex.compile.BexCompiledProgramKey.from(expected.source()));
    }

    @Test
    void shouldKeepChangedStepDefinitionEntryAndNormalizationKeysDistinct() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(8, 1_000_000L, metrics);
        FrozenNode baseStep = step("base");
        FrozenNode definitionA = definition("A");
        FrozenNode definitionB = definition("B");

        publish(cache, key(baseStep, definitionA, "run"), plan(baseStep, definitionA));

        // When
        ComputeProgramPlanCache.Lookup changedStep =
                cache.lookup(key(step("changed"), definitionA, "run"),
                        () -> plan(step("changed"), definitionA));
        ComputeProgramPlanCache.Lookup changedDefinition =
                cache.lookup(key(baseStep, definitionB, "run"),
                        () -> plan(baseStep, definitionB));
        ComputeProgramPlanCache.Lookup changedEntry =
                cache.lookup(key(baseStep, definitionA, "other"),
                        () -> plan(baseStep, definitionA));
        ComputeProgramPlanCache.Key otherVersion = ComputeProgramPlanCache.Key.from(
                baseStep, definitionA, "run", VERSION + "-changed");
        ComputeProgramPlanCache.Lookup changedVersion =
                cache.lookup(otherVersion, () -> plan(baseStep, definitionA));

        // Then
        assertFalse(changedStep.cacheHit());
        assertFalse(changedDefinition.cacheHit());
        assertFalse(changedEntry.cacheHit());
        assertFalse(changedVersion.cacheHit());
        assertEquals(5L, metrics.computePlanCacheMisses());
        assertEquals(0L, metrics.computePlanCacheHits());
    }

    @Test
    void shouldEvictLeastRecentlyUsedPlansAndTrackLiveWeight() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(2, Long.MAX_VALUE, metrics);
        FrozenNode rawA = step("A");
        FrozenNode rawB = step("B");
        FrozenNode rawC = step("C");
        ComputeProgramPlanCache.Key keyA = key(rawA, null, null);
        ComputeProgramPlanCache.Key keyB = key(rawB, null, null);

        // When
        publish(cache, keyA, plan(rawA, null));
        publish(cache, keyB, plan(rawB, null));
        boolean retainedA = cache.lookup(keyA,
                () -> {
                    throw new AssertionError("A should be cached");
                }).cacheHit();
        publish(cache, key(rawC, null, null), plan(rawC, null));

        // Then
        assertTrue(retainedA);
        assertEquals(2, cache.size());
        assertEquals(1L, metrics.computePlanCacheEvictions());
        assertFalse(cache.lookup(keyB, () -> plan(rawB, null)).cacheHit());
        assertTrue(cache.weightBytes() > 0L);
        assertEquals(cache.weightBytes(), metrics.computePlanWeightBytes());
    }

    @Test
    void shouldClearAllWeightAndRejectCandidatesCreatedBeforeClear() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(4, 1_000_000L, metrics);
        FrozenNode raw = step("clear");
        ComputeProgramPlan plan = plan(raw, null);
        ComputeProgramPlanCache.Key key = key(raw, null, null);

        publish(cache, key, plan);
        FrozenNode staleRaw = step("in-flight-before-clear");
        ComputeProgramPlanCache.Lookup staleCandidate = cache.lookup(
                key(staleRaw, null, null),
                () -> plan(staleRaw, null));

        // When
        cache.clear();
        cache.publish(staleCandidate);

        // Then
        assertEquals(0, cache.size());
        assertEquals(0L, cache.weightBytes());
        assertEquals(0L, metrics.computePlanWeightBytes());
    }

    @Test
    void shouldCloseCacheReleaseWeightAndPreventRepopulation() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(4, 1_000_000L, metrics);
        FrozenNode raw = step("close");
        ComputeProgramPlan plan = plan(raw, null);
        ComputeProgramPlanCache.Key key = key(raw, null, null);
        ComputeProgramPlanCache.Lookup afterClear = cache.lookup(key, () -> plan);
        cache.publish(afterClear);
        int sizeBeforeClose = cache.size();

        // When
        cache.close();
        ComputeProgramPlanCache.Lookup afterClose = cache.lookup(key, () -> plan);
        cache.publish(afterClose);

        // Then
        assertEquals(1, sizeBeforeClose);
        assertTrue(cache.isClosed());
        assertEquals(0, cache.size());
        assertEquals(0L, metrics.computePlanWeightBytes());
        assertEquals(0L, cache.weightBytes());
    }

    @Test
    void shouldDeduplicateSharedFrozenSubgraphsWhenEstimatingRetainedWeight() {
        // Given
        FrozenNode sharedChild = FrozenNode.fromResolvedNode(new Node().value("shared"));
        FrozenNode shared = FrozenNode.empty()
                .withProperty("left", sharedChild)
                .withProperty("right", sharedChild);
        FrozenNode duplicate = FrozenNode.empty()
                .withProperty("left", FrozenNode.fromResolvedNode(new Node().value("shared")))
                .withProperty("right", FrozenNode.fromResolvedNode(new Node().value("shared")));

        // When
        long sharedWeight = plan(shared, null).approximateWeightBytes();
        long duplicateWeight = plan(duplicate, null).approximateWeightBytes();

        // Then
        assertSame(shared.getProperties().get("left"), shared.getProperties().get("right"));
        assertTrue(sharedWeight < duplicateWeight);
    }

    @Test
    void shouldPreserveExactDefinitionIdentityAndMetadataDuringNormalization() {
        // Given
        FrozenNode exactDefinition =
                FrozenNode.fromNode(
                        new Node()
                                .name("Exact hosted definition")
                                .description(
                                        "Provider-authored metadata")
                                .properties(
                                        "constants",
                                        new Node().properties(
                                                "kind",
                                                new Node().value(
                                                        "exact")))
                                .properties(
                                        "extension",
                                        new Node().value(
                                                "retained")));
        ComputeProgramNormalizer normalizer =
                new ComputeProgramNormalizer();
        String exactBlueId = exactDefinition.blueId();

        // When
        FrozenNode normalized =
                normalizer.definition(exactDefinition);

        // Then
        assertSame(exactDefinition, normalized);
        assertEquals(exactBlueId, normalized.blueId());
        assertEquals(
                "Exact hosted definition",
                normalized.getName());
        assertEquals(
                "Provider-authored metadata",
                normalized.getDescription());
        assertEquals(
                "retained",
                normalized.property("extension").getValue());
    }

    @Test
    void shouldProjectOnlyExecutableDefinitionFieldsForBex() {
        // Given
        Node containerType =
                new Node().name(
                        "resolved container type");
        FrozenNode exactDefinition =
                FrozenNode.fromResolvedNode(
                        new Node()
                                .name("Resolved definition")
                                .properties(
                                        "constants",
                                        new Node()
                                                .type(containerType)
                                                .properties(
                                                        "kind",
                                                        new Node()
                                                                .value(
                                                                        "projected")))
                                .properties(
                                        "functions",
                                        new Node()
                                                .type(containerType)
                                                .properties(
                                                        "build",
                                                        new Node()
                                                                .properties(
                                                                        "do",
                                                                        new Node()
                                                                                .items(
                                                                                        new Node())))));
        ComputeProgramNormalizer normalizer =
                new ComputeProgramNormalizer();

        // When
        FrozenNode source =
                normalizer.definitionSource(
                        exactDefinition);

        // Then
        assertSame(
                exactDefinition,
                normalizer.definition(
                        exactDefinition));
        assertEquals(
                "Resolved definition",
                source.getName());
        assertEquals(
                "projected",
                source.property("constants")
                        .property("kind")
                        .getValue());
        assertNull(
                source.property("constants")
                        .getType());
        assertTrue(
                source.property("functions")
                        .property("build")
                        .property("do")
                        .getItems()
                        .get(0)
                        .getProperties()
                        .containsKey("$return"));
    }

    @Test
    void shouldNeverPublishFailedBuildOrReturnRetryAsHit() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(4, 1_000_000L, metrics);
        FrozenNode raw = step("malformed");
        ComputeProgramPlanCache.Key key = key(raw, null, null);

        // When
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> cache.lookup(key, () -> {
                    throw new IllegalStateException("malformed");
                }));
        int sizeAfterFailure = cache.size();

        ComputeProgramPlan valid = plan(raw, null);
        ComputeProgramPlanCache.Lookup retry = cache.lookup(key, () -> valid);
        boolean retryHit = retry.cacheHit();
        cache.publish(retry);

        // Then
        assertEquals("malformed", failure.getMessage());
        assertEquals(0, sizeAfterFailure);
        assertFalse(retryHit);
        assertEquals(2L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(1, cache.size());
    }

    @Test
    void shouldServeConcurrentWarmLookupsWithoutRebuilding() throws Exception {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeProgramPlanCache cache = new ComputeProgramPlanCache(4, 1_000_000L, metrics);
        FrozenNode raw = step("concurrent");
        ComputeProgramPlanCache.Key key = key(raw, null, null);
        ComputeProgramPlan expected = plan(raw, null);
        publish(cache, key, expected);
        int threads = 8;
        int lookupsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<Future<Void>>();

        // When
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        for (int j = 0; j < lookupsPerThread; j++) {
                            ComputeProgramPlanCache.Lookup lookup = cache.lookup(key, () -> {
                                throw new AssertionError("concurrent warm lookup rebuilt");
                            });
                            assertTrue(lookup.cacheHit());
                            assertSame(expected, lookup.plan());
                        }
                        return null;
                    }
                }));
            }
            for (Future<Void> future : futures) {
                future.get(10L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // Then
        assertEquals((long) threads * lookupsPerThread, metrics.computePlanCacheHits());
        assertEquals(1L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(1, cache.size());
    }

    private static void publish(ComputeProgramPlanCache cache,
                                ComputeProgramPlanCache.Key key,
                                ComputeProgramPlan plan) {
        ComputeProgramPlanCache.Lookup lookup = cache.lookup(key, () -> plan);
        cache.publish(lookup);
    }

    private static ComputeProgramPlanCache.Key key(FrozenNode rawStep,
                                                   FrozenNode definition,
                                                   String entry) {
        return ComputeProgramPlanCache.Key.from(rawStep, definition, entry, VERSION);
    }

    private static ComputeProgramPlan plan(FrozenNode rawStep, FrozenNode definition) {
        BexProgramSource source = definition != null
                ? BexProgramSource.withDefinition(rawStep, definition, null)
                : BexProgramSource.inline(rawStep);
        return new ComputeProgramPlan(rawStep,
                definition,
                source,
                null,
                100_000L,
                true,
                true,
                rawStep,
                definition);
    }

    private static FrozenNode step(String value) {
        return FrozenNode.fromResolvedNode(new Node()
                .name("Compute")
                .properties("expr", new Node().value(value)));
    }

    private static FrozenNode definition(String value) {
        return FrozenNode.fromResolvedNode(new Node()
                .name("Definition")
                .properties("constants", new Node()
                        .properties("value", new Node().value(value))));
    }
}
