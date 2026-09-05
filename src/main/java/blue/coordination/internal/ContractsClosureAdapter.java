package blue.coordination.internal;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessRetryInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.DocumentTransitionEvidence;
import blue.language.processor.closure.ExternalEventCause;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedOccurrenceEvidenceResolution;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.ManagedScopeKey;
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
import java.util.function.Function;

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
    private final AutomaticOccurrenceResolutionCoordinator<CohortInvocation>
            automaticResolutionCoordinator;
    private final ManagedEpochApplicationExecutor
            managedEpochApplicationExecutor;
    private final Map<String, ContractsManagedDraftPlan> managedDraftPlans =
            new LinkedHashMap<>();
    private final Map<String, ContractsManagedEpochSelectionPlan>
            managedEpochSelectionPlans = new LinkedHashMap<>();
    private Consumer<PublicationFailurePoint> publicationFailureInjector =
            ignored -> { };
    private Consumer<MultiDocumentPublicationTransaction.FailurePoint>
            storeFailureInjector = ignored -> { };
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
        ManagedOccurrenceResolver occurrenceResolver =
                new ManagedOccurrenceResolver(
                        runtime.nodeProvider(), runtime.metrics());
        this.automaticResolutionCoordinator =
                new AutomaticOccurrenceResolutionCoordinator<
                        CohortInvocation>(
                        occurrenceResolver,
                        runtime.metrics(),
                        new AutomaticOccurrenceResolutionCoordinator
                                .InputView<CohortInvocation>() {
                            @Override
                            public ClosureInvocationInput input(
                                    CohortInvocation invocation) {
                                return invocation.input();
                            }

                            @Override
                            public String invocationIdentity(
                                    CohortInvocation invocation) {
                                return invocation
                                        .executionInvocationIdentity();
                            }
                        },
                        invocation -> {
                            executionObserver.beginAttempt(
                                    invocation.members().stream()
                                            .map(DocumentId::value)
                                            .toList());
                            return runtime.metrics().timed(
                                    PROCESSOR_PHASE,
                                    () -> invocation.retryInput() == null
                                            ? contracts.processClosure(
                                                    invocation.input())
                                            : contracts.processClosureRetry(
                                                    invocation.retryInput()));
                        },
                        new AutomaticOccurrenceResolutionCoordinator
                                .ExpansionBuilder<CohortInvocation>() {
                            @Override
                            public InMemoryDocumentStore
                                    .OccurrenceResolutionSnapshot
                                    captureStoreState() {
                                return documents
                                        .occurrenceResolutionSnapshot();
                            }

                            @Override
                            public CohortInvocation expand(
                                    CohortInvocation current,
                                    ManagedOccurrenceResolver.Resolution
                                            resolution,
                                    InMemoryDocumentStore
                                            .OccurrenceResolutionSnapshot
                                            storeState) {
                                return augmentWithAutomaticResolution(
                                        current, resolution, storeState);
                            }
                        });
        this.managedEpochApplicationExecutor =
                new ManagedEpochApplicationExecutor(
                        this,
                        runtime,
                        objects,
                        layoutBuilder,
                        documents,
                        routes,
                        profile,
                        activeSourceTimelines,
                        environment,
                        executionObserver,
                        contracts);
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
            requireManagedEpochSelectionTarget(
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

    /** Forgets disposable host evidence after its journal entry is terminal. */
    synchronized void completeManagedDraftPlan(String entryBlueId) {
        ensureOpen();
        String identity = Objects.requireNonNull(
                entryBlueId, "entryBlueId");
        managedDraftPlans.remove(identity);
        managedEpochSelectionPlans.remove(identity);
    }

    /** Package-internal append-atomicity observation. */
    synchronized boolean hasManagedDraftPlan(String entryBlueId) {
        ensureOpen();
        return managedDraftPlans.containsKey(Objects.requireNonNull(
                entryBlueId, "entryBlueId"));
    }

    /** Registers exact SDK epoch selections before journal visibility. */
    synchronized boolean registerManagedEpochSelectionPlan(
            String entryBlueId,
            ContractsManagedEpochSelectionPlan plan) {
        ensureOpen();
        String identity = Objects.requireNonNull(
                entryBlueId, "entryBlueId");
        if (identity.isBlank()) {
            throw new IllegalArgumentException(
                    "entryBlueId must not be blank");
        }
        ContractsManagedEpochSelectionPlan selected =
                Objects.requireNonNull(plan, "plan");
        ContractsManagedEpochSelectionPlan prior =
                managedEpochSelectionPlans.putIfAbsent(identity, selected);
        if (prior != null && prior != selected) {
            throw new IllegalStateException(
                    "A managed epoch selection plan is already registered "
                            + "for " + identity);
        }
        return prior == null;
    }

    /** Removes only selector evidence inserted by a rolling-back append. */
    synchronized void unregisterManagedEpochSelectionPlan(
            String entryBlueId,
            ContractsManagedEpochSelectionPlan plan) {
        ensureOpen();
        if (!managedEpochSelectionPlans.remove(
                Objects.requireNonNull(entryBlueId, "entryBlueId"),
                Objects.requireNonNull(plan, "plan"))) {
            throw new IllegalStateException(
                    "Managed epoch selector rollback lost its exact plan");
        }
    }

    /** Package-internal append-atomicity observation. */
    synchronized boolean hasManagedEpochSelectionPlan(String entryBlueId) {
        ensureOpen();
        return managedEpochSelectionPlans.containsKey(Objects.requireNonNull(
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
            target = session.currentRepresentation();
            if (session.epoch() != selected.targetEpoch()
                    || !target.blueId().equals(selected.targetBlueId())) {
                throw stale("Managed expansion target head changed before "
                        + "append " + selected.targetDocumentId());
            }
        }
        validateManagedDraftExpectationPaths(selected, target);
    }

    /** Rejects invalid exact managed selectors before journal order is used. */
    synchronized void preflightManagedEpochSelectionPlan(
            ContractsManagedEpochSelectionPlan plan) {
        ensureOpen();
        ContractsManagedEpochSelectionPlan selected = Objects.requireNonNull(
                plan, "plan");
        DocumentSession targetSession = documents.require(
                selected.targetDocumentId());
        synchronized (targetSession) {
            ExactValue target = targetSession.currentRepresentation();
            if (targetSession.epoch() != selected.targetEpoch()
                    || !target.blueId().equals(selected.targetBlueId())) {
                throw stale("Managed epoch selector target head changed "
                        + "before append " + selected.targetDocumentId());
            }
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

    /** Executes at most one canonically due retained managed-epoch step. */
    synchronized Optional<ManagedApplicationOutcome>
            processNextManagedEpochApplication() {
        ensureOpen();
        return managedEpochApplicationExecutor.processNext();
    }

    /** Selects one due step outside consumers failed by this drain call. */
    synchronized Optional<ManagedApplicationOutcome>
            processNextManagedEpochApplication(
                    Set<DocumentId> excludedConsumers) {
        ensureOpen();
        return managedEpochApplicationExecutor.processNext(
                excludedConsumers);
    }

    /** Executes one exact idempotent occurrence-specific source epoch. */
    synchronized ManagedApplicationOutcome executeManagedEpochApplication(
            ManagedEpochApplicationWork work) {
        ensureOpen();
        return managedEpochApplicationExecutor.execute(work);
    }

    /** Runs retained managed work through the ordinary typed-demand loop. */
    AutomaticOccurrenceResolutionCoordinator.RunResult<
            CohortInvocation, ContractsClosurePublicationReceipt>
            resolveManagedApplicationOccurrences(CohortInvocation invocation) {
        return automaticResolutionCoordinator.run(
                Objects.requireNonNull(invocation, "invocation"),
                requireAutomaticExpansionLimit(),
                ignored -> Optional.empty(),
                this::requireAutomaticRetryStillCurrent);
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
            return outcome(receipt, true, selected.members());
        }
        requireRouteSelectionCurrent(frozen, selected);
        AutomaticOccurrenceResolutionCoordinator.RunResult<
                CohortInvocation,
                ContractsClosurePublicationReceipt> automatic =
                automaticResolutionCoordinator.run(
                        selected,
                        requireAutomaticExpansionLimit(),
                        invocation -> publicationReceiptByIdentity(
                                frozen, invocation),
                        (before, expanded, storeState) -> {
                            requireAutomaticRetryStillCurrent(
                                    before, expanded, storeState);
                            requireRouteSelectionCurrent(frozen, selected);
                        },
                        managedEpochSelectionPlan(frozen, selected));
        CohortInvocation executed = automatic.invocation();
        if (automatic.replayed()) {
            ContractsClosurePublicationReceipt replay = automatic.replay();
            if (replay.commits()) {
                reconcilePublication(frozen, selected);
            }
            return outcome(replay, true, selected.members());
        }
        ClosureAttemptResult attempt = automatic.attempt();
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
            identity = publicationIdentity(frozen, executed);
            if (!attempt.isComplete()) {
                return new CohortOutcome(
                        selected.members(), executed.members(), attempt,
                        false, identity, false, automatic.expansionCount(),
                        ManagedSurfacePublicationEvidence.empty(),
                        automatic.unresolvedDemands());
            }
            if (!isDurablyTerminalStatus(
                    attempt.processResult().status())) {
                throw new ProjectionUnavailableException(
                        "Contracts capability failure is not a durable feeder "
                                + "disposition and must be retried after the "
                                + "capability is available: "
                                + attempt.processResult().diagnostic()
                                        .message()
                                + " "
                                + attempt.processResult().diagnostic()
                                        .details());
            }
            ContractsManagedDraftPlan rejected = attempt.processResult().commits()
                    && executed.managedDraftPlan() != null
                    && executed.managedDraftPlan().missingExpectedOccurrence(attempt.processResult())
                    ? executed.managedDraftPlan() : null;
            if (rejected != null) requireCommitFences(executed, attempt.processResult());
            receipt = new ContractsClosurePublicationReceipt(
                    identity,
                    executed.members(),
                    attempt,
                    automatic.expansionCount(),
                    attempt.processResult().commits() && rejected == null
                            ? ManagedSurfacePublicationEvidence.committed(
                                    executed, attempt.processResult())
                            : ManagedSurfacePublicationEvidence.empty(), rejected);
        } finally {
            runtime.metrics().addNanos(
                    RESULT_VALIDATION_PHASE,
                    System.nanoTime() - validationStarted);
        }
        ContractsClosurePublicationReceipt selectedReceipt = receipt;
        ContractsClosurePublicationReceipt retainedReceipt =
                runtime.metrics().timed(PUBLICATION_PHASE, () -> {
                    if (selectedReceipt.commits()) {
                        return publish(frozen, executed, selectedReceipt);
                    }
                    publishNonCommit(frozen, executed, selectedReceipt);
                    return selectedReceipt;
                });
        if (retainedReceipt.commits()) {
            runtime.metrics().increment("deliveryReceiptsCommitted");
            runtime.metrics().increment("temporal.externalProcessCalls");
        }
        return outcome(retainedReceipt, false, selected.members());
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
            boolean replayed,
            List<DocumentId> laneMembers) {
        return new CohortOutcome(
                laneMembers,
                receipt.documentIds(),
                receipt.attempt(),
                receipt.commits(),
                receipt.publicationIdentity(),
                replayed,
                receipt.automaticRetryCount(),
                receipt.managedSurfaceEvidence(),
                List.of(), receipt.rejectedDraftPlan());
    }

    synchronized Optional<ContractsClosurePublicationReceipt>
            publicationReceipt(
                    FrozenBatch batch,
                    CohortInvocation cohort) {
        ensureOpen();
        FrozenBatch frozen = requireCohortHandle(batch, cohort);
        return publicationReceiptByIdentity(frozen, cohort);
    }

    private Optional<ContractsClosurePublicationReceipt>
            publicationReceiptByIdentity(
                    FrozenBatch batch,
                    CohortInvocation cohort) {
        FrozenBatch frozen = Objects.requireNonNull(batch, "batch");
        CohortInvocation selected = Objects.requireNonNull(cohort, "cohort");
        String identity = publicationIdentity(frozen, selected);
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
        if (!new LinkedHashSet<>(receipt.documentIds()).containsAll(
                selected.publicationIdentityMembers())
                || (selected.automaticExpansion() != null
                        && !receipt.documentIds().equals(
                                selected.members()))) {
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

    synchronized void onStoreFailurePoint(
            Consumer<MultiDocumentPublicationTransaction.FailurePoint>
                    injector) {
        ensureOpen();
        storeFailureInjector = Objects.requireNonNull(injector, "injector");
    }

    /** Clears disposable catch-up repair state after durable route rebuild. */
    synchronized void resetManagedEpochReconciliationAfterRouteRebuild() {
        ensureOpen();
        managedEpochApplicationExecutor.resetAfterRouteRebuild();
    }

    void configureManagedApplicationTransaction(
            MultiDocumentPublicationTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction")
                .onFailurePoint(storeFailureInjector);
    }

    void injectManagedApplicationPublicationFailure() {
        publicationFailureInjector.accept(
                PublicationFailurePoint
                        .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH);
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
        if (receipt.commits()) {
            throw new IllegalArgumentException(
                    "Receipt-only publication requires a non-commit decision");
        }
        InMemoryDocumentStore.ClosureSnapshot current =
                documents.closureSnapshot(invocation.existingMemberSet());
        requireCohortStillCurrent(invocation, current);
        MultiDocumentPublicationTransaction transaction = documents
                .beginAtomicPublication(
                        receipt.publicationIdentity(),
                        current.occurrenceInventoryGeneration(),
                        current.componentIndexGeneration());
        transaction.onFailurePoint(storeFailureInjector);
        for (CapturedDocument document : invocation.documents().values()) {
            transaction.expectHead(
                    document.documentId(),
                    document.head().epoch(),
                    document.head().blueId());
            transaction.expectGraphGeneration(
                    document.documentId(), document.graphGeneration());
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
                invocation.executionInvocationIdentity())
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
        for (DocumentId documentId : receipt.get().documentIds()) {
            DocumentSession session = documents.require(documentId);
            synchronized (session) {
                replacements.add(new OperationRouteIndex.Replacement(
                        documentId,
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
            }
        }
        routes.prepareReplacement(replacements).publish();
        activeSourceTimelines.refresh(receipt.get().documentIds(), documents);
        return true;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            managedDraftPlans.clear();
            managedEpochSelectionPlans.clear();
            managedEpochApplicationExecutor.resetAfterRouteRebuild();
            contracts.close();
        }
    }

    static List<CohortSelection> partitionSelection(
            ProcessEmbeddedComponentIndex componentIndex,
            ManagedOccurrenceInventory occurrenceInventory,
            OperationRouteIndex.FrozenDirectDeliverySelection selection) {
        return partitionSelection(
                componentIndex,
                occurrenceInventory,
                selection,
                null);
    }

    static List<CohortSelection> partitionSelection(
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
        List<ConnectedSelection> groups = new ArrayList<>();
        long occurrenceRowsExamined = 0L;
        List<DocumentId> directTargets = frozen.documentIds().stream()
                .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .toList();
        for (DocumentId directTarget : directTargets) {
            if (groups.stream().anyMatch(group -> group.members().contains(
                    directTarget))) {
                continue;
            }
            ConnectedSelection connected = connectedSelection(
                    inventory, directTarget);
            connected = mergeIntersecting(groups, connected);
            groups.add(connected);
            occurrenceRowsExamined = Math.addExact(
                    occurrenceRowsExamined, connected.rowsExamined());
        }
        groups.sort(Comparator.comparing(
                group -> group.members().get(0),
                EmbeddingBinding.DOCUMENT_ORDER));
        if (metrics != null) {
            metrics.add(OCCURRENCE_ROWS_EXAMINED, occurrenceRowsExamined);
        }
        List<CohortSelection> result = new ArrayList<>();
        for (ConnectedSelection connected : groups) {
            List<DocumentId> members = connected.members();
            Set<DocumentId> memberSet = new LinkedHashSet<>(members);
            List<ProcessEmbeddedComponentIndex.Component> components =
                    forwardComponents(index, memberSet);
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
            ManagedOccurrenceInventory inventory,
            DocumentId start) {
        TreeMap<DocumentId, Boolean> discovered = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Deque<DocumentId> pending = new ArrayDeque<>();
        discovered.put(Objects.requireNonNull(start, "start"), Boolean.TRUE);
        pending.addLast(start);
        Map<String, ManagedOccurrenceBinding> occurrences =
                new LinkedHashMap<>();
        // The captured inventory cannot change during selection. Retain the
        // reverse frontier as publication adds parents and their forward branches;
        // each ancestor's incoming rows need to be opened only once.
        TreeMap<DocumentId, Boolean> reverseReachable = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        long rowsExamined = 0L;
        while (true) {
            while (!pending.isEmpty()) {
                DocumentId current = pending.removeFirst();
                for (ManagedOccurrenceBinding row
                        : inventory.rowsFrom(current)) {
                    if (occurrences.putIfAbsent(
                            row.occurrenceIdentity(), row) != null) {
                        continue;
                    }
                    rowsExamined = Math.addExact(rowsExamined, 1L);
                    DocumentId target = coordinationId(
                            row.targetDocumentId());
                    if (discovered.putIfAbsent(
                            target, Boolean.TRUE) == null) {
                        pending.addLast(target);
                    }
                }
            }

            extendReverseReachableThroughActiveOccurrences(
                    inventory, discovered.keySet(), reverseReachable);
            boolean addedSource = false;
            for (DocumentId source : reverseReachable.keySet()) {
                if (discovered.containsKey(source)) {
                    continue;
                }
                // Every active occurrence owns an exact embedded value that
                // must be republished with its child. Typed listener demands
                // govern event delivery inside Contracts, not this state
                // publication boundary.
                discovered.put(source, Boolean.TRUE);
                pending.addLast(source);
                addedSource = true;
            }
            if (!addedSource) {
                break;
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

    /**
     * Selects the ordinary affected closure for one managed-revision seed.
     * Forward occurrences retain authoritative reservations and descendants; active
     * reverse parents retain every occurrence of the changed child. Listener
     * matching remains owned by Contracts during event delivery.
     */
    static ConnectedSelection initialConnectedSelection(
            ManagedOccurrenceInventory inventory,
            ClosureSubscriptionInventory subscriptions,
            DocumentId start) {
        Objects.requireNonNull(subscriptions, "subscriptions");
        return connectedSelection(inventory, start);
    }

    private static void extendReverseReachableThroughActiveOccurrences(
                    ManagedOccurrenceInventory inventory,
                    Collection<DocumentId> selectedMembers,
                    TreeMap<DocumentId, Boolean> result) {
        Deque<DocumentId> pending = new ArrayDeque<>();
        for (DocumentId selected : selectedMembers) {
            if (result.putIfAbsent(selected, Boolean.TRUE) == null) {
                pending.addLast(selected);
            }
        }
        while (!pending.isEmpty()) {
            DocumentId current = pending.removeFirst();
            for (ManagedOccurrenceBinding row
                    : inventory.rowsTouching(current)) {
                if (!row.active()
                        || !coordinationId(row.targetDocumentId())
                                .equals(current)) {
                    continue;
                }
                DocumentId source = coordinationId(row.sourceDocumentId());
                if (result.putIfAbsent(source, Boolean.TRUE) == null) {
                    pending.addLast(source);
                }
            }
        }
    }

    private static ConnectedSelection mergeIntersecting(
            List<ConnectedSelection> groups,
            ConnectedSelection candidate) {
        TreeMap<DocumentId, Boolean> members = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        candidate.members().forEach(member -> members.put(
                member, Boolean.TRUE));
        LinkedHashMap<String, ManagedOccurrenceBinding> occurrences =
                new LinkedHashMap<>();
        candidate.occurrences().forEach(row -> occurrences.put(
                row.occurrenceIdentity(), row));
        long rowsExamined = candidate.rowsExamined();
        for (int index = groups.size() - 1; index >= 0; index--) {
            ConnectedSelection existing = groups.get(index);
            if (existing.members().stream().noneMatch(
                    members::containsKey)) {
                continue;
            }
            groups.remove(index);
            existing.members().forEach(member -> members.put(
                    member, Boolean.TRUE));
            existing.occurrences().forEach(row -> occurrences.putIfAbsent(
                    row.occurrenceIdentity(), row));
        }
        ArrayList<ManagedOccurrenceBinding> canonicalOccurrences =
                new ArrayList<>(occurrences.values());
        canonicalOccurrences.sort(Comparator.naturalOrder());
        return new ConnectedSelection(
                new ArrayList<>(members.keySet()),
                canonicalOccurrences,
                rowsExamined);
    }

    private static List<ProcessEmbeddedComponentIndex.Component>
            forwardComponents(
                    ProcessEmbeddedComponentIndex index,
                    Set<DocumentId> members) {
        Comparator<ProcessEmbeddedComponentIndex.Component> order =
                Comparator.comparing(
                        component -> component.members().get(0),
                        EmbeddingBinding.DOCUMENT_ORDER);
        TreeMap<DocumentId, ProcessEmbeddedComponentIndex.Component>
                selected = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId member : members) {
            ProcessEmbeddedComponentIndex.Component component =
                    index.component(member);
            if (!members.containsAll(component.members())) {
                throw new IllegalStateException(
                        "Forward occurrence selection contains only part of "
                                + "an active component "
                                + component.members());
            }
            selected.putIfAbsent(component.members().get(0), component);
        }

        Map<ProcessEmbeddedComponentIndex.Component, Integer>
                remainingTargets = new LinkedHashMap<>();
        Map<ProcessEmbeddedComponentIndex.Component,
                List<ProcessEmbeddedComponentIndex.Component>>
                sourcesByTarget = new LinkedHashMap<>();
        selected.values().forEach(component -> sourcesByTarget.put(
                component, new ArrayList<>()));
        for (ProcessEmbeddedComponentIndex.Component source
                : selected.values()) {
            int targets = 0;
            for (ProcessEmbeddedComponentIndex.Component target
                    : index.targets(source)) {
                if (!sourcesByTarget.containsKey(target)) {
                    throw new IllegalStateException(
                            "Forward occurrence selection omitted active "
                                    + "component " + target.members());
                }
                sourcesByTarget.get(target).add(source);
                targets = Math.addExact(targets, 1);
            }
            remainingTargets.put(source, targets);
        }
        java.util.PriorityQueue<ProcessEmbeddedComponentIndex.Component>
                ready = new java.util.PriorityQueue<>(order);
        remainingTargets.forEach((component, targets) -> {
            if (targets == 0) {
                ready.add(component);
            }
        });
        ArrayList<ProcessEmbeddedComponentIndex.Component> result =
                new ArrayList<>(selected.size());
        while (!ready.isEmpty()) {
            ProcessEmbeddedComponentIndex.Component target = ready.remove();
            result.add(target);
            List<ProcessEmbeddedComponentIndex.Component> sources =
                    sourcesByTarget.get(target);
            sources.sort(order);
            for (ProcessEmbeddedComponentIndex.Component source : sources) {
                int remaining = Math.subtractExact(
                        remainingTargets.get(source), 1);
                remainingTargets.put(source, remaining);
                if (remaining == 0) {
                    ready.add(source);
                }
            }
        }
        if (result.size() != selected.size()) {
            throw new IllegalStateException(
                    "Selected component condensation must be acyclic");
        }
        return List.copyOf(result);
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
                    invocationDocument(document.current()),
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

        ArrayList<ManagedOccurrenceBinding> occurrences = new ArrayList<>();
        LinkedHashSet<String> dormantOccurrenceIdentities =
                new LinkedHashSet<>();
        LinkedHashSet<DocumentId> directDeliveryTargets =
                new LinkedHashSet<>();
        selection.deliveries().forEach(delivery ->
                directDeliveryTargets.add(delivery.documentId()));
        for (ManagedOccurrenceBinding occurrence
                : selection.occurrences()) {
            CapturedDocument source = captured.get(coordinationId(
                    occurrence.sourceDocumentId()));
            if (source == null) {
                throw new IllegalStateException(
                        "Captured occurrence source is outside its cohort "
                                + occurrence.sourceDocumentId().value());
            }
            boolean dormantReservation = directDeliveryTargets.contains(
                            source.documentId())
                    && !occurrence.active()
                    && occurrence.pendingHistoricalEpoch() == null
                    && source.current().canonicalAt(
                            occurrence.sourcePath()) == null;
            if (dormantReservation) {
                dormantOccurrenceIdentities.add(
                        occurrence.occurrenceIdentity());
            } else {
                occurrences.add(occurrence);
            }
        }
        long graphGeneration = maximumCapturedGraphGeneration(
                captured.values());
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
        AutomaticManagedOccurrenceExpansion dormantReservations =
                dormantOccurrenceIdentities.isEmpty()
                        ? null
                        : new AutomaticManagedOccurrenceExpansion(
                                Map.of(),
                                List.of(),
                                dormantOccurrenceIdentities);
        return new CohortInvocation(
                selection.members(),
                deliveries,
                input,
                captured,
                null,
                dormantReservations);
    }

    /** Returns the proof-verified provider shell required for cyclic calls. */
    private Node invocationDocument(ExactValue current) {
        ExactValue selected = Objects.requireNonNull(current, "current");
        if (!selected.isCyclicMember()) {
            return selected.copyNode();
        }
        return objects.requireProviderDocument(selected);
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
                    base.retryInput(),
                    base.documents(),
                    plan,
                    null,
                    base.publicationIdentityMembers(),
                    base.publicationIdentityPublicRoots());
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
                base,
                plan,
                entry.exactRequest().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Managed expansion requires a present exact request"))));
        return List.copyOf(result);
    }

    private void requireManagedEpochSelectionTarget(
            TimelineEntry entry,
            List<CohortInvocation> invocations) {
        ContractsManagedEpochSelectionPlan plan =
                managedEpochSelectionPlans.get(entry.blueId());
        if (plan == null) {
            return;
        }
        int matching = 0;
        for (CohortInvocation invocation : invocations) {
            if (!invocation.memberSet().contains(
                    plan.targetDocumentId())) {
                continue;
            }
            matching++;
            CapturedDocument target = invocation.documents().get(
                    plan.targetDocumentId());
            if (target == null
                    || target.head().epoch() != plan.targetEpoch()
                    || !target.head().blueId().equals(
                            plan.targetBlueId())) {
                throw stale("Managed epoch selector target head changed "
                        + "before capture " + plan.targetDocumentId());
            }
        }
        if (matching != 1) {
            throw new IllegalArgumentException(
                    "MANAGED_EPOCH_SELECTOR_TARGET_PATH_MISMATCH: target "
                            + plan.targetDocumentId()
                            + " was selected by " + matching
                            + " closure lanes");
        }
    }

    private ContractsManagedEpochSelectionPlan managedEpochSelectionPlan(
            FrozenBatch batch,
            CohortInvocation invocation) {
        ContractsManagedEpochSelectionPlan plan =
                managedEpochSelectionPlans.get(batch.entry().blueId());
        return plan != null && invocation.memberSet().contains(
                plan.targetDocumentId()) ? plan : null;
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
                null,
                base.documents(),
                plan,
                null,
                base.publicationIdentityMembers(),
                base.publicationIdentityPublicRoots());
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

    private CohortInvocation augmentWithAutomaticResolution(
            CohortInvocation base,
            ManagedOccurrenceResolver.Resolution resolution,
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState) {
        CohortInvocation current = Objects.requireNonNull(base, "base");
        ManagedOccurrenceResolver.Resolution selected =
                Objects.requireNonNull(resolution, "resolution");
        InMemoryDocumentStore.OccurrenceResolutionSnapshot indexed =
                Objects.requireNonNull(storeState, "storeState");
        if (!selected.complete()) {
            throw new IllegalArgumentException(
                    "Only complete occurrence evidence may expand a retry");
        }
        for (ManagedOccurrenceResolver.ResolvedExactNode exact
                : selected.resolvedExactNodes()) {
            ExactValue retained = objects.putVerifiedProviderEvidence(
                    exact.exactValue(),
                    exact.providerBody(),
                    exact.cyclicProof(),
                    "automatic-occurrence-retry-resource");
            if (!retained.sameExactValue(exact.exactValue())) {
                throw new IllegalStateException(
                        "Retry resource cache changed verified exact content");
            }
        }

        Set<DocumentId> existingMembers = forwardExistingMembers(
                current.existingMemberSet(),
                selected.existingTargets(),
                indexed.occurrenceInventory(),
                runtime.metrics());
        InMemoryDocumentStore.ClosureSnapshot durable =
                documents.closureSnapshot(existingMembers);
        if (durable.occurrenceInventoryGeneration()
                        != indexed.occurrenceInventoryGeneration()
                || durable.componentIndexGeneration()
                        != indexed.componentIndexGeneration()) {
            throw stale("Managed occurrence indexes changed during retry "
                    + "expansion");
        }
        TreeMap<DocumentId, CapturedDocument> captured = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        captured.putAll(current.documents());
        for (DocumentId documentId : existingMembers) {
            CapturedDocument prior = captured.get(documentId);
            InMemoryDocumentStore.DocumentHead head =
                    durable.requireHead(documentId);
            if (prior != null) {
                if (!prior.head().equals(head)) {
                    throw stale("Document head changed during automatic "
                            + "occurrence expansion " + documentId);
                }
                continue;
            }
            captured.put(documentId, captureDocument(
                    documentId, head, existingMembers));
        }
        long graphGeneration = maximumCapturedGraphGeneration(
                captured.values());

        TreeMap<DocumentId, ContractsManagedDraftPlan.ManagedDraft> drafts =
                automaticDrafts(current, selected);
        for (DocumentId documentId : drafts.keySet()) {
            if (existingMembers.contains(documentId)) {
                throw stale("Resolved authored draft became durable during "
                        + "retry expansion " + documentId);
            }
            if (profile.isPublicRoot(documentId)) {
                throw new IllegalArgumentException(
                        "Automatic managed draft cannot create a public Root "
                                + documentId);
            }
        }
        long maximumDocuments = requireManagedDocumentLimit();
        long totalDocuments = Math.addExact(
                existingMembers.size(), drafts.size());
        if (totalDocuments > maximumDocuments) {
            throw new ProjectionUnavailableException(
                    "Automatic occurrence expansion exceeds "
                            + "managedDocumentsPerClosure");
        }

        LinkedHashMap<String, ManagedOccurrenceBinding> rows =
                new LinkedHashMap<>();
        LinkedHashMap<String, ManagedOccurrenceEvidenceResolution>
                retryResolutions = new LinkedHashMap<>();
        if (current.retryInput() != null) {
            for (ManagedOccurrenceEvidenceResolution resolutionEvidence
                    : current.retryInput().resolutions()) {
                retryResolutions.put(
                        resolutionEvidence.demand().demandIdentity(),
                        resolutionEvidence);
            }
        }
        for (DocumentId source : existingMembers) {
            List<ManagedOccurrenceBinding> sourceRows =
                    indexed.occurrenceInventory().rowsFrom(source);
            runtime.metrics().add(
                    OCCURRENCE_ROWS_EXAMINED, sourceRows.size());
            for (ManagedOccurrenceBinding row : sourceRows) {
                DocumentId target = coordinationId(row.targetDocumentId());
                if (!existingMembers.contains(target)) {
                    throw stale("Forward managed closure omitted occurrence "
                            + "target " + target);
                }
                mergeAutomaticRow(rows, row);
            }
        }
        for (ManagedOccurrenceBinding row
                : current.input().snapshot().occurrences()) {
            mergeAutomaticRow(rows, row);
        }

        LinkedHashSet<String> prospectiveIdentities = new LinkedHashSet<>();
        for (ManagedOccurrenceResolver.ResolvedOccurrence occurrence
                : selected.resolvedOccurrences()) {
            String key = occurrenceKey(
                    occurrence.demand().sourceDocumentId().value(),
                    occurrence.demand().sourcePath());
            ManagedOccurrenceBinding retained = rows.get(key);
            if (retained != null) {
                if (retained.active()
                        || ManagedOccurrenceResolver
                                .isVerifiedPendingReceiptEventReplacement(
                                        current.input().cause()
                                                instanceof ManagedRevisionCause
                                                        revision
                                                ? revision : null,
                                        retained,
                                        occurrence.demand()
                                                .suppliedValueBlueId(),
                                        indexed.lineageIndex())) {
                    Long historicalEpoch =
                            occurrence.pendingHistoricalEpoch();
                    blue.language.processor.closure.DocumentId target =
                            closureId(occurrence.targetDocumentId());
                    if (historicalEpoch != null
                            && current.input().snapshot()
                                    .managedDocument(target) != null) {
                        ManagedOccurrenceEvidenceResolution exact =
                                ManagedOccurrenceEvidenceResolution.derived(
                                        occurrence.demand(),
                                        target,
                                        historicalEpoch.longValue());
                        ManagedOccurrenceEvidenceResolution priorResolution =
                                retryResolutions.putIfAbsent(
                                        occurrence.demand().demandIdentity(),
                                        exact);
                        if (priorResolution != null
                                && !priorResolution.resolutionIdentity()
                                        .equals(exact
                                                .resolutionIdentity())) {
                            throw new IllegalStateException(
                                    "Automatic retry changed an exact "
                                            + "managed-occurrence resolution "
                                            + key);
                        }
                    }
                    continue;
                }
                ManagedOccurrenceBinding replacement =
                        ManagedOccurrenceBinding.derived(
                                retained.bindingPolicyIdentity(),
                                retained.sourceDocumentId(),
                                ScopeAddress.embedded(
                                        retained.sourcePath(),
                                        retained.activationGeneration()),
                                closureId(occurrence.targetDocumentId()),
                                occurrence.expectedTargetBlueId(),
                                false,
                                occurrence.pendingHistoricalEpoch());
                boolean sameTarget = retained.targetDocumentId().value()
                        .equals(occurrence.targetDocumentId().value());
                if (sameTarget != replacement.occurrenceIdentity().equals(
                        retained.occurrenceIdentity())) {
                    throw new IllegalStateException(
                            "Inactive occurrence replacement has an invalid "
                                    + "stable identity transition " + key);
                }
                rows.put(key, replacement);
                prospectiveIdentities.add(
                        retained.occurrenceIdentity());
                prospectiveIdentities.add(
                        replacement.occurrenceIdentity());
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
                            false,
                            occurrence.pendingHistoricalEpoch());
            mergeAutomaticRow(rows, prospective);
            prospectiveIdentities.add(
                    prospective.occurrenceIdentity());
        }

        AutomaticManagedOccurrenceExpansion prior =
                current.automaticExpansion() == null
                        ? AutomaticManagedOccurrenceExpansion.empty()
                        : current.automaticExpansion();
        AutomaticManagedOccurrenceExpansion accumulated = prior.merge(
                selected, prospectiveIdentities);

        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                bodies = new LinkedHashMap<>();
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = componentGenerations(
                        durable, captured, existingMembers);
        LinkedHashMap<blue.language.processor.closure.DocumentId,
                ManagedDocumentSnapshot> existing = new LinkedHashMap<>();
        ArrayList<blue.language.processor.closure.DocumentId> members =
                new ArrayList<>();
        ArrayList<blue.language.processor.closure.DocumentId> publicRoots =
                new ArrayList<>();
        for (CapturedDocument document : captured.values()) {
            blue.language.processor.closure.DocumentId documentId =
                    closureId(document.documentId());
            boolean publicRoot = profile.isPublicRoot(document.documentId());
            ManagedDocumentSnapshot priorInvocationDocument = current
                    .input()
                    .snapshot()
                    .managedDocument(documentId);
            Node invocationBody;
            if (priorInvocationDocument == null) {
                invocationBody = invocationDocument(document.current());
            } else {
                if (!priorInvocationDocument.blueId().equals(
                                document.head().blueId())
                        || priorInvocationDocument.epoch()
                                != document.head().epoch()
                        || priorInvocationDocument.initialized()
                                != document.initialized()
                        || priorInvocationDocument.terminated()
                                != document.terminated()
                        || priorInvocationDocument.publicRoot()
                                != publicRoot) {
                    throw stale("Automatic occurrence expansion changed an "
                            + "existing invocation document " + documentId);
                }
                invocationBody = priorInvocationDocument.document();
            }
            ManagedDocumentSnapshot snapshot = new ManagedDocumentSnapshot(
                    documentId,
                    document.head().blueId(),
                    invocationBody,
                    document.initialized(),
                    document.terminated(),
                    publicRoot,
                    document.head().epoch(),
                    requireComponentGeneration(
                            coordinationGenerations(generations),
                            document.documentId()));
            members.add(documentId);
            bodies.put(documentId, invocationBody.clone());
            existing.put(documentId, snapshot);
            if (publicRoot) {
                publicRoots.add(documentId);
            }
        }
        drafts.forEach((documentId, draft) -> {
            blue.language.processor.closure.DocumentId closureDocumentId =
                    closureId(documentId);
            members.add(closureDocumentId);
            bodies.put(closureDocumentId, draft.initial().copyNode());
            generations.put(closureDocumentId, 1L);
        });

        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                members, rows.values());
        ComponentFinalizationResult finalization =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations,
                                bodies,
                                rows.values()));
        ArrayList<ManagedDocumentSnapshot> compiledDocuments =
                new ArrayList<>();
        finalization.documents().forEach((documentId, exact) -> {
            ManagedDocumentSnapshot durableDocument = existing.get(
                    documentId);
            if (durableDocument != null) {
                if (!durableDocument.blueId().equals(exact.blueId())) {
                    throw stale("Automatic occurrence expansion changed a "
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
            ContractsManagedDraftPlan.ManagedDraft draft = drafts.get(
                    coordinationId(documentId));
            if (draft == null
                    || !draft.initial().blueId().equals(exact.blueId())) {
                throw new IllegalStateException(
                        "Automatic draft is not an exact isolated input "
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
        ClosureInvocationInput original = current.input();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        graphGeneration,
                        compiledDocuments,
                        finalization.finalizedGraph().bindings(),
                        components,
                        publicRoots);
        ClosureInvocationInput expanded = ClosureEvidenceFactory
                .processClosure(
                        snapshot,
                        original.cause(),
                        original.directDeliveries(),
                        original.executionPolicy(),
                        original.environment());
        ClosureProcessRetryInput retryInput = retryResolutions.isEmpty()
                ? null
                : ClosureProcessRetryInput.derived(
                        expanded,
                        new ArrayList<>(retryResolutions.values()));
        return new CohortInvocation(
                coordinationIds(graph.documentIds()),
                current.directDeliveries(),
                expanded,
                retryInput,
                captured,
                current.managedDraftPlan(),
                accumulated,
                current.publicationIdentityMembers(),
                current.publicationIdentityPublicRoots());
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

    static Set<DocumentId> forwardExistingMembers(
            Collection<DocumentId> original,
            Collection<DocumentId> targets,
            ManagedOccurrenceInventory inventory,
            EngineMetrics metrics) {
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
            List<ManagedOccurrenceBinding> sourceRows =
                    inventory.rowsFrom(source);
            metrics.add(OCCURRENCE_ROWS_EXAMINED, sourceRows.size());
            for (ManagedOccurrenceBinding row : sourceRows) {
                DocumentId target = coordinationId(row.targetDocumentId());
                if (discovered.putIfAbsent(target, Boolean.TRUE) == null) {
                    pending.addLast(target);
                }
            }
        }
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(discovered.keySet()));
    }

    private static TreeMap<DocumentId,
            ContractsManagedDraftPlan.ManagedDraft> automaticDrafts(
                    CohortInvocation current,
                    ManagedOccurrenceResolver.Resolution resolution) {
        TreeMap<DocumentId, ContractsManagedDraftPlan.ManagedDraft> drafts =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        if (current.managedDraftPlan() != null) {
            current.managedDraftPlan().drafts().forEach((documentId, draft) ->
                    mergeAutomaticDraft(drafts, documentId, draft));
        }
        if (current.automaticExpansion() != null) {
            current.automaticExpansion().drafts().forEach(
                    (documentId, draft) -> mergeAutomaticDraft(
                            drafts, documentId, draft));
        }
        resolution.newDrafts().forEach((documentId, draft) ->
                mergeAutomaticDraft(drafts, documentId, draft));
        return drafts;
    }

    private static void mergeAutomaticDraft(
            Map<DocumentId, ContractsManagedDraftPlan.ManagedDraft> drafts,
            DocumentId documentId,
            ContractsManagedDraftPlan.ManagedDraft draft) {
        ContractsManagedDraftPlan.ManagedDraft prior = drafts.putIfAbsent(
                documentId, draft);
        if (prior != null && !prior.initial().sameExactValue(
                draft.initial())) {
            throw new IllegalStateException(
                    "Managed draft evidence disagrees for " + documentId);
        }
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

    private LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
            componentGenerations(
                    InMemoryDocumentStore.ClosureSnapshot durable,
                    Map<DocumentId, CapturedDocument> captured,
                    Set<DocumentId> expectedMembers) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        for (ComponentSnapshot component : durable.componentStates()) {
            runtime.metrics().increment(COMPONENT_STATES_READ);
            List<DocumentId> componentMembers = coordinationIds(
                    component.orderedMemberDocumentIds());
            if (!expectedMembers.containsAll(componentMembers)) {
                throw stale("Forward closure contains only part of a durable "
                        + "component " + componentMembers);
            }
            for (int index = 0; index < componentMembers.size(); index++) {
                DocumentId member = componentMembers.get(index);
                CapturedDocument document = captured.get(member);
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
                    + "occurrence expansion");
        }
        return generations;
    }

    private static Map<DocumentId, Long> coordinationGenerations(
            Map<blue.language.processor.closure.DocumentId, Long>
                    generations) {
        TreeMap<DocumentId, Long> result = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        generations.forEach((documentId, generation) -> result.put(
                coordinationId(documentId), generation));
        return result;
    }

    private void requireAutomaticRetryStillCurrent(
            CohortInvocation before,
            CohortInvocation expanded,
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState) {
        if (!before.input().cause().causeIdentity().equals(
                expanded.input().cause().causeIdentity())
                || !before.input().directDeliverySnapshotIdentity().equals(
                        expanded.input().directDeliverySnapshotIdentity())
                || before.input().executionPolicy()
                        != expanded.input().executionPolicy()
                || before.input().environment()
                        != expanded.input().environment()
                || before.input().snapshot().graphGeneration()
                        > expanded.input().snapshot().graphGeneration()
                || expanded.input().snapshot().graphGeneration()
                        != maximumCapturedGraphGeneration(
                                expanded.documents().values())
                || !before.publicationIdentityMembers().equals(
                        expanded.publicationIdentityMembers())
                || !before.publicationIdentityPublicRoots().equals(
                        expanded.publicationIdentityPublicRoots())) {
            throw new IllegalStateException(
                    "Automatic retry changed its frozen logical lane");
        }
        InMemoryDocumentStore.OccurrenceResolutionSnapshot currentIndexes =
                documents.occurrenceResolutionSnapshot();
        if (currentIndexes.occurrenceInventoryGeneration()
                        != storeState.occurrenceInventoryGeneration()
                || currentIndexes.componentIndexGeneration()
                        != storeState.componentIndexGeneration()) {
            throw stale("Managed occurrence indexes changed before retry");
        }
        InMemoryDocumentStore.ClosureSnapshot current =
                documents.closureSnapshot(expanded.existingMemberSet());
        requireCohortStillCurrent(expanded, current);
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

    CapturedDocument captureDocument(
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
                            session.currentRepresentation().blueId());
            if (!expectedHead.equals(actualHead)) {
                throw stale("Document head changed during closure capture for "
                        + documentId);
            }
            ExactValue current = session.currentRepresentation();
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
                    documents.graphGeneration(documentId),
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

    private ContractsClosurePublicationReceipt publish(
            FrozenBatch batch,
            CohortInvocation invocation,
            ContractsClosurePublicationReceipt receipt) {
        ClosureProcessResult result = receipt.attempt().processResult();
        if (!receipt.publicationIdentity().equals(
                publicationIdentity(batch, invocation))) {
            throw new IllegalArgumentException(
                    "Process receipt identity does not identify this cohort");
        }
        requirePublishableResult(invocation, result);
        InMemoryDocumentStore.ClosureSnapshot current =
                documents.closureSnapshot(invocation.existingMemberSet());
        requireCohortStillCurrent(invocation, current);
        ManagedOccurrenceInventory.DeltaResult inventoryDelta =
                mergeInventory(
                current.occurrenceInventory(),
                invocation.memberSet(),
                result.occurrenceBindings());
        ManagedOccurrenceInventory resultingInventory =
                inventoryDelta.inventory();
        long resultingInventoryGeneration = transitionGeneration(
                current.occurrenceInventoryGeneration(),
                inventoryDelta.changed(),
                "occurrence inventory generation");
        boolean topologyChanged = !sameActiveTopologyForSources(
                current.occurrenceInventory(),
                resultingInventory,
                invocation.memberSet());
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
        transaction.onFailurePoint(storeFailureInjector);
        for (CapturedDocument document
                : invocation.documents().values()) {
            transaction.expectHead(
                    document.documentId(),
                    document.head().epoch(),
                    document.head().blueId());
            transaction.expectGraphGeneration(
                    document.documentId(), document.graphGeneration());
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
        if (invocation.managedExpansion() || inventoryDelta.changed()) {
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

        Map<DocumentId, ResultingDocument> resultingDocuments =
                resultingDocuments(result, invocation.memberSet());
        Map<DocumentId, ManagedDocumentTransitionReceipt>
                transitionReceipts = transitionReceipts(
                        result, resultingDocuments.keySet());
        Map<DocumentId, ManagedCatchUpPlanner.Head> resultingHeads =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        Map<DocumentId, ManagedEpochReceipt> committedEpochReceipts =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        ClosureSubscriptionInventory resultingClosureSubscriptions =
                current.closureSubscriptions().apply(
                        result,
                        capturedGraphGenerations(
                                invocation.documents().values()));
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
                            .managedDraft(entry.getKey());
                    if (draft == null || after.epoch() != 0L
                            || !after.initialized()) {
                        throw new ProjectionUnavailableException(
                                "Managed expansion did not initialize new Root "
                                        + entry.getKey());
                    }
                    ManagedRootSubscriptionSurface projected = contracts
                            .projectRootSubscriptionSurface(after.document());
                    transaction.stageEmbeddedDemands(
                            entry.getKey(),
                            ClosureSubscriptionInventory.embeddedDemands(
                                    projected));
                    EmbeddedOnlyLayout layout = layoutBuilder
                            .retainVerifiedClosureRoot(
                                    result,
                                    entry.getKey(),
                                    projected);
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
                    ManagedDocumentTransitionReceipt transition =
                            requireTransitionReceipt(
                                    transitionReceipts, entry.getKey());
                    List<Node> emitted = result.publicEvents().stream()
                            .filter(event -> event.publicRootDocumentId()
                                    .value().equals(entry.getKey().value()))
                            .map(PublicEventOccurrence::event)
                            .toList();
                    ExactValue initialized = objects.put(
                            layout.semanticRoot(),
                            "closure-initialization-revision");
                    ManagedEpochReceipt epochReceipt =
                            ManagedEpochReceiptMapper.map(
                                    entry.getKey(),
                                    0L,
                                    DocumentRevision.Kind.INITIALIZATION,
                                    authored,
                                    initialized,
                                    null,
                                    batch.entry().sourceOrderKey(),
                                    transition,
                                    result.platformCommitCompanion());
                    DocumentRevision revision = new DocumentRevision(
                            entry.getKey(),
                            0L,
                            0L,
                            DocumentRevision.Kind.INITIALIZATION,
                            authored,
                            initialized,
                            null,
                            batch.entry().sourceOrderKey(),
                            batch.entry().blueId(),
                            null,
                            emitted,
                            transition.admittedGas(),
                            epochReceipt);
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
                    transaction.stageManagedEpochReceipt(
                            epochReceipt, transition);
                    resultingHeads.put(
                            entry.getKey(),
                            new ManagedCatchUpPlanner.Head(
                                    0L, initialized.blueId()));
                    committedEpochReceipts.put(
                            entry.getKey(), epochReceipt);
                    routeReplacements.add(
                            new OperationRouteIndex.Replacement(
                                    entry.getKey(),
                                    layout.routingSurface(),
                                    activeSubscriptions));
                    continue;
                }
                ManagedDocumentTransitionReceipt transition =
                        transitionReceipts.get(entry.getKey());
                boolean checkpointSettlementChange =
                        isVerifiedCheckpointSettlementChange(
                                result,
                                entry.getKey(),
                                before.head(),
                                after,
                                transition);
                boolean componentRepresentationRebind =
                        !checkpointSettlementChange
                                && isIndirectComponentRepresentationRebind(
                                        invocation,
                                        entry.getKey(),
                                        before,
                                        after);
                boolean changed = componentRepresentationRebind
                        || requiresDocumentPublication(
                                result, before, after, transition);
                boolean stateChanged = !before.head().blueId().equals(
                        after.afterBlueId());
                ManagedRootSubscriptionSurface projected = contracts
                        .projectRootSubscriptionSurface(after.document());
                transaction.stageEmbeddedDemands(
                        entry.getKey(),
                        ClosureSubscriptionInventory.embeddedDemands(
                                projected));
                EmbeddedOnlyLayout layout = stateChanged
                        ? layoutBuilder.retainVerifiedClosureRoot(
                                result,
                                entry.getKey(),
                                before.layout(),
                                projected)
                        : before.layout();
                requireExactRootSubscriptionSurface(
                        entry.getKey(),
                        projected,
                        subscriptionStatesFor(
                                resultingClosureSubscriptions,
                                entry.getKey()));
                List<SubscriptionDelta.Entry> activeSubscriptionsAfter;
                if (stateChanged && !componentRepresentationRebind) {
                    long projectionEpoch = checkpointSettlementChange
                            ? Math.addExact(before.head().epoch(), 1L)
                            : after.epoch();
                    SubscriptionDelta routeDelta = routeDelta(
                            before.activeSubscriptions(),
                            projected.externalSubscriptions(),
                            projectionEpoch,
                            batch.entry().sourceOrderKey());
                    activeSubscriptionsAfter = DocumentTransitionProcessor
                            .applyManagedRootSubscriptionDelta(
                                    before.activeSubscriptions(),
                                    routeDelta,
                                    projectionEpoch,
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
                    resultingHeads.put(
                            entry.getKey(),
                            new ManagedCatchUpPlanner.Head(
                                    before.head().epoch(),
                                    before.head().blueId()));
                    continue;
                }
                if (transition == null) {
                    throw new ProjectionUnavailableException(
                            "Closure publication changed a document without "
                                    + "a complete transition receipt "
                                    + entry.getKey());
                }
                if (componentRepresentationRebind) {
                    transaction.stageIndirectComponentRepresentationRebind(
                            invocation.publicationIdentityMembers(),
                            after,
                            layout,
                            activeSubscriptionsAfter,
                            transition);
                    resultingHeads.put(
                            entry.getKey(),
                            new ManagedCatchUpPlanner.Head(
                                    before.head().epoch(),
                                    after.afterBlueId()));
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
                long coordinationEpoch = Math.addExact(
                        before.head().epoch(), 1L);
                DocumentRevision.Kind revisionKind =
                        transition.beforeBlueId().equals(
                                transition.afterBlueId())
                                ? DocumentRevision.Kind.EVENT_ONLY
                                : DocumentRevision.Kind.TIMELINE_ENTRY;
                ManagedEpochReceipt epochReceipt =
                        ManagedEpochReceiptMapper.map(
                                entry.getKey(),
                                coordinationEpoch,
                                revisionKind,
                                before.current(),
                                exact,
                                batch.entry(),
                                batch.entry().sourceOrderKey(),
                                transition,
                                result.platformCommitCompanion());
                DocumentRevision revision = new DocumentRevision(
                        entry.getKey(),
                        coordinationEpoch,
                        before.nextApplicationOrder(),
                        revisionKind,
                        before.current(),
                        exact,
                        batch.entry(),
                        null,
                        emitted,
                        transition.admittedGas(),
                        epochReceipt);
                transaction.stageDocument(
                        revision,
                        layout,
                        batch.entry().sourceOrderKey(),
                        activeSubscriptionsAfter,
                        after.terminated(),
                        publicationIdentity + "|"
                                + entry.getKey().value());
                transaction.stageManagedEpochReceipt(
                        epochReceipt, transition);
                resultingHeads.put(
                        entry.getKey(),
                        new ManagedCatchUpPlanner.Head(
                                coordinationEpoch, exact.blueId()));
                committedEpochReceipts.put(
                        entry.getKey(), epochReceipt);
            }
            objects.retainVerifiedClosureComponentEvidence(result);
            CatchUpPlanStore beforeCatchUpPlans =
                    documents.catchUpPlansSnapshot();
            ManagedCatchUpPlanner.PlanningResult catchUp =
                    ManagedCatchUpPlanner.afterPublication(
                            beforeCatchUpPlans,
                            current.occurrenceInventory(),
                            resultingInventory,
                            invocation.memberSet(),
                            committedEpochReceipts.values(),
                            invocation.input().cause().causeIdentity(),
                            batch.entry().sourceOrderKey(),
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
                            documentId -> invocation.memberSet().contains(
                                    documentId)
                                    ? result.graphGeneration()
                                    : documents.graphGeneration(documentId));
            transaction.stageCatchUpPlans(
                    beforeCatchUpPlans, catchUp.plans());
            requireRouteSelectionCurrent(batch, invocation);
            OperationRouteIndex.PreparedReplacement preparedRoutes =
                    routes.prepareReplacement(routeReplacements);
            ContractsClosurePublicationReceipt retainedReceipt =
                    receipt.withOperationRouteChanges(
                            preparedRoutes.operationRouteChanges());
            transaction.stageClosurePublicationReceipt(retainedReceipt);
            transaction.commit();
            storeCommitted = true;
            publicationFailureInjector.accept(
                    PublicationFailurePoint
                            .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH);
            preparedRoutes.publish();
            activeSourceTimelines.refresh(invocation.members(), documents);
            objects.commit(objectMark);
            return retainedReceipt;
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

    private ManagedCatchUpPlanner.Head currentManagedHead(
            DocumentId documentId) {
        DocumentSession session = documents.require(Objects.requireNonNull(
                documentId, "documentId"));
        synchronized (session) {
            return new ManagedCatchUpPlanner.Head(
                    session.epoch(),
                    session.currentRepresentation().blueId());
        }
    }

    ManagedCatchUpPlanner.Head resultingManagedHead(
            Map<DocumentId, ManagedCatchUpPlanner.Head> resultingHeads,
            DocumentId documentId) {
        ManagedCatchUpPlanner.Head staged = Objects.requireNonNull(
                resultingHeads, "resultingHeads").get(
                        Objects.requireNonNull(documentId, "documentId"));
        return staged == null ? currentManagedHead(documentId) : staged;
    }

    static void requirePublishableResult(
            CohortInvocation invocation,
            ClosureProcessResult result) {
        requireCommitFences(invocation, result);
        if (invocation.managedExpansion()) requireManagedExpansionResult(invocation, result);
        if (invocation.automaticExpansion() != null) requireAutomaticOccurrenceResults(invocation, result);
    }

    private static void requireCommitFences(CohortInvocation invocation, ClosureProcessResult result) {
        if (!result.commits()
                || result.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing result can be published");
        }
        requireTerminalResult(invocation, result);
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
    }

    private static void requireManagedExpansionResult(
            CohortInvocation invocation,
            ClosureProcessResult result) {
        Map<DocumentId, ResultingDocument> documents = resultingDocuments(
                result, invocation.memberSet());
        for (DocumentId documentId : invocation.newMemberSet()) {
            ContractsManagedDraftPlan.ManagedDraft draft = invocation
                    .managedDraft(documentId);
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
        if (invocation.managedDraftPlan() == null) {
            return;
        }
        ContractsManagedDraftPlan plan = invocation.managedDraftPlan();
        if (plan.missingExpectedOccurrence(result)) {
            throw new IllegalStateException("Expected managed occurrence was not established");
        }
        for (ContractsManagedDraftPlan.ExpectedOccurrence expectation : plan.expectedOccurrences()) {
            ManagedOccurrenceBinding prospective = plan.prospectiveOccurrence(invocation.input(), expectation);
            ManagedOccurrenceBinding established = result.occurrenceBindings().stream()
                    .filter(row -> row.sourceDocumentId().value().equals(plan.targetDocumentId().value())
                            && row.sourcePath().equals(expectation.path())).findFirst().orElseThrow();
            if (!established.occurrenceIdentity().equals(prospective.occurrenceIdentity())) {
                throw new IllegalStateException("Managed occurrence does not preserve its unique prospective identity");
            }
        }
    }

    private static void requireAutomaticOccurrenceResults(
            CohortInvocation invocation,
            ClosureProcessResult result) {
        Map<DocumentId, ResultingDocument> documents = resultingDocuments(
                result, invocation.memberSet());
        for (ManagedOccurrenceResolver.ResolvedOccurrence occurrence
                : invocation.automaticExpansion().occurrences()) {
            DocumentId sourceId = DocumentId.of(
                    occurrence.demand().sourceDocumentId().value());
            ResultingDocument source = documents.get(sourceId);
            ResultingDocument target = documents.get(
                    occurrence.targetDocumentId());
            if (source == null || target == null) {
                throw new IllegalStateException(
                        "Automatic occurrence result omitted a resolved "
                                + "endpoint");
            }
            List<ManagedOccurrenceBinding> matches = result
                    .occurrenceBindings().stream()
                    .filter(row -> row.sourceDocumentId().value().equals(
                            sourceId.value())
                            && row.sourcePath().equals(
                                    occurrence.demand().sourcePath()))
                    .toList();
            Node exact = NodePathEditor.getOrNull(
                    source.document(), occurrence.demand().sourcePath());
            String expectedResultBlueId = occurrence.historicalExisting()
                    ? occurrence.expectedTargetBlueId()
                    : target.afterBlueId();
            if (matches.size() != 1
                    || (occurrence.historicalExisting()
                            ? matches.get(0).active()
                                    || !Objects.equals(
                                            matches.get(0)
                                                    .pendingHistoricalEpoch(),
                                            occurrence
                                                    .pendingHistoricalEpoch())
                            : !matches.get(0).active())
                    || !matches.get(0).targetDocumentId().value().equals(
                            occurrence.targetDocumentId().value())
                    || !matches.get(0).expectedTargetBlueId().equals(
                            expectedResultBlueId)
                    || exact == null
                    || !expectedResultBlueId.equals(
                            exact.isReferenceOnly()
                                    ? exact.getBlueId()
                                    : blue.language.identity
                                            .DirectBlueIdCalculator
                                            .calculateBlueId(exact))) {
                throw new IllegalStateException(
                        "Automatic managed occurrence was not established "
                                + "exactly at " + sourceId
                                + occurrence.demand().sourcePath());
            }
        }
    }

    static void requireExactRootSubscriptionSurface(
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

    static SubscriptionDelta routeDelta(
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

    static List<SubscriptionDelta.Entry> activateInitialSubscriptions(
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

    static void requireUnchangedRouteSurface(
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

    void requireCohortStillCurrent(
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
            if (document.graphGeneration()
                    != current.graphGenerations().require(
                            document.documentId())) {
                throw stale("Document graph generation changed before "
                        + "closure publication " + document.documentId());
            }
        }
        long capturedGraphGeneration = maximumCapturedGraphGeneration(
                invocation.documents().values());
        if (capturedGraphGeneration
                != invocation.input().snapshot().graphGeneration()) {
            throw stale("Closure input does not bind the maximum captured "
                    + "graph generation");
        }

        Map<String, OccurrenceProjection> expectedOccurrences =
                new TreeMap<>(EmbeddingBinding.TEXT_ORDER);
        invocation.input().snapshot().occurrences().stream()
                .filter(row -> !invocation
                        .prospectiveOccurrenceIdentities()
                        .contains(row.occurrenceIdentity()))
                .filter(row -> members.contains(coordinationId(
                        row.sourceDocumentId()))
                        && members.contains(coordinationId(
                                row.targetDocumentId())))
                .forEach(row -> expectedOccurrences.put(
                        occurrenceKey(
                                row.sourceDocumentId().value(),
                                row.sourcePath()),
                        OccurrenceProjection.from(row)));
        Map<String, OccurrenceProjection> currentOccurrences =
                new TreeMap<>(EmbeddingBinding.TEXT_ORDER);
        for (DocumentId member : members) {
            List<ManagedOccurrenceBinding> sourceRows =
                    current.occurrenceInventory().rowsFrom(member);
            runtime.metrics().add(
                    OCCURRENCE_ROWS_EXAMINED, sourceRows.size());
            for (ManagedOccurrenceBinding row : sourceRows) {
                if (invocation.prospectiveOccurrenceIdentities()
                        .contains(row.occurrenceIdentity())) {
                    continue;
                }
                boolean target = members.contains(
                        coordinationId(row.targetDocumentId()));
                if (!target) {
                    throw stale("Forward occurrence closure changed before "
                            + "publication");
                }
                currentOccurrences.put(
                        occurrenceKey(
                                row.sourceDocumentId().value(),
                                row.sourcePath()),
                        OccurrenceProjection.from(row));
            }
        }
        if (!expectedOccurrences.equals(currentOccurrences)) {
            throw stale("Cohort occurrence inventory changed before "
                    + "publication");
        }

        Map<List<DocumentId>, String> expectedComponents =
                componentStateIndex(
                        invocation.input().snapshot().components(), members);
        Map<List<DocumentId>, String> currentComponents =
                componentStateIndex(current.componentStates(), members);
        if (!expectedComponents.equals(currentComponents)) {
            throw stale("Cohort component state changed before publication");
        }
    }

    private static Map<List<DocumentId>, String> componentStateIndex(
            Collection<ComponentSnapshot> components,
            Set<DocumentId> members) {
        LinkedHashMap<List<DocumentId>, String> result =
                new LinkedHashMap<>();
        for (ComponentSnapshot component : components) {
            List<DocumentId> componentMembers = coordinationIds(
                    component.orderedMemberDocumentIds());
            boolean any = componentMembers.stream().anyMatch(
                    members::contains);
            if (!any) {
                continue;
            }
            if (!members.containsAll(componentMembers)) {
                throw stale("Captured forward closure contains only part of "
                        + "a component " + componentMembers);
            }
            String duplicate = result.putIfAbsent(
                    componentMembers,
                    component.componentStateIdentity());
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate component state " + componentMembers);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<DocumentId, ResultingDocument> resultingDocuments(
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

    static boolean requiresDocumentPublication(
            ClosureProcessResult result,
            CapturedDocument before,
            ResultingDocument after,
            ManagedDocumentTransitionReceipt transition) {
        Objects.requireNonNull(result, "result");
        if (!after.beforeBlueId().equals(before.head().blueId())) {
            throw new IllegalStateException(
                    "Result predecessor disagrees with captured head for "
                            + before.documentId());
        }
        if (transition != null
                && (!transition.documentId().value().equals(
                        before.documentId().value())
                || !transition.beforeBlueId().equals(
                        before.head().blueId())
                || !transition.afterBlueId().equals(
                        after.afterBlueId()))) {
            throw new IllegalStateException(
                    "Managed transition receipt disagrees with result for "
                            + before.documentId());
        }
        if (after.epoch() == before.head().epoch()) {
            if (!after.afterBlueId().equals(before.head().blueId())) {
                if (isVerifiedCheckpointSettlementChange(
                        result,
                        before.documentId(),
                        before.head(),
                        after,
                        transition)) {
                    return true;
                }
                throw new ProjectionUnavailableException(
                        "Changed document retained its durable epoch for "
                                + before.documentId()
                                + " (epoch=" + before.head().epoch()
                                + ", before=" + before.head().blueId()
                                + ", after=" + after.afterBlueId()
                                + ", transition="
                                + (transition == null
                                        ? "absent"
                                        : transition
                                                .transitionReceiptIdentity())
                                + ")");
            }
            return transition != null;
        }
        if (after.epoch() != Math.addExact(before.head().epoch(), 1L)) {
            throw new ProjectionUnavailableException(
                    "One closure result spans multiple durable epochs for "
                            + before.documentId());
        }
        if (transition == null) {
            throw new ProjectionUnavailableException(
                    "Changed document has no complete managed transition "
                            + "receipt for " + before.documentId());
        }
        return true;
    }

    /** Recognizes an exact Root checkpoint settlement at a retained work epoch. */
    static boolean isVerifiedCheckpointSettlementChange(
            ClosureProcessResult result,
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before,
            ResultingDocument after,
            ManagedDocumentTransitionReceipt transition) {
        boolean settlementEvidence = transition != null
                && (!transition.emittedRootEvents().isEmpty()
                        || result.checkpointWrites().stream().anyMatch(
                                write -> checkpointTargets(
                                        write, documentId)));
        if (!result.commits()
                || transition == null
                || after.epoch() != before.epoch()
                || !after.beforeBlueId().equals(before.blueId())
                || after.afterBlueId().equals(before.blueId())
                || !transition.documentId().value().equals(documentId.value())
                || !transition.sourceInvocationIdentity().equals(
                        result.invocationIdentity())
                || !transition.beforeBlueId().equals(before.blueId())
                || !transition.afterBlueId().equals(after.afterBlueId())
                || !settlementEvidence) {
            return false;
        }
        List<DocumentTransitionEvidence> boundaries = result
                .documentTransitionEvidence()
                .stream()
                .filter(evidence -> evidence.documentId().value().equals(
                        documentId.value()))
                .toList();
        return !boundaries.isEmpty()
                && boundaries.stream().allMatch(evidence ->
                        evidence.beforeDocumentBlueId().equals(before.blueId())
                                && evidence.afterDocumentBlueId().equals(
                                        before.blueId()));
    }

    private static boolean checkpointTargets(
            CheckpointWrite write,
            DocumentId documentId) {
        try {
            new CheckpointWrite(
                    write.checkpointWriteOrdinal(),
                    ManagedScopeKey.root(
                            new blue.language.processor.closure.DocumentId(
                                    documentId.value())),
                    write.targetManagedScopeIdentity(),
                    write.rawChannelKey(),
                    checkpointState(
                            write.beforePresent(),
                            write.beforeDomainBlueId(),
                            write.beforeDomainValue(),
                            write.beforeSubjectBlueId()),
                    checkpointState(
                            write.afterPresent(),
                            write.afterDomainBlueId(),
                            write.afterDomainValue(),
                            write.afterSubjectBlueId()));
            return true;
        } catch (IllegalArgumentException mismatch) {
            return false;
        }
    }

    private static CheckpointWrite.State checkpointState(
            boolean present,
            String domainBlueId,
            CheckpointDomainValue domainValue,
            String subjectBlueId) {
        return present
                ? new CheckpointWrite.State(
                        domainBlueId, domainValue, subjectBlueId)
                : null;
    }

    /**
     * Recognizes a Contracts component finalizer which changes only an
     * indirectly reached member representation. Direct Timeline targets must
     * always advance through the ordinary document-revision lane.
     */
    private static boolean isIndirectComponentRepresentationRebind(
            CohortInvocation invocation,
            DocumentId documentId,
            CapturedDocument before,
            ResultingDocument after) {
        return !invocation.publicationIdentityMembers().contains(documentId)
                && after.epoch() == before.head().epoch()
                && after.beforeBlueId().equals(before.head().blueId())
                && !after.afterBlueId().equals(before.head().blueId());
    }

    static Map<DocumentId, ManagedDocumentTransitionReceipt>
            transitionReceipts(
                    ClosureProcessResult result,
                    Set<DocumentId> cohortMembers) {
        TreeMap<DocumentId, ManagedDocumentTransitionReceipt> indexed =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (ManagedDocumentTransitionReceipt receipt
                : Objects.requireNonNull(result, "result")
                        .managedTransitionReceipts()) {
            DocumentId documentId = coordinationId(receipt.documentId());
            if (!cohortMembers.contains(documentId)
                    || indexed.putIfAbsent(documentId, receipt) != null) {
                throw new IllegalStateException(
                        "Managed transition receipts do not name one unique "
                                + "cohort document " + documentId);
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static ManagedDocumentTransitionReceipt
            requireTransitionReceipt(
                    Map<DocumentId, ManagedDocumentTransitionReceipt> receipts,
                    DocumentId documentId) {
        ManagedDocumentTransitionReceipt receipt = receipts.get(documentId);
        if (receipt == null) {
            throw new ProjectionUnavailableException(
                    "Initialized document has no complete managed transition "
                            + "receipt " + documentId);
        }
        return receipt;
    }

    ManagedOccurrenceInventory.DeltaResult mergeInventory(
            ManagedOccurrenceInventory before,
            Set<DocumentId> cohortMembers,
            Collection<ManagedOccurrenceBinding> replacements) {
        List<ManagedOccurrenceBinding> replacementRows = new ArrayList<>();
        LinkedHashSet<String> replacementKeys = new LinkedHashSet<>();
        for (ManagedOccurrenceBinding replacement : replacements) {
            if (!cohortMembers.contains(coordinationId(
                    replacement.sourceDocumentId()))
                    || !cohortMembers.contains(coordinationId(
                    replacement.targetDocumentId()))) {
                throw new IllegalStateException(
                        "Contracts result occurrence escaped its cohort");
            }
            replacementRows.add(replacement);
            replacementKeys.add(occurrenceKey(
                    replacement.sourceDocumentId().value(),
                    replacement.sourcePath()));
        }
        for (DocumentId source : cohortMembers) {
            for (ManagedOccurrenceBinding row : before.rowsFrom(source)) {
                if (!cohortMembers.contains(
                        coordinationId(row.targetDocumentId()))) {
                    throw new ProjectionUnavailableException(
                            "Forward occurrence closure omitted an outgoing "
                                    + "target");
                }
                String key = occurrenceKey(
                        row.sourceDocumentId().value(), row.sourcePath());
                if (!row.active()
                        && row.pendingHistoricalEpoch() == null
                        && !replacementKeys.contains(key)) {
                    replacementRows.add(row);
                    replacementKeys.add(key);
                }
            }
        }
        return before.replaceSources(cohortMembers, replacementRows);
    }

    static boolean sameActiveTopologyForSources(
            ManagedOccurrenceInventory first,
            ManagedOccurrenceInventory second,
            Collection<DocumentId> sources) {
        for (DocumentId source : sources) {
            List<ActiveEdge> before = first.activeRowsFrom(source).stream()
                    .map(row -> new ActiveEdge(
                            row.occurrenceIdentity(),
                            row.sourceDocumentId().value(),
                            row.targetDocumentId().value()))
                    .toList();
            List<ActiveEdge> after = second.activeRowsFrom(source).stream()
                    .map(row -> new ActiveEdge(
                            row.occurrenceIdentity(),
                            row.sourceDocumentId().value(),
                            row.targetDocumentId().value()))
                    .toList();
            if (!before.equals(after)) {
                return false;
            }
        }
        return true;
    }

    List<SubscriptionState> subscriptionStatesFor(
            ClosureSubscriptionInventory subscriptions,
            DocumentId documentId) {
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

    static long transitionGeneration(
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
        List<DocumentId> publicRoots = selected
                .publicationIdentityPublicRoots().stream()
                .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .toList();
        List<DocumentId> lane = publicRoots.isEmpty()
                ? selected.publicationIdentityMembers() : publicRoots;
        updatePublicationIdentityFrame(
                digest, 2, publicRoots.isEmpty() ? "internal" : "public");
        updatePublicationIdentityFrame(
                digest, 3, Integer.toString(lane.size()));
        for (DocumentId root : lane) {
            updatePublicationIdentityFrame(digest, 4, root.value());
        }
        updatePublicationIdentityFrame(
                digest,
                5,
                Integer.toString(
                        selected.publicationIdentityMembers().size()));
        for (DocumentId member : selected.publicationIdentityMembers()) {
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

    static long requireComponentGeneration(
            Map<DocumentId, Long> generations,
            DocumentId documentId) {
        Long generation = generations.get(documentId);
        if (generation == null) {
            throw new IllegalStateException(
                    "No component generation for " + documentId);
        }
        return generation;
    }

    static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    static DocumentId coordinationId(
            blue.language.processor.closure.DocumentId documentId) {
        return DocumentId.of(documentId.value());
    }

    private static List<DocumentId> coordinationIds(
            List<blue.language.processor.closure.DocumentId> documentIds) {
        return documentIds.stream()
                .map(ContractsClosureAdapter::coordinationId)
                .toList();
    }

    static MultiDocumentPublicationTransaction
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

    record ConnectedSelection(
            List<DocumentId> members,
            List<ManagedOccurrenceBinding> occurrences,
            long rowsExamined) {
        ConnectedSelection {
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
            ClosureProcessRetryInput retryInput,
            Map<DocumentId, CapturedDocument> documents,
            ContractsManagedDraftPlan managedDraftPlan,
            AutomaticManagedOccurrenceExpansion automaticExpansion,
            List<DocumentId> publicationIdentityMembers,
            List<DocumentId> publicationIdentityPublicRoots) {
        CohortInvocation(
                List<DocumentId> members,
                List<DirectLogicalDelivery> directDeliveries,
                ClosureInvocationInput input,
                Map<DocumentId, CapturedDocument> documents,
                ContractsManagedDraftPlan managedDraftPlan) {
            this(
                    members,
                    directDeliveries,
                    input,
                    null,
                    documents,
                    managedDraftPlan,
                    null,
                    directTargetDocumentIds(directDeliveries),
                    directPublicRootDocumentIds(
                            directDeliveries, input));
        }

        CohortInvocation(
                List<DocumentId> members,
                List<DirectLogicalDelivery> directDeliveries,
                ClosureInvocationInput input,
                Map<DocumentId, CapturedDocument> documents,
                ContractsManagedDraftPlan managedDraftPlan,
                AutomaticManagedOccurrenceExpansion automaticExpansion) {
            this(
                    members,
                    directDeliveries,
                    input,
                    null,
                    documents,
                    managedDraftPlan,
                    automaticExpansion,
                    directTargetDocumentIds(directDeliveries),
                    directPublicRootDocumentIds(
                            directDeliveries, input));
        }

        CohortInvocation {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            directDeliveries = List.copyOf(Objects.requireNonNull(
                    directDeliveries, "directDeliveries"));
            input = Objects.requireNonNull(input, "input");
            if (retryInput != null
                    && !retryInput.baseInvocation().invocationIdentity()
                            .equals(input.invocationIdentity())) {
                throw new IllegalArgumentException(
                        "Process retry does not reconstruct this cohort "
                                + "input");
            }
            documents = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            documents, "documents")));
            publicationIdentityMembers = List.copyOf(Objects.requireNonNull(
                    publicationIdentityMembers,
                    "publicationIdentityMembers"));
            publicationIdentityPublicRoots = List.copyOf(
                    Objects.requireNonNull(
                            publicationIdentityPublicRoots,
                            "publicationIdentityPublicRoots"));
            if (publicationIdentityMembers.isEmpty()
                    || !new LinkedHashSet<>(publicationIdentityMembers)
                            .containsAll(publicationIdentityPublicRoots)) {
                throw new IllegalArgumentException(
                        "Publication identity lane must contain every Root");
            }
        }

        Set<DocumentId> memberSet() {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(members));
        }

        String executionInvocationIdentity() {
            return retryInput == null
                    ? input.invocationIdentity()
                    : retryInput.retryInvocationIdentity();
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
            return !newMemberSet().isEmpty();
        }

        ContractsManagedDraftPlan.ManagedDraft managedDraft(
                DocumentId documentId) {
            DocumentId selected = Objects.requireNonNull(
                    documentId, "documentId");
            ContractsManagedDraftPlan.ManagedDraft explicit =
                    managedDraftPlan == null
                            ? null : managedDraftPlan.drafts().get(selected);
            ContractsManagedDraftPlan.ManagedDraft automatic =
                    automaticExpansion == null
                            ? null : automaticExpansion.drafts().get(selected);
            if (explicit != null && automatic != null
                    && !explicit.initial().sameExactValue(
                            automatic.initial())) {
                throw new IllegalStateException(
                        "Explicit and automatic expansion disagree for "
                                + selected);
            }
            return explicit != null ? explicit : automatic;
        }

        Set<String> prospectiveOccurrenceIdentities() {
            return automaticExpansion == null
                    ? Set.of()
                    : automaticExpansion.prospectiveOccurrenceIdentities();
        }

        private static List<DocumentId> directTargetDocumentIds(
                Collection<DirectLogicalDelivery> deliveries) {
            TreeMap<DocumentId, Boolean> targets = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
            for (DirectLogicalDelivery delivery : deliveries) {
                targets.put(
                        coordinationId(delivery.targetDocumentId()),
                        Boolean.TRUE);
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException(
                        "A closure invocation needs a direct target lane");
            }
            return List.copyOf(targets.keySet());
        }

        private static List<DocumentId> directPublicRootDocumentIds(
                Collection<DirectLogicalDelivery> deliveries,
                ClosureInvocationInput input) {
            Set<DocumentId> direct = new LinkedHashSet<>(
                    directTargetDocumentIds(deliveries));
            return input.snapshot().publicRootDocumentIds().stream()
                    .map(ContractsClosureAdapter::coordinationId)
                    .filter(direct::contains)
                    .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                    .toList();
        }
    }

    record ManagedApplicationOutcome(
            ManagedEpochApplicationWork work,
            ClosureAttemptResult attempt,
            ManagedEpochApplicationReceipt applicationReceipt,
            boolean published,
            boolean replayed,
            long automaticRetryCount,
            Optional<AutomaticOccurrenceResolutionCoordinator.StopReason>
                    automaticResolutionStopReason,
            List<ManagedOccurrenceResolver.UnresolvedDemand>
                    unresolvedDemands,
            Optional<ManagedApplicationPublicationFailure>
                    publicationFailure) {
        ManagedApplicationOutcome {
            work = Objects.requireNonNull(work, "work");
            attempt = Objects.requireNonNull(attempt, "attempt");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    automaticRetryCount, "automaticRetryCount");
            automaticResolutionStopReason = Objects.requireNonNull(
                    automaticResolutionStopReason,
                    "automaticResolutionStopReason");
            unresolvedDemands = List.copyOf(Objects.requireNonNull(
                    unresolvedDemands, "unresolvedDemands"));
            publicationFailure = Objects.requireNonNull(
                    publicationFailure, "publicationFailure");
            if (published != (applicationReceipt != null)) {
                throw new IllegalArgumentException(
                        "Published managed work requires its application "
                                + "receipt");
            }
            if (replayed && !published) {
                throw new IllegalArgumentException(
                        "Only a committed managed application can replay");
            }
            if ((published || attempt.isComplete())
                    && !unresolvedDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only a suspended unpublished managed application "
                                + "may expose unresolved occurrence evidence");
            }
            if ((published || attempt.isComplete())
                    && automaticResolutionStopReason.isPresent()) {
                throw new IllegalArgumentException(
                        "Only a suspended unpublished managed application may "
                                + "expose an automatic resolution stop reason");
            }
            if (!attempt.isComplete()
                    && automaticResolutionStopReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "A suspended managed application requires an automatic "
                                + "resolution stop reason");
            }
            if (publicationFailure.isPresent()
                    && (published
                    || replayed
                    || !attempt.isComplete()
                    || !attempt.processResult().commits())) {
                throw new IllegalArgumentException(
                        "A managed publication failure requires one complete "
                                + "committing but unpublished attempt");
            }
            if (publicationFailure.isPresent()
                    && (automaticResolutionStopReason.isPresent()
                    || !unresolvedDemands.isEmpty())) {
                throw new IllegalArgumentException(
                        "A managed publication failure cannot also be an "
                                + "automatic resolution stop");
            }
            boolean unresolvedStop = automaticResolutionStopReason
                    .filter(reason -> reason
                            == AutomaticOccurrenceResolutionCoordinator
                                    .StopReason.UNRESOLVED_DEMANDS)
                    .isPresent();
            if (unresolvedStop != !unresolvedDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "UNRESOLVED_DEMANDS must identify at least one exact "
                                + "managed occurrence issue");
            }
        }

        Optional<ManagedEpochApplicationReceipt> receipt() {
            return Optional.ofNullable(applicationReceipt);
        }
    }

    record ManagedApplicationPublicationFailure(
            CoordinationErrorCode code,
            String message,
            Map<String, String> details) {
        ManagedApplicationPublicationFailure {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException(
                        "Publication failure message must not be blank");
            }
            details = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(details, "details")));
        }
    }

    record CohortOutcome(
            List<DocumentId> members,
            List<DocumentId> publicationMembers,
            ClosureAttemptResult attempt,
            boolean published,
            String publicationIdentity,
            boolean replayed,
            long automaticRetryCount,
            ManagedSurfacePublicationEvidence managedSurfaceEvidence,
            List<ManagedOccurrenceResolver.UnresolvedDemand>
                    unresolvedDemands,
            ContractsManagedDraftPlan rejectedDraftPlan) {
        CohortOutcome(List<DocumentId> members, List<DocumentId> publicationMembers,
                ClosureAttemptResult attempt, boolean published, String publicationIdentity,
                boolean replayed, long retries, ManagedSurfacePublicationEvidence evidence,
                List<ManagedOccurrenceResolver.UnresolvedDemand> demands) {
            this(members, publicationMembers, attempt, published, publicationIdentity,
                    replayed, retries, evidence, demands, null);
        }

        CohortOutcome(
                List<DocumentId> members,
                ClosureAttemptResult attempt,
                boolean published) {
            this(members, members, attempt, published, null, false, 0L,
                    ManagedSurfacePublicationEvidence.empty(), List.of());
        }

        CohortOutcome(
                List<DocumentId> members,
                ClosureAttemptResult attempt,
                boolean published,
                String publicationIdentity,
                boolean replayed) {
            this(members, members, attempt, published, publicationIdentity,
                    replayed, 0L, ManagedSurfacePublicationEvidence.empty(),
                    List.of());
        }

        CohortOutcome {
            if (rejectedDraftPlan != null && (published || publicationIdentity == null
                    || !attempt.isComplete() || !attempt.processResult().commits()
                    || !rejectedDraftPlan.missingExpectedOccurrence(attempt.processResult()))) {
                throw new IllegalArgumentException("Unpublished success requires its exact managed draft rejection");
            }
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            publicationMembers = List.copyOf(Objects.requireNonNull(
                    publicationMembers, "publicationMembers"));
            if (!publicationMembers.containsAll(members)) {
                throw new IllegalArgumentException(
                        "Publication members must contain the frozen lane");
            }
            attempt = Objects.requireNonNull(attempt, "attempt");
            managedSurfaceEvidence = Objects.requireNonNull(
                    managedSurfaceEvidence, "managedSurfaceEvidence");
            unresolvedDemands = List.copyOf(Objects.requireNonNull(
                    unresolvedDemands, "unresolvedDemands"));
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    automaticRetryCount, "automaticRetryCount");
            if ((published || attempt.isComplete())
                    && !unresolvedDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only a suspended unpublished cohort may expose "
                                + "unresolved managed occurrence evidence");
            }
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
            if (!published && managedSurfaceEvidence.present()) {
                throw new IllegalArgumentException(
                        "Only a published outcome can expose managed "
                                + "publication evidence");
            }
        }
    }

    record CapturedDocument(
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead head,
            long graphGeneration,
            ExactValue current,
            EmbeddedOnlyLayout layout,
            List<SubscriptionDelta.Entry> activeSubscriptions,
            long nextApplicationOrder,
            boolean initialized,
            boolean terminated) {
        CapturedDocument {
            documentId = Objects.requireNonNull(documentId, "documentId");
            head = Objects.requireNonNull(head, "head");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    graphGeneration, "graphGeneration");
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

    static long maximumCapturedGraphGeneration(
            Collection<CapturedDocument> documents) {
        long maximum = Objects.requireNonNull(documents, "documents").stream()
                .mapToLong(document -> Objects.requireNonNull(document, "document").graphGeneration())
                .max().orElse(-1L);
        if (maximum < 0L) {
            throw new IllegalArgumentException(
                    "A captured graph-generation cohort must not be empty");
        }
        return maximum;
    }

    static Map<DocumentId, Long> capturedGraphGenerations(
            Collection<CapturedDocument> documents) {
        TreeMap<DocumentId, Long> result = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (CapturedDocument document : Objects.requireNonNull(
                documents, "documents")) {
            CapturedDocument exact = Objects.requireNonNull(
                    document, "document");
            if (result.putIfAbsent(
                    exact.documentId(), exact.graphGeneration()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate captured graph-generation member "
                                + exact.documentId());
            }
        }
        return Collections.unmodifiableMap(result);
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
