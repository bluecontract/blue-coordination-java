package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.spi.CoordinationCanonicalFragmentHandleStore;
import blue.coordination.round4.Round4ParityReceipt;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic differential matrix for direct inventory Root assembly. */
final class Round4ReferenceCutDifferentialTest {

    @Test
    void directAssemblyEqualsCompleteRootReferenceCut() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ActivePathSet active = ActivePathSet.of(asList(
                "/left/deep/middle/leaf",
                "/right/peer"));

        ReferenceCutRootArtifact actual = graph.compiler(
                new ReferenceCutMetrics()).compile(graph.inventory, active);
        Node expected = graph.authoritativeSparse(active);

        assertEquals(NodeWireForm.get(expected),
                NodeWireForm.get(actual.copyForFrozenBoundary()));
        assertEquals(graph.inventory.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(
                        actual.copyForFrozenBoundary()));
        assertEquals(
                InventoryReferenceCutRootCompiler
                        .estimatedMaterializedFragmentCount(
                                graph.inventory,
                                graph.plan(active)),
                actual.materializedFragmentCount());
        assertTrue(actual.assembledDirectlyFromInventory());
    }

    @Test
    void rootOnlyActivePathMaterializesMinimumFragments() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ActivePathSet rootOnly = ActivePathSet.of(
                Collections.<String>emptyList());

        ReferenceCutRootArtifact artifact = graph.compiler(
                new ReferenceCutMetrics()).compile(
                        graph.inventory, rootOnly);

        assertEquals(1, artifact.materializedFragmentCount());
        assertTrue(artifact.inventoryFragmentCount() > 1);
        assertEquals(NodeWireForm.get(graph.authoritativeSparse(rootOnly)),
                NodeWireForm.get(artifact.copyForFrozenBoundary()));
    }

    @Test
    void deepActivePathIncludesEveryAncestor() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ActivePathSet active = ActivePathSet.of(Collections.singletonList(
                "/left/deep/middle/leaf"));

        Node sparse = graph.compiler(new ReferenceCutMetrics())
                .compile(graph.inventory, active)
                .copyForFrozenBoundary();

        assertConcrete(sparse, "/left");
        assertConcrete(sparse, "/left/deep");
        assertConcrete(sparse, "/left/deep/middle");
        assertConcrete(sparse, "/left/deep/middle/leaf");
        assertReference(sparse, "/left/peer");
        assertReference(sparse, "/right");
        assertEquals(NodeWireForm.get(graph.authoritativeSparse(active)),
                NodeWireForm.get(sparse));
    }

    @Test
    void siblingActivePathsDeduplicateAncestors() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ActivePathSet active = ActivePathSet.of(asList(
                "/left/deep/nearLeaf",
                "/left/deep/middle/sideLeaf",
                "/right/deep/leaf"));
        ReferenceCutPlan plan = graph.plan(active);
        graph.store.resetReadCounts();

        ReferenceCutRootArtifact artifact = graph.compiler(
                new ReferenceCutMetrics()).compile(
                        graph.inventory, active, plan);

        assertEquals(
                InventoryReferenceCutRootCompiler
                        .estimatedMaterializedFragmentCount(
                                graph.inventory, plan),
                artifact.materializedFragmentCount());
        assertEquals(artifact.materializedFragmentCount(),
                graph.store.requestedIdentityCount(),
                "shared Root/ancestor identities must be loaded once");
        assertEquals(1L, graph.store.batchReadCount());
        assertEquals(0L, graph.store.singleReadCount());
        assertEquals(NodeWireForm.get(graph.authoritativeSparse(active)),
                NodeWireForm.get(artifact.copyForFrozenBoundary()));
    }

    @Test
    void authoredPureReferenceIsNotReclassified() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ActivePathSet active = ActivePathSet.of(Collections.singletonList(
                "/authored/attempted-descendant"));

        ReferenceCutRootArtifact artifact = graph.compiler(
                new ReferenceCutMetrics()).compile(
                        graph.inventory, active);
        Node authored = NodePathEditor.getOrNull(
                artifact.copyForFrozenBoundary(), "/authored");

        assertTrue(authored != null && authored.isReferenceOnly());
        assertEquals(graph.authoredExternalBlueId, authored.getBlueId());
        for (ReferenceCutPlan.Cut cut : artifact.cuts()) {
            assertFalse("/authored".equals(cut.absolutePointer()));
        }
        boolean provenanceFound = false;
        for (FragmentEdgeRecord edge : graph.inventory.edges()) {
            if ("/authored".equals(edge.absolutePointer())) {
                provenanceFound = true;
                assertTrue(edge.originalPureReference());
                assertFalse(edge.splitterCreated());
            }
        }
        assertTrue(provenanceFound);
    }

    @Test
    void missingAndOutOfInventoryHandlesFailClosed() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutFragmentSource real =
                ReferenceCutFragmentSource.bestAvailable(
                        graph.store, metrics);
        ReferenceCutFragmentSource missing = (inventoryIdentity, blueIds) -> {
            Map<String, ExactNodeHandle> supplied =
                    new LinkedHashMap<String, ExactNodeHandle>(
                            real.loadCanonical(inventoryIdentity, blueIds));
            supplied.remove(blueIds.iterator().next());
            return Collections.unmodifiableMap(supplied);
        };
        InventoryReferenceCutRootCompiler compiler =
                new InventoryReferenceCutRootCompiler(
                        graph.planner(), missing, metrics);

        assertThrows(IllegalStateException.class, () -> compiler.compile(
                graph.inventory,
                ActivePathSet.of(Collections.<String>emptyList())));
        assertThrows(IllegalArgumentException.class, () ->
                graph.store.readCanonicalFragmentHandles(
                        graph.inventory.inventoryIdentity(),
                        Collections.singletonList(
                                graph.authoredExternalBlueId)));
    }

    @Test
    void verifiedHandleIdentityMismatchFailsBeforeAssembly() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        Node wrong = new Node().value("wrong-root-body");
        String wrongBlueId = DirectBlueIdCalculator.calculateBlueId(wrong);
        ExactNodeHandle wrongHandle = ExactNodeHandle.copyAndVerify(
                wrongBlueId, wrong, new Object());
        ReferenceCutFragmentSource mismatched = (inventoryIdentity, blueIds) -> {
            Map<String, ExactNodeHandle> result =
                    new LinkedHashMap<String, ExactNodeHandle>();
            for (String blueId : blueIds) {
                result.put(blueId, wrongHandle);
            }
            return Collections.unmodifiableMap(result);
        };
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        InventoryReferenceCutRootCompiler compiler =
                new InventoryReferenceCutRootCompiler(
                        graph.planner(), mismatched, metrics);

        assertThrows(IllegalStateException.class, () -> compiler.compile(
                graph.inventory,
                ActivePathSet.of(Collections.<String>emptyList())));
        assertEquals(0L, metrics.snapshot().identityChecks(),
                "unverified content must not reach the Root identity gate");

        Map<String, ExactNodeHandle> invalidBatch =
                new LinkedHashMap<String, ExactNodeHandle>();
        invalidBatch.put(graph.inventory.rootBlueId(), wrongHandle);
        assertThrows(IllegalArgumentException.class, () ->
                new CoordinationCanonicalFragmentHandleStore
                        .CanonicalFragmentHandleBatch(
                                invalidBatch, 1, 0));
    }

    @Test
    void topologyMismatchedPreflightPlanFailsClosed() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        InventoryReferenceCutRootCompiler compiler = graph.compiler(
                new ReferenceCutMetrics());
        ReferenceCutPlan foreignInventory = new ReferenceCutPlan(
                graph.inventory.rootBlueId(),
                "foreign-inventory",
                Collections.<ReferenceCutPlan.Cut>emptyList());
        ReferenceCutPlan unknownCut = new ReferenceCutPlan(
                graph.inventory.rootBlueId(),
                graph.inventory.inventoryIdentity(),
                Collections.singletonList(new ReferenceCutPlan.Cut(
                        "/not-present",
                        graph.inventory.rootBlueId())));
        ReferenceCutPlan wrongChild = new ReferenceCutPlan(
                graph.inventory.rootBlueId(),
                graph.inventory.inventoryIdentity(),
                Collections.singletonList(new ReferenceCutPlan.Cut(
                        "/left",
                        graph.inventory.rootBlueId())));
        ActivePathSet plannedPaths = ActivePathSet.of(
                Collections.singletonList("/left"));
        ReferenceCutPlan sealedForOtherPaths = graph.plan(plannedPaths);

        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                graph.inventory,
                ActivePathSet.of(Collections.<String>emptyList()),
                foreignInventory));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                graph.inventory,
                ActivePathSet.of(Collections.<String>emptyList()),
                unknownCut));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                graph.inventory,
                ActivePathSet.of(Collections.<String>emptyList()),
                wrongChild));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                graph.inventory,
                ActivePathSet.of(Collections.singletonList("/right")),
                sealedForOtherPaths));
    }

    @Test
    void selectedFragmentsLoadInOneBatchWithZeroSingleReads() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ActivePathSet active = ActivePathSet.of(asList(
                "/left/deep/middle/leaf/payload",
                "/right/peer/payload"));
        graph.store.resetReadCounts();

        ReferenceCutRootArtifact artifact = graph.compiler(metrics).compile(
                graph.inventory, active);

        assertEquals(1L, graph.store.batchReadCount());
        assertEquals(0L, graph.store.singleReadCount());
        assertEquals(artifact.materializedFragmentCount(),
                graph.store.requestedIdentityCount());
        assertEquals(1L, metrics.snapshot().canonicalBatchReads());
        assertEquals(0L, metrics.snapshot().canonicalSingleReads());
        assertEquals(1L, metrics.snapshot().verifiedHandleBatches());
        assertEquals(0L, metrics.snapshot().portableCanonicalBatches());
    }

    @Test
    void cacheEvictionPreservesSparseParity() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        InventoryReferenceCutRootCompiler compiler = graph.compiler(metrics);
        ActivePathSet firstPaths = ActivePathSet.of(
                Collections.singletonList("/left/deep/middle/leaf"));
        ActivePathSet secondPaths = ActivePathSet.of(
                Collections.singletonList("/right/deep/leaf"));
        ReferenceCutRootArtifact firstPrototype = compiler.compile(
                graph.inventory, firstPaths);
        ReferenceCutRootArtifact secondPrototype = compiler.compile(
                graph.inventory, secondPaths);
        ReferenceCutRootCacheKey firstKey = graph.cacheKey(firstPaths);
        ReferenceCutRootCacheKey secondKey = graph.cacheKey(secondPaths);
        long maximumWeight = Math.max(
                ReferenceCutRootCache.estimatedRetainedWeightBytes(
                        firstKey, firstPrototype),
                ReferenceCutRootCache.estimatedRetainedWeightBytes(
                        secondKey, secondPrototype));
        ReferenceCutRootCache cache = new ReferenceCutRootCache(
                maximumWeight, metrics);
        AtomicInteger builds = new AtomicInteger();

        ReferenceCutRootArtifact first = cache.getOrBuild(
                firstKey, () -> {
                    builds.incrementAndGet();
                    return compiler.compile(graph.inventory, firstPaths);
                });
        cache.getOrBuild(secondKey, () -> {
            builds.incrementAndGet();
            return compiler.compile(graph.inventory, secondPaths);
        });
        ReferenceCutRootArtifact rebuilt = cache.getOrBuild(
                firstKey, () -> {
                    builds.incrementAndGet();
                    return compiler.compile(graph.inventory, firstPaths);
                });

        assertNotSame(first, rebuilt);
        assertEquals(3, builds.get());
        assertEquals(1, cache.size());
        assertEquals(2L, metrics.snapshot().cacheEvictions());
        assertEquals(NodeWireForm.get(graph.authoritativeSparse(firstPaths)),
                NodeWireForm.get(first.copyForFrozenBoundary()));
        assertEquals(NodeWireForm.get(first.copyForFrozenBoundary()),
                NodeWireForm.get(rebuilt.copyForFrozenBoundary()));
    }

    @Test
    void randomizedTenThousandPathSetsHaveZeroMismatch() {
        ReferenceCutTestFixtures.ReferenceCutGraph graph =
                ReferenceCutTestFixtures.referenceCutGraph();
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        InventoryReferenceCutRootCompiler compiler = graph.compiler(metrics);
        Random random = new Random(0x4b1d5eedL);
        graph.store.resetReadCounts();

        for (int iteration = 0; iteration < 10_000; iteration++) {
            List<String> supplied = randomizedPaths(
                    graph.splitterCreatedPaths, random, iteration);
            ActivePathSet active = ActivePathSet.of(supplied);
            ReferenceCutPlan plan = graph.plan(active);
            ReferenceCutRootArtifact actual = compiler.compile(
                    graph.inventory, active, plan);
            Node expected = graph.authoritativeSparse(active);

            assertEquals(NodeWireForm.get(expected),
                    NodeWireForm.get(actual.copyForFrozenBoundary()),
                    "sparse wire mismatch at deterministic case "
                            + iteration + " paths=" + active.paths());
            assertEquals(graph.inventory.rootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(
                            actual.copyForFrozenBoundary()),
                    "Root identity mismatch at case " + iteration);
            assertEquals(
                    InventoryReferenceCutRootCompiler
                            .estimatedMaterializedFragmentCount(
                                    graph.inventory, plan),
                    actual.materializedFragmentCount(),
                    "selected-fragment mismatch at case " + iteration);
        }

        ReferenceCutMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(10_000L, snapshot.compilations());
        assertEquals(10_000L, snapshot.inventoryCompilations());
        assertEquals(10_000L, snapshot.identityChecks());
        assertEquals(0L, snapshot.identityFailures());
        assertEquals(10_000L,
                snapshot.fullRootMaterializationsAvoided());
        assertEquals(10_000L, snapshot.canonicalBatchReads());
        assertEquals(0L, snapshot.canonicalSingleReads());
        assertEquals(10_000L, snapshot.verifiedHandleBatches());
        assertEquals(0L, snapshot.portableCanonicalBatches());
        assertEquals(
                Math.multiplyExact(
                        10_000L,
                        graph.inventory.fragmentBlueIds().size()),
                snapshot.inventoryFragments());
        assertTrue(snapshot.materializedFragments() > 0L);
        assertTrue(snapshot.materializedFragments()
                <= snapshot.inventoryFragments());
        assertEquals(10_000L, graph.store.batchReadCount());
        assertEquals(0L, graph.store.singleReadCount());
        assertEquals(snapshot.canonicalFragmentsRead(),
                graph.store.requestedIdentityCount());
        Round4ParityReceipt.write(
                "sparseRootComparisons", 10_000L, 0L);
    }

    private static List<String> randomizedPaths(
            List<String> candidates,
            Random random,
            int iteration) {
        if (iteration % 509 == 0) {
            return new ArrayList<String>(candidates);
        }
        if (iteration % 257 == 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String candidate : candidates) {
            if (random.nextInt(4) == 0) {
                result.add(candidate);
                if (random.nextInt(7) == 0) {
                    result.add(candidate + "/non-fragment-descendant");
                }
            }
        }
        if (result.isEmpty()) {
            result.add(candidates.get(random.nextInt(candidates.size())));
        }
        Collections.shuffle(result, random);
        return result;
    }

    private static List<String> asList(String... paths) {
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, paths);
        return result;
    }

    private static void assertConcrete(Node root, String pointer) {
        Node selected = NodePathEditor.getOrNull(root, pointer);
        assertTrue(selected != null && !selected.isReferenceOnly(),
                pointer + " must be materialized");
    }

    private static void assertReference(Node root, String pointer) {
        Node selected = NodePathEditor.getOrNull(root, pointer);
        assertTrue(selected != null && selected.isReferenceOnly(),
                pointer + " must remain a pure reference");
    }
}
