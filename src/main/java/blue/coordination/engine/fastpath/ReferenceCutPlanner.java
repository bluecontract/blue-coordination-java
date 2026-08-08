package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Produces one complete immutable sparse-Root preflight in a single pass.
 *
 * <p>Inventory edges are decorated once, sorted once, and evaluated once.
 * The cut index is a segment trie, so ancestor suppression is proportional
 * to pointer depth and never to the number of prior cuts.</p>
 */
public final class ReferenceCutPlanner {
    private final ReferenceCutPolicy policy;
    private final LongAdder planningPasses = new LongAdder();
    private final LongAdder inventoryScanPasses = new LongAdder();
    private final LongAdder inventoryEdgesScanned = new LongAdder();
    private final LongAdder inventoryEdgeSorts = new LongAdder();
    private final LongAdder cutAncestorLookups = new LongAdder();
    private final LongAdder cutAncestorSegmentProbes = new LongAdder();
    private final LongAdder cutIndexInsertSegmentProbes = new LongAdder();

    public ReferenceCutPlanner(ReferenceCutPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ReferenceCutPlan plan(
            CoordinationFragmentInventory inventory,
            ActivePathSet activePaths) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        ActivePathSet active = Objects.requireNonNull(activePaths, "activePaths");
        planningPasses.increment();

        List<CandidateEdge> ordered = new ArrayList<CandidateEdge>(
                checked.edges().size());
        inventoryScanPasses.increment();
        int scanned = 0;
        for (FragmentEdgeRecord edge : checked.edges()) {
            ordered.add(new CandidateEdge(edge));
            scanned++;
        }
        inventoryEdgesScanned.add(scanned);
        ordered.sort(CandidateEdge.ORDER);
        inventoryEdgeSorts.increment();

        List<ReferenceCutPlan.Cut> cuts =
                new ArrayList<ReferenceCutPlan.Cut>();
        List<ReferenceCutPlan.ExpandedEdge> expanded =
                new ArrayList<ReferenceCutPlan.ExpandedEdge>();
        Set<String> selectedBlueIds = new LinkedHashSet<String>();
        selectedBlueIds.add(checked.rootBlueId());
        CutPathIndex cutIndex = new CutPathIndex();
        for (CandidateEdge candidate : ordered) {
            if (cutIndex.hasAncestor(candidate.segments)) continue;
            FragmentEdgeRecord edge = candidate.edge;
            if (policy.mayCut(edge, active, candidate.path)) {
                cuts.add(new ReferenceCutPlan.Cut(
                        candidate.path, edge.childBlueId()));
                cutIndex.add(candidate.segments);
                continue;
            }
            if (edge.splitterCreated()) {
                expanded.add(candidate.expandedEdge());
                selectedBlueIds.add(edge.childBlueId());
            }
        }

        /* Ancestors were visited first. Reverse once to graft children into
         * their selected owners before those owners are grafted upward. */
        Collections.reverse(expanded);
        List<String> selected = new ArrayList<String>(selectedBlueIds);
        selected.sort(Comparator.naturalOrder());

        ReferenceCutPlan.PlanningWork work =
                new ReferenceCutPlan.PlanningWork(
                        1,
                        1,
                        scanned,
                        1,
                        cutIndex.lookups,
                        cutIndex.lookupSegmentProbes,
                        cutIndex.insertSegmentProbes);
        cutAncestorLookups.add(cutIndex.lookups);
        cutAncestorSegmentProbes.add(cutIndex.lookupSegmentProbes);
        cutIndexInsertSegmentProbes.add(cutIndex.insertSegmentProbes);
        return ReferenceCutPlan.validated(
                checked.rootBlueId(),
                checked.inventoryIdentity(),
                active,
                cuts,
                expanded,
                selected,
                checked.fragmentBlueIds().size(),
                work);
    }

    /** Cumulative real-site work, primarily for regression evidence. */
    public WorkSnapshot workSnapshot() {
        return new WorkSnapshot(
                planningPasses.sum(),
                inventoryScanPasses.sum(),
                inventoryEdgesScanned.sum(),
                inventoryEdgeSorts.sum(),
                cutAncestorLookups.sum(),
                cutAncestorSegmentProbes.sum(),
                cutIndexInsertSegmentProbes.sum());
    }

    public static final class WorkSnapshot {
        private final long planningPasses;
        private final long inventoryScanPasses;
        private final long inventoryEdgesScanned;
        private final long inventoryEdgeSorts;
        private final long cutAncestorLookups;
        private final long cutAncestorSegmentProbes;
        private final long cutIndexInsertSegmentProbes;

