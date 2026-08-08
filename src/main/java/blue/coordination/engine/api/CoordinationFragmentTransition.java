package blue.coordination.engine.api;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.coordination.engine.fastpath.FastFragmentDelta;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable physical fragment and occurrence delta for one PROCESS result. */
public final class CoordinationFragmentTransition {

    private final CoordinationFragmentInventory resultingInventory;
    private final Map<String, Node> newFragments;
    private final Map<String, Node> processingViews;
    private final Set<String> reusedFragmentBlueIds;
    private final Set<String> retiredFragmentBlueIds;
    private final List<FragmentEdgeRecord> addedEdges;
    private final List<FragmentEdgeRecord> retiredEdges;
    private final List<CoordinationScopeTransition> scopeTransitions;
    private final VerifiedNodeAccessAuthority verifiedAuthority;
    private final FastFragmentDelta verifiedDelta;

    public CoordinationFragmentTransition(
            CoordinationFragmentInventory resultingInventory,
            Map<String, Node> newFragments,
            Collection<String> reusedFragmentBlueIds,
            Collection<FragmentEdgeRecord> addedEdges,
            Collection<FragmentEdgeRecord> retiredEdges,
            Collection<CoordinationScopeTransition> scopeTransitions) {
        this(
                resultingInventory,
                newFragments,
                Collections.<String, Node>emptyMap(),
                reusedFragmentBlueIds,
                Collections.<String>emptySet(),
                addedEdges,
                retiredEdges,
                scopeTransitions);
    }

    public CoordinationFragmentTransition(
            CoordinationFragmentInventory resultingInventory,
            Map<String, Node> newFragments,
            Map<String, Node> processingViews,
            Collection<String> reusedFragmentBlueIds,
            Collection<FragmentEdgeRecord> addedEdges,
            Collection<FragmentEdgeRecord> retiredEdges,
            Collection<CoordinationScopeTransition> scopeTransitions) {
        this(
                resultingInventory,
                newFragments,
                processingViews,
                reusedFragmentBlueIds,
                Collections.<String>emptySet(),
                addedEdges,
                retiredEdges,
                scopeTransitions);
    }

    /**
     * Creates one closed fragment transition including exact retirements.
     *
     * <p>Retirement is occurrence/inventory state only. Immutable fragment
     * stores do not delete the corresponding content and may still reuse it
     * from another document session or historical epoch.</p>
     */
    public CoordinationFragmentTransition(
            CoordinationFragmentInventory resultingInventory,
            Map<String, Node> newFragments,
            Map<String, Node> processingViews,
            Collection<String> reusedFragmentBlueIds,
            Collection<String> retiredFragmentBlueIds,
            Collection<FragmentEdgeRecord> addedEdges,
            Collection<FragmentEdgeRecord> retiredEdges,
            Collection<CoordinationScopeTransition> scopeTransitions) {
        this.resultingInventory = Objects.requireNonNull(
                resultingInventory, "resultingInventory");
        this.newFragments = immutableFragments(newFragments);
        this.processingViews = immutableFragments(processingViews);
        this.reusedFragmentBlueIds = immutableTextSet(
                reusedFragmentBlueIds, "reusedFragmentBlueIds");
        this.retiredFragmentBlueIds = immutableTextSet(
                retiredFragmentBlueIds, "retiredFragmentBlueIds");
        this.addedEdges = immutableSorted(addedEdges, "addedEdges");
        this.retiredEdges = immutableSorted(retiredEdges, "retiredEdges");
        this.scopeTransitions = Collections.unmodifiableList(
                new ArrayList<CoordinationScopeTransition>(
                        Objects.requireNonNull(
                                scopeTransitions, "scopeTransitions")));
        this.verifiedAuthority = null;
        this.verifiedDelta = null;
        validateCoverage(
                this.newFragments.keySet(),
                this.processingViews.keySet());
    }

    private CoordinationFragmentTransition(
            VerifiedNodeAccessAuthority verifiedAuthority,
            FastFragmentDelta verifiedDelta) {
        this.verifiedAuthority = Objects.requireNonNull(
                verifiedAuthority, "verifiedAuthority");
        this.verifiedDelta = Objects.requireNonNull(
                verifiedDelta, "verifiedDelta");
        this.resultingInventory = verifiedDelta.inventory();
        this.newFragments = Collections.emptyMap();
        this.processingViews = Collections.emptyMap();
        this.reusedFragmentBlueIds = verifiedDelta.reused();
        this.retiredFragmentBlueIds = verifiedDelta.retired();
        this.addedEdges = verifiedDelta.addedEdges();
        this.retiredEdges = verifiedDelta.retiredEdges();
        this.scopeTransitions = verifiedDelta.scopeTransitions();
        validateCoverage(
                verifiedDelta.newFragments(verifiedAuthority).keySet(),
                verifiedDelta.changedProcessingViews(verifiedAuthority)
                        .keySet());
    }

