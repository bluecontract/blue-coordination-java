package blue.coordination.engine.internal;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentMetadataRecord;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
 * Assembles a complete document inventory while cutting only identities that
 * are absent from the prior immutable inventory.
 *
 * <p>The exact PROCESS result is already available, so identity calculation
 * and edge enumeration are local CPU work. Prior fragment bodies are never
 * fetched or reconstructed. Selected delivery scopes and subscription
 * lifecycle paths prioritize the causal frontier; content identity remains
 * authoritative because a Handler may legally update an ancestor or another
 * output location.</p>
 */
final class CoordinationIncrementalFragmentAssembler {

    private final CoordinationDocumentSplitter splitter;
    private final NodeProvider canonicalPhysicalProvider;
    private final CoordinationFragmentStore canonicalPhysicalStore;

    CoordinationIncrementalFragmentAssembler(
            CoordinationDocumentSplitter splitter,
            NodeProvider canonicalPhysicalProvider,
            CoordinationFragmentStore canonicalPhysicalStore) {
        this.splitter = Objects.requireNonNull(
                splitter, "splitter");
        this.canonicalPhysicalProvider = Objects.requireNonNull(
                canonicalPhysicalProvider,
                "canonicalPhysicalProvider");
        this.canonicalPhysicalStore = canonicalPhysicalStore;
    }

    AssembledDocument assemble(
            CoordinationFragmentInventory priorInventory,
            CoordinationDocumentSplitter.DocumentFragmentationBlueprint
                    blueprint,
            Collection<String> causalScopePaths) {
        CoordinationFragmentInventory prior = Objects.requireNonNull(
                priorInventory, "priorInventory");
        CoordinationDocumentSplitter.DocumentFragmentationBlueprint plan =
                Objects.requireNonNull(blueprint, "blueprint");
        Set<String> causalPaths = immutablePaths(causalScopePaths);
        Map<String, NodeProviderResult> admittedPhysicalBodies =
                admittedPhysicalBodies(prior, plan);
        Assembly assembly = new Assembly(
                prior,
                plan,
                causalPaths,
                admittedPhysicalBodies);

        List<CoordinationDocumentSplitter.PhysicalFragmentRoot> roots =
                new ArrayList<CoordinationDocumentSplitter
                        .PhysicalFragmentRoot>(plan.physicalRoots());
        Collections.sort(
                roots,
                Comparator
                        .<CoordinationDocumentSplitter
                                .PhysicalFragmentRoot>
                        comparingInt(
                                root -> causallyRelated(
                                        root.basePath(), causalPaths)
                                        ? 0 : 1)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                        .PhysicalFragmentRoot::basePath)
                        .thenComparing(
                                root -> root.rootKind().name()));
        for (CoordinationDocumentSplitter.PhysicalFragmentRoot root
                : roots) {
            assembly.visit(root);
        }

