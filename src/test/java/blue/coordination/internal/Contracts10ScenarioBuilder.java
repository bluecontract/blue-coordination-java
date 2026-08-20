package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Test-only authored-document facade for Contracts 1.0 closure admission. */
final class Contracts10ScenarioBuilder {

    enum OccurrenceOrder {
        DECLARED,
        REVERSED
    }

    enum ReferenceRepresentation {
        REFERENCE_ONLY,
        MATERIALIZED
    }

    private enum EdgeKind {
        PATH,
        COLLECTION_MEMBER
    }

    private static final String EMBEDDED_CONTRACT = "embedded";
    private static final String ADMISSION_POLICY =
            "contracts-top-level-admission-v1";

    private final DefaultCoordinationEngine engine;
    private final LinkedHashMap<DocumentId, Node> documents =
            new LinkedHashMap<>();
    private final ArrayList<Edge> edges = new ArrayList<>();
    private final LinkedHashSet<DocumentId> publicRoots =
            new LinkedHashSet<>();
    private final ArrayList<List<DocumentId>> expectedComponents =
            new ArrayList<>();
    private OccurrenceOrder occurrenceOrder = OccurrenceOrder.DECLARED;
    private ReferenceRepresentation representation =
            ReferenceRepresentation.REFERENCE_ONLY;
    private String admissionLabel = "contracts10-authored-scenario";
    private Scenario built;

    Contracts10ScenarioBuilder(DefaultCoordinationEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    Contracts10ScenarioBuilder document(
            DocumentId documentId,
            String yaml) {
        requireMutable();
        Objects.requireNonNull(yaml, "yaml");
        return document(documentId, engine.exactValue(yaml).copyNode());
    }

    Contracts10ScenarioBuilder document(
            DocumentId documentId,
            Node document) {
        requireMutable();
        DocumentId selectedId = Objects.requireNonNull(
                documentId, "documentId");
        Node selectedDocument = Objects.requireNonNull(
                document, "document").clone();
        requireOrWriteDocumentId(selectedId, selectedDocument);
        if (documents.put(selectedId, selectedDocument) != null) {
            throw new IllegalArgumentException(
                    "Duplicate scenario document " + selectedId);
        }
        return this;
    }

    Contracts10ScenarioBuilder processEmbeddedPath(
            DocumentId source,
            String path,
            DocumentId target) {
        requireMutable();
        edges.add(Edge.path(source, path, target));
        return this;
    }

    Contracts10ScenarioBuilder processEmbeddedCollectionMember(
            DocumentId source,
            String collectionPath,
            String memberKey,
            DocumentId target) {
        requireMutable();
        edges.add(Edge.collectionMember(
                source, collectionPath, memberKey, target));
        return this;
    }

    Contracts10ScenarioBuilder publicRoot(DocumentId documentId) {
        requireMutable();
        if (!publicRoots.add(Objects.requireNonNull(
                documentId, "documentId"))) {
            throw new IllegalArgumentException(
                    "Duplicate public Root " + documentId);
        }
        return this;
    }

    Contracts10ScenarioBuilder expectedComponent(DocumentId... members) {
        requireMutable();
        Objects.requireNonNull(members, "members");
        if (members.length == 0) {
            throw new IllegalArgumentException(
                    "An expected component must have a member");
        }
        ArrayList<DocumentId> retained = new ArrayList<>(members.length);
        for (DocumentId member : members) {
            retained.add(Objects.requireNonNull(
                    member, "expected component member"));
        }
        expectedComponents.add(Collections.unmodifiableList(retained));
        return this;
    }

    Contracts10ScenarioBuilder occurrenceOrder(OccurrenceOrder order) {
        requireMutable();
        occurrenceOrder = Objects.requireNonNull(order, "order");
        return this;
    }

    Contracts10ScenarioBuilder representation(
            ReferenceRepresentation selectedRepresentation) {
        requireMutable();
        representation = Objects.requireNonNull(
                selectedRepresentation, "selectedRepresentation");
        return this;
    }

    Contracts10ScenarioBuilder admissionLabel(String label) {
        requireMutable();
        String selected = Objects.requireNonNull(label, "label").trim();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Admission label must not be blank");
        }
        admissionLabel = selected;
        return this;
    }

