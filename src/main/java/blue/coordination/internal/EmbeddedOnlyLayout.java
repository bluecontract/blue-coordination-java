package blue.coordination.internal;

import blue.coordination.api.ExactValue;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Semantic Root, ownership view, and one shell per embedded scope. */
final class EmbeddedOnlyLayout {
    private final ExactValue semanticRoot;
    private final FrozenNode processingRoot;
    private final Map<String, ExactValue> shellsByScope;
    private final List<EmbeddedBoundary> boundaries;
    private final List<EmbeddedOccurrence> directOccurrences;
    private final EmbeddedLayoutPlan plan;

    EmbeddedOnlyLayout(
            ExactValue semanticRoot,
            FrozenNode processingRoot,
            Map<String, ExactValue> shellsByScope,
            List<EmbeddedBoundary> boundaries,
            List<EmbeddedOccurrence> directOccurrences,
            EmbeddedLayoutPlan plan) {
        this.semanticRoot = Objects.requireNonNull(
                semanticRoot, "semanticRoot");
        this.processingRoot = Objects.requireNonNull(
                processingRoot, "processingRoot");
        this.shellsByScope = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        shellsByScope, "shellsByScope")));
        this.boundaries = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        boundaries, "boundaries")));
        this.directOccurrences = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        directOccurrences, "directOccurrences")));
        this.plan = Objects.requireNonNull(plan, "plan");
        ExactValue rootShell = this.shellsByScope.get(JsonPointer.ROOT);
        if (rootShell == null) {
            throw new IllegalArgumentException("Layout must contain Root scope");
        }
        if (!semanticRoot.blueId().equals(rootShell.blueId())) {
            throw new IllegalArgumentException(
                    "Root shell must preserve semantic Root identity");
        }
    }

    public String rootBlueId() {
        return semanticRoot.blueId();
    }

    public ExactValue semanticRoot() {
        return semanticRoot;
    }

    public EmbeddedLayoutPlan plan() {
        return plan;
    }

    public RoutingSurface routingSurface() {
        return plan.routingSurface();
    }

    public Set<String> scopePaths() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(shellsByScope.keySet()));
    }

    public List<EmbeddedBoundary> boundaries() {
        return boundaries;
    }

    public int physicalObjectCount() {
        return shellsByScope.size();
    }

    FrozenNode processingFrozen() {
        return processingRoot;
    }

    /** Fully materialized semantic Root, used only at API/assertion boundaries. */
    public Node reconstructRoot() {
        return semanticRoot.copyNode();
    }

    public ExactValue stored(String scopePath) {
        ExactValue value = shellsByScope.get(
                Objects.requireNonNull(scopePath, "scopePath"));
        if (value == null) {
            throw new IllegalArgumentException("Unknown scope " + scopePath);
        }
        return value;
    }

    /**
     * Direct managed children were compiled once with this layout. Reading
     * them does not reconstruct child graphs or recalculate BlueIds.
     */
    public List<EmbeddedOccurrence> directOccurrences() {
        return directOccurrences;
    }
}
