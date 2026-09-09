package blue.coordination.internal;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Executes retained managed epochs through the ordinary Contracts closure
 * processor and publishes their occurrence-specific cursor transition.
 *
 * <p>The executor owns only invocation-local catch-up orchestration and one
 * disposable response-loss reconciliation hint. Durable source receipts,
 * plans, application receipts, sessions, and route material remain owned by
 * their existing stores. A restart rebuilds route material from those stores
 * and explicitly clears the hint; no application-history scan is needed.</p>
 */
final class ManagedEpochApplicationExecutor {
    static final String SOURCE_RECEIPTS_READ =
            "managedEpoch.catchUp.sourceReceiptsRead";
    static final String PLANS_OPENED =
            "managedEpoch.catchUp.plansOpened";
    static final String OCCURRENCES_ADVANCED =
            "managedEpoch.catchUp.occurrencesAdvanced";
    static final String AFFECTED_DOCUMENTS_OPENED =
            "managedEpoch.catchUp.affectedDocumentsOpened";
    static final String PROCESS_CALLS =
            "managedEpoch.catchUp.processCalls";
    static final String SOURCE_PROCESS_CALLS =
            "managedEpoch.catchUp.sourceProcessCalls";
    static final String COMPONENT_FINALIZATIONS =
            "managedEpoch.catchUp.componentFinalizations";
    static final String UNRELATED_DOCUMENTS_SCANNED =
            "managedEpoch.catchUp.unrelatedDocumentsScanned";

    private final ContractsClosureAdapter host;
    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final EmbeddedOnlyLayoutBuilder layoutBuilder;
    private final InMemoryDocumentStore documents;
    private final OperationRouteIndex routes;
    private final ContractsActiveSourceTimelineIndex activeSourceTimelines;
    private final ContractsClosureExecutionMetricsObserver executionObserver;
    private final BlueClosureContracts contracts;
    private final ManagedEpochInvocationCapturer invocationCapturer;
    private ManagedEpochApplicationWork pendingRouteReconciliation;

    ManagedEpochApplicationExecutor(
            ContractsClosureAdapter host,
            BlueRuntime runtime,
            WholeObjectStore objects,
            EmbeddedOnlyLayoutBuilder layoutBuilder,
            InMemoryDocumentStore documents,
            OperationRouteIndex routes,
            ContractsClosureProfile profile,
            ContractsActiveSourceTimelineIndex activeSourceTimelines,
            ClosureEnvironment environment,
            ContractsClosureExecutionMetricsObserver executionObserver,
            BlueClosureContracts contracts) {
        this.host = Objects.requireNonNull(host, "host");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.layoutBuilder = Objects.requireNonNull(
                layoutBuilder, "layoutBuilder");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.activeSourceTimelines = Objects.requireNonNull(
                activeSourceTimelines, "activeSourceTimelines");
        this.executionObserver = Objects.requireNonNull(
                executionObserver, "executionObserver");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.invocationCapturer = new ManagedEpochInvocationCapturer(
                host,
                runtime,
                objects,
                documents,
                profile,
                environment);
        registerStructuralCounters();
    }

    /** Executes at most one response-loss repair or canonically due step. */
    Optional<ContractsClosureAdapter.ManagedApplicationOutcome> processNext() {
        return processNext(Set.of());
    }

    /** Selects one exact due step outside consumers failed in this drain. */
    Optional<ContractsClosureAdapter.ManagedApplicationOutcome> processNext(
            Set<DocumentId> excludedConsumers) {
        Set<DocumentId> excluded = Set.copyOf(Objects.requireNonNull(
                excludedConsumers, "excludedConsumers"));
        ManagedEpochApplicationWork pending = pendingRouteReconciliation;
        if (pending != null) {
            return Optional.of(execute(pending));
        }
        return documents.nextCatchUpWorkExcluding(excluded)
                .map(work -> execute(work, excluded));
    }

    /** Executes one exact idempotent occurrence-specific source epoch. */
    ContractsClosureAdapter.ManagedApplicationOutcome execute(
            ManagedEpochApplicationWork work) {
        return execute(work, Set.of());
    }

