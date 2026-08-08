package blue.coordination.processor;

import blue.coordination.processor.support.CoordinationProcessHeaderSupport;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.model.wire.JsonPointer;
import blue.language.provider.ExactNodeGraphFragments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Diagnostic reconstruction and validation for a canonical Coordination
 * fragment inventory.
 *
 * <p>This operation is deliberately outside PROCESS. It expands only edges
 * marked as splitter-created, preserves authored references, and never asks a
 * provider to fabricate content for an opaque cyclic-member reference.</p>
 */
public final class CoordinationFragmentReconstructor {

    private CoordinationFragmentReconstructor() {
    }

    /**
     * Expands one retained fragment from an already selected local closure.
     * Authored pure references remain opaque; only splitter-created physical
     * edges are opened. This is the request-local counterpart to reconstructing
     * an entire semantic Root.
     */
    public static Node reconstructSelectedFragment(
            String profileIdentity,
            String semanticRootBlueId,
            String fragmentBlueId,
            Map<String, Node> selectedFragments,
            List<CoordinationDocumentSplitter.EdgeOccurrence>
                    selectedEdges) {
        return reconstructSelectedFragment(
                profileIdentity,
                semanticRootBlueId,
                fragmentBlueId,
                selectedFragments,
                selectedEdges,
                Collections.<String>emptySet());
    }

    /**
     * Expands a selected fragment while retaining nominated dependency roots
     * as exact references. PROCESS uses this to keep executable bodies lazy
     * even when their enclosing structural chain is materialized.
     */
    public static Node reconstructSelectedFragment(
            String profileIdentity,
            String semanticRootBlueId,
            String fragmentBlueId,
            Map<String, Node> selectedFragments,
            List<CoordinationDocumentSplitter.EdgeOccurrence> selectedEdges,
            Set<String> opaqueChildBlueIds) {
        Map<String, Node> fragments = immutableFragments(selectedFragments);
        List<CoordinationDocumentSplitter.EdgeOccurrence> edges =
                physicalEdges(
                        fragments,
                        immutableEdges(selectedEdges));
        SortedMap<String, SortedMap<String,
                CoordinationDocumentSplitter.EdgeOccurrence>> indexed =
                indexEdges(
                        Objects.requireNonNull(
                                profileIdentity, "profileIdentity"),
                        Objects.requireNonNull(
                                semanticRootBlueId, "semanticRootBlueId"),
                        fragments,
                        edges);
        verifyEveryPhysicalReferenceDescribed(fragments, indexed);
        Node expanded = expand(
                Objects.requireNonNull(fragmentBlueId, "fragmentBlueId"),
                fragments,
                indexed,
                new HashSet<String>(),
                new HashSet<String>(),
                Collections.unmodifiableSet(new HashSet<String>(
                        Objects.requireNonNull(
                                opaqueChildBlueIds,
                                "opaqueChildBlueIds"))));
        expanded = CoordinationProcessHeaderSupport.canonicalExactCopy(
                expanded);
        requireIdentity(
                fragmentBlueId,
                expanded,
                "Selected reconstructed fragment");
        return expanded;
    }

