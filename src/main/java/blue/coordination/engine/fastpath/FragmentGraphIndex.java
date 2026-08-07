package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentMetadataRecord;
import blue.coordination.processor.CoordinationDocumentSplitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-inventory adjacency and metadata index. It replaces repeated full edge
 * scans and list membership tests in bundle closure calculation. Construction
 * is O(V+E) once per committed inventory; each request closure is O(Vselected +
 * Eselected).
 */
public final class FragmentGraphIndex {
    private final String inventoryIdentity;
    private final String rootBlueId;
    private final Set<String> fragments;
    private final Map<String, List<FragmentEdgeRecord>> outgoing;
    private final Set<String> executableBodies;
    private final Set<String> sourceContributions;
    private final long approximateRetainedWeightBytes;

    public FragmentGraphIndex(CoordinationFragmentInventory inventory) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        this.inventoryIdentity = checked.inventoryIdentity();
        this.rootBlueId = checked.rootBlueId();
        this.fragments = Collections.unmodifiableSet(
                new LinkedHashSet<String>(checked.fragmentBlueIds()));
        Map<String, List<FragmentEdgeRecord>> mutable =
                new LinkedHashMap<String, List<FragmentEdgeRecord>>();
        for (FragmentEdgeRecord edge : checked.edges()) {
            mutable.computeIfAbsent(
                    edge.ownerNodeBlueId(), ignored ->
                            new ArrayList<FragmentEdgeRecord>()).add(edge);
        }
        Map<String, List<FragmentEdgeRecord>> frozen =
                new LinkedHashMap<String, List<FragmentEdgeRecord>>();
        for (Map.Entry<String, List<FragmentEdgeRecord>> entry
                : mutable.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<FragmentEdgeRecord>(entry.getValue())));
        }
        this.outgoing = Collections.unmodifiableMap(frozen);
        Set<String> bodies = new LinkedHashSet<String>();
        Set<String> contributions = new LinkedHashSet<String>();
        for (FragmentMetadataRecord metadata : checked.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter.FragmentKind
                    .EXECUTABLE_BODY) {
                bodies.add(metadata.blueId());
            }
            if (metadata.kind()
                    == CoordinationDocumentSplitter.FragmentKind
                    .SOURCE_CONTRIBUTION) {
                contributions.add(metadata.blueId());
            }
        }
        this.executableBodies = Collections.unmodifiableSet(bodies);
        this.sourceContributions = Collections.unmodifiableSet(contributions);
        this.approximateRetainedWeightBytes = retainedWeight(
                this.fragments.size(),
                this.outgoing,
                this.executableBodies.size(),
                this.sourceContributions.size());
    }

    public Set<String> selectedClosure(Collection<String> seeds) {
        return closure(seeds, edge -> edge.splitterCreated());
    }

    public Set<String> selectedSeedAndContributionClosure(
            Collection<String> seeds) {
        Set<String> result = new LinkedHashSet<String>();
        ArrayDeque<String> queue = seedQueue(seeds, result);
        while (!queue.isEmpty()) {
            String owner = queue.removeFirst();
            for (FragmentEdgeRecord edge : outgoing(owner)) {
                admit(edge.childBlueId(), result, queue);
                for (String contribution
                        : edge.sourceContributionBlueIds()) {
                    admit(contribution, result, queue);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public Set<String> rootHeaderClosure() {
        return closure(Collections.singleton(rootBlueId), edge ->
                edge.edgeKind()
                        != CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT
                        && !executableBodies.contains(edge.childBlueId()));
    }

    public Set<String> fragmentBlueIds() { return fragments; }
    public Set<String> executableBodyBlueIds() { return executableBodies; }
    public boolean isSourceContribution(String blueId) {
        return sourceContributions.contains(blueId);
    }
    public String inventoryIdentity() { return inventoryIdentity; }
    public String rootBlueId() { return rootBlueId; }
    public long approximateRetainedWeightBytes() {
        return approximateRetainedWeightBytes;
    }
    public List<FragmentEdgeRecord> outgoing(String ownerBlueId) {
        List<FragmentEdgeRecord> values = outgoing.get(ownerBlueId);
        return values == null
                ? Collections.<FragmentEdgeRecord>emptyList()
                : values;
    }

    private Set<String> closure(
            Collection<String> seeds, EdgePredicate predicate) {
        Set<String> result = new LinkedHashSet<String>();
        ArrayDeque<String> queue = seedQueue(seeds, result);
        while (!queue.isEmpty()) {
            String owner = queue.removeFirst();
            for (FragmentEdgeRecord edge : outgoing(owner)) {
                if (predicate.include(edge)) {
                    admit(edge.childBlueId(), result, queue);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private ArrayDeque<String> seedQueue(
            Collection<String> seeds, Set<String> result) {
        ArrayDeque<String> queue = new ArrayDeque<String>();
        for (String seed : Objects.requireNonNull(seeds, "seeds")) {
            admit(seed, result, queue);
        }
        return queue;
    }

    private void admit(
            String blueId, Set<String> result, ArrayDeque<String> queue) {
        if (fragments.contains(blueId) && result.add(blueId)) {
            queue.addLast(blueId);
        }
    }

    @FunctionalInterface
    private interface EdgePredicate {
        boolean include(FragmentEdgeRecord edge);
    }

    private static long retainedWeight(
            int fragmentCount,
            Map<String, List<FragmentEdgeRecord>> outgoing,
            int executableBodyCount,
            int sourceContributionCount) {
        /* Inventory strings and edge records are authoritative immutable
         * values owned by the fragment store. Charge only the index-owned
         * containers/references so shared inventory evidence is not counted
         * once per derived cache. */
        long weight = 256L;
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                64L + RetainedNodeWeight.saturatedMultiply(
                        40L, fragmentCount));
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                64L + RetainedNodeWeight.saturatedMultiply(
                        40L, outgoing.size()));
        for (List<FragmentEdgeRecord> edges : outgoing.values()) {
            weight = RetainedNodeWeight.saturatedAdd(
                    weight,
                    32L + RetainedNodeWeight.saturatedMultiply(
                            8L, edges.size()));
        }
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                64L + RetainedNodeWeight.saturatedMultiply(
                        40L, executableBodyCount));
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                64L + RetainedNodeWeight.saturatedMultiply(
                        40L, sourceContributionCount));
        return Math.max(1L, weight);
    }
}
