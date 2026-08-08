package blue.coordination.engine.fastpath;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Objects;

/** Verified identity-equivalent sparse Root retained for one epoch/plan shape. */
public final class ReferenceCutRootArtifact {
    private final String rootBlueId;
    private final String inventoryIdentity;
    private final FrozenNode sparseRoot;
    private final List<ReferenceCutPlan.Cut> cuts;
    private final NodeGraphStats fullStats;
    private final NodeGraphStats sparseStats;
    private final int inventoryFragmentCount;
    private final int materializedFragmentCount;
    private final boolean assembledDirectlyFromInventory;

    ReferenceCutRootArtifact(
            String rootBlueId,
            String inventoryIdentity,
            Node sparseRoot,
            List<ReferenceCutPlan.Cut> cuts,
            NodeGraphStats fullStats,
            NodeGraphStats sparseStats) {
        this(
                rootBlueId,
                inventoryIdentity,
                sparseRoot,
                cuts,
                fullStats,
                sparseStats,
                0,
                0,
                false);
    }

    private ReferenceCutRootArtifact(
            String rootBlueId,
            String inventoryIdentity,
            Node sparseRoot,
            List<ReferenceCutPlan.Cut> cuts,
            NodeGraphStats fullStats,
            NodeGraphStats sparseStats,
            int inventoryFragmentCount,
            int materializedFragmentCount,
            boolean assembledDirectlyFromInventory) {
        this.rootBlueId = Objects.requireNonNull(rootBlueId, "rootBlueId");
        this.inventoryIdentity = Objects.requireNonNull(
                inventoryIdentity, "inventoryIdentity");
        this.sparseRoot = FrozenNode.fromNode(
                Objects.requireNonNull(sparseRoot, "sparseRoot"));
        this.cuts = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<ReferenceCutPlan.Cut>(
                        Objects.requireNonNull(cuts, "cuts")));
        this.fullStats = Objects.requireNonNull(fullStats, "fullStats");
        this.sparseStats = Objects.requireNonNull(sparseStats, "sparseStats");
        if (inventoryFragmentCount < 0
                || materializedFragmentCount < 0
                || materializedFragmentCount > inventoryFragmentCount) {
            throw new IllegalArgumentException(
                    "Invalid sparse-Root fragment counts");
        }
        this.inventoryFragmentCount = inventoryFragmentCount;
        this.materializedFragmentCount = materializedFragmentCount;
        this.assembledDirectlyFromInventory = assembledDirectlyFromInventory;
    }

    static ReferenceCutRootArtifact fromInventoryAssembly(
            String rootBlueId,
            String inventoryIdentity,
            Node sparseRoot,
            List<ReferenceCutPlan.Cut> cuts,
            int inventoryFragmentCount,
            int materializedFragmentCount) {
        NodeGraphStats sparseStats = NodeGraphStats.measure(sparseRoot);
        return new ReferenceCutRootArtifact(
                rootBlueId,
                inventoryIdentity,
                sparseRoot,
                cuts,
                sparseStats,
                sparseStats,
                inventoryFragmentCount,
                materializedFragmentCount,
                true);
    }

    public String rootBlueId() { return rootBlueId; }
    public String inventoryIdentity() { return inventoryIdentity; }
    public List<ReferenceCutPlan.Cut> cuts() { return cuts; }
    public NodeGraphStats fullStats() { return fullStats; }
    public NodeGraphStats sparseStats() { return sparseStats; }
    public int inventoryFragmentCount() { return inventoryFragmentCount; }
    public int materializedFragmentCount() {
        return materializedFragmentCount;
    }
    public boolean assembledDirectlyFromInventory() {
        return assembledDirectlyFromInventory;
    }
    public Node copyForFrozenBoundary() { return sparseRoot.toNode(); }
    public long approximateRetainedWeightBytes() {
        long weight = ReferenceCutRootCacheKey.addWeight(
                112L,
                sparseRoot.approximateRetainedWeightBytes());
        weight = ReferenceCutRootCacheKey.addWeight(
                weight,
                ReferenceCutRootCacheKey.stringWeight(rootBlueId));
        weight = ReferenceCutRootCacheKey.addWeight(
                weight,
                ReferenceCutRootCacheKey.stringWeight(inventoryIdentity));
        weight = ReferenceCutRootCacheKey.addWeight(
                weight,
                ReferenceCutRootCacheKey.listWeight(cuts.size()));
        for (ReferenceCutPlan.Cut cut : cuts) {
            weight = ReferenceCutRootCacheKey.addWeight(weight, 32L);
            weight = ReferenceCutRootCacheKey.addWeight(
                    weight,
                    ReferenceCutRootCacheKey.stringWeight(
                            cut.absolutePointer()));
            weight = ReferenceCutRootCacheKey.addWeight(
                    weight,
                    ReferenceCutRootCacheKey.stringWeight(
                            cut.childBlueId()));
        }
        return weight;
    }

    public double nodeReductionFraction() {
        long full = fullStats.nodes();
        if (full <= 0L) return 0.0d;
        return clamp((full - sparseStats.nodes()) / (double) full);
    }

    public double fragmentReductionFraction() {
        if (inventoryFragmentCount <= 0) return 0.0d;
        return clamp((inventoryFragmentCount - materializedFragmentCount)
                / (double) inventoryFragmentCount);
    }

    /** Best conservative reduction evidence available for this compiler path. */
    public double verifiedReductionFraction() {
        return assembledDirectlyFromInventory
                ? fragmentReductionFraction()
                : nodeReductionFraction();
    }

    private static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