        private WorkSnapshot(
                long planningPasses,
                long inventoryScanPasses,
                long inventoryEdgesScanned,
                long inventoryEdgeSorts,
                long cutAncestorLookups,
                long cutAncestorSegmentProbes,
                long cutIndexInsertSegmentProbes) {
            this.planningPasses = planningPasses;
            this.inventoryScanPasses = inventoryScanPasses;
            this.inventoryEdgesScanned = inventoryEdgesScanned;
            this.inventoryEdgeSorts = inventoryEdgeSorts;
            this.cutAncestorLookups = cutAncestorLookups;
            this.cutAncestorSegmentProbes = cutAncestorSegmentProbes;
            this.cutIndexInsertSegmentProbes = cutIndexInsertSegmentProbes;
        }

        public long planningPasses() { return planningPasses; }
        public long inventoryScanPasses() { return inventoryScanPasses; }
        public long inventoryEdgesScanned() { return inventoryEdgesScanned; }
        public long inventoryEdgeSorts() { return inventoryEdgeSorts; }
        public long cutAncestorLookups() { return cutAncestorLookups; }
        public long cutAncestorSegmentProbes() {
            return cutAncestorSegmentProbes;
        }
        public long cutIndexInsertSegmentProbes() {
            return cutIndexInsertSegmentProbes;
        }

        public WorkSnapshot minus(WorkSnapshot previous) {
            WorkSnapshot before = Objects.requireNonNull(previous, "previous");
            return new WorkSnapshot(
                    planningPasses - before.planningPasses,
                    inventoryScanPasses - before.inventoryScanPasses,
                    inventoryEdgesScanned - before.inventoryEdgesScanned,
                    inventoryEdgeSorts - before.inventoryEdgeSorts,
                    cutAncestorLookups - before.cutAncestorLookups,
                    cutAncestorSegmentProbes
                            - before.cutAncestorSegmentProbes,
                    cutIndexInsertSegmentProbes
                            - before.cutIndexInsertSegmentProbes);
        }
    }

    private static final class CandidateEdge {
        private static final Comparator<CandidateEdge> ORDER =
                Comparator.comparingInt((CandidateEdge edge) -> edge.depth)
                        .thenComparing(edge -> edge.path)
                        .thenComparing(edge -> edge.edge.childBlueId());

        private final FragmentEdgeRecord edge;
        private final String path;
        private final List<String> segments;
        private final int depth;

        private CandidateEdge(FragmentEdgeRecord edge) {
            this.edge = Objects.requireNonNull(edge, "edge");
            this.path = JsonPointer.canonicalize(edge.absolutePointer());
            this.segments = JsonPointer.split(path);
            this.depth = segments.size();
        }

        private ReferenceCutPlan.ExpandedEdge expandedEdge() {
            List<String> relative = JsonPointer.split(
                    edge.ownerRelativePointer());
            if (relative.size() > segments.size()) {
                throw invalidOwnerPath();
            }
            int ownerSize = segments.size() - relative.size();
            for (int index = 0; index < relative.size(); index++) {
                if (!Objects.equals(
                        segments.get(ownerSize + index),
                        relative.get(index))) {
                    throw invalidOwnerPath();
                }
            }
            return new ReferenceCutPlan.ExpandedEdge(
                    path,
                    JsonPointer.toPointer(segments.subList(0, ownerSize)),
                    edge.ownerRelativePointer(),
                    edge.childBlueId());
        }

        private IllegalArgumentException invalidOwnerPath() {
            return new IllegalArgumentException(
                    "Relative path is not an absolute-path suffix for "
                            + path);
        }
    }

    private static final class CutPathIndex {
        private final TrieNode root = new TrieNode();
        private long lookups;
        private long lookupSegmentProbes;
        private long insertSegmentProbes;

        private boolean hasAncestor(List<String> segments) {
            lookups++;
            TrieNode cursor = root;
            if (cursor.cut) return true;
            for (String segment : segments) {
                lookupSegmentProbes++;
                cursor = cursor.children.get(segment);
                if (cursor == null) return false;
                if (cursor.cut) return true;
            }
            return false;
        }

        private void add(List<String> segments) {
            TrieNode cursor = root;
            for (String segment : segments) {
                insertSegmentProbes++;
                TrieNode next = cursor.children.get(segment);
                if (next == null) {
                    next = new TrieNode();
                    cursor.children.put(segment, next);
                }
                cursor = next;
            }
            cursor.cut = true;
        }
    }

    private static final class TrieNode {
        private final Map<String, TrieNode> children =
                new HashMap<String, TrieNode>();
        private boolean cut;
    }
}