    /**
     * One semantic BlueId can occur through more than one physical owner
     * shape in the enclosing inventory.  A selected request contains one
     * canonical direct fragment for that identity, so retain only occurrence
     * records that are physical edges of that exact fragment body.
     */
    private static List<CoordinationDocumentSplitter.EdgeOccurrence>
    physicalEdges(
            Map<String, Node> fragments,
            List<CoordinationDocumentSplitter.EdgeOccurrence> edges) {
        List<CoordinationDocumentSplitter.EdgeOccurrence> result =
                new ArrayList<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence edge : edges) {
            Node owner = fragments.get(edge.ownerNodeBlueId());
            if (isPhysicalEdge(
                    owner,
                    edge.ownerRelativePointer(),
                    edge.childBlueId())) {
                result.add(edge);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns whether an occurrence describes this exact direct body. */
    public static boolean isPhysicalEdge(
            Node owner,
            String ownerRelativePointer,
            String childBlueId) {
        Node child = owner != null
                ? structuralChild(owner, ownerRelativePointer)
                : null;
        return child != null
                && child.isReferenceOnly()
                && Objects.equals(childBlueId, child.getBlueId());
    }

    /**
     * Reconstructs the requested semantic Root from exact fragments and edge
     * occurrence metadata.
     *
     * @param profileIdentity stable physical profile identity
     * @param rootBlueId requested semantic Root identity
     * @param fragmentRoots exact roots retained in the physical inventory
     * @param fragments immutable fragment content keyed by exact BlueId
     * @param edgeOccurrences exact direct-edge occurrences
     * @return reconstructed exact semantic Root
     */
    public static Node reconstruct(
            String profileIdentity,
            String rootBlueId,
            Collection<CoordinationDocumentSplitter.FragmentRoot>
                    fragmentRoots,
            Map<String, Node> fragments,
            Collection<CoordinationDocumentSplitter.EdgeOccurrence>
                    edgeOccurrences) {
        if (!CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID.equals(
                profileIdentity)) {
            throw evidenceFailure(
                    "Unsupported fragmentation profile "
                            + profileIdentity);
        }
        Objects.requireNonNull(rootBlueId, "rootBlueId");
        List<CoordinationDocumentSplitter.FragmentRoot> roots =
                immutableRoots(fragmentRoots);
        SortedMap<String, Node> retained =
                immutableFragments(fragments);
        List<CoordinationDocumentSplitter.EdgeOccurrence> edges =
                immutableEdges(edgeOccurrences);
        if (roots.isEmpty()) {
            throw evidenceFailure(
                    "Fragment inventory has no exact roots");
        }
        boolean requestedRootRetained = false;
        for (CoordinationDocumentSplitter.FragmentRoot root : roots) {
            if (rootBlueId.equals(root.blueId())
                    && (root.kind()
                    == CoordinationDocumentSplitter
                    .FragmentRootKind.DOCUMENT
                    || root.kind()
                    == CoordinationDocumentSplitter
                    .FragmentRootKind.EVENT)) {
                requestedRootRetained = true;
            }
        }
        if (!requestedRootRetained) {
            throw evidenceFailure(
                    "Requested semantic Root is not retained as a document "
                            + "or event root: "
                            + rootBlueId);
        }

        SortedMap<String, SortedMap<String,
                CoordinationDocumentSplitter.EdgeOccurrence>>
                edgesByOwner = indexEdges(
                profileIdentity,
                rootBlueId,
                retained,
                edges);
        verifyEveryPhysicalReferenceDescribed(
                retained,
                edgesByOwner);

        Set<String> active = new HashSet<>();
        Set<String> used = new HashSet<>();
        SortedMap<String, Node> reconstructedRoots =
                new TreeMap<>();
        for (CoordinationDocumentSplitter.FragmentRoot root : roots) {
            Node reconstructed = expand(
                    root.blueId(),
                    retained,
                    edgesByOwner,
                    active,
                    used,
                    Collections.<String>emptySet());
            Node previous =
                    reconstructedRoots.put(
                            root.blueId(),
                            reconstructed);
            if (previous != null
                    && !sameNode(
                    previous,
                    reconstructed)) {
                throw evidenceFailure(
                        "Repeated fragment Root reconstructs inconsistently: "
                                + root.blueId());
            }
        }
        if (!used.equals(retained.keySet())) {
            Set<String> extra =
                    new HashSet<>(
                            retained.keySet());
            extra.removeAll(used);
            throw evidenceFailure(
                    "Fragment inventory contains unreachable or mixed-profile "
                            + "content: "
                            + extra);
        }

        verifyCanonicalInventory(
                roots,
                reconstructedRoots,
                retained);
        Node requested =
                reconstructedRoots.get(
                        rootBlueId);
        if (requested == null) {
            throw evidenceFailure(
                    "Requested Root was not reconstructed: "
                            + rootBlueId);
        }
        requireIdentity(
                rootBlueId,
                requested,
                "Reconstructed semantic Root");
        return requested.clone();
    }

    private static SortedMap<String, SortedMap<String,
            CoordinationDocumentSplitter.EdgeOccurrence>>
    indexEdges(
            String profileIdentity,
            String rootBlueId,
            Map<String, Node> fragments,
            List<CoordinationDocumentSplitter.EdgeOccurrence> edges) {
        SortedMap<String, SortedMap<String,
                CoordinationDocumentSplitter.EdgeOccurrence>>
                indexed = new TreeMap<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence edge : edges) {
            if (!profileIdentity.equals(
                    edge.fragmentationProfileIdentity())) {
                throw evidenceFailure(
                        "Mixed fragmentation profiles at "
                                + edge.absolutePointer());
            }
            if (!CoordinationDocumentSplitter
                    .EDGE_METADATA_SCHEMA_ID.equals(
                            edge.schemaIdentity())) {
                throw evidenceFailure(
                        "Unsupported edge metadata schema at "
                                + edge.absolutePointer());
            }
            if (!rootBlueId.equals(
                    edge.rootBlueId())) {
                throw evidenceFailure(
                        "Edge occurrence is bound to another semantic Root at "
                                + edge.absolutePointer());
            }
            if (!fragments.containsKey(
                    edge.ownerNodeBlueId())) {
                throw evidenceFailure(
                        "Edge owner fragment is missing: "
                                + edge.ownerNodeBlueId());
            }
            SortedMap<String,
                    CoordinationDocumentSplitter.EdgeOccurrence>
                    byPointer =
                    indexed.computeIfAbsent(
                            edge.ownerNodeBlueId(),
                            ignored -> new TreeMap<>());
            CoordinationDocumentSplitter.EdgeOccurrence previous =
                    byPointer.putIfAbsent(
                            edge.ownerRelativePointer(),
                            edge);
            if (previous != null
                    && (!previous.childBlueId().equals(
                    edge.childBlueId())
                    || previous.splitterCreated()
                    != edge.splitterCreated()
                    || previous.originalPureReference()
                    != edge.originalPureReference())) {
                throw evidenceFailure(
                        "One physical owner edge has inconsistent occurrence "
                                + "metadata: "
                                + edge.ownerNodeBlueId()
                                + edge.ownerRelativePointer());
            }
        }
        return indexed;
    }

    private static Node expand(
            String blueId,
            Map<String, Node> fragments,
            Map<String, SortedMap<String,
                    CoordinationDocumentSplitter.EdgeOccurrence>>
                    edgesByOwner,
            Set<String> active,
            Set<String> used,
            Set<String> opaqueChildBlueIds) {
        Node direct =
                fragments.get(
                        blueId);
        if (direct == null) {
            throw evidenceFailure(
                    "Required exact fragment is missing: "
                            + blueId);
        }
        requireIdentity(
                blueId,
                direct,
                "Stored direct fragment");
        if (!active.add(blueId)) {
            throw evidenceFailure(
                    "Local fragment inventory contains a cycle at "
                            + blueId
                            + "; cyclic members must remain opaque");
        }
        try {
            used.add(blueId);
            Node expanded = direct.clone();
            if (expanded.getBlueId() != null
                    && !expanded.isReferenceOnly()) {
                expanded.blueId(null);
            }
            Map<String,
                    CoordinationDocumentSplitter.EdgeOccurrence>
                    ownerEdges =
                    edgesByOwner.get(
                            blueId);
            if (ownerEdges == null) {
                return expanded;
            }
            for (CoordinationDocumentSplitter.EdgeOccurrence edge
                    : ownerEdges.values()) {
                Node current =
                        structuralChild(
                                direct,
                                edge.ownerRelativePointer());
                if (current == null
                        || !current.isReferenceOnly()
                        || !edge.childBlueId().equals(
                        current.getBlueId())) {
                    throw evidenceFailure(
                            "Edge metadata disagrees with stored owner "
                                    + blueId
                                    + edge.ownerRelativePointer());
                }
                if (edge.originalPureReference()) {
                    continue;
                }
                if (!edge.splitterCreated()) {
                    throw evidenceFailure(
                            "Non-authored edge is not marked splitter-created "
                                    + "at "
                                + edge.absolutePointer());
                }
                if (opaqueChildBlueIds.contains(edge.childBlueId())) {
                    continue;
                }
                // A PROCESS header view may carry its established identity
                // together with physical reference fields. Once a
                // splitter-created child is opened, the result is semantic
                // content rather than an established-reference envelope.
                // Keeping the marker would create an invalid blueId+sibling
                // hybrid even though the expanded content has the same exact
                // identity.
                Node child = expand(
                        edge.childBlueId(),
                        fragments,
                        edgesByOwner,
                        active,
                        used,
                        opaqueChildBlueIds);
                putStructuralChild(
                        expanded,
                        edge.ownerRelativePointer(),
                        child);
            }
            requireIdentity(
                    blueId,
                    expanded,
                    "Reconstructed fragment");
            return expanded;
        } finally {
            active.remove(blueId);
        }
    }

    private static void verifyEveryPhysicalReferenceDescribed(
            Map<String, Node> fragments,
            Map<String, SortedMap<String,
                    CoordinationDocumentSplitter.EdgeOccurrence>>
                    edgesByOwner) {
        for (Map.Entry<String, Node> fragment
                : fragments.entrySet()) {
            SortedMap<String, String> references =
                    directReferenceChildren(
                            fragment.getValue());
            Map<String,
                    CoordinationDocumentSplitter.EdgeOccurrence>
                    described =
                    edgesByOwner.get(
                            fragment.getKey());
            Set<String> describedPointers =
                    described != null
                            ? described.keySet()
                            : Collections
                            .<String>emptySet();
            if (references.containsKey("/type")
                    && !describedPointers.contains("/type")
                    && isCanonicalImplicitScalarType(
                    fragment.getKey(), fragment.getValue())) {
                references.remove("/type");
            }
            if (!references.keySet().equals(
                    describedPointers)) {
                throw evidenceFailure(
                        "Edge occurrence inventory is incomplete or contains "
                                + "nonphysical edges for owner "
                                + fragment.getKey()
                                + ": physical="
                                + references.keySet()
                                + ", described="
                                + describedPointers);
            }
            if (described != null) {
                for (Map.Entry<String, String> reference
                        : references.entrySet()) {
                    if (!reference.getValue().equals(
                            described.get(
                                    reference.getKey())
                                    .childBlueId())) {
                        throw evidenceFailure(
                                "Edge child identity disagrees at "
                                        + fragment.getKey()
                                        + reference.getKey());
                    }
                }
            }
        }
    }

    private static boolean isCanonicalImplicitScalarType(
            String blueId,
            Node node) {
        if (node.getRawValue() == null
                || node.getType() == null
                || !node.getType().isReferenceOnly()
                || node.getName() != null
                || node.getDescription() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getContracts() != null
                || node.getBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null) {
            return false;
        }
        return blueId.equals(
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(node.getRawValue())));
    }

