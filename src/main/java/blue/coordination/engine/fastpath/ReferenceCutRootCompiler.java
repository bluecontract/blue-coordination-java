package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;

import java.util.Objects;

/**
 * Shadow-differential oracle that compiles a complete exact Root into an
 * identity-equivalent sparse Root.
 *
 * <p>This full-Root-first implementation is deliberately excluded from the
 * primary and fallback paths. It exists only to compare the inventory-native
 * compiler while shadow mode is explicitly enabled. The compiler verifies
 * the final direct BlueId so an oracle mismatch fails closed.</p>
 */
public final class ReferenceCutRootCompiler {
    public static final String ALGORITHM_VERSION =
            "blue.coordination/reference-cut/complete-root/1";

    private final ReferenceCutPlanner planner;
    private final ReferenceCutMetrics metrics;

    public ReferenceCutRootCompiler(
            ReferenceCutPlanner planner,
            ReferenceCutMetrics metrics) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ReferenceCutRootArtifact compile(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            ActivePathSet activePaths) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        Node exact = Objects.requireNonNull(exactRoot, "exactRoot");
        if (exact.isReferenceOnly()) {
            throw new IllegalArgumentException("Root must be concrete");
        }
        ReferenceCutPlan plan = planner.plan(checked, activePaths);
        NodeGraphStats fullStats = NodeGraphStats.measure(exact);
        Node sparse = exact.clone();
        for (ReferenceCutPlan.Cut cut : plan.cuts()) {
            NodePathEditor.put(
                    sparse,
                    cut.absolutePointer(),
                    new Node().blueId(cut.childBlueId()));
        }
        NodeGraphStats sparseStats = NodeGraphStats.measure(sparse);
        metrics.compilation();
        metrics.cutEdges(plan.cuts().size());
        metrics.fullNodes(fullStats.nodes());
        metrics.sparseNodes(sparseStats.nodes());
        metrics.identityCheck();
        String actual = DirectBlueIdCalculator.calculateBlueId(sparse);
        if (!checked.rootBlueId().equals(actual)) {
            metrics.identityFailure();
            throw new IllegalStateException(
                    "Reference-cut Root changed identity: expected="
                            + checked.rootBlueId() + ", actual=" + actual);
        }
        return new ReferenceCutRootArtifact(
                checked.rootBlueId(), checked.inventoryIdentity(), sparse,
                plan.cuts(), fullStats, sparseStats);
    }
}
