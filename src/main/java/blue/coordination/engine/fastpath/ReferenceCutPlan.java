package blue.coordination.engine.fastpath;

import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, inventory-bound preflight for one exact active-path surface.
 *
 * <p>A planner-produced instance contains every topology decision required by
 * direct sparse-Root assembly. The compiler consequently performs no policy
 * evaluation, inventory-edge scan, edge sort, or fragment-count estimation.
 * The legacy three-argument constructor is intentionally unsealed and cannot
 * cross the compiler boundary; it remains only for source compatibility and
 * fail-closed validation tests.</p>
 */
public final class ReferenceCutPlan {
    public static final class Cut {
        private final String absolutePointer;
        private final String childBlueId;

        public Cut(String absolutePointer, String childBlueId) {
            this.absolutePointer = JsonPointer.canonicalize(
                    Objects.requireNonNull(
                            absolutePointer, "absolutePointer"));
            this.childBlueId = requireText(childBlueId, "childBlueId");
        }

        public String absolutePointer() { return absolutePointer; }
        public String childBlueId() { return childBlueId; }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Cut)) return false;
            Cut other = (Cut) value;
            return absolutePointer.equals(other.absolutePointer)
                    && childBlueId.equals(other.childBlueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(absolutePointer, childBlueId);
        }

        @Override
        public String toString() {
            return "Cut{absolutePointer='" + absolutePointer
                    + "', childBlueId='" + childBlueId + "'}";
        }
    }

    /** Exact selected splitter-created occurrence, already in graft order. */
    public static final class ExpandedEdge {
        private final String absolutePointer;
        private final String ownerPointer;
        private final String ownerRelativePointer;
        private final String childBlueId;

        ExpandedEdge(
                String absolutePointer,
                String ownerPointer,
                String ownerRelativePointer,
                String childBlueId) {
            this.absolutePointer = JsonPointer.canonicalize(
                    Objects.requireNonNull(
                            absolutePointer, "absolutePointer"));
            this.ownerPointer = JsonPointer.canonicalize(
                    Objects.requireNonNull(ownerPointer, "ownerPointer"));
            this.ownerRelativePointer = JsonPointer.canonicalize(
                    Objects.requireNonNull(
                            ownerRelativePointer, "ownerRelativePointer"));
            this.childBlueId = requireText(childBlueId, "childBlueId");
        }

        public String absolutePointer() { return absolutePointer; }
        public String ownerPointer() { return ownerPointer; }
        public String ownerRelativePointer() { return ownerRelativePointer; }
        public String childBlueId() { return childBlueId; }
    }

    /** Work evidence populated at the real planning sites. */
    public static final class PlanningWork {
        private final int planningPasses;
        private final int inventoryScanPasses;
        private final int inventoryEdgesScanned;
        private final int inventoryEdgeSorts;
        private final long cutAncestorLookups;
        private final long cutAncestorSegmentProbes;
        private final long cutIndexInsertSegmentProbes;

        PlanningWork(
                int planningPasses,
                int inventoryScanPasses,
                int inventoryEdgesScanned,
                int inventoryEdgeSorts,
                long cutAncestorLookups,
                long cutAncestorSegmentProbes,
                long cutIndexInsertSegmentProbes) {
            this.planningPasses = nonNegative(
                    planningPasses, "planningPasses");
            this.inventoryScanPasses = nonNegative(
                    inventoryScanPasses, "inventoryScanPasses");
            this.inventoryEdgesScanned = nonNegative(
                    inventoryEdgesScanned, "inventoryEdgesScanned");
            this.inventoryEdgeSorts = nonNegative(
                    inventoryEdgeSorts, "inventoryEdgeSorts");
            this.cutAncestorLookups = nonNegative(
                    cutAncestorLookups, "cutAncestorLookups");
            this.cutAncestorSegmentProbes = nonNegative(
                    cutAncestorSegmentProbes,
                    "cutAncestorSegmentProbes");
            this.cutIndexInsertSegmentProbes = nonNegative(
                    cutIndexInsertSegmentProbes,
                    "cutIndexInsertSegmentProbes");
        }

        public int planningPasses() { return planningPasses; }
        public int inventoryScanPasses() { return inventoryScanPasses; }
        public int inventoryEdgesScanned() { return inventoryEdgesScanned; }
        public int inventoryEdgeSorts() { return inventoryEdgeSorts; }
        public long cutAncestorLookups() { return cutAncestorLookups; }
        public long cutAncestorSegmentProbes() {
            return cutAncestorSegmentProbes;
        }
        public long cutIndexInsertSegmentProbes() {
            return cutIndexInsertSegmentProbes;
        }
    }

    private final String rootBlueId;
    private final String inventoryIdentity;
    private final String activePathIdentity;
    private final List<String> activePaths;
    private final List<Cut> cuts;
    private final List<ExpandedEdge> expandedEdges;
    private final List<String> selectedBlueIds;
    private final int totalFragmentCount;
    private final int selectedFragmentCount;
    private final double fragmentReductionFraction;
    private final PlanningWork planningWork;
    private final boolean validatedPreflight;

    /**
     * Legacy unsealed shape. Direct compilation rejects this value even when
     * its Root and inventory strings happen to match.
     */
    @Deprecated
    public ReferenceCutPlan(
            String rootBlueId,
            String inventoryIdentity,
            List<Cut> cuts) {
        this(
                rootBlueId,
                inventoryIdentity,
                "unsealed",
                Collections.<String>emptyList(),
                cuts,
                Collections.<ExpandedEdge>emptyList(),
                Collections.<String>emptyList(),
                0,
                new PlanningWork(0, 0, 0, 0, 0L, 0L, 0L),
                false);
    }

    static ReferenceCutPlan validated(
            String rootBlueId,
            String inventoryIdentity,
            ActivePathSet activePaths,
            List<Cut> cuts,
            List<ExpandedEdge> expandedEdges,
            List<String> selectedBlueIds,
            int totalFragmentCount,
            PlanningWork planningWork) {
        ActivePathSet active = Objects.requireNonNull(
                activePaths, "activePaths");
        return new ReferenceCutPlan(
                rootBlueId,
                inventoryIdentity,
                active.identity(),
                active.paths(),
                cuts,
                expandedEdges,
                selectedBlueIds,
                totalFragmentCount,
                planningWork,
                true);
    }

    private ReferenceCutPlan(
            String rootBlueId,
            String inventoryIdentity,
            String activePathIdentity,
            List<String> activePaths,
            List<Cut> cuts,
            List<ExpandedEdge> expandedEdges,
            List<String> selectedBlueIds,
            int totalFragmentCount,
            PlanningWork planningWork,
            boolean validatedPreflight) {
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.inventoryIdentity = requireText(
                inventoryIdentity, "inventoryIdentity");
        this.activePathIdentity = requireText(
                activePathIdentity, "activePathIdentity");
        this.activePaths = immutableCopy(activePaths, "activePaths");
        this.cuts = Collections.unmodifiableList(
                new ArrayList<Cut>(Objects.requireNonNull(cuts, "cuts")));
        this.expandedEdges = Collections.unmodifiableList(
                new ArrayList<ExpandedEdge>(Objects.requireNonNull(
                        expandedEdges, "expandedEdges")));
        this.selectedBlueIds = immutableCopy(
                selectedBlueIds, "selectedBlueIds");
        this.totalFragmentCount = nonNegative(
                totalFragmentCount, "totalFragmentCount");
        this.selectedFragmentCount = this.selectedBlueIds.size();
        if (selectedFragmentCount > totalFragmentCount) {
            throw new IllegalArgumentException(
                    "selected fragments exceed total fragments");
        }
        this.fragmentReductionFraction = totalFragmentCount == 0
                ? 0.0d
                : clamp((totalFragmentCount - selectedFragmentCount)
                        / (double) totalFragmentCount);
        this.planningWork = Objects.requireNonNull(
                planningWork, "planningWork");
        this.validatedPreflight = validatedPreflight;
    }

    public String rootBlueId() { return rootBlueId; }
    public String inventoryIdentity() { return inventoryIdentity; }
    public String activePathIdentity() { return activePathIdentity; }
    public List<String> activePaths() { return activePaths; }
    public List<Cut> cuts() { return cuts; }
    public List<ExpandedEdge> expandedEdges() { return expandedEdges; }
    public List<String> selectedBlueIds() { return selectedBlueIds; }
    public int totalFragmentCount() { return totalFragmentCount; }
    public int selectedFragmentCount() { return selectedFragmentCount; }
    public double fragmentReductionFraction() {
        return fragmentReductionFraction;
    }
    public PlanningWork planningWork() { return planningWork; }
    public boolean isFullRoot() { return cuts.isEmpty(); }

    boolean isValidatedPreflight() { return validatedPreflight; }

    private static List<String> immutableCopy(
            List<String> source,
            String label) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) requireText(value, label + " entry");
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    private static int nonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    private static long nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    private static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