    private static void verifyCanonicalInventory(
            List<CoordinationDocumentSplitter.FragmentRoot> roots,
            Map<String, Node> reconstructedRoots,
            Map<String, Node> retained) {
        List<Node> exactRoots =
                new ArrayList<>();
        for (CoordinationDocumentSplitter.FragmentRoot root : roots) {
            exactRoots.add(
                    reconstructedRoots.get(
                            root.blueId()));
        }
        Map<String, Node> canonical =
                new ExactNodeGraphFragments(
                        exactRoots).fragments();
        if (!canonical.keySet().equals(
                retained.keySet())) {
            throw evidenceFailure(
                    "Reconstructed roots do not produce the supplied exact "
                            + "fragment keys");
        }
        for (String blueId : canonical.keySet()) {
            if (!sameNode(
                    canonical.get(blueId),
                    retained.get(blueId))) {
                throw evidenceFailure(
                        "Mixed or noncanonical physical representation for "
                                + blueId);
            }
        }
    }

    private static SortedMap<String, String>
    directReferenceChildren(Node node) {
        SortedMap<String, String> result =
                new TreeMap<>();
        addReference(result, "/type", node.getType());
        addReference(result, "/itemType", node.getItemType());
        addReference(result, "/keyType", node.getKeyType());
        addReference(result, "/valueType", node.getValueType());
        addReference(result, "/contracts", node.getContracts());
        addReference(result, "/blue", node.getBlue());
        if (node.getItems() != null) {
            for (int index = 0;
                    index < node.getItems().size();
                    index++) {
                addReference(
                        result,
                        JsonPointer.toPointer(
                                java.util.Arrays.asList(
                                        "items",
                                        String.valueOf(index))),
                        node.getItems().get(index));
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> property
                    : node.getProperties().entrySet()) {
                addReference(
                        result,
                        JsonPointer.toPointer(
                                Collections.singletonList(
                                        property.getKey())),
                        property.getValue());
            }
        }
        Schema schema = node.getSchema();
        if (schema != null
                && !schema.isReferenceOnly()) {
            addReference(
                    result,
                    "/schema/minimum",
                    schema.getMinimum());
            addReference(
                    result,
                    "/schema/maximum",
                    schema.getMaximum());
            addReference(
                    result,
                    "/schema/exclusiveMinimum",
                    schema.getExclusiveMinimum());
            addReference(
                    result,
                    "/schema/exclusiveMaximum",
                    schema.getExclusiveMaximum());
            addReference(
                    result,
                    "/schema/multipleOf",
                    schema.getMultipleOf());
            if (schema.getEnum() != null) {
                for (int index = 0;
                        index < schema.getEnum().size();
                        index++) {
                    addReference(
                            result,
                            JsonPointer.toPointer(
                                    java.util.Arrays.asList(
                                            "schema",
                                            "enum",
                                            String.valueOf(index))),
                            schema.getEnum().get(index));
                }
            }
        }
        return result;
    }