    ContractsClosureAdapter.ManagedApplicationOutcome execute(
            ManagedEpochApplicationWork work,
            Set<DocumentId> excludedConsumers) {
        ManagedEpochApplicationWork selected = Objects.requireNonNull(
                work, "work");
        Optional<ManagedEpochApplicationReceipt> prior =
                documents.catchUpApplicationByWork(
                        selected.workIdentity());
        if (prior.isPresent()) {
            ContractsClosurePublicationReceipt retained = documents
                    .closureReceiptForApplication(prior.orElseThrow())
                    .orElseThrow(() -> new IllegalStateException(
                            "Managed application has no retained Contracts "
                                    + "publication receipt "
                                    + selected.workIdentity()));
            if (!retained.commits()) {
                throw new IllegalStateException(
                        "A committed managed application retained a rollback");
            }
            reconcilePublication(retained);
            clearPendingReconciliation(selected);
            return new ContractsClosureAdapter.ManagedApplicationOutcome(
                    selected,
                    retained.attempt(),
                    prior.get(),
                    true,
                    true,
                    retained.automaticRetryCount(),
                    Optional.empty(),
                    List.of(),
                    Optional.empty());
        }

        WholeObjectStore.Mark attemptMark = objects.mark();
        boolean attemptMarkClosed = false;
        try {
            ManagedEpochInvocationCapturer.Capture initialCapture =
                    invocationCapturer.capture(
                            selected, excludedConsumers);
            AutomaticOccurrenceResolutionCoordinator.RunResult<
                    ContractsClosureAdapter.CohortInvocation,
                    ContractsClosurePublicationReceipt> automatic = host
                            .resolveManagedApplicationOccurrences(
                                    initialCapture.invocation());
            if (automatic.replayed()) {
                throw new IllegalStateException(
                        "A retained managed application cannot replay through "
                                + "occurrence resolution");
            }
            runtime.metrics().add(
                    PROCESS_CALLS,
                    Math.addExact(automatic.expansionCount(), 1L));
            ClosureAttemptResult attempt = automatic.attempt();
            recordExecutionEvidence();
            if (!attempt.isComplete()
                    || !attempt.processResult().commits()) {
                ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                        new ContractsClosureAdapter
                                .ManagedApplicationOutcome(
                                selected,
                                attempt,
                                null,
                                false,
                                false,
                                automatic.expansionCount(),
                                automatic.automaticResolutionStopReason(),
                                automatic.unresolvedDemands(),
                                Optional.empty());
                objects.rollbackTo(attemptMark);
                attemptMarkClosed = true;
                return outcome;
            }
            ManagedEpochInvocationCapturer.Capture capture =
                    new ManagedEpochInvocationCapturer.Capture(
                            initialCapture.work(),
                            initialCapture.sourceReceipt(),
                            initialCapture.sourceTransitionReceipt(),
                            automatic.invocation());
            ClosureProcessResult result = attempt.processResult();
            RootedTerminalEvidence terminal = RootedTerminalEvidence.captureHistorical(capture.invocation(), result, selected);
            ContractsClosurePublicationReceipt receipt = new ContractsClosurePublicationReceipt(
                    terminal == null ? selected.workIdentity() : capture.invocation().rootedEvidence().terminalKey(),
                    RootedResultScope.members(result), attempt, automatic.expansionCount(),
                    ManagedSurfacePublicationEvidence.committed(capture.invocation(), result), null, terminal);
            ManagedEpochApplicationReceipt application;
            try {
                application = publish(capture, receipt, excludedConsumers);
            } catch (UnsupportedNestedNewLineageException failure) {
                objects.rollbackTo(attemptMark);
                attemptMarkClosed = true;
                documents.recordManagedEpochApplicationFailure(
                        selected,
                        CoordinationErrorCode.UNSUPPORTED_NESTED_NEW_LINEAGE
                                .name(),
                        failure.getMessage());
                return new ContractsClosureAdapter.ManagedApplicationOutcome(
                        selected,
                        attempt,
                        null,
                        false,
                        false,
                        automatic.expansionCount(),
                        Optional.empty(),
                        List.of(),
                        Optional.of(new ContractsClosureAdapter
                                .ManagedApplicationPublicationFailure(
                                CoordinationErrorCode
                                        .UNSUPPORTED_NESTED_NEW_LINEAGE,
                                failure.getMessage(),
                                failure.details())));
            }
            ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                    new ContractsClosureAdapter.ManagedApplicationOutcome(
                            selected,
                            attempt,
                            application,
                            true,
                            false,
                            automatic.expansionCount(),
                            Optional.empty(),
                            List.of(),
                            Optional.empty());
            objects.commit(attemptMark);
            attemptMarkClosed = true;
            return outcome;
        } catch (RuntimeException | Error failure) {
            if (!attemptMarkClosed) {
                if (pendingReconciliationFor(selected)) {
                    objects.commit(attemptMark);
                } else {
                    objects.rollbackTo(attemptMark);
                }
            }
            throw failure;
        }
    }

    private boolean pendingReconciliationFor(
            ManagedEpochApplicationWork work) {
        ManagedEpochApplicationWork pending = pendingRouteReconciliation;
        return pending != null && pending.workIdentity().equals(
                Objects.requireNonNull(work, "work").workIdentity());
    }

    /** Drops only the disposable repair hint after durable routes rebuild. */
    void resetAfterRouteRebuild() {
        pendingRouteReconciliation = null;
    }

