package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionState;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

/** Executes and atomically publishes the bounded all-new admission lane. */
final class ContractsClosureAdmissionAdapter implements AutoCloseable {
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
        ensureOpen();
        ClosureInvocationInput admission = Objects.requireNonNull(
                input, "input");
        CoordinationEngine.AdmissionPolicy temporalPolicy =
                Objects.requireNonNull(policy, "policy");
        ExternalOrderKey frontier = Objects.requireNonNull(
                admissionFrontier, "admissionFrontier");
        requireExactAdmissionInput(admission);
        List<DocumentId> members = coordinationIds(
                admission.snapshot().managedDocuments());
        String publicationIdentity = publicationIdentity(
                admission, temporalPolicy, frontier);

        InMemoryDocumentStore.PublicationSnapshot before =
                documents.publicationSnapshot();
        ContractsClosureAdmissionReceipt prior = before.admissionReceipts()
                .get(publicationIdentity);
        if (prior != null) {
            if (!prior.documentIds().equals(members)) {
                throw new IllegalStateException(
                        "Durable admission receipt member set does not equal "
                                + "the retried closure");
            }
            requireExactlyPresent(members, before);
            reconcileRoutes(members);
            return new ContractsClosureAdmissionReceipt(
                    prior.attempt(),
                    prior.publicationIdentity(),
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .ALREADY_PUBLISHED,
                    prior.documentIds());
        }
        requireAllAbsent(members, before);

        executionObserver.beginAttempt(members.stream()
                .map(DocumentId::value)
                .toList());
        ClosureAttemptResult attempt = contracts.admitClosure(admission);
        if (!attempt.isComplete()
                || !attempt.processResult().commits()) {
            return new ContractsClosureAdmissionReceipt(
                    attempt,
                    publicationIdentity,
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    List.of());
        }
        ClosureProcessResult result = attempt.processResult();
        requireResultBelongsToAdmission(admission, result, members);
        publish(
                admission,
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

    private void publish(
            ClosureInvocationInput input,
            ClosureAttemptResult attempt,
            ClosureProcessResult result,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey frontier,
            String publicationIdentity,
            List<DocumentId> members,
            InMemoryDocumentStore.PublicationSnapshot before) {
        ManagedOccurrenceInventory resultingInventory = mergeAdmissionInventory(
                before.occurrenceInventory(),
                result.occurrenceBindings(),
                new LinkedHashSet<>(members),
                runtime.metrics());
        long inventoryGeneration = result.occurrenceBindings().isEmpty()
                ? before.occurrenceInventoryGeneration()
                : InMemoryDocumentStore.increment(
                        before.occurrenceInventoryGeneration(),
                        "occurrence inventory generation");
        long componentIndexGeneration = InMemoryDocumentStore.increment(
                before.componentIndexGeneration(),
                "component index generation");
        ClosureSubscriptionInventory subscriptions = before
                .closureSubscriptions().apply(result);
        Map<DocumentId, ResultingDocument> resultingDocuments =
                resultingDocuments(result, new LinkedHashSet<>(members));
        Map<DocumentId, ManagedDocumentSnapshot> inputDocuments =
                inputDocuments(input, new LinkedHashSet<>(members));
        Map<DocumentId, Long> gas = gasByDocument(
                result, new LinkedHashSet<>(members));
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
                        before.componentIndexGeneration())
                .stageOccurrenceInventory(
                        resultingInventory,
                        inventoryGeneration,
                        componentIndexGeneration)
                .stageComponentStates(result.resultingComponents())
                .stageClosureAdmissionResult(result)
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
                if (admitted.epoch() != 0L || resulting.epoch() != 0L) {
                    throw new AdmissionProjectionUnavailableException(
                            "New closure admission must publish epoch zero for "
                                    + documentId);
                }
                ExactValue authored = objects.put(
                        ExactValue.fromVerifiedClosureAdmissionInput(
                                input, result, documentId),
                        "verified-closure-admission-input");
                ManagedRootSubscriptionSurface rootSurface = contracts
                        .projectRootSubscriptionSurface(
                                resulting.document());
                RoutingSurface routingSurface = RoutingSurface
                        .fromManagedRootContracts(
                                rootSurface.effectiveRootContracts());
                EmbeddedOnlyLayout layout = layoutBuilder
                        .retainVerifiedClosureRoot(
                                result, documentId, routingSurface);
                ExactValue initialized = layout.semanticRoot();
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
                        gas.getOrDefault(documentId, 0L));
                DocumentSession session = new DocumentSession(
                        documentId,
                        authored,
                        layout,
                        activeSubscriptions,
                        frontier,
                        revision);
                session.restoreCoordinationState(
                        resulting.terminated()
                                ? SessionStatus.TERMINATED
                                : SessionStatus.READY,
                        frontier,
                        0L,
                        0L);
                transaction.expectAbsent(documentId)
                        .stageNewSession(session);
                routeReplacements.add(new OperationRouteIndex.Replacement(
                        documentId,
                        layout.routingSurface(),
                        activeSubscriptions));
            }
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

    private static ManagedOccurrenceInventory mergeAdmissionInventory(
            ManagedOccurrenceInventory before,
            Collection<ManagedOccurrenceBinding> admittedRows,
            Set<DocumentId> admittedMembers,
            EngineMetrics metrics) {
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                before.rows().size());
        ArrayList<ManagedOccurrenceBinding> merged = new ArrayList<>(
                before.rows());
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
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                merged.size());
        return ManagedOccurrenceInventory.of(merged);
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

    private static List<DocumentId> coordinationIds(
            Collection<ManagedDocumentSnapshot> documents) {
        return documents.stream()
                .map(document -> DocumentId.of(document.documentId().value()))
                .sorted()
                .toList();
    }

    private static void requireAllAbsent(
            List<DocumentId> members,
            InMemoryDocumentStore.PublicationSnapshot publication) {
        List<DocumentId> present = members.stream()
                .filter(publication.documentHeads()::containsKey)
                .toList();
        if (present.isEmpty()) {
            return;
        }
        if (present.size() == members.size()) {
            throw new IllegalStateException(
                    "All admission members already exist without the exact "
                            + "typed publication receipt");
        }
        throw new UnsupportedOperationException(
                "Mixed existing/new Contracts closure admission is not "
                        + "supported without complete existing-head fences: "
                        + present);
    }

    private static void requireExactlyPresent(
            List<DocumentId> members,
            InMemoryDocumentStore.PublicationSnapshot publication) {
        if (!publication.documentHeads().keySet().containsAll(members)) {
            throw new IllegalStateException(
                    "Durable admission receipt is missing a published member");
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
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(frontier, "frontier");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "JVM does not provide SHA-256", unavailable);
        }
        updatePublicationIdentityFrame(
                digest, 0, "coordination-contracts-closure-admission-v1");
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
        return "coordination-contracts-closure-admission-v1:sha256:"
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

    private static final class AdmissionProjectionUnavailableException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private AdmissionProjectionUnavailableException(String message) {
            super(message);
        }
    }
}
