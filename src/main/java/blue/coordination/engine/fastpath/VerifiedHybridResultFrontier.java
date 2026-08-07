package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

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
 * Non-forgeable proof that a hybrid PROCESS result's retained references and
 * identity-equivalent representation boundaries are the exact values at the
 * same paths in one prepared prior epoch.
 *
 * <p>The proof intentionally exposes paths, not a caller-settable
 * {@code trusted} flag.  Its constructor is package-private and the sole
 * producer verifies every retained BlueId against the prepared epoch's
 * content-addressed index and prior Root object graph.</p>
 */
public final class VerifiedHybridResultFrontier {
    private final String sessionId;
    private final long priorEpoch;
    private final String priorRootBlueId;
    private final String priorInventoryIdentity;
    private final Node exactPriorRoot;
    private final Node requestOwnedResultRoot;
    private final Set<String> expandedPaths;
    private final Map<String, Node> priorExpandedNodeByPath;
    private final Map<String, String> retainedBlueIdByPath;
    private final Map<String, Node> retainedResolvedNodeByPath;
    private final Map<String, String> exactValueBoundaryBlueIdByPath;
    private final Map<String, String> newRuntimeBoundaryBlueIdByPath;
    private final Map<String, String> processEmbeddedBoundaryBlueIdByPath;
    private final Map<String, String> newSubtreeHeaderBlueIdByPath;
    private final Map<String, Node> newSubtreeHeaderResolvedNodeByPath;

