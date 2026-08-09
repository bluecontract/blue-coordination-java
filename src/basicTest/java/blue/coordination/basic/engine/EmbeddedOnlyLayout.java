package blue.coordination.basic.engine;

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
public final class EmbeddedOnlyLayout {
    public static final String PROFILE_ID =
            "blue.coordination/basic/process-embedded-only/4.0";

    private final ExactNodeValue semanticRoot;
    private final FrozenNode processingRoot;
    private final Map<String, ExactNodeValue> shellsByScope;
    private final List<EmbeddedBoundary> boundaries;
    private final List<EmbeddedOccurrence> directOccurrences;
    private final EmbeddedLayoutPlan plan;

    EmbeddedOnlyLayout(
            ExactNodeValue semanticRoot,
            FrozenNode processingRoot,
            Map<String, ExactNodeValue> shellsByScope,
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
        ExactNodeValue rootShell = this.shellsByScope.get(JsonPointer.ROOT);
        if (rootShell == null) {
            throw new IllegalArgumentException("Layout must contain Root scope");
        }
        if (!semanticRoot.blueId().equals(rootShell.blueId())) {
            throw new IllegalArgumentException(
                    "Root shell must preserve semantic Root identity");
        }
    }

    public String profileId() {
        return PROFILE_ID;
    }

    public String rootBlueId() {
        return semanticRoot.blueId();
    }

    public ExactNodeValue semanticRoot() {
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

    public int embeddedDocumentCount() {
        return shellsByScope.size() - 1;
    }

    public int splitterCreatedEdgeCount() {
        return Math.toIntExact(boundaries.stream()
                .filter(EmbeddedBoundary::splitterCreated)
                .count());
    }

    FrozenNode processingFrozen() {
        return processingRoot;
    }

    /** Fully materialized semantic Root, used only at API/assertion boundaries. */
    public Node reconstructRoot() {
        return semanticRoot.copyNode();
    }

    /** Fully materialized exact scope without recursive mutable assembly. */
    public Node reconstructScope(String scopePath) {
        String path = JsonPointer.canonicalize(
                Objects.requireNonNull(scopePath, "scopePath"));
        FrozenNode selected = JsonPointer.ROOT.equals(path)
                ? semanticRoot.frozen()
                : semanticRoot.canonicalAt(path);
        if (selected == null) {
            throw new IllegalArgumentException("Unknown scope " + path);
        }
        return selected.toNode();
    }

    public ExactNodeValue stored(String scopePath) {
        ExactNodeValue value = shellsByScope.get(
                Objects.requireNonNull(scopePath, "scopePath"));
        if (value == null) {
            throw new IllegalArgumentException("Unknown scope " + scopePath);
        }
        return value;
    }

    /**
     * Direct autonomous children were compiled once with this layout. Reading
     * them does not reconstruct child graphs or recalculate BlueIds.
     */
    public List<EmbeddedOccurrence> directOccurrences() {
        return directOccurrences;
    }
}
