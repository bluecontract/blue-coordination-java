package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic in-memory document store. */
final class InMemoryDocumentStore {
    /**
     * Explicit snapshots that materialize every durable document head.
     *
     * <p>This is the deliberately narrow source of the public
     * FULL_ENVIRONMENT_SCANS counter. Remaining global publication-state
     * traversals are reported separately by {@link ContractsStructuralWorkMetrics}.</p>
     */
    static final String FULL_ENVIRONMENT_SCANS =
            "temporal.fullEnvironmentScans";
    static final String CLOSURE_TOPOLOGY_SNAPSHOTS =
            "contracts.closure.topologySnapshots";
    static final String CLOSURE_HEADS_CAPTURED =
            "contracts.closure.headsCaptured";
    static final String CLOSURE_COMPONENT_STATES_CAPTURED =
            "contracts.closure.componentStatesCaptured";
    static final String MANAGED_RECEIPT_ROWS_OPENED =
            "managedEpoch.store.receiptRowsOpened";
    static final String MANAGED_PLAN_ROWS_OPENED =
            "managedEpoch.store.planRowsOpened";
    static final String MANAGED_BARRIER_ROWS_OPENED =
            "managedEpoch.store.barrierRowsOpened";
    static final String MANAGED_APPLICATION_RECEIPT_ROWS_OPENED =
            "managedEpoch.store.applicationReceiptRowsOpened";
    private static final String UNRELATED_DOCUMENT_READS =
            "temporal.unrelatedDocumentReads";

    private final EngineMetrics metrics;
    private StoreState state;

    InMemoryDocumentStore() {
        this(new EngineMetrics());
    }

    InMemoryDocumentStore(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        registerExactReadCounters();
        state = StoreState.empty();
    }

    public synchronized Optional<DocumentSession> find(DocumentId documentId) {
        return Optional.ofNullable(state.sessions().get(
                Objects.requireNonNull(documentId, "documentId")));
    }

    public synchronized DocumentSession require(DocumentId documentId) {
        return find(documentId).orElseThrow(() ->
                new IllegalArgumentException("Unknown document " + documentId));
    }

    public synchronized void insert(DocumentSession session) {
        Objects.requireNonNull(session, "session");
        if (state.sessions().containsKey(session.documentId())) {
            throw new IllegalArgumentException(
                    "Duplicate document session " + session.documentId());
        }
        Map<DocumentId, DocumentSession> nextSessions = new LinkedHashMap<>(
                state.sessions());
        nextSessions.put(session.documentId(), session);
        ProcessEmbeddedComponentIndex nextIndex = componentIndex(
                nextSessions.values(), state.occurrenceInventory());
        state = state.withSessions(
                nextSessions,
                state.lineageIndex().withNewLineage(session),
                nextIndex,
                increment(state.componentIndexGeneration(),
                        "component index generation"));
    }

    public synchronized void remove(DocumentId documentId) {
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        if (!state.sessions().containsKey(selected)) {
            return;
        }
        Map<DocumentId, DocumentSession> nextSessions = new LinkedHashMap<>(
                state.sessions());
        nextSessions.remove(selected);
        ProcessEmbeddedComponentIndex nextIndex = componentIndex(
                nextSessions.values(), state.occurrenceInventory());
        state = state.withSessions(
                nextSessions,
                state.lineageIndex().withoutLineage(selected),
                nextIndex,
                increment(state.componentIndexGeneration(),
                        "component index generation"));
    }

    public synchronized Collection<DocumentSession> sessions() {
        return Collections.unmodifiableList(
                new ArrayList<>(state.sessions().values()));
    }

    public synchronized int size() {
        return state.sessions().size();
    }

    /** Current immutable occurrence topology without opening document heads. */
    synchronized ManagedOccurrenceInventory occurrenceInventory() {
        return state.occurrenceInventory();
    }

    /** Exact immutable managed-lineage indexes without a session scan. */
    synchronized ManagedLineageIndex lineageIndex() {
        return state.lineageIndex();
    }

    /** Exact immutable catch-up indexes for one transaction attempt. */
    synchronized CatchUpPlanStore catchUpPlansSnapshot() {
        return state.catchUpPlans();
    }

    /**
     * Captures the exact immutable indexes used by one occurrence-resolution
     * attempt without opening or scanning document sessions.
     */
    synchronized OccurrenceResolutionSnapshot occurrenceResolutionSnapshot() {
        return new OccurrenceResolutionSnapshot(
                state.lineageIndex(),
                state.occurrenceInventory(),
                state.componentIndex(),
                state.occurrenceInventoryGeneration(),
                state.componentIndexGeneration());
    }

    EngineMetrics metrics() {
        return metrics;
    }

    synchronized StoreStructureSnapshot storeStructureSnapshotForTesting() {
        return new StoreStructureSnapshot(
                state.sessionIndex(),
                state.componentIndex(),
                state.componentStateInventory(),
                state.outboxLog(),
                state.checkpointEvidenceLog(),
                state.publicationReceiptIndex(),
                state.admissionReceiptIndex(),
                state.closurePublicationReceiptIndex());
    }

    /** Captures every durable head plus immutable publication evidence. */
    synchronized PublicationSnapshot publicationSnapshot() {
        metrics.increment(FULL_ENVIRONMENT_SCANS);
        return PublicationSnapshot.from(state);
    }

    /** Captures immutable topology indexes without opening document heads. */
    synchronized ClosureTopologySnapshot closureTopologySnapshot() {
        metrics.increment(CLOSURE_TOPOLOGY_SNAPSHOTS);
        return new ClosureTopologySnapshot(
                state.occurrenceInventoryGeneration(),
                state.componentIndexGeneration(),
                state.occurrenceInventory(),
                state.componentIndex(),
                state.closureSubscriptions());
    }

    /** Opens one exact durable head, graph generation, and component proof. */
    synchronized ManagedReadSnapshot managedReadSnapshot(
            DocumentId documentId) {
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        PersistentOrderedMap.ReadResult<DocumentSession> sessionRead =
                state.sessionIndex().read(selected);
        metrics.add(
                ContractsClosureAdapter.DOCUMENT_OPENS,
                sessionRead.found() ? 1L : 0L);
        if (!sessionRead.found()) {
            throw new IllegalArgumentException("Unknown document " + selected);
        }
        ComponentStateInventory.ComponentStateRead componentRead =
                state.componentStateInventory().forDocumentRead(selected);
        metrics.add(
                ContractsClosureAdapter.COMPONENT_STATES_READ,
                componentRead.componentRowsRead());
        metrics.add(
                UNRELATED_DOCUMENT_READS,
                componentRead.unrelatedDocumentReads());
        DocumentSession session = sessionRead.value();
        return new ManagedReadSnapshot(
                new DocumentHead(
                        session.epoch(),
                        session.currentRepresentation().blueId()),
                state.graphGenerations().require(selected),
                componentRead.component());
    }

    /** Opens only the exact parent's active occurrence bucket. */
    synchronized List<ManagedOccurrenceBinding> activeOccurrencesFrom(
            DocumentId sourceDocumentId) {
        ManagedOccurrenceInventory.RowsRead read = state.occurrenceInventory()
                .activeRowsFromRead(Objects.requireNonNull(
                        sourceDocumentId, "sourceDocumentId"));
        metrics.add(
                ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED,
                read.occurrenceRowsRead());
        metrics.add(
                UNRELATED_DOCUMENT_READS,
                read.unrelatedDocumentReads());
        return read.rows();
    }

    /** Captures only the durable heads and component states in one cohort. */
    synchronized ClosureSnapshot closureSnapshot(
            Collection<DocumentId> documentIds) {
        return closureSnapshot(documentIds, null);
    }

    /** Captures one cohort against the exact topology image used to select it. */
    synchronized ClosureSnapshot closureSnapshot(
            Collection<DocumentId> documentIds,
            ClosureTopologySnapshot expectedTopology) {
        return targetedClosureSnapshot(
                documentIds, expectedTopology, true);
    }

