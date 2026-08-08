package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReferenceCutTestFixtures {
    private ReferenceCutTestFixtures() { }

    /**
     * One authoritative canonical-direct graph shared by the sparse-Root
     * matrix.  It deliberately combines a deep branch, sibling branches and
     * an authored pure reference so every compiler assertion exercises the
     * real inventory and verified-handle storage boundary.
     */
    static ReferenceCutGraph referenceCutGraph() {
        Node external = new Node().properties(
                "kind", new Node().value("authored-external"),
                "payload", new Node().value("not-admitted"));
        String externalBlueId = DirectBlueIdCalculator.calculateBlueId(
                external);
        Node exact = new Node().properties(
                "left", new Node().properties(
                        "deep", new Node().properties(
                                "middle", new Node().properties(
                                        "leaf", new Node().properties(
                                                "payload", new Node().value(
                                                        "deep-value")),
                                        "sideLeaf", new Node().properties(
                                                "payload", new Node().value(
                                                        "side-value"))),
                                "nearLeaf", new Node().properties(
                                        "payload", new Node().value(
                                                "near-value"))),
                        "peer", new Node().properties(
                                "payload", new Node().value("left-peer"))),
                "right", new Node().properties(
                        "deep", new Node().properties(
                                "leaf", new Node().properties(
                                        "payload", new Node().value(
                                                "right-deep"))),
                        "peer", new Node().properties(
                                "payload", new Node().value("right-peer"))),
                "unicode", new Node().value("Zażółć 🌍"),
                "authored", new Node().blueId(externalBlueId));
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(exact);
        CoordinationFragmentInventory inventory =
                CoordinationFragmentInventory.from(split);
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID);
        store.putAllIfAbsent(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                split.fragments());
        store.putInventory(inventory);
        store.resetReadCounts();
        return new ReferenceCutGraph(
                inventory,
                store,
                split.reconstruct(),
                externalBlueId);
    }

    static final class ReferenceCutGraph {
        final CoordinationFragmentInventory inventory;
        final InMemoryCoordinationFragmentStore store;
        final Node completeRoot;
        final String authoredExternalBlueId;
        final List<String> splitterCreatedPaths;

        private ReferenceCutGraph(
                CoordinationFragmentInventory inventory,
                InMemoryCoordinationFragmentStore store,
                Node completeRoot,
                String authoredExternalBlueId) {
            this.inventory = inventory;
            this.store = store;
            this.completeRoot = completeRoot.clone();
            this.authoredExternalBlueId = authoredExternalBlueId;
            List<String> paths = new ArrayList<String>();
            for (FragmentEdgeRecord edge : inventory.edges()) {
                if (edge.splitterCreated()) {
                    paths.add(edge.absolutePointer());
                }
            }
            this.splitterCreatedPaths = Collections.unmodifiableList(paths);
        }

        ReferenceCutPlanner planner() {
            return new ReferenceCutPlanner(
                    ReferenceCutPolicy.strictDefaults());
        }

        InventoryReferenceCutRootCompiler compiler(
                ReferenceCutMetrics metrics) {
            return new InventoryReferenceCutRootCompiler(
                    planner(),
                    ReferenceCutFragmentSource.bestAvailable(
                            store, metrics),
                    metrics);
        }

        ReferenceCutPlan plan(ActivePathSet activePaths) {
            return planner().plan(inventory, activePaths);
        }

        /** Full-Root-first shadow oracle, kept outside the primary compiler. */
        Node authoritativeSparse(ActivePathSet activePaths) {
            Node result = completeRoot.clone();
            for (ReferenceCutPlan.Cut cut : plan(activePaths).cuts()) {
                NodePathEditor.put(
                        result,
                        cut.absolutePointer(),
                        new Node().blueId(cut.childBlueId()));
            }
            return result;
        }

        ReferenceCutRootCacheKey cacheKey(ActivePathSet activePaths) {
            return new ReferenceCutRootCacheKey(
                    inventory.rootBlueId(),
                    inventory.inventoryIdentity(),
                    activePaths.paths(),
                    "round4-test-environment",
                    "round4-test-gas",
                    "round4-test-subscriptions",
                    "round4-test-runtime",
                    store.canonicalFragmentStorageGenerationAuthority(),
                    InventoryReferenceCutRootCompiler.ALGORITHM_VERSION);
        }

        Map<String, String> splitterCreatedBlueIdByPath() {
            Map<String, String> result =
                    new LinkedHashMap<String, String>();
            for (FragmentEdgeRecord edge : inventory.edges()) {
                if (edge.splitterCreated()) {
                    result.put(edge.absolutePointer(), edge.childBlueId());
                }
            }
            return Collections.unmodifiableMap(result);
        }
    }
}