    VerifiedHybridResultFrontier(
            String sessionId,
            long priorEpoch,
            String priorRootBlueId,
            String priorInventoryIdentity,
            Node exactPriorRoot,
            Node requestOwnedResultRoot,
            Collection<String> expandedPaths,
            Map<String, Node> priorExpandedNodeByPath,
            Map<String, String> retainedBlueIdByPath,
            Map<String, Node> retainedResolvedNodeByPath,
            Map<String, String> exactValueBoundaryBlueIdByPath,
            Map<String, String> newRuntimeBoundaryBlueIdByPath,
            Map<String, String> processEmbeddedBoundaryBlueIdByPath,
            Map<String, String> newSubtreeHeaderBlueIdByPath,
            Map<String, Node> newSubtreeHeaderResolvedNodeByPath) {
        this.sessionId = requireText(sessionId, "sessionId");
        if (priorEpoch < 0L) {
            throw new IllegalArgumentException(
                    "priorEpoch must be non-negative");
        }
        this.priorEpoch = priorEpoch;
        this.priorRootBlueId = requireText(
                priorRootBlueId, "priorRootBlueId");
        this.priorInventoryIdentity = requireText(
                priorInventoryIdentity, "priorInventoryIdentity");
        this.exactPriorRoot = Objects.requireNonNull(
                exactPriorRoot, "exactPriorRoot");
        this.requestOwnedResultRoot = Objects.requireNonNull(
                requestOwnedResultRoot, "requestOwnedResultRoot");
        this.expandedPaths = immutablePaths(expandedPaths);
        this.priorExpandedNodeByPath = Collections.unmodifiableMap(
                new LinkedHashMap<String, Node>(Objects.requireNonNull(
                        priorExpandedNodeByPath,
                        "priorExpandedNodeByPath")));
        if (!this.expandedPaths.containsAll(
                this.priorExpandedNodeByPath.keySet())) {
            throw new IllegalArgumentException(
                    "prior projection proof path is not expanded");
        }
        this.retainedBlueIdByPath = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(Objects.requireNonNull(
                        retainedBlueIdByPath,
                        "retainedBlueIdByPath")));
        this.retainedResolvedNodeByPath = Collections.unmodifiableMap(
                new LinkedHashMap<String, Node>(Objects.requireNonNull(
                        retainedResolvedNodeByPath,
                        "retainedResolvedNodeByPath")));
        if (!this.retainedBlueIdByPath.keySet().containsAll(
                this.retainedResolvedNodeByPath.keySet())) {
            throw new IllegalArgumentException(
                    "resolved retained proof path is not retained");
        }
        this.exactValueBoundaryBlueIdByPath = immutableBlueIds(
                exactValueBoundaryBlueIdByPath,
                "exact value boundary");
        for (String boundary : this.exactValueBoundaryBlueIdByPath.keySet()) {
            if (this.expandedPaths.contains(boundary)
                    || this.retainedBlueIdByPath.containsKey(boundary)) {
                throw new IllegalArgumentException(
                        "exact value boundary overlaps frontier: "
                                + boundary);
            }
        }
        this.newRuntimeBoundaryBlueIdByPath = immutableBlueIds(
                newRuntimeBoundaryBlueIdByPath,
                "new runtime boundary");
        for (String boundary : this.newRuntimeBoundaryBlueIdByPath.keySet()) {
            if (this.expandedPaths.contains(boundary)
                    || this.retainedBlueIdByPath.containsKey(boundary)
                    || this.exactValueBoundaryBlueIdByPath.containsKey(
                            boundary)) {
                throw new IllegalArgumentException(
                        "new runtime boundary overlaps frontier: "
                                + boundary);
            }
        }
        this.processEmbeddedBoundaryBlueIdByPath = immutableBlueIds(
                processEmbeddedBoundaryBlueIdByPath,
                "Process Embedded boundary");
        for (String boundary
                : this.processEmbeddedBoundaryBlueIdByPath.keySet()) {
            if (this.expandedPaths.contains(boundary)
                    || this.retainedBlueIdByPath.containsKey(boundary)
                    || this.exactValueBoundaryBlueIdByPath.containsKey(
                            boundary)
                    || this.newRuntimeBoundaryBlueIdByPath.containsKey(
                            boundary)) {
                throw new IllegalArgumentException(
                        "Process Embedded boundary overlaps frontier: "
                                + boundary);
            }
        }
        this.newSubtreeHeaderBlueIdByPath = immutableBlueIds(
                newSubtreeHeaderBlueIdByPath,
                "new-subtree header");
        this.newSubtreeHeaderResolvedNodeByPath =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, Node>(
                                Objects.requireNonNull(
                                        newSubtreeHeaderResolvedNodeByPath,
                                        "newSubtreeHeaderResolvedNodeByPath")));
        if (!this.newSubtreeHeaderBlueIdByPath.keySet().containsAll(
                this.newSubtreeHeaderResolvedNodeByPath.keySet())) {
            throw new IllegalArgumentException(
                    "resolved new-subtree header path is not proved");
        }
    }

    public String sessionId() { return sessionId; }
    public long priorEpoch() { return priorEpoch; }
    public String priorRootBlueId() { return priorRootBlueId; }
    public String priorInventoryIdentity() {
        return priorInventoryIdentity;
    }
    public Set<String> expandedPaths() { return expandedPaths; }
    public Map<String, String> retainedBlueIdByPath() {
        return retainedBlueIdByPath;
    }
    public Map<String, String> exactValueBoundaryBlueIdByPath() {
        return exactValueBoundaryBlueIdByPath;
    }
    public Map<String, String> newRuntimeBoundaryBlueIdByPath() {
        return newRuntimeBoundaryBlueIdByPath;
    }
    public Map<String, String> processEmbeddedBoundaryBlueIdByPath() {
        return processEmbeddedBoundaryBlueIdByPath;
    }
    public Map<String, String> newSubtreeHeaderBlueIdByPath() {
        return newSubtreeHeaderBlueIdByPath;
    }

    /** Verifies the exact borrowed prior Root object bound by this proof. */
    public boolean bindsPriorRoot(Node supplied) {
        return exactPriorRoot == supplied;
    }

    /**
     * Verifies the request-owned result after in-place retained-reference
     * resolution.  Resolution may replace children but must retain this Root
     * object.
     */
    public boolean bindsResultRoot(Node supplied) {
        return requestOwnedResultRoot == supplied;
    }

    /**
     * Ensures every retained path contains its exact admitted representative
     * (or the same unresolved BlueId), and every identity-equivalent boundary
     * still hashes to its proved prior identity. This prevents mutation
     * between proof creation and delta publication and proves that in-place
     * resolution completed.
     */
    public boolean retainedBindingsRemainExact(Node resolvedResultRoot) {
        Node root = Objects.requireNonNull(
                resolvedResultRoot, "resolvedResultRoot");
        for (Map.Entry<String, String> retained
                : retainedBlueIdByPath.entrySet()) {
            Node actual = structuralNodeAt(root, retained.getKey());
            Node resolved = retainedResolvedNodeByPath.get(
                    retained.getKey());
            if (resolved != null && actual != resolved) {
                return false;
            }
            if (resolved == null
                    && (actual == null
                        || !actual.isReferenceOnly()
                        || !retained.getValue().equals(
                                actual.getBlueId()))) {
                return false;
            }
        }
        if (!bindingsRemainExact(
                root,
                newSubtreeHeaderBlueIdByPath,
                newSubtreeHeaderResolvedNodeByPath)) {
            return false;
        }
        for (Map.Entry<String, String> exactValue
                : exactValueBoundaryBlueIdByPath.entrySet()) {
            if (!boundaryRemainsExact(root, exactValue)) return false;
        }
        for (Map.Entry<String, String> runtimeBoundary
                : newRuntimeBoundaryBlueIdByPath.entrySet()) {
            if (!boundaryRemainsExact(root, runtimeBoundary)) return false;
        }
        for (Map.Entry<String, String> processEmbeddedBoundary
                : processEmbeddedBoundaryBlueIdByPath.entrySet()) {
            if (!boundaryRemainsExact(root, processEmbeddedBoundary)) {
                return false;
            }
        }
        return true;
    }

    private static boolean boundaryRemainsExact(
            Node root, Map.Entry<String, String> expected) {
        Node actual = structuralNodeAt(root, expected.getKey());
        if (actual == null || actual.isReferenceOnly()) return false;
        try {
            return expected.getValue().equals(
                    DirectBlueIdCalculator.calculateBlueId(actual));
        } catch (RuntimeException invalidNode) {
            return false;
        }
    }

    private static boolean bindingsRemainExact(
            Node root,
            Map<String, String> expectedBlueIds,
            Map<String, Node> resolvedNodes) {
        for (Map.Entry<String, String> expected
                : expectedBlueIds.entrySet()) {
            Node actual = structuralNodeAt(root, expected.getKey());
            Node resolved = resolvedNodes.get(expected.getKey());
            if (resolved != null && actual != resolved) return false;
            if (resolved == null
                    && (actual == null
                            || !actual.isReferenceOnly()
                            || !expected.getValue().equals(
                                    actual.getBlueId()))) {
                return false;
            }
        }
        return true;
    }

    /** Selects a structural prior node using the frontier's internal paths. */
    public Node priorNodeAt(String path) {
        return priorExpandedNodeByPath.get(
                Objects.requireNonNull(path, "path"));
    }

    /** Selects a structural resulting node after in-place resolution. */
    public Node resultingNodeAt(Node resolvedResultRoot, String path) {
        if (!bindsResultRoot(resolvedResultRoot)) {
            return null;
        }
        return structuralNodeAt(
                resolvedResultRoot,
                Objects.requireNonNull(path, "path"));
    }

    private static Set<String> immutablePaths(
            Collection<String> supplied) {
        List<String> ordered = new ArrayList<String>(
                Objects.requireNonNull(supplied, "expandedPaths"));
        Collections.sort(ordered);
        Set<String> result = new LinkedHashSet<String>();
        for (String path : ordered) {
            String exact = Objects.requireNonNull(path, "expanded path");
            if (!exact.equals(JsonPointer.canonicalize(exact))) {
                throw new IllegalArgumentException(
                        "expanded path must be canonical: " + exact);
            }
            if (!result.add(exact)) {
                throw new IllegalArgumentException(
                        "duplicate expanded path: " + exact);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, String> immutableBlueIds(
            Map<String, String> supplied, String label) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry
                : Objects.requireNonNull(supplied, label).entrySet()) {
            String path = Objects.requireNonNull(
                    entry.getKey(), label + " path");
            if (!path.equals(JsonPointer.canonicalize(path))) {
                throw new IllegalArgumentException(
                        label + " path must be canonical: " + path);
            }
            String previous = result.put(
                    path, requireText(entry.getValue(), label + " BlueId"));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate " + label + " path: " + path);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Node structuralNodeAt(Node root, String pointer) {
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null || current.isReferenceOnly()) {
                return null;
            }
            current = structuralChild(current, segment);
        }
        return current;
    }

    private static Node structuralChild(Node parent, String segment) {
        if ("$type".equals(segment)) return parent.getType();
        if ("$itemType".equals(segment)) return parent.getItemType();
        if ("$keyType".equals(segment)) return parent.getKeyType();
        if ("$valueType".equals(segment)) return parent.getValueType();
        if ("$contracts".equals(segment)) return parent.getContracts();
        if ("$blue".equals(segment)) return parent.getBlue();
        if (JsonPointer.isArrayIndexSegment(segment)
                && parent.getItems() != null) {
            int index = Integer.parseInt(segment);
            return index < parent.getItems().size()
                    ? parent.getItems().get(index)
                    : null;
        }
        return parent.getProperties() != null
                ? parent.getProperties().get(segment)
                : null;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }
}