    /**
     * Carries an engine-verified delta without materializing mutable DTO
     * bodies. The authority is unforgeable and is retained only internally.
     */
    public static CoordinationFragmentTransition fromVerifiedDelta(
            VerifiedNodeAccessAuthority authority,
            FastFragmentDelta delta) {
        return new CoordinationFragmentTransition(authority, delta);
    }

    private void validateCoverage(
            Collection<String> newFragmentBlueIds,
            Collection<String> processingViewBlueIds) {
        Set<String> overlap = new LinkedHashSet<String>(
                newFragmentBlueIds);
        overlap.retainAll(this.reusedFragmentBlueIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Fragments cannot be both new and reused: " + overlap);
        }
        Set<String> complete = new LinkedHashSet<String>(
                newFragmentBlueIds);
        complete.addAll(this.reusedFragmentBlueIds);
        if (!complete.equals(new LinkedHashSet<String>(
                resultingInventory.fragmentBlueIds()))) {
            throw new IllegalArgumentException(
                    "New and reused fragments do not cover resulting inventory");
        }
        if (!resultingInventory.fragmentBlueIds().containsAll(
                processingViewBlueIds)) {
            throw new IllegalArgumentException(
                    "PROCESS views must belong to the resulting inventory");
        }
        Set<String> retainedRetirements = new LinkedHashSet<String>(
                this.retiredFragmentBlueIds);
        retainedRetirements.retainAll(resultingInventory.fragmentBlueIds());
        if (!retainedRetirements.isEmpty()) {
            throw new IllegalArgumentException(
                    "Retired fragments remain in the resulting inventory: "
                            + retainedRetirements);
        }
    }

    public CoordinationFragmentInventory resultingInventory() {
        return resultingInventory;
    }
    public Map<String, Node> newFragments() {
        return verifiedDelta == null
                ? defensiveFragments(newFragments)
                : verifiedDelta.materializeNewFragments(verifiedAuthority);
    }
    public Map<String, Node> processingViews() {
        return verifiedDelta == null
                ? defensiveFragments(processingViews)
                : verifiedDelta.materializeChangedProcessingViews(
                        verifiedAuthority);
    }
    public Set<String> reusedFragmentBlueIds() {
        return reusedFragmentBlueIds;
    }
    public Set<String> retiredFragmentBlueIds() {
        return retiredFragmentBlueIds;
    }
    public List<FragmentEdgeRecord> addedEdges() { return addedEdges; }
    public List<FragmentEdgeRecord> retiredEdges() { return retiredEdges; }
    public List<CoordinationScopeTransition> scopeTransitions() {
        return scopeTransitions;
    }

    /** Engine-only access to the verified carrier, guarded by exact token. */
    public FastFragmentDelta verifiedDelta(
            VerifiedNodeAccessAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        if (verifiedDelta == null) {
            return null;
        }
        if (verifiedAuthority != authority) {
            throw new IllegalArgumentException(
                    "Fragment transition belongs to another engine");
        }
        return verifiedDelta;
    }

    private static Map<String, Node> immutableFragments(
            Map<String, Node> source) {
        Map<String, Node> result = new TreeMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : Objects.requireNonNull(source, "newFragments").entrySet()) {
            Node node = Objects.requireNonNull(
                    entry.getValue(), "new fragment").clone();
            String actual = DirectBlueIdCalculator.calculateBlueId(
                    node);
            if (!entry.getKey().equals(actual)) {
                throw new IllegalArgumentException(
                        "New fragment identity mismatch for " + entry.getKey());
            }
            Node previous = result.put(entry.getKey(), node);
            if (previous != null
                    && !NodeWireForm.get(previous).equals(NodeWireForm.get(node))) {
                throw new IllegalArgumentException(
                        "Conflicting new fragment: " + entry.getKey());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns isolated mutable values without repeating constructor-time
     * canonical identity verification. The retained map is private and its
     * Nodes never escape directly, so rehashing on every getter adds no
     * integrity evidence.
     */
    private static Map<String, Node> defensiveFragments(
            Map<String, Node> source) {
        Map<String, Node> result = new TreeMap<String, Node>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> immutableTextSet(
            Collection<String> source,
            String label) {
        Set<String> result = new LinkedHashSet<String>();
        for (String value : Objects.requireNonNull(source, label)) {
            if (value == null || value.isEmpty() || !result.add(value)) {
                throw new IllegalArgumentException(
                        label + " contains an empty or duplicate value");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static List<FragmentEdgeRecord> immutableSorted(
            Collection<FragmentEdgeRecord> source,
            String label) {
        List<FragmentEdgeRecord> result =
                new ArrayList<FragmentEdgeRecord>(
                        Objects.requireNonNull(source, label));
        for (FragmentEdgeRecord record : result) {
            Objects.requireNonNull(record, label + " entry");
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }
}