    private ManagedEpochApplicationReceipt publish(
            ManagedEpochInvocationCapturer.Capture capture,
            ContractsClosurePublicationReceipt receipt,
            Set<DocumentId> excludedConsumers) {
        ContractsClosureAdapter.CohortInvocation invocation =
                capture.invocation();
        ManagedEpochApplicationWork work = capture.work();
        ClosureProcessResult result = Objects.requireNonNull(
                receipt, "receipt").attempt().processResult();
        if (receipt.rootedTerminalEvidence() == null ? !receipt.publicationIdentity().equals(work.workIdentity())
                : !receipt.rootedTerminalEvidence().identifiesHistoricalWork(work)) {
            throw new IllegalArgumentException(
                    "Managed application receipt does not identify its work");
        }
        ContractsClosureAdapter.requirePublishableResult(invocation, result);
        Set<DocumentId> ownedMembers = new java.util.LinkedHashSet<>(RootedResultScope.members(result));
        Set<DocumentId> existingOwners = new java.util.LinkedHashSet<>(ownedMembers);
        existingOwners.retainAll(invocation.existingMemberSet());
        InMemoryDocumentStore.ClosureSnapshot current = documents.closureSnapshot(existingOwners);
        if (result.rootedProjection() == null) host.requireCohortStillCurrent(invocation, current);
        else host.requireRootedOwnersStillCurrent(invocation, result, current, existingOwners);
        String sourceCausalEntryBlueId = requireSourceCausalEntryBlueId(
                work, capture.sourceReceipt());
        ManagedOccurrenceInventory.DeltaResult inventoryDelta =
                result.rootedProjection() == null ? host.mergeInventory(current.occurrenceInventory(),
                        invocation.memberSet(), result.occurrenceBindings())
                        : host.mergeOwnedInventory(current.occurrenceInventory(), result);
        ManagedOccurrenceInventory resultingInventory =
                inventoryDelta.inventory();
        long resultingInventoryGeneration =
                ContractsClosureAdapter.transitionGeneration(
                        current.occurrenceInventoryGeneration(),
                        inventoryDelta.changed(),
                        "occurrence inventory generation");
        boolean topologyChanged =
                !ContractsClosureAdapter.sameActiveTopologyForSources(
                        current.occurrenceInventory(),
                        resultingInventory,
                        ownedMembers);
        long resultingComponentIndexGeneration =
                ContractsClosureAdapter.transitionGeneration(
                        current.componentIndexGeneration(),
                        topologyChanged,
                        "component index generation");
        MultiDocumentPublicationTransaction transaction = documents
                .beginAtomicPublication(
                        receipt.publicationIdentity(),
                        current.occurrenceInventoryGeneration(),
                        current.componentIndexGeneration());
        host.configureManagedApplicationTransaction(transaction);
        for (ContractsClosureAdapter.CapturedDocument document
                : invocation.documents().values()) {
            if (!existingOwners.contains(document.documentId())) continue;
            transaction.expectHead(
                    document.documentId(),
                    document.head().epoch(),
                    document.head().blueId());
            transaction.expectGraphGeneration(
                    document.documentId(), document.graphGeneration());
        }
        invocation.newMemberSet().stream().filter(ownedMembers::contains).forEach(transaction::expectAbsent);
        invocation.input().snapshot().components().stream()
                .filter(component -> component.orderedMemberDocumentIds()
                        .stream().allMatch(member -> existingOwners.contains(
                                        DocumentId.of(member.value()))))
                .forEach(transaction::expectComponentState);
        if (invocation.managedExpansion() || inventoryDelta.changed()) {
            transaction.stageOccurrenceInventory(
                    resultingInventory,
                    resultingInventoryGeneration,
                    resultingComponentIndexGeneration);
        }
        transaction.stageComponentStates(RootedResultScope.components(result));
        if (invocation.managedExpansion()) {
            transaction.stageManagedExpansionResult(invocation.input(), result);
        } else {
            transaction.stageClosureGraphGeneration(result);
            transaction.stageClosureSubscriptionDeltas(result);
        }
        transaction.stageOutbox(RootedResultScope.events(result));
        transaction.stageCheckpointEvidence(RootedResultScope.checkpoints(result));

        Map<DocumentId, ResultingDocument> resultingDocuments =
                ContractsClosureAdapter.resultingDocuments(
                        result, invocation.memberSet());
        Map<DocumentId, ManagedDocumentTransitionReceipt>
                transitionReceipts =
                        ContractsClosureAdapter.transitionReceipts(
                                result, resultingDocuments.keySet());
        ClosureSubscriptionInventory resultingClosureSubscriptions =
                ContractsClosureAdapter.capturedSubscriptions(invocation, current.closureSubscriptions()).apply(
                        result,
                        ContractsClosureAdapter.capturedGraphGenerations(
                                invocation.documents().values()));
        Map<DocumentId, ManagedCatchUpPlanner.Head> resultingHeads =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        Map<DocumentId, ManagedEpochReceipt> committedEpochReceipts =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        ExternalOrderKey causalOrder = capture.sourceReceipt()
                .sourceOrder()
                .orElseThrow(() -> new IllegalStateException(
                        "A catch-up source receipt requires causal order"));
        TimelineEntry causalEntry = capture.sourceReceipt()
                .sourceEntry()
                .orElse(null);
        WholeObjectStore.Mark objectMark = objects.mark();
        boolean storeCommitted = false;
        try {
            List<OperationRouteIndex.Replacement> routeReplacements =
                    new ArrayList<>();
            Map<DocumentId, List<SubscriptionDelta.Entry>> viewRoutes = new java.util.LinkedHashMap<>();
            for (Map.Entry<DocumentId, ResultingDocument> entry
                    : resultingDocuments.entrySet()) {
                ContractsClosureAdapter.CapturedDocument before =
                        invocation.documents().get(entry.getKey());
                if (before == null) {
                    ManagedEpochReceipt birth = stageNewLineage(
                            capture, transaction, result, entry.getValue(),
                            transitionReceipts.get(entry.getKey()),
                            resultingClosureSubscriptions, routeReplacements,
                            causalOrder, sourceCausalEntryBlueId);
                    resultingHeads.put(entry.getKey(),
                            new ManagedCatchUpPlanner.Head(0L,
                                    birth.afterBlueId()));
                    committedEpochReceipts.put(entry.getKey(), birth);
                    continue;
                }
                ResultingDocument after = entry.getValue();
                ManagedDocumentTransitionReceipt transition =
                        transitionReceipts.get(entry.getKey());
                if (!ownedMembers.contains(entry.getKey())) {
                    ManagedRootSubscriptionSurface projected = contracts.projectRootSubscriptionSurface(after.document());
                    EmbeddedOnlyLayout layout = layoutBuilder.retainVerifiedClosureRoot(result, entry.getKey(), before.layout(), projected);
                    ContractsClosureAdapter.requireExactRootSubscriptionSurface(entry.getKey(), projected,
                            host.subscriptionStatesFor(resultingClosureSubscriptions, entry.getKey()));
                    List<SubscriptionDelta.Entry> localRoutes;
                    if (before.head().epoch() == after.epoch() && before.head().blueId().equals(after.afterBlueId())) {
                        ContractsClosureAdapter.requireUnchangedRouteSurface(entry.getKey(), before.activeSubscriptions(),
                                projected.externalSubscriptions());
                        localRoutes = before.activeSubscriptions();
                    } else {
                        SubscriptionDelta delta = ContractsClosureAdapter.routeDelta(before.activeSubscriptions(),
                                projected.externalSubscriptions(), after.epoch(), causalOrder);
                        localRoutes = DocumentTransitionProcessor.applyManagedRootSubscriptionDelta(
                                before.activeSubscriptions(), delta, after.epoch(), causalOrder, runtime.metrics());
                    }
                    CheckpointDomainEvidence.retainAll(localRoutes, objects);
                    objects.put(layout.semanticRoot(), "rooted-retained-local-view");
                    viewRoutes.put(entry.getKey(), localRoutes);
                    continue;
                }
                boolean componentRepresentationRebind =
                        isIndirectComponentRepresentationRebind(
                                invocation,
                                entry.getKey(),
                                before,
                                after,
                                transition);
                boolean checkpointSettlementChange =
                        !componentRepresentationRebind
                                && ContractsClosureAdapter
                                        .isVerifiedCheckpointSettlementChange(
                                                result,
                                                entry.getKey(),
                                                before.head(),
                                                after,
                                                transition);
                boolean changed = componentRepresentationRebind
                        || ContractsClosureAdapter
                                .requiresDocumentPublication(
                                        result, before, after, transition);
                if (changed
                        && entry.getKey().equals(work.sourceDocumentId())
                        && !componentRepresentationRebind
                        && !isVerifiedRetainedSourceReferenceAdvance(
                                invocation, result, entry.getKey(), before,
                                after, transition, current.occurrenceInventory(),
                                resultingInventory)) {
                    throw new ContractsClosureAdapter
                            .ProjectionUnavailableException(
                            "A retained managed application cannot append or "
                                    + "reinterpret its immutable source "
                                    + "history " + entry.getKey());
                }
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
                ContractsClosureAdapter.requireExactRootSubscriptionSurface(
                        entry.getKey(),
                        projected,
                        host.subscriptionStatesFor(
                                resultingClosureSubscriptions,
                                entry.getKey()));
                List<SubscriptionDelta.Entry> activeSubscriptionsAfter;
                if (stateChanged && !componentRepresentationRebind) {
                    long projectionEpoch = checkpointSettlementChange
                            ? Math.addExact(before.head().epoch(), 1L)
                            : after.epoch();
                    SubscriptionDelta routeDelta =
                            ContractsClosureAdapter.routeDelta(
                                    before.activeSubscriptions(),
                                    projected.externalSubscriptions(),
                                    projectionEpoch,
                                    causalOrder);
                    activeSubscriptionsAfter = DocumentTransitionProcessor
                            .applyManagedRootSubscriptionDelta(
                                    before.activeSubscriptions(),
                                    routeDelta,
                                    projectionEpoch,
                                    causalOrder,
                                    runtime.metrics());
                } else {
                    ContractsClosureAdapter.requireUnchangedRouteSurface(
                            entry.getKey(),
                            before.activeSubscriptions(),
                            projected.externalSubscriptions());
                    activeSubscriptionsAfter = before.activeSubscriptions();
                }
                CheckpointDomainEvidence.retainAll(
                        activeSubscriptionsAfter, objects);
                viewRoutes.put(entry.getKey(), activeSubscriptionsAfter);
                routeReplacements.add(
                        new OperationRouteIndex.Replacement(
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
                    throw new ContractsClosureAdapter
                            .ProjectionUnavailableException(
                            "Managed application changed a document without "
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
                        "managed-epoch-application-revision");
                List<Node> emitted = result.publicEvents().stream()
                        .filter(event -> event.publicRootDocumentId().value()
                                .equals(entry.getKey().value()))
                        .map(PublicEventOccurrence::event)
                        .toList();
                long coordinationEpoch = Math.addExact(
                        before.head().epoch(), 1L);
                ManagedEpochReceipt epochReceipt =
                        ManagedEpochReceiptMapper.map(
                                entry.getKey(),
                                coordinationEpoch,
                                DocumentRevision.Kind
                                        .EMBEDDED_REVISION_APPLICATION,
                                before.current(),
                                exact,
                                causalEntry,
                                causalOrder,
                                transition,
                                result.platformCommitCompanion());
                DocumentRevision revision = new DocumentRevision(
                        entry.getKey(),
                        coordinationEpoch,
                        before.nextApplicationOrder(),
                        DocumentRevision.Kind
                                .EMBEDDED_REVISION_APPLICATION,
                        before.current(),
                        exact,
                        null,
                        causalOrder,
                        sourceCausalEntryBlueId,
                        null,
                        emitted,
                        transition.admittedGas(),
                        epochReceipt);
                transaction.stageDocument(
                        revision,
                        layout,
                        causalOrder,
                        activeSubscriptionsAfter,
                        after.terminated(),
                        work.workIdentity() + "|" + entry.getKey().value());
                transaction.stageManagedEpochReceipt(
                        epochReceipt, transition);
                resultingHeads.put(
                        entry.getKey(),
                        new ManagedCatchUpPlanner.Head(
                                coordinationEpoch, exact.blueId()));
                committedEpochReceipts.put(entry.getKey(), epochReceipt);
            }

            ManagedEpochReceipt consumerRevision =
                    committedEpochReceipts.get(work.consumerDocumentId());
            if (consumerRevision == null) {
                ContractsClosureAdapter.CapturedDocument before = invocation
                        .documents().get(work.consumerDocumentId());
                ResultingDocument after = resultingDocuments.get(
                        work.consumerDocumentId());
                if (before == null
                        || after == null
                        || transitionReceipts.containsKey(
                                work.consumerDocumentId())
                        || !after.initialized()
                        || after.terminated() != before.terminated()
                        || after.epoch() != before.head().epoch()
                        || !after.beforeBlueId().equals(
                                before.head().blueId())
                        || !after.afterBlueId().equals(
                                before.head().blueId())) {
                    throw new ContractsClosureAdapter
                            .ProjectionUnavailableException(
                            "Managed application omitted its consumer "
                                    + "revision without one exact unchanged, "
                                    + "eventless Contracts result "
                                    + work.consumerDocumentId());
                }
                long coordinationEpoch = Math.addExact(
                        before.head().epoch(), 1L);
                consumerRevision = ManagedEpochReceiptMapper
                        .mapEventlessApplication(
                                work,
                                coordinationEpoch,
                                before.current(),
                                causalEntry,
                                causalOrder,
                                result);
                DocumentRevision revision = new DocumentRevision(
                        work.consumerDocumentId(),
                        coordinationEpoch,
                        before.nextApplicationOrder(),
                        DocumentRevision.Kind
                                .EMBEDDED_REVISION_APPLICATION,
                        before.current(),
                        before.current(),
                        null,
                        causalOrder,
                        sourceCausalEntryBlueId,
                        null,
                        List.of(),
                        result.totalGas(),
                        consumerRevision);
                transaction.stageDocument(
                        revision,
                        before.layout(),
                        causalOrder,
                        before.activeSubscriptions(),
                        after.terminated(),
                        work.workIdentity() + "|"
                                + work.consumerDocumentId().value());
                transaction.stageManagedEpochApplicationReceipt(
                        consumerRevision, work);
                resultingHeads.put(
                        work.consumerDocumentId(),
                        new ManagedCatchUpPlanner.Head(
                                coordinationEpoch,
                                before.head().blueId()));
                committedEpochReceipts.put(
                        work.consumerDocumentId(), consumerRevision);
            }
            objects.retainVerifiedClosureComponentEvidence(result);
            ManagedEpochApplicationReceipt application =
                    ManagedEpochApplicationReceipt.identified(
                            work.workIdentity(),
                            work.planIdentity(),
                            work.sourceReceiptIdentity(),
                            result.invocationIdentity(),
                            result.outputClosureIdentity(),
                            result.platformCommitCompanion()
                                    .companionIdentity(),
                            work.consumerDocumentId(),
                            consumerRevision.epoch(),
                            consumerRevision.receiptIdentity(),
                            consumerRevision.afterBlueId(),
                            Math.addExact(work.sourceEpoch(), 1L));
            if (work.isRepresentationApplication()) {
                var completedOccurrence = resultingInventory.find(work.consumerDocumentId(), work.targetPath())
                        .orElseThrow(() -> new IllegalStateException("Representation application lost its owning occurrence"));
                var cause = work.representationCause().orElseThrow();
                if (completedOccurrence.active() != cause.terminalPositionReached()
                        || (!completedOccurrence.active() && !java.util.Objects.equals(completedOccurrence.pendingHistoricalEpoch(), cause.fromEpoch()))) {
                    throw new IllegalStateException("Representation application changed the numbered cursor or activated before its captured tail");
                }
                application = ManagedEpochApplicationReceipt.identifiedRepresentation(application, work,
                        completedOccurrence.pendingRepresentationCursor());
            }
            CatchUpPlanStore beforeCatchUpPlans =
                    documents.catchUpPlansSnapshot();
            ManagedCatchUpBarrier owningBarrier = beforeCatchUpPlans
                    .barrier(work.barrierIdentity()).barrier();
            if (owningBarrier == null
                    || !owningBarrier.consumerDocumentId().equals(
                            work.consumerDocumentId())) {
                throw ContractsClosureAdapter.stale(
                        "Managed application lost its owning catch-up "
                                + "barrier " + work.barrierIdentity());
            }
            CatchUpPlanStore advancedCatchUpPlans = beforeCatchUpPlans
                    .withCommittedApplication(
                            work, application, excludedConsumers);
            ManagedCatchUpPlanner.PlanningResult catchUp =
                    ManagedCatchUpPlanner.afterPublication(
                            advancedCatchUpPlans,
                            current.occurrenceInventory(),
                            resultingInventory,
                            ownedMembers,
                            committedEpochReceipts.values(),
                            owningBarrier.causedByIdentity(),
                            owningBarrier.causeOrder(),
                            documentId -> result.rootedProjection() != null && !ownedMembers.contains(documentId)
                                    && capture.invocation().documents().containsKey(documentId)
                                    ? new ManagedCatchUpPlanner.Head(capture.invocation().documents().get(documentId).head().epoch(),
                                            capture.invocation().documents().get(documentId).head().blueId())
                                    : host.resultingManagedHead(resultingHeads, documentId),
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
                            documentId -> ownedMembers.contains(
                                    documentId)
                                    ? result.graphGeneration()
                                    : documents.graphGeneration(documentId),
                            new ManagedRepresentationHistory(documents).afterPublication(receipt, resultingHeads, committedEpochReceipts),
                            blueId -> objects.cyclicSetProofFor(blueId).proof().orElse(null), result.rootedProjection() != null);
            transaction.stageCatchUpPlans(
                    beforeCatchUpPlans, catchUp.plans());
            OperationRouteIndex.PreparedReplacement preparedRoutes = routes
                    .prepareReplacement(routeReplacements);
            ContractsClosurePublicationReceipt retainedReceipt = receipt
                    .withOperationRouteChanges(
                            preparedRoutes.operationRouteChanges());
            transaction.stageClosurePublicationReceipt(retainedReceipt);
            if (result.rootedProjection() != null) transaction.stageRootedView(
                    new RootedDocumentView(result, resultingClosureSubscriptions, viewRoutes, owningBarrier.causeOrder()));
            transaction.commit();
            storeCommitted = true;
            runtime.metrics().increment(OCCURRENCES_ADVANCED);
            runtime.metrics().increment(
                    "process.embeddedEpochProcessCalls");
            runtime.metrics().increment(
                    "embedding.parentEpochApplications");
            host.injectManagedApplicationPublicationFailure();
            preparedRoutes.publish();
            activeSourceTimelines.refresh(ownedMembers, documents);
            objects.commit(objectMark);
            clearPendingReconciliation(work);
            return application;
        } catch (RuntimeException failure) {
            if (storeCommitted) {
                pendingRouteReconciliation = work;
                objects.commit(objectMark);
            } else {
                objects.rollbackTo(objectMark);
            }
            throw failure;
        }
    }

    /** Stages one Contracts-authenticated birth in the consumer transaction. */
    private ManagedEpochReceipt stageNewLineage(
            ManagedEpochInvocationCapturer.Capture capture,
            MultiDocumentPublicationTransaction transaction,
            ClosureProcessResult result,
            ResultingDocument after,
            ManagedDocumentTransitionReceipt transition,
            ClosureSubscriptionInventory subscriptions,
            List<OperationRouteIndex.Replacement> routeReplacements,
            ExternalOrderKey causalOrder,
            String sourceCausalEntryBlueId) {
        DocumentId id = DocumentId.of(after.documentId().value());
        ContractsManagedDraftPlan.ManagedDraft draft = capture.invocation()
                .managedDraft(id);
        // A resulting body alone does not prove INITIALIZE or its emissions.
        // The Contracts receipt and companion must authenticate the birth,
        // including an eventless initialization that preserves the BlueId.
        if (draft == null || transition == null || after.epoch() != 0L
                || !after.initialized()
                || !draft.initial().blueId().equals(after.beforeBlueId())) {
            throw new UnsupportedNestedNewLineageException(capture.work(), id);
        }
        ManagedRootSubscriptionSurface projected = contracts
                .projectRootSubscriptionSurface(after.document());
        transaction.stageEmbeddedDemands(id,
                ClosureSubscriptionInventory.embeddedDemands(projected));
        EmbeddedOnlyLayout layout = layoutBuilder.retainVerifiedClosureRoot(
                result, id, projected);
        ContractsClosureAdapter.requireExactRootSubscriptionSurface(id,
                projected, host.subscriptionStatesFor(subscriptions, id));
        List<SubscriptionDelta.Entry> active = ContractsClosureAdapter
                .activateInitialSubscriptions(
                        projected.externalSubscriptions(), causalOrder);
        CheckpointDomainEvidence.retainAll(active, objects);
        ExactValue authored = objects.put(draft.initial(),
                "retained-application-authored-birth");
        ExactValue initialized = objects.put(layout.semanticRoot(),
                "retained-application-initialized-birth");
        ManagedEpochReceipt birth = ManagedEpochReceiptMapper.map(id, 0L,
                DocumentRevision.Kind.INITIALIZATION, authored, initialized,
                null, causalOrder, transition, result.platformCommitCompanion());
        List<Node> emitted = result.publicEvents().stream()
                .filter(event -> event.publicRootDocumentId().value()
                        .equals(id.value()))
                .map(PublicEventOccurrence::event).toList();
        DocumentRevision revision = new DocumentRevision(id, 0L, 0L,
                DocumentRevision.Kind.INITIALIZATION, authored, initialized,
                null, causalOrder, sourceCausalEntryBlueId, null, emitted,
                transition.admittedGas(), birth);
        DocumentSession session = new DocumentSession(id, authored, layout,
                active, causalOrder, revision);
        session.restoreCoordinationState(after.terminated()
                        ? SessionStatus.TERMINATED : SessionStatus.READY,
                causalOrder, 0L, 0L);
        transaction.stageNewSession(session);
        transaction.stageManagedEpochReceipt(birth, transition);
        routeReplacements.add(new OperationRouteIndex.Replacement(id,
                layout.routingSurface(), active));
        return birth;
    }

    /**
     * Recognizes every existing member finalized indirectly by this retained
     * application. The directly delivered consumer remains on the ordinary
     * revision lane; an indirect member may only change its representation
     * at the same own epoch under one exact eventless Contracts transition.
     */
    /** Allows only a new eventless revision of an indirect current source. */
    private static boolean isVerifiedRetainedSourceReferenceAdvance(
            ContractsClosureAdapter.CohortInvocation invocation,
            ClosureProcessResult result,
            DocumentId documentId,
            ContractsClosureAdapter.CapturedDocument before,
            ResultingDocument after,
            ManagedDocumentTransitionReceipt transition,
            ManagedOccurrenceInventory prior,
            ManagedOccurrenceInventory next) {
        if (!result.commits()
                || !before.initialized()
                || before.terminated()
                || !after.initialized()
                || after.terminated()
                || invocation.publicationIdentityMembers().contains(documentId)
                || after.epoch() != Math.addExact(before.head().epoch(), 1L)
                || !after.beforeBlueId().equals(before.head().blueId())
                || after.afterBlueId().equals(before.head().blueId())
                || transition == null
                || !transition.documentId().value().equals(documentId.value())
                || !transition.sourceInvocationIdentity().equals(
                        result.invocationIdentity())
                || !transition.beforeBlueId().equals(before.head().blueId())
                || !transition.afterBlueId().equals(after.afterBlueId())
                || !transition.emittedRootEvents().isEmpty()) {
            return false;
        }
        // The existing publication path has already verified the complete
        // result, commit companion, exact input/current-head fences and source
        // receipt. Authenticate both complete occurrence inventories and all
        // other parent fields before permitting an ordinary appended revision.
        return ManagedSourceReferenceRewrite.verifies(
                before.current().copyNode(), after.document(),
                prior.rowsFrom(documentId), next.rowsFrom(documentId),
                invocation.documents().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().value(), entry -> entry.getValue().current().copyNode())),
                result.resultingDocuments().stream().collect(java.util.stream.Collectors.toMap(
                        row -> row.documentId().value(), ResultingDocument::document)));
    }

