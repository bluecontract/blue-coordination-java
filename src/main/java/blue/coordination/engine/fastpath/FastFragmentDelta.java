package blue.coordination.engine.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Engine-owned fragment delta. Unlike the public DTO, accessors do not clone
 * and re-hash every body. The content handles were verified on interning and
 * the final public result is materialized only if a caller actually asks.
 */
public final class FastFragmentDelta {
    private final VerifiedNodeAccessAuthority accessAuthority;
    private final CoordinationFragmentInventory inventory;
    private final Map<String, ExactNodeHandle> newFragments;
    private final Map<String, ExactNodeHandle> changedProcessingViews;
    private final Set<String> reused;
    private final Set<String> retired;
    private final List<FragmentEdgeRecord> addedEdges;
    private final List<FragmentEdgeRecord> retiredEdges;
    private final List<CoordinationScopeTransition> scopeTransitions;
    private final long requestIdentityCalculations;
    private final long requestIdentityMemoHits;
    private final AtomicLong defensiveNodeCopies = new AtomicLong();

    public FastFragmentDelta(
            VerifiedNodeAccessAuthority accessAuthority,
            CoordinationFragmentInventory inventory,
            Map<String, ExactNodeHandle> newFragments,
            Map<String, ExactNodeHandle> changedProcessingViews,
            Collection<String> reused,
            Collection<String> retired,
            Collection<FragmentEdgeRecord> addedEdges,
            Collection<FragmentEdgeRecord> retiredEdges,
            Collection<CoordinationScopeTransition> scopeTransitions,
            long requestIdentityCalculations,
            long requestIdentityMemoHits) {
        this.accessAuthority = Objects.requireNonNull(
                accessAuthority, "accessAuthority");
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
        if (requestIdentityCalculations < 0L
                || requestIdentityMemoHits < 0L) {
            throw new IllegalArgumentException(
                    "Request identity metrics must not be negative");
        }
        this.requestIdentityCalculations = requestIdentityCalculations;
        this.requestIdentityMemoHits = requestIdentityMemoHits;

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
    public Map<String, ExactNodeHandle> newFragments(
            VerifiedNodeAccessAuthority authority) {
        requireAuthority(authority);
        return newFragments;
    }
    public Map<String, ExactNodeHandle> changedProcessingViews(
            VerifiedNodeAccessAuthority authority) {
        requireAuthority(authority);
        return changedProcessingViews;
    }
    public Set<String> reused() { return reused; }
    public Set<String> retired() { return retired; }
    public List<FragmentEdgeRecord> addedEdges() { return addedEdges; }
    public List<FragmentEdgeRecord> retiredEdges() { return retiredEdges; }
    public List<CoordinationScopeTransition> scopeTransitions() {
        return scopeTransitions;
    }

    /** Materializes isolated public values only when a caller asks for them. */
    public Map<String, Node> materializeNewFragments(
            VerifiedNodeAccessAuthority authority) {
        return materialize(authority, newFragments);
    }

    /** Materializes isolated public values only when a caller asks for them. */
    public Map<String, Node> materializeChangedProcessingViews(
            VerifiedNodeAccessAuthority authority) {
        return materialize(authority, changedProcessingViews);
    }

    public long requestIdentityCalculations(
            VerifiedNodeAccessAuthority authority) {
        requireAuthority(authority);
        return requestIdentityCalculations;
    }

    public long requestIdentityMemoHits(
            VerifiedNodeAccessAuthority authority) {
        requireAuthority(authority);
        return requestIdentityMemoHits;
    }

    public long defensiveNodeCopies(
            VerifiedNodeAccessAuthority authority) {
        requireAuthority(authority);
        return defensiveNodeCopies.get();
    }

    private Map<String, Node> materialize(
            VerifiedNodeAccessAuthority authority,
            Map<String, ExactNodeHandle> handles) {
        requireAuthority(authority);
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, ExactNodeHandle> entry
                : handles.entrySet()) {
            result.put(entry.getKey(), entry.getValue().copy());
            defensiveNodeCopies.incrementAndGet();
        }
        return Collections.unmodifiableMap(result);
    }

    private void requireAuthority(VerifiedNodeAccessAuthority authority) {
        if (accessAuthority != Objects.requireNonNull(
                authority, "accessAuthority")) {
            throw new IllegalArgumentException(
                    "Fragment delta belongs to another engine authority");
        }
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
