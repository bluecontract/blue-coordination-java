package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.SessionStatus;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionState;
import blue.language.provider.NodeProvider;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Executes and atomically publishes Contracts closure admission. */
final class ContractsClosureAdmissionAdapter implements AutoCloseable {
    private static final String FULL_LIFECYCLE_IDENTITY_DOMAIN =
            "coordination-contracts-closure-admission-v1";
    private static final String BOUNDED_COMPATIBILITY_IDENTITY_DOMAIN =
            "coordination-contracts-closure-admission-bounded-compatibility-v1";

    private enum AdmissionLane {
        FULL_LIFECYCLE,
        BOUNDED_COMPATIBILITY
    }

    enum PublicationFailurePoint {
        AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH
    }

    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final EmbeddedOnlyLayoutBuilder layoutBuilder;
    private final InMemoryDocumentStore documents;
    private final OperationRouteIndex routes;
    private final ContractsClosureProfile profile;
    private final ContractsActiveSourceTimelineIndex activeSourceTimelines;
    private final ClosureEnvironment environment;
    private final ContractsClosureExecutionMetricsObserver executionObserver;
    private final BlueClosureContracts contracts;
    private Consumer<MultiDocumentPublicationTransaction.FailurePoint>
            failureInjector = ignored -> { };
    private Consumer<PublicationFailurePoint> publicationFailureInjector =
            ignored -> { };
    private boolean closed;

    ContractsClosureAdmissionAdapter(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EmbeddedOnlyLayoutBuilder layoutBuilder,
            InMemoryDocumentStore documents,
            OperationRouteIndex routes,
            ContractsClosureProfile profile) {
        this(
                runtime,
                objects,
                layoutBuilder,
                documents,
                routes,
                profile,
                new ContractsActiveSourceTimelineIndex(
                        profile.publicRoots()));
    }