    /** Captures a static-admission partition, including the empty partition. */
    synchronized ClosureSnapshot admissionSnapshot(
            Collection<DocumentId> documentIds) {
        return targetedClosureSnapshot(documentIds, null, false);
    }

    private ClosureSnapshot targetedClosureSnapshot(
            Collection<DocumentId> documentIds,
            ClosureTopologySnapshot expectedTopology,
            boolean requireMember) {
        if (expectedTopology != null
                && (state.occurrenceInventoryGeneration()
                        != expectedTopology.occurrenceInventoryGeneration()
                || state.componentIndexGeneration()
                        != expectedTopology.componentIndexGeneration())) {
            throw new MultiDocumentPublicationTransaction
                    .AtomicPublicationCasException(
                            "Closure topology changed during targeted capture");
        }
        TreeMap<DocumentId, DocumentHead> heads = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : new LinkedHashSet<>(Objects.requireNonNull(
                documentIds, "documentIds"))) {
            DocumentSession session = state.sessions().get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (session == null) {
                throw new IllegalArgumentException(
                        "Unknown document " + documentId);
            }
            heads.put(documentId, new DocumentHead(
                    session.epoch(),
                    session.currentRepresentation().blueId()));
        }
        if (requireMember && heads.isEmpty()) {
            throw new IllegalArgumentException(
                    "A closure snapshot requires at least one document");
        }
        List<ComponentSnapshot> components = state.componentStatesFor(
                heads.keySet());
        metrics.add(CLOSURE_HEADS_CAPTURED, heads.size());
        metrics.add(CLOSURE_COMPONENT_STATES_CAPTURED, components.size());
        return new ClosureSnapshot(
                heads,
                state.occurrenceInventoryGeneration(),
                state.componentIndexGeneration(),
                state.occurrenceInventory(),
                state.graphGenerations(),
                components,
                state.closureSubscriptions(),
                state.publicationReceipts(),
                state.closurePublicationReceipts());
    }

    /**
     * Opens an unwired package-internal multi-document publication attempt.
     * The caller supplies its exact input fences explicitly; no ambient store
     * state is silently added to the affected closure.
     */
    synchronized MultiDocumentPublicationTransaction beginAtomicPublication(
            String publicationIdentity,
            long expectedOccurrenceInventoryGeneration,
            long expectedComponentIndexGeneration) {
        return new MultiDocumentPublicationTransaction(
                this,
                publicationIdentity,
                expectedOccurrenceInventoryGeneration,
                expectedComponentIndexGeneration);
    }

    /** Looks up one typed process receipt without opening document heads. */
    synchronized Optional<ContractsClosurePublicationReceipt>
            closurePublicationReceipt(String publicationIdentity) {
        return Optional.ofNullable(state.closurePublicationReceipts().get(
                Objects.requireNonNull(
                        publicationIdentity, "publicationIdentity")));
    }

    /** Looks up one typed admission receipt without opening document heads. */
    synchronized Optional<ContractsClosureAdmissionReceipt> admissionReceipt(
            String publicationIdentity) {
        return Optional.ofNullable(state.admissionReceipts().get(
                Objects.requireNonNull(
                        publicationIdentity, "publicationIdentity")));
    }

    /** Checks the generic idempotency ledger without opening document heads. */
    synchronized boolean hasPublicationReceipt(String publicationIdentity) {
        return state.publicationReceipts().contains(Objects.requireNonNull(
                publicationIdentity, "publicationIdentity"));
    }

    /** Reads one complete source receipt without opening a document session. */
    synchronized Optional<ManagedEpochReceipt> managedEpochReceipt(
            DocumentId documentId, long epoch) {
        ManagedEpochReceiptStore.ReceiptRead read = state.managedEpochReceipts()
                .exact(Objects.requireNonNull(documentId, "documentId"), epoch);
        recordExactRows(
                MANAGED_RECEIPT_ROWS_OPENED,
                read.receiptRowsRead(),
                read.unrelatedDocumentReads());
        return Optional.ofNullable(read.receipt());
    }

    /** Reads source receipts for exactly one lineage in contiguous order. */
    synchronized List<ManagedEpochReceipt> managedEpochReceipts(
            DocumentId documentId) {
        ManagedEpochReceiptStore.ReceiptAudit read = state
                .managedEpochReceipts()
                .audit(Objects.requireNonNull(documentId, "documentId"));
        recordExactRows(
                MANAGED_RECEIPT_ROWS_OPENED,
                read.receiptRowsRead(),
                read.unrelatedDocumentReads());
        return read.receipts();
    }

    /** Reads one public source receipt by its canonical identity. */
    synchronized Optional<ManagedEpochReceipt> managedEpochReceipt(
            String receiptIdentity) {
        ManagedEpochReceiptStore.ReceiptRead read = state.managedEpochReceipts()
                .byIdentity(Objects.requireNonNull(
                        receiptIdentity, "receiptIdentity"));
        recordExactRows(
                MANAGED_RECEIPT_ROWS_OPENED,
                read.receiptRowsRead(),
                read.unrelatedDocumentReads());
        return Optional.ofNullable(read.receipt());
    }

    /** Reads the original typed Contracts transition for one source epoch. */
    synchronized Optional<ManagedDocumentTransitionReceipt>
            managedTransitionReceipt(DocumentId documentId, long epoch) {
        ManagedEpochReceiptStore.TransitionReceiptRead read = state
                .managedEpochReceipts().exactTransition(
                        Objects.requireNonNull(documentId, "documentId"), epoch);
        recordExactRows(
                MANAGED_RECEIPT_ROWS_OPENED,
                read.receiptRowsRead(),
                read.unrelatedDocumentReads());
        return Optional.ofNullable(read.transitionReceipt());
    }

    /** Opens one exact row containing both public and Contracts evidence. */
    synchronized ManagedEpochEvidence managedEpochEvidence(
            DocumentId documentId, long epoch) {
        ManagedEpochReceiptStore.EvidenceRead read = state
                .managedEpochReceipts().exactEvidence(
                        Objects.requireNonNull(documentId, "documentId"), epoch);
        recordExactRows(
                MANAGED_RECEIPT_ROWS_OPENED,
                read.receiptRowsRead(),
                read.unrelatedDocumentReads());
        return new ManagedEpochEvidence(
                read.receipt(), read.transitionReceipt());
    }

    /** Installs one immutable path-copied corruption fixture for tests only. */
    synchronized void replaceManagedEpochEvidenceForTesting(
            DocumentId documentId,
            long epoch,
            ManagedEpochReceipt publicReceipt,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        ManagedEpochReceiptStore replacement = state.managedEpochReceipts()
                .withUnverifiedEvidenceForTesting(
                        Objects.requireNonNull(documentId, "documentId"),
                        epoch,
                        publicReceipt,
                        transitionReceipt);
        state = state.withManagedEpochReceipts(replacement);
    }

    /** Seeds an authenticated public receipt in legacy fixture setup only. */
    synchronized void retainManagedEpochReceiptForTesting(
            ManagedEpochReceipt receipt) {
        ManagedEpochReceiptStore replacement = state.managedEpochReceipts()
                .withReceipt(Objects.requireNonNull(receipt, "receipt"));
        state = state.withManagedEpochReceipts(replacement);
    }

    synchronized Optional<ManagedOccurrenceCatchUpPlan> catchUpPlan(
            String planIdentity) {
        ManagedCatchUpPlanIndex.PlanRead read = state.catchUpPlans()
                .plan(Objects.requireNonNull(planIdentity, "planIdentity"));
        recordExactRows(
                MANAGED_PLAN_ROWS_OPENED,
                read.planRowsRead(),
                read.unrelatedPlanReads());
        return Optional.ofNullable(read.plan());
    }

    synchronized List<ManagedOccurrenceCatchUpPlan> catchUpPlans(
            DocumentId consumerDocumentId) {
        ManagedCatchUpPlanIndex.PlanAudit read = state.catchUpPlans()
                .plansForConsumer(Objects.requireNonNull(
                        consumerDocumentId, "consumerDocumentId"));
        recordExactRows(
                MANAGED_PLAN_ROWS_OPENED,
                read.planRowsRead(),
                read.unrelatedPlanReads());
        return read.plans();
    }

    synchronized Optional<ManagedCatchUpBarrier> catchUpBarrier(
            String barrierIdentity) {
        CatchUpPlanStore.BarrierRead read = state.catchUpPlans()
                .barrier(Objects.requireNonNull(
                        barrierIdentity, "barrierIdentity"));
        recordExactRows(
                MANAGED_BARRIER_ROWS_OPENED,
                read.barrierRowsRead(),
                read.unrelatedPlanReads());
        return Optional.ofNullable(read.barrier());
    }

    synchronized Optional<ManagedEpochApplicationWork> catchUpWork(
            String workIdentity) {
        return Optional.ofNullable(state.catchUpPlans()
                .work(Objects.requireNonNull(workIdentity, "workIdentity"))
                .work());
    }

    /** Selects one canonical due work item without scanning plan rows. */
    synchronized Optional<ManagedEpochApplicationWork> nextCatchUpWork() {
        return Optional.ofNullable(state.catchUpPlans()
                .nextDueWork().work());
    }

    /** Selects canonical due work outside failed consumers without a scan. */
    synchronized Optional<ManagedEpochApplicationWork>
            nextCatchUpWorkExcluding(Set<DocumentId> excludedConsumers) {
        return Optional.ofNullable(state.catchUpPlans()
                .nextDueWorkExcluding(Objects.requireNonNull(
                        excludedConsumers, "excludedConsumers"))
                .work());
    }

    /**
     * Atomically records a pre-PROCESS immutable-evidence failure. Document
     * sessions, committed/ready heads, occurrence cursors, and receipt history
     * remain the exact same immutable state objects.
     */
    synchronized void recordManagedEpochEvidenceFailure(
            ManagedEpochEvidenceException failure) {
        ManagedEpochEvidenceException selected = Objects.requireNonNull(
                failure, "failure");
        CatchUpPlanStore changed = state.catchUpPlans()
                .withEvidenceFailure(selected);
        state = state.withCatchUpPlans(changed);
    }

    /**
     * Atomically blocks one complete PROCESS result rejected by the managed
     * application publication boundary, without changing document state.
     */
    synchronized void recordManagedEpochApplicationFailure(
            ManagedEpochApplicationWork work,
            String code,
            String message) {
        CatchUpPlanStore changed = state.catchUpPlans()
                .withApplicationFailure(work, code, message);
        state = state.withCatchUpPlans(changed);
    }

    synchronized boolean hasActiveCatchUp() {
        return state.catchUpPlans().hasActiveBarriers();
    }

    synchronized boolean hasActiveCatchUpFrom(DocumentId sourceDocumentId) {
        return state.catchUpPlans().hasActivePlanForSource(
                Objects.requireNonNull(
                        sourceDocumentId, "sourceDocumentId"));
    }

    /** Reads one document's exact durable Contracts graph generation. */
    synchronized long graphGeneration(DocumentId documentId) {
        return state.graphGenerations().require(Objects.requireNonNull(
                documentId, "documentId"));
    }

    /** Reads owned and transitive source barriers still holding this Root ready. */
    synchronized List<String> activeCatchUpBarrierIdentities(
            DocumentId consumerDocumentId) {
        CatchUpPlanStore.ActiveBarriersRead read = new ManagedCatchUpReadiness(
                state.catchUpPlans(), state.occurrenceInventory()).read(
                        Objects.requireNonNull(consumerDocumentId, "consumerDocumentId"));
        recordActiveBarrierRead(read);
        return read.barrierIdentities();
    }

    /** Builds one exact committed-versus-ready view from targeted indexes. */
    synchronized ManagedDocumentReadiness managedReadiness(
            DocumentId documentId) {
        DocumentSession session = require(Objects.requireNonNull(
                documentId, "documentId"));
        CatchUpPlanStore.ActiveBarriersRead activeRead = new ManagedCatchUpReadiness(
                state.catchUpPlans(), state.occurrenceInventory()).read(documentId);
        recordActiveBarrierRead(activeRead);
        List<String> active = activeRead.barrierIdentities();
        String waitingCode = null;
        String waitingMessage = null;
        for (ManagedCatchUpBarrier barrier : activeRead.barriers()) {
            if (barrier.status() == ManagedCatchUpBarrierStatus.BLOCKED) {
                waitingCode = barrier.waitingCode().orElseThrow();
                waitingMessage = barrier.waitingMessage().orElse(null);
                break;
            }
            if (barrier.status()
                            == ManagedCatchUpBarrierStatus.WAITING_FOR_HISTORY
                    && waitingCode == null) {
                waitingCode = barrier.waitingCode().orElseThrow();
                waitingMessage = barrier.waitingMessage().orElse(null);
            }
        }
        return ManagedDocumentReadiness.identified(
                documentId,
                session.epoch(),
                session.currentRepresentation().blueId(),
                Long.valueOf(session.readyEpoch()),
                session.readyRepresentation().blueId(),
                session.status(),
                waitingCode,
                waitingMessage,
                active);
    }

    synchronized Optional<ManagedEpochApplicationReceipt>
            catchUpApplication(String applicationReceiptIdentity) {
        ManagedCatchUpWorkIndex.ApplicationRead read = state.catchUpPlans()
                .application(Objects.requireNonNull(
                        applicationReceiptIdentity,
                        "applicationReceiptIdentity"));
        recordExactRows(
                MANAGED_APPLICATION_RECEIPT_ROWS_OPENED,
                read.applicationRowsRead(),
                read.unrelatedPlanReads());
        return Optional.ofNullable(read.receipt());
    }

    /** Reads an already committed application by its idempotent work key. */
    synchronized Optional<ManagedEpochApplicationReceipt>
            catchUpApplicationByWork(String workIdentity) {
        ManagedCatchUpWorkIndex.ApplicationRead read = state.catchUpPlans()
                .applicationByWork(Objects.requireNonNull(
                        workIdentity, "workIdentity"));
        recordExactRows(
                MANAGED_APPLICATION_RECEIPT_ROWS_OPENED,
                read.applicationRowsRead(),
                read.unrelatedPlanReads());
        return Optional.ofNullable(read.receipt());
    }

    private void registerExactReadCounters() {
        metrics.add(MANAGED_RECEIPT_ROWS_OPENED, 0L);
        metrics.add(MANAGED_PLAN_ROWS_OPENED, 0L);
        metrics.add(MANAGED_BARRIER_ROWS_OPENED, 0L);
        metrics.add(MANAGED_APPLICATION_RECEIPT_ROWS_OPENED, 0L);
    }

    private void recordActiveBarrierRead(
            CatchUpPlanStore.ActiveBarriersRead read) {
        recordExactRows(
                MANAGED_PLAN_ROWS_OPENED,
                read.planRowsRead(),
                read.unrelatedPlanReads());
        recordExactRows(
                MANAGED_BARRIER_ROWS_OPENED,
                read.barrierRowsRead(),
                0);
    }

    private void recordExactRows(
            String counter, int rowsRead, int unrelatedDocumentReads) {
        metrics.add(counter, rowsRead);
        metrics.add(UNRELATED_DOCUMENT_READS, unrelatedDocumentReads);
    }

    synchronized void commit(MultiDocumentPublicationTransaction transaction) {
        MultiDocumentPublicationTransaction selected = Objects.requireNonNull(
                transaction, "transaction");
        StoreState replacement = selected.prepareReplacement(state);
        state = replacement;
    }

    static ProcessEmbeddedComponentIndex componentIndex(
            Collection<DocumentSession> sessions,
            ManagedOccurrenceInventory inventory) {
        List<DocumentId> documents = sessions.stream()
                .map(DocumentSession::documentId)
                .toList();
        return ProcessEmbeddedComponentIndex
                .fromDocumentsAndOccurrenceInventory(documents, inventory);
    }

    static long increment(long value, String label) {
        if (value >= MultiDocumentPublicationTransaction.MAX_SAFE_INTEGER) {
            throw new IllegalStateException(label + " exhausted");
        }
        return Math.addExact(value, 1L);
    }

    /** Immutable indexed store image for one managed-occurrence resolution. */
    record OccurrenceResolutionSnapshot(
            ManagedLineageIndex lineageIndex,
            ManagedOccurrenceInventory occurrenceInventory,
            ProcessEmbeddedComponentIndex componentIndex,
            long occurrenceInventoryGeneration,
            long componentIndexGeneration) {
        OccurrenceResolutionSnapshot {
            lineageIndex = Objects.requireNonNull(
                    lineageIndex, "lineageIndex");
            occurrenceInventory = Objects.requireNonNull(
                    occurrenceInventory, "occurrenceInventory");
            componentIndex = Objects.requireNonNull(
                    componentIndex, "componentIndex");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    occurrenceInventoryGeneration,
                    "occurrenceInventoryGeneration");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    componentIndexGeneration,
                    "componentIndexGeneration");
        }
    }

    record ManagedReadSnapshot(
            DocumentHead head,
            long graphGeneration,
            ComponentSnapshot componentState) {
        ManagedReadSnapshot {
            head = Objects.requireNonNull(head, "head");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    graphGeneration, "graphGeneration");
        }
    }

    record ManagedEpochEvidence(
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        ManagedEpochEvidence {
            if (receipt == null && transitionReceipt != null) {
                throw new IllegalArgumentException(
                        "Contracts evidence requires a public epoch receipt");
            }
        }
    }

    record StoreStructureSnapshot(
            PersistentOrderedMap<DocumentId, DocumentSession> sessions,
            ProcessEmbeddedComponentIndex componentIndex,
            ComponentStateInventory componentStates,
            PersistentAppendLog<PublicEventOccurrence> outbox,
            PersistentAppendLog<CheckpointWrite> checkpoints,
            PersistentOrderedMap<String, Boolean> publicationReceipts,
            PersistentOrderedMap<String, ContractsClosureAdmissionReceipt>
                    admissionReceipts,
            PersistentOrderedMap<String, ContractsClosurePublicationReceipt>
                    closurePublicationReceipts) {
        StoreStructureSnapshot {
            sessions = Objects.requireNonNull(sessions, "sessions");
            componentIndex = Objects.requireNonNull(
                    componentIndex, "componentIndex");
            componentStates = Objects.requireNonNull(
                    componentStates, "componentStates");
            outbox = Objects.requireNonNull(outbox, "outbox");
            checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
            publicationReceipts = Objects.requireNonNull(
                    publicationReceipts, "publicationReceipts");
            admissionReceipts = Objects.requireNonNull(
                    admissionReceipts, "admissionReceipts");
            closurePublicationReceipts = Objects.requireNonNull(
                    closurePublicationReceipts,
                    "closurePublicationReceipts");
        }

        int sharedSessionNodes(StoreStructureSnapshot other) {
            return sessions.sharedNodeCountForTesting(
                    Objects.requireNonNull(other, "other").sessions);
        }

        int sharedComponentIndexNodes(StoreStructureSnapshot other) {
            return componentIndex.sharedComponentNodesForTesting(
                    Objects.requireNonNull(other, "other").componentIndex);
        }

        int sharedComponentStateNodes(StoreStructureSnapshot other) {
            return componentStates.sharedLineageNodesForTesting(
                    Objects.requireNonNull(other, "other").componentStates);
        }

        boolean sameComponentIndexEntryIdentity(
                StoreStructureSnapshot other,
                DocumentId document) {
            return componentIndex.sameComponentEntryIdentityForTesting(
                    Objects.requireNonNull(other, "other").componentIndex,
                    document);
        }

        boolean sameComponentStateEntryIdentity(
                StoreStructureSnapshot other,
                DocumentId document) {
            return componentStates.sameStateEntryIdentityForTesting(
                    Objects.requireNonNull(other, "other").componentStates,
                    document);
        }

        boolean evidenceExtends(StoreStructureSnapshot prefix) {
            StoreStructureSnapshot selected = Objects.requireNonNull(
                    prefix, "prefix");
            return outbox.extendsLog(selected.outbox)
                    && checkpoints.extendsLog(selected.checkpoints);
        }

        int outboxSize() {
            return outbox.size();
        }

        int checkpointSize() {
            return checkpoints.size();
        }
    }

    /** Immutable durable publication image; exactly one instance is swapped. */
    static final class StoreState {
        private final PersistentOrderedMap<DocumentId, DocumentSession>
                sessionIndex;
        private final Map<DocumentId, DocumentSession> sessions;
        private final ManagedLineageIndex lineageIndex;
        private final ManagedOccurrenceInventory occurrenceInventory;
        private final long occurrenceInventoryGeneration;
        private final ProcessEmbeddedComponentIndex componentIndex;
        private final long componentIndexGeneration;
        private final ClosureGraphGenerationInventory graphGenerations;
        private final ComponentStateInventory componentStates;
        private final ClosureSubscriptionInventory closureSubscriptions;
        private final PersistentAppendLog<PublicEventOccurrence> outbox;
        private final PersistentAppendLog<CheckpointWrite>
                checkpointEvidence;
        private final PersistentOrderedMap<String, Boolean>
                publicationReceiptIndex;
        private final Set<String> publicationReceipts;
        private final PersistentOrderedMap<String,
                ContractsClosureAdmissionReceipt> admissionReceiptIndex;
        private final Map<String, ContractsClosureAdmissionReceipt>
                admissionReceipts;
        private final PersistentOrderedMap<String,
                ContractsClosurePublicationReceipt>
                closurePublicationReceiptIndex;
        private final Map<String, ContractsClosurePublicationReceipt>
                closurePublicationReceipts;
        private final ManagedEpochReceiptStore managedEpochReceipts;
        private final CatchUpPlanStore catchUpPlans;

        StoreState(
                Map<DocumentId, DocumentSession> sessions,
                ManagedLineageIndex lineageIndex,
                ManagedOccurrenceInventory occurrenceInventory,
                long occurrenceInventoryGeneration,
                ProcessEmbeddedComponentIndex componentIndex,
                long componentIndexGeneration,
                ClosureGraphGenerationInventory graphGenerations,
                Collection<ComponentSnapshot> componentStates,
                ClosureSubscriptionInventory closureSubscriptions,
                Collection<PublicEventOccurrence> outbox,
                Collection<CheckpointWrite> checkpointEvidence,
                Collection<String> publicationReceipts,
                Map<String, ContractsClosureAdmissionReceipt>
                        admissionReceipts,
                Map<String, ContractsClosurePublicationReceipt>
                        closurePublicationReceipts,
                ManagedEpochReceiptStore managedEpochReceipts,
                CatchUpPlanStore catchUpPlans) {
            this.sessionIndex = sessionIndex(sessions);
            this.sessions = new PersistentMapView<>(sessionIndex);
            this.lineageIndex = Objects.requireNonNull(
                    lineageIndex, "lineageIndex");
            if (!this.lineageIndex.documentIds().equals(
                    this.sessions.keySet())) {
                throw new IllegalArgumentException(
                        "Managed-lineage index must cover every durable "
                                + "document exactly once");
            }
            this.occurrenceInventory = Objects.requireNonNull(
                    occurrenceInventory, "occurrenceInventory");
            this.occurrenceInventoryGeneration =
                    MultiDocumentPublicationTransaction.requireSafeInteger(
                            occurrenceInventoryGeneration,
                            "occurrenceInventoryGeneration");
            this.componentIndex = Objects.requireNonNull(
                    componentIndex, "componentIndex");
            this.componentIndexGeneration =
                    MultiDocumentPublicationTransaction.requireSafeInteger(
                            componentIndexGeneration,
                            "componentIndexGeneration");
            this.graphGenerations = Objects.requireNonNull(
                    graphGenerations, "graphGenerations");
            if (!new LinkedHashSet<>(this.graphGenerations.documents())
                    .equals(this.sessions.keySet())) {
                throw new IllegalArgumentException(
                        "Graph-generation inventory must cover every durable "
                                + "document exactly once");
            }
            ArrayList<ComponentSnapshot> canonicalComponents =
                    new ArrayList<>(Objects.requireNonNull(
                            componentStates, "componentStates"));
            Set<String> componentLineages = new LinkedHashSet<>();
            Set<String> componentStateIdentities = new LinkedHashSet<>();
            Set<String> componentMembers = new LinkedHashSet<>();
            LinkedHashMap<DocumentId, ComponentSnapshot> byDocument =
                    new LinkedHashMap<>();
            LinkedHashMap<String, Integer> orderByIdentity =
                    new LinkedHashMap<>();
            for (int statePosition = 0;
                    statePosition < canonicalComponents.size();
                    statePosition++) {
                ComponentSnapshot component = canonicalComponents.get(
                        statePosition);
                if (!componentLineages.add(component.componentIdentity())) {
                    throw new IllegalArgumentException(
                            "Duplicate component lineage "
                                    + component.componentIdentity());
                }
                if (!componentStateIdentities.add(
                        component.componentStateIdentity())) {
                    throw new IllegalArgumentException(
                            "Duplicate component state identity "
                                    + component.componentStateIdentity());
                }
                component.orderedMemberDocumentIds().forEach(documentId -> {
                    if (!componentMembers.add(documentId.value())) {
                        throw new IllegalArgumentException(
                                "Overlapping component state member "
                                        + documentId.value());
                    }
                    byDocument.put(
                            DocumentId.of(documentId.value()), component);
                });
                orderByIdentity.put(
                        component.componentStateIdentity(), statePosition);
            }
            requireCondensationOrder(canonicalComponents, componentIndex);
            this.componentStates = ComponentStateInventory.of(
                    canonicalComponents);
            this.closureSubscriptions = Objects.requireNonNull(
                    closureSubscriptions, "closureSubscriptions");
            this.closureSubscriptions.states().forEach(state -> {
                DocumentId owner = DocumentId.of(state.channelOccurrence()
                        .managedDocumentId().value());
                DocumentSession session = this.sessions.get(owner);
                if (session == null) {
                    throw new IllegalArgumentException(
                            "Closure subscription belongs to an absent document "
                                    + owner);
                }
                if (!session.currentRepresentation().blueId()
                        .equals(state.documentBlueId())) {
                    throw new IllegalArgumentException(
                            "Closure subscription does not identify the durable "
                                    + "document head " + owner);
                }
                ComponentSnapshot component = this.componentStates.forDocument(
                        owner);
                if (component == null) {
                    throw new IllegalArgumentException(
                            "Closure subscription has no component state for "
                                    + owner);
                }
                if (component.componentGeneration()
                        != state.componentGeneration()) {
                    throw new IllegalArgumentException(
                            "Closure subscription component generation is stale "
                                    + "for " + owner);
                }
                if (this.graphGenerations.require(owner)
                        != state.graphGeneration()) {
                    throw new IllegalArgumentException(
                            "Closure subscription graph generation is stale "
                                    + "for " + owner);
                }
            });
            this.outbox = PersistentAppendLog.of(Objects.requireNonNull(
                    outbox, "outbox"));
            this.checkpointEvidence = PersistentAppendLog.of(
                    Objects.requireNonNull(
                            checkpointEvidence, "checkpointEvidence"));
            this.publicationReceiptIndex = publicationReceiptIndex(
                    publicationReceipts);
            this.publicationReceipts = Collections.unmodifiableSet(
                    new PersistentMapView<>(publicationReceiptIndex).keySet());
            LinkedHashMap<String, ContractsClosureAdmissionReceipt>
                    typedReceipts = new LinkedHashMap<>();
            Objects.requireNonNull(
                    admissionReceipts, "admissionReceipts")
                    .forEach((identity, receipt) -> {
                        String key = Objects.requireNonNull(
                                identity, "admission receipt identity");
                        ContractsClosureAdmissionReceipt exact =
                                Objects.requireNonNull(
                                        receipt, "admission receipt");
                        if (!key.equals(exact.publicationIdentity())) {
                            throw new IllegalArgumentException(
                                    "Admission receipt is stored under the "
                                            + "wrong publication identity");
                        }
                        if (exact.publicationOutcome()
                                != ContractsClosureAdmissionReceipt
                                        .PublicationOutcome.PUBLISHED) {
                            throw new IllegalArgumentException(
                                    "Only newly published admission receipts "
                                            + "are durable");
                        }
                        if (!this.publicationReceipts.contains(key)) {
                            throw new IllegalArgumentException(
                                    "Typed admission receipt has no generic "
                                            + "publication receipt " + key);
                        }
                        if (typedReceipts.putIfAbsent(key, exact) != null) {
                            throw new IllegalArgumentException(
                                    "Duplicate typed admission receipt " + key);
                        }
                        requireRetainedResult(
                                exact.documentIds(),
                                exact.attempt().processResult(),
                                this.sessions,
                                "Admission receipt");
                    });
            this.admissionReceiptIndex = admissionReceiptIndex(typedReceipts);
            this.admissionReceipts = new PersistentMapView<>(
                    admissionReceiptIndex);
            LinkedHashMap<String, ContractsClosurePublicationReceipt>
                    processReceipts = new LinkedHashMap<>();
            Objects.requireNonNull(
                    closurePublicationReceipts,
                    "closurePublicationReceipts")
                    .forEach((identity, receipt) -> {
                        String key = Objects.requireNonNull(
                                identity, "process receipt identity");
                        ContractsClosurePublicationReceipt exact =
                                Objects.requireNonNull(
                                        receipt, "process receipt");
                        if (!key.equals(exact.publicationIdentity())) {
                            throw new IllegalArgumentException(
                                    "Process receipt is stored under the "
                                            + "wrong publication identity");
                        }
                        if (!this.publicationReceipts.contains(key)) {
                            throw new IllegalArgumentException(
                                    "Typed process receipt has no generic "
                                            + "publication receipt " + key);
                        }
                        if (this.admissionReceipts.containsKey(key)) {
                            throw new IllegalArgumentException(
                                    "One publication identity cannot name both "
                                            + "admission and process receipts");
                        }
                        if (processReceipts.putIfAbsent(key, exact) != null) {
                            throw new IllegalArgumentException(
                                    "Duplicate typed process receipt " + key);
                        }
                        requireRetainedResult(
                                exact.documentIds(),
                                exact.attempt().processResult(),
                                this.sessions,
                                "Process receipt");
                    });
            this.closurePublicationReceiptIndex =
                    closurePublicationReceiptIndex(processReceipts);
            this.closurePublicationReceipts = new PersistentMapView<>(
                    closurePublicationReceiptIndex);
            this.managedEpochReceipts = Objects.requireNonNull(
                    managedEpochReceipts, "managedEpochReceipts");
            this.catchUpPlans = Objects.requireNonNull(
                    catchUpPlans, "catchUpPlans");
        }

        private StoreState(
                PersistentOrderedMap<DocumentId, DocumentSession> sessionIndex,
                ManagedLineageIndex lineageIndex,
                ManagedOccurrenceInventory occurrenceInventory,
                long occurrenceInventoryGeneration,
                ProcessEmbeddedComponentIndex componentIndex,
                long componentIndexGeneration,
                ClosureGraphGenerationInventory graphGenerations,
                ComponentStateInventory componentStates,
                ClosureSubscriptionInventory closureSubscriptions,
                PersistentAppendLog<PublicEventOccurrence> outbox,
                PersistentAppendLog<CheckpointWrite> checkpointEvidence,
                PersistentOrderedMap<String, Boolean> publicationReceiptIndex,
                PersistentOrderedMap<String, ContractsClosureAdmissionReceipt>
                        admissionReceiptIndex,
                PersistentOrderedMap<String,
                        ContractsClosurePublicationReceipt>
                        closurePublicationReceiptIndex,
                ManagedEpochReceiptStore managedEpochReceipts,
                CatchUpPlanStore catchUpPlans) {
            this.sessionIndex = Objects.requireNonNull(
                    sessionIndex, "sessionIndex");
            this.sessions = new PersistentMapView<>(sessionIndex);
            this.lineageIndex = Objects.requireNonNull(
                    lineageIndex, "lineageIndex");
            this.occurrenceInventory = Objects.requireNonNull(
                    occurrenceInventory, "occurrenceInventory");
            this.occurrenceInventoryGeneration =
                    MultiDocumentPublicationTransaction.requireSafeInteger(
                            occurrenceInventoryGeneration,
                            "occurrenceInventoryGeneration");
            this.componentIndex = Objects.requireNonNull(
                    componentIndex, "componentIndex");
            this.componentIndexGeneration =
                    MultiDocumentPublicationTransaction.requireSafeInteger(
                            componentIndexGeneration,
                            "componentIndexGeneration");
            this.graphGenerations = Objects.requireNonNull(
                    graphGenerations, "graphGenerations");

            this.componentStates = Objects.requireNonNull(
                    componentStates, "componentStates");
            this.closureSubscriptions = Objects.requireNonNull(
                    closureSubscriptions, "closureSubscriptions");
            this.outbox = Objects.requireNonNull(outbox, "outbox");
            this.checkpointEvidence = Objects.requireNonNull(
                    checkpointEvidence, "checkpointEvidence");
            this.publicationReceiptIndex = Objects.requireNonNull(
                    publicationReceiptIndex, "publicationReceiptIndex");
            this.publicationReceipts = Collections.unmodifiableSet(
                    new PersistentMapView<>(publicationReceiptIndex).keySet());
            this.admissionReceiptIndex = Objects.requireNonNull(
                    admissionReceiptIndex, "admissionReceiptIndex");
            this.admissionReceipts = new PersistentMapView<>(
                    admissionReceiptIndex);
            this.closurePublicationReceiptIndex = Objects.requireNonNull(
                    closurePublicationReceiptIndex,
                    "closurePublicationReceiptIndex");
            this.closurePublicationReceipts = new PersistentMapView<>(
                    closurePublicationReceiptIndex);
            this.managedEpochReceipts = Objects.requireNonNull(
                    managedEpochReceipts, "managedEpochReceipts");
            this.catchUpPlans = Objects.requireNonNull(
                    catchUpPlans, "catchUpPlans");
        }

        static StoreState trustedTransition(
                PersistentOrderedMap<DocumentId, DocumentSession> sessions,
                ManagedLineageIndex lineageIndex,
                ManagedOccurrenceInventory occurrenceInventory,
                long occurrenceInventoryGeneration,
                ProcessEmbeddedComponentIndex componentIndex,
                long componentIndexGeneration,
                ClosureGraphGenerationInventory graphGenerations,
                ComponentStateInventory componentStates,
                ClosureSubscriptionInventory closureSubscriptions,
                PersistentAppendLog<PublicEventOccurrence> outbox,
                PersistentAppendLog<CheckpointWrite> checkpointEvidence,
                PersistentOrderedMap<String, Boolean> publicationReceipts,
                PersistentOrderedMap<String, ContractsClosureAdmissionReceipt>
                        admissionReceipts,
                PersistentOrderedMap<String,
                        ContractsClosurePublicationReceipt>
                        closurePublicationReceipts,
                ManagedEpochReceiptStore managedEpochReceipts,
                CatchUpPlanStore catchUpPlans) {
            return new StoreState(
                    sessions,
                    lineageIndex,
                    occurrenceInventory,
                    occurrenceInventoryGeneration,
                    componentIndex,
                    componentIndexGeneration,
                    graphGenerations,
                    componentStates,
                    closureSubscriptions,
                    outbox,
                    checkpointEvidence,
                    publicationReceipts,
                    admissionReceipts,
                    closurePublicationReceipts,
                    managedEpochReceipts,
                    catchUpPlans);
        }

        static StoreState empty() {
            ManagedOccurrenceInventory inventory =
                    ManagedOccurrenceInventory.empty();
            return new StoreState(
                    Map.of(),
                    ManagedLineageIndex.empty(),
                    inventory,
                    0L,
                    InMemoryDocumentStore.componentIndex(
                            List.<DocumentSession>of(), inventory),
                    0L,
                    ClosureGraphGenerationInventory.empty(),
                    List.of(),
                    ClosureSubscriptionInventory.empty(),
                    List.of(),
                    List.of(),
                    Set.of(),
                    Map.of(),
                    Map.of(),
                    ManagedEpochReceiptStore.empty(),
                    CatchUpPlanStore.empty());
        }

        StoreState withSessions(
                Map<DocumentId, DocumentSession> replacementSessions,
                ManagedLineageIndex replacementLineages,
                ProcessEmbeddedComponentIndex replacementIndex,
                long replacementIndexGeneration) {
            List<ComponentSnapshot> retainedComponents = componentStates()
                    .stream()
                    .filter(component -> component.orderedMemberDocumentIds()
                            .stream().allMatch(documentId ->
                                    replacementSessions.containsKey(DocumentId.of(
                                            documentId.value()))))
                    .toList();
            return new StoreState(
                    replacementSessions,
                    replacementLineages,
                    occurrenceInventory,
                    occurrenceInventoryGeneration,
                    replacementIndex,
                    replacementIndexGeneration,
                    graphGenerations.retainingDocuments(
                            replacementSessions.keySet()),
                    retainedComponents,
                    closureSubscriptions.retainingDocuments(
                            replacementSessions.keySet()),
                    outbox.values(),
                    checkpointEvidence.values(),
                    publicationReceipts,
                    admissionReceipts,
                    closurePublicationReceipts,
                    managedEpochReceipts,
                    catchUpPlans);
        }

        PersistentOrderedMap<DocumentId, DocumentSession> sessionIndex() {
            return sessionIndex;
        }

        Map<DocumentId, DocumentSession> sessions() {
            return sessions;
        }

        ManagedLineageIndex lineageIndex() {
            return lineageIndex;
        }

        ManagedOccurrenceInventory occurrenceInventory() {
            return occurrenceInventory;
        }

        long occurrenceInventoryGeneration() {
            return occurrenceInventoryGeneration;
        }

        ProcessEmbeddedComponentIndex componentIndex() {
            return componentIndex;
        }

        long componentIndexGeneration() {
            return componentIndexGeneration;
        }

        ClosureGraphGenerationInventory graphGenerations() {
            return graphGenerations;
        }

        List<ComponentSnapshot> componentStates() {
            return componentStates.states(componentIndex);
        }

        List<ComponentSnapshot> componentStatesFor(
                Collection<DocumentId> documentIds) {
            return componentStates.statesFor(documentIds, componentIndex);
        }

        ComponentStateInventory componentStateInventory() {
            return componentStates;
        }

        ComponentSnapshot componentState(String componentIdentity) {
            return componentStates.byLineage(componentIdentity);
        }

        ClosureSubscriptionInventory closureSubscriptions() {
            return closureSubscriptions;
        }

        List<PublicEventOccurrence> outbox() {
            return outbox.values();
        }

        List<CheckpointWrite> checkpointEvidence() {
            return checkpointEvidence.values();
        }

        PersistentAppendLog<PublicEventOccurrence> outboxLog() {
            return outbox;
        }

        PersistentAppendLog<CheckpointWrite> checkpointEvidenceLog() {
            return checkpointEvidence;
        }

        boolean hasPublicationReceipt(String identity) {
            return publicationReceiptIndex.containsKey(
                    Objects.requireNonNull(identity, "identity"));
        }

        PersistentOrderedMap<String, Boolean> publicationReceiptIndex() {
            return publicationReceiptIndex;
        }

        PersistentOrderedMap<String, ContractsClosureAdmissionReceipt>
                admissionReceiptIndex() {
            return admissionReceiptIndex;
        }

        PersistentOrderedMap<String, ContractsClosurePublicationReceipt>
                closurePublicationReceiptIndex() {
            return closurePublicationReceiptIndex;
        }

        Set<String> publicationReceipts() {
            return publicationReceipts;
        }

        Map<String, ContractsClosureAdmissionReceipt> admissionReceipts() {
            return admissionReceipts;
        }

        Map<String, ContractsClosurePublicationReceipt>
                closurePublicationReceipts() {
            return closurePublicationReceipts;
        }

        ManagedEpochReceiptStore managedEpochReceipts() {
            return managedEpochReceipts;
        }

        StoreState withManagedEpochReceipts(
                ManagedEpochReceiptStore replacement) {
            return trustedTransition(
                    sessionIndex,
                    lineageIndex,
                    occurrenceInventory,
                    occurrenceInventoryGeneration,
                    componentIndex,
                    componentIndexGeneration,
                    graphGenerations,
                    componentStates,
                    closureSubscriptions,
                    outbox,
                    checkpointEvidence,
                    publicationReceiptIndex,
                    admissionReceiptIndex,
                    closurePublicationReceiptIndex,
                    Objects.requireNonNull(
                            replacement, "managedEpochReceipts"),
                    catchUpPlans);
        }

        CatchUpPlanStore catchUpPlans() {
            return catchUpPlans;
        }

        StoreState withCatchUpPlans(CatchUpPlanStore replacement) {
            return trustedTransition(
                    sessionIndex,
                    lineageIndex,
                    occurrenceInventory,
                    occurrenceInventoryGeneration,
                    componentIndex,
                    componentIndexGeneration,
                    graphGenerations,
                    componentStates,
                    closureSubscriptions,
                    outbox,
                    checkpointEvidence,
                    publicationReceiptIndex,
                    admissionReceiptIndex,
                    closurePublicationReceiptIndex,
                    managedEpochReceipts,
                    Objects.requireNonNull(replacement, "catchUpPlans"));
        }

        private static PersistentOrderedMap<DocumentId, DocumentSession>
                sessionIndex(Map<DocumentId, DocumentSession> sessions) {
            PersistentOrderedMap<DocumentId, DocumentSession> result =
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.DOCUMENT_ORDER);
            for (Map.Entry<DocumentId, DocumentSession> entry
                    : Objects.requireNonNull(sessions, "sessions").entrySet()) {
                DocumentId documentId = Objects.requireNonNull(
                        entry.getKey(), "documentId");
                DocumentSession session = Objects.requireNonNull(
                        entry.getValue(), "session");
                if (!documentId.equals(session.documentId())
                        || result.containsKey(documentId)) {
                    throw new IllegalArgumentException(
                            "Session index contains a duplicate or wrong key "
                                    + documentId);
                }
                result = result.put(documentId, session).map();
            }
            return result;
        }

        private static PersistentOrderedMap<String, Boolean>
                publicationReceiptIndex(Collection<String> identities) {
            PersistentOrderedMap<String, Boolean> result =
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER);
            for (String identity : Objects.requireNonNull(
                    identities, "publicationReceipts")) {
                String selected = Objects.requireNonNull(
                        identity, "publicationReceipt");
                if (result.containsKey(selected)) {
                    throw new IllegalArgumentException(
                            "Duplicate publication receipt " + selected);
                }
                result = result.put(selected, Boolean.TRUE).map();
            }
            return result;
        }

        private static PersistentOrderedMap<String,
                ContractsClosureAdmissionReceipt> admissionReceiptIndex(
                        Map<String, ContractsClosureAdmissionReceipt>
                                receipts) {
            PersistentOrderedMap<String, ContractsClosureAdmissionReceipt>
                    result = PersistentOrderedMap.empty(
                            EmbeddingBinding.TEXT_ORDER);
            for (Map.Entry<String, ContractsClosureAdmissionReceipt> entry
                    : Objects.requireNonNull(
                            receipts, "admissionReceipts").entrySet()) {
                result = result.put(
                        Objects.requireNonNull(entry.getKey(), "identity"),
                        Objects.requireNonNull(entry.getValue(), "receipt"))
                        .map();
            }
            return result;
        }

        private static PersistentOrderedMap<String,
                ContractsClosurePublicationReceipt>
                closurePublicationReceiptIndex(
                        Map<String, ContractsClosurePublicationReceipt>
                                receipts) {
            PersistentOrderedMap<String, ContractsClosurePublicationReceipt>
                    result = PersistentOrderedMap.empty(
                            EmbeddingBinding.TEXT_ORDER);
            for (Map.Entry<String, ContractsClosurePublicationReceipt> entry
                    : Objects.requireNonNull(
                            receipts, "closurePublicationReceipts").entrySet()) {
                result = result.put(
                        Objects.requireNonNull(entry.getKey(), "identity"),
                        Objects.requireNonNull(entry.getValue(), "receipt"))
                        .map();
            }
            return result;
        }

        private static void requireRetainedResult(
                List<DocumentId> receiptDocuments,
                blue.language.processor.closure.ClosureProcessResult result,
                Map<DocumentId, DocumentSession> sessions,
                String label) {
            TreeMap<DocumentId, ResultingDocument> resulting =
                    new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
            for (ResultingDocument document : Objects.requireNonNull(
                    result, "receipt result").resultingDocuments()) {
                DocumentId documentId = DocumentId.of(
                        document.documentId().value());
                if (resulting.putIfAbsent(documentId, document) != null) {
                    throw new IllegalArgumentException(
                            label + " result repeats document " + documentId);
                }
            }
            List<DocumentId> canonical = receiptDocuments.stream()
                    .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                    .toList();
            if (!canonical.equals(new ArrayList<>(resulting.keySet()))) {
                throw new IllegalArgumentException(
                        label + " document set differs from its exact result");
            }
            boolean retainedDocument = false;
            for (Map.Entry<DocumentId, ResultingDocument> entry
                    : resulting.entrySet()) {
                ResultingDocument exact = entry.getValue();
                if (!result.commits()
                        && exact.epoch() == 0L
                        && exact.beforeBlueId().equals(exact.afterBlueId())
                        && !exact.initialized()
                        && !exact.terminated()
                        && !exact.publicRoot()) {
                    // This row authenticates absence at the completed attempt,
                    // not the current store image. A later operation may admit
                    // the same lineage without invalidating the retained
                    // virtual-draft rollback evidence.
                    continue;
                }
                DocumentSession session = sessions.get(entry.getKey());
                if (session == null) {
                    throw new IllegalArgumentException(
                            label + " belongs to an absent document "
                                    + entry.getKey());
                }
                retainedDocument = true;
                if (exact.epoch() > session.epoch()
                        || !session.revision(exact.epoch()).after().blueId()
                                .equals(exact.afterBlueId())
                        && !retainsAuthenticatedComponentRepresentation(
                                result, exact, session)) {
                    throw new IllegalArgumentException(
                            label + " result head is absent from durable "
                                    + "history for " + entry.getKey());
                }
            }
            if (!retainedDocument) {
                throw new IllegalArgumentException(
                        label + " has no retained document");
            }
        }

        private static boolean retainsAuthenticatedComponentRepresentation(
                blue.language.processor.closure.ClosureProcessResult result,
                ResultingDocument document,
                DocumentSession session) {
            if (!result.commits()
                    || result.platformCommitCompanion() == null
                    || !result.platformCommitCompanion()
                            .bindsManagedTransitionReceipts()
                    || !document.initialized()
                    || document.terminated()
                    || document.beforeBlueId().equals(
                            document.afterBlueId())) {
                return false;
            }
            ManagedDocumentTransitionReceipt transition = result
                    .managedTransitionReceipts().stream()
                    .filter(candidate -> candidate.documentId().equals(
                            document.documentId()))
                    .findFirst()
                    .orElse(null);
            if (transition == null
                    || !transition.sourceInvocationIdentity().equals(
                            result.invocationIdentity())
                    || !transition.beforeBlueId().equals(
                            document.beforeBlueId())
                    || !transition.afterBlueId().equals(
                            document.afterBlueId())
                    || !transition.emittedRootEvents().isEmpty()
                    || !session.hasComponentRepresentationTransition(
                            document.epoch(),
                            document.beforeBlueId(),
                            document.afterBlueId(),
                            transition.transitionReceiptIdentity())) {
                return false;
            }
            List<ComponentSnapshot> components = result.resultingComponents()
                    .stream()
                    .filter(component -> component.orderedMemberDocumentIds()
                            .contains(document.documentId()))
                    .toList();
            if (components.size() != 1) {
                return false;
            }
            ComponentSnapshot component = components.get(0);
            int memberIndex = component.orderedMemberDocumentIds().indexOf(
                    document.documentId());
            return memberIndex >= 0
                    && component.componentGeneration()
                            == document.componentGeneration()
                    && component.componentIdentity().equals(
                            document.componentIdentity())
                    && component.componentStateIdentity().equals(
                            document.componentStateIdentity())
                    && component.orderedMemberBlueIds().get(memberIndex)
                            .equals(document.afterBlueId());
        }

        private static void requireCondensationOrder(
                List<ComponentSnapshot> states,
                ProcessEmbeddedComponentIndex index) {
            int prior = -1;
            for (ComponentSnapshot state : states) {
                List<DocumentId> members = state.orderedMemberDocumentIds()
                        .stream()
                        .map(member -> DocumentId.of(member.value()))
                        .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                        .toList();
                int position = -1;
                for (int candidate = 0;
                        candidate < index.components().size(); candidate++) {
                    if (index.components().get(candidate).members()
                            .equals(members)) {
                        position = candidate;
                        break;
                    }
                }
                if (position < 0) {
                    throw new IllegalArgumentException(
                            "Component state is absent from the active graph: "
                                    + members);
                }
                if (position <= prior) {
                    throw new IllegalArgumentException(
                            "Component states are not in target-before-source "
                                    + "condensation order");
                }
                prior = position;
            }
        }
    }

    /** Immutable topology image which opens no document session. */
    record ClosureTopologySnapshot(
            long occurrenceInventoryGeneration,
            long componentIndexGeneration,
            ManagedOccurrenceInventory occurrenceInventory,
            ProcessEmbeddedComponentIndex componentIndex,
            ClosureSubscriptionInventory closureSubscriptions) {
        ClosureTopologySnapshot {
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    occurrenceInventoryGeneration,
                    "occurrenceInventoryGeneration");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    componentIndexGeneration,
                    "componentIndexGeneration");
            occurrenceInventory = Objects.requireNonNull(
                    occurrenceInventory, "occurrenceInventory");
            componentIndex = Objects.requireNonNull(
                    componentIndex, "componentIndex");
            closureSubscriptions = Objects.requireNonNull(
                    closureSubscriptions, "closureSubscriptions");
        }
    }

    /** Targeted immutable publication image for one affected closure. */
    record ClosureSnapshot(
            Map<DocumentId, DocumentHead> documentHeads,
            long occurrenceInventoryGeneration,
            long componentIndexGeneration,
            ManagedOccurrenceInventory occurrenceInventory,
            ClosureGraphGenerationInventory graphGenerations,
            List<ComponentSnapshot> componentStates,
            ClosureSubscriptionInventory closureSubscriptions,
            Set<String> publicationReceipts,
            Map<String, ContractsClosurePublicationReceipt>
                    closurePublicationReceipts) {
        ClosureSnapshot {
            documentHeads = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            documentHeads, "documentHeads")));
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    occurrenceInventoryGeneration,
                    "occurrenceInventoryGeneration");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    componentIndexGeneration,
                    "componentIndexGeneration");
            occurrenceInventory = Objects.requireNonNull(
                    occurrenceInventory, "occurrenceInventory");
            graphGenerations = Objects.requireNonNull(
                    graphGenerations, "graphGenerations");
            componentStates = List.copyOf(Objects.requireNonNull(
                    componentStates, "componentStates"));
            closureSubscriptions = Objects.requireNonNull(
                    closureSubscriptions, "closureSubscriptions");
            // These are already immutable StoreState views. Retaining them
            // avoids copying global receipt inventories for a local capture.
            publicationReceipts = Objects.requireNonNull(
                    publicationReceipts, "publicationReceipts");
            closurePublicationReceipts = Objects.requireNonNull(
                    closurePublicationReceipts,
                    "closurePublicationReceipts");
        }

        DocumentHead requireHead(DocumentId documentId) {
            DocumentHead head = documentHeads.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (head == null) {
                throw new IllegalArgumentException(
                        "Document is outside the captured closure "
                                + documentId);
            }
            return head;
        }
    }

    /** One immutable read image used by tests and future persistence adapters. */
    record PublicationSnapshot(
            Map<DocumentId, DocumentHead> documentHeads,
            long occurrenceInventoryGeneration,
            long componentIndexGeneration,
            ManagedOccurrenceInventory occurrenceInventory,
            ProcessEmbeddedComponentIndex componentIndex,
            ClosureGraphGenerationInventory graphGenerations,
            List<ComponentSnapshot> componentStates,
            ClosureSubscriptionInventory closureSubscriptions,
            List<PublicEventOccurrence> outbox,
            List<CheckpointWrite> checkpointEvidence,
            Set<String> publicationReceipts,
            Map<String, ContractsClosureAdmissionReceipt>
                    admissionReceipts,
            Map<String, ContractsClosurePublicationReceipt>
                    closurePublicationReceipts) {
        PublicationSnapshot {
            documentHeads = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            documentHeads, "documentHeads")));
            occurrenceInventory = Objects.requireNonNull(
                    occurrenceInventory, "occurrenceInventory");
            componentIndex = Objects.requireNonNull(
                    componentIndex, "componentIndex");
            graphGenerations = Objects.requireNonNull(
                    graphGenerations, "graphGenerations");
            componentStates = List.copyOf(componentStates);
            closureSubscriptions = Objects.requireNonNull(
                    closureSubscriptions, "closureSubscriptions");
            outbox = List.copyOf(outbox);
            checkpointEvidence = List.copyOf(checkpointEvidence);
            publicationReceipts = Collections.unmodifiableSet(
                    new LinkedHashSet<>(publicationReceipts));
            admissionReceipts = Collections.unmodifiableMap(
                    new LinkedHashMap<>(admissionReceipts));
            closurePublicationReceipts = Collections.unmodifiableMap(
                    new LinkedHashMap<>(closurePublicationReceipts));
        }

        static PublicationSnapshot from(StoreState state) {
            TreeMap<DocumentId, DocumentHead> canonicalHeads =
                    new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
            state.sessions().forEach((documentId, session) -> canonicalHeads.put(
                    documentId,
                    new DocumentHead(
                            session.epoch(),
                            session.currentRepresentation().blueId())));
            return new PublicationSnapshot(
                    canonicalHeads,
                    state.occurrenceInventoryGeneration(),
                    state.componentIndexGeneration(),
                    state.occurrenceInventory(),
                    state.componentIndex(),
                    state.graphGenerations(),
                    state.componentStates(),
                    state.closureSubscriptions(),
                    state.outbox(),
                    state.checkpointEvidence(),
                    state.publicationReceipts(),
                    state.admissionReceipts(),
                    state.closurePublicationReceipts());
        }

        DocumentHead requireHead(DocumentId documentId) {
            DocumentHead head = documentHeads.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (head == null) {
                throw new IllegalArgumentException(
                        "Unknown document " + documentId);
            }
            return head;
        }
    }

    /** Exact durable CAS head of one independently managed document. */
    record DocumentHead(long epoch, String blueId) {
        DocumentHead {
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    epoch, "epoch");
            Objects.requireNonNull(blueId, "blueId");
        }
    }
}
