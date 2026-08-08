package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.processor.CoordinationExactNodeIndex;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable pre-resolution PROCESS frontier for fragment-inventory grafting.
 *
 * <p>The snapshot contains expanded changed nodes and exact pure-reference
 * boundaries only. Retained prior subtrees are therefore not cloned into the
 * event-local artifact. Construction is restricted to a path-bound
 * {@link VerifiedHybridResultFrontier} proof.</p>
 */
public final class VerifiedFragmentTransitionFrontier {
    private final VerifiedHybridResultFrontier bindingProof;
    private final String sessionId;
    private final long priorEpoch;
    private final String priorRootBlueId;
    private final String priorInventoryIdentity;
    private final String resultingRootBlueId;
    private final FrozenNode sparseResultRoot;
    private final Set<String> expandedPaths;
    private final Map<String, String> retainedBlueIdByPath;
    private final Map<String, String> retainedBlueIdByPhysicalPath;
    private final Map<String, String> expandedBlueIdByPath;

    VerifiedFragmentTransitionFrontier(
            VerifiedHybridResultFrontier bindingProof,
            Node requestOwnedHybridRoot,
            String resultingRootBlueId) {
        this.bindingProof = Objects.requireNonNull(
                bindingProof, "bindingProof");
        Node hybrid = Objects.requireNonNull(
                requestOwnedHybridRoot, "requestOwnedHybridRoot");
        if (!bindingProof.bindsResultRoot(hybrid)) {
            throw new IllegalArgumentException(
                    "Hybrid Root belongs to another frontier proof");
        }
        this.resultingRootBlueId = requireText(
                resultingRootBlueId, "resultingRootBlueId");
        this.sessionId = bindingProof.sessionId();
        this.priorEpoch = bindingProof.priorEpoch();
        this.priorRootBlueId = bindingProof.priorRootBlueId();
        this.priorInventoryIdentity =
                bindingProof.priorInventoryIdentity();
        this.sparseResultRoot = FrozenNode.fromNode(hybrid);
        this.expandedPaths = Collections.unmodifiableSet(
                new LinkedHashSet<String>(bindingProof.expandedPaths()));
        CoordinationExactNodeIndex identities =
                new CoordinationExactNodeIndex();
        String actualRootBlueId = identities.blueId(hybrid);
        if (!this.resultingRootBlueId.equals(actualRootBlueId)) {
            throw new IllegalArgumentException(
                    "Hybrid frontier Root identity differs from verified "
                            + "PROCESS output");
        }
        Map<String, String> expandedIdentities =
                new LinkedHashMap<String, String>();
        for (String path : this.expandedPaths) {
            Node expanded = structuralNodeAt(hybrid, path);
            if (expanded == null || expanded.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Expanded frontier path is not inline: " + path);
            }
            expandedIdentities.put(path, identities.blueId(expanded));
        }
        this.expandedBlueIdByPath = Collections.unmodifiableMap(
                expandedIdentities);
        this.retainedBlueIdByPath = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(
                        bindingProof.retainedBlueIdByPath()));
        this.retainedBlueIdByPhysicalPath = physicalRetainedPaths(
                hybrid, this.retainedBlueIdByPath);
    }

    public String sessionId() { return sessionId; }
    public long priorEpoch() { return priorEpoch; }
    public String priorRootBlueId() { return priorRootBlueId; }
    public String priorInventoryIdentity() { return priorInventoryIdentity; }
    public String resultingRootBlueId() { return resultingRootBlueId; }
    public Set<String> expandedPaths() { return expandedPaths; }
    public Map<String, String> retainedBlueIdByPath() {
        return retainedBlueIdByPath;
    }
    public Map<String, String> retainedBlueIdByPhysicalPath() {
        return retainedBlueIdByPhysicalPath;
    }
    public Map<String, String> expandedBlueIdByPath() {
        return expandedBlueIdByPath;
    }
    public int sparseExpandedNodeCount() { return expandedPaths.size(); }

    /** Returns one request-owned sparse materialization for the graft pass. */
    public Node sparseResultRoot() {
        return sparseResultRoot.toNode();
    }

    /**
     * Rechecks generation, prior inventory, result object, and every retained
     * binding after in-place reference resolution.
     */
    public boolean remainsBound(
            CoordinationFragmentInventory priorInventory,
            Node resolvedResultRoot) {
        CoordinationFragmentInventory prior = Objects.requireNonNull(
                priorInventory, "priorInventory");
        return priorRootBlueId.equals(prior.rootBlueId())
                && priorInventoryIdentity.equals(prior.inventoryIdentity())
                && bindingProof.bindsResultRoot(resolvedResultRoot)
                && bindingProof.retainedBindingsRemainExact(
                        resolvedResultRoot);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    /** Converts structural frontier paths to canonical physical pointers. */
    private static Map<String, String> physicalRetainedPaths(
            Node root,
            Map<String, String> retainedByStructuralPath) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> retained
                : retainedByStructuralPath.entrySet()) {
            Node current = root;
            String physical = "/";
            for (String segment : JsonPointer.split(retained.getKey())) {
                if (current == null || current.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Retained frontier path crosses a prior boundary: "
                                    + retained.getKey());
                }
                String physicalSegment;
                Node child;
                if ("$type".equals(segment)) {
                    physicalSegment = "type";
                    child = current.getType();
                } else if ("$itemType".equals(segment)) {
                    physicalSegment = "itemType";
                    child = current.getItemType();
                } else if ("$keyType".equals(segment)) {
                    physicalSegment = "keyType";
                    child = current.getKeyType();
                } else if ("$valueType".equals(segment)) {
                    physicalSegment = "valueType";
                    child = current.getValueType();
                } else if ("$contracts".equals(segment)) {
                    physicalSegment = "contracts";
                    child = current.getContracts();
                } else if ("$blue".equals(segment)) {
                    physicalSegment = "blue";
                    child = current.getBlue();
                } else if (current.getItems() != null) {
                    int index = parseIndex(segment, retained.getKey());
                    if (index >= current.getItems().size()) {
                        throw new IllegalArgumentException(
                                "Retained frontier item path is out of range: "
                                        + retained.getKey());
                    }
                    physical = JsonPointer.append(physical, "items");
                    physicalSegment = segment;
                    child = current.getItems().get(index);
                } else {
                    physicalSegment = segment;
                    child = current.getProperties() == null
                            ? null
                            : current.getProperties().get(segment);
                }
                physical = JsonPointer.append(physical, physicalSegment);
                current = child;
            }
            if (current == null
                    || !current.isReferenceOnly()
                    || !retained.getValue().equals(current.getBlueId())) {
                throw new IllegalArgumentException(
                        "Retained frontier path lost its exact boundary: "
                                + retained.getKey());
            }
            String previous = result.put(physical, retained.getValue());
            if (previous != null && !previous.equals(retained.getValue())) {
                throw new IllegalArgumentException(
                        "Two retained boundaries map to one physical path: "
                                + physical);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Node structuralNodeAt(Node root, String path) {
        Node current = root;
        for (String segment : JsonPointer.split(path)) {
            if (current == null || current.isReferenceOnly()) return null;
            if ("$type".equals(segment)) {
                current = current.getType();
            } else if ("$itemType".equals(segment)) {
                current = current.getItemType();
            } else if ("$keyType".equals(segment)) {
                current = current.getKeyType();
            } else if ("$valueType".equals(segment)) {
                current = current.getValueType();
            } else if ("$contracts".equals(segment)) {
                current = current.getContracts();
            } else if ("$blue".equals(segment)) {
                current = current.getBlue();
            } else if (current.getItems() != null) {
                int index = parseIndex(segment, path);
                current = index < current.getItems().size()
                        ? current.getItems().get(index)
                        : null;
            } else {
                current = current.getProperties() == null
                        ? null
                        : current.getProperties().get(segment);
            }
        }
        return current;
    }

    private static int parseIndex(String value, String path) {
        try {
            if (value.isEmpty() || (value.length() > 1
                    && value.charAt(0) == '0')) {
                throw new NumberFormatException(value);
            }
            int index = Integer.parseInt(value);
            if (index < 0) throw new NumberFormatException(value);
            return index;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Invalid retained frontier item path: " + path,
                    invalid);
        }
    }
}