    private static boolean isIndirectComponentRepresentationRebind(
            ContractsClosureAdapter.CohortInvocation invocation,
            DocumentId documentId,
            ContractsClosureAdapter.CapturedDocument before,
            ResultingDocument after,
            ManagedDocumentTransitionReceipt transition) {
        return before.initialized()
                && !before.terminated()
                && after.initialized()
                && !after.terminated()
                && !invocation.publicationIdentityMembers().contains(
                        documentId)
                && after.epoch() == before.head().epoch()
                && after.beforeBlueId().equals(before.head().blueId())
                && !after.afterBlueId().equals(before.head().blueId())
                && transition != null
                && transition.documentId().value().equals(documentId.value())
                && transition.beforeBlueId().equals(before.head().blueId())
                && transition.afterBlueId().equals(after.afterBlueId())
                && transition.emittedRootEvents().isEmpty();
    }

    private void reconcilePublication(
            ContractsClosurePublicationReceipt receipt) {
        List<OperationRouteIndex.Replacement> replacements =
                new ArrayList<>();
        for (DocumentId documentId : Objects.requireNonNull(
                receipt, "receipt").documentIds()) {
            DocumentSession session = documents.require(documentId);
            synchronized (session) {
                replacements.add(new OperationRouteIndex.Replacement(
                        documentId,
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
            }
        }
        routes.prepareReplacement(replacements).publish();
        activeSourceTimelines.refresh(receipt.documentIds(), documents);
    }

    /**
     * Resolves the plain causal BlueId from the immutable source revision.
     * Receipt identities remain the SHA-256 evidence keys used by plans and
     * barriers; they are deliberately not substituted for document causality.
     */
    private String requireSourceCausalEntryBlueId(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt sourceReceipt) {
        DocumentRevision sourceRevision = documents
                .require(work.sourceDocumentId())
                .revision(work.sourceEpoch());
        ManagedEpochReceipt revisionReceipt = sourceRevision
                .managedEpochReceipt()
                .orElseThrow(() -> ManagedEpochEvidenceException.blocked(
                        work,
                        ManagedEpochEvidenceException
                                .RECEIPT_IDENTITY_MISMATCH,
                        "Source revision has no immutable managed epoch "
                                + "receipt for " + work.workIdentity()));
        if (!revisionReceipt.receiptIdentity().equals(
                        sourceReceipt.receiptIdentity())
                || !sourceRevision.after().blueId().equals(
                        sourceReceipt.afterBlueId())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.RECEIPT_IDENTITY_MISMATCH,
                    "Source revision disagrees with its immutable managed "
                            + "epoch receipt");
        }
        return sourceRevision.causalEntryBlueId()
                .orElseThrow(() -> ManagedEpochEvidenceException.blocked(
                        work,
                        ManagedEpochEvidenceException
                                .ORIGINAL_CAUSE_MISMATCH,
                        "Source revision has no exact causal BlueId for "
                                + work.workIdentity()));
    }

    private static ManagedEpochEvidenceException mismatch(
            ManagedEpochApplicationWork work,
            String code,
            String message) {
        return ManagedEpochEvidenceException.blocked(
                work, code, message + " for " + work.workIdentity());
    }

    private void recordExecutionEvidence() {
        Optional<ClosureImplementationEvidence> evidence =
                executionObserver.lastEvidence();
        if (evidence.isPresent()) {
            runtime.metrics().add(
                    COMPONENT_FINALIZATIONS,
                    evidence.get().tentativeFinalizations().size());
        }
    }

    private void clearPendingReconciliation(
            ManagedEpochApplicationWork completed) {
        if (pendingRouteReconciliation != null
                && pendingRouteReconciliation.workIdentity().equals(
                        completed.workIdentity())) {
            pendingRouteReconciliation = null;
        }
    }

    private void registerStructuralCounters() {
        EngineMetrics metrics = runtime.metrics();
        metrics.add(SOURCE_RECEIPTS_READ, 0L);
        metrics.add(PLANS_OPENED, 0L);
        metrics.add(OCCURRENCES_ADVANCED, 0L);
        metrics.add(AFFECTED_DOCUMENTS_OPENED, 0L);
        metrics.add(PROCESS_CALLS, 0L);
        // This executor has no source Timeline dispatch or global-scan lane.
        // Register both zero producers so an absent metric is never mistaken
        // for evidence; any future such lane must own a matching increment.
        metrics.add(SOURCE_PROCESS_CALLS, 0L);
        metrics.add(COMPONENT_FINALIZATIONS, 0L);
        metrics.add(UNRELATED_DOCUMENTS_SCANNED, 0L);
    }

}
