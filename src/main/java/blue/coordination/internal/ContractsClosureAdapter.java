package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.ExternalEventCause;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.util.PointerUtils;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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

/**
 * Package-internal Contracts 1.0 execution and atomic-publication boundary.
 *
 * <p>One frozen Root route selection may select several disconnected managed
 * cohorts. Each cohort gets its own exact invocation and publication attempt.
 * Every ordinary work occurrence is still executed by Contracts against its
 * target document as Root; this adapter never supplies a containing document
 * or reverse-containment context.</p>
 *
 * <p>The caller must serialize capture with route, session, and journal
 * publication, as {@link DefaultCoordinationEngine} already does. The adapter
 * additionally rechecks the frozen route generation and lets the store enforce
 * every document-head and topology generation fence at the final swap.</p>
 */
final class ContractsClosureAdapter implements AutoCloseable {
    static final String PLAN_CONSTRUCTIONS =
            "contracts.closure.planConstructions";
    static final String COHORTS_SELECTED =
            "contracts.closure.cohortsSelected";
    static final String DOCUMENT_OPENS =
            "contracts.closure.documentOpens";
    static final String UNRELATED_DOCUMENT_OPENS =
            "contracts.closure.unrelatedDocumentOpens";
    static final String OCCURRENCE_ROWS_EXAMINED =
            "contracts.closure.occurrenceRowsExamined";
    static final String COMPONENT_STATES_READ =
            "contracts.closure.componentStatesRead";
    static final String RESULTING_COMPONENTS =
            "contracts.closure.resultingComponents";
    static final String PLAN_CONSTRUCTION_PHASE =
            "contracts.closure.planConstruction";
    static final String PROCESSOR_PHASE =
            "contracts.closure.processor";
    static final String RESULT_VALIDATION_PHASE =
            "contracts.closure.resultValidation";
    static final String PUBLICATION_PHASE =
            "contracts.closure.publication";

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
    private final Map<String, ContractsManagedDraftPlan> managedDraftPlans =
            new LinkedHashMap<>();
    private Consumer<PublicationFailurePoint> publicationFailureInjector =
            ignored -> { };
    private boolean closed;