    private static void addReference(
            Map<String, String> result,
            String pointer,
            Node child) {
        if (child != null
                && child.isReferenceOnly()) {
            result.put(
                    pointer,
                    child.getBlueId());
        }
    }

    private static Node structuralChild(
            Node owner,
            String pointer) {
        List<String> segments =
                JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            return owner;
        }
        String first = segments.get(0);
        if ("type".equals(first)) {
            return owner.getType();
        }
        if ("itemType".equals(first)) {
            return owner.getItemType();
        }
        if ("keyType".equals(first)) {
            return owner.getKeyType();
        }
        if ("valueType".equals(first)) {
            return owner.getValueType();
        }
        if ("contracts".equals(first)) {
            return owner.getContracts();
        }
        if ("blue".equals(first)) {
            return owner.getBlue();
        }
        if ("items".equals(first)) {
            if (segments.size() != 2
                    || owner.getItems() == null) {
                return null;
            }
            int index =
                    Integer.parseInt(
                            segments.get(1));
            return index < owner.getItems().size()
                    ? owner.getItems().get(index)
                    : null;
        }
        if ("schema".equals(first)) {
            return schemaChild(
                    owner.getSchema(),
                    segments);
        }
        return owner.getProperties() != null
                ? owner.getProperties().get(first)
                : null;
    }

    private static Node schemaChild(
            Schema schema,
            List<String> segments) {
        if (schema == null
                || segments.size() < 2) {
            return null;
        }
        String key = segments.get(1);
        if ("minimum".equals(key)) {
            return schema.getMinimum();
        }
        if ("maximum".equals(key)) {
            return schema.getMaximum();
        }
        if ("exclusiveMinimum".equals(key)) {
            return schema.getExclusiveMinimum();
        }
        if ("exclusiveMaximum".equals(key)) {
            return schema.getExclusiveMaximum();
        }
        if ("multipleOf".equals(key)) {
            return schema.getMultipleOf();
        }
        if ("enum".equals(key)
                && segments.size() == 3
                && schema.getEnum() != null) {
            int index =
                    Integer.parseInt(
                            segments.get(2));
            return index < schema.getEnum().size()
                    ? schema.getEnum().get(index)
                    : null;
        }
        return null;
    }

    private static void putStructuralChild(
            Node owner,
            String pointer,
            Node child) {
        List<String> segments =
                JsonPointer.split(pointer);
        if (segments.size() == 1) {
            String first = segments.get(0);
            if ("type".equals(first)) {
                owner.type(child);
                return;
            }
            if ("itemType".equals(first)) {
                owner.itemType(child);
                return;
            }
            if ("keyType".equals(first)) {
                owner.keyType(child);
                return;
            }
            if ("valueType".equals(first)) {
                owner.valueType(child);
                return;
            }
            if ("contracts".equals(first)) {
                owner.contracts(child);
                return;
            }
            if ("blue".equals(first)) {
                owner.blue(child);
                return;
            }
            if (owner.getProperties() == null) {
                owner.properties(
                        new LinkedHashMap<String, Node>());
            }
            owner.getProperties().put(
                    first,
                    child);
            return;
        }
        if (segments.size() == 2
                && "items".equals(
                segments.get(0))) {
            owner.getItems().set(
                    Integer.parseInt(
                            segments.get(1)),
                    child);
            return;
        }
        if (segments.size() >= 2
                && "schema".equals(
                segments.get(0))) {
            putSchemaChild(
                    owner.getSchema(),
                    segments,
                    child);
            return;
        }
        throw evidenceFailure(
                "Unsupported direct edge pointer "
                        + pointer);
    }

    private static void putSchemaChild(
            Schema schema,
            List<String> segments,
            Node child) {
        if (schema == null) {
            throw evidenceFailure(
                    "Schema edge has no owner schema");
        }
        String key = segments.get(1);
        if ("minimum".equals(key)) {
            schema.minimum(child);
            return;
        }
        if ("maximum".equals(key)) {
            schema.maximum(child);
            return;
        }
        if ("exclusiveMinimum".equals(key)) {
            schema.exclusiveMinimum(child);
            return;
        }
        if ("exclusiveMaximum".equals(key)) {
            schema.exclusiveMaximum(child);
            return;
        }
        if ("multipleOf".equals(key)) {
            schema.multipleOf(child);
            return;
        }
        if ("enum".equals(key)
                && segments.size() == 3
                && schema.getEnum() != null) {
            schema.getEnum().set(
                    Integer.parseInt(
                            segments.get(2)),
                    child);
            return;
        }
        throw evidenceFailure(
                "Unsupported schema edge pointer "
                        + JsonPointer.toPointer(
                        segments));
    }

    private static List<CoordinationDocumentSplitter.FragmentRoot>
    immutableRoots(
            Collection<CoordinationDocumentSplitter.FragmentRoot>
                    source) {
        List<CoordinationDocumentSplitter.FragmentRoot> copy =
                new ArrayList<>(
                        Objects.requireNonNull(
                                source,
                                "fragmentRoots"));
        if (copy.contains(null)) {
            throw evidenceFailure(
                    "Fragment roots contain null");
        }
        copy.sort(
                Comparator
                        .comparing(
                                (CoordinationDocumentSplitter.FragmentRoot value)
                                        -> value.kind().name())
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .FragmentRoot::absolutePath)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .FragmentRoot::blueId));
        return Collections.unmodifiableList(
                copy);
    }

    private static List<CoordinationDocumentSplitter.EdgeOccurrence>
    immutableEdges(
            Collection<CoordinationDocumentSplitter.EdgeOccurrence>
                    source) {
        List<CoordinationDocumentSplitter.EdgeOccurrence> copy =
                new ArrayList<>(
                        Objects.requireNonNull(
                                source,
                                "edgeOccurrences"));
        if (copy.contains(null)) {
            throw evidenceFailure(
                    "Edge occurrences contain null");
        }
        return Collections.unmodifiableList(
                copy);
    }

    private static SortedMap<String, Node>
    immutableFragments(
            Map<String, Node> source) {
        SortedMap<String, Node> copy =
                new TreeMap<>();
        for (Map.Entry<String, Node> entry
                : Objects.requireNonNull(
                source, "fragments").entrySet()) {
            Node fragment =
                    Objects.requireNonNull(
                            entry.getValue(),
                            "fragment").clone();
            requireIdentity(
                    entry.getKey(),
                    fragment,
                    "Stored fragment");
            copy.put(
                    entry.getKey(),
                    fragment);
        }
        return Collections.unmodifiableSortedMap(
                copy);
    }

    private static boolean sameNode(
            Node left,
            Node right) {
        return NodeWireForm.get(
                left).equals(
                NodeWireForm.get(
                        right));
    }

    private static void requireIdentity(
            String expected,
            Node node,
            String label) {
        String actual =
                DirectBlueIdCalculator.calculateBlueId(
                        node);
        if (!expected.equals(actual)) {
            throw evidenceFailure(
                    label
                            + " changed identity from "
                            + expected
                            + " to "
                            + actual);
        }
    }

    private static IllegalArgumentException evidenceFailure(
            String message) {
        return new IllegalArgumentException(
                "Invalid Coordination fragment evidence: "
                        + message);
    }
}
