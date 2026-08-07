package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationFragmentReconstructor;
import blue.language.api.NodeProviderOutcome;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.NodeProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persistable, body-free description of one exact physical fragment graph.
 *
 * <p>The identity covers every retained physical fragment identity and all
 * root/edge/metadata records, but never embeds fragment bodies. Rehydration
 * accepts a closed map shape and recomputes the identity.</p>
 */
public final class CoordinationFragmentInventory {

    public static final String SCHEMA_VERSION =
            "blue.coordination/fragment-inventory/1.0";

    private final String schemaVersion;
    private final String fragmentationProfileIdentity;
    private final String edgeMetadataSchemaIdentity;
    private final String rootBlueId;
    private final List<String> fragmentBlueIds;
    private final Set<String> exactBodyBlueIds;
    private final List<FragmentRootRecord> fragmentRoots;
    private final List<FragmentEdgeRecord> edges;
    private final List<FragmentMetadataRecord> metadata;
    private final String inventoryIdentity;

    public CoordinationFragmentInventory(
            String schemaVersion,
            String fragmentationProfileIdentity,
            String edgeMetadataSchemaIdentity,
            String rootBlueId,
            Collection<String> fragmentBlueIds,
            Collection<FragmentRootRecord> fragmentRoots,
            Collection<FragmentEdgeRecord> edges,
            Collection<FragmentMetadataRecord> metadata) {
        this(schemaVersion,
                fragmentationProfileIdentity,
                edgeMetadataSchemaIdentity,
                rootBlueId,
                fragmentBlueIds,
                fragmentRoots,
                edges,
                metadata,
                null);
    }