        List<FragmentRootRecord> rootRecords =
                new ArrayList<FragmentRootRecord>();
        for (CoordinationDocumentSplitter.FragmentRoot root
                : plan.fragmentRoots()) {
            rootRecords.add(new FragmentRootRecord(
                    root.blueId(),
                    root.kind(),
                    root.absolutePath()));
        }
        List<FragmentMetadataRecord> metadata =
                new ArrayList<FragmentMetadataRecord>();
        for (CoordinationDocumentSplitter.FragmentMetadata item
                : plan.metadata()) {
            metadata.add(new FragmentMetadataRecord(
                    item.blueId(),
                    item.kind(),
                    item.scopePath(),
                    item.pointer(),
                    item.handlerTypeBlueId(),
                    item.executableBodyField()));
        }
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter
                                .EDGE_METADATA_SCHEMA_ID,
                        plan.rootBlueId(),
                        assembly.fragmentBlueIds,
                        rootRecords,
                        assembly.edgeRecords(),
                        metadata);

        Map<String, Node> processingViews = new TreeMap<String, Node>();
        Map<String, Node> allViews = plan.processHeaderViews();
        for (Map.Entry<String, Node> entry
                : allViews.entrySet()) {
            if (assembly.fragmentBlueIds.contains(entry.getKey())) {
                processingViews.put(entry.getKey(), entry.getValue());
            }
        }
        return new AssembledDocument(
                inventory,
                assembly.newFragments,
                processingViews);
    }

    private Map<String, NodeProviderResult> admittedPhysicalBodies(
            CoordinationFragmentInventory prior,
            CoordinationDocumentSplitter.DocumentFragmentationBlueprint
                    blueprint) {
        if (canonicalPhysicalStore == null) {
            return Collections.emptyMap();
        }
        CandidateDiscovery discovery = new CandidateDiscovery(
                prior, blueprint);
        for (CoordinationDocumentSplitter.PhysicalFragmentRoot root
                : blueprint.physicalRoots()) {
            discovery.visit(root);
        }
        Set<String> candidates = discovery.candidates();
        /* Header-only identities can be retained without becoming a direct
         * recursion root in a particular representation. Including them is
         * conservative and keeps the batch complete across equivalent
         * physical shapes. */
        candidates.addAll(blueprint.processHeaderViews().keySet());
        candidates.removeAll(prior.fragmentBlueIds());
        if (candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        return canonicalPhysicalStore.readAll(candidates);
    }

    /** Discovers the exact new direct-fragment frontier without store reads. */
    private final class CandidateDiscovery {
        private final Set<String> priorBlueIds;
        private final CoordinationDocumentSplitter
                .DocumentFragmentationBlueprint blueprint;
        private final Set<String> candidates = new LinkedHashSet<String>();
        private final Set<VisitKey> visitedContexts =
                new LinkedHashSet<VisitKey>();

        private CandidateDiscovery(
                CoordinationFragmentInventory prior,
                CoordinationDocumentSplitter
                        .DocumentFragmentationBlueprint blueprint) {
            this.priorBlueIds = new LinkedHashSet<String>(
                    prior.fragmentBlueIds());
            this.blueprint = blueprint;
        }

        private void visit(
                CoordinationDocumentSplitter.PhysicalFragmentRoot root) {
            if (!begin(
                    root.blueId(), root.rootKind(), root.basePath())) {
                return;
            }
            CoordinationDocumentSplitter.DirectNodeInspection inspection =
                    splitter.inspectPhysicalRoot(blueprint, root, true);
            visitChildren(root.rootKind(), inspection.children());
        }

        private void visitChild(
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                CoordinationDocumentSplitter.DirectChildOccurrence child) {
            CoordinationDocumentSplitter.EdgeOccurrence edge = child.edge();
            if (!edge.splitterCreated()
                    || !begin(
                            edge.childBlueId(),
                            rootKind,
                            edge.absolutePointer())) {
                return;
            }
            CoordinationDocumentSplitter.DirectNodeInspection inspection =
                    splitter.inspectDirectChild(
                            blueprint, rootKind, child, true);
            visitChildren(rootKind, inspection.children());
        }

        private void visitChildren(
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                Collection<CoordinationDocumentSplitter
                        .DirectChildOccurrence> children) {
            for (CoordinationDocumentSplitter.DirectChildOccurrence child
                    : children) {
                visitChild(rootKind, child);
            }
        }

        private boolean begin(
                String blueId,
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                String absolutePath) {
            if (priorBlueIds.contains(blueId)) {
                return false;
            }
            if (!visitedContexts.add(new VisitKey(
                    rootKind, absolutePath, blueId))) {
                return false;
            }
            candidates.add(blueId);
            return true;
        }

        private Set<String> candidates() {
            return new LinkedHashSet<String>(candidates);
        }
    }

    private final class Assembly {

        private final CoordinationDocumentSplitter
                .DocumentFragmentationBlueprint blueprint;
        private final Set<String> causalPaths;
        private final Map<String, NodeProviderResult>
                admittedPhysicalBodies;
        private final Set<String> fragmentBlueIds = new TreeSet<String>();
        private final SortedMap<String, Node> newFragments =
                new TreeMap<String, Node>();
        private final SortedMap<EdgeKey, FragmentEdgeRecord> edges =
                new TreeMap<EdgeKey, FragmentEdgeRecord>();
        private final Set<VisitKey> visitedContexts =
                new LinkedHashSet<VisitKey>();
        private final SortedMap<String, SortedMap<String, ShapeReference>>
                physicalShapes =
                new TreeMap<String, SortedMap<String, ShapeReference>>();

        private Assembly(
                CoordinationFragmentInventory prior,
                CoordinationDocumentSplitter
                        .DocumentFragmentationBlueprint blueprint,
                Set<String> causalPaths,
                Map<String, NodeProviderResult> admittedPhysicalBodies) {
            this.blueprint = blueprint;
            this.causalPaths = causalPaths;
            this.admittedPhysicalBodies = Objects.requireNonNull(
                    admittedPhysicalBodies, "admittedPhysicalBodies");
            for (String blueId : prior.fragmentBlueIds()) {
                physicalShapes.put(
                        blueId,
                        new TreeMap<String, ShapeReference>());
            }
            for (FragmentEdgeRecord edge : prior.edges()) {
                SortedMap<String, ShapeReference> shape =
                        physicalShapes.get(edge.ownerNodeBlueId());
                if (shape == null) {
                    throw new IllegalStateException(
                            "Prior edge owner is absent from its inventory: "
                                    + edge.ownerNodeBlueId());
                }
                ShapeReference reference = ShapeReference.from(edge);
                ShapeReference previous = shape.putIfAbsent(
                        reference.relativePointer,
                        reference);
                if (previous != null && !previous.equals(reference)) {
                    throw new IllegalStateException(
                            "Prior inventory has inconsistent physical shape "
                                    + "for " + edge.ownerNodeBlueId()
                                    + reference.relativePointer);
                }
            }
        }

        private void visit(
                CoordinationDocumentSplitter.PhysicalFragmentRoot root) {
            String ownerBlueId = root.blueId();
            if (!beginVisit(
                    ownerBlueId,
                    root.rootKind(),
                    root.basePath())) {
                return;
            }
            if (physicalShapes.containsKey(ownerBlueId)) {
                retainShape(
                        ownerBlueId,
                        root.rootKind(),
                        root.basePath());
                return;
            }
            Node admittedBody = admittedPhysicalBody(ownerBlueId);
            CoordinationDocumentSplitter.DirectNodeInspection inspection =
                    splitter.inspectPhysicalRoot(
                            blueprint,
                            root,
                            admittedBody == null);
            retainInspection(
                    ownerBlueId,
                    root.rootKind(),
                    root.basePath(),
                    inspection,
                    admittedBody);
        }

        private boolean beginVisit(
                String ownerBlueId,
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                String absolutePath) {
            fragmentBlueIds.add(ownerBlueId);
            return visitedContexts.add(new VisitKey(
                    rootKind, absolutePath, ownerBlueId));
        }

        private void retainInspection(
                String ownerBlueId,
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                String ownerAbsolutePath,
                CoordinationDocumentSplitter.DirectNodeInspection
                        inspection,
                Node admittedBody) {
            if (!ownerBlueId.equals(inspection.ownerBlueId())) {
                throw new IllegalStateException(
                        "Direct-node inspection changed owner identity from "
                                + ownerBlueId + " to "
                                + inspection.ownerBlueId());
            }
            if (admittedBody == null
                    && !inspection.assembledFragment()) {
                throw new IllegalStateException(
                        "A new physical identity was inspected without its "
                                + "canonical body: " + ownerBlueId);
            }
            Node selectedBody = admittedBody != null
                    ? admittedBody.clone()
                    : inspection.directFragment();
            String selectedBlueId = DirectBlueIdCalculator.calculateBlueId(
                    selectedBody.clone());
            if (!ownerBlueId.equals(selectedBlueId)
                    || selectedBody.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Selected physical body is invalid for "
                                + ownerBlueId);
            }
            newFragments.put(
                    ownerBlueId,
                    selectedBody);

            List<SelectedChild> children = selectedPhysicalChildren(
                    rootKind,
                    ownerAbsolutePath,
                    selectedBody,
                    inspection.children());
            Collections.sort(
                    children,
                    Comparator
                            .<SelectedChild>
                            comparingInt(
                                    child -> causallyRelated(
                                            child.edge.absolutePointer(),
                                            causalPaths) ? 0 : 1)
                            .thenComparing(
                                    child -> child.edge
                                            .absolutePointer())
                            .thenComparing(
                                    child -> child.edge.childBlueId()));
            SortedMap<String, ShapeReference> shape =
                    new TreeMap<String, ShapeReference>();
            for (SelectedChild child
                    : children) {
                ShapeReference reference = ShapeReference.from(
                        edgeRecord(child.edge));
                ShapeReference previous = shape.putIfAbsent(
                        reference.relativePointer,
                        reference);
                if (previous != null && !previous.equals(reference)) {
                    throw new IllegalStateException(
                            "New fragment has inconsistent physical shape at "
                                    + ownerBlueId
                                    + reference.relativePointer);
                }
            }
            if (physicalShapes.putIfAbsent(ownerBlueId, shape) != null) {
                throw new IllegalStateException(
                        "Physical shape was selected twice for "
                                + ownerBlueId);
            }
            for (SelectedChild child
                    : children) {
                FragmentEdgeRecord edge = edgeRecord(child.edge);
                retainEdge(edge);
                if (edge.splitterCreated()) {
                    if (child.recursionSource == null) {
                        throw new IllegalStateException(
                                "A splitter-created physical edge has no exact "
                                        + "recursion source at "
                                        + edge.absolutePointer());
                    }
                    String childBlueId = edge.childBlueId();
                    if (!beginVisit(
                            childBlueId,
                            rootKind,
                            edge.absolutePointer())) {
                        continue;
                    }
                    if (physicalShapes.containsKey(childBlueId)) {
                        retainShape(
                                childBlueId,
                                rootKind,
                                edge.absolutePointer());
                        continue;
                    }
                    Node admittedChild = admittedPhysicalBody(
                            childBlueId);
                    CoordinationDocumentSplitter.DirectNodeInspection
                            childInspection = splitter.inspectDirectChild(
                            blueprint,
                            rootKind,
                            child.recursionSource,
                            admittedChild == null);
                    retainInspection(
                            childBlueId,
                            rootKind,
                            edge.absolutePointer(),
                            childInspection,
                            admittedChild);
                }
            }
        }

        private Node admittedPhysicalBody(String blueId) {
            NodeProviderResult result = admittedPhysicalBodies.containsKey(
                    blueId)
                    ? admittedPhysicalBodies.get(blueId)
                    : canonicalPhysicalProvider.fetchResultByBlueId(blueId);
            if (result == null) {
                throw new IllegalStateException(
                        "Canonical physical provider returned no outcome for "
                                + blueId);
            }
            if (result.outcome() == NodeProviderOutcome.NOT_FOUND) {
                return null;
            }
            List<Node> candidates = result.nodes();
            if (result.outcome() != NodeProviderOutcome.FOUND
                    || candidates.size() != 1) {
                throw new IllegalStateException(
                        "Canonical physical fragment is unavailable or "
                                + "ambiguous for " + blueId
                                + result.diagnostic()
                                .map(reason -> ": " + reason)
                                .orElse(""));
            }
            Node body = candidates.get(0).clone();
            String actual = DirectBlueIdCalculator.calculateBlueId(
                    body.clone());
            if (!blueId.equals(actual) || body.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Canonical physical provider returned invalid body for "
                                + blueId);
            }
            return body;
        }

        private List<SelectedChild> selectedPhysicalChildren(
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                String ownerAbsolutePath,
                Node selectedBody,
                Collection<CoordinationDocumentSplitter
                        .DirectChildOccurrence> inspectedChildren) {
            Map<String, CoordinationDocumentSplitter.DirectChildOccurrence>
                    sourceByPointer =
                    new LinkedHashMap<String, CoordinationDocumentSplitter
                            .DirectChildOccurrence>();
            for (CoordinationDocumentSplitter.DirectChildOccurrence child
                    : inspectedChildren) {
                CoordinationDocumentSplitter.DirectChildOccurrence previous =
                        sourceByPointer.put(
                                child.edge().ownerRelativePointer(),
                                child);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Direct-node inspection repeated physical pointer "
                                    + child.edge().ownerRelativePointer());
                }
            }
            CoordinationDocumentSplitter.DirectNodeInspection physical =
                    splitter.inspectDirectNode(
                            blueprint,
                            rootKind,
                            selectedBody,
                            ownerAbsolutePath,
                            false);
            List<SelectedChild> selected =
                    new ArrayList<SelectedChild>();
            for (CoordinationDocumentSplitter.DirectChildOccurrence child
                    : physical.children()) {
                /* The canonical body may retain a representation-equivalent
                 * child inline (notably an implicit scalar type). Such a
                 * child is content of this fragment, not a physical edge from
                 * it. Only pure references in the selected stored body belong
                 * in the edge inventory. An independently cut occurrence of
                 * the inline identity is visited from that occurrence. */
                if (!child.exactChild().isReferenceOnly()) {
                    continue;
                }
                CoordinationDocumentSplitter.DirectChildOccurrence source =
                        sourceByPointer.get(
                                child.edge().ownerRelativePointer());
                if (source != null
                        && source.edge().childBlueId().equals(
                                child.edge().childBlueId())) {
                    selected.add(new SelectedChild(
                            source.edge(),
                            source));
                } else {
                    selected.add(new SelectedChild(
                            child.edge(),
                            null));
                }
            }
            return selected;
        }

        private void retainShape(
                String ownerBlueId,
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                String ownerAbsolutePath) {
            SortedMap<String, ShapeReference> shape =
                    physicalShapes.get(ownerBlueId);
            if (shape == null) {
                throw new IllegalStateException(
                        "Retained physical shape is unavailable for "
                                + ownerBlueId);
            }
            List<ShapeReference> references =
                    new ArrayList<ShapeReference>(shape.values());
            Collections.sort(
                    references,
                    Comparator.<ShapeReference>comparingInt(
                            reference -> causallyRelated(
                                            appendRelativePointer(
                                                    ownerAbsolutePath,
                                                    reference
                                                            .relativePointer),
                                            causalPaths) ? 0 : 1)
                            .thenComparing(
                                    reference -> reference.relativePointer)
                            .thenComparing(
                                    reference -> reference.childBlueId));
            for (ShapeReference reference : references) {
                FragmentEdgeRecord edge = edgeRecord(
                        splitter.describeRetainedDirectEdge(
                                blueprint,
                                rootKind,
                                ownerBlueId,
                                ownerAbsolutePath,
                                reference.relativePointer,
                                reference.childBlueId,
                                reference.originalPureReference,
                                reference.splitterCreated));
                retainEdge(edge);
                if (!reference.splitterCreated) {
                    continue;
                }
                if (!beginVisit(
                        reference.childBlueId,
                        rootKind,
                        edge.absolutePointer())) {
                    continue;
                }
                if (!physicalShapes.containsKey(
                        reference.childBlueId)) {
                    throw new IllegalStateException(
                            "Retained physical child shape is unavailable for "
                                    + reference.childBlueId);
                }
                retainShape(
                        reference.childBlueId,
                        rootKind,
                        edge.absolutePointer());
            }
        }

        private void retainEdge(
                FragmentEdgeRecord edge) {
            EdgeKey key = new EdgeKey(
                    edge.ownerNodeBlueId(),
                    edge.absolutePointer(),
                    edge.childBlueId());
            FragmentEdgeRecord existing = edges.get(key);
            if (existing == null) {
                edges.put(key, edge);
                return;
            }
            if (existing.rootKind()
                    != CoordinationDocumentSplitter
                    .FragmentRootKind.DOCUMENT
                    && edge.rootKind()
                    == CoordinationDocumentSplitter
                    .FragmentRootKind.DOCUMENT) {
                edges.put(key, edge);
                return;
            }
            if (!physicallyEquivalent(existing, edge)) {
                throw new IllegalStateException(
                        "One incremental direct edge has inconsistent "
                                + "occurrence metadata at "
                                + edge.absolutePointer());
            }
        }

        private List<FragmentEdgeRecord> edgeRecords() {
            return Collections.unmodifiableList(
                    new ArrayList<FragmentEdgeRecord>(
                            edges.values()));
        }
    }

    private static FragmentEdgeRecord edgeRecord(
            CoordinationDocumentSplitter.EdgeOccurrence edge) {
        return FragmentEdgeRecord.fromVerifiedOccurrence(edge);
    }

    private static final class ShapeReference {

        private final String relativePointer;
        private final String childBlueId;
        private final boolean originalPureReference;
        private final boolean splitterCreated;

        private ShapeReference(
                String relativePointer,
                String childBlueId,
                boolean originalPureReference,
                boolean splitterCreated) {
            this.relativePointer = relativePointer;
            this.childBlueId = childBlueId;
            this.originalPureReference = originalPureReference;
            this.splitterCreated = splitterCreated;
        }

        private static ShapeReference from(
                FragmentEdgeRecord edge) {
            return new ShapeReference(
                    edge.ownerRelativePointer(),
                    edge.childBlueId(),
                    edge.originalPureReference(),
                    edge.splitterCreated());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ShapeReference)) {
                return false;
            }
            ShapeReference that = (ShapeReference) other;
            return relativePointer.equals(that.relativePointer)
                    && childBlueId.equals(that.childBlueId)
                    && originalPureReference == that.originalPureReference
                    && splitterCreated == that.splitterCreated;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    relativePointer,
                    childBlueId,
                    originalPureReference,
                    splitterCreated);
        }
    }

    private static final class SelectedChild {

        private final CoordinationDocumentSplitter.EdgeOccurrence edge;
        private final CoordinationDocumentSplitter.DirectChildOccurrence
                recursionSource;

        private SelectedChild(
                CoordinationDocumentSplitter.EdgeOccurrence edge,
                CoordinationDocumentSplitter.DirectChildOccurrence
                        recursionSource) {
            this.edge = Objects.requireNonNull(edge, "edge");
            this.recursionSource = recursionSource;
        }
    }

    /** Allocation-bounded occurrence identity used only inside one assembly. */
    private static final class VisitKey {
        private final CoordinationDocumentSplitter.FragmentRootKind rootKind;
        private final String absolutePath;
        private final String blueId;

        private VisitKey(
                CoordinationDocumentSplitter.FragmentRootKind rootKind,
                String absolutePath,
                String blueId) {
            this.rootKind = Objects.requireNonNull(rootKind, "rootKind");
            this.absolutePath = Objects.requireNonNull(
                    absolutePath, "absolutePath");
            this.blueId = Objects.requireNonNull(blueId, "blueId");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof VisitKey)) return false;
            VisitKey that = (VisitKey) other;
            return rootKind == that.rootKind
                    && absolutePath.equals(that.absolutePath)
                    && blueId.equals(that.blueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rootKind, absolutePath, blueId);
        }
    }

    /** Deterministic edge tuple without concatenating deep pointer strings. */
    private static final class EdgeKey implements Comparable<EdgeKey> {
        private final String ownerBlueId;
        private final String absolutePointer;
        private final String childBlueId;

        private EdgeKey(
                String ownerBlueId,
                String absolutePointer,
                String childBlueId) {
            this.ownerBlueId = Objects.requireNonNull(
                    ownerBlueId, "ownerBlueId");
            this.absolutePointer = Objects.requireNonNull(
                    absolutePointer, "absolutePointer");
            this.childBlueId = Objects.requireNonNull(
                    childBlueId, "childBlueId");
        }

        @Override
        public int compareTo(EdgeKey other) {
            int ownerOrder = ownerBlueId.compareTo(other.ownerBlueId);
            if (ownerOrder != 0) return ownerOrder;
            int pointerOrder = absolutePointer.compareTo(
                    other.absolutePointer);
            return pointerOrder != 0
                    ? pointerOrder
                    : childBlueId.compareTo(other.childBlueId);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EdgeKey
                    && compareTo((EdgeKey) other) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(ownerBlueId, absolutePointer, childBlueId);
        }
    }

    private static boolean physicallyEquivalent(
            FragmentEdgeRecord left,
            FragmentEdgeRecord right) {
        return left.schemaIdentity().equals(right.schemaIdentity())
                && left.rootBlueId().equals(right.rootBlueId())
                && left.ownerNodeBlueId().equals(
                        right.ownerNodeBlueId())
                && Objects.equals(
                        left.ownerScopePath(),
                        right.ownerScopePath())
                && left.absolutePointer().equals(
                        right.absolutePointer())
                && left.ownerRelativePointer().equals(
                        right.ownerRelativePointer())
                && left.childBlueId().equals(right.childBlueId())
                && left.edgeKind() == right.edgeKind()
                && left.originalPureReference()
                        == right.originalPureReference()
                && left.splitterCreated() == right.splitterCreated()
                && Objects.equals(
                        left.declaringScopePath(),
                        right.declaringScopePath())
                && left.embeddedOrigin() == right.embeddedOrigin()
                && Objects.equals(
                        left.explicitDeclarationPath(),
                        right.explicitDeclarationPath())
                && Objects.equals(
                        left.collectionDeclarationPath(),
                        right.collectionDeclarationPath())
                && Objects.equals(
                        left.collectionMemberKey(),
                        right.collectionMemberKey())
                && Objects.equals(
                        left.handlerEffectiveTypeBlueId(),
                        right.handlerEffectiveTypeBlueId())
                && Objects.equals(
                        left.executableBodyField(),
                        right.executableBodyField())
                && left.sourceContributionBlueIds().equals(
                        right.sourceContributionBlueIds());
    }

    private static Set<String> immutablePaths(
            Collection<String> paths) {
        Set<String> result = new LinkedHashSet<String>();
        for (String path : Objects.requireNonNull(
                paths, "causalScopePaths")) {
            result.add(blue.language.model.wire.JsonPointer.canonicalize(
                    Objects.requireNonNull(path, "causalScopePath")));
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean causallyRelated(
            String path,
            Set<String> causalPaths) {
        for (String causalPath : causalPaths) {
            if (descendantOrEqual(path, causalPath)
                    || descendantOrEqual(causalPath, path)) {
                return true;
            }
        }
        return false;
    }

    private static String appendRelativePointer(
            String base,
            String relative) {
        if ("/".equals(relative)) return base;
        return "/".equals(base) ? relative : base + relative;
    }

    /** Canonical JSON pointers make slash-boundary ancestry a text test. */
    private static boolean descendantOrEqual(
            String candidate,
            String ancestor) {
        return candidate.equals(ancestor)
                || "/".equals(ancestor)
                || (candidate.startsWith(ancestor)
                && candidate.length() > ancestor.length()
                && candidate.charAt(ancestor.length()) == '/');
    }

    static final class AssembledDocument {

        private final CoordinationFragmentInventory inventory;
        private final Map<String, Node> newFragments;
        private final Map<String, Node> processingViews;

        private AssembledDocument(
                CoordinationFragmentInventory inventory,
                Map<String, Node> newFragments,
                Map<String, Node> processingViews) {
            this.inventory = Objects.requireNonNull(
                    inventory, "inventory");
            this.newFragments = immutableNodes(newFragments);
            this.processingViews = immutableNodes(processingViews);
        }

        CoordinationFragmentInventory inventory() {
            return inventory;
        }

        Map<String, Node> newFragments() {
            return newFragments;
        }

        Map<String, Node> processingViews() {
            return processingViews;
        }

        private static Map<String, Node> immutableNodes(
                Map<String, Node> supplied) {
            Map<String, Node> result = new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> entry
                    : new TreeMap<String, Node>(
                            Objects.requireNonNull(
                                    supplied,
                                    "supplied")).entrySet()) {
                result.put(entry.getKey(), entry.getValue().clone());
            }
            return Collections.unmodifiableMap(result);
        }
    }
}
