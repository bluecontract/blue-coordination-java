package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.engine.api.FragmentEdgeRecord;

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
 * Engine-owned fragment delta. Unlike the public DTO, accessors do not clone
 * and re-hash every body. The content handles were verified on interning and
 * the final public result is materialized only if a caller actually asks.
 */
public final class FastFragmentDelta {
    private final CoordinationFragmentInventory inventory;
    private final Map<String, ExactNodeHandle> newFragments;
    private final Map<String, ExactNodeHandle> changedProcessingViews;
    private final Set<String> reused;
    private final Set<String> retired;
    private final List<FragmentEdgeRecord> addedEdges;
    private final List<FragmentEdgeRecord> retiredEdges;
    private final List<CoordinationScopeTransition> scopeTransitions;

    public FastFragmentDelta(
            CoordinationFragmentInventory inventory,
            Map<String, ExactNodeHandle> newFragments,
            Map<String, ExactNodeHandle> changedProcessingViews,
            Collection<String> reused,
            Collection<String> retired,
            Collection<FragmentEdgeRecord> addedEdges,
            Collection<FragmentEdgeRecord> retiredEdges,
            Collection<CoordinationScopeTransition> scopeTransitions) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.newFragments = handles(newFragments, "newFragments");
        this.changedProcessingViews = handles(
                changedProcessingViews, "changedProcessingViews");
        this.reused = immutableSet(reused, "reused");
        this.retired = immutableSet(retired, "retired");
        this.addedEdges = immutableList(addedEdges, "addedEdges");
        this.retiredEdges = immutableList(retiredEdges, "retiredEdges");
        this.scopeTransitions = immutableList(
                scopeTransitions, "scopeTransitions");

        Set<String> coverage = new LinkedHashSet<String>(
                this.newFragments.keySet());
        if (!Collections.disjoint(coverage, this.reused)) {
            throw new IllegalArgumentException(
                    "New and reused fragments overlap");
        }
        coverage.addAll(this.reused);
        if (!coverage.equals(new LinkedHashSet<String>(
                inventory.fragmentBlueIds()))) {
            throw new IllegalArgumentException(
                    "Delta does not cover resulting inventory");
        }
        if (!inventory.fragmentBlueIds().containsAll(
                this.changedProcessingViews.keySet())) {
            throw new IllegalArgumentException(
                    "Processing view is outside resulting inventory");
        }
        if (!Collections.disjoint(
                inventory.fragmentBlueIds(), this.retired)) {
            throw new IllegalArgumentException(
                    "Retired fragment remains in resulting inventory");
        }
    }

    public CoordinationFragmentInventory inventory() { return inventory; }
    public Map<String, ExactNodeHandle> newFragments() { return newFragments; }
    public Map<String, ExactNodeHandle> changedProcessingViews() {
        return changedProcessingViews;
    }
    public Set<String> reused() { return reused; }
    public Set<String> retired() { return retired; }
    public List<FragmentEdgeRecord> addedEdges() { return addedEdges; }
    public List<FragmentEdgeRecord> retiredEdges() { return retiredEdges; }
    public List<CoordinationScopeTransition> scopeTransitions() {
        return scopeTransitions;
    }

    private static Map<String, ExactNodeHandle> handles(
            Map<String, ExactNodeHandle> source, String label) {
        Map<String, ExactNodeHandle> result =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, ExactNodeHandle> entry
                : Objects.requireNonNull(source, label).entrySet()) {
            ExactNodeHandle handle = Objects.requireNonNull(
                    entry.getValue(), label + " handle");
            if (!entry.getKey().equals(handle.blueId())) {
                throw new IllegalArgumentException(
                        label + " identity mismatch at " + entry.getKey());
            }
            result.put(entry.getKey(), handle);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> immutableSet(
            Collection<String> source, String label) {
        Set<String> result = new LinkedHashSet<String>();
        for (String value : Objects.requireNonNull(source, label)) {
            if (value == null || value.isEmpty() || !result.add(value)) {
                throw new IllegalArgumentException(
                        label + " contains an invalid value");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static <T> List<T> immutableList(
            Collection<T> source, String label) {
        List<T> result = new ArrayList<T>(
                Objects.requireNonNull(source, label));
        for (T value : result) Objects.requireNonNull(value, label + " item");
        return Collections.unmodifiableList(result);
    }
}
