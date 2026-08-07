package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Raw output of the one-pass splitter/assembler adapter. */
public final class AssembledInventoryDelta {
    private final CoordinationFragmentInventory inventory;
    private final Map<String, Node> newFragmentBodies;
    private final Map<String, Node> changedProcessingViews;
    private final List<CoordinationScopeTransition> scopeTransitions;

    public AssembledInventoryDelta(
            CoordinationFragmentInventory inventory,
            Map<String, Node> newFragmentBodies,
            Map<String, Node> changedProcessingViews,
            Collection<CoordinationScopeTransition> scopeTransitions) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.newFragmentBodies = Collections.unmodifiableMap(
                new LinkedHashMap<String, Node>(Objects.requireNonNull(
                        newFragmentBodies, "newFragmentBodies")));
        this.changedProcessingViews = Collections.unmodifiableMap(
                new LinkedHashMap<String, Node>(Objects.requireNonNull(
                        changedProcessingViews,
                        "changedProcessingViews")));
        this.scopeTransitions = Collections.unmodifiableList(
                new ArrayList<CoordinationScopeTransition>(
                        Objects.requireNonNull(
                                scopeTransitions, "scopeTransitions")));
    }

    public CoordinationFragmentInventory inventory() { return inventory; }
    public Map<String, Node> newFragmentBodies() { return newFragmentBodies; }
    public Map<String, Node> changedProcessingViews() {
        return changedProcessingViews;
    }
    public List<CoordinationScopeTransition> scopeTransitions() {
        return scopeTransitions;
    }
}