    /**
     * Creates an inventory while validating an optional exact Root.
     *
     * <p>The Root is deliberately <em>not</em> retained. Inventory instances
     * are historical persistence values and retaining one complete document
     * body in every value makes memory use grow with the number of revisions.
     * Managed engines keep hot Roots in their own explicitly bounded
     * inventory-keyed cache.</p>
     */
    public CoordinationFragmentInventory(
            String schemaVersion,
            String fragmentationProfileIdentity,
            String edgeMetadataSchemaIdentity,
            String rootBlueId,
            Collection<String> fragmentBlueIds,
            Collection<FragmentRootRecord> fragmentRoots,
            Collection<FragmentEdgeRecord> edges,
            Collection<FragmentMetadataRecord> metadata,
            Node directRoot) {
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(this.schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported fragment inventory schema: "
                            + this.schemaVersion);
        }
        this.fragmentationProfileIdentity = requireText(
                fragmentationProfileIdentity,
                "fragmentationProfileIdentity");
        if (!CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID.equals(
                this.fragmentationProfileIdentity)) {
            throw new IllegalArgumentException(
                    "Unsupported fragmentation profile: "
                            + this.fragmentationProfileIdentity);
        }
        this.edgeMetadataSchemaIdentity = requireText(
                edgeMetadataSchemaIdentity,
                "edgeMetadataSchemaIdentity");
        if (!CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID.equals(
                this.edgeMetadataSchemaIdentity)) {
            throw new IllegalArgumentException(
                    "Unsupported edge metadata schema: "
                            + this.edgeMetadataSchemaIdentity);
        }
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.fragmentBlueIds = immutableUniqueText(
                fragmentBlueIds, "fragmentBlueIds");
        this.fragmentRoots = immutableSorted(
                fragmentRoots, "fragmentRoots");
        this.edges = immutableSorted(edges, "edges");
        this.metadata = immutableSorted(metadata, "metadata");
        this.exactBodyBlueIds = exactBodyIdentities(
                this.rootBlueId,
                this.fragmentRoots,
                this.edges,
                this.metadata);
        validateGraphShape();
        this.inventoryIdentity = identity(canonicalMap());
        validateDirectRoot(directRoot);
    }

    /** Creates the persistable value directly from the canonical splitter. */
    public static CoordinationFragmentInventory from(
            CoordinationDocumentSplitter.SplitGraph graph) {
        Objects.requireNonNull(graph, "graph");
        List<FragmentRootRecord> roots = new ArrayList<FragmentRootRecord>();
        for (CoordinationDocumentSplitter.FragmentRoot root
                : graph.fragmentRoots()) {
            roots.add(FragmentRootRecord.from(root));
        }
        List<FragmentEdgeRecord> edgeRecords =
                new ArrayList<FragmentEdgeRecord>();
        for (CoordinationDocumentSplitter.EdgeOccurrence edge
                : graph.edgeOccurrences()) {
            edgeRecords.add(FragmentEdgeRecord.from(edge));
        }
        List<FragmentMetadataRecord> metadataRecords =
                new ArrayList<FragmentMetadataRecord>();
        for (CoordinationDocumentSplitter.FragmentMetadata item
                : graph.metadata()) {
            metadataRecords.add(FragmentMetadataRecord.from(item));
        }
        return new CoordinationFragmentInventory(
                SCHEMA_VERSION,
                graph.fragmentationProfileIdentity(),
                graph.edgeMetadataSchemaIdentity(),
                graph.rootBlueId(),
                graph.fragmentBlueIds(),
                roots,
                edgeRecords,
                metadataRecords);
    }

    public String schemaVersion() { return schemaVersion; }
    public String fragmentationProfileIdentity() {
        return fragmentationProfileIdentity;
    }
    public String edgeMetadataSchemaIdentity() {
        return edgeMetadataSchemaIdentity;
    }
    public String rootBlueId() { return rootBlueId; }
    public List<String> fragmentBlueIds() { return fragmentBlueIds; }
    public List<FragmentRootRecord> fragmentRoots() { return fragmentRoots; }
    public List<FragmentEdgeRecord> edges() { return edges; }
    public List<FragmentMetadataRecord> metadata() { return metadata; }
    public String inventoryIdentity() { return inventoryIdentity; }

    /**
     * Whether this inventory owns a concrete exact body for {@code blueId}.
     * A retained authored pure-reference stub is not body ownership.
     */
    public boolean ownsExactBody(String blueId) {
        return exactBodyBlueIds.contains(requireText(blueId, "blueId"));
    }

    /**
     * Returns a distinct body-free immutable copy.
     */
    public CoordinationFragmentInventory retainedCopy() {
        return new CoordinationFragmentInventory(
                schemaVersion,
                fragmentationProfileIdentity,
                edgeMetadataSchemaIdentity,
                rootBlueId,
                fragmentBlueIds,
                fragmentRoots,
                edges,
                metadata);
    }

    /** Returns the closed scalar/list/map persistence representation. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>(
                canonicalMap());
        result.put("inventoryIdentity", inventoryIdentity);
        return immutableMap(result);
    }

    /** Rehydrates a closed persistence map and verifies its exact identity. */
    public static CoordinationFragmentInventory rehydrate(
            Map<String, ?> persisted) {
        requireFields(
                persisted,
                "fragment inventory",
                "schemaVersion",
                "fragmentationProfileIdentity",
                "edgeMetadataSchemaIdentity",
                "rootBlueId",
                "fragmentBlueIds",
                "fragmentRoots",
                "edges",
                "metadata",
                "inventoryIdentity");
        List<FragmentRootRecord> roots = new ArrayList<FragmentRootRecord>();
        for (Map<String, ?> map : mapList(persisted, "fragmentRoots")) {
            roots.add(FragmentRootRecord.rehydrate(map));
        }
        List<FragmentEdgeRecord> edges = new ArrayList<FragmentEdgeRecord>();
        for (Map<String, ?> map : mapList(persisted, "edges")) {
            edges.add(FragmentEdgeRecord.rehydrate(map));
        }
        List<FragmentMetadataRecord> metadata =
                new ArrayList<FragmentMetadataRecord>();
        for (Map<String, ?> map : mapList(persisted, "metadata")) {
            metadata.add(FragmentMetadataRecord.rehydrate(map));
        }
        CoordinationFragmentInventory value =
                new CoordinationFragmentInventory(
                        text(persisted, "schemaVersion"),
                        text(persisted, "fragmentationProfileIdentity"),
                        text(persisted, "edgeMetadataSchemaIdentity"),
                        text(persisted, "rootBlueId"),
                        textList(persisted, "fragmentBlueIds"),
                        roots,
                        edges,
                        metadata);
        String suppliedIdentity = text(persisted, "inventoryIdentity");
        if (!value.inventoryIdentity.equals(suppliedIdentity)) {
            throw new IllegalArgumentException(
                    "Persisted fragment inventory identity does not match "
                            + "its content");
        }
        return value;
    }

    /** Loads every exact body, reconstructs, and verifies the semantic Root. */
    public Node reconstruct(NodeProvider store) {
        NodeProvider checked = Objects.requireNonNull(
                store, "store");
        Map<String, Node> fragments = new LinkedHashMap<String, Node>();
        for (String blueId : fragmentBlueIds) {
            NodeProviderResult result = checked.fetchResultByBlueId(blueId);
            if (result == null
                    || result.outcome() != NodeProviderOutcome.FOUND
                    || result.nodes().size() != 1) {
                throw new IllegalStateException(
                        "Exact fragment is unavailable or ambiguous: "
                                + blueId);
            }
            Node node = result.nodes().get(0);
            if (!blueId.equals(DirectBlueIdCalculator.calculateBlueId(
                    node.clone()))) {
                throw new IllegalStateException(
                        "Stored fragment has invalid identity evidence: "
                                + blueId);
            }
            fragments.put(blueId, node);
        }
        List<CoordinationDocumentSplitter.FragmentRoot> roots =
                new ArrayList<CoordinationDocumentSplitter.FragmentRoot>();
        for (FragmentRootRecord root : fragmentRoots) {
            roots.add(root.toFragmentRoot());
        }
        List<CoordinationDocumentSplitter.EdgeOccurrence> occurrences =
                new ArrayList<CoordinationDocumentSplitter.EdgeOccurrence>();
        for (FragmentEdgeRecord edge : edges) {
            occurrences.add(edge.toEdgeOccurrence(
                    fragmentationProfileIdentity));
        }
        return CoordinationFragmentReconstructor.reconstruct(
                fragmentationProfileIdentity,
                rootBlueId,
                roots,
                fragments,
                occurrences);
    }

    /**
     * Legacy compatibility accessor for the removed per-inventory Root
     * handle.
     *
     * <p>Inventories are now always body-free. Managed engines use a bounded
     * cache and fall back to {@link #reconstruct(NodeProvider)} after an
     * eviction. This method remains temporarily source-compatible and always
     * returns {@code null}.</p>
     *
     * @return always {@code null}
     * @deprecated use an engine-owned bounded Root-view cache
     */
    @Deprecated
    public Node directRootOrNull() {
        return null;
    }

    private void validateDirectRoot(Node value) {
        if (value == null) {
            return;
        }
        Node root = value.clone();
        String actual = DirectBlueIdCalculator.calculateBlueId(root.clone());
        if (!rootBlueId.equals(actual) || root.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Direct Root handle does not match the inventory Root");
        }
    }

    private void validateGraphShape() {
        if (Collections.binarySearch(fragmentBlueIds, rootBlueId) < 0) {
            throw new IllegalArgumentException(
                    "Fragment inventory does not retain its Root body");
        }
        boolean semanticRoot = false;
        Set<String> rootKeys = new HashSet<String>();
        for (FragmentRootRecord root : fragmentRoots) {
            String key = root.kind().name() + "|" + root.absolutePath()
                    + "|" + root.blueId();
            if (!rootKeys.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate fragment root record: " + key);
            }
            if (rootBlueId.equals(root.blueId())
                    && (root.kind()
                    == CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT
                    || root.kind()
                    == CoordinationDocumentSplitter.FragmentRootKind.EVENT)) {
                semanticRoot = true;
            }
            requireRetained(root.blueId(), "fragment root");
        }
        if (!semanticRoot) {
            throw new IllegalArgumentException(
                    "Inventory Root is not declared as document or event");
        }
        Set<String> edgeKeys = new HashSet<String>();
        for (FragmentEdgeRecord edge : edges) {
            if (!edgeMetadataSchemaIdentity.equals(edge.schemaIdentity())) {
                throw new IllegalArgumentException(
                        "Fragment edge uses another metadata schema");
            }
            String key = edge.rootKind().name() + "|" + edge.rootBlueId()
                    + "|" + edge.ownerNodeBlueId() + "|"
                    + edge.absolutePointer();
            if (!edgeKeys.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate fragment edge occurrence: " + key);
            }
            requireRetained(edge.ownerNodeBlueId(), "edge owner");
            /* An authored pure reference is deliberately retained as an
             * unresolved identity in the canonical fragment. Its target is
             * outside this physical inventory and reconstruction must not
             * pretend that the body was admitted. Splitter-created cuts, in
             * contrast, always name a body owned by this inventory. */
            if (edge.splitterCreated()) {
                requireRetained(edge.childBlueId(), "edge child");
            }
        }
        for (FragmentMetadataRecord item : metadata) {
            requireRetained(item.blueId(), "metadata fragment");
        }
    }

    private void requireRetained(String blueId, String label) {
        if (Collections.binarySearch(fragmentBlueIds, blueId) < 0) {
            throw new IllegalArgumentException(
                    "Unknown " + label + " identity: " + blueId);
        }
    }

    private static Set<String> exactBodyIdentities(
            String rootBlueId,
            List<FragmentRootRecord> roots,
            List<FragmentEdgeRecord> edges,
            List<FragmentMetadataRecord> metadata) {
        Set<String> result = new HashSet<String>();
        result.add(rootBlueId);
        for (FragmentRootRecord root : roots) {
            result.add(root.blueId());
        }
        for (FragmentMetadataRecord item : metadata) {
            result.add(item.blueId());
        }
        for (FragmentEdgeRecord edge : edges) {
            result.add(edge.ownerNodeBlueId());
            if (edge.splitterCreated()) {
                result.add(edge.childBlueId());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private Map<String, Object> canonicalMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", schemaVersion);
        result.put("fragmentationProfileIdentity", fragmentationProfileIdentity);
        result.put("edgeMetadataSchemaIdentity", edgeMetadataSchemaIdentity);
        result.put("rootBlueId", rootBlueId);
        result.put("fragmentBlueIds", fragmentBlueIds);
        List<Map<String, Object>> roots =
                new ArrayList<Map<String, Object>>();
        for (FragmentRootRecord root : fragmentRoots) roots.add(root.toMap());
        result.put("fragmentRoots", roots);
        List<Map<String, Object>> edgeMaps =
                new ArrayList<Map<String, Object>>();
        for (FragmentEdgeRecord edge : edges) edgeMaps.add(edge.toMap());
        result.put("edges", edgeMaps);
        List<Map<String, Object>> metadataMaps =
                new ArrayList<Map<String, Object>>();
        for (FragmentMetadataRecord item : metadata) {
            metadataMaps.add(item.toMap());
        }
        result.put("metadata", metadataMaps);
        return result;
    }

    private static String identity(Map<String, Object> map) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = UncheckedObjectMapper.JSON_MAPPER
                    .writeValueAsString(map)
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + hex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static <T extends Comparable<? super T>> List<T> immutableSorted(
            Collection<T> source,
            String label) {
        List<T> result = new ArrayList<T>(
                Objects.requireNonNull(source, label));
        for (T item : result) Objects.requireNonNull(item, label + " entry");
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static List<String> immutableUniqueText(
            Collection<String> source,
            String label) {
        Set<String> result = new TreeSet<String>();
        for (String value : Objects.requireNonNull(source, label)) {
            if (!result.add(requireText(value, label + " entry"))) {
                throw new IllegalArgumentException(
                        "Duplicate " + label + " entry: " + value);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return Collections.unmodifiableList(new ArrayList<String>(result));
    }

    static void requireFields(
            Map<String, ?> map,
            String label,
            String... fields) {
        Objects.requireNonNull(map, label);
        Set<String> expected = new LinkedHashSet<String>();
        Collections.addAll(expected, fields);
        if (!expected.equals(map.keySet())) {
            throw new IllegalArgumentException(
                    label + " fields differ: expected " + expected
                            + " but got " + map.keySet());
        }
    }

    static String text(Map<String, ?> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return requireText((String) value, field);
    }

    static String optionalText(Map<String, ?> map, String field) {
        Object value = map.get(field);
        if (value == null) return null;
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be text or null");
        }
        return (String) value;
    }

    static boolean bool(Map<String, ?> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    static <E extends Enum<E>> E enumValue(
            Map<String, ?> map,
            String field,
            Class<E> type) {
        String value = text(map, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Unknown " + field + " value: " + value,
                    invalid);
        }
    }

    static List<String> textList(Map<String, ?> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(
                        field + " entries must be text");
            }
            result.add((String) item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> mapList(
            Map<String, ?> map,
            String field) {
        Object value = map.get(field);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        List<Map<String, ?>> result = new ArrayList<Map<String, ?>>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException(
                        field + " entries must be maps");
            }
            result.add((Map<String, ?>) item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = immutableMap((Map<String, Object>) value);
            } else if (value instanceof List) {
                value = immutableList((List<?>) value);
            }
            result.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static List<?> immutableList(List<?> source) {
        List<Object> result = new ArrayList<Object>();
        for (Object value : source) {
            if (value instanceof Map) {
                value = immutableMap((Map<String, Object>) value);
            } else if (value instanceof List) {
                value = immutableList((List<?>) value);
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