    ContractsClosureAdapter(
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

    ContractsClosureAdapter(
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

    /** Captures all exact inputs selected by one immutable Root feeder event. */
    synchronized FrozenBatch capture(TimelineEntry entry) {
        ensureOpen();
        TimelineEntry selectedEntry = Objects.requireNonNull(entry, "entry");
        OperationRouteIndex.FrozenDirectDeliverySelection selection =
                routes.selectDirectDeliveries(selectedEntry);
        if (selection.deliveries().isEmpty()) {
            return new FrozenBatch(
                    selectedEntry, selection.routeGeneration(), List.of());
        }
        return runtime.metrics().timed(PLAN_CONSTRUCTION_PHASE, () -> {
            InMemoryDocumentStore.ClosureTopologySnapshot topology =
                    documents.closureTopologySnapshot();
            List<CohortSelection> selectedCohorts = partitionSelection(
                    topology.componentIndex(),
                    topology.occurrenceInventory(),
                    selection,
                    runtime.metrics());
            LinkedHashSet<DocumentId> selectedMembers = new LinkedHashSet<>();
            selectedCohorts.forEach(cohort -> selectedMembers.addAll(
                    cohort.members()));
            InMemoryDocumentStore.ClosureSnapshot publication =
                    documents.closureSnapshot(selectedMembers, topology);
            List<CohortInvocation> invocations = new ArrayList<>();
            for (CohortSelection selectedCohort : selectedCohorts) {
                invocations.add(captureInvocation(
                        selectedEntry,
                        publication,
                        selectedCohort));
            }
            invocations = applyManagedDraftPlan(
                    selectedEntry, invocations);
            runtime.metrics().add(COHORTS_SELECTED, invocations.size());
            runtime.metrics().increment(PLAN_CONSTRUCTIONS);
            return new FrozenBatch(
                    selectedEntry,
                    selection.routeGeneration(),
                    invocations);
        });
    }

    /** Registers exact SDK host evidence before the entry can be drained. */
    synchronized boolean registerManagedDraftPlan(
            String entryBlueId,
            ContractsManagedDraftPlan plan) {
        ensureOpen();
        String identity = Objects.requireNonNull(
                entryBlueId, "entryBlueId");
        if (identity.isBlank()) {
            throw new IllegalArgumentException(
                    "entryBlueId must not be blank");
        }
        ContractsManagedDraftPlan selected = Objects.requireNonNull(
                plan, "plan");
        ContractsManagedDraftPlan prior = managedDraftPlans.putIfAbsent(
                identity, selected);
        if (prior != null && prior != selected) {
            throw new IllegalStateException(
                    "A managed draft plan is already registered for "
                            + identity);
        }
        return prior == null;
    }

    /** Removes only a plan inserted by a journal append that is rolling back. */
    synchronized void unregisterManagedDraftPlan(
            String entryBlueId,
            ContractsManagedDraftPlan plan) {
        ensureOpen();
        if (!managedDraftPlans.remove(
                Objects.requireNonNull(entryBlueId, "entryBlueId"),
                Objects.requireNonNull(plan, "plan"))) {
            throw new IllegalStateException(
                    "Managed draft rollback lost its exact plan");
        }
    }

    /** Package-internal append-atomicity observation. */
    synchronized boolean hasManagedDraftPlan(String entryBlueId) {
        ensureOpen();
        return managedDraftPlans.containsKey(Objects.requireNonNull(
                entryBlueId, "entryBlueId"));
    }

    /**
     * Rejects a managed-draft plan against the exact current target before its
     * Timeline Entry can consume journal order.
     */
    synchronized void preflightManagedDraftPlan(
            ContractsManagedDraftPlan plan) {
        ensureOpen();
        ContractsManagedDraftPlan selected = Objects.requireNonNull(
                plan, "plan");
        DocumentSession session = documents.require(
                selected.targetDocumentId());
        ExactValue target;
        synchronized (session) {
            target = session.currentRevision().after();
            if (session.epoch() != selected.targetEpoch()
                    || !target.blueId().equals(selected.targetBlueId())) {
                throw stale("Managed expansion target head changed before "
                        + "append " + selected.targetDocumentId());
            }
        }
        validateManagedDraftExpectationPaths(selected, target);
    }

    /** Executes and independently publishes every disconnected cohort. */
    synchronized List<CohortOutcome> processAndPublish(FrozenBatch batch) {
        ensureOpen();
        FrozenBatch frozen = Objects.requireNonNull(batch, "batch");
        List<CohortOutcome> outcomes = new ArrayList<>();
        for (CohortInvocation invocation : frozen.invocations()) {
            outcomes.add(executeAndPublish(frozen, invocation));
        }
        return List.copyOf(outcomes);
    }

    /** Executes and independently publishes exactly one frozen cohort lane. */
    synchronized CohortOutcome executeAndPublish(
            FrozenBatch batch,
            CohortInvocation cohort) {
        ensureOpen();
        FrozenBatch frozen = Objects.requireNonNull(batch, "batch");
        CohortInvocation selected = Objects.requireNonNull(cohort, "cohort");
        if (!frozen.invocations().contains(selected)) {
            throw new IllegalArgumentException(
                    "Cohort invocation does not belong to the frozen batch");
        }
        Optional<ContractsClosurePublicationReceipt> prior =
                publicationReceipt(frozen, selected);
        if (prior.isPresent()) {
            ContractsClosurePublicationReceipt receipt = prior.get();
            if (receipt.commits()) {
                reconcilePublication(frozen, selected);
            }
            return outcome(receipt, true);
        }
        requireRouteSelectionCurrent(frozen, selected);
        executionObserver.beginAttempt(selected.members().stream()
                .map(DocumentId::value)
                .toList());
        ClosureAttemptResult attempt = runtime.metrics().timed(
                PROCESSOR_PHASE,
                () -> contracts.processClosure(selected.input()));
        long validationStarted = System.nanoTime();
        String identity;
        ContractsClosurePublicationReceipt receipt;
        try {
            if (attempt.isComplete()) {
                runtime.metrics().add(
                        RESULTING_COMPONENTS,
                        attempt.processResult()
                                .resultingComponents().size());
            }
            identity = publicationIdentity(frozen, selected);
            if (!attempt.isComplete()) {
                return new CohortOutcome(
                        selected.members(), attempt, false, identity, false);
            }
            if (!isDurablyTerminalStatus(
                    attempt.processResult().status())) {
                throw new ProjectionUnavailableException(
                        "Contracts capability failure is not a durable feeder "
                                + "disposition and must be retried after the "
                                + "capability is available");
            }
            receipt = new ContractsClosurePublicationReceipt(
                    identity,
                    selected.members(),
                    attempt);
        } finally {
            runtime.metrics().addNanos(
                    RESULT_VALIDATION_PHASE,
                    System.nanoTime() - validationStarted);
        }
        runtime.metrics().timed(PUBLICATION_PHASE, () -> {
            if (receipt.commits()) {
                publish(frozen, selected, receipt);
            } else {
                publishNonCommit(frozen, selected, receipt);
            }
        });
        return outcome(receipt, false);
    }

    /** Exact implementation evidence from the latest completed execution. */
    synchronized Optional<ClosureImplementationEvidence>
            lastExecutionEvidence() {
        ensureOpen();
        return executionObserver.lastEvidence();
    }

    static boolean isDurablyTerminalStatus(ProcessorStatus status) {
        return Objects.requireNonNull(status, "status")
                != ProcessorStatus.CAPABILITY_FAILURE;
    }

    private static CohortOutcome outcome(
            ContractsClosurePublicationReceipt receipt,
            boolean replayed) {
        return new CohortOutcome(
                receipt.documentIds(),
                receipt.attempt(),
                receipt.commits(),
                receipt.publicationIdentity(),
                replayed);
    }

    synchronized Optional<ContractsClosurePublicationReceipt>
            publicationReceipt(
                    FrozenBatch batch,
                    CohortInvocation cohort) {
        ensureOpen();
        FrozenBatch frozen = requireCohortHandle(batch, cohort);
        String identity = publicationIdentity(frozen, cohort);
        ContractsClosurePublicationReceipt receipt = documents
                .closurePublicationReceipt(identity)
                .orElse(null);
        if (receipt == null) {
            if (documents.hasPublicationReceipt(identity)) {
                throw new IllegalStateException(
                        "Closure publication has no typed replay receipt "
                                + identity);
            }
            return Optional.empty();
        }
        if (!receipt.documentIds().equals(cohort.members())) {
            throw new IllegalStateException(
                    "Typed closure receipt does not belong to the frozen "
                            + "cohort " + identity);
        }
        return Optional.of(receipt);
    }

    /** Stable pre-execution idempotency key for one frozen cohort handle. */
    synchronized String publicationIdentityFor(
            FrozenBatch batch,
            CohortInvocation cohort) {
        ensureOpen();
        FrozenBatch frozen = requireCohortHandle(batch, cohort);
        return publicationReceipt(frozen, cohort)
                .map(ContractsClosurePublicationReceipt::publicationIdentity)
                .orElseGet(() -> publicationIdentity(frozen, cohort));
    }

    synchronized void onPublicationFailurePoint(
            Consumer<PublicationFailurePoint> injector) {
        ensureOpen();
        publicationFailureInjector = Objects.requireNonNull(
                injector, "injector");
    }

    private void publishNonCommit(
            FrozenBatch batch,
            CohortInvocation invocation,
            ContractsClosurePublicationReceipt receipt) {
        ClosureProcessResult result = receipt.attempt().processResult();
        if (!receipt.publicationIdentity().equals(
                publicationIdentity(batch, invocation))) {
            throw new IllegalArgumentException(
                    "Process receipt identity does not identify this cohort");
        }
        requireTerminalResult(invocation, result);
        if (result.commits()) {
            throw new IllegalArgumentException(
                    "Receipt-only publication requires a non-commit result");
        }
        InMemoryDocumentStore.ClosureSnapshot current =
                documents.closureSnapshot(invocation.existingMemberSet());
        requireCohortStillCurrent(invocation, current);
        MultiDocumentPublicationTransaction transaction = documents
                .beginAtomicPublication(
                        receipt.publicationIdentity(),
                        current.occurrenceInventoryGeneration(),
                        current.componentIndexGeneration());
        for (CapturedDocument document : invocation.documents().values()) {
            transaction.expectHead(
                    document.documentId(),
                    document.head().epoch(),
                    document.head().blueId());
        }
        if (invocation.managedExpansion()) {
            invocation.newMemberSet().forEach(transaction::expectAbsent);
            transaction.stageManagedExpansionInput(invocation.input());
        }
        invocation.input().snapshot().components().forEach(
                component -> {
                    boolean existing = component.orderedMemberDocumentIds()
                            .stream().allMatch(member -> invocation
                                    .existingMemberSet().contains(
                                            coordinationId(member)));
                    if (existing) {
                        transaction.expectComponentState(component);
                    }
                });
        transaction.stageClosurePublicationReceipt(receipt);
        requireRouteSelectionCurrent(batch, invocation);
        transaction.commit();
    }

    private static void requireTerminalResult(
            CohortInvocation invocation,
            ClosureProcessResult result) {
        if (!result.invocationIdentity().equals(
                invocation.input().invocationIdentity())
                || !result.inputClosureIdentity().equals(
                        invocation.input().snapshot().closureIdentity())) {
            throw new IllegalStateException(
                    "Closure result does not belong to the captured input");
        }
        resultingDocuments(result, invocation.memberSet());
    }

    /** Captures, executes, and publishes one event under caller serialization. */
    synchronized List<CohortOutcome> processAndPublish(TimelineEntry entry) {
        return processAndPublish(capture(entry));
    }

    synchronized boolean hasPublicationReceipt(
            FrozenBatch batch,
            CohortInvocation cohort) {
        ensureOpen();
        FrozenBatch frozen = requireCohortHandle(batch, cohort);
        return publicationReceipt(frozen, cohort).isPresent();
    }

    /**
     * Rebuilds the disposable route cache from durable post-commit sessions
     * when a receipt proves that this cohort was already atomically published.
     */
    synchronized boolean reconcilePublication(
            FrozenBatch batch,
            CohortInvocation cohort) {
        ensureOpen();
        FrozenBatch frozen = requireCohortHandle(batch, cohort);
        Optional<ContractsClosurePublicationReceipt> receipt =
                publicationReceipt(frozen, cohort);
        if (receipt.isEmpty()) {
            return false;
        }
        if (!receipt.get().commits()) {
            return true;
        }
        List<OperationRouteIndex.Replacement> replacements =
                new ArrayList<>();
        for (DocumentId documentId : cohort.members()) {
            DocumentSession session = documents.require(documentId);
            synchronized (session) {
                replacements.add(new OperationRouteIndex.Replacement(
                        documentId,
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
            }
        }
        routes.prepareReplacement(replacements).publish();
        activeSourceTimelines.refresh(cohort.members(), documents);
        return true;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            managedDraftPlans.clear();
            contracts.close();
        }
    }

    static List<CohortSelection> partitionSelection(
            ProcessEmbeddedComponentIndex componentIndex,
            ManagedOccurrenceInventory occurrenceInventory,
            OperationRouteIndex.FrozenDirectDeliverySelection selection) {
        return partitionSelection(
                componentIndex, occurrenceInventory, selection, null);
    }

    private static List<CohortSelection> partitionSelection(
            ProcessEmbeddedComponentIndex componentIndex,
            ManagedOccurrenceInventory occurrenceInventory,
            OperationRouteIndex.FrozenDirectDeliverySelection selection,
            EngineMetrics metrics) {
        ProcessEmbeddedComponentIndex index = Objects.requireNonNull(
                componentIndex, "componentIndex");
        ManagedOccurrenceInventory inventory = Objects.requireNonNull(
                occurrenceInventory, "occurrenceInventory");
        OperationRouteIndex.FrozenDirectDeliverySelection frozen =
                Objects.requireNonNull(selection, "selection");
        TreeMap<DocumentId, ConnectedSelection> groups = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Set<DocumentId> alreadySelected = new LinkedHashSet<>();
        long occurrenceRowsExamined = 0L;
        List<DocumentId> directTargets = frozen.documentIds().stream()
                .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .toList();
        for (DocumentId directTarget : directTargets) {
            if (alreadySelected.contains(directTarget)) {
                continue;
            }
            ConnectedSelection connected = connectedSelection(
                    index, inventory, directTarget);
            groups.put(connected.members().get(0), connected);
            alreadySelected.addAll(connected.members());
            occurrenceRowsExamined = Math.addExact(
                    occurrenceRowsExamined, connected.rowsExamined());
        }
        if (metrics != null) {
            metrics.add(OCCURRENCE_ROWS_EXAMINED, occurrenceRowsExamined);
        }
        List<CohortSelection> result = new ArrayList<>();
        for (ConnectedSelection connected : groups.values()) {
            List<DocumentId> members = connected.members();
            Set<DocumentId> memberSet = new LinkedHashSet<>(members);
            TreeMap<DocumentId, ProcessEmbeddedComponentIndex.Cohort>
                    activeCohorts = new TreeMap<>(
                            EmbeddingBinding.DOCUMENT_ORDER);
            for (DocumentId member : members) {
                ProcessEmbeddedComponentIndex.Cohort active =
                        index.cohort(member);
                activeCohorts.putIfAbsent(
                        active.members().get(0), active);
            }
            List<ProcessEmbeddedComponentIndex.Component> components =
                    activeCohorts.values().stream()
                            .flatMap(active -> active.components().stream())
                            .toList();
            List<OperationRouteIndex.FrozenDirectDelivery> deliveries =
                    frozen.deliveries().stream()
                            .filter(delivery -> memberSet.contains(
                                    delivery.documentId()))
                            .toList();
            result.add(new CohortSelection(
                    members,
                    components,
                    connected.occurrences(),
                    deliveries));
        }
        return List.copyOf(result);
    }

    private static ConnectedSelection connectedSelection(
            ProcessEmbeddedComponentIndex index,
            ManagedOccurrenceInventory inventory,
            DocumentId start) {
        TreeMap<DocumentId, Boolean> discovered = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Deque<DocumentId> pending = new ArrayDeque<>();
        discovered.put(Objects.requireNonNull(start, "start"), Boolean.TRUE);
        pending.addLast(start);
        Map<String, ManagedOccurrenceBinding> occurrences =
                new LinkedHashMap<>();
        long rowsExamined = 0L;
        while (!pending.isEmpty()) {
            DocumentId current = pending.removeFirst();
            for (DocumentId activeMember : index.cohort(current).members()) {
                if (discovered.putIfAbsent(
                        activeMember, Boolean.TRUE) == null) {
                    pending.addLast(activeMember);
                }
            }
            for (ManagedOccurrenceBinding row
                    : inventory.rowsTouching(current)) {
                if (occurrences.putIfAbsent(
                        row.occurrenceIdentity(), row) != null) {
                    continue;
                }
                rowsExamined = Math.addExact(rowsExamined, 1L);
                DocumentId source = coordinationId(row.sourceDocumentId());
                DocumentId target = coordinationId(row.targetDocumentId());
                if (discovered.putIfAbsent(source, Boolean.TRUE) == null) {
                    pending.addLast(source);
                }
                if (discovered.putIfAbsent(target, Boolean.TRUE) == null) {
                    pending.addLast(target);
                }
            }
        }
        ArrayList<ManagedOccurrenceBinding> canonicalOccurrences =
                new ArrayList<>(occurrences.values());
        canonicalOccurrences.sort(Comparator.naturalOrder());
        return new ConnectedSelection(
                new ArrayList<>(discovered.keySet()),
                canonicalOccurrences,
                rowsExamined);
    }

    private CohortInvocation captureInvocation(
            TimelineEntry entry,
            InMemoryDocumentStore.ClosureSnapshot publication,
            CohortSelection selection) {
        TreeMap<DocumentId, CapturedDocument> captured = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Set<DocumentId> allowedMembers = new LinkedHashSet<>(
                selection.members());
        for (DocumentId documentId : selection.members()) {
            captured.put(documentId, captureDocument(
                    documentId,
                    publication.requireHead(documentId),
                    allowedMembers));
        }

        List<ComponentSnapshot> components = captureComponents(
                publication, selection.components(), captured);
        Map<DocumentId, Long> componentGenerations = new LinkedHashMap<>();
        for (ComponentSnapshot component : components) {
            component.orderedMemberDocumentIds().forEach(member ->
                    componentGenerations.put(
                            coordinationId(member),
                            component.componentGeneration()));
        }

        List<ManagedDocumentSnapshot> managedDocuments = new ArrayList<>();
        List<blue.language.processor.closure.DocumentId> publicRoots =
                new ArrayList<>();
        for (CapturedDocument document : captured.values()) {
            boolean publicRoot = profile.isPublicRoot(document.documentId());
            blue.language.processor.closure.DocumentId closureDocumentId =
                    closureId(document.documentId());
            managedDocuments.add(new ManagedDocumentSnapshot(
                    closureDocumentId,
                    document.head().blueId(),
                    document.current().copyNode(),
                    document.initialized(),
                    document.terminated(),
                    publicRoot,
                    document.head().epoch(),
                    requireComponentGeneration(
                            componentGenerations, document.documentId())));
            if (publicRoot) {
                publicRoots.add(closureDocumentId);
            }
        }

        List<ManagedOccurrenceBinding> occurrences = selection.occurrences();
        long graphGeneration = publication.graphGenerations()
                .requireCohortGeneration(captured.keySet());
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        graphGeneration,
                        managedDocuments,
                        occurrences,
                        components,
                        publicRoots);
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(
                entry.exactEvent().copyNode(),
                entry.blueId(),
                entry.sourceOrderKey(),
                environment.externalOrderPolicyIdentity());
        List<DirectLogicalDelivery> deliveries = selection.deliveries()
                .stream()
                .map(OperationRouteIndex.FrozenDirectDelivery
                        ::toContractsEvidence)
                .toList();
        ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(
                snapshot,
                cause,
                deliveries,
                profile.executionPolicy(),
                environment);
        return new CohortInvocation(
                selection.members(),
                deliveries,
                input,
                captured,
                null);
    }

    private List<CohortInvocation> applyManagedDraftPlan(
            TimelineEntry entry,
            List<CohortInvocation> invocations) {
        ContractsManagedDraftPlan plan = managedDraftPlans.get(
                entry.blueId());
        if (plan == null) {
            return invocations;
        }
        ArrayList<CohortInvocation> result = new ArrayList<>(invocations);
        int selected = -1;
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).memberSet().contains(
                    plan.targetDocumentId())) {
                if (selected >= 0) {
                    throw new IllegalStateException(
                            "Managed expansion target belongs to more than "
                                    + "one captured cohort");
                }
                selected = index;
            }
        }
        if (selected < 0) {
            throw new IllegalStateException(
                    "Managed expansion target was not selected by its exact "
                            + "operation " + plan.targetDocumentId());
        }
        CohortInvocation base = result.get(selected);
        long presentDrafts = plan.drafts().keySet().stream()
                .filter(documentId -> documents.find(documentId).isPresent())
                .count();
        if (presentDrafts == plan.drafts().size()) {
            TreeMap<DocumentId, Boolean> replayMembers = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
            base.members().forEach(member -> replayMembers.put(
                    member, Boolean.TRUE));
            plan.drafts().keySet().forEach(member -> replayMembers.put(
                    member, Boolean.TRUE));
            CohortInvocation replay = new CohortInvocation(
                    List.copyOf(replayMembers.keySet()),
                    base.directDeliveries(),
                    base.input(),
                    base.documents(),
                    plan);
            String identity = publicationIdentity(
                    new FrozenBatch(entry, 0L, List.of(replay)), replay);
            ContractsClosurePublicationReceipt receipt = documents
                    .closurePublicationReceipt(identity)
                    .orElse(null);
            if (receipt != null && receipt.documentIds().equals(
                    replay.members())) {
                // The store swap completed before feeder progress was
                // recorded. Ordinary recapture plus the typed receipt now
                // drives route-cache reconciliation.
                return invocations;
            }
            throw stale("Managed draft lineage already exists before "
                    + entry.blueId());
        }
        if (presentDrafts != 0L) {
            throw stale("Managed expansion is only partially durable for "
                    + entry.blueId());
        }
        CapturedDocument target = base.documents().get(
                plan.targetDocumentId());
        if (target == null
                || target.head().epoch() != plan.targetEpoch()
                || !target.head().blueId().equals(plan.targetBlueId())) {
            throw stale("Managed expansion target head changed before capture "
                    + plan.targetDocumentId());
        }
        result.set(selected, augmentWithManagedDrafts(
                base, plan, entry.exactRequest()));
        return List.copyOf(result);
    }

    private CohortInvocation augmentWithManagedDrafts(
            CohortInvocation base,
            ContractsManagedDraftPlan plan,
            ExactValue exactRequest) {
        ClosureInvocationInput original = base.input();
        validateManagedDraftDeclarations(base, plan, exactRequest);

        ArrayList<blue.language.processor.closure.DocumentId> members =
                new ArrayList<>(original.snapshot().managedDocuments()
                        .stream()
                        .map(ManagedDocumentSnapshot::documentId)
                        .toList());
        plan.drafts().keySet().forEach(documentId -> members.add(
                closureId(documentId)));

        ArrayList<ManagedOccurrenceBinding> rows = new ArrayList<>(
                original.snapshot().occurrences());
        for (ContractsManagedDraftPlan.ExpectedOccurrence expectation
                : plan.expectedOccurrences()) {
            ContractsManagedDraftPlan.ManagedDraft draft = plan.drafts().get(
                    expectation.targetDocumentId());
            rows.add(ManagedOccurrenceBinding.derived(
                    original.environment().managedBindingPolicyIdentity(),
                    closureId(plan.targetDocumentId()),
                    ScopeAddress.embedded(expectation.path(), 1L),
                    closureId(expectation.targetDocumentId()),
                    draft.initial().blueId(),
                    false,
                    null));
        }

        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                bodies = new LinkedHashMap<>();
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        LinkedHashMap<blue.language.processor.closure.DocumentId,
                ManagedDocumentSnapshot> existing = new LinkedHashMap<>();
        for (ManagedDocumentSnapshot document
                : original.snapshot().managedDocuments()) {
            bodies.put(document.documentId(), document.document());
            generations.put(
                    document.documentId(), document.componentGeneration());
            existing.put(document.documentId(), document);
        }
        plan.drafts().forEach((documentId, draft) -> {
            blue.language.processor.closure.DocumentId draftId = closureId(
                    documentId);
            bodies.put(draftId, draft.initial().copyNode());
            generations.put(draftId, 1L);
        });

        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                members, rows);
        ComponentFinalizationResult finalization =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, rows));

        ArrayList<ManagedDocumentSnapshot> compiledDocuments =
                new ArrayList<>();
        finalization.documents().forEach((documentId, exact) -> {
            ManagedDocumentSnapshot prior = existing.get(documentId);
            if (prior != null) {
                if (!prior.blueId().equals(exact.blueId())) {
                    throw new IllegalStateException(
                            "Managed expansion changed an existing input head "
                                    + documentId);
                }
                compiledDocuments.add(new ManagedDocumentSnapshot(
                        documentId,
                        exact.blueId(),
                        exact.document(),
                        prior.initialized(),
                        prior.terminated(),
                        prior.publicRoot(),
                        prior.epoch(),
                        exact.componentGeneration()));
                return;
            }
            ContractsManagedDraftPlan.ManagedDraft draft = plan.drafts().get(
                    coordinationId(documentId));
            if (draft == null
                    || !draft.initial().blueId().equals(exact.blueId())) {
                throw new IllegalStateException(
                        "Managed draft input is not an exact isolated Root "
                                + documentId);
            }
            compiledDocuments.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    false,
                    false,
                    false,
                    0L,
                    exact.componentGeneration()));
        });
        List<ComponentSnapshot> components = finalization.components()
                .stream()
                .map(component -> component.component())
                .toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        original.snapshot().graphGeneration(),
                        compiledDocuments,
                        finalization.finalizedGraph().bindings(),
                        components,
                        original.snapshot().publicRootDocumentIds());
        ClosureInvocationInput expanded = ClosureEvidenceFactory
                .processClosure(
                        snapshot,
                        original.cause(),
                        original.directDeliveries(),
                        original.executionPolicy(),
                        original.environment());
        return new CohortInvocation(
                coordinationIds(graph.documentIds()),
                base.directDeliveries(),
                expanded,
                base.documents(),
                plan);
    }

    private void validateManagedDraftDeclarations(
            CohortInvocation base,
            ContractsManagedDraftPlan plan,
            ExactValue exactRequest) {
        CapturedDocument target = base.documents().get(
                plan.targetDocumentId());
        for (DocumentId documentId : plan.drafts().keySet()) {
            if (profile.isPublicRoot(documentId)) {
                throw new IllegalArgumentException(
                        "Managed draft expansion cannot create a public Root "
                                + documentId);
            }
        }
        plan.managedRequestFields().forEach((field, documentId) -> {
            String requestBlueId = exactRequest.canonicalBlueIdAt(
                    PointerUtils.appendPointer("/", field));
            String draftBlueId = plan.drafts().get(documentId)
                    .initial().blueId();
            if (!draftBlueId.equals(requestBlueId)) {
                throw new IllegalArgumentException(
                        "Managed request field " + field
                                + " does not retain exact draft "
                                + documentId);
            }
        });
        validateManagedDraftExpectationPaths(plan, target.current());
    }

    private void validateManagedDraftExpectationPaths(
            ContractsManagedDraftPlan plan,
            ExactValue target) {
        EffectiveFragmentationCatalog catalog = runtime
                .effectiveFragmentationCatalog(Objects.requireNonNull(
                        target, "target").blueId());
        for (ContractsManagedDraftPlan.ExpectedOccurrence expectation
                : plan.expectedOccurrences()) {
            ArrayList<String> matches = new ArrayList<>();
            for (EmbeddedScopePlanView scope
                    : catalog.scopePlansByScope().values()) {
                for (String declaration
                        : scope.explicitDeclarationPaths()) {
                    String absolute = PointerUtils.resolvePointer(
                            scope.scopePath(), declaration);
                    if (absolute.equals(expectation.path())) {
                        matches.add("path " + absolute);
                    }
                }
                for (String declaration
                        : scope.collectionDeclarationPaths()) {
                    String absolute = PointerUtils.resolvePointer(
                            scope.scopePath(), declaration);
                    if (isDirectCollectionMember(
                            absolute, expectation.path())) {
                        matches.add("collectionPath " + absolute);
                    }
                }
            }
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "Expected managed occurrence "
                                + plan.targetDocumentId()
                                + expectation.path()
                                + " must match exactly one effective Process "
                                + "Embedded declaration; found " + matches);
            }
        }
    }

    private static boolean isDirectCollectionMember(
            String collectionPath,
            String candidatePath) {
        List<String> collection = JsonPointer.split(collectionPath);
        List<String> candidate = JsonPointer.split(candidatePath);
        return candidate.size() == collection.size() + 1
                && candidate.subList(0, collection.size()).equals(collection);
    }

    private CapturedDocument captureDocument(
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead expectedHead,
            Set<DocumentId> allowedMembers) {
        runtime.metrics().increment(DOCUMENT_OPENS);
        if (!Objects.requireNonNull(allowedMembers, "allowedMembers")
                .contains(documentId)) {
            runtime.metrics().increment(UNRELATED_DOCUMENT_OPENS);
            runtime.metrics().increment("temporal.unrelatedDocumentReads");
        }
        DocumentSession session = documents.require(documentId);
        synchronized (session) {
            InMemoryDocumentStore.DocumentHead actualHead =
                    new InMemoryDocumentStore.DocumentHead(
                            session.epoch(),
                            session.currentRevision().after().blueId());
            if (!expectedHead.equals(actualHead)) {
                throw stale("Document head changed during closure capture for "
                        + documentId);
            }
            ExactValue current = session.currentRevision().after();
            if (!current.blueId().equals(session.layout().rootBlueId())) {
                throw new IllegalStateException(
                        "Session layout disagrees with the durable head for "
                                + documentId);
            }
            boolean initialized = runtime.documentProcessor().isInitialized(
                    current.copyNode());
            boolean terminated = session.status() == SessionStatus.TERMINATED;
            return new CapturedDocument(
                    documentId,
                    actualHead,
                    current,
                    session.layout(),
                    session.activeSubscriptions(),
                    session.nextApplicationOrder(),
                    initialized,
                    terminated);
        }
    }

    private List<ComponentSnapshot> captureComponents(
            InMemoryDocumentStore.ClosureSnapshot publication,
            List<ProcessEmbeddedComponentIndex.Component> indexedComponents,
            Map<DocumentId, CapturedDocument> documentsById) {
        Map<List<DocumentId>, ComponentSnapshot> byMembers =
                new LinkedHashMap<>();
        for (ComponentSnapshot component : publication.componentStates()) {
            runtime.metrics().increment(COMPONENT_STATES_READ);
            List<DocumentId> members = coordinationIds(
                    component.orderedMemberDocumentIds());
            if (byMembers.putIfAbsent(members, component) != null) {
                throw new IllegalStateException(
                        "Duplicate durable component state for " + members);
            }
        }
        List<ComponentSnapshot> result = new ArrayList<>();
        for (ProcessEmbeddedComponentIndex.Component indexed
                : indexedComponents) {
            ComponentSnapshot state = byMembers.get(indexed.members());
            if (state == null) {
                throw new ProjectionUnavailableException(
                        "No Contracts component state is durable for "
                                + indexed.members());
            }
            ComponentKind expectedKind = indexed.cyclic()
                    ? ComponentKind.CYCLIC : ComponentKind.ACYCLIC;
            if (state.kind() != expectedKind) {
                throw new IllegalStateException(
                        "Durable component kind disagrees with topology for "
                                + indexed.members());
            }
            for (int index = 0; index < indexed.members().size(); index++) {
                DocumentId member = indexed.members().get(index);
                CapturedDocument document = documentsById.get(member);
                if (document == null
                        || !document.head().blueId().equals(
                        state.orderedMemberBlueIds().get(index))) {
                    throw stale("Durable component state is stale for "
                            + member);
                }
            }
            result.add(state);
        }
        return List.copyOf(result);
    }

    private void publish(
            FrozenBatch batch,
            CohortInvocation invocation,
            ContractsClosurePublicationReceipt receipt) {
        ClosureProcessResult result = receipt.attempt().processResult();
        if (!receipt.publicationIdentity().equals(
                publicationIdentity(batch, invocation))) {
            throw new IllegalArgumentException(
                    "Process receipt identity does not identify this cohort");
        }
        requirePublishableResult(batch, invocation, result);
        InMemoryDocumentStore.ClosureSnapshot current =
                documents.closureSnapshot(invocation.existingMemberSet());
        requireCohortStillCurrent(invocation, current);
        ManagedOccurrenceInventory resultingInventory = mergeInventory(
                current.occurrenceInventory(),
                invocation.memberSet(),
                result.occurrenceBindings());
        long resultingInventoryGeneration = transitionGeneration(
                current.occurrenceInventoryGeneration(),
                !sameInventory(
                        current.occurrenceInventory(),
                        resultingInventory),
                "occurrence inventory generation");
        boolean topologyChanged = !sameActiveTopology(
                current.occurrenceInventory(),
                resultingInventory);
        long resultingComponentIndexGeneration = transitionGeneration(
                current.componentIndexGeneration(),
                topologyChanged,
                "component index generation");
        String publicationIdentity = receipt.publicationIdentity();
        MultiDocumentPublicationTransaction transaction = documents
                .beginAtomicPublication(
                        publicationIdentity,
                        current.occurrenceInventoryGeneration(),
                        current.componentIndexGeneration());
        for (CapturedDocument document
                : invocation.documents().values()) {
            transaction.expectHead(
                    document.documentId(),
                    document.head().epoch(),
                    document.head().blueId());
        }
        if (invocation.managedExpansion()) {
            invocation.newMemberSet().forEach(transaction::expectAbsent);
        }
        invocation.input().snapshot().components().forEach(
                component -> {
                    boolean existing = component.orderedMemberDocumentIds()
                            .stream().allMatch(member -> invocation
                                    .existingMemberSet().contains(
                                            coordinationId(member)));
                    if (existing) {
                        transaction.expectComponentState(component);
                    }
                });
        if (invocation.managedExpansion() || !sameInventory(
                current.occurrenceInventory(),
                resultingInventory)) {
            transaction.stageOccurrenceInventory(
                    resultingInventory,
                    resultingInventoryGeneration,
                    resultingComponentIndexGeneration);
        }
        transaction.stageComponentStates(result.resultingComponents());
        if (invocation.managedExpansion()) {
            transaction.stageManagedExpansionResult(
                    invocation.input(), result);
        } else {
            transaction.stageClosureGraphGeneration(result);
            transaction.stageClosureSubscriptionDeltas(result);
        }
        transaction.stageOutbox(result.publicEvents());
        transaction.stageCheckpointEvidence(result.checkpointWrites());
        transaction.stageClosurePublicationReceipt(receipt);

        Map<DocumentId, ResultingDocument> resultingDocuments =
                resultingDocuments(result, invocation.memberSet());
        Map<DocumentId, Long> gasByDocument = gasByDocument(
                result, resultingDocuments.keySet());
        ContractsStructuralWorkMetrics.recordGlobalPasses(
                runtime.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
                3L,
                Math.multiplyExact(
                        current.closureSubscriptions().states().size(),
                        3L));
        ClosureSubscriptionInventory resultingClosureSubscriptions =
                current.closureSubscriptions().apply(result);
        WholeObjectStore.Mark objectMark = objects.mark();
        boolean storeCommitted = false;
        try {
            List<OperationRouteIndex.Replacement> routeReplacements =
                    new ArrayList<>();
            for (Map.Entry<DocumentId, ResultingDocument> entry
                    : resultingDocuments.entrySet()) {
                CapturedDocument before = invocation.documents().get(
                        entry.getKey());
                ResultingDocument after = entry.getValue();
                if (before == null) {
                    ContractsManagedDraftPlan.ManagedDraft draft = invocation
                            .managedDraftPlan().drafts().get(entry.getKey());
                    if (draft == null || after.epoch() != 0L
                            || !after.initialized()) {
                        throw new ProjectionUnavailableException(
                                "Managed expansion did not initialize new Root "
                                        + entry.getKey());
                    }
                    ManagedRootSubscriptionSurface projected = contracts
                            .projectRootSubscriptionSurface(after.document());
                    RoutingSurface routingSurface = RoutingSurface
                            .fromManagedRootContracts(
                                    projected.effectiveRootContracts());
                    EmbeddedOnlyLayout layout = layoutBuilder
                            .retainVerifiedClosureRoot(
                                    result,
                                    entry.getKey(),
                                    routingSurface);
                    requireExactRootSubscriptionSurface(
                            entry.getKey(),
                            projected,
                            subscriptionStatesFor(
                                    resultingClosureSubscriptions,
                                    entry.getKey()));
                    List<SubscriptionDelta.Entry> activeSubscriptions =
                            activateInitialSubscriptions(
                                    projected.externalSubscriptions(),
                                    batch.entry().sourceOrderKey());
                    CheckpointDomainEvidence.retainAll(
                            activeSubscriptions, objects);
                    ExactValue authored = objects.put(
                            draft.initial(),
                            "verified-managed-expansion-input");
                    List<Node> emitted = result.publicEvents().stream()
                            .filter(event -> event.publicRootDocumentId()
                                    .value().equals(entry.getKey().value()))
                            .map(PublicEventOccurrence::event)
                            .toList();
                    DocumentRevision revision = new DocumentRevision(
                            entry.getKey(),
                            0L,
                            0L,
                            DocumentRevision.Kind.INITIALIZATION,
                            authored,
                            layout.semanticRoot(),
                            null,
                            batch.entry().sourceOrderKey(),
                            batch.entry().blueId(),
                            null,
                            emitted,
                            gasByDocument.getOrDefault(
                                    entry.getKey(), 0L));
                    DocumentSession session = new DocumentSession(
                            entry.getKey(),
                            authored,
                            layout,
                            activeSubscriptions,
                            batch.entry().sourceOrderKey(),
                            revision);
                    session.restoreCoordinationState(
                            after.terminated()
                                    ? SessionStatus.TERMINATED
                                    : SessionStatus.READY,
                            batch.entry().sourceOrderKey(),
                            0L,
                            0L);
                    transaction.stageNewSession(session);
                    routeReplacements.add(
                            new OperationRouteIndex.Replacement(
                                    entry.getKey(),
                                    layout.routingSurface(),
                                    activeSubscriptions));
                    continue;
                }
                boolean changed = requiresDocumentPublication(before, after);
                ManagedRootSubscriptionSurface projected = contracts
                        .projectRootSubscriptionSurface(after.document());
                RoutingSurface routingSurface = RoutingSurface
                        .fromManagedRootContracts(
                                projected.effectiveRootContracts());
                EmbeddedOnlyLayout layout = changed
                        ? layoutBuilder.retainVerifiedClosureRoot(
                                result,
                                entry.getKey(),
                                before.layout(),
                                routingSurface)
                        : before.layout();
                requireExactRootSubscriptionSurface(
                        entry.getKey(),
                        projected,
                        subscriptionStatesFor(
                                resultingClosureSubscriptions,
                                entry.getKey()));
                List<SubscriptionDelta.Entry> activeSubscriptionsAfter;
                if (changed) {
                    SubscriptionDelta routeDelta = routeDelta(
                            before.activeSubscriptions(),
                            projected.externalSubscriptions(),
                            after.epoch(),
                            batch.entry().sourceOrderKey());
                    activeSubscriptionsAfter = DocumentTransitionProcessor
                            .applyManagedRootSubscriptionDelta(
                                    before.activeSubscriptions(),
                                    routeDelta,
                                    after.epoch(),
                                    batch.entry().sourceOrderKey(),
                                    runtime.metrics());
                } else {
                    requireUnchangedRouteSurface(
                            entry.getKey(),
                            before.activeSubscriptions(),
                            projected.externalSubscriptions());
                    activeSubscriptionsAfter = before.activeSubscriptions();
                }
                CheckpointDomainEvidence.retainAll(
                        activeSubscriptionsAfter, objects);
                routeReplacements.add(new OperationRouteIndex.Replacement(
                        entry.getKey(),
                        layout.routingSurface(),
                        activeSubscriptionsAfter));
                if (!changed) {
                    continue;
                }
                ExactValue exact = objects.put(
                        layout.semanticRoot(),
                        "closure-document-revision");
                List<blue.language.model.Node> emitted = result.publicEvents()
                        .stream()
                        .filter(event -> event.publicRootDocumentId().value()
                                .equals(entry.getKey().value()))
                        .map(PublicEventOccurrence::event)
                        .toList();
                DocumentRevision revision = new DocumentRevision(
                        entry.getKey(),
                        after.epoch(),
                        before.nextApplicationOrder(),
                        DocumentRevision.Kind.TIMELINE_ENTRY,
                        before.current(),
                        exact,
                        batch.entry(),
                        null,
                        emitted,
                        gasByDocument.getOrDefault(entry.getKey(), 0L));
                transaction.stageDocument(
                        revision,
                        layout,
                        batch.entry().sourceOrderKey(),
                        activeSubscriptionsAfter,
                        publicationIdentity + "|"
                                + entry.getKey().value());
            }
            requireRouteSelectionCurrent(batch, invocation);
            OperationRouteIndex.PreparedReplacement preparedRoutes =
                    routes.prepareReplacement(routeReplacements);
            transaction.commit();
            storeCommitted = true;
            publicationFailureInjector.accept(
                    PublicationFailurePoint
                            .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH);
            preparedRoutes.publish();
            activeSourceTimelines.refresh(invocation.members(), documents);
            objects.commit(objectMark);
        } catch (RuntimeException failure) {
            if (storeCommitted) {
                // Durable sessions may already reference these exact values.
                // Preserve them so receipt reconciliation can safely rebuild
                // an interrupted route-cache publication.
                objects.commit(objectMark);
            } else {
                objects.rollbackTo(objectMark);
            }
            throw failure;
        }
    }

    private static void requirePublishableResult(
            FrozenBatch batch,
            CohortInvocation invocation,
            ClosureProcessResult result) {
        if (!result.commits()
                || result.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing result can be published");
        }
        if (!result.invocationIdentity().equals(
                invocation.input().invocationIdentity())
                || !result.inputClosureIdentity().equals(
                invocation.input().snapshot().closureIdentity())) {
            throw new IllegalStateException(
                    "Closure result does not belong to the captured input");
        }
        ClosureCommitCompanion companion = result.platformCommitCompanion();
        if (companion.expectedInputGraphGeneration()
                != invocation.input().snapshot().graphGeneration()) {
            throw new IllegalStateException(
                    "Commit companion graph fence is stale");
        }
        Map<DocumentId, String> expectedDocuments = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        companion.expectedInputDocuments().forEach(document ->
                expectedDocuments.put(
                        coordinationId(document.documentId()),
                        document.blueId()));
        Map<DocumentId, String> inputDocuments = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        invocation.input().snapshot().managedDocuments().forEach(document ->
                inputDocuments.put(
                        coordinationId(document.documentId()),
                        document.blueId()));
        if (!expectedDocuments.equals(inputDocuments)) {
            throw new IllegalStateException(
                    "Commit companion input-document fences are incomplete");
        }
        invocation.documents().forEach((documentId, document) ->
                {
                    if (!document.head().blueId().equals(
                            inputDocuments.get(documentId))) {
                        throw new IllegalStateException(
                                "Captured durable head differs from managed "
                                        + "expansion input " + documentId);
                    }
                });
        if (invocation.managedExpansion()) {
            requireManagedExpansionResult(invocation, result);
        }
    }

    private static void requireManagedExpansionResult(
            CohortInvocation invocation,
            ClosureProcessResult result) {
        Map<DocumentId, ResultingDocument> documents = resultingDocuments(
                result, invocation.memberSet());
        for (DocumentId documentId : invocation.newMemberSet()) {
            ContractsManagedDraftPlan.ManagedDraft draft = invocation
                    .managedDraftPlan().drafts().get(documentId);
            ResultingDocument initialized = documents.get(documentId);
            if (draft == null
                    || !draft.initial().blueId().equals(
                            initialized.beforeBlueId())
                    || initialized.epoch() != 0L
                    || !initialized.initialized()) {
                throw new IllegalStateException(
                        "Managed draft was not initialized exactly once "
                                + documentId);
            }
        }
        ResultingDocument source = documents.get(
                invocation.managedDraftPlan().targetDocumentId());
        for (ContractsManagedDraftPlan.ExpectedOccurrence expectation
                : invocation.managedDraftPlan().expectedOccurrences()) {
            ResultingDocument target = documents.get(
                    expectation.targetDocumentId());
            List<ManagedOccurrenceBinding> prospectiveRows = invocation.input()
                    .snapshot().occurrences().stream()
                    .filter(row -> !row.active()
                            && row.sourceDocumentId().value().equals(
                                    source.documentId().value())
                            && row.sourcePath().equals(expectation.path())
                            && row.targetDocumentId().value().equals(
                                    expectation.targetDocumentId().value()))
                    .toList();
            if (prospectiveRows.size() != 1) {
                throw new IllegalStateException(
                        "Managed expansion input has no unique prospective "
                                + "occurrence at " + expectation.path());
            }
            ManagedOccurrenceBinding prospective = prospectiveRows.get(0);
            List<ManagedOccurrenceBinding> matches = result
                    .occurrenceBindings().stream()
                    .filter(row -> row.sourceDocumentId().value().equals(
                            source.documentId().value())
                            && row.sourcePath().equals(expectation.path()))
                    .toList();
            Node exact = NodePathEditor.getOrNull(
                    source.document(), expectation.path());
            if (matches.size() != 1
                    || !matches.get(0).active()
                    || !matches.get(0).occurrenceIdentity().equals(
                            prospective.occurrenceIdentity())
                    || !matches.get(0).targetDocumentId().value().equals(
                            expectation.targetDocumentId().value())
                    || !matches.get(0).expectedTargetBlueId().equals(
                            target.afterBlueId())
                    || exact == null
                    || !target.afterBlueId().equals(exact.getBlueId())) {
                throw new IllegalStateException(
                        "Managed occurrence was not established exactly at "
                                + expectation.path());
            }
        }
    }

    private static void requireExactRootSubscriptionSurface(
            DocumentId documentId,
            ManagedRootSubscriptionSurface projected,
            List<SubscriptionState> exactStates) {
        Map<String, ManagedRootChannelOccurrence> channels =
                new LinkedHashMap<>();
        for (ManagedRootChannelOccurrence channel
                : projected.channelOccurrences()) {
            if (channels.putIfAbsent(
                    channel.rawChannelKey(), channel) != null) {
                throw new ProjectionUnavailableException(
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
                        "Closure subscription escaped document "
                                + documentId);
            }
            String channelKey = state.channelOccurrence().rawChannelKey();
            if (states.putIfAbsent(channelKey, state) != null) {
                throw new IllegalStateException(
                        "Closure subscription repeats Root Channel "
                                + channelKey + " for " + documentId);
            }
        }
        if (!channels.keySet().equals(states.keySet())) {
            throw new ProjectionUnavailableException(
                    "Root Channel projection disagrees with the verified "
                            + "closure subscription inventory for "
                            + documentId);
        }
        for (Map.Entry<String, ManagedRootChannelOccurrence> entry
                : channels.entrySet()) {
            ManagedRootChannelOccurrence channel = entry.getValue();
            blue.language.processor.closure.ChannelOccurrence exact =
                    states.get(entry.getKey()).channelOccurrence();
            if (!channel.effectiveRuntimeContributionBlueId().equals(
                    exact.effectiveRuntimeContributionBlueId())
                    || !channel.subscriptionHeaderBlueId().equals(
                    exact.subscriptionHeaderBlueId())) {
                throw new ProjectionUnavailableException(
                        "Root Channel evidence disagrees with the verified "
                                + "closure state at " + documentId + "/"
                                + entry.getKey());
            }
        }
        Set<String> externalChannels = projected.channelOccurrences().stream()
                .filter(ManagedRootChannelOccurrence::externalSource)
                .map(ManagedRootChannelOccurrence::rawChannelKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        Set<String> routedChannels = projected.externalSubscriptions().stream()
                .peek(subscription -> {
                    if (!"/".equals(subscription.scopePath())) {
                        throw new ProjectionUnavailableException(
                                "Managed Root route projection escaped Root at "
                                        + subscription.scopePath());
                    }
                })
                .map(SubscriptionDelta.Entry::channelKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        if (!externalChannels.equals(routedChannels)) {
            throw new ProjectionUnavailableException(
                    "Externally routable Root Channels are incomplete for "
                            + documentId);
        }
    }

    private static SubscriptionDelta routeDelta(
            List<SubscriptionDelta.Entry> previous,
            List<SubscriptionDelta.Entry> desired,
            long resultingEpoch,
            blue.language.processor.ExternalOrderKey transitionOrder) {
        Map<LegacyOccurrence, SubscriptionDelta.Entry> before =
                legacyByOccurrence(previous, true);
        Map<LegacyOccurrence, SubscriptionDelta.Entry> after =
                legacyByOccurrence(desired, false);
        List<SubscriptionDelta.Entry> removed = new ArrayList<>();
        for (Map.Entry<LegacyOccurrence, SubscriptionDelta.Entry> entry
                : before.entrySet()) {
            SubscriptionDelta.Entry replacement = after.get(entry.getKey());
            if (replacement == null
                    || !sameRouteSnapshot(entry.getValue(), replacement)) {
                removed.add(retired(entry.getValue(), resultingEpoch));
            }
        }
        List<SubscriptionDelta.Entry> added = new ArrayList<>();
        for (Map.Entry<LegacyOccurrence, SubscriptionDelta.Entry> entry
                : after.entrySet()) {
            SubscriptionDelta.Entry established = before.get(entry.getKey());
            if (established == null
                    || !sameRouteSnapshot(established, entry.getValue())) {
                added.add(activated(
                        entry.getValue(), resultingEpoch, transitionOrder));
            }
        }
        return new SubscriptionDelta(added, removed);
    }

    private static List<SubscriptionDelta.Entry> activateInitialSubscriptions(
            List<SubscriptionDelta.Entry> desired,
            blue.language.processor.ExternalOrderKey frontier) {
        ArrayList<SubscriptionDelta.Entry> result = new ArrayList<>();
        for (SubscriptionDelta.Entry value : Objects.requireNonNull(
                desired, "desired")) {
            if (!"/".equals(value.scopePath())) {
                throw new ProjectionUnavailableException(
                        "Managed expansion route escaped Root at "
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
                    0L,
                    Objects.requireNonNull(frontier, "frontier"),
                    null));
        }
        return List.copyOf(result);
    }

    private static void requireUnchangedRouteSurface(
            DocumentId documentId,
            List<SubscriptionDelta.Entry> previous,
            List<SubscriptionDelta.Entry> desired) {
        Map<LegacyOccurrence, SubscriptionDelta.Entry> before =
                legacyByOccurrence(previous, true);
        Map<LegacyOccurrence, SubscriptionDelta.Entry> after =
                legacyByOccurrence(desired, false);
        if (!before.keySet().equals(after.keySet())) {
            throw new ProjectionUnavailableException(
                    "A closure changed the Root route surface without "
                            + "advancing document " + documentId);
        }
        for (Map.Entry<LegacyOccurrence, SubscriptionDelta.Entry> entry
                : before.entrySet()) {
            if (!sameRouteSnapshot(
                    entry.getValue(), after.get(entry.getKey()))) {
                throw new ProjectionUnavailableException(
                        "A closure changed the Root route surface without "
                                + "advancing document " + documentId);
            }
        }
    }

    private static Map<LegacyOccurrence, SubscriptionDelta.Entry>
            legacyByOccurrence(
                    List<SubscriptionDelta.Entry> values,
                    boolean requireActive) {
        Map<LegacyOccurrence, SubscriptionDelta.Entry> result =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry value : Objects.requireNonNull(
                values, "subscription values")) {
            if (!"/".equals(value.scopePath())) {
                throw new ProjectionUnavailableException(
                        "Managed document retained a non-Root subscription at "
                                + value.scopePath() + "/"
                                + value.channelKey());
            }
            if (requireActive && !value.isActiveInterval()) {
                throw new IllegalStateException(
                        "Managed document retained an inactive subscription at "
                                + value.channelKey());
            }
            LegacyOccurrence key = new LegacyOccurrence(
                    value.scopePath(), value.channelKey());
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        "Duplicate Root subscription at "
                                + value.channelKey());
            }
        }
        return result;
    }

    private static boolean sameRouteSnapshot(
            SubscriptionDelta.Entry left,
            SubscriptionDelta.Entry right) {
        return left.scopePath().equals(right.scopePath())
                && left.channelKey().equals(right.channelKey())
                && left.effectiveTypeBlueId().equals(
                        right.effectiveTypeBlueId())
                && left.sourceContributionNodeBlueIds().equals(
                        right.sourceContributionNodeBlueIds())
                && left.order() == right.order()
                && left.subscriptionKeys().equals(right.subscriptionKeys())
                && left.checkpointDomainBlueId().equals(
                        right.checkpointDomainBlueId())
                && left.dependencies().equals(right.dependencies());
    }

    private static SubscriptionDelta.Entry retired(
            SubscriptionDelta.Entry value,
            long resultingEpoch) {
        return withInterval(
                value,
                value.activationRootRevision(),
                value.startAfterExternalOrderKey(),
                resultingEpoch);
    }

    private static SubscriptionDelta.Entry activated(
            SubscriptionDelta.Entry value,
            long resultingEpoch,
            blue.language.processor.ExternalOrderKey transitionOrder) {
        return withInterval(
                value, resultingEpoch, transitionOrder, null);
    }

    private static SubscriptionDelta.Entry withInterval(
            SubscriptionDelta.Entry value,
            Long activationEpoch,
            blue.language.processor.ExternalOrderKey startAfter,
            Long endEpoch) {
        return new SubscriptionDelta.Entry(
                value.scopePath(),
                value.channelKey(),
                value.effectiveTypeBlueId(),
                value.sourceContributionNodeBlueIds(),
                value.order(),
                value.subscriptionKeys(),
                value.checkpointDomainBlueId(),
                value.dependencies(),
                activationEpoch,
                startAfter,
                endEpoch);
    }

    private static void requireCohortStillCurrent(
            CohortInvocation invocation,
            InMemoryDocumentStore.ClosureSnapshot current) {
        Set<DocumentId> members = invocation.managedExpansion()
                ? invocation.existingMemberSet()
                : invocation.memberSet();
        for (CapturedDocument document : invocation.documents().values()) {
            if (!document.head().equals(
                    current.requireHead(document.documentId()))) {
                throw stale("Document head changed before closure publication "
                        + document.documentId());
            }
        }
        long currentGraphGeneration = current.graphGenerations()
                .requireCohortGeneration(members);
        if (currentGraphGeneration
                != invocation.input().snapshot().graphGeneration()) {
            throw stale("Cohort graph generation changed before publication");
        }

        List<OccurrenceProjection> expectedOccurrences = invocation.input()
                .snapshot().occurrences().stream()
                .filter(row -> members.contains(coordinationId(
                        row.sourceDocumentId()))
                        && members.contains(coordinationId(
                                row.targetDocumentId())))
                .map(OccurrenceProjection::from)
                .toList();
        List<OccurrenceProjection> currentOccurrences = new ArrayList<>();
        for (ManagedOccurrenceBinding row
                : targetedOccurrences(
                        current.occurrenceInventory(), members)) {
            boolean source = members.contains(
                    coordinationId(row.sourceDocumentId()));
            boolean target = members.contains(
                    coordinationId(row.targetDocumentId()));
            if (source != target) {
                throw stale("Occurrence inventory joined a captured cohort "
                        + "before publication");
            }
            if (source) {
                currentOccurrences.add(OccurrenceProjection.from(row));
            }
        }
        if (!expectedOccurrences.equals(currentOccurrences)) {
            throw stale("Cohort occurrence inventory changed before "
                    + "publication");
        }

        List<String> expectedComponents = invocation.input().snapshot()
                .components().stream()
                .filter(component -> component.orderedMemberDocumentIds()
                        .stream().allMatch(member -> members.contains(
                                coordinationId(member))))
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
        List<String> currentComponents = current.componentStates().stream()
                .filter(component -> component.orderedMemberDocumentIds()
                        .stream().anyMatch(member -> members.contains(
                                coordinationId(member))))
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
        if (!expectedComponents.equals(currentComponents)) {
            throw stale("Cohort component state changed before publication");
        }
    }

    private static List<ManagedOccurrenceBinding> targetedOccurrences(
            ManagedOccurrenceInventory inventory,
            Set<DocumentId> members) {
        Map<String, ManagedOccurrenceBinding> selected =
                new LinkedHashMap<>();
        for (DocumentId member : members) {
            for (ManagedOccurrenceBinding row : inventory.rowsTouching(member)) {
                selected.putIfAbsent(row.occurrenceIdentity(), row);
            }
        }
        ArrayList<ManagedOccurrenceBinding> canonical = new ArrayList<>(
                selected.values());
        canonical.sort(Comparator.naturalOrder());
        return List.copyOf(canonical);
    }

    private static Map<DocumentId, ResultingDocument> resultingDocuments(
            ClosureProcessResult result,
            Set<DocumentId> cohortMembers) {
        TreeMap<DocumentId, ResultingDocument> indexed = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (ResultingDocument document : result.resultingDocuments()) {
            DocumentId documentId = coordinationId(document.documentId());
            if (indexed.putIfAbsent(documentId, document) != null) {
                throw new IllegalStateException(
                        "Result repeats document " + documentId);
            }
        }
        if (!indexed.keySet().equals(cohortMembers)) {
            throw new IllegalStateException(
                    "Result document set differs from the captured cohort");
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static boolean requiresDocumentPublication(
            CapturedDocument before,
            ResultingDocument after) {
        if (!after.beforeBlueId().equals(before.head().blueId())) {
            throw new IllegalStateException(
                    "Result predecessor disagrees with captured head for "
                            + before.documentId());
        }
        if (after.epoch() == before.head().epoch()) {
            if (!after.afterBlueId().equals(before.head().blueId())) {
                throw new ProjectionUnavailableException(
                        "Changed document retained its durable epoch for "
                                + before.documentId());
            }
            return false;
        }
        if (after.epoch() != Math.addExact(before.head().epoch(), 1L)) {
            throw new ProjectionUnavailableException(
                    "One closure result spans multiple durable epochs for "
                            + before.documentId());
        }
        return true;
    }

    private ManagedOccurrenceInventory mergeInventory(
            ManagedOccurrenceInventory before,
            Set<DocumentId> cohortMembers,
            Collection<ManagedOccurrenceBinding> replacements) {
        List<ManagedOccurrenceBinding> merged = new ArrayList<>();
        ContractsStructuralWorkMetrics.recordGlobalPass(
                runtime.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                before.rows().size());
        for (ManagedOccurrenceBinding row : before.rows()) {
            boolean source = cohortMembers.contains(
                    coordinationId(row.sourceDocumentId()));
            boolean target = cohortMembers.contains(
                    coordinationId(row.targetDocumentId()));
            if (source != target) {
                throw new ProjectionUnavailableException(
                        "Occurrence inventory crosses a disconnected cohort");
            }
            if (!source) {
                merged.add(row);
            }
        }
        for (ManagedOccurrenceBinding replacement : replacements) {
            if (!cohortMembers.contains(coordinationId(
                    replacement.sourceDocumentId()))
                    || !cohortMembers.contains(coordinationId(
                    replacement.targetDocumentId()))) {
                throw new IllegalStateException(
                        "Contracts result occurrence escaped its cohort");
            }
            merged.add(replacement);
        }
        ContractsStructuralWorkMetrics.recordGlobalPass(
                runtime.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                merged.size());
        return ManagedOccurrenceInventory.of(merged);
    }

    private boolean sameInventory(
            ManagedOccurrenceInventory first,
            ManagedOccurrenceInventory second) {
        return inventoryProjection(first).equals(inventoryProjection(second));
    }

    private List<OccurrenceProjection> inventoryProjection(
            ManagedOccurrenceInventory inventory) {
        ContractsStructuralWorkMetrics.recordGlobalPass(
                runtime.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                inventory.rows().size());
        return inventory.rows().stream()
                .map(OccurrenceProjection::from)
                .toList();
    }

    private boolean sameActiveTopology(
            ManagedOccurrenceInventory first,
            ManagedOccurrenceInventory second) {
        return activeTopology(first).equals(activeTopology(second));
    }

    private List<ActiveEdge> activeTopology(
            ManagedOccurrenceInventory inventory) {
        ContractsStructuralWorkMetrics.recordGlobalPass(
                runtime.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                inventory.activeRows().size());
        return inventory.activeRows().stream()
                .map(row -> new ActiveEdge(
                        row.occurrenceIdentity(),
                        row.sourceDocumentId().value(),
                        row.targetDocumentId().value()))
                .toList();
    }

    private List<SubscriptionState> subscriptionStatesFor(
            ClosureSubscriptionInventory subscriptions,
            DocumentId documentId) {
        ContractsStructuralWorkMetrics.recordGlobalPass(
                runtime.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
                subscriptions.states().size());
        return subscriptions.statesFor(documentId);
    }

    private static Map<DocumentId, Long> gasByDocument(
            ClosureProcessResult result,
            Set<DocumentId> documents) {
        TreeMap<DocumentId, Long> gas = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        documents.forEach(document -> gas.put(document, 0L));
        long invocationOwned = 0L;
        for (GasTraceEntry entry : result.gasTrace()) {
            if (entry.documentId() == null) {
                invocationOwned = Math.addExact(
                        invocationOwned, entry.subtotal());
                continue;
            }
            DocumentId documentId = coordinationId(entry.documentId());
            if (!gas.containsKey(documentId)) {
                throw new IllegalStateException(
                        "Gas trace names a document outside the cohort");
            }
            gas.put(documentId, Math.addExact(
                    gas.get(documentId), entry.subtotal()));
        }
        if (!gas.isEmpty()) {
            DocumentId owner = gas.firstKey();
            gas.put(owner, Math.addExact(gas.get(owner), invocationOwned));
        } else if (invocationOwned != 0L) {
            throw new ProjectionUnavailableException(
                    "Invocation-owned gas has no durable document revision");
        }
        long projected = gas.values().stream().reduce(
                0L, Math::addExact);
        if (projected != result.totalGas()) {
            throw new IllegalStateException(
                    "Document gas projection does not preserve total gas");
        }
        return Collections.unmodifiableMap(gas);
    }

    private static long transitionGeneration(
            long before,
            boolean changed,
            String label) {
        return changed ? InMemoryDocumentStore.increment(before, label)
                : before;
    }

    static String publicationIdentity(
            FrozenBatch batch,
            CohortInvocation invocation) {
        FrozenBatch frozen = Objects.requireNonNull(batch, "batch");
        CohortInvocation selected = Objects.requireNonNull(
                invocation, "invocation");
        if (selected.members().isEmpty()) {
            throw new IllegalArgumentException(
                    "A closure cohort must not be empty");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "JVM does not provide SHA-256", unavailable);
        }
        String domain = "coordination-contracts-process-closure-v1";
        updatePublicationIdentityFrame(digest, 0, domain);
        updatePublicationIdentityFrame(
                digest, 1, frozen.entry().blueId());
        List<DocumentId> publicRoots = selected.input().snapshot()
                .publicRootDocumentIds().stream()
                .map(documentId -> DocumentId.of(documentId.value()))
                .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .toList();
        List<DocumentId> lane = publicRoots.isEmpty()
                ? selected.members() : publicRoots;
        updatePublicationIdentityFrame(
                digest, 2, publicRoots.isEmpty() ? "internal" : "public");
        updatePublicationIdentityFrame(
                digest, 3, Integer.toString(lane.size()));
        for (DocumentId root : lane) {
            updatePublicationIdentityFrame(digest, 4, root.value());
        }
        updatePublicationIdentityFrame(
                digest, 5, Integer.toString(selected.members().size()));
        for (DocumentId member : selected.members()) {
            updatePublicationIdentityFrame(digest, 6, member.value());
        }
        List<Object> order = frozen.entry().sourceOrderKey().components();
        updatePublicationIdentityFrame(
                digest, 7, Integer.toString(order.size()));
        for (Object component : order) {
            if (component instanceof BigInteger integer) {
                updatePublicationIdentityFrame(
                        digest, 8, integer.toString());
            } else if (component instanceof String text) {
                updatePublicationIdentityFrame(digest, 9, text);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported external order component " + component);
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

    private void requireRouteSelectionCurrent(
            FrozenBatch batch,
            CohortInvocation invocation) {
        long actual = routes.generation();
        if (actual == batch.routeGeneration()) {
            return;
        }
        if (!routes.revalidatesDirectDeliveries(
                batch.entry(), invocation.directDeliveries())) {
            throw stale("Stale frozen route generation: expected "
                    + batch.routeGeneration() + " but found " + actual
                    + "; the cohort's exact frozen deliveries changed");
        }
    }

    private static FrozenBatch requireCohortHandle(
            FrozenBatch batch,
            CohortInvocation cohort) {
        FrozenBatch frozen = Objects.requireNonNull(batch, "batch");
        CohortInvocation selected = Objects.requireNonNull(cohort, "cohort");
        if (!frozen.invocations().contains(selected)) {
            throw new IllegalArgumentException(
                    "Cohort invocation does not belong to the frozen batch");
        }
        return frozen;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Contracts closure adapter is closed");
        }
    }

    private static long requireComponentGeneration(
            Map<DocumentId, Long> generations,
            DocumentId documentId) {
        Long generation = generations.get(documentId);
        if (generation == null) {
            throw new IllegalStateException(
                    "No component generation for " + documentId);
        }
        return generation;
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

    private static List<DocumentId> coordinationIds(
            List<blue.language.processor.closure.DocumentId> documentIds) {
        return documentIds.stream()
                .map(ContractsClosureAdapter::coordinationId)
                .toList();
    }

    private static MultiDocumentPublicationTransaction
            .AtomicPublicationCasException stale(String message) {
        return new MultiDocumentPublicationTransaction
                .AtomicPublicationCasException(message);
    }

    record FrozenBatch(
            TimelineEntry entry,
            long routeGeneration,
            List<CohortInvocation> invocations) {
        FrozenBatch {
            entry = Objects.requireNonNull(entry, "entry");
            if (routeGeneration < 0L) {
                throw new IllegalArgumentException(
                        "routeGeneration must be non-negative");
            }
            invocations = List.copyOf(Objects.requireNonNull(
                    invocations, "invocations"));
        }
    }

    record CohortSelection(
            List<DocumentId> members,
            List<ProcessEmbeddedComponentIndex.Component> components,
            List<ManagedOccurrenceBinding> occurrences,
            List<OperationRouteIndex.FrozenDirectDelivery> deliveries) {
        CohortSelection {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            components = List.copyOf(Objects.requireNonNull(
                    components, "components"));
            occurrences = List.copyOf(Objects.requireNonNull(
                    occurrences, "occurrences"));
            deliveries = List.copyOf(Objects.requireNonNull(
                    deliveries, "deliveries"));
            if (members.isEmpty() || components.isEmpty()) {
                throw new IllegalArgumentException(
                        "A selected cohort must retain members and components");
            }
            if (deliveries.isEmpty()) {
                throw new IllegalArgumentException(
                        "A selected cohort must retain a direct delivery");
            }
        }
    }

    private record ConnectedSelection(
            List<DocumentId> members,
            List<ManagedOccurrenceBinding> occurrences,
            long rowsExamined) {
        private ConnectedSelection {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            occurrences = List.copyOf(Objects.requireNonNull(
                    occurrences, "occurrences"));
            if (members.isEmpty() || rowsExamined < 0L) {
                throw new IllegalArgumentException(
                        "Connected selection must retain members and a "
                                + "non-negative row count");
            }
        }
    }

    record CohortInvocation(
            List<DocumentId> members,
            List<DirectLogicalDelivery> directDeliveries,
            ClosureInvocationInput input,
            Map<DocumentId, CapturedDocument> documents,
            ContractsManagedDraftPlan managedDraftPlan) {
        CohortInvocation {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            directDeliveries = List.copyOf(Objects.requireNonNull(
                    directDeliveries, "directDeliveries"));
            input = Objects.requireNonNull(input, "input");
            documents = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            documents, "documents")));
        }

        Set<DocumentId> memberSet() {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(members));
        }

        Set<DocumentId> existingMemberSet() {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(documents.keySet()));
        }

        Set<DocumentId> newMemberSet() {
            LinkedHashSet<DocumentId> result = new LinkedHashSet<>(members);
            result.removeAll(documents.keySet());
            return Collections.unmodifiableSet(result);
        }

        boolean managedExpansion() {
            return managedDraftPlan != null;
        }
    }

    record CohortOutcome(
            List<DocumentId> members,
            ClosureAttemptResult attempt,
            boolean published,
            String publicationIdentity,
            boolean replayed) {
        CohortOutcome(
                List<DocumentId> members,
                ClosureAttemptResult attempt,
                boolean published) {
            this(members, attempt, published, null, false);
        }

        CohortOutcome {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            attempt = Objects.requireNonNull(attempt, "attempt");
            if (publicationIdentity != null
                    && publicationIdentity.isEmpty()) {
                throw new IllegalArgumentException(
                        "publicationIdentity must be non-empty when present");
            }
            if (published && (!attempt.isComplete()
                    || !attempt.processResult().commits())) {
                throw new IllegalArgumentException(
                        "Only a committing attempt can be published");
            }
            if (replayed && publicationIdentity == null) {
                throw new IllegalArgumentException(
                        "A replayed outcome requires its publication identity");
            }
        }
    }

    private record CapturedDocument(
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead head,
            ExactValue current,
            EmbeddedOnlyLayout layout,
            List<SubscriptionDelta.Entry> activeSubscriptions,
            long nextApplicationOrder,
            boolean initialized,
            boolean terminated) {
        private CapturedDocument {
            documentId = Objects.requireNonNull(documentId, "documentId");
            head = Objects.requireNonNull(head, "head");
            current = Objects.requireNonNull(current, "current");
            layout = Objects.requireNonNull(layout, "layout");
            activeSubscriptions = List.copyOf(Objects.requireNonNull(
                    activeSubscriptions, "activeSubscriptions"));
            if (nextApplicationOrder < 0L) {
                throw new IllegalArgumentException(
                        "nextApplicationOrder must be non-negative");
            }
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
            Long pendingHistoricalEpoch) {
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
                    row.pendingHistoricalEpoch());
        }
    }

    private record ActiveEdge(
            String occurrenceIdentity,
            String sourceDocumentId,
            String targetDocumentId) {
    }

    private record LegacyOccurrence(String scopePath, String channelKey) {
        private LegacyOccurrence {
            scopePath = Objects.requireNonNull(scopePath, "scopePath");
            channelKey = Objects.requireNonNull(channelKey, "channelKey");
        }
    }

    static final class ProjectionUnavailableException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ProjectionUnavailableException(String message) {
            super(message);
        }
    }
}
