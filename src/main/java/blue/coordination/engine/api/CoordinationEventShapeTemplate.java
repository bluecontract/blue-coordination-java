package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable event-fragment topology compiled once from an authoritative full
 * split. A first-seen instance patches only declared authored leaves, then
 * rebuilds only their direct-fragment ancestor chains.
 *
 * <p>This value never contains a future exact Timeline Entry. It contains one
 * sentinel operation shape, immutable static canonical fragments, and the
 * path/edge topology required to derive a new exact identity. Exact timestamp
 * and previous-entry values are supplied only to {@link #instantiate}.</p>
 */
public final class CoordinationEventShapeTemplate {
    private final String shapeIdentity;
    private final CoordinationEventAdmissionCacheKey prototypeKey;
    private final FrozenNode exactPrototype;
    private final CoordinationFragmentInventory prototypeInventory;
    private final Map<String, CoordinationCanonicalFragment>
            prototypeFragments;
    private final Set<String> volatileLeafPaths;
    private final Map<String, FragmentEdgeRecord> edgeByChildPath;
    private final Map<String, List<FragmentEdgeRecord>> edgesByOwnerPath;
    private final Map<String, String> prototypeIdByPath;
    private final Set<String> localPaths;
    private final CoordinationEventShapeMetrics metrics;
    private final long approximateRetainedWeightBytes;

    private CoordinationEventShapeTemplate(
            String shapeIdentity,
            CoordinationVerifiedEventAdmission prototype,
            Set<String> volatileLeafPaths,
            CoordinationEventShapeMetrics metrics) {
        this.shapeIdentity = requireText(shapeIdentity, "shapeIdentity");
        CoordinationVerifiedEventAdmission checked = Objects.requireNonNull(
                prototype, "prototype");
        this.prototypeKey = checked.key();
        this.exactPrototype = FrozenNode.fromNode(checked.exactEvent());
        this.prototypeInventory = checked.inventory().retainedCopy();
        this.prototypeFragments = Collections.unmodifiableMap(
                new LinkedHashMap<String, CoordinationCanonicalFragment>(
                        checked.fragments()));
        this.volatileLeafPaths = Collections.unmodifiableSet(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        volatileLeafPaths, "volatileLeafPaths")));
        this.metrics = Objects.requireNonNull(metrics, "metrics");

        LinkedHashMap<String, FragmentEdgeRecord> byChild =
                new LinkedHashMap<String, FragmentEdgeRecord>();
        LinkedHashMap<String, List<FragmentEdgeRecord>> byOwner =
                new LinkedHashMap<String, List<FragmentEdgeRecord>>();
        LinkedHashMap<String, String> idsByPath =
                new LinkedHashMap<String, String>();
        LinkedHashSet<String> local = new LinkedHashSet<String>();
        idsByPath.put(JsonPointer.ROOT, prototypeInventory.rootBlueId());
        local.add(JsonPointer.ROOT);
        for (FragmentEdgeRecord edge : prototypeInventory.edges()) {
            String childPath = JsonPointer.canonicalize(
                    edge.absolutePointer());
            FragmentEdgeRecord prior = byChild.put(childPath, edge);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Event shape has duplicate direct-edge path: "
                                + childPath);
            }
            String ownerPath = ownerPath(edge);
            byOwner.computeIfAbsent(
                    ownerPath,
                    ignored -> new ArrayList<FragmentEdgeRecord>())
                    .add(edge);
            idsByPath.put(childPath, edge.childBlueId());
            if (edge.splitterCreated()) {
                local.add(childPath);
            }
        }
        for (Map.Entry<String, List<FragmentEdgeRecord>> item
                : byOwner.entrySet()) {
            item.getValue().sort(Comparator
                    .comparing(FragmentEdgeRecord::ownerRelativePointer)
                    .thenComparing(FragmentEdgeRecord::childBlueId));
        }
        for (String path : this.volatileLeafPaths) {
            FragmentEdgeRecord edge = byChild.get(path);
            if (edge == null) {
                throw new IllegalArgumentException(
                        "Volatile event path is absent from shape: " + path);
            }
            if (byOwner.containsKey(path)) {
                throw new IllegalArgumentException(
                        "Volatile event path must be a semantic leaf: "
                                + path);
            }
        }
        for (String path : local) {
            String blueId = idsByPath.get(path);
            if (blueId == null || !prototypeFragments.containsKey(blueId)) {
                throw new IllegalArgumentException(
                        "Local event path has no canonical fragment: "
                                + path);
            }
        }
        this.edgeByChildPath = Collections.unmodifiableMap(byChild);
        LinkedHashMap<String, List<FragmentEdgeRecord>> immutableOwners =
                new LinkedHashMap<String, List<FragmentEdgeRecord>>();
        for (Map.Entry<String, List<FragmentEdgeRecord>> item
                : byOwner.entrySet()) {
            immutableOwners.put(
                    item.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<FragmentEdgeRecord>(
                                    item.getValue())));
        }
        this.edgesByOwnerPath = Collections.unmodifiableMap(immutableOwners);
        this.prototypeIdByPath = Collections.unmodifiableMap(idsByPath);
        this.localPaths = Collections.unmodifiableSet(local);
        this.approximateRetainedWeightBytes = estimateWeight();
    }

    static CoordinationEventShapeTemplate fromAuthoritativePrototype(
            String shapeIdentity,
            CoordinationVerifiedEventAdmission prototype,
            Set<String> volatileLeafPaths,
            CoordinationEventShapeMetrics metrics) {
        return new CoordinationEventShapeTemplate(
                shapeIdentity, prototype, volatileLeafPaths, metrics);
    }

    public String shapeIdentity() {
        return shapeIdentity;
    }

    public Set<String> volatileLeafPaths() {
        return volatileLeafPaths;
    }

    public long approximateRetainedWeightBytes() {
        return approximateRetainedWeightBytes;
    }

    /**
     * Returns a caller-owned copy of the non-exact sentinel used to compile
     * this shape. This exists for no-cheating audits: callers can prove that
     * volatile leaves contain only shape sentinels, never values from a
     * future exact event. The returned graph is not an admitted event.
     */
    public Node sentinelPrototypeForAudit() {
        return exactPrototype.toNode();
    }

    /**
     * Creates a first-seen exact instance. Every declared volatile leaf must
     * be supplied exactly once; undeclared mutation is structurally
     * impossible because the exact event is materialized from this template.
     */
    public CoordinationEventShapeInstance instantiate(
            Collection<CoordinationEventShapePatch> suppliedPatches) {
        return instantiate(suppliedPatches, metrics);
    }

    /** Records instance work in the current owning environment. */
    public CoordinationEventShapeInstance instantiate(
            Collection<CoordinationEventShapePatch> suppliedPatches,
            CoordinationEventShapeMetrics operationMetrics) {
        CoordinationEventShapeMetrics work = Objects.requireNonNull(
                operationMetrics, "operationMetrics");
        Map<String, Node> patches = checkedPatches(suppliedPatches);
        Node exactEvent = exactPrototype.toNode();
        work.exactGraphMaterialized();
        for (Map.Entry<String, Node> patch : patches.entrySet()) {
            NodePathEditor.put(
                    exactEvent, patch.getKey(), patch.getValue().clone());
        }

        LinkedHashMap<String, String> changedIdByPath =
                new LinkedHashMap<String, String>();
        LinkedHashMap<String, Node> changedBodyByPath =
                new LinkedHashMap<String, Node>();
        LinkedHashSet<String> affectedOwnerPaths =
                new LinkedHashSet<String>();
        for (Map.Entry<String, Node> patch : patches.entrySet()) {
            String path = patch.getKey();
            Node replacement = patch.getValue();
            FragmentEdgeRecord edge = edgeByChildPath.get(path);
            requireSameEdgeOrigin(edge, replacement, path);
            requireLeafReplacement(replacement, path);
            String replacementId = DirectBlueIdCalculator.calculateBlueId(
                    replacement);
            changedIdByPath.put(path, replacementId);
            if (edge.splitterCreated()) {
                changedBodyByPath.put(path, replacement.clone());
            }
            addOwnerChain(path, affectedOwnerPaths);
        }

        List<String> orderedOwners = new ArrayList<String>(
                affectedOwnerPaths);
        orderedOwners.sort(Comparator
                .comparingInt(CoordinationEventShapeTemplate::depth)
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (String ownerPath : orderedOwners) {
            String prototypeId = prototypeIdByPath.get(ownerPath);
            CoordinationCanonicalFragment prototypeFragment =
                    prototypeFragments.get(prototypeId);
            if (prototypeFragment == null) {
                throw new IllegalStateException(
                        "No prototype body for affected owner " + ownerPath);
            }
            Node direct = prototypeFragment.materialize();
            for (FragmentEdgeRecord edge : outgoing(ownerPath)) {
                String childPath = JsonPointer.canonicalize(
                        edge.absolutePointer());
                String changedChildId = changedIdByPath.get(childPath);
                if (changedChildId != null) {
                    NodePathEditor.put(
                            direct,
                            edge.ownerRelativePointer(),
                            new Node().blueId(changedChildId));
                }
            }
            String ownerId = DirectBlueIdCalculator.calculateBlueId(direct);
            changedIdByPath.put(ownerPath, ownerId);
            changedBodyByPath.put(ownerPath, direct);
        }

        String eventBlueId = changedIdByPath.get(JsonPointer.ROOT);
        if (eventBlueId == null) {
            throw new IllegalStateException(
                    "Volatile patches did not reach the event Root");
        }
        FinalGraph finalGraph = finalGraph(
                eventBlueId, changedIdByPath, changedBodyByPath);
        CoordinationEventAdmissionCacheKey key =
                new CoordinationEventAdmissionCacheKey(
                        prototypeKey.environmentIdentity(),
                        prototypeKey.fragmentationProfileIdentity(),
                        prototypeKey.languageGenerationIdentity(),
                        prototypeKey.providerGenerationIdentity(),
                        eventBlueId);
        CoordinationVerifiedEventAdmission admission =
                new CoordinationVerifiedEventAdmission(
                        key,
                        finalGraph.inventory,
                        exactEvent,
                        finalGraph.fragments,
                        Collections.<String, Node>emptyMap());
        work.instanceCompiled();
        work.directFragmentsRehashed(
                finalGraph.changedLocalFragmentCount);
        work.staticFragmentsReused(
                finalGraph.reusedLocalFragmentCount);
        return new CoordinationEventShapeInstance(
                admission,
                finalGraph.changedLocalFragmentCount,
                finalGraph.reusedLocalFragmentCount);
    }

    /**
     * Test/shadow oracle. This is deliberately separate from the measured hot
     * path because it performs the complete authoritative event split.
     */
    public void requireAuthoritativeParity(
            CoordinationEventShapeInstance instance,
            CoordinationDocumentSplitter splitter) {
        CoordinationEventShapeInstance checked = Objects.requireNonNull(
                instance, "instance");
        metrics.fullSplitterOracleRun();
        CoordinationDocumentSplitter.SplitGraph graph =
                Objects.requireNonNull(splitter, "splitter")
                        .splitEvent(checked.exactEvent());
        CoordinationFragmentInventory expected =
                CoordinationFragmentInventory.from(graph);
        CoordinationVerifiedEventAdmission actual = checked.admission();
        if (!graph.rootBlueId().equals(actual.key().eventBlueId())
                || !expected.toMap().equals(actual.inventory().toMap())
                || !graph.fragmentBlueIds().equals(
                        actual.orderedFragmentBlueIds())) {
            metrics.oracleFailure();
            throw new IllegalStateException(
                    "Incremental event topology differs from full splitter");
        }
        for (String blueId : graph.fragmentBlueIds()) {
            CoordinationCanonicalFragment fragment =
                    actual.fragments().get(blueId);
            if (fragment == null
                    || !NodeWireForm.get(graph.fragment(blueId)).equals(
                            NodeWireForm.get(fragment.materialize()))) {
                metrics.oracleFailure();
                throw new IllegalStateException(
                        "Incremental event fragment differs at " + blueId);
            }
        }
    }

    private FinalGraph finalGraph(
            String eventBlueId,
            Map<String, String> changedIdByPath,
            Map<String, Node> changedBodyByPath) {
        TreeSet<String> localIds = new TreeSet<String>();
        localIds.add(eventBlueId);
        for (String path : localPaths) {
            localIds.add(finalId(path, changedIdByPath));
        }

        SortedMap<String, CoordinationCanonicalFragment> fragments =
                new TreeMap<String, CoordinationCanonicalFragment>();
        LinkedHashSet<String> changedIds = new LinkedHashSet<String>();
        for (String path : localPaths) {
            String finalId = finalId(path, changedIdByPath);
            if (!localIds.contains(finalId)) {
                continue;
            }
            Node changedBody = changedBodyByPath.get(path);
            if (changedBody != null) {
                CoordinationCanonicalFragment created =
                        new CoordinationCanonicalFragment(
                                finalId,
                                CoordinationFragmentAdmissionVerifier
                                        .physicalFragmentIdentity(changedBody),
                                changedBody);
                mergeFragment(fragments, created);
                changedIds.add(finalId);
            } else {
                String prototypeId = prototypeIdByPath.get(path);
                CoordinationCanonicalFragment reused =
                        prototypeFragments.get(prototypeId);
                if (reused == null || !finalId.equals(reused.blueId())) {
                    throw new IllegalStateException(
                            "Static event fragment is unavailable at " + path);
                }
                mergeFragment(fragments, reused);
            }
        }
        if (!fragments.keySet().equals(localIds)) {
            throw new IllegalStateException(
                    "Final event fragment membership is incomplete");
        }

        List<FragmentEdgeRecord> edges =
                new ArrayList<FragmentEdgeRecord>();
        for (FragmentEdgeRecord edge : prototypeInventory.edges()) {
            String childPath = JsonPointer.canonicalize(
                    edge.absolutePointer());
            String ownerPath = ownerPath(edge);
            edges.add(copyEdge(
                    edge,
                    eventBlueId,
                    finalId(ownerPath, changedIdByPath),
                    finalId(childPath, changedIdByPath)));
        }
        List<FragmentMetadataRecord> metadata =
                new ArrayList<FragmentMetadataRecord>();
        for (String blueId : localIds) {
            boolean root = eventBlueId.equals(blueId);
            metadata.add(new FragmentMetadataRecord(
                    blueId,
                    root
                            ? CoordinationDocumentSplitter.FragmentKind
                                    .EVENT_ROOT
                            : CoordinationDocumentSplitter.FragmentKind
                                    .EVENT_FRAGMENT,
                    JsonPointer.ROOT,
                    root ? JsonPointer.ROOT : null,
                    null,
                    null));
        }
        List<FragmentRootRecord> roots = Collections.singletonList(
                new FragmentRootRecord(
                        eventBlueId,
                        CoordinationDocumentSplitter.FragmentRootKind.EVENT,
                        JsonPointer.ROOT));
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        prototypeInventory.fragmentationProfileIdentity(),
                        prototypeInventory.edgeMetadataSchemaIdentity(),
                        eventBlueId,
                        localIds,
                        roots,
                        edges,
                        metadata);
        return new FinalGraph(
                inventory,
                Collections.unmodifiableMap(
                        new LinkedHashMap<String,
                                CoordinationCanonicalFragment>(fragments)),
                changedIds.size(),
                Math.subtractExact(fragments.size(), changedIds.size()));
    }

    private Map<String, Node> checkedPatches(
            Collection<CoordinationEventShapePatch> supplied) {
        LinkedHashMap<String, Node> result =
                new LinkedHashMap<String, Node>();
        for (CoordinationEventShapePatch patch : Objects.requireNonNull(
                supplied, "suppliedPatches")) {
            CoordinationEventShapePatch checked = Objects.requireNonNull(
                    patch, "patch");
            if (!volatileLeafPaths.contains(checked.pointer())) {
                throw new IllegalArgumentException(
                        "Undeclared event-shape mutation: "
                                + checked.pointer());
            }
            Node prior = result.put(
                    checked.pointer(), checked.replacement());
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Duplicate event-shape mutation: "
                                + checked.pointer());
            }
        }
        if (!result.keySet().equals(volatileLeafPaths)) {
            LinkedHashSet<String> missing = new LinkedHashSet<String>(
                    volatileLeafPaths);
            missing.removeAll(result.keySet());
            throw new IllegalArgumentException(
                    "Missing volatile event-shape mutations: " + missing);
        }
        return Collections.unmodifiableMap(result);
    }

    private void addOwnerChain(
            String childPath,
            Set<String> owners) {
        String current = childPath;
        Deque<String> guard = new ArrayDeque<String>();
        while (!JsonPointer.ROOT.equals(current)) {
            FragmentEdgeRecord edge = edgeByChildPath.get(current);
            if (edge == null) {
                throw new IllegalStateException(
                        "Event path has no parent edge: " + current);
            }
            String owner = ownerPath(edge);
            if (!owners.add(owner) && guard.contains(owner)) {
                throw new IllegalStateException(
                        "Event direct-edge graph is cyclic at " + owner);
            }
            guard.addLast(owner);
            current = owner;
        }
    }

    private List<FragmentEdgeRecord> outgoing(String ownerPath) {
        List<FragmentEdgeRecord> result = edgesByOwnerPath.get(ownerPath);
        return result == null
                ? Collections.<FragmentEdgeRecord>emptyList()
                : result;
    }

    private String finalId(
            String path,
            Map<String, String> changedIdByPath) {
        String changed = changedIdByPath.get(path);
        if (changed != null) {
            return changed;
        }
        String prototype = prototypeIdByPath.get(path);
        if (prototype == null) {
            throw new IllegalStateException(
                    "Event shape has no identity at " + path);
        }
        return prototype;
    }

    private static FragmentEdgeRecord copyEdge(
            FragmentEdgeRecord edge,
            String rootBlueId,
            String ownerBlueId,
            String childBlueId) {
        return new FragmentEdgeRecord(
                edge.schemaIdentity(),
                edge.rootKind(),
                rootBlueId,
                ownerBlueId,
                edge.ownerScopePath(),
                edge.absolutePointer(),
                edge.ownerRelativePointer(),
                childBlueId,
                edge.edgeKind(),
                edge.originalPureReference(),
                edge.splitterCreated(),
                edge.declaringScopePath(),
                edge.embeddedOrigin(),
                edge.explicitDeclarationPath(),
                edge.collectionDeclarationPath(),
                edge.collectionMemberKey(),
                edge.handlerEffectiveTypeBlueId(),
                edge.executableBodyField(),
                edge.sourceContributionBlueIds());
    }

    private static void mergeFragment(
            Map<String, CoordinationCanonicalFragment> fragments,
            CoordinationCanonicalFragment candidate) {
        CoordinationCanonicalFragment prior = fragments.putIfAbsent(
                candidate.blueId(), candidate);
        if (prior != null
                && !prior.canonicalWireFingerprint().equals(
                        candidate.canonicalWireFingerprint())) {
            throw new IllegalStateException(
                    "Equal event BlueId produced unequal direct fragments: "
                            + candidate.blueId());
        }
    }

    private static void requireSameEdgeOrigin(
            FragmentEdgeRecord edge,
            Node replacement,
            String path) {
        boolean replacementReference = replacement.isReferenceOnly();
        if (edge.originalPureReference() != replacementReference) {
            throw new IllegalArgumentException(
                    "Event-shape mutation changes authored edge origin at "
                            + path);
        }
    }

    private static void requireLeafReplacement(Node node, String path) {
        if (node.isReferenceOnly()) {
            return;
        }
        if (node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getContracts() != null
                || node.getBlue() != null
                || (node.getItems() != null && !node.getItems().isEmpty())
                || (node.getProperties() != null
                        && !node.getProperties().isEmpty())) {
            throw new IllegalArgumentException(
                    "Event-shape mutation must remain a semantic leaf: "
                            + path);
        }
    }

    private static String ownerPath(FragmentEdgeRecord edge) {
        List<String> absolute = JsonPointer.split(edge.absolutePointer());
        List<String> relative = JsonPointer.split(
                edge.ownerRelativePointer());
        if (relative.size() > absolute.size()) {
            throw new IllegalArgumentException(
                    "Relative edge path exceeds absolute path: "
                            + edge.absolutePointer());
        }
        int start = absolute.size() - relative.size();
        for (int index = 0; index < relative.size(); index++) {
            if (!Objects.equals(
                    absolute.get(start + index), relative.get(index))) {
                throw new IllegalArgumentException(
                        "Relative edge path is not an absolute-path suffix: "
                                + edge.absolutePointer());
            }
        }
        return JsonPointer.toPointer(absolute.subList(0, start));
    }

    private static int depth(String path) {
        return JsonPointer.split(path).size();
    }

    private long estimateWeight() {
        long weight = exactPrototype.approximateRetainedWeightBytes();
        weight = Math.addExact(weight, 512L);
        weight = Math.addExact(
                weight,
                Math.multiplyExact(192L, prototypeFragments.size()));
        weight = Math.addExact(
                weight,
                Math.multiplyExact(160L, prototypeInventory.edges().size()));
        weight = Math.addExact(
                weight,
                Math.multiplyExact(96L, prototypeIdByPath.size()));
        return weight;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static final class FinalGraph {
        private final CoordinationFragmentInventory inventory;
        private final Map<String, CoordinationCanonicalFragment> fragments;
        private final int changedLocalFragmentCount;
        private final int reusedLocalFragmentCount;

        private FinalGraph(
                CoordinationFragmentInventory inventory,
                Map<String, CoordinationCanonicalFragment> fragments,
                int changedLocalFragmentCount,
                int reusedLocalFragmentCount) {
            this.inventory = Objects.requireNonNull(inventory, "inventory");
            this.fragments = Objects.requireNonNull(fragments, "fragments");
            if (changedLocalFragmentCount < 0
                    || reusedLocalFragmentCount < 0) {
                throw new IllegalArgumentException(
                        "Event-shape fragment counts must be non-negative");
            }
            this.changedLocalFragmentCount = changedLocalFragmentCount;
            this.reusedLocalFragmentCount = reusedLocalFragmentCount;
        }

        private CoordinationFragmentInventory inventory() {
            return inventory;
        }

        private Map<String, CoordinationCanonicalFragment> fragments() {
            return fragments;
        }

        private int changedLocalFragmentCount() {
            return changedLocalFragmentCount;
        }

        private int reusedLocalFragmentCount() {
            return reusedLocalFragmentCount;
        }
    }
}
