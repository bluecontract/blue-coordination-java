package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExternalOrderKey;
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
import blue.language.processor.util.PointerUtils;
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
import java.util.Set;

/**
 * Compiles authored Contracts 1.0 documents into verified closure admission
 * evidence.
 *
 * <p>This is the production counterpart of the old scenario fixture. Callers
 * supply stable managed lineages and occurrence declarations, never a graph,
 * component partition, BlueId, cyclic proof, or closure snapshot. The
 * compiler resolves effective Process Embedded declarations with the shipped
 * runtime, derives the graph solely from the declared occurrences, and asks
 * Language's finalization and proof-verification kernels to establish the
 * complete closure.</p>
 *
 * <p>The class is public only as an internal cross-package bridge. A public
 * SDK translates its immutable values into these DTOs without making this
 * compiler or any low-level closure type part of the ordinary SDK surface.</p>
 */
public final class Contracts10AuthoredClosureCompiler {
    private static final String ADMISSION_POLICY =
            "contracts-top-level-admission-v1";
    private static final String DEFAULT_ADMISSION_LABEL =
            "contracts10-sdk-authored-closure";
    private static final long INITIAL_GENERATION = 1L;

    private final DefaultCoordinationEngine engine;

    public Contracts10AuthoredClosureCompiler(
            DefaultCoordinationEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Compiles one complete immutable authored request. */
    public CompiledClosure compile(CompilationRequest request) {
        return compile(request, DocumentIdentityMode.EXPLICIT_LINEAGE);
    }

    /**
     * Compiles an explicit request whose lineages are exact authored BlueIds.
     *
     * <p>This additive mode never authors or rewrites {@code /documentId}.
     * Every supplied lineage must instead equal the exact resolved source
     * identity before initialization.</p>
     */
    public CompiledClosure compileContentIdentified(
            CompilationRequest request) {
        return compile(request, DocumentIdentityMode.CONTENT_DERIVED);
    }

    /**
     * Compiles resolver-prepared content-derived members without mutating an
     * authored identity field.
     *
     * <p>The static Process Embedded resolver is the sole caller. It verifies
     * each lineage against the untouched exact authored member before adding
     * the preliminary occurrence representations required by the existing
     * explicit compiler and finalizer.</p>
     */
    CompiledClosure compilePreverifiedContentIdentified(
            CompilationRequest request) {
        return compile(request, DocumentIdentityMode.PREVERIFIED_CONTENT);
    }

    private CompiledClosure compile(
            CompilationRequest request,
            DocumentIdentityMode identityMode) {
        CompilationRequest input = Objects.requireNonNull(request, "request");
        RequestIndex index = RequestIndex.from(input);

        EngineMetrics verificationMetrics = new EngineMetrics();
        WholeObjectStore verificationObjects =
                new WholeObjectStore(verificationMetrics);
        try (BlueRuntime verificationRuntime = BlueRuntime.create(
                verificationObjects,
                verificationMetrics,
                engine.applicationExactNodeProvider())) {
            LinkedHashMap<DocumentId, Node> resolved = resolveDocuments(
                    input.documents(), verificationRuntime,
                    verificationObjects,
                    Objects.requireNonNull(identityMode, "identityMode"));
            validateDeclarationsAndCoverage(
                    index, resolved, verificationRuntime,
                    verificationObjects);
            LinkedHashMap<DocumentId, Node> authored =
                    installAndVerifyPreliminaryReferences(
                            index, resolved, identityMode);
            return finalizeClosure(input, index, authored);
        }
    }

    private static LinkedHashMap<DocumentId, Node> resolveDocuments(
            List<AuthoredDocument> documents,
            BlueRuntime runtime,
            WholeObjectStore objects,
            DocumentIdentityMode identityMode) {
        LinkedHashMap<DocumentId, Node> result = new LinkedHashMap<>();
        for (AuthoredDocument document : documents) {
            ExactValue exact = runtime.exactSource(
                    document.authoredYaml(),
                    objects,
                    "contracts10-authored-compiler-source");
            Node body = exact.copyNode();
            switch (identityMode) {
                case EXPLICIT_LINEAGE -> requireOrWriteDocumentId(
                        document.documentId(), body);
                case CONTENT_DERIVED -> {
                    if (!document.documentId().value().equals(
                            exact.blueId())) {
                        throw new IllegalArgumentException(
                                "Managed DocumentId must equal the exact "
                                        + "authored pre-initialization BlueId: "
                                        + document.documentId() + " != "
                                        + exact.blueId());
                    }
                }
                case PREVERIFIED_CONTENT -> {
                    // The static resolver already authenticated the untouched
                    // authored member before adding preliminary references.
                }
            }
            result.put(document.documentId(), body);
        }
        return result;
    }

    private static void validateDeclarationsAndCoverage(
            RequestIndex index,
            Map<DocumentId, Node> resolved,
            BlueRuntime runtime,
            WholeObjectStore objects) {
        Map<DocumentId, List<ResolvedOccurrence>> bySource =
                occurrencesBySource(index.occurrences());
        for (Map.Entry<DocumentId, Node> entry : resolved.entrySet()) {
            DocumentId source = entry.getKey();
            Node declarationOnly = entry.getValue().clone();
            List<ResolvedOccurrence> declared = bySource.getOrDefault(
                    source, List.of());
            ArrayList<String> paths = new ArrayList<>(declared.stream()
                    .map(ResolvedOccurrence::path)
                    .toList());
            paths.sort(Contracts10AuthoredClosureCompiler
                    ::compareRemovalPaths);
            for (String path : paths) {
                removeAt(declarationOnly, path);
            }

            ExactValue declarationExact = objects.put(
                    declarationOnly,
                    "contracts10-authored-compiler-declarations");
            EffectiveFragmentationCatalog catalog =
                    runtime.effectiveFragmentationCatalog(
                            declarationExact.blueId());
            for (ResolvedOccurrence occurrence : declared) {
                requireOneEffectiveDeclaration(catalog, occurrence);
            }
            List<String> unbound = catalog.scopePlansByScope().values()
                    .stream()
                    .flatMap(plan -> plan.concreteChildPaths().stream())
                    .distinct()
                    .sorted()
                    .toList();
            if (!unbound.isEmpty()) {
                throw new IllegalArgumentException(
                        "Authored document " + source
                                + " contains Process Embedded occurrences "
                                + "without managed bindings: " + unbound);
            }
        }
    }

    private static void requireOneEffectiveDeclaration(
            EffectiveFragmentationCatalog catalog,
            ResolvedOccurrence occurrence) {
        ArrayList<String> matches = new ArrayList<>();
        for (EmbeddedScopePlanView plan
                : catalog.scopePlansByScope().values()) {
            for (String declaration : plan.explicitDeclarationPaths()) {
                String absolute = PointerUtils.resolvePointer(
                        plan.scopePath(), declaration);
                if (absolute.equals(occurrence.path())) {
                    matches.add("path " + absolute);
                }
            }
            for (String declaration : plan.collectionDeclarationPaths()) {
                String absolute = PointerUtils.resolvePointer(
                        plan.scopePath(), declaration);
                if (isDirectCollectionMember(
                        absolute, occurrence.path())) {
                    matches.add("collectionPath " + absolute);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed occurrence " + occurrence.sourceDocumentId()
                            + occurrence.path()
                            + " is not declared by the effective Process "
                            + "Embedded paths or collectionPaths catalog");
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "Managed occurrence " + occurrence.sourceDocumentId()
                            + occurrence.path()
                            + " is ambiguously declared by " + matches);
        }
    }

    private static LinkedHashMap<DocumentId, Node>
            installAndVerifyPreliminaryReferences(
                    RequestIndex index,
                    Map<DocumentId, Node> resolved,
                    DocumentIdentityMode identityMode) {
        LinkedHashMap<DocumentId, Node> referenceBodies = cloneBodies(
                resolved);
        for (ResolvedOccurrence occurrence : index.occurrences()) {
            NodePathEditor.put(
                    referenceBodies.get(occurrence.sourceDocumentId()),
                    occurrence.path(),
                    preliminaryReference(occurrence.targetDocumentId()));
        }

        LinkedHashMap<DocumentId, Node> authored = cloneBodies(resolved);
        for (ResolvedOccurrence occurrence : index.occurrences()) {
            Node current = NodePathEditor.getOrNull(
                    resolved.get(occurrence.sourceDocumentId()),
                    occurrence.path());
            Node replacement;
            if (current == null) {
                replacement = preliminaryReference(
                        occurrence.targetDocumentId());
            } else if (current.isReferenceOnly()) {
                requireExpectedPreliminaryIdentity(current, occurrence);
                replacement = preliminaryReference(
                        occurrence.targetDocumentId());
            } else {
                replacement = expectedMaterializedTarget(
                        referenceBodies, occurrence.targetDocumentId());
                requireExpectedMaterializedTarget(
                        current,
                        replacement,
                        occurrence,
                        identityMode != DocumentIdentityMode
                                .PREVERIFIED_CONTENT);
            }
            NodePathEditor.put(
                    authored.get(occurrence.sourceDocumentId()),
                    occurrence.path(), replacement);
        }
        return authored;
    }

    private CompiledClosure finalizeClosure(
            CompilationRequest request,
            RequestIndex index,
            LinkedHashMap<DocumentId, Node> authored) {
        ContractsClosureAdmissionAdapter adapter =
                engine.contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = adapter.environment();
        ExecutionPolicy policy = adapter.executionPolicy();

        List<blue.language.processor.closure.DocumentId> closureIds =
                request.documents().stream()
                        .map(AuthoredDocument::documentId)
                        .map(Contracts10AuthoredClosureCompiler::closureId)
                        .toList();
        List<ManagedOccurrenceBinding> bindings = bindings(
                index.occurrences(), authored, environment);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                closureIds, bindings);
        ComponentFinalizationResult finalization =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations(closureIds),
                                closureBodies(authored),
                                bindings));

        List<ManagedOccurrenceBinding> canonicalBindings =
                verifiedCanonicalBindings(finalization);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                representedBodies = representedBodies(finalization);
        Map<String, String> independentlyVerifiedMasters =
                verifyLanguageEvidence(finalization, representedBodies);
        List<ManagedDocumentSnapshot> snapshots = snapshots(
                finalization,
                representedBodies,
                request.publicRootDocumentIds());
        List<ComponentSnapshot> components = finalization.components()
                .stream()
                .map(FinalizedComponentEvidence::component)
                .toList();
        List<blue.language.processor.closure.DocumentId> roots =
                request.publicRootDocumentIds().stream()
                        .sorted()
                        .map(Contracts10AuthoredClosureCompiler::closureId)
                        .toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        INITIAL_GENERATION,
                        snapshots,
                        canonicalBindings,
                        components,
                        roots);
        ClosureInvocationInput invocation = ClosureEvidenceFactory
                .admitClosure(
                        snapshot,
                        ClosureEvidenceFactory.admissionCause(
                                AdmissionKind.TOP_LEVEL_ADMISSION,
                                request.admissionLabel(),
                                null,
                                null,
                                ADMISSION_POLICY),
                        null,
                        policy,
                        environment);
        return new CompiledClosure(
                invocation,
                request.activationInputs(),
                authored,
                representedBodies,
                finalization,
                canonicalBindings,
                independentlyVerifiedMasters);
    }

    private static List<ManagedOccurrenceBinding> bindings(
            List<ResolvedOccurrence> occurrences,
            Map<DocumentId, Node> authored,
            ClosureEnvironment environment) {
        ArrayList<ManagedOccurrenceBinding> result = new ArrayList<>();
        for (ResolvedOccurrence occurrence : occurrences) {
            Node exactReference = NodePathEditor.getOrNull(
                    authored.get(occurrence.sourceDocumentId()),
                    occurrence.path());
            String expected = preliminaryBlueId(
                    occurrence.targetDocumentId());
            if (exactReference == null
                    || !expected.equals(exactReference.getBlueId())) {
                throw new IllegalStateException(
                        "Managed occurrence no longer carries the verified "
                                + "target identity at "
                                + occurrence.sourceDocumentId()
                                + occurrence.path());
            }
            ManagedOccurrenceBinding derived =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            closureId(occurrence.sourceDocumentId()),
                            ScopeAddress.embedded(
                                    occurrence.path(), INITIAL_GENERATION),
                            closureId(occurrence.targetDocumentId()),
                            expected,
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
        return List.copyOf(result);
    }

    private static List<ManagedOccurrenceBinding> verifiedCanonicalBindings(
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

    private static LinkedHashMap<blue.language.processor.closure.DocumentId,
            Node> representedBodies(ComponentFinalizationResult finalization) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                result = new LinkedHashMap<>();
        finalization.documents().forEach((documentId, evidence) ->
                result.put(documentId, evidence.document()));
        return result;
    }

    private static Map<String, String> verifyLanguageEvidence(
            ComponentFinalizationResult finalization,
            Map<blue.language.processor.closure.DocumentId, Node> bodies) {
        CompilerProofProvider evidence = new CompilerProofProvider();
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
                                + "verify the finalized cyclic component");
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

    private static List<ManagedDocumentSnapshot> snapshots(
            ComponentFinalizationResult finalization,
            Map<blue.language.processor.closure.DocumentId, Node> bodies,
            Set<DocumentId> publicRoots) {
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

    private static LinkedHashMap<blue.language.processor.closure.DocumentId,
            Long> generations(
                    Collection<blue.language.processor.closure.DocumentId>
                            documentIds) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                result = new LinkedHashMap<>();
        for (blue.language.processor.closure.DocumentId documentId
                : documentIds) {
            result.put(documentId, INITIAL_GENERATION);
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

    private static Map<DocumentId, List<ResolvedOccurrence>>
            occurrencesBySource(List<ResolvedOccurrence> occurrences) {
        LinkedHashMap<DocumentId, List<ResolvedOccurrence>> result =
                new LinkedHashMap<>();
        for (ResolvedOccurrence occurrence : occurrences) {
            result.computeIfAbsent(
                    occurrence.sourceDocumentId(),
                    ignored -> new ArrayList<>()).add(occurrence);
        }
        return result;
    }

    private static void requireExpectedPreliminaryIdentity(
            Node current,
            ResolvedOccurrence occurrence) {
        String expected = preliminaryBlueId(
                occurrence.targetDocumentId());
        if (!expected.equals(current.getBlueId())) {
            throw new IllegalArgumentException(
                    "Managed occurrence " + occurrence.sourceDocumentId()
                            + occurrence.path()
                            + " contains conflicting reference "
                            + current.getBlueId() + "; expected " + expected
                            + " for target "
                            + occurrence.targetDocumentId());
        }
    }

    private static void requireExpectedMaterializedTarget(
            Node current,
            Node expected,
            ResolvedOccurrence occurrence,
            boolean requirePreliminaryClaim) {
        String currentUnclaimed = unclaimedBlueId(current);
        String expectedUnclaimed = unclaimedBlueId(expected);
        if ((requirePreliminaryClaim
                && !preliminaryBlueId(occurrence.targetDocumentId()).equals(
                        current.getBlueId()))
                || !currentUnclaimed.equals(expectedUnclaimed)) {
            throw new IllegalArgumentException(
                    "Managed occurrence " + occurrence.sourceDocumentId()
                            + occurrence.path()
                            + " contains materialized state for the wrong "
                            + "target; expected exact authored member "
                            + occurrence.targetDocumentId()
                            + " (actual " + currentUnclaimed
                            + ", expected " + expectedUnclaimed + ")");
        }
    }

    private static String unclaimedBlueId(Node value) {
        Node unclaimed = Objects.requireNonNull(value, "value").clone();
        removeMaterializedClaims(unclaimed);
        return DirectBlueIdCalculator.calculateBlueId(unclaimed);
    }

    /** Removes representation-only materialized claims but keeps pure refs. */
    private static void removeMaterializedClaims(Node node) {
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            node.blueId(null);
        }
        removeMaterializedClaim(node.getType());
        removeMaterializedClaim(node.getItemType());
        removeMaterializedClaim(node.getKeyType());
        removeMaterializedClaim(node.getValueType());
        removeMaterializedClaim(node.getContracts());
        removeMaterializedClaim(node.getBlue());
        if (node.getItems() != null) {
            node.getItems().forEach(
                    Contracts10AuthoredClosureCompiler
                            ::removeMaterializedClaims);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(
                    Contracts10AuthoredClosureCompiler
                            ::removeMaterializedClaims);
        }
    }

    private static void removeMaterializedClaim(Node node) {
        if (node != null) {
            removeMaterializedClaims(node);
        }
    }

    private static Node expectedMaterializedTarget(
            Map<DocumentId, Node> referenceBodies,
            DocumentId target) {
        Node body = referenceBodies.get(target);
        if (body == null) {
            throw new IllegalStateException(
                    "Missing resolved target body " + target);
        }
        return body.clone().blueId(preliminaryBlueId(target));
    }

    private static Node preliminaryReference(DocumentId target) {
        return new Node().blueId(preliminaryBlueId(target));
    }

    private static String preliminaryBlueId(DocumentId target) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value("scenario-target:" + target.value()));
    }

    private static boolean isDirectCollectionMember(
            String collectionPath,
            String candidatePath) {
        List<String> collection = JsonPointer.split(collectionPath);
        List<String> candidate = JsonPointer.split(candidatePath);
        return candidate.size() == collection.size() + 1
                && candidate.subList(0, collection.size())
                        .equals(collection);
    }

    private static boolean overlaps(String left, String right) {
        return left.equals(right)
                || left.startsWith(right + "/")
                || right.startsWith(left + "/");
    }

    private static int compareRemovalPaths(String left, String right) {
        List<String> leftSegments = JsonPointer.split(left);
        List<String> rightSegments = JsonPointer.split(right);
        int depth = Integer.compare(
                rightSegments.size(), leftSegments.size());
        if (depth != 0) {
            return depth;
        }
        int leaf = leftSegments.size() - 1;
        if (leftSegments.subList(0, leaf).equals(
                        rightSegments.subList(0, leaf))
                && JsonPointer.isArrayIndexSegment(leftSegments.get(leaf))
                && JsonPointer.isArrayIndexSegment(
                        rightSegments.get(leaf))) {
            return Integer.compare(
                    Integer.parseInt(rightSegments.get(leaf)),
                    Integer.parseInt(leftSegments.get(leaf)));
        }
        return right.compareTo(left);
    }

    private static void removeAt(Node root, String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "A managed occurrence cannot replace the document Root");
        }
        Node parent = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            parent = NodePathEditor.getOrNull(
                    parent, "/" + escapePointerToken(
                            segments.get(index)));
            if (parent == null) {
                return;
            }
        }
        String leaf = segments.get(segments.size() - 1);
        if ("type".equals(leaf)) {
            parent.type((Node) null);
        } else if ("itemType".equals(leaf)) {
            parent.itemType((Node) null);
        } else if ("keyType".equals(leaf)) {
            parent.keyType((Node) null);
        } else if ("valueType".equals(leaf)) {
            parent.valueType((Node) null);
        } else if ("blue".equals(leaf)) {
            parent.blue(null);
        } else if ("contracts".equals(leaf)) {
            parent.contracts(null);
        } else if (JsonPointer.isArrayIndexSegment(leaf)
                && parent.getItems() != null) {
            int item = Integer.parseInt(leaf);
            if (item < parent.getItems().size()) {
                parent.getItems().remove(item);
            }
        } else if (parent.getProperties() != null) {
            parent.getProperties().remove(leaf);
        }
    }

    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
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
                    "Authored documentId does not match managed lineage "
                            + documentId);
        }
    }

    private static LinkedHashMap<DocumentId, Node> cloneBodies(
            Map<DocumentId, Node> source) {
        LinkedHashMap<DocumentId, Node> result = new LinkedHashMap<>();
        source.forEach((documentId, document) ->
                result.put(documentId, document.clone()));
        return result;
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

    /** One managed document authored as source YAML. */
    public record AuthoredDocument(
            DocumentId documentId,
            String authoredYaml) {
        public AuthoredDocument {
            documentId = Objects.requireNonNull(documentId, "documentId");
            authoredYaml = requireText(authoredYaml, "authoredYaml");
        }
    }

    /** One source-path-to-target managed lineage declaration. */
    public record OccurrenceBinding(
            String sourceAlias,
            String path,
            String targetAlias) {
        public OccurrenceBinding {
            sourceAlias = requireText(sourceAlias, "sourceAlias");
            targetAlias = requireText(targetAlias, "targetAlias");
            path = JsonPointer.canonicalize(
                    Objects.requireNonNull(path, "path"));
            if (path.isEmpty()) {
                throw new IllegalArgumentException(
                        "A managed occurrence path cannot be the document Root");
            }
        }
    }

    /** Temporal publication inputs applied after compilation. */
    public record ActivationInputs(
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier) {
        public ActivationInputs {
            policy = Objects.requireNonNull(policy, "policy");
            if (policy == CoordinationEngine.AdmissionPolicy.FROM_FRONTIER
                    && verifiedFrontier == null) {
                throw new IllegalArgumentException(
                        "FROM_FRONTIER requires verified frontier evidence");
            }
            if (policy != CoordinationEngine.AdmissionPolicy.FROM_FRONTIER
                    && verifiedFrontier != null) {
                throw new IllegalArgumentException(
                        policy + " does not accept frontier evidence");
            }
        }

        public static ActivationInputs fromNow() {
            return new ActivationInputs(
                    CoordinationEngine.AdmissionPolicy.FROM_NOW, null);
        }

        public static ActivationInputs fullHistory() {
            return new ActivationInputs(
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY, null);
        }

        public static ActivationInputs fromFrontier(
                ExternalOrderKey frontier) {
            return new ActivationInputs(
                    CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                    Objects.requireNonNull(frontier, "frontier"));
        }
    }

    /** Complete high-level input. It contains no caller-authored graph. */
    public record CompilationRequest(
            List<AuthoredDocument> documents,
            Map<String, DocumentId> aliases,
            List<OccurrenceBinding> occurrenceBindings,
            Set<DocumentId> publicRootDocumentIds,
            ActivationInputs activationInputs,
            String admissionLabel) {
        public CompilationRequest {
            documents = List.copyOf(Objects.requireNonNull(
                    documents, "documents"));
            aliases = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(aliases, "aliases")));
            occurrenceBindings = List.copyOf(Objects.requireNonNull(
                    occurrenceBindings, "occurrenceBindings"));
            publicRootDocumentIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            publicRootDocumentIds,
                            "publicRootDocumentIds")));
            activationInputs = Objects.requireNonNull(
                    activationInputs, "activationInputs");
            admissionLabel = requireText(
                    admissionLabel, "admissionLabel");
        }

        public CompilationRequest(
                List<AuthoredDocument> documents,
                Map<String, DocumentId> aliases,
                List<OccurrenceBinding> occurrenceBindings,
                Set<DocumentId> publicRootDocumentIds,
                ActivationInputs activationInputs) {
            this(
                    documents,
                    aliases,
                    occurrenceBindings,
                    publicRootDocumentIds,
                    activationInputs,
                    DEFAULT_ADMISSION_LABEL);
        }
    }

    /** Immutable result retained by the future SDK runtime bridge. */
    public static final class CompiledClosure {
        private final ClosureInvocationInput invocation;
        private final ActivationInputs activationInputs;
        private final Map<DocumentId, Node> authoredDocuments;
        private final Map<DocumentId, Node> finalizedDocuments;
        private final Map<DocumentId, String> blueIds;
        private final List<ManagedOccurrenceBinding> bindings;
        private final List<ComponentSnapshot> components;
        private final Map<DocumentId, List<DocumentId>> adjacency;
        private final Map<DocumentId, String> independentlyVerifiedMasters;

        private CompiledClosure(
                ClosureInvocationInput invocation,
                ActivationInputs activationInputs,
                Map<DocumentId, Node> authored,
                Map<blue.language.processor.closure.DocumentId, Node> bodies,
                ComponentFinalizationResult finalization,
                List<ManagedOccurrenceBinding> bindings,
                Map<String, String> verifiedMasters) {
            this.invocation = Objects.requireNonNull(
                    invocation, "invocation");
            this.activationInputs = Objects.requireNonNull(
                    activationInputs, "activationInputs");
            this.authoredDocuments = Collections.unmodifiableMap(
                    cloneBodies(authored));
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
            this.finalizedDocuments = Collections.unmodifiableMap(
                    retainedDocuments);
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
                                    .map(Contracts10AuthoredClosureCompiler
                                            ::apiId)
                                    .toList()));
            this.adjacency = Collections.unmodifiableMap(retainedAdjacency);
            LinkedHashMap<DocumentId, String> masters =
                    new LinkedHashMap<>();
            for (ComponentSnapshot component : components) {
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
        }

        public ClosureInvocationInput invocation() {
            return invocation;
        }

        public ActivationInputs activationInputs() {
            return activationInputs;
        }

        public Node authoredDocument(DocumentId documentId) {
            return requireDocument(authoredDocuments, documentId).clone();
        }

        public Node finalizedDocument(DocumentId documentId) {
            return requireDocument(finalizedDocuments, documentId).clone();
        }

        public Map<DocumentId, String> blueIds() {
            return blueIds;
        }

        public String blueId(DocumentId documentId) {
            String blueId = blueIds.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (blueId == null) {
                throw new IllegalArgumentException(
                        "Unknown compiled document " + documentId);
            }
            return blueId;
        }

        public List<ManagedOccurrenceBinding> bindings() {
            return bindings;
        }

        public List<ComponentSnapshot> components() {
            return components;
        }

        public List<List<DocumentId>> componentMembers() {
            return components.stream()
                    .map(ComponentSnapshot::orderedMemberDocumentIds)
                    .map(members -> members.stream()
                            .map(Contracts10AuthoredClosureCompiler::apiId)
                            .toList())
                    .toList();
        }

        public Map<DocumentId, List<DocumentId>> adjacency() {
            return adjacency;
        }

        public String independentlyVerifiedMaster(DocumentId documentId) {
            return independentlyVerifiedMasters.get(Objects.requireNonNull(
                    documentId, "documentId"));
        }

        private static Node requireDocument(
                Map<DocumentId, Node> documents,
                DocumentId documentId) {
            Node document = documents.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (document == null) {
                throw new IllegalArgumentException(
                        "Unknown compiled document " + documentId);
            }
            return document;
        }
    }

    private record ResolvedOccurrence(
            DocumentId sourceDocumentId,
            String path,
            DocumentId targetDocumentId) {
    }

    private enum DocumentIdentityMode {
        EXPLICIT_LINEAGE,
        CONTENT_DERIVED,
        PREVERIFIED_CONTENT
    }

    private record RequestIndex(List<ResolvedOccurrence> occurrences) {
        private static RequestIndex from(CompilationRequest request) {
            if (request.documents().isEmpty()) {
                throw new IllegalArgumentException(
                        "A closure requires at least one authored document");
            }
            LinkedHashSet<DocumentId> documentIds = new LinkedHashSet<>();
            for (AuthoredDocument document : request.documents()) {
                Objects.requireNonNull(document, "document");
                if (!documentIds.add(document.documentId())) {
                    throw new IllegalArgumentException(
                            "Duplicate managed document identity "
                                    + document.documentId());
                }
            }
            if (request.aliases().isEmpty()) {
                throw new IllegalArgumentException(
                        "A closure requires document aliases");
            }
            LinkedHashSet<DocumentId> aliased = new LinkedHashSet<>();
            for (Map.Entry<String, DocumentId> alias
                    : request.aliases().entrySet()) {
                requireText(alias.getKey(), "alias");
                DocumentId documentId = Objects.requireNonNull(
                        alias.getValue(), "alias documentId");
                if (!documentIds.contains(documentId)) {
                    throw new IllegalArgumentException(
                            "Alias " + alias.getKey()
                                    + " names unknown document " + documentId);
                }
                if (!aliased.add(documentId)) {
                    throw new IllegalArgumentException(
                            "Managed document has more than one alias: "
                                    + documentId);
                }
            }
            if (!aliased.equals(documentIds)) {
                throw new IllegalArgumentException(
                        "Aliases must name every authored document exactly "
                                + "once");
            }
            if (request.publicRootDocumentIds().isEmpty()) {
                throw new IllegalArgumentException(
                        "A closure requires at least one public Root");
            }
            for (DocumentId root : request.publicRootDocumentIds()) {
                if (!documentIds.contains(Objects.requireNonNull(
                        root, "publicRootDocumentId"))) {
                    throw new IllegalArgumentException(
                            "Public Root is not an authored document: "
                                    + root);
                }
            }

            ArrayList<ResolvedOccurrence> occurrences = new ArrayList<>();
            LinkedHashMap<DocumentId, List<String>> pathsBySource =
                    new LinkedHashMap<>();
            for (OccurrenceBinding binding
                    : request.occurrenceBindings()) {
                Objects.requireNonNull(binding, "occurrenceBinding");
                DocumentId source = request.aliases().get(
                        binding.sourceAlias());
                DocumentId target = request.aliases().get(
                        binding.targetAlias());
                if (source == null) {
                    throw new IllegalArgumentException(
                            "Unknown occurrence source alias "
                                    + binding.sourceAlias());
                }
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Unknown occurrence target alias "
                                    + binding.targetAlias());
                }
                List<String> paths = pathsBySource.computeIfAbsent(
                        source, ignored -> new ArrayList<>());
                for (String existing : paths) {
                    if (overlaps(existing, binding.path())) {
                        throw new IllegalArgumentException(
                                "Managed occurrence paths overlap in "
                                        + source + ": " + existing + " and "
                                        + binding.path());
                    }
                }
                paths.add(binding.path());
                occurrences.add(new ResolvedOccurrence(
                        source, binding.path(), target));
            }
            return new RequestIndex(List.copyOf(occurrences));
        }
    }

    private static final class CompilerProofProvider
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

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