    ClosureInvocationInput admission() {
        return scenario().admission();
    }

    Scenario scenario() {
        if (built == null) {
            built = build();
        }
        return built;
    }

    ScenarioRuntime admitTo(CoordinationEngine publicEngine) {
        CoordinationEngine selected = Objects.requireNonNull(
                publicEngine, "publicEngine");
        if (selected != engine) {
            throw new IllegalArgumentException(
                    "A scenario must be admitted to the engine that authored it");
        }
        Scenario selectedScenario = scenario();
        ContractsClosureAdmissionReceipt receipt = selected
                .admitContractsClosure(
                        selectedScenario.admission(),
                        CoordinationEngine.AdmissionPolicy.FROM_NOW,
                        null);
        return new ScenarioRuntime(selectedScenario, receipt);
    }

    private Scenario build() {
        requireCompleteDeclarations();
        LinkedHashMap<DocumentId, Node> authoredBodies =
                prepareAuthoredBodies();
        List<blue.language.processor.closure.DocumentId> closureIds =
                documents.keySet().stream()
                        .map(Contracts10ScenarioBuilder::closureId)
                        .toList();
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        List<ManagedOccurrenceBinding> bindingInput = bindings(
                authoredBodies, environment);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                closureIds, bindingInput);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = generations(closureIds);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                closureBodies = closureBodies(authoredBodies);
        ComponentFinalizationResult finalization =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations,
                                closureBodies,
                                bindingInput));

        requireExpectedPartition(finalization);
        List<ManagedOccurrenceBinding> canonicalBindings =
                verifyCanonicalBindings(finalization);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                representedBodies = representedBodies(
                        finalization, canonicalBindings);
        Map<String, String> verifiedMasters = verifyLanguageEvidence(
                finalization, representedBodies);
        List<ManagedDocumentSnapshot> snapshots = snapshots(
                finalization, representedBodies);
        List<ComponentSnapshot> components = finalization.components().stream()
                .map(FinalizedComponentEvidence::component)
                .toList();
        List<blue.language.processor.closure.DocumentId> closureRoots =
                publicRoots.stream()
                        .map(Contracts10ScenarioBuilder::closureId)
                        .toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        snapshots,
                        canonicalBindings,
                        components,
                        closureRoots);
        ExecutionPolicy policy = engine.contractsClosureAdmissionAdapter()
                .executionPolicy();
        ClosureInvocationInput invocation = ClosureEvidenceFactory
                .admitClosure(
                        snapshot,
                        ClosureEvidenceFactory.admissionCause(
                                AdmissionKind.TOP_LEVEL_ADMISSION,
                                admissionLabel,
                                null,
                                null,
                                ADMISSION_POLICY),
                        null,
                        policy,
                        environment);
        return new Scenario(
                invocation,
                authoredBodies,
                representedBodies,
                finalization,
                canonicalBindings,
                expectedComponents,
                verifiedMasters,
                representation);
    }

    private void requireCompleteDeclarations() {
        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "A scenario requires at least one document");
        }
        if (expectedComponents.isEmpty()) {
            throw new IllegalStateException(
                    "Declare literal expected components before building");
        }
        for (Edge edge : edges) {
            if (!documents.containsKey(edge.source())) {
                throw new IllegalArgumentException(
                        "Occurrence source is not a scenario document: "
                                + edge.source());
            }
            if (!documents.containsKey(edge.target())) {
                throw new IllegalArgumentException(
                        "Occurrence target is not a scenario document: "
                                + edge.target());
            }
        }
        for (DocumentId publicRoot : publicRoots) {
            if (!documents.containsKey(publicRoot)) {
                throw new IllegalArgumentException(
                        "Public Root is not a scenario document: "
                                + publicRoot);
            }
        }
        requireExpectedCoverage();
        requireUniqueNonOverlappingOccurrences();
    }

    private void requireExpectedCoverage() {
        LinkedHashSet<DocumentId> covered = new LinkedHashSet<>();
        for (List<DocumentId> component : expectedComponents) {
            DocumentId previous = null;
            for (DocumentId member : component) {
                if (!documents.containsKey(member)) {
                    throw new IllegalArgumentException(
                            "Expected component names an unknown document: "
                                    + member);
                }
                if (!covered.add(member)) {
                    throw new IllegalArgumentException(
                            "Expected component repeats document " + member);
                }
                if (previous != null && previous.compareTo(member) >= 0) {
                    throw new IllegalArgumentException(
                            "Expected component members must use canonical "
                                    + "DocumentId order");
                }
                previous = member;
            }
        }
        if (!covered.equals(new HashSet<>(documents.keySet()))) {
            throw new IllegalArgumentException(
                    "Expected components must cover every scenario document");
        }
    }

    private void requireUniqueNonOverlappingOccurrences() {
        Map<DocumentId, List<String>> pathsBySource = new LinkedHashMap<>();
        for (Edge edge : edges) {
            List<String> paths = pathsBySource.computeIfAbsent(
                    edge.source(), ignored -> new ArrayList<>());
            for (String existing : paths) {
                if (overlaps(existing, edge.sourcePath())) {
                    throw new IllegalArgumentException(
                            "Scenario occurrence paths overlap in "
                                    + edge.source() + ": " + existing
                                    + " and " + edge.sourcePath());
                }
            }
            paths.add(edge.sourcePath());
        }
    }

    private LinkedHashMap<DocumentId, Node> prepareAuthoredBodies() {
        LinkedHashMap<DocumentId, Node> result = new LinkedHashMap<>();
        documents.forEach((documentId, document) ->
                result.put(documentId, document.clone()));
        TreeMap<DocumentId, TreeSet<String>> paths = new TreeMap<>();
        TreeMap<DocumentId, TreeSet<String>> collections = new TreeMap<>();
        for (Edge edge : edges) {
            if (edge.kind() == EdgeKind.PATH) {
                paths.computeIfAbsent(
                        edge.source(), ignored -> new TreeSet<>())
                        .add(edge.contractPath());
            } else {
                collections.computeIfAbsent(
                        edge.source(), ignored -> new TreeSet<>())
                        .add(edge.contractPath());
            }
            NodePathEditor.put(
                    result.get(edge.source()),
                    edge.sourcePath(),
                    new Node().blueId(seedBlueId(edge.target())));
        }
        for (DocumentId documentId : new TreeSet<>(documents.keySet())) {
            Node document = result.get(documentId);
            addProcessEmbeddedContract(
                    document,
                    paths.get(documentId),
                    collections.get(documentId));
        }
        if (representation == ReferenceRepresentation.MATERIALIZED) {
            LinkedHashMap<DocumentId, Node> referenceBodies =
                    cloneBodies(result);
            for (Edge edge : edges) {
                Node materialized = referenceBodies.get(edge.target())
                        .clone()
                        .blueId(seedBlueId(edge.target()));
                NodePathEditor.put(
                        result.get(edge.source()),
                        edge.sourcePath(),
                        materialized);
            }
        }
        return result;
    }

    private List<ManagedOccurrenceBinding> bindings(
            Map<DocumentId, Node> authoredBodies,
            ClosureEnvironment environment) {
        ArrayList<ManagedOccurrenceBinding> result = new ArrayList<>();
        for (Edge edge : edges) {
            Node exactReference = NodePathEditor.getOrNull(
                    authoredBodies.get(edge.source()), edge.sourcePath());
            if (exactReference == null
                    || !seedBlueId(edge.target()).equals(
                            exactReference.getBlueId())) {
                throw new IllegalStateException(
                        "Declared occurrence does not carry the exact "
                                + "authored target identity "
                                + edge.source() + edge.sourcePath());
            }
            ManagedOccurrenceBinding derived =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            closureId(edge.source()),
                            ScopeAddress.embedded(edge.sourcePath(), 1L),
                            closureId(edge.target()),
                            exactReference.getBlueId(),
                            true,
                            null);
            result.add(ManagedOccurrenceBinding.verified(
                    derived.occurrenceIdentity(),
                    derived.bindingIdentity(),
                    derived.bindingPolicyIdentity(),
                    derived.sourceDocumentId(),
                    derived.sourceAddress(),
                    derived.targetDocumentId(),
                    derived.expectedTargetBlueId(),
                    derived.active(),
                    derived.pendingHistoricalEpoch()));
        }
        if (occurrenceOrder == OccurrenceOrder.REVERSED) {
            Collections.reverse(result);
        }
        return result;
    }

    private static LinkedHashMap<blue.language.processor.closure.DocumentId,
            Long> generations(
                    Collection<blue.language.processor.closure.DocumentId>
                            documentIds) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                result = new LinkedHashMap<>();
        for (blue.language.processor.closure.DocumentId documentId
                : documentIds) {
            result.put(documentId, 1L);
        }
        return result;
    }

    private static LinkedHashMap<blue.language.processor.closure.DocumentId,
            Node> closureBodies(Map<DocumentId, Node> authoredBodies) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                result = new LinkedHashMap<>();
        authoredBodies.forEach((documentId, document) ->
                result.put(closureId(documentId), document.clone()));
        return result;
    }

    private void requireExpectedPartition(
            ComponentFinalizationResult finalization) {
        List<List<blue.language.processor.closure.DocumentId>> expected =
                expectedComponents.stream()
                        .map(component -> component.stream()
                                .map(Contracts10ScenarioBuilder::closureId)
                                .toList())
                        .toList();
        List<List<blue.language.processor.closure.DocumentId>> actual =
                finalization.components().stream()
                        .map(FinalizedComponentEvidence::component)
                        .map(ComponentSnapshot::orderedMemberDocumentIds)
                        .toList();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Literal expected component partition " + expected
                            + " does not match verified partition " + actual);
        }
    }

    private static List<ManagedOccurrenceBinding> verifyCanonicalBindings(
            ComponentFinalizationResult finalization) {
        List<ManagedOccurrenceBinding> rows =
                finalization.finalizedGraph().bindings();
        ArrayList<ManagedOccurrenceBinding> sorted = new ArrayList<>(rows);
        Collections.sort(sorted);
        if (!bindingIdentitySequence(rows).equals(
                bindingIdentitySequence(sorted))) {
            throw new IllegalStateException(
                    "Finalized occurrence rows are not canonical");
        }
        for (ManagedOccurrenceBinding row : rows) {
            ManagedOccurrenceBinding.verified(
                    row.occurrenceIdentity(),
                    row.bindingIdentity(),
                    row.bindingPolicyIdentity(),
                    row.sourceDocumentId(),
                    row.sourceAddress(),
                    row.targetDocumentId(),
                    row.expectedTargetBlueId(),
                    row.active(),
                    row.pendingHistoricalEpoch());
        }
        return List.copyOf(rows);
    }

    private LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
            representedBodies(
                    ComponentFinalizationResult finalization,
                    List<ManagedOccurrenceBinding> bindings) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                result = new LinkedHashMap<>();
        finalization.documents().forEach((documentId, evidence) ->
                result.put(documentId, evidence.document()));
        return result;
    }

    private static Map<String, String> verifyLanguageEvidence(
            ComponentFinalizationResult finalization,
            Map<blue.language.processor.closure.DocumentId, Node> bodies) {
        ScenarioProofProvider evidence = new ScenarioProofProvider();
        for (FinalizedDocumentEvidence document
                : finalization.documents().values()) {
            evidence.addDocument(
                    document.blueId(), bodies.get(document.documentId()));
        }
        LinkedHashMap<String, String> verifiedMasters =
                new LinkedHashMap<>();
        for (FinalizedComponentEvidence finalizedComponent
                : finalization.components()) {
            ComponentSnapshot component = finalizedComponent.component();
            if (component.kind() != ComponentKind.CYCLIC) {
                continue;
            }
            CyclicSetProof proof = component.completeCyclicProof();
            for (String memberBlueId : component.orderedMemberBlueIds()) {
                evidence.addProof(memberBlueId, proof);
            }
            List<String> independentlyCalculated =
                    CircularSetIdentityCalculator
                            .calculateCircularSetBlueIds(
                                    proof.declaredPlaceholderSet());
            String verifiedMaster = BlueIds.cyclicSetMasterBlueId(
                    independentlyCalculated.get(0));
            if (!verifiedMaster.equals(component.masterBlueId())
                    || !new HashSet<>(independentlyCalculated).equals(
                            new HashSet<>(
                                    component.orderedMemberBlueIds()))) {
                throw new IllegalArgumentException(
                        "Complete Language proof does not independently "
                                + "verify the claimed cyclic component");
            }
            verifiedMasters.put(
                    component.componentIdentity(), verifiedMaster);
        }
        VerifyingNodeProvider verifier = new VerifyingNodeProvider(evidence);
        for (FinalizedDocumentEvidence document
                : finalization.documents().values()) {
            List<Node> verified = verifier.fetchByBlueId(document.blueId());
            if (verified == null || verified.size() != 1) {
                throw new IllegalArgumentException(
                        "Language proof verifier did not return one exact "
                                + "document for " + document.documentId());
            }
        }
        return Collections.unmodifiableMap(verifiedMasters);
    }

    private List<ManagedDocumentSnapshot> snapshots(
            ComponentFinalizationResult finalization,
            Map<blue.language.processor.closure.DocumentId, Node> bodies) {
        ArrayList<ManagedDocumentSnapshot> result = new ArrayList<>();
        for (FinalizedDocumentEvidence document
                : finalization.documents().values()) {
            result.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    bodies.get(document.documentId()),
                    false,
                    false,
                    publicRoots.contains(apiId(document.documentId())),
                    0L,
                    document.componentGeneration()));
        }
        Collections.sort(result);
        return List.copyOf(result);
    }

    private static void addProcessEmbeddedContract(
            Node document,
            Collection<String> paths,
            Collection<String> collectionPaths) {
        boolean hasPaths = paths != null && !paths.isEmpty();
        boolean hasCollections = collectionPaths != null
                && !collectionPaths.isEmpty();
        if (!hasPaths && !hasCollections) {
            return;
        }
        Node contracts = document.getContracts();
        if (contracts == null) {
            contracts = new Node();
            document.contracts(contracts);
        }
        Map<String, Node> contractValues = contracts.getProperties();
        if (contractValues == null) {
            contracts.properties(new LinkedHashMap<>());
            contractValues = contracts.getProperties();
        }
        if (contractValues.containsKey(EMBEDDED_CONTRACT)) {
            throw new IllegalArgumentException(
                    "Scenario document already uses reserved contract key "
                            + EMBEDDED_CONTRACT);
        }
        LinkedHashMap<String, Node> embeddedProperties =
                new LinkedHashMap<>();
        if (hasPaths) {
            embeddedProperties.put("paths", pathList(paths));
        }
        if (hasCollections) {
            embeddedProperties.put(
                    "collectionPaths", pathList(collectionPaths));
        }
        contractValues.put(
                EMBEDDED_CONTRACT,
                new Node()
                        .type(new Node().blueId(
                                RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties(embeddedProperties));
    }

    private static Node pathList(Collection<String> paths) {
        ArrayList<Node> values = new ArrayList<>();
        for (String path : paths) {
            values.add(new Node().value(path));
        }
        return new Node().items(values);
    }

    private static LinkedHashMap<DocumentId, Node> cloneBodies(
            Map<DocumentId, Node> source) {
        LinkedHashMap<DocumentId, Node> result = new LinkedHashMap<>();
        source.forEach((documentId, document) ->
                result.put(documentId, document.clone()));
        return result;
    }

    private static void requireOrWriteDocumentId(
            DocumentId documentId,
            Node document) {
        Node declared = NodePathEditor.getOrNull(document, "/documentId");
        if (declared == null) {
            NodePathEditor.put(
                    document,
                    "/documentId",
                    new Node().value(documentId.value()));
            return;
        }
        if (!documentId.value().equals(declared.getValue())) {
            throw new IllegalArgumentException(
                    "Authored documentId does not match scenario lineage "
                            + documentId);
        }
    }

    private static String seedBlueId(DocumentId target) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value("scenario-target:" + target.value()));
    }

    private static boolean overlaps(String left, String right) {
        return left.equals(right)
                || left.startsWith(right + "/")
                || right.startsWith(left + "/");
    }

    private static List<String> bindingIdentitySequence(
            List<ManagedOccurrenceBinding> bindings) {
        return bindings.stream()
                .map(row -> row.occurrenceIdentity()
                        + ":" + row.bindingIdentity())
                .toList();
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static DocumentId apiId(
            blue.language.processor.closure.DocumentId documentId) {
        return DocumentId.of(documentId.value());
    }

    private void requireMutable() {
        if (built != null) {
            throw new IllegalStateException(
                    "A built scenario cannot be mutated");
        }
    }

    static final class Scenario {
        private final ClosureInvocationInput admission;
        private final Map<DocumentId, Node> authoredDocuments;
        private final Map<DocumentId, Node> documents;
        private final Map<DocumentId, String> blueIds;
        private final List<ManagedOccurrenceBinding> bindings;
        private final List<ComponentSnapshot> components;
        private final Map<DocumentId, List<DocumentId>> adjacency;
        private final List<List<DocumentId>> expectedComponents;
        private final Map<DocumentId, String> independentlyVerifiedMasters;
        private final ReferenceRepresentation representation;

        private Scenario(
                ClosureInvocationInput admission,
                Map<DocumentId, Node> authoredBodies,
                Map<blue.language.processor.closure.DocumentId, Node> bodies,
                ComponentFinalizationResult finalization,
                List<ManagedOccurrenceBinding> bindings,
                List<List<DocumentId>> expectedComponents,
                Map<String, String> verifiedMasters,
                ReferenceRepresentation representation) {
            this.admission = Objects.requireNonNull(admission, "admission");
            this.authoredDocuments = Collections.unmodifiableMap(
                    cloneBodies(authoredBodies));
            LinkedHashMap<DocumentId, Node> retainedDocuments =
                    new LinkedHashMap<>();
            LinkedHashMap<DocumentId, String> retainedBlueIds =
                    new LinkedHashMap<>();
            finalization.documents().forEach((documentId, evidence) -> {
                DocumentId apiDocumentId = apiId(documentId);
                retainedDocuments.put(
                        apiDocumentId, bodies.get(documentId).clone());
                retainedBlueIds.put(apiDocumentId, evidence.blueId());
            });
            this.documents = Collections.unmodifiableMap(retainedDocuments);
            this.blueIds = Collections.unmodifiableMap(retainedBlueIds);
            this.bindings = List.copyOf(bindings);
            this.components = finalization.components().stream()
                    .map(FinalizedComponentEvidence::component)
                    .toList();
            LinkedHashMap<DocumentId, List<DocumentId>> retainedAdjacency =
                    new LinkedHashMap<>();
            finalization.finalizedGraph().adjacency().forEach(
                    (source, targets) -> retainedAdjacency.put(
                            apiId(source),
                            targets.stream()
                                    .map(Contracts10ScenarioBuilder::apiId)
                                    .toList()));
            this.adjacency = Collections.unmodifiableMap(retainedAdjacency);
            this.expectedComponents = expectedComponents.stream()
                    .map(List::copyOf)
                    .toList();
            LinkedHashMap<DocumentId, String> masters =
                    new LinkedHashMap<>();
            for (ComponentSnapshot component : this.components) {
                String verified = verifiedMasters.get(
                        component.componentIdentity());
                if (verified == null) {
                    continue;
                }
                for (blue.language.processor.closure.DocumentId member
                        : component.orderedMemberDocumentIds()) {
                    masters.put(apiId(member), verified);
                }
            }
            this.independentlyVerifiedMasters =
                    Collections.unmodifiableMap(masters);
            this.representation = Objects.requireNonNull(
                    representation, "representation");
        }

        ClosureInvocationInput admission() {
            return admission;
        }

        Node document(DocumentId documentId) {
            Node document = documents.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (document == null) {
                throw new IllegalArgumentException(
                        "Unknown scenario document " + documentId);
            }
            return document.clone();
        }

        Node authoredDocument(DocumentId documentId) {
            Node document = authoredDocuments.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (document == null) {
                throw new IllegalArgumentException(
                        "Unknown scenario document " + documentId);
            }
            return document.clone();
        }

        Map<DocumentId, String> blueIds() {
            return blueIds;
        }

        String blueId(DocumentId documentId) {
            String blueId = blueIds.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (blueId == null) {
                throw new IllegalArgumentException(
                        "Unknown scenario document " + documentId);
            }
            return blueId;
        }

        List<ManagedOccurrenceBinding> bindings() {
            return bindings;
        }

        List<ComponentSnapshot> components() {
            return components;
        }

        List<List<DocumentId>> componentMembers() {
            return components.stream()
                    .map(ComponentSnapshot::orderedMemberDocumentIds)
                    .map(members -> members.stream()
                            .map(Contracts10ScenarioBuilder::apiId)
                            .toList())
                    .toList();
        }

        List<List<DocumentId>> expectedComponents() {
            return expectedComponents;
        }

        Map<DocumentId, List<DocumentId>> adjacency() {
            return adjacency;
        }

        String independentlyVerifiedMaster(DocumentId documentId) {
            return independentlyVerifiedMasters.get(Objects.requireNonNull(
                    documentId, "documentId"));
        }

        ReferenceRepresentation representation() {
            return representation;
        }
    }

    static final class ScenarioRuntime {
        private final Scenario scenario;
        private final ContractsClosureAdmissionReceipt admissionReceipt;

        private ScenarioRuntime(
                Scenario scenario,
                ContractsClosureAdmissionReceipt admissionReceipt) {
            this.scenario = Objects.requireNonNull(scenario, "scenario");
            this.admissionReceipt = Objects.requireNonNull(
                    admissionReceipt, "admissionReceipt");
        }

        Scenario scenario() {
            return scenario;
        }

        ContractsClosureAdmissionReceipt admissionReceipt() {
            return admissionReceipt;
        }
    }

    private record Edge(
            DocumentId source,
            String sourcePath,
            String contractPath,
            DocumentId target,
            EdgeKind kind) {

        private Edge {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
            validatePointer(sourcePath, "sourcePath");
            validatePointer(contractPath, "contractPath");
        }

        private static Edge path(
                DocumentId source,
                String path,
                DocumentId target) {
            String selectedPath = Objects.requireNonNull(path, "path");
            return new Edge(
                    source,
                    selectedPath,
                    selectedPath,
                    target,
                    EdgeKind.PATH);
        }

        private static Edge collectionMember(
                DocumentId source,
                String collectionPath,
                String memberKey,
                DocumentId target) {
            String selectedCollection = Objects.requireNonNull(
                    collectionPath, "collectionPath");
            String selectedMember = Objects.requireNonNull(
                    memberKey, "memberKey");
            if (selectedMember.isEmpty()) {
                throw new IllegalArgumentException(
                        "Collection member key must not be empty");
            }
            String sourcePath = selectedCollection + "/"
                    + escapePointerToken(selectedMember);
            return new Edge(
                    source,
                    sourcePath,
                    selectedCollection,
                    target,
                    EdgeKind.COLLECTION_MEMBER);
        }

        private static void validatePointer(String pointer, String label) {
            blue.language.model.wire.JsonPointer.split(
                    Objects.requireNonNull(pointer, label));
            if (pointer.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " must identify a non-Root location");
            }
        }

        private static String escapePointerToken(String token) {
            return token.replace("~", "~0").replace("/", "~1");
        }
    }

    private static final class ScenarioProofProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final Map<String, Node> documents = new LinkedHashMap<>();
        private final Map<String, CyclicSetProof> proofs =
                new LinkedHashMap<>();

        private void addDocument(String blueId, Node document) {
            documents.put(
                    Objects.requireNonNull(blueId, "blueId"),
                    Objects.requireNonNull(document, "document").clone());
        }

        private void addProof(String blueId, CyclicSetProof proof) {
            proofs.put(
                    Objects.requireNonNull(blueId, "blueId"),
                    CyclicSetProof.fromDeclaredPlaceholderSet(
                            Objects.requireNonNull(proof, "proof")
                                    .declaredPlaceholderSet()));
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node document = documents.get(blueId);
            return document == null
                    ? List.of()
                    : List.of(document.clone());
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            CyclicSetProof proof = proofs.get(blueId);
            return proof == null
                    ? CyclicSetProofResult.notFound()
                    : CyclicSetProofResult.found(proof);
        }
    }
}
