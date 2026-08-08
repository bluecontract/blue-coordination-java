package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentMetadataRecord;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryReferenceCutRootCompilerTest {

    @Test
    void assemblesOnlyActiveBranchesWithoutBuildingTheFullRoot() {
        Node hotLeaf = new Node().value("hot");
        Node coldLeaf = new Node().value("cold");
        String hotId = DirectBlueIdCalculator.calculateBlueId(hotLeaf);
        String coldId = DirectBlueIdCalculator.calculateBlueId(coldLeaf);
        Node directRoot = new Node().properties(
                "hot", new Node().blueId(hotId),
                "cold", new Node().blueId(coldId));
        String rootId = DirectBlueIdCalculator.calculateBlueId(directRoot);
        CoordinationFragmentInventory inventory = inventory(
                rootId, hotId, coldId);
        Map<String, Node> fragments = new LinkedHashMap<String, Node>();
        fragments.put(rootId, directRoot);
        fragments.put(hotId, hotLeaf);
        fragments.put(coldId, coldLeaf);
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID);
        store.putAllIfAbsent(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                fragments);
        store.putInventory(inventory);
        store.resetReadCounts();
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutPlanner planner = new ReferenceCutPlanner(
                ReferenceCutPolicy.strictDefaults());
        InventoryReferenceCutRootCompiler compiler =
                new InventoryReferenceCutRootCompiler(
                        planner,
                        ReferenceCutFragmentSource.bestAvailable(
                                store, metrics),
                        metrics);
        ActivePathSet active = ActivePathSet.of(
                Collections.singletonList("/hot"));
        ReferenceCutPlan preflight = planner.plan(inventory, active);
        ReferenceCutPlanner.WorkSnapshot planned = planner.workSnapshot();

        ReferenceCutRootArtifact artifact = compiler.compile(
                inventory, active, preflight);
        Node expected = new Node().properties(
                "hot", hotLeaf,
                "cold", new Node().blueId(coldId));

        assertEquals(
                NodeWireForm.get(expected),
                NodeWireForm.get(artifact.copyForFrozenBoundary()));
        assertEquals(rootId, DirectBlueIdCalculator.calculateBlueId(
                artifact.copyForFrozenBoundary()));
        assertEquals(3, artifact.inventoryFragmentCount());
        assertEquals(2, artifact.materializedFragmentCount());
        assertTrue(artifact.assembledDirectlyFromInventory());
        assertEquals(1L,
                metrics.snapshot().fullRootMaterializationsAvoided());
        assertEquals(2L, metrics.snapshot().canonicalFragmentsRead());
        assertEquals(1L, store.batchReadCount());
        assertEquals(0L, store.singleReadCount());
        assertEquals(2L, store.requestedIdentityCount());
        assertEquals(1L, metrics.snapshot().canonicalBatchReads());
        assertEquals(0L, metrics.snapshot().canonicalSingleReads());
        assertEquals(1L, metrics.snapshot().verifiedHandleBatches());
        assertEquals(0L, metrics.snapshot().portableCanonicalBatches());
        assertEquals(planned.planningPasses(),
                planner.workSnapshot().planningPasses());
        assertEquals(1, preflight.planningWork().planningPasses());
        assertEquals(1, preflight.planningWork().inventoryScanPasses());
        assertEquals(1, preflight.planningWork().inventoryEdgeSorts());
    }

    @Test
    void thousandSiblingCutsStayLinearAndCompileConsumesOnlyPreflight() {
        int decoys = 1_024;
        LargeSiblingGraph graph = largeSiblingGraph(decoys);
        ReferenceCutMetrics metrics = new ReferenceCutMetrics();
        ReferenceCutPlanner planner = new ReferenceCutPlanner(
                ReferenceCutPolicy.strictDefaults());
        InventoryReferenceCutRootCompiler compiler =
                new InventoryReferenceCutRootCompiler(
                        planner,
                        ReferenceCutFragmentSource.bestAvailable(
                                graph.store, metrics),
                        metrics);
        ActivePathSet active = ActivePathSet.of(
                Collections.singletonList("/branch-0000/payload"));
        ReferenceCutPlanner.WorkSnapshot before = planner.workSnapshot();

        ReferenceCutPlan plan = planner.plan(graph.inventory, active);

        ReferenceCutPlanner.WorkSnapshot planning = planner.workSnapshot()
                .minus(before);
        assertEquals(1L, planning.planningPasses());
        assertEquals(1L, planning.inventoryScanPasses());
        assertEquals(decoys, planning.inventoryEdgesScanned());
        assertEquals(1L, planning.inventoryEdgeSorts());
        assertEquals(decoys, planning.cutAncestorLookups());
        assertTrue(planning.cutAncestorSegmentProbes() <= decoys,
                "sibling ancestor checks must not grow with prior cuts");
        assertTrue(planning.cutIndexInsertSegmentProbes() <= decoys);
        assertEquals(decoys - 1, plan.cuts().size());
        assertEquals(2, plan.selectedFragmentCount());
        assertEquals(decoys + 1, plan.totalFragmentCount());
        assertEquals(plan.selectedFragmentCount(),
                InventoryReferenceCutRootCompiler
                        .estimatedMaterializedFragmentCount(
                                graph.inventory, plan));
        assertEquals(plan.fragmentReductionFraction(),
                InventoryReferenceCutRootCompiler
                        .estimatedFragmentReduction(graph.inventory, plan));

        ReferenceCutPlanner.WorkSnapshot sealed = planner.workSnapshot();
        ReferenceCutRootArtifact artifact = compiler.compile(
                graph.inventory, active, plan);

        assertEquals(sealed.planningPasses(),
                planner.workSnapshot().planningPasses());
        assertEquals(sealed.inventoryEdgesScanned(),
                planner.workSnapshot().inventoryEdgesScanned());
        assertEquals(2, artifact.materializedFragmentCount());
        assertEquals(2L, graph.store.requestedIdentityCount());
        assertEquals(graph.inventory.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(
                        artifact.copyForFrozenBoundary()));
    }

    private static CoordinationFragmentInventory inventory(
            String rootId,
            String hotId,
            String coldId) {
        return new CoordinationFragmentInventory(
                CoordinationFragmentInventory.SCHEMA_VERSION,
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                rootId,
                Arrays.asList(rootId, hotId, coldId),
                Collections.singletonList(new FragmentRootRecord(
                        rootId,
                        CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT,
                        "/")),
                Arrays.asList(
                        edge(rootId, "/hot", hotId),
                        edge(rootId, "/cold", coldId)),
                Collections.singletonList(
                        new FragmentMetadataRecord(
                                rootId,
                                CoordinationDocumentSplitter.FragmentKind
                                        .DOCUMENT_ROOT,
                                "/",
                                "/",
                                null,
                                null)));
    }

    private static FragmentEdgeRecord edge(
            String rootId,
            String path,
            String childId) {
        return new FragmentEdgeRecord(
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT,
                rootId,
                rootId,
                "/",
                path,
                path,
                childId,
                CoordinationDocumentSplitter.EdgeKind.DOCUMENT_DIRECT_CHILD,
                false,
                true,
                null,
                CoordinationDocumentSplitter.EmbeddedEdgeOrigin.NONE,
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList());
    }

    private static LargeSiblingGraph largeSiblingGraph(int count) {
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        Map<String, Node> fragments = new LinkedHashMap<String, Node>();
        List<String> childIds = new ArrayList<String>(count);
        List<Node> childBodies = new ArrayList<Node>(count);
        for (int index = 0; index < count; index++) {
            String key = String.format("branch-%04d", index);
            Node body = new Node().properties(
                    "payload", new Node().value(index));
            String blueId = DirectBlueIdCalculator.calculateBlueId(body);
            properties.put(key, new Node().blueId(blueId));
            childIds.add(blueId);
            childBodies.add(body);
        }
        Node root = new Node().properties(properties);
        String rootId = DirectBlueIdCalculator.calculateBlueId(root);
        List<String> fragmentIds = new ArrayList<String>(count + 1);
        fragmentIds.add(rootId);
        fragmentIds.addAll(childIds);
        List<FragmentEdgeRecord> edges =
                new ArrayList<FragmentEdgeRecord>(count);
        for (int index = 0; index < count; index++) {
            String path = String.format("/branch-%04d", index);
            edges.add(edge(rootId, path, childIds.get(index)));
            fragments.put(childIds.get(index), childBodies.get(index));
        }
        fragments.put(rootId, root);
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                        rootId,
                        fragmentIds,
                        Collections.singletonList(new FragmentRootRecord(
                                rootId,
                                CoordinationDocumentSplitter.FragmentRootKind
                                        .DOCUMENT,
                                "/")),
                        edges,
                        Collections.singletonList(
                                new FragmentMetadataRecord(
                                        rootId,
                                        CoordinationDocumentSplitter
                                                .FragmentKind.DOCUMENT_ROOT,
                                        "/",
                                        "/",
                                        null,
                                        null)));
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
        store.putAllIfAbsent(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                fragments);
        store.putInventory(inventory);
        store.resetReadCounts();
        return new LargeSiblingGraph(inventory, store);
    }

    private static final class LargeSiblingGraph {
        private final CoordinationFragmentInventory inventory;
        private final InMemoryCoordinationFragmentStore store;

        private LargeSiblingGraph(
                CoordinationFragmentInventory inventory,
                InMemoryCoordinationFragmentStore store) {
            this.inventory = inventory;
            this.store = store;
        }
    }
}