    ContractsClosureAdmissionAdapter(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EmbeddedOnlyLayoutBuilder layoutBuilder,
            InMemoryDocumentStore documents,
            OperationRouteIndex routes,
            ContractsClosureProfile profile,
            ContractsActiveSourceTimelineIndex activeSourceTimelines) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.layoutBuilder = Objects.requireNonNull(
                layoutBuilder, "layoutBuilder");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.activeSourceTimelines = Objects.requireNonNull(
                activeSourceTimelines, "activeSourceTimelines");
        this.environment = profile.environment(runtime.documentProcessor());
        this.executionObserver =
                new ContractsClosureExecutionMetricsObserver(
                        runtime.metrics());
        this.contracts = new BlueClosureContracts(
                runtime.documentProcessor(), executionObserver);
    }

    synchronized ContractsClosureAdmissionReceipt admitAndPublish(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey admissionFrontier) {
        return admitAndPublish(
                input, policy, admissionFrontier,
                AdmissionLane.FULL_LIFECYCLE,
                runtime.nodeProvider(),
                null);
    }

    synchronized ContractsClosureAdmissionReceipt admitAndPublish(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey admissionFrontier,
            NodeProvider exactNodes) {
        return admitAndPublish(
                input,
                policy,
                admissionFrontier,
                AdmissionLane.FULL_LIFECYCLE,
                exactNodes,
                null);
    }

    synchronized ContractsClosureAdmissionReceipt admitAndPublish(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey admissionFrontier,
            NodeProvider exactNodes,
            ContractsManagedEpochSelectionPlan selectionPlan) {
        return admitAndPublish(
                input,
                policy,
                admissionFrontier,
                AdmissionLane.FULL_LIFECYCLE,
                exactNodes,
                Objects.requireNonNull(selectionPlan, "selectionPlan"));
    }

    synchronized ContractsClosureAdmissionReceipt
            admitAndPublishBoundedCompatibility(
                    ClosureInvocationInput input,
                    CoordinationEngine.AdmissionPolicy policy,
                    ExternalOrderKey admissionFrontier) {
        return admitAndPublish(
                input, policy, admissionFrontier,
                AdmissionLane.BOUNDED_COMPATIBILITY,
                runtime.nodeProvider(),
                null);
    }

    private ContractsClosureAdmissionReceipt admitAndPublish(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey admissionFrontier,
            AdmissionLane lane,
            NodeProvider exactNodes,
            ContractsManagedEpochSelectionPlan selectionPlan) {
        ensureOpen();
        ClosureInvocationInput admission = Objects.requireNonNull(
                input, "input");
        CoordinationEngine.AdmissionPolicy temporalPolicy =
                Objects.requireNonNull(policy, "policy");
        ExternalOrderKey frontier = Objects.requireNonNull(
                admissionFrontier, "admissionFrontier");
        AdmissionLane selectedLane = Objects.requireNonNull(lane, "lane");
        NodeProvider selectedExactNodes = Objects.requireNonNull(
                exactNodes, "exactNodes");
        requireExactAdmissionInput(admission);
        if (selectionPlan != null) {
            requireStaticAdmissionSelectionPlan(admission, selectionPlan);
        }
        List<DocumentId> identityMembers = coordinationIds(
                admission.snapshot().managedDocuments());
        String publicationIdentity = switch (selectedLane) {
            case FULL_LIFECYCLE -> publicationIdentity(
                    admission, temporalPolicy, frontier, selectionPlan);
            case BOUNDED_COMPATIBILITY ->
                    boundedCompatibilityPublicationIdentity(
                            admission, temporalPolicy, frontier);
        };

        Optional<ContractsClosureAdmissionReceipt> prior =
                documents.admissionReceipt(publicationIdentity);
        if (prior.isPresent()) {
            ContractsClosureAdmissionReceipt retained = prior.orElseThrow();
            requireExactlyPresent(retained.documentIds());
            reconcileRoutes(retained.documentIds());
            return new ContractsClosureAdmissionReceipt(
                    retained.attempt(),
                    retained.publicationIdentity(),
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .ALREADY_PUBLISHED,
                    retained.documentIds());
        }

        AdmissionInvocation initial = AdmissionInvocation.initial(
                admission, identityMembers);
        AutomaticOccurrenceResolutionCoordinator.RunResult<
                AdmissionInvocation,
                ContractsClosureAdmissionReceipt> automatic =
                automaticCoordinator(selectedLane, selectedExactNodes).run(
                        initial,
                        requireAutomaticExpansionLimit(),
                        ignored -> documents.admissionReceipt(
                                publicationIdentity),
                        this::requireAutomaticRetryStillCurrent,
                        selectionPlan);
        if (automatic.replayed()) {
            ContractsClosureAdmissionReceipt retained = automatic.replay();
            requireExactlyPresent(retained.documentIds());
            reconcileRoutes(retained.documentIds());
            return new ContractsClosureAdmissionReceipt(
                    retained.attempt(),
                    retained.publicationIdentity(),
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .ALREADY_PUBLISHED,
                    retained.documentIds());
        }
        AdmissionInvocation completed = automatic.invocation();
        ClosureAttemptResult attempt = automatic.attempt();
        if (!attempt.isComplete()
                || !attempt.processResult().commits()) {
            return new ContractsClosureAdmissionReceipt(
                    attempt,
                    publicationIdentity,
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    List.of());
        }
        ClosureInvocationInput completedAdmission = completed.input();
        List<DocumentId> members = coordinationIds(
                completedAdmission.snapshot().managedDocuments());
        ClosureProcessResult result = attempt.processResult();
        requireResultBelongsToAdmission(
                completedAdmission, result, members);
        InMemoryDocumentStore.ClosureSnapshot before =
                documents.admissionSnapshot(completed.existingMembers());
        publish(
                completed,
                attempt,
                result,
                temporalPolicy,
                frontier,
                publicationIdentity,
                members,
                before);
        return new ContractsClosureAdmissionReceipt(
                attempt,
                publicationIdentity,
                ContractsClosureAdmissionReceipt.PublicationOutcome.PUBLISHED,
                members);
    }

    /** Exact implementation evidence from the latest completed admission. */
    synchronized Optional<ClosureImplementationEvidence>
            lastExecutionEvidence() {
        ensureOpen();
        return executionObserver.lastEvidence();
    }

    synchronized ClosureEnvironment environment() {
        ensureOpen();
        return environment;
    }

    synchronized ExecutionPolicy executionPolicy() {
        ensureOpen();
        return profile.executionPolicy();
    }

    private void requireStaticAdmissionSelectionPlan(
            ClosureInvocationInput input,
            ContractsManagedEpochSelectionPlan plan) {
        ContractsManagedEpochSelectionPlan selected = Objects.requireNonNull(
                plan, "plan");
        if (selected.targetEpoch() != -1L) {
            throw new IllegalArgumentException(
                    "MANAGED_EPOCH_SELECTOR_TARGET_MISMATCH: static "
                            + "admission target position must be authored "
                            + "initial (-1)");
        }
        ManagedDocumentSnapshot target = null;
        for (ManagedDocumentSnapshot document
                : Objects.requireNonNull(input, "input")
                        .snapshot().managedDocuments()) {
            if (!document.documentId().value().equals(
                    selected.targetDocumentId().value())) {
                continue;
            }
            if (target != null) {
                throw new IllegalArgumentException(
                        "MANAGED_EPOCH_SELECTOR_TARGET_MISMATCH: static "
                                + "admission repeats target "
                                + selected.targetDocumentId());
            }
            target = document;
        }
        if (target == null
                || target.initialized()
                || !target.blueId().equals(selected.targetBlueId())) {
            throw new IllegalArgumentException(
                    "MANAGED_EPOCH_SELECTOR_TARGET_MISMATCH: static "
                            + "admission does not contain the exact authored "
                            + "Root " + selected.targetDocumentId());
        }
        ManagedLineageIndex lineages = documents.lineageIndex();
        for (ContractsManagedEpochSelectionPlan.Selection selection
                : selected.selections()) {
            ManagedLineageIndex.Lineage lineage = lineages.byDocumentId(
                    selection.sourceDocumentId());
            if (lineage == null) {
                throw new IllegalArgumentException(
                        "MANAGED_EPOCH_SELECTOR_LINEAGE_MISMATCH: absent "
                                + selection.sourceDocumentId());
            }
            String exactBlueId = selectedEpochBlueId(
                    lineage, selection.sourceEpoch());
            if (exactBlueId == null
                    || !exactBlueId.equals(
                            selection.expectedSourceBlueId())) {
                throw new IllegalArgumentException(
                        "MANAGED_EPOCH_SELECTOR_STATE_MISMATCH: "
                                + selection.sourceDocumentId()
                                + " epoch " + selection.sourceEpoch()
                                + " does not equal "
                                + selection.expectedSourceBlueId());
            }
        }
    }

    private static String selectedEpochBlueId(
            ManagedLineageIndex.Lineage lineage,
            long sourceEpoch) {
        if (sourceEpoch == -1L) {
            return lineage.authoredInitialBlueId();
        }
        for (ManagedLineageIndex.RetainedState state
                : lineage.retainedStates()) {
            if (state.epoch() == sourceEpoch) {
                return state.blueId();
            }
        }
        return null;
    }

    synchronized void onFailurePoint(
            Consumer<MultiDocumentPublicationTransaction.FailurePoint>
                    injector) {
        ensureOpen();
        failureInjector = Objects.requireNonNull(injector, "injector");
    }

    synchronized void onPublicationFailurePoint(
            Consumer<PublicationFailurePoint> injector) {
        ensureOpen();
        publicationFailureInjector = Objects.requireNonNull(
                injector, "injector");
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            contracts.close();
        }
    }

    private AutomaticOccurrenceResolutionCoordinator<AdmissionInvocation>
            automaticCoordinator(
                    AdmissionLane lane,
                    NodeProvider exactNodes) {
        ManagedOccurrenceResolver resolver = new ManagedOccurrenceResolver(
                Objects.requireNonNull(exactNodes, "exactNodes"),
                runtime.metrics(), new ManagedRepresentationHistory(documents));
        return new AutomaticOccurrenceResolutionCoordinator<>(
                resolver,
                runtime.metrics(),
                AdmissionInvocation::input,
                invocation -> {
                    executionObserver.beginAttempt(invocation.input()
                            .snapshot().managedDocuments().stream()
                            .map(document -> document.documentId().value())
                            .toList());
                    try {
                        return switch (lane) {
                            case FULL_LIFECYCLE ->
                                    contracts.admitClosureWithLifecycleQueue(
                                            invocation.input());
                            case BOUNDED_COMPATIBILITY ->
                                    contracts.admitClosure(invocation.input());
                        };
                    } catch (CoordinationException failure) {
                        throw enrichProviderFailure(
                                invocation.input(), failure);
                    }
                },
                new AutomaticOccurrenceResolutionCoordinator
                        .ExpansionBuilder<AdmissionInvocation>() {
                    @Override
                    public InMemoryDocumentStore.OccurrenceResolutionSnapshot
                            captureStoreState() {
                        return documents.occurrenceResolutionSnapshot();
                    }

                    @Override
                    public AdmissionInvocation expand(
                            AdmissionInvocation current,
                            ManagedOccurrenceResolver.Resolution resolution,
                            InMemoryDocumentStore.OccurrenceResolutionSnapshot
                                    storeState) {
                        return augmentWithAutomaticResolution(
                                current, resolution, storeState);
                    }
                });
    }

    private static CoordinationException enrichProviderFailure(
            ClosureInvocationInput input,
            CoordinationException failure) {
        if (failure.code() != CoordinationErrorCode
                .INVALID_DOCUMENT_IDENTITY
                || failure.details().containsKey("sourceDocumentId")) {
            return failure;
        }
        String blueId = failure.details().get("blueId");
        if (blueId == null) {
            return failure;
        }
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            String path = referencePath(document.document(), blueId, "");
            if (path == null) {
                continue;
            }
            LinkedHashMap<String, String> details = new LinkedHashMap<>(
                    failure.details());
            details.put("sourceDocumentId", document.documentId().value());
            details.put("sourcePath", path);
            return new CoordinationException(
                    failure.code(), failure.getMessage(), failure, details);
        }
        return failure;
    }

    private static String referencePath(
            Node node,
            String blueId,
            String path) {
        if (node.isReferenceOnly() && blueId.equals(node.getBlueId())) {
            return path.isEmpty() ? "/" : path;
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> property
                    : node.getProperties().entrySet()) {
                String nested = referencePath(
                        property.getValue(),
                        blueId,
                        path + "/" + pointerSegment(property.getKey()));
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                String nested = referencePath(
                        node.getItems().get(index),
                        blueId,
                        path + "/" + index);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String pointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private long requireAutomaticExpansionLimit() {
        Long limit = environment.portableLimitPolicy().limits().get(
                "closureExpansionsPerInvocation");
        if (limit == null || limit.longValue() <= 0L) {
            throw new IllegalStateException(
                    "Portable limit policy has no positive "
                            + "closureExpansionsPerInvocation limit");
        }
        return MultiDocumentPublicationTransaction.requireSafeInteger(
                limit.longValue(), "closureExpansionsPerInvocation");
    }

    private AdmissionInvocation augmentWithAutomaticResolution(
            AdmissionInvocation current,
            ManagedOccurrenceResolver.Resolution resolution,
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState) {
        ManagedOccurrenceResolver.Resolution selected = Objects.requireNonNull(
                resolution, "resolution");
        InMemoryDocumentStore.OccurrenceResolutionSnapshot indexed =
                Objects.requireNonNull(storeState, "storeState");
        if (!selected.complete()) {
            throw new IllegalArgumentException(
                    "Only complete occurrence evidence may expand admission");
        }
        for (ManagedOccurrenceResolver.ResolvedExactNode exact
                : selected.resolvedExactNodes()) {
            ExactValue retained = objects.putVerifiedProviderEvidence(
                    exact.exactValue(),
                    exact.providerBody(),
                    exact.cyclicProof(),
                    "automatic-admission-retry-resource");
            if (!retained.sameExactValue(exact.exactValue())) {
                throw new IllegalStateException(
                        "Admission retry cache changed verified exact content");
            }
        }

        Set<DocumentId> existingMembers = connectedExistingMembers(
                current.existingMembers(),
                selected.existingTargets(),
                indexed.occurrenceInventory());
        InMemoryDocumentStore.ClosureSnapshot durable =
                documents.admissionSnapshot(existingMembers);
        requireIndexGenerations(indexed, durable);

        TreeMap<DocumentId, CapturedAdmissionDocument> captured =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        captured.putAll(current.existingDocuments());
        for (DocumentId documentId : existingMembers) {
            InMemoryDocumentStore.DocumentHead head =
                    durable.requireHead(documentId);
            CapturedAdmissionDocument prior = captured.get(documentId);
            if (prior != null) {
                if (!prior.head().equals(head)
                        || prior.graphGeneration()
                                != durable.graphGenerations().require(
                                        documentId)) {
                    throw stale("Document head changed during automatic "
                            + "admission expansion " + documentId);
                }
                continue;
            }
            captured.put(documentId, captureAdmissionDocument(
                    documentId,
                    head,
                    durable.graphGenerations().require(documentId)));
        }

        TreeMap<DocumentId, ExactValue> newDocuments = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        newDocuments.putAll(current.newDocuments());
        selected.newDrafts().forEach((documentId, draft) -> {
            if (existingMembers.contains(documentId)) {
                throw stale("Resolved authored admission draft became "
                        + "durable " + documentId);
            }
            ExactValue prior = newDocuments.putIfAbsent(
                    documentId, draft.initial());
            if (prior != null && !prior.sameExactValue(draft.initial())) {
                throw new IllegalStateException(
                        "Admission draft evidence disagrees for "
                                + documentId);
            }
        });
        existingMembers.forEach(newDocuments::remove);
        long maximumDocuments = requireManagedDocumentLimit();
        if (Math.addExact(existingMembers.size(), newDocuments.size())
                > maximumDocuments) {
            throw new AdmissionProjectionUnavailableException(
                    "Automatic admission expansion exceeds "
                            + "managedDocumentsPerClosure");
        }

        LinkedHashMap<String, ManagedOccurrenceBinding> rows =
                new LinkedHashMap<>();
        for (DocumentId source : existingMembers) {
            for (ManagedOccurrenceBinding row
                    : indexed.occurrenceInventory().rowsFrom(source)) {
                DocumentId target = coordinationId(row.targetDocumentId());
                if (!existingMembers.contains(target)) {
                    throw stale("Forward admission closure omitted target "
                            + target);
                }
                mergeAutomaticRow(rows, row);
            }
        }
        current.input().snapshot().occurrences().forEach(row ->
                mergeAutomaticRow(rows, row));

        LinkedHashSet<String> prospectiveIdentities = new LinkedHashSet<>();
        for (ManagedOccurrenceResolver.ResolvedOccurrence occurrence
                : selected.resolvedOccurrences()) {
            String key = occurrenceKey(
                    occurrence.demand().sourceDocumentId().value(),
                    occurrence.demand().sourcePath());
            if (rows.containsKey(key)) {
                continue;
            }
            ManagedOccurrenceBinding prospective =
                    ManagedOccurrenceBinding.derived(
                            current.input().environment()
                                    .managedBindingPolicyIdentity(),
                            occurrence.demand().sourceDocumentId(),
                            ScopeAddress.embedded(
                                    occurrence.demand().sourcePath(), 1L),
                            closureId(occurrence.targetDocumentId()),
                            occurrence.expectedTargetBlueId(),
                            !occurrence.historicalExisting(),
                            occurrence.pendingHistoricalEpoch());
            mergeAutomaticRow(rows, prospective);
            prospectiveIdentities.add(prospective.occurrenceIdentity());
        }
        AutomaticManagedOccurrenceExpansion accumulated =
                current.automaticExpansion().merge(
                        selected, prospectiveIdentities);

        LinkedHashMap<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = componentGenerations(
                        durable, captured, existingMembers);
        LinkedHashMap<blue.language.processor.closure.DocumentId,
                ManagedDocumentSnapshot> durableDocuments =
                new LinkedHashMap<>();
        ArrayList<blue.language.processor.closure.DocumentId> members =
                new ArrayList<>();
        ArrayList<blue.language.processor.closure.DocumentId> publicRoots =
                new ArrayList<>();
        for (CapturedAdmissionDocument document : captured.values()) {
            blue.language.processor.closure.DocumentId id =
                    closureId(document.documentId());
            boolean publicRoot = profile.isPublicRoot(document.documentId());
            long componentGeneration = requireComponentGeneration(
                    generations, id);
            Node invocationBody = invocationDocument(document.current());
            ManagedDocumentSnapshot snapshot = new ManagedDocumentSnapshot(
                    id,
                    document.head().blueId(),
                    invocationBody,
                    document.initialized(),
                    document.terminated(),
                    publicRoot,
                    document.head().epoch(),
                    componentGeneration);
            members.add(id);
            bodies.put(id, invocationBody.clone());
            durableDocuments.put(id, snapshot);
            if (publicRoot) {
                publicRoots.add(id);
            }
        }
        for (Map.Entry<DocumentId, ExactValue> entry
                : newDocuments.entrySet()) {
            blue.language.processor.closure.DocumentId id =
                    closureId(entry.getKey());
            members.add(id);
            bodies.put(id, entry.getValue().copyNode());
            generations.put(id, 1L);
            if (profile.isPublicRoot(entry.getKey())) {
                publicRoots.add(id);
            }
        }

        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                members, rows.values());
        ComponentFinalizationResult finalization =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, rows.values()));
        ArrayList<ManagedDocumentSnapshot> compiledDocuments =
                new ArrayList<>();
        finalization.documents().forEach((documentId, exact) -> {
            ManagedDocumentSnapshot durableDocument = durableDocuments.get(
                    documentId);
            if (durableDocument != null) {
                if (!durableDocument.blueId().equals(exact.blueId())) {
                    throw stale("Automatic admission expansion changed a "
                            + "durable input head " + documentId);
                }
                compiledDocuments.add(new ManagedDocumentSnapshot(
                        documentId,
                        exact.blueId(),
                        exact.document(),
                        durableDocument.initialized(),
                        durableDocument.terminated(),
                        durableDocument.publicRoot(),
                        durableDocument.epoch(),
                        exact.componentGeneration()));
                return;
            }
            ExactValue authored = newDocuments.get(
                    coordinationId(documentId));
            if (authored == null) {
                throw new IllegalStateException(
                        "Automatic admission finalization lost authored input "
                                + documentId);
            }
            compiledDocuments.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    false,
                    false,
                    profile.isPublicRoot(coordinationId(documentId)),
                    0L,
                    exact.componentGeneration()));
        });
        List<ComponentSnapshot> components = finalization.components().stream()
                .map(component -> component.component())
                .toList();
        long graphGeneration = existingMembers.isEmpty()
                ? current.input().snapshot().graphGeneration()
                : captured.values().stream()
                        .mapToLong(CapturedAdmissionDocument::graphGeneration)
                        .max()
                        .orElseThrow(() -> new IllegalStateException(
                                "Existing admission members were not "
                                        + "captured"));
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        graphGeneration,
                        compiledDocuments,
                        finalization.finalizedGraph().bindings(),
                        components,
                        publicRoots);
        ClosureInvocationInput original = current.input();
        ClosureInvocationInput expanded = ClosureEvidenceFactory.admitClosure(
                snapshot,
                (AdmissionCause) original.cause(),
                original.admissionCandidate(),
                original.executionPolicy(),
                original.environment());
        return new AdmissionInvocation(
                expanded,
                current.publicationIdentityMembers(),
                newDocuments,
                existingMembers,
                captured,
                accumulated);
    }

    private long requireManagedDocumentLimit() {
        Long limit = environment.portableLimitPolicy().limits().get(
                "managedDocumentsPerClosure");
        if (limit == null || limit.longValue() <= 0L) {
            throw new IllegalStateException(
                    "Portable limit policy has no positive "
                            + "managedDocumentsPerClosure limit");
        }
        return MultiDocumentPublicationTransaction.requireSafeInteger(
                limit.longValue(), "managedDocumentsPerClosure");
    }

    private CapturedAdmissionDocument captureAdmissionDocument(
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead expectedHead,
            long expectedGraphGeneration) {
        DocumentSession session = documents.require(documentId);
        synchronized (session) {
            InMemoryDocumentStore.DocumentHead actual =
                    new InMemoryDocumentStore.DocumentHead(
                            session.epoch(),
                            session.currentRepresentation().blueId());
            if (!actual.equals(expectedHead)) {
                throw stale("Document head changed during admission capture "
                        + documentId);
            }
            ExactValue current = session.currentRepresentation();
            if (!current.blueId().equals(session.layout().rootBlueId())) {
                throw new IllegalStateException(
                        "Session layout disagrees with durable head "
                                + documentId);
            }
            return new CapturedAdmissionDocument(
                    documentId,
                    actual,
                    expectedGraphGeneration,
                    current,
                    session.layout(),
                    session.activeSubscriptions(),
                    runtime.documentProcessor().isInitialized(
                            current.copyNode()),
                    session.status() == SessionStatus.TERMINATED);
        }
    }

    private static Set<DocumentId> connectedExistingMembers(
            Collection<DocumentId> original,
            Collection<DocumentId> targets,
            ManagedOccurrenceInventory inventory) {
        TreeMap<DocumentId, Boolean> discovered = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Deque<DocumentId> pending = new ArrayDeque<>();
        for (DocumentId documentId : original) {
            if (discovered.putIfAbsent(documentId, Boolean.TRUE) == null) {
                pending.addLast(documentId);
            }
        }
        for (DocumentId documentId : targets) {
            if (discovered.putIfAbsent(documentId, Boolean.TRUE) == null) {
                pending.addLast(documentId);
            }
        }
        while (!pending.isEmpty()) {
            DocumentId source = pending.removeFirst();
            for (ManagedOccurrenceBinding row : inventory.rowsFrom(source)) {
                DocumentId target = coordinationId(row.targetDocumentId());
                if (discovered.putIfAbsent(target, Boolean.TRUE) == null) {
                    pending.addLast(target);
                }
            }
            // Active containing occurrences participate in the same exact
            // publication. Inactive reservations retain forward evidence but
            // do not make their containing documents live participants.
            for (ManagedOccurrenceBinding row : inventory.rowsTouching(source)) {
                DocumentId parent = coordinationId(row.sourceDocumentId());
                if (row.active()
                        && coordinationId(row.targetDocumentId()).equals(source)
                        && discovered.putIfAbsent(parent, Boolean.TRUE) == null) {
                    pending.addLast(parent);
                }
            }
        }
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(discovered.keySet()));
    }

    private static void mergeAutomaticRow(
            Map<String, ManagedOccurrenceBinding> rows,
            ManagedOccurrenceBinding row) {
        String key = occurrenceKey(
                row.sourceDocumentId().value(), row.sourcePath());
        ManagedOccurrenceBinding prior = rows.putIfAbsent(key, row);
        if (prior != null && !OccurrenceProjection.from(prior).equals(
                OccurrenceProjection.from(row))) {
            throw stale("Managed occurrence evidence disagrees at " + key);
        }
    }

    private static String occurrenceKey(
            String sourceDocumentId,
            String sourcePath) {
        return Objects.requireNonNull(sourceDocumentId, "sourceDocumentId")
                + "\u0000"
                + Objects.requireNonNull(sourcePath, "sourcePath");
    }

    private static LinkedHashMap<blue.language.processor.closure.DocumentId,
            Long> componentGenerations(
                    InMemoryDocumentStore.ClosureSnapshot durable,
                    Map<DocumentId, CapturedAdmissionDocument> captured,
                    Set<DocumentId> expectedMembers) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        for (ComponentSnapshot component : durable.componentStates()) {
            List<DocumentId> componentMembers = component
                    .orderedMemberDocumentIds().stream()
                    .map(ContractsClosureAdmissionAdapter::coordinationId)
                    .toList();
            if (!expectedMembers.containsAll(componentMembers)) {
                throw stale("Forward admission closure contains only part of "
                        + "a durable component " + componentMembers);
            }
            for (int index = 0; index < componentMembers.size(); index++) {
                DocumentId member = componentMembers.get(index);
                CapturedAdmissionDocument document = captured.get(member);
                if (document == null
                        || !document.head().blueId().equals(
                                component.orderedMemberBlueIds().get(index))) {
                    throw stale("Durable component state is stale for "
                            + member);
                }
                generations.put(
                        closureId(member),
                        component.componentGeneration());
            }
        }
        if (generations.size() != expectedMembers.size()) {
            throw stale("Durable component state does not cover automatic "
                    + "admission expansion");
        }
        return generations;
    }

    private static long requireComponentGeneration(
            Map<blue.language.processor.closure.DocumentId, Long> generations,
            blue.language.processor.closure.DocumentId documentId) {
        Long generation = generations.get(documentId);
        if (generation == null) {
            throw new IllegalStateException(
                    "No component generation for " + documentId);
        }
        return generation;
    }

    private static void requireIndexGenerations(
            InMemoryDocumentStore.OccurrenceResolutionSnapshot expected,
            InMemoryDocumentStore.ClosureSnapshot actual) {
        if (actual.occurrenceInventoryGeneration()
                        != expected.occurrenceInventoryGeneration()
                || actual.componentIndexGeneration()
                        != expected.componentIndexGeneration()) {
            throw stale("Managed occurrence indexes changed during "
                    + "admission expansion");
        }
    }

    private void requireAutomaticRetryStillCurrent(
            AdmissionInvocation before,
            AdmissionInvocation expanded,
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState) {
        if (!before.input().cause().causeIdentity().equals(
                expanded.input().cause().causeIdentity())
                || !Objects.equals(
                        before.input().admissionCandidateIdentity(),
                        expanded.input().admissionCandidateIdentity())
                || before.input().executionPolicy()
                        != expanded.input().executionPolicy()
                || before.input().environment()
                        != expanded.input().environment()
                || !before.publicationIdentityMembers().equals(
                        expanded.publicationIdentityMembers())) {
            throw new IllegalStateException(
                    "Automatic admission retry changed its frozen lane");
        }
        InMemoryDocumentStore.OccurrenceResolutionSnapshot currentIndexes =
                documents.occurrenceResolutionSnapshot();
        if (currentIndexes.occurrenceInventoryGeneration()
                        != storeState.occurrenceInventoryGeneration()
                || currentIndexes.componentIndexGeneration()
                        != storeState.componentIndexGeneration()) {
            throw stale("Managed occurrence indexes changed before "
                    + "admission retry");
        }
        InMemoryDocumentStore.ClosureSnapshot current =
                documents.admissionSnapshot(expanded.existingMembers());
        for (Map.Entry<DocumentId, CapturedAdmissionDocument> entry
                : expanded.existingDocuments().entrySet()) {
            if (!entry.getValue().head().equals(
                    current.requireHead(entry.getKey()))) {
                throw stale("Existing admission member changed before retry "
                        + entry.getKey());
            }
        }
    }

    private Node invocationDocument(ExactValue current) {
        ExactValue selected = Objects.requireNonNull(current, "current");
        return selected.isCyclicMember()
                ? objects.requireProviderDocument(selected)
                : selected.copyNode();
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static DocumentId coordinationId(
            blue.language.processor.closure.DocumentId documentId) {
        return DocumentId.of(documentId.value());
    }

    private static MultiDocumentPublicationTransaction.AtomicPublicationCasException
            stale(String message) {
        return new MultiDocumentPublicationTransaction
                .AtomicPublicationCasException(message);
    }

    private void publish(
            AdmissionInvocation invocation,
            ClosureAttemptResult attempt,
            ClosureProcessResult result,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier,
            String publicationIdentity,
            List<DocumentId> members,
            InMemoryDocumentStore.ClosureSnapshot before) {
        ClosureInvocationInput input = invocation.input();
        Set<DocumentId> memberSet = new LinkedHashSet<>(members);
        ManagedOccurrenceInventory.DeltaResult inventoryDelta =
                mergeAdmissionInventory(
                before.occurrenceInventory(),
                result.occurrenceBindings(),
                memberSet);
        ManagedOccurrenceInventory resultingInventory =
                inventoryDelta.inventory();
        boolean inventoryChanged = inventoryDelta.changed();
        long inventoryGeneration = transitionGeneration(
                before.occurrenceInventoryGeneration(),
                inventoryChanged,
                "occurrence inventory generation");
        boolean componentIndexChanged = !invocation.newDocuments().isEmpty()
                || !sameActiveTopologyForSources(
                before.occurrenceInventory(), resultingInventory, memberSet);
        long componentIndexGeneration = transitionGeneration(
                before.componentIndexGeneration(),
                componentIndexChanged,
                "component index generation");
        TreeMap<DocumentId, Long> existingGraphGenerations = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        invocation.existingDocuments().values().forEach(document ->
                existingGraphGenerations.put(
                        document.documentId(), document.graphGeneration()));
        ClosureSubscriptionInventory subscriptions = before
                .closureSubscriptions().apply(
                        result, existingGraphGenerations);
        Map<DocumentId, ResultingDocument> resultingDocuments =
                resultingDocuments(result, memberSet);
        Map<DocumentId, ManagedDocumentSnapshot> inputDocuments =
                inputDocuments(input, memberSet);
        Map<DocumentId, ManagedDocumentTransitionReceipt>
                transitionReceipts = transitionReceipts(result, memberSet);
        Map<DocumentId, ManagedCatchUpPlanner.Head> resultingHeads =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        Map<DocumentId, ManagedEpochReceipt> committedEpochReceipts =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        ContractsClosureAdmissionReceipt durableReceipt =
                new ContractsClosureAdmissionReceipt(
                        attempt,
                        publicationIdentity,
                        ContractsClosureAdmissionReceipt.PublicationOutcome
                                .PUBLISHED,
                        members);

        MultiDocumentPublicationTransaction transaction = documents
                .beginAtomicPublication(
                        publicationIdentity,
                        before.occurrenceInventoryGeneration(),
                        before.componentIndexGeneration());
        for (CapturedAdmissionDocument document
                : invocation.existingDocuments().values()) {
            transaction.expectHead(
                    document.documentId(),
                    document.head().epoch(),
                    document.head().blueId());
            transaction.expectGraphGeneration(
                    document.documentId(), document.graphGeneration());
        }
        invocation.newDocuments().keySet().forEach(transaction::expectAbsent);
        input.snapshot().components().forEach(component -> {
            boolean allExisting = component.orderedMemberDocumentIds().stream()
                    .allMatch(member -> invocation.existingMembers().contains(
                            coordinationId(member)));
            if (allExisting) {
                transaction.expectComponentState(component);
            }
        });
        transaction
                .stageOccurrenceInventory(
                        resultingInventory,
                        inventoryGeneration,
                        componentIndexGeneration)
                .stageComponentStates(result.resultingComponents())
                .stageClosureAdmissionResult(input, result)
                .stageOutbox(result.publicEvents())
                .stageCheckpointEvidence(result.checkpointWrites())
                .stageAdmissionReceipt(durableReceipt)
                .onFailurePoint(failureInjector);

        WholeObjectStore.Mark objectMark = objects.mark();
        boolean storeCommitted = false;
        try {
            List<OperationRouteIndex.Replacement> routeReplacements =
                    new ArrayList<>();
            for (DocumentId documentId : members) {
                ManagedDocumentSnapshot admitted = inputDocuments.get(
                        documentId);
                ResultingDocument resulting = resultingDocuments.get(
                        documentId);
                CapturedAdmissionDocument existing = invocation
                        .existingDocuments().get(documentId);
                if (existing != null) {
                    if (!resulting.beforeBlueId().equals(
                            existing.head().blueId())
                            || resulting.epoch() != existing.head().epoch()
                            || !resulting.afterBlueId().equals(
                                    existing.head().blueId())) {
                        throw new AdmissionProjectionUnavailableException(
                                "Static admission may retain but cannot advance "
                                        + "existing member " + documentId);
                    }
                    routeReplacements.add(
                            new OperationRouteIndex.Replacement(
                                    documentId,
                                    existing.layout().routingSurface(),
                                    existing.activeSubscriptions()));
                    resultingHeads.put(
                            documentId,
                            new ManagedCatchUpPlanner.Head(
                                    existing.head().epoch(),
                                    existing.head().blueId()));
                    continue;
                }
                if (admitted.epoch() != 0L || admitted.initialized()
                        || resulting.epoch() != 0L) {
                    throw new AdmissionProjectionUnavailableException(
                            "New closure admission must initialize epoch zero "
                                    + documentId);
                }
                ExactValue resolvedAuthored = invocation.newDocuments()
                        .get(documentId);
                ExactValue admissionAuthored = resolvedAuthored != null
                        && resolvedAuthored.blueId().equals(documentId.value())
                        ? resolvedAuthored
                        : ExactValue.fromVerifiedClosureAdmissionInput(
                                input, result, documentId);
                ExactValue authored = objects.put(
                        admissionAuthored,
                        "verified-closure-admission-input");
                ManagedRootSubscriptionSurface rootSurface = contracts
                        .projectRootSubscriptionSurface(
                                resulting.document());
                transaction.stageEmbeddedDemands(
                        documentId,
                        ClosureSubscriptionInventory.embeddedDemands(
                                rootSurface));
                EmbeddedOnlyLayout layout = layoutBuilder
                        .retainVerifiedClosureRoot(
                                result, documentId, rootSurface);
                ExactValue initialized = objects.put(
                        layout.semanticRoot(),
                        "closure-admission-initialization-revision");
                requireExactRootSubscriptionSurface(
                        documentId,
                        rootSurface,
                        subscriptions.statesFor(documentId));
                List<SubscriptionDelta.Entry> activeSubscriptions =
                        activateInitialSubscriptions(
                                rootSurface.externalSubscriptions(),
                                resulting.epoch(),
                                frontier);
                CheckpointDomainEvidence.retainAll(
                        activeSubscriptions, objects);
                String causeBlueId = retainAdmissionCause(
                        input,
                        result,
                        documentId,
                        policy,
                        frontier);
                List<Node> emitted = result.publicEvents().stream()
                        .filter(event -> event.publicRootDocumentId().value()
                                .equals(documentId.value()))
                        .map(PublicEventOccurrence::event)
                        .toList();
                ManagedDocumentTransitionReceipt transition =
                        transitionReceipts.get(documentId);
                if (transition == null) {
                    throw new AdmissionProjectionUnavailableException(
                            "New closure admission has no complete managed "
                                    + "transition receipt " + documentId);
                }
                ManagedEpochReceipt epochReceipt =
                        ManagedEpochReceiptMapper.map(
                                documentId,
                                0L,
                                DocumentRevision.Kind.INITIALIZATION,
                                authored,
                                initialized,
                                null,
                                frontier,
                                transition,
                                result.platformCommitCompanion());
                DocumentRevision revision = new DocumentRevision(
                        documentId,
                        0L,
                        0L,
                        DocumentRevision.Kind.INITIALIZATION,
                        authored,
                        initialized,
                        null,
                        frontier,
                        causeBlueId,
                        null,
                        emitted,
                        transition.admittedGas(),
                        epochReceipt);
                DocumentSession session = new DocumentSession(
                        documentId,
                        authored,
                        layout,
                        activeSubscriptions,
                        frontier,
                        revision);
                if (profile.rootedCheckpoint()) {
                    session.establishRootedHistory(RootedDocumentHistory.admitted(documentId,
                            authored, policy, frontier, input, result, profile.rootedRuntimeSemanticsIdentity()));
                }
                session.restoreCoordinationState(
                        resulting.terminated()
                                ? SessionStatus.TERMINATED
                                : SessionStatus.READY,
                        frontier,
                        0L,
                        0L);
                transaction.stageNewSession(session);
                transaction.stageManagedEpochReceipt(
                        epochReceipt, transition);
                resultingHeads.put(
                        documentId,
                        new ManagedCatchUpPlanner.Head(
                                0L, initialized.blueId()));
                committedEpochReceipts.put(documentId, epochReceipt);
                routeReplacements.add(new OperationRouteIndex.Replacement(
                        documentId,
                        layout.routingSurface(),
                        activeSubscriptions));
            }
            if (profile.rootedCheckpoint()) {
                Map<DocumentId, List<SubscriptionDelta.Entry>> selectedRoutes = new LinkedHashMap<>();
                for (OperationRouteIndex.Replacement replacement : routeReplacements) {
                    selectedRoutes.put(replacement.documentId(), replacement.activeSubscriptions());
                }
                transaction.stageRootedView(new RootedDocumentView(result, subscriptions, selectedRoutes));
            }
            objects.retainVerifiedClosureComponentEvidence(result);
            CatchUpPlanStore beforeCatchUpPlans =
                    documents.catchUpPlansSnapshot();
            ManagedCatchUpPlanner.PlanningResult catchUp =
                    ManagedCatchUpPlanner.afterPublication(
                            beforeCatchUpPlans,
                            before.occurrenceInventory(),
                            resultingInventory,
                            memberSet,
                            committedEpochReceipts.values(),
                            input.cause().causeIdentity(),
                            frontier,
                            documentId -> resultingManagedHead(
                                    resultingHeads, documentId),
                            (documentId, epoch) -> {
                                ManagedEpochReceipt staged =
                                        committedEpochReceipts.get(documentId);
                                if (staged != null
                                        && staged.epoch() == epoch) {
                                    return staged;
                                }
                                return documents.managedEpochReceipt(
                                                documentId, epoch)
                                        .orElse(null);
                            },
                            documentId -> memberSet.contains(documentId)
                                    ? result.graphGeneration()
                                    : documents.graphGeneration(documentId),
                            new ManagedRepresentationHistory(documents),
                            blueId -> objects.cyclicSetProofFor(blueId).proof().orElse(null));
            transaction.stageCatchUpPlans(
                    beforeCatchUpPlans, catchUp.plans());
            OperationRouteIndex.PreparedReplacement preparedRoutes = routes
                    .prepareReplacement(routeReplacements);
            transaction.commit();
            storeCommitted = true;
            publicationFailureInjector.accept(
                    PublicationFailurePoint
                            .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH);
            preparedRoutes.publish();
            activeSourceTimelines.refresh(members, documents);
            objects.commit(objectMark);
        } catch (RuntimeException failure) {
            if (storeCommitted) {
                objects.commit(objectMark);
            } else {
                objects.rollbackTo(objectMark);
            }
            throw failure;
        }
    }

    private ManagedCatchUpPlanner.Head resultingManagedHead(
            Map<DocumentId, ManagedCatchUpPlanner.Head> resultingHeads,
            DocumentId documentId) {
        ManagedCatchUpPlanner.Head staged = Objects.requireNonNull(
                resultingHeads, "resultingHeads").get(
                        Objects.requireNonNull(documentId, "documentId"));
        if (staged != null) {
            return staged;
        }
        DocumentSession session = documents.require(documentId);
        synchronized (session) {
            return new ManagedCatchUpPlanner.Head(
                    session.epoch(),
                    session.currentRepresentation().blueId());
        }
    }

    private void requireExactAdmissionInput(ClosureInvocationInput input) {
        if (input.operation()
                != ClosureInvocationInput.Operation.ADMIT_CLOSURE
                || !(input.cause() instanceof AdmissionCause)) {
            throw new IllegalArgumentException(
                    "Contracts admission requires one ADMIT_CLOSURE input");
        }
        if (!sameEnvironment(environment, input.environment())) {
            throw new IllegalArgumentException(
                    "Admission environment does not equal this engine's exact "
                            + "Contracts 1.0 configuration");
        }
        if (!sameExecutionPolicy(
                profile.executionPolicy(), input.executionPolicy())) {
            throw new IllegalArgumentException(
                    "Admission execution policy does not equal the configured "
                            + "Contracts 1.0 release policy");
        }
        Set<DocumentId> members = new LinkedHashSet<>(coordinationIds(
                input.snapshot().managedDocuments()));
        Set<DocumentId> declaredRoots = new LinkedHashSet<>();
        input.snapshot().publicRootDocumentIds().forEach(root ->
                declaredRoots.add(DocumentId.of(root.value())));
        Set<DocumentId> configuredMembers = new LinkedHashSet<>();
        profile.publicRoots().stream()
                .filter(members::contains)
                .forEach(configuredMembers::add);
        if (declaredRoots.isEmpty()
                || !declaredRoots.equals(configuredMembers)) {
            throw new IllegalArgumentException(
                    "Admission public Roots do not match configured Root "
                            + "lineages in the admitted closure");
        }
    }

    private static void requireResultBelongsToAdmission(
            ClosureInvocationInput input,
            ClosureProcessResult result,
            List<DocumentId> members) {
        if (!result.invocationIdentity().equals(input.invocationIdentity())
                || !result.inputClosureIdentity().equals(
                        input.snapshot().closureIdentity())
                || result.platformCommitCompanion() == null) {
            throw new IllegalStateException(
                    "Successful admission result does not authenticate its input");
        }
        Set<DocumentId> exactMembers = new LinkedHashSet<>(members);
        Set<DocumentId> resultMembers = new LinkedHashSet<>();
        result.resultingDocuments().forEach(document -> resultMembers.add(
                DocumentId.of(document.documentId().value())));
        Set<DocumentId> companionMembers = new LinkedHashSet<>();
        result.platformCommitCompanion().expectedInputDocuments()
                .forEach(document -> companionMembers.add(DocumentId.of(
                        document.documentId().value())));
        if (!exactMembers.equals(resultMembers)
                || !exactMembers.equals(companionMembers)) {
            throw new IllegalStateException(
                    "Successful admission result has an incomplete member set");
        }
    }

    private static ManagedOccurrenceInventory.DeltaResult
            mergeAdmissionInventory(
            ManagedOccurrenceInventory before,
            Collection<ManagedOccurrenceBinding> admittedRows,
            Set<DocumentId> admittedMembers) {
        for (DocumentId source : admittedMembers) {
            for (ManagedOccurrenceBinding row : before.rowsFrom(source)) {
                if (!admittedMembers.contains(
                        coordinationId(row.targetDocumentId()))) {
                    throw new UnsupportedOperationException(
                            "Forward admission closure omitted an outgoing "
                                    + "target");
                }
            }
        }
        ArrayList<ManagedOccurrenceBinding> merged = new ArrayList<>();
        for (ManagedOccurrenceBinding row : Objects.requireNonNull(
                admittedRows, "admittedRows")) {
            if (!admittedMembers.contains(DocumentId.of(
                    row.sourceDocumentId().value()))
                    || !admittedMembers.contains(DocumentId.of(
                    row.targetDocumentId().value()))) {
                throw new IllegalStateException(
                        "Admission occurrence escapes the admitted closure");
            }
            merged.add(row);
        }
        return before.replaceSources(admittedMembers, merged);
    }

    private static boolean sameActiveTopologyForSources(
            ManagedOccurrenceInventory first,
            ManagedOccurrenceInventory second,
            Collection<DocumentId> sources) {
        for (DocumentId source : sources) {
            if (!first.activeRowsFrom(source).stream()
                    .map(ActiveEdge::from).toList()
                    .equals(second.activeRowsFrom(source).stream()
                            .map(ActiveEdge::from).toList())) {
                return false;
            }
        }
        return true;
    }

    private static long transitionGeneration(
            long current,
            boolean changed,
            String label) {
        return changed
                ? InMemoryDocumentStore.increment(current, label)
                : current;
    }

    private static Map<DocumentId, ManagedDocumentSnapshot> inputDocuments(
            ClosureInvocationInput input,
            Set<DocumentId> expectedMembers) {
        TreeMap<DocumentId, ManagedDocumentSnapshot> indexed = new TreeMap<>();
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            DocumentId documentId = DocumentId.of(
                    document.documentId().value());
            if (indexed.putIfAbsent(documentId, document) != null) {
                throw new IllegalStateException(
                        "Admission input repeats document " + documentId);
            }
        }
        if (!indexed.keySet().equals(expectedMembers)) {
            throw new IllegalStateException(
                    "Admission input document set changed during publication");
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<DocumentId, ResultingDocument> resultingDocuments(
            ClosureProcessResult result,
            Set<DocumentId> expectedMembers) {
        TreeMap<DocumentId, ResultingDocument> indexed = new TreeMap<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            DocumentId documentId = DocumentId.of(
                    document.documentId().value());
            if (indexed.putIfAbsent(documentId, document) != null) {
                throw new IllegalStateException(
                        "Admission result repeats document " + documentId);
            }
        }
        if (!indexed.keySet().equals(expectedMembers)) {
            throw new IllegalStateException(
                    "Admission result document set is incomplete");
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<DocumentId, ManagedDocumentTransitionReceipt>
            transitionReceipts(
                    ClosureProcessResult result,
                    Set<DocumentId> expectedMembers) {
        TreeMap<DocumentId, ManagedDocumentTransitionReceipt> indexed =
                new TreeMap<>();
        for (ManagedDocumentTransitionReceipt receipt
                : Objects.requireNonNull(result, "result")
                        .managedTransitionReceipts()) {
            DocumentId documentId = DocumentId.of(
                    receipt.documentId().value());
            if (!expectedMembers.contains(documentId)
                    || indexed.putIfAbsent(documentId, receipt) != null) {
                throw new IllegalStateException(
                        "Admission transition receipts do not name one unique "
                                + "member " + documentId);
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static List<DocumentId> coordinationIds(
            Collection<ManagedDocumentSnapshot> documents) {
        return documents.stream()
                .map(document -> DocumentId.of(document.documentId().value()))
                .sorted()
                .toList();
    }

    private void requireExactlyPresent(List<DocumentId> members) {
        for (DocumentId documentId : members) {
            if (documents.find(documentId).isEmpty()) {
                throw new IllegalStateException(
                        "Durable admission receipt is missing member "
                                + documentId);
            }
        }
    }

    private void reconcileRoutes(List<DocumentId> members) {
        List<OperationRouteIndex.Replacement> replacements = new ArrayList<>();
        for (DocumentId documentId : members) {
            DocumentSession session = documents.require(documentId);
            synchronized (session) {
                replacements.add(new OperationRouteIndex.Replacement(
                        documentId,
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
            }
        }
        routes.prepareReplacement(replacements).publish();
        activeSourceTimelines.refresh(members, documents);
    }

    private String retainAdmissionCause(
            ClosureInvocationInput input,
            ClosureProcessResult result,
            DocumentId documentId,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier) {
        LinkedHashMap<String, Node> fields = new LinkedHashMap<>();
        fields.put("causeType", new Node().value(
                "Coordination/Contracts Closure Admission Cause/v1"));
        fields.put("documentId", new Node().value(documentId.value()));
        fields.put("invocationIdentity", new Node().value(
                input.invocationIdentity()));
        fields.put("admissionCauseIdentity", new Node().value(
                input.cause().causeIdentity()));
        fields.put("inputClosureIdentity", new Node().value(
                result.inputClosureIdentity()));
        fields.put("outputClosureIdentity", new Node().value(
                result.outputClosureIdentity()));
        fields.put("admissionPolicy", new Node().value(policy.name()));
        fields.put("admissionFrontier", new Node().items(
                frontier.components().stream()
                        .map(value -> new Node().value(value))
                        .toList()));
        return objects.put(
                new Node().properties(fields),
                "contracts-closure-admission-cause").blueId();
    }

    private static List<SubscriptionDelta.Entry> activateInitialSubscriptions(
            List<SubscriptionDelta.Entry> desired,
            long epoch,
            ExternalOrderKey frontier) {
        ArrayList<SubscriptionDelta.Entry> result = new ArrayList<>();
        for (SubscriptionDelta.Entry value : Objects.requireNonNull(
                desired, "desired")) {
            if (!"/".equals(value.scopePath())) {
                throw new AdmissionProjectionUnavailableException(
                        "Managed admission route escaped Root at "
                                + value.scopePath());
            }
            result.add(new SubscriptionDelta.Entry(
                    value.scopePath(),
                    value.channelKey(),
                    value.effectiveTypeBlueId(),
                    value.sourceContributionNodeBlueIds(),
                    value.order(),
                    value.subscriptionKeys(),
                    value.checkpointDomainBlueId(),
                    value.dependencies(),
                    epoch,
                    frontier,
                    null));
        }
        return List.copyOf(result);
    }

    private static void requireExactRootSubscriptionSurface(
            DocumentId documentId,
            ManagedRootSubscriptionSurface projected,
            List<SubscriptionState> exactStates) {
        Map<String, ManagedRootChannelOccurrence> channels =
                new LinkedHashMap<>();
        for (ManagedRootChannelOccurrence channel
                : projected.channelOccurrences()) {
            if (channels.putIfAbsent(channel.rawChannelKey(), channel) != null) {
                throw new AdmissionProjectionUnavailableException(
                        "Root Channel projection repeats "
                                + channel.rawChannelKey() + " for "
                                + documentId);
            }
        }
        Map<String, SubscriptionState> states = new LinkedHashMap<>();
        for (SubscriptionState state : exactStates) {
            if (!state.channelOccurrence().managedDocumentId().value()
                    .equals(documentId.value())) {
                throw new IllegalStateException(
                        "Closure subscription escaped document " + documentId);
            }
            String key = state.channelOccurrence().rawChannelKey();
            if (states.putIfAbsent(key, state) != null) {
                throw new IllegalStateException(
                        "Closure subscription repeats Root Channel " + key);
            }
        }
        if (!channels.keySet().equals(states.keySet())) {
            throw new AdmissionProjectionUnavailableException(
                    "Root Channel projection disagrees with verified admission "
                            + "subscriptions for " + documentId);
        }
        for (Map.Entry<String, ManagedRootChannelOccurrence> entry
                : channels.entrySet()) {
            ManagedRootChannelOccurrence channel = entry.getValue();
            blue.language.processor.closure.ChannelOccurrence exact = states
                    .get(entry.getKey()).channelOccurrence();
            if (!channel.effectiveRuntimeContributionBlueId().equals(
                    exact.effectiveRuntimeContributionBlueId())
                    || !channel.subscriptionHeaderBlueId().equals(
                    exact.subscriptionHeaderBlueId())) {
                throw new AdmissionProjectionUnavailableException(
                        "Root Channel evidence disagrees at " + documentId
                                + "/" + entry.getKey());
            }
        }
        Set<String> externalChannels = projected.channelOccurrences().stream()
                .filter(ManagedRootChannelOccurrence::externalSource)
                .map(ManagedRootChannelOccurrence::rawChannelKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        Set<String> routedChannels = projected.externalSubscriptions().stream()
                .map(SubscriptionDelta.Entry::channelKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        if (!externalChannels.equals(routedChannels)) {
            throw new AdmissionProjectionUnavailableException(
                    "Externally routable Root Channels are incomplete for "
                            + documentId);
        }
    }

    private static Map<DocumentId, Long> gasByDocument(
            ClosureProcessResult result,
            Set<DocumentId> documents) {
        TreeMap<DocumentId, Long> gas = new TreeMap<>();
        documents.forEach(document -> gas.put(document, 0L));
        long invocationOwned = 0L;
        for (GasTraceEntry entry : result.gasTrace()) {
            if (entry.documentId() == null) {
                invocationOwned = Math.addExact(
                        invocationOwned, entry.subtotal());
                continue;
            }
            DocumentId documentId = DocumentId.of(
                    entry.documentId().value());
            if (!gas.containsKey(documentId)) {
                throw new IllegalStateException(
                        "Admission gas names an outside document " + documentId);
            }
            gas.put(documentId, Math.addExact(
                    gas.get(documentId), entry.subtotal()));
        }
        if (!gas.isEmpty()) {
            DocumentId owner = gas.firstKey();
            gas.put(owner, Math.addExact(gas.get(owner), invocationOwned));
        }
        long total = gas.values().stream().reduce(0L, Math::addExact);
        if (total != result.totalGas()) {
            throw new IllegalStateException(
                    "Admission gas projection does not preserve total gas");
        }
        return Collections.unmodifiableMap(gas);
    }

    private static boolean sameEnvironment(
            ClosureEnvironment expected,
            ClosureEnvironment actual) {
        return expected.blueLanguageSpecificationIdentity().equals(
                actual.blueLanguageSpecificationIdentity())
                && expected.contractsSpecificationIdentity().equals(
                actual.contractsSpecificationIdentity())
                && expected.runtimeRegistryIdentity().equals(
                actual.runtimeRegistryIdentity())
                && expected.gasManifestIdentity().equals(
                actual.gasManifestIdentity())
                && sameLabeled(expected.managedDocumentIdentityPolicy(),
                        actual.managedDocumentIdentityPolicy())
                && sameLabeled(expected.managedBindingPolicy(),
                        actual.managedBindingPolicy())
                && sameLabeled(expected.exactNodeProviderDomain(),
                        actual.exactNodeProviderDomain())
                && sameLabeled(expected.externalOrderPolicy(),
                        actual.externalOrderPolicy())
                && expected.portableLimitPolicy().identity().equals(
                        actual.portableLimitPolicy().identity())
                && expected.portableLimitPolicy().label().equals(
                        actual.portableLimitPolicy().label())
                && expected.portableLimitPolicy().limits().equals(
                        actual.portableLimitPolicy().limits())
                && expected.cyclicFinalizerIdentity().equals(
                        actual.cyclicFinalizerIdentity())
                && expected.cyclicProofVerifierIdentity().equals(
                        actual.cyclicProofVerifierIdentity());
    }

    private static boolean sameLabeled(
            ClosureEnvironment.LabeledIdentityEvidence expected,
            ClosureEnvironment.LabeledIdentityEvidence actual) {
        return expected.identity().equals(actual.identity())
                && expected.label().equals(actual.label());
    }

    private static boolean sameExecutionPolicy(
            ExecutionPolicy expected,
            ExecutionPolicy actual) {
        return expected.identity().equals(actual.identity())
                && expected.sharedLimit() == actual.sharedLimit()
                && expected.localLimits().equals(actual.localLimits())
                && expected.label().equals(actual.label());
    }

    static String publicationIdentity(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier) {
        return publicationIdentity(
                input,
                policy,
                frontier,
                FULL_LIFECYCLE_IDENTITY_DOMAIN,
                null);
    }

    static String publicationIdentity(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier,
            ContractsManagedEpochSelectionPlan selectionPlan) {
        return publicationIdentity(
                input,
                policy,
                frontier,
                FULL_LIFECYCLE_IDENTITY_DOMAIN,
                selectionPlan);
    }

    private static String boundedCompatibilityPublicationIdentity(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier) {
        return publicationIdentity(
                input,
                policy,
                frontier,
                BOUNDED_COMPATIBILITY_IDENTITY_DOMAIN,
                null);
    }

    private static String publicationIdentity(
            ClosureInvocationInput input,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier,
            String identityDomain,
            ContractsManagedEpochSelectionPlan selectionPlan) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(frontier, "frontier");
        String domain = Objects.requireNonNull(
                identityDomain, "identityDomain");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "JVM does not provide SHA-256", unavailable);
        }
        updatePublicationIdentityFrame(digest, 0, domain);
        updatePublicationIdentityFrame(
                digest, 1, input.invocationIdentity());
        updatePublicationIdentityFrame(
                digest, 2, input.snapshot().closureIdentity());
        updatePublicationIdentityFrame(digest, 3, policy.name());
        List<Object> components = frontier.components();
        updatePublicationIdentityFrame(
                digest, 4, Integer.toString(components.size()));
        for (Object component : components) {
            if (component instanceof BigInteger integer) {
                updatePublicationIdentityFrame(
                        digest, 5, integer.toString());
            } else if (component instanceof String text) {
                updatePublicationIdentityFrame(digest, 6, text);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported external frontier component "
                                + component);
            }
        }
        if (selectionPlan != null) {
            updatePublicationIdentityFrame(
                    digest, 7, selectionPlan.targetDocumentId().value());
            updatePublicationIdentityFrame(
                    digest, 8, Long.toString(selectionPlan.targetEpoch()));
            updatePublicationIdentityFrame(
                    digest, 9, selectionPlan.targetBlueId());
            updatePublicationIdentityFrame(
                    digest,
                    10,
                    Integer.toString(selectionPlan.selections().size()));
            for (ContractsManagedEpochSelectionPlan.Selection selection
                    : selectionPlan.selections()) {
                updatePublicationIdentityFrame(
                        digest, 11, selection.sourceDocumentId().value());
                updatePublicationIdentityFrame(
                        digest, 12, Long.toString(selection.sourceEpoch()));
                updatePublicationIdentityFrame(
                        digest, 13, selection.expectedSourceBlueId());
                updatePublicationIdentityFrame(
                        digest, 14, selection.targetOccurrencePath());
            }
        }
        return domain + ":sha256:"
                + HexFormat.of().formatHex(digest.digest());
    }

    private static void updatePublicationIdentityFrame(
            MessageDigest digest,
            int kind,
            String value) {
        byte[] encoded = Objects.requireNonNull(value, "frame value")
                .getBytes(StandardCharsets.UTF_8);
        digest.update((byte) kind);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(encoded.length)
                .array());
        digest.update(encoded);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Contracts closure admission adapter is closed");
        }
    }

    private record AdmissionInvocation(
            ClosureInvocationInput input,
            List<DocumentId> publicationIdentityMembers,
            Map<DocumentId, ExactValue> newDocuments,
            Set<DocumentId> existingMembers,
            Map<DocumentId, CapturedAdmissionDocument> existingDocuments,
            AutomaticManagedOccurrenceExpansion automaticExpansion) {
        private AdmissionInvocation {
            input = Objects.requireNonNull(input, "input");
            publicationIdentityMembers = List.copyOf(Objects.requireNonNull(
                    publicationIdentityMembers,
                    "publicationIdentityMembers"));
            newDocuments = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(newDocuments, "newDocuments")));
            existingMembers = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            existingMembers, "existingMembers")));
            existingDocuments = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            existingDocuments, "existingDocuments")));
            automaticExpansion = Objects.requireNonNull(
                    automaticExpansion, "automaticExpansion");
            if (publicationIdentityMembers.isEmpty()
                    || !existingDocuments.keySet().equals(existingMembers)) {
                throw new IllegalArgumentException(
                        "Admission invocation has incomplete identity or "
                                + "existing-member evidence");
            }
            LinkedHashSet<DocumentId> overlap = new LinkedHashSet<>(
                    newDocuments.keySet());
            overlap.retainAll(existingMembers);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "Admission members cannot be both present and absent "
                                + overlap);
            }
        }

        static AdmissionInvocation initial(
                ClosureInvocationInput input,
                List<DocumentId> publicationIdentityMembers) {
            TreeMap<DocumentId, ExactValue> authored = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
            input.snapshot().managedDocuments().forEach(document -> {
                DocumentId id = coordinationId(document.documentId());
                ExactValue exact = ExactValue.verified(document.document());
                if (authored.putIfAbsent(id, exact) != null) {
                    throw new IllegalArgumentException(
                            "Admission input repeats document " + id);
                }
            });
            return new AdmissionInvocation(
                    input,
                    publicationIdentityMembers,
                    authored,
                    Set.of(),
                    Map.of(),
                    AutomaticManagedOccurrenceExpansion.empty());
        }
    }

    private record CapturedAdmissionDocument(
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead head,
            long graphGeneration,
            ExactValue current,
            EmbeddedOnlyLayout layout,
            List<SubscriptionDelta.Entry> activeSubscriptions,
            boolean initialized,
            boolean terminated) {
        private CapturedAdmissionDocument {
            documentId = Objects.requireNonNull(documentId, "documentId");
            head = Objects.requireNonNull(head, "head");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    graphGeneration, "graphGeneration");
            current = Objects.requireNonNull(current, "current");
            layout = Objects.requireNonNull(layout, "layout");
            activeSubscriptions = List.copyOf(Objects.requireNonNull(
                    activeSubscriptions, "activeSubscriptions"));
        }
    }

    private record OccurrenceProjection(
            String occurrenceIdentity,
            String bindingIdentity,
            String bindingPolicyIdentity,
            String sourceDocumentId,
            String sourcePath,
            long activationGeneration,
            String targetDocumentId,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch,
            blue.language.processor.closure.ManagedRepresentationCursor pendingRepresentationCursor) {
        static OccurrenceProjection from(ManagedOccurrenceBinding row) {
            return new OccurrenceProjection(
                    row.occurrenceIdentity(),
                    row.bindingIdentity(),
                    row.bindingPolicyIdentity(),
                    row.sourceDocumentId().value(),
                    row.sourcePath(),
                    row.activationGeneration(),
                    row.targetDocumentId().value(),
                    row.expectedTargetBlueId(),
                    row.active(),
                    row.pendingHistoricalEpoch(), row.pendingRepresentationCursor());
        }
    }

    private record ActiveEdge(
            String occurrenceIdentity,
            String sourceDocumentId,
            String targetDocumentId) {
        static ActiveEdge from(ManagedOccurrenceBinding row) {
            return new ActiveEdge(
                    row.occurrenceIdentity(),
                    row.sourceDocumentId().value(),
                    row.targetDocumentId().value());
        }
    }

    private static final class AdmissionProjectionUnavailableException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private AdmissionProjectionUnavailableException(String message) {
            super(message);
        }
    }
}
