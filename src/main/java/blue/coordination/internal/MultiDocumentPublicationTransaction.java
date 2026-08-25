package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * One package-internal copy-on-write publication over multiple document heads.
 *
 * <p>The transaction has no ambient container lookup. Callers explicitly fence
 * every document lineage whose exact head they depend on. Touched sessions are
 * copied and committed off-store, while bindings, component state, outbox, and
 * checkpoint evidence remain staged in replacement collections. Only the
 * enclosing store performs the final reference swap.</p>
 */
final class MultiDocumentPublicationTransaction {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    enum FailurePoint {
        AFTER_CAS_CHECKS,
        AFTER_DOCUMENTS_STAGED,
        AFTER_TOPOLOGY_STAGED,
        BEFORE_SWAP
    }

    private final InMemoryDocumentStore store;
    private final String publicationIdentity;
    private final long expectedOccurrenceInventoryGeneration;
    private final long expectedComponentIndexGeneration;
    private final Map<DocumentId, InMemoryDocumentStore.DocumentHead>
            expectedHeads = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Set<DocumentId> expectedAbsent = new java.util.TreeSet<>(
            EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, DocumentUpdate> documentUpdates =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, DocumentSession> newSessions =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<String, String> expectedComponentStates =
            new LinkedHashMap<>();
    private final List<ComponentSnapshot> stagedComponentStates =
            new ArrayList<>();
    private final List<PublicEventOccurrence> stagedOutbox =
            new ArrayList<>();
    private final List<CheckpointWrite> stagedCheckpointEvidence =
            new ArrayList<>();
    private ManagedOccurrenceInventory stagedOccurrenceInventory;
    private Long resultingOccurrenceInventoryGeneration;
    private Long resultingComponentIndexGeneration;
    private ClosureProcessResult stagedGraphGeneration;
    private ClosureProcessResult stagedClosureSubscriptions;
    private boolean stagedAdmissionResult;
    private ClosureInvocationInput stagedAdmissionInput;
    private ClosureInvocationInput stagedManagedExpansionInput;
    private ContractsClosureAdmissionReceipt stagedAdmissionReceipt;
    private ContractsClosurePublicationReceipt stagedClosurePublicationReceipt;
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private boolean attempted;

    MultiDocumentPublicationTransaction(
            InMemoryDocumentStore store,
            String publicationIdentity,
            long expectedOccurrenceInventoryGeneration,
            long expectedComponentIndexGeneration) {
        this.store = Objects.requireNonNull(store, "store");
        this.publicationIdentity = requireText(
                publicationIdentity, "publicationIdentity");
        this.expectedOccurrenceInventoryGeneration = requireSafeInteger(
                expectedOccurrenceInventoryGeneration,
                "expectedOccurrenceInventoryGeneration");
        this.expectedComponentIndexGeneration = requireSafeInteger(
                expectedComponentIndexGeneration,
                "expectedComponentIndexGeneration");
    }

    /** Adds one exact epoch-and-BlueId CAS fence. */
    synchronized MultiDocumentPublicationTransaction expectHead(
            DocumentId documentId,
            long expectedEpoch,
            String expectedBlueId) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        if (expectedAbsent.contains(selected)) {
            throw new IllegalArgumentException(
                    "Document already has an expected-absent fence "
                            + selected);
        }
        InMemoryDocumentStore.DocumentHead proposed =
                new InMemoryDocumentStore.DocumentHead(
                        requireSafeInteger(expectedEpoch, "expectedEpoch"),
                        BlueIds.requireBlueIdOrCyclicMember(
                                expectedBlueId, "expectedBlueId"));
        if (expectedHeads.putIfAbsent(selected, proposed) != null) {
            throw new IllegalArgumentException(
                    "Duplicate document-head fence " + selected);
        }
        return this;
    }

    /** Adds an exact CAS fence requiring one lineage to remain absent. */
    synchronized MultiDocumentPublicationTransaction expectAbsent(
            DocumentId documentId) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        if (expectedHeads.containsKey(selected)
                || !expectedAbsent.add(selected)) {
            throw new IllegalArgumentException(
                    "Duplicate or conflicting expected-absent fence "
                            + selected);
        }
        return this;
    }

    /** Adds one exact component-lineage and state-identity CAS fence. */
    synchronized MultiDocumentPublicationTransaction expectComponentState(
            ComponentSnapshot component) {
        ensureOpen();
        ComponentSnapshot selected = Objects.requireNonNull(
                component, "component");
        if (expectedComponentStates.putIfAbsent(
                selected.componentIdentity(),
                selected.componentStateIdentity()) != null) {
            throw new IllegalArgumentException(
                    "Duplicate component-state fence "
                            + selected.componentIdentity());
        }
        return this;
    }

    /** Stages one next durable revision and its Coordination-owned session state. */
    synchronized MultiDocumentPublicationTransaction stageDocument(
            DocumentRevision revision,
            EmbeddedOnlyLayout resultingLayout,
            ExternalOrderKey committedFrontier,
            List<SubscriptionDelta.Entry> resultingSubscriptions,
            String transitionReceipt) {
        ensureOpen();
        DocumentUpdate update = new DocumentUpdate(
                revision,
                resultingLayout,
                committedFrontier,
                resultingSubscriptions,
                transitionReceipt);
        if (documentUpdates.putIfAbsent(
                update.revision().documentId(), update) != null) {
            throw new IllegalArgumentException(
                    "Duplicate staged document revision "
                            + update.revision().documentId());
        }
        return this;
    }

    /** Stages one fully initialized new session behind an absent-lineage CAS. */
    synchronized MultiDocumentPublicationTransaction stageNewSession(
            DocumentSession session) {
        ensureOpen();
        DocumentSession selected = Objects.requireNonNull(session, "session");
        DocumentId documentId = selected.documentId();
        if (documentUpdates.containsKey(documentId)
                || newSessions.putIfAbsent(documentId, selected) != null) {
            throw new IllegalArgumentException(
                    "Duplicate staged new session " + documentId);
        }
        return this;
    }

    /**
     * Stages the complete Contracts-owned occurrence inventory and both exact
     * lifecycle generations resulting from it.
     */
    synchronized MultiDocumentPublicationTransaction stageOccurrenceInventory(
            ManagedOccurrenceInventory occurrenceInventory,
            long occurrenceInventoryGeneration,
            long componentIndexGeneration) {
        ensureOpen();
        if (stagedOccurrenceInventory != null) {
            throw new IllegalStateException(
                    "Occurrence inventory is already staged");
        }
        stagedOccurrenceInventory = Objects.requireNonNull(
                occurrenceInventory, "occurrenceInventory");
        resultingOccurrenceInventoryGeneration = requireSafeInteger(
                occurrenceInventoryGeneration,
                "occurrenceInventoryGeneration");
        resultingComponentIndexGeneration = requireSafeInteger(
                componentIndexGeneration,
                "componentIndexGeneration");
        return this;
    }

    /**
     * Stages the exact subscription transition carried by one verified
     * successful Contracts closure result.
     *
     * <p>Deltas are applied to the store image observed after CAS checks, not
     * to a caller-authored replacement list. This preserves disjoint commits
     * and prevents one stale snapshot from erasing another document's exact
     * subscription state.</p>
     */
    synchronized MultiDocumentPublicationTransaction
            stageClosureSubscriptionDeltas(ClosureProcessResult result) {
        ensureOpen();
        ClosureProcessResult selected = Objects.requireNonNull(result, "result");
        if (!selected.commits()) {
            throw new IllegalArgumentException(
                    "Only a successful closure result can stage subscriptions");
        }
        if (stagedClosureSubscriptions != null) {
            throw new IllegalStateException(
                    "Closure subscription deltas are already staged");
        }
        if (stagedGraphGeneration == null) {
            stagedGraphGeneration = selected;
        } else {
            requireSameClosureResult(stagedGraphGeneration, selected);
        }
        stagedClosureSubscriptions = selected;
        return this;
    }

    /**
     * Stages the cohort-local durable graph generation carried by one
     * verified successful Contracts result.
     */
    synchronized MultiDocumentPublicationTransaction
            stageClosureGraphGeneration(ClosureProcessResult result) {
        ensureOpen();
        ClosureProcessResult selected = Objects.requireNonNull(result, "result");
        if (!selected.commits()) {
            throw new IllegalArgumentException(
                    "Only a successful closure result can stage graph state");
        }
        if (stagedGraphGeneration != null) {
            throw new IllegalStateException(
                    "Closure graph generation is already staged");
        }
        stagedGraphGeneration = selected;
        return this;
    }

    /**
     * Stages graph and subscription state for an all-new verified admission.
     * Existing and new lineages cannot be mixed in this bounded lane.
     */
    synchronized MultiDocumentPublicationTransaction
            stageClosureAdmissionResult(ClosureProcessResult result) {
        ensureOpen();
        ClosureProcessResult selected = Objects.requireNonNull(result, "result");
        if (!selected.commits()) {
            throw new IllegalArgumentException(
                    "Only a successful closure admission can be staged");
        }
        if (stagedGraphGeneration != null
                || stagedClosureSubscriptions != null) {
            throw new IllegalStateException(
                    "Closure result state is already staged");
        }
        stagedGraphGeneration = selected;
        stagedClosureSubscriptions = selected;
        stagedAdmissionResult = true;
        return this;
    }

    /**
     * Stages an authenticated admission which may retain exact existing
     * members while atomically creating the absent partition.
     */
    synchronized MultiDocumentPublicationTransaction
            stageClosureAdmissionResult(
                    ClosureInvocationInput input,
                    ClosureProcessResult result) {
        ensureOpen();
        ClosureInvocationInput invocation = Objects.requireNonNull(
                input, "input");
        if (invocation.operation()
                != ClosureInvocationInput.Operation.ADMIT_CLOSURE) {
            throw new IllegalArgumentException(
                    "A closure admission requires ADMIT_CLOSURE input");
        }
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null
                || !selected.invocationIdentity().equals(
                        invocation.invocationIdentity())
                || !selected.inputClosureIdentity().equals(
                        invocation.snapshot().closureIdentity())) {
            throw new IllegalArgumentException(
                    "Admission result does not authenticate its input");
        }
        if (stagedAdmissionInput != null) {
            throw new IllegalStateException(
                    "Closure admission input is already staged");
        }
        stageClosureAdmissionResult(selected);
        stagedAdmissionInput = invocation;
        return this;
    }

    /**
     * Stages one verified PROCESS result which initializes absent members in
     * the same atomic publication as its existing cohort transitions.
     */
    synchronized MultiDocumentPublicationTransaction
            stageManagedExpansionResult(
                    ClosureInvocationInput input,
                    ClosureProcessResult result) {
        ensureOpen();
        ClosureInvocationInput invocation = stageManagedExpansionInput(input);
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a successful managed expansion can be staged");
        }
        if (!selected.invocationIdentity().equals(
                invocation.invocationIdentity())
                || !selected.inputClosureIdentity().equals(
                        invocation.snapshot().closureIdentity())) {
            throw new IllegalArgumentException(
                    "Managed expansion result does not authenticate its input");
        }
        if (stagedGraphGeneration != null
                || stagedClosureSubscriptions != null) {
            throw new IllegalStateException(
                    "Closure result state is already staged");
        }
        stagedGraphGeneration = selected;
        stagedClosureSubscriptions = selected;
        return this;
    }

    /** Stages the authenticated virtual-member input for a receipt-only rollback. */
    synchronized ClosureInvocationInput stageManagedExpansionInput(
            ClosureInvocationInput input) {
        ensureOpen();
        ClosureInvocationInput invocation = Objects.requireNonNull(
                input, "input");
        if (invocation.operation()
                != ClosureInvocationInput.Operation.PROCESS_CLOSURE) {
            throw new IllegalArgumentException(
                    "A managed expansion requires PROCESS_CLOSURE input");
        }
        if (stagedManagedExpansionInput != null) {
            throw new IllegalStateException(
                    "Managed expansion input is already staged");
        }
        stagedManagedExpansionInput = invocation;
        return invocation;
    }

    /** Stages the typed durable receipt for the successful admission. */
    synchronized MultiDocumentPublicationTransaction stageAdmissionReceipt(
            ContractsClosureAdmissionReceipt receipt) {
        ensureOpen();
        ContractsClosureAdmissionReceipt selected = Objects.requireNonNull(
                receipt, "receipt");
        if (!publicationIdentity.equals(selected.publicationIdentity())) {
            throw new IllegalArgumentException(
                    "Admission receipt publication identity mismatch");
        }
        if (selected.publicationOutcome()
                != ContractsClosureAdmissionReceipt.PublicationOutcome
                        .PUBLISHED) {
            throw new IllegalArgumentException(
                    "Only a newly published admission receipt can be staged");
        }
        if (stagedAdmissionReceipt != null) {
            throw new IllegalStateException(
                    "Admission receipt is already staged");
        }
        stagedAdmissionReceipt = selected;
        return this;
    }

    /** Stages the typed durable terminal receipt for one PROCESS_CLOSURE lane. */
    synchronized MultiDocumentPublicationTransaction
            stageClosurePublicationReceipt(
                    ContractsClosurePublicationReceipt receipt) {
        ensureOpen();
        ContractsClosurePublicationReceipt selected = Objects.requireNonNull(
                receipt, "receipt");
        if (!publicationIdentity.equals(selected.publicationIdentity())) {
            throw new IllegalArgumentException(
                    "Process receipt publication identity mismatch");
        }
        if (stagedClosurePublicationReceipt != null) {
            throw new IllegalStateException(
                    "Process receipt is already staged");
        }
        stagedClosurePublicationReceipt = selected;
        return this;
    }

    /** Stages exact resulting state for every affected component supplied. */
    synchronized MultiDocumentPublicationTransaction stageComponentStates(
            Collection<ComponentSnapshot> componentStates) {
        ensureOpen();
        Objects.requireNonNull(componentStates, "componentStates").forEach(
                component -> stagedComponentStates.add(
                        Objects.requireNonNull(component, "componentState")));
        return this;
    }

    /** Stages one invocation's already-verified public event occurrences. */
    synchronized MultiDocumentPublicationTransaction stageOutbox(
            Collection<PublicEventOccurrence> publicEvents) {
        ensureOpen();
        Objects.requireNonNull(publicEvents, "publicEvents").forEach(event ->
                stagedOutbox.add(Objects.requireNonNull(
                        event, "publicEvent")));
        return this;
    }

    /** Stages one invocation's already-verified checkpoint write evidence. */
    synchronized MultiDocumentPublicationTransaction stageCheckpointEvidence(
            Collection<CheckpointWrite> checkpointWrites) {
        ensureOpen();
        Objects.requireNonNull(checkpointWrites, "checkpointWrites").forEach(
                checkpoint -> stagedCheckpointEvidence.add(
                        Objects.requireNonNull(
                                checkpoint, "checkpointWrite")));
        return this;
    }

    /** Test/persistence-adapter hook; failures occur strictly before swap. */
    synchronized MultiDocumentPublicationTransaction onFailurePoint(
            Consumer<FailurePoint> injector) {
        ensureOpen();
        failureInjector = Objects.requireNonNull(injector, "injector");
        return this;
    }

    /** Attempts the one-shot CAS and publishes exactly one replacement image. */
    synchronized void commit() {
        ensureOpen();
        attempted = true;
        store.commit(this);
    }

    synchronized InMemoryDocumentStore.StoreState prepareReplacement(
            InMemoryDocumentStore.StoreState before) {
        Objects.requireNonNull(before, "before");
        EngineMetrics metrics = store.metrics();
        if ((stagedGraphGeneration == null)
                != (stagedClosureSubscriptions == null)) {
            throw new IllegalStateException(
                    "Closure graph and subscription state must be staged "
                            + "from one complete result");
        }
        if (stagedGraphGeneration != null) {
            requireSameClosureResult(
                    stagedGraphGeneration, stagedClosureSubscriptions);
        }
        requireAdmissionShape();
        requireGenerationFences(before);
        requireHeadFences(before);
        requireAbsentFences(before);
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
                before.componentStates().size());
        requireComponentStateFences(before);
        requireClosurePublicationShape();
        if (before.hasPublicationReceipt(publicationIdentity)) {
            throw new IllegalStateException(
                    "Duplicate publication receipt " + publicationIdentity);
        }
        failureInjector.accept(FailurePoint.AFTER_CAS_CHECKS);

        PersistentOrderedMap<DocumentId, DocumentSession> resultingSessionIndex =
                before.sessionIndex();
        long sessionIndexComparisons = 0L;
        long sessionIndexNodesCopied = 0L;
        ManagedLineageIndex resultingLineages = before.lineageIndex();
        for (Map.Entry<DocumentId, DocumentSession> entry
                : newSessions.entrySet()) {
            if (!expectedAbsent.contains(entry.getKey())) {
                throw new IllegalStateException(
                        "Staged new session has no expected-absent fence "
                                + entry.getKey());
            }
            DocumentSession replacement =
                    entry.getValue().copyForAtomicPublication();
            PersistentOrderedMap.Mutation<DocumentId, DocumentSession>
                    mutation = resultingSessionIndex.put(
                            entry.getKey(), replacement);
            resultingSessionIndex = mutation.map();
            sessionIndexComparisons = Math.addExact(
                    sessionIndexComparisons, mutation.comparisons());
            sessionIndexNodesCopied = Math.addExact(
                    sessionIndexNodesCopied, mutation.copiedNodes());
            resultingLineages = resultingLineages.withNewLineage(
                    replacement);
        }
        for (DocumentUpdate update : documentUpdates.values()) {
            DocumentId documentId = update.revision().documentId();
            if (!expectedHeads.containsKey(documentId)) {
                throw new IllegalStateException(
                        "Staged document has no exact head fence " + documentId);
            }
            DocumentSession current = requireSession(before, documentId);
            requireRevisionTransition(current, update);
            DocumentSession replacement =
                    current.copyForAtomicPublication();
            replacement.commit(
                    update.revision(),
                    update.resultingLayout(),
                    update.committedFrontier(),
                    update.resultingSubscriptions(),
                    update.transitionReceipt());
            if (stagedClosurePublicationReceipt != null
                    && stagedClosurePublicationReceipt.commits()) {
                replacement.markGraphPublished();
                replacement.markReady(update.committedFrontier());
            }
            PersistentOrderedMap.Mutation<DocumentId, DocumentSession>
                    mutation = resultingSessionIndex.put(
                            documentId, replacement);
            resultingSessionIndex = mutation.map();
            sessionIndexComparisons = Math.addExact(
                    sessionIndexComparisons, mutation.comparisons());
            sessionIndexNodesCopied = Math.addExact(
                    sessionIndexNodesCopied, mutation.copiedNodes());
            resultingLineages = resultingLineages.withAdvancedRevision(
                    replacement);
        }
        metrics.add("store.sessionIndexComparisons", sessionIndexComparisons);
        metrics.add("store.sessionIndexNodesCopied", sessionIndexNodesCopied);
        Map<DocumentId, DocumentSession> resultingSessions =
                new PersistentMapView<>(resultingSessionIndex);
        failureInjector.accept(FailurePoint.AFTER_DOCUMENTS_STAGED);

        ManagedOccurrenceInventory resultingInventory =
                stagedOccurrenceInventory == null
                        ? before.occurrenceInventory()
                        : stagedOccurrenceInventory;
        long resultingInventoryGeneration =
                stagedOccurrenceInventory == null
                        ? before.occurrenceInventoryGeneration()
                        : this.resultingOccurrenceInventoryGeneration;
        ProcessEmbeddedComponentIndex resultingIndex =
                stagedOccurrenceInventory == null
                        ? before.componentIndex()
                        : rebuildComponentIndex(
                                resultingSessions,
                                resultingInventory,
                                metrics);
        long resultingIndexGeneration =
                stagedOccurrenceInventory == null
                        ? before.componentIndexGeneration()
                        : this.resultingComponentIndexGeneration;
        recordGenerationTransitionTraversals(
                before, resultingInventory, resultingIndex, metrics);
        requireGenerationTransitions(
                before,
                resultingInventory,
                resultingInventoryGeneration,
                resultingIndexGeneration,
                resultingIndex.documents());

        List<ComponentSnapshot> resultingComponents = mergeComponentStates(
                before,
                resultingSessions,
                resultingIndex);
        if (stagedGraphGeneration != null) {
            ContractsStructuralWorkMetrics.recordGlobalPasses(
                    metrics,
                    ContractsStructuralWorkMetrics
                            .GLOBAL_GRAPH_ENTRIES_TRAVERSED,
                    2L,
                    Math.multiplyExact(
                            before.graphGenerations().documents().size(),
                            2L));
        }
        ClosureGraphGenerationInventory resultingGraphGenerations =
                stagedGraphGeneration == null
                        ? before.graphGenerations()
                        : stagedAdmissionResult
                        ? stagedAdmissionInput != null
                                && !expectedHeads.isEmpty()
                                ? before.graphGenerations().applyExpansion(
                                        stagedGraphGeneration,
                                        expectedHeads.keySet(),
                                        expectedAbsent)
                                : before.graphGenerations().admit(
                                        stagedGraphGeneration, expectedAbsent)
                        : stagedManagedExpansionInput != null
                        ? before.graphGenerations().applyExpansion(
                                stagedGraphGeneration,
                                expectedHeads.keySet(),
                                expectedAbsent)
                        : before.graphGenerations().apply(stagedGraphGeneration);
        ClosureSubscriptionInventory resultingClosureSubscriptions =
                applyClosureSubscriptions(before, metrics);
        requireContiguousPublicEventOrdinals(stagedOutbox);
        PersistentAppendLog<PublicEventOccurrence> resultingOutbox =
                before.outboxLog().appendAll(stagedOutbox);
        requireContiguousCheckpointOrdinals(stagedCheckpointEvidence);
        PersistentAppendLog<CheckpointWrite> resultingCheckpoints =
                before.checkpointEvidenceLog().appendAll(
                        stagedCheckpointEvidence);
        PersistentOrderedMap.Mutation<String, Boolean> receiptMutation =
                before.publicationReceiptIndex().put(
                        publicationIdentity, Boolean.TRUE);
        PersistentOrderedMap<String, Boolean> resultingReceipts =
                receiptMutation.map();
        PersistentOrderedMap<String, ContractsClosureAdmissionReceipt>
                resultingAdmissionReceipts = before.admissionReceiptIndex();
        long receiptComparisons = receiptMutation.comparisons();
        long receiptNodesCopied = receiptMutation.copiedNodes();
        if (stagedAdmissionReceipt != null) {
            PersistentOrderedMap.Mutation<String,
                    ContractsClosureAdmissionReceipt> mutation =
                    resultingAdmissionReceipts.put(
                            publicationIdentity, stagedAdmissionReceipt);
            resultingAdmissionReceipts = mutation.map();
            receiptComparisons = Math.addExact(
                    receiptComparisons, mutation.comparisons());
            receiptNodesCopied = Math.addExact(
                    receiptNodesCopied, mutation.copiedNodes());
        }
        PersistentOrderedMap<String, ContractsClosurePublicationReceipt>
                resultingClosurePublicationReceipts =
                before.closurePublicationReceiptIndex();
        if (stagedClosurePublicationReceipt != null) {
            PersistentOrderedMap.Mutation<String,
                    ContractsClosurePublicationReceipt> mutation =
                    resultingClosurePublicationReceipts.put(
                            publicationIdentity,
                            stagedClosurePublicationReceipt);
            resultingClosurePublicationReceipts = mutation.map();
            receiptComparisons = Math.addExact(
                    receiptComparisons, mutation.comparisons());
            receiptNodesCopied = Math.addExact(
                    receiptNodesCopied, mutation.copiedNodes());
        }
        metrics.add("store.receiptIndexComparisons", receiptComparisons);
        metrics.add("store.receiptIndexNodesCopied", receiptNodesCopied);
        requireClosurePublicationResult(
                resultingSessions,
                resultingInventory,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions);
        requireAdmissionPublicationResult(
                resultingSessions,
                resultingInventory,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions);
        failureInjector.accept(FailurePoint.AFTER_TOPOLOGY_STAGED);

        recordRemainingGlobalStateConstruction(
                resultingIndex,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions,
                metrics);

        InMemoryDocumentStore.StoreState replacement =
                InMemoryDocumentStore.StoreState.trustedTransition(
                        resultingSessionIndex,
                        resultingLineages,
                        resultingInventory,
                        resultingInventoryGeneration,
                        resultingIndex,
                        resultingIndexGeneration,
                        resultingGraphGenerations,
                        resultingComponents,
                        resultingClosureSubscriptions,
                        resultingOutbox,
                        resultingCheckpoints,
                        resultingReceipts,
                        resultingAdmissionReceipts,
                        resultingClosurePublicationReceipts);
        failureInjector.accept(FailurePoint.BEFORE_SWAP);
        return replacement;
    }

    private static ProcessEmbeddedComponentIndex rebuildComponentIndex(
            Map<DocumentId, DocumentSession> sessions,
            ManagedOccurrenceInventory inventory,
            EngineMetrics metrics) {
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_SESSION_ENTRIES_TRAVERSED,
                sessions.size());
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                inventory.rows().size());
        return InMemoryDocumentStore.componentIndex(
                sessions.values(), inventory);
    }

    private static void recordGenerationTransitionTraversals(
            InMemoryDocumentStore.StoreState before,
            ManagedOccurrenceInventory resultingInventory,
            ProcessEmbeddedComponentIndex resultingIndex,
            EngineMetrics metrics) {
        ContractsStructuralWorkMetrics.recordGlobalPasses(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                2L,
                Math.addExact(
                        (long) before.occurrenceInventory().rows().size(),
                        resultingInventory.rows().size()));
        ContractsStructuralWorkMetrics.recordGlobalPasses(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_SESSION_ENTRIES_TRAVERSED,
                2L,
                Math.addExact(
                        (long) before.componentIndex().documents().size(),
                        resultingIndex.documents().size()));
        ContractsStructuralWorkMetrics.recordGlobalPasses(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                2L,
                Math.addExact(
                        (long) before.occurrenceInventory()
                                .activeRows().size(),
                        resultingInventory.activeRows().size()));
    }

    private ClosureSubscriptionInventory applyClosureSubscriptions(
            InMemoryDocumentStore.StoreState before,
            EngineMetrics metrics) {
        if (stagedClosureSubscriptions == null) {
            return before.closureSubscriptions();
        }
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
                before.closureSubscriptions().states().size());
        ClosureSubscriptionInventory resulting = before
                .closureSubscriptions().apply(stagedClosureSubscriptions);
        ContractsStructuralWorkMetrics.recordGlobalPasses(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
                2L,
                Math.multiplyExact(resulting.states().size(), 2L));
        return resulting;
    }

    private static void recordRemainingGlobalStateConstruction(
            ProcessEmbeddedComponentIndex componentIndex,
            ClosureGraphGenerationInventory graphGenerations,
            List<ComponentSnapshot> components,
            ClosureSubscriptionInventory subscriptions,
            EngineMetrics metrics) {
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_GRAPH_ENTRIES_TRAVERSED,
                graphGenerations.documents().size());
        ContractsStructuralWorkMetrics.recordGlobalPasses(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
                2L,
                Math.addExact((long) components.size(),
                        componentIndex.components().size()));
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
                subscriptions.states().size());
    }

    private void requireGenerationFences(
            InMemoryDocumentStore.StoreState before) {
        if (before.occurrenceInventoryGeneration()
                != expectedOccurrenceInventoryGeneration) {
            throw new AtomicPublicationCasException(
                    "Stale occurrence inventory generation: expected "
                            + expectedOccurrenceInventoryGeneration
                            + " but found "
                            + before.occurrenceInventoryGeneration());
        }
        if (before.componentIndexGeneration()
                != expectedComponentIndexGeneration) {
            throw new AtomicPublicationCasException(
                    "Stale component index generation: expected "
                            + expectedComponentIndexGeneration
                            + " but found "
                            + before.componentIndexGeneration());
        }
    }

    private void requireHeadFences(
            InMemoryDocumentStore.StoreState before) {
        for (Map.Entry<DocumentId, InMemoryDocumentStore.DocumentHead> entry
                : expectedHeads.entrySet()) {
            DocumentSession session = requireSession(before, entry.getKey());
            InMemoryDocumentStore.DocumentHead actual =
                    new InMemoryDocumentStore.DocumentHead(
                            session.epoch(),
                            session.currentRevision().after().blueId());
            if (!actual.equals(entry.getValue())) {
                throw new AtomicPublicationCasException(
                        "Stale document head " + entry.getKey()
                                + ": expected " + entry.getValue()
                                + " but found " + actual);
            }
        }
    }

    private void requireAbsentFences(
            InMemoryDocumentStore.StoreState before) {
        for (DocumentId documentId : expectedAbsent) {
            if (before.sessions().containsKey(documentId)) {
                throw new AtomicPublicationCasException(
                        "Expected absent document is already present "
                                + documentId);
            }
        }
    }

    private void requireAdmissionShape() {
        if (stagedManagedExpansionInput != null) {
            requireManagedExpansionShape();
            return;
        }
        if (!stagedAdmissionResult) {
            if (stagedAdmissionReceipt != null
                    || !expectedAbsent.isEmpty()
                    || !newSessions.isEmpty()) {
                throw new IllegalStateException(
                        "New sessions and typed admission receipts require one "
                                + "complete closure admission result");
            }
            return;
        }
        if (stagedClosurePublicationReceipt != null) {
            throw new IllegalStateException(
                    "Admission and process receipts cannot share a transaction");
        }
        if (!documentUpdates.isEmpty()) {
            throw new IllegalStateException(
                    "Static admission cannot advance existing document heads");
        }
        if (expectedAbsent.isEmpty()
                || !newSessions.keySet().equals(expectedAbsent)) {
            throw new IllegalStateException(
                    "Closure admission must stage exactly every absent lineage");
        }
        if (stagedOccurrenceInventory == null
                || stagedAdmissionReceipt == null) {
            throw new IllegalStateException(
                    "Closure admission requires complete topology and a typed "
                            + "durable receipt");
        }
        Set<DocumentId> resultDocuments = new LinkedHashSet<>();
        stagedGraphGeneration.resultingDocuments().forEach(document ->
                resultDocuments.add(DocumentId.of(
                        document.documentId().value())));
        Set<DocumentId> companionDocuments = new LinkedHashSet<>();
        stagedGraphGeneration.platformCommitCompanion()
                .expectedInputDocuments().forEach(document ->
                        companionDocuments.add(DocumentId.of(
                                document.documentId().value())));
        LinkedHashSet<DocumentId> expectedMembers = new LinkedHashSet<>(
                expectedHeads.keySet());
        expectedMembers.addAll(expectedAbsent);
        if (!resultDocuments.equals(expectedMembers)
                || !companionDocuments.equals(expectedMembers)
                || !new LinkedHashSet<>(stagedAdmissionReceipt.documentIds())
                        .equals(expectedMembers)
                || !stagedAdmissionReceipt.attempt().isComplete()
                || stagedAdmissionReceipt.attempt().processResult()
                        != stagedGraphGeneration) {
            throw new IllegalStateException(
                    "Admission result, companion, sessions, and receipt name "
                            + "different document sets or results");
        }
        if (stagedAdmissionInput == null) {
            if (!expectedHeads.isEmpty()
                    || !expectedMembers.equals(expectedAbsent)) {
                throw new IllegalStateException(
                        "Legacy admission staging requires all members absent");
            }
        } else {
            LinkedHashMap<DocumentId,
                    blue.language.processor.closure.ManagedDocumentSnapshot>
                    inputDocuments = new LinkedHashMap<>();
            stagedAdmissionInput.snapshot().managedDocuments()
                    .forEach(document -> inputDocuments.put(
                            DocumentId.of(document.documentId().value()),
                            document));
            if (!stagedGraphGeneration.invocationIdentity().equals(
                    stagedAdmissionInput.invocationIdentity())
                    || !stagedGraphGeneration.inputClosureIdentity().equals(
                            stagedAdmissionInput.snapshot().closureIdentity())
                    || !inputDocuments.keySet().equals(expectedMembers)) {
                throw new IllegalStateException(
                        "Admission input, result, and fences name different "
                                + "members or identities");
            }
            Map<DocumentId,
                    blue.language.processor.closure.ResultingDocument>
                    indexedResults = new LinkedHashMap<>();
            stagedGraphGeneration.resultingDocuments().forEach(document ->
                    indexedResults.put(
                            DocumentId.of(document.documentId().value()),
                            document));
            for (DocumentId documentId : expectedHeads.keySet()) {
                blue.language.processor.closure.ManagedDocumentSnapshot input =
                        inputDocuments.get(documentId);
                blue.language.processor.closure.ResultingDocument result =
                        indexedResults.get(documentId);
                InMemoryDocumentStore.DocumentHead expected =
                        expectedHeads.get(documentId);
                if (!input.initialized()
                        || input.epoch() != expected.epoch()
                        || !input.blueId().equals(expected.blueId())
                        || !result.beforeBlueId().equals(expected.blueId())
                        || result.epoch() != expected.epoch()
                        || !result.afterBlueId().equals(expected.blueId())) {
                    throw new IllegalStateException(
                            "Existing admission member is not retained at its "
                                    + "exact durable head " + documentId);
                }
            }
            for (DocumentId documentId : expectedAbsent) {
                blue.language.processor.closure.ManagedDocumentSnapshot input =
                        inputDocuments.get(documentId);
                blue.language.processor.closure.ResultingDocument result =
                        indexedResults.get(documentId);
                DocumentSession session = newSessions.get(documentId);
                if (input.initialized() || input.terminated()
                        || input.epoch() != 0L
                        || !result.initialized()
                        || result.epoch() != 0L
                        || session == null
                        || !session.currentRevision().after().blueId().equals(
                                result.afterBlueId())) {
                    throw new IllegalStateException(
                            "New admission member is not one exact epoch-zero "
                                    + "initialization " + documentId);
                }
            }
        }
        if (!stagedComponentStates.equals(
                stagedGraphGeneration.resultingComponents())
                || !stagedOutbox.equals(
                        stagedGraphGeneration.publicEvents())
                || !stagedCheckpointEvidence.equals(
                        stagedGraphGeneration.checkpointWrites())) {
            throw new IllegalStateException(
                    "Admission receipt requires the exact component, outbox, "
                            + "and checkpoint result");
        }
    }

    private void requireManagedExpansionShape() {
        if (stagedAdmissionResult || stagedAdmissionReceipt != null) {
            throw new IllegalStateException(
                    "Managed expansion cannot also publish an admission");
        }
        if (expectedHeads.isEmpty() || expectedAbsent.isEmpty()) {
            throw new IllegalStateException(
                    "Managed expansion requires present and absent fences");
        }
        if (stagedClosurePublicationReceipt == null) {
            throw new IllegalStateException(
                    "Managed expansion requires one typed process receipt");
        }
        boolean commits = stagedClosurePublicationReceipt.commits();
        if (commits && (!newSessions.keySet().equals(expectedAbsent)
                || stagedOccurrenceInventory == null)) {
            throw new IllegalStateException(
                    "Committing managed expansion must stage every absent "
                            + "lineage and complete topology");
        }
        if (!commits && (!newSessions.isEmpty()
                || stagedOccurrenceInventory != null)) {
            throw new IllegalStateException(
                    "Non-committing managed expansion must be receipt-only");
        }

        LinkedHashSet<DocumentId> expectedMembers = new LinkedHashSet<>(
                expectedHeads.keySet());
        expectedMembers.addAll(expectedAbsent);
        LinkedHashMap<DocumentId,
                blue.language.processor.closure.ManagedDocumentSnapshot>
                inputDocuments = new LinkedHashMap<>();
        stagedManagedExpansionInput.snapshot().managedDocuments()
                .forEach(document -> inputDocuments.put(
                        DocumentId.of(document.documentId().value()),
                        document));
        LinkedHashMap<DocumentId,
                blue.language.processor.closure.ResultingDocument>
                resultDocuments = new LinkedHashMap<>();
        ClosureProcessResult processResult = stagedClosurePublicationReceipt
                .attempt().processResult();
        if (!processResult.invocationIdentity().equals(
                stagedManagedExpansionInput.invocationIdentity())
                || !processResult.inputClosureIdentity().equals(
                        stagedManagedExpansionInput.snapshot()
                                .closureIdentity())) {
            throw new IllegalStateException(
                    "Managed expansion receipt does not authenticate its "
                            + "virtual-member input");
        }
        processResult.resultingDocuments().forEach(document ->
                resultDocuments.put(
                        DocumentId.of(document.documentId().value()),
                        document));
        LinkedHashSet<DocumentId> companionDocuments = new LinkedHashSet<>();
        if (commits) {
            processResult.platformCommitCompanion()
                    .expectedInputDocuments().forEach(document ->
                            companionDocuments.add(DocumentId.of(
                                    document.documentId().value())));
        }
        if (!inputDocuments.keySet().equals(expectedMembers)
                || !resultDocuments.keySet().equals(expectedMembers)
                || (commits
                        && !companionDocuments.equals(expectedMembers))
                || !new LinkedHashSet<>(stagedClosurePublicationReceipt
                        .documentIds()).equals(expectedMembers)
                || (commits
                        && processResult != stagedGraphGeneration)) {
            throw new IllegalStateException(
                    "Managed expansion input, result, fences, and receipt "
                            + "name different member sets or results");
        }
        for (DocumentId documentId : expectedHeads.keySet()) {
            blue.language.processor.closure.ManagedDocumentSnapshot input =
                    inputDocuments.get(documentId);
            InMemoryDocumentStore.DocumentHead expected = expectedHeads.get(
                    documentId);
            if (!input.initialized()
                    || input.epoch() != expected.epoch()
                    || !input.blueId().equals(expected.blueId())) {
                throw new IllegalStateException(
                        "Managed expansion present input is not its exact "
                                + "durable head " + documentId);
            }
        }
        for (DocumentId documentId : expectedAbsent) {
            blue.language.processor.closure.ManagedDocumentSnapshot input =
                    inputDocuments.get(documentId);
            blue.language.processor.closure.ResultingDocument result =
                    resultDocuments.get(documentId);
            DocumentSession session = newSessions.get(documentId);
            if (input.initialized() || input.terminated()
                    || input.epoch() != 0L
                    || (commits && (!result.initialized()
                            || result.epoch() != 0L
                            || session == null
                            || !session.currentRevision().after().blueId()
                                    .equals(result.afterBlueId())))) {
                throw new IllegalStateException(
                        "Managed expansion new lineage is not one exact "
                                + "epoch-zero initialization " + documentId);
            }
        }
    }

    private void requireClosurePublicationShape() {
        ContractsClosurePublicationReceipt receipt =
                stagedClosurePublicationReceipt;
        if (receipt == null) {
            return;
        }
        if (stagedAdmissionResult || stagedAdmissionReceipt != null) {
            throw new IllegalStateException(
                    "A process receipt cannot publish an admission");
        }
        Set<DocumentId> members = new LinkedHashSet<>(receipt.documentIds());
        Set<DocumentId> fencedMembers = new LinkedHashSet<>(
                expectedHeads.keySet());
        fencedMembers.addAll(expectedAbsent);
        if (!fencedMembers.equals(members)) {
            throw new IllegalStateException(
                    "A process receipt requires exact present/absent fences for its "
                            + "complete cohort");
        }
        ClosureProcessResult result = receipt.attempt().processResult();
        Map<DocumentId, blue.language.processor.closure.ResultingDocument>
                resultDocuments = new TreeMap<>(
                        EmbeddingBinding.DOCUMENT_ORDER);
        result.resultingDocuments().forEach(document -> resultDocuments.put(
                DocumentId.of(document.documentId().value()), document));
        for (Map.Entry<DocumentId,
                blue.language.processor.closure.ResultingDocument> entry
                : resultDocuments.entrySet()) {
            InMemoryDocumentStore.DocumentHead before = expectedHeads.get(
                    entry.getKey());
            blue.language.processor.closure.ResultingDocument after =
                    entry.getValue();
            if (before == null) {
                boolean completeCommit = result.commits()
                        && expectedAbsent.contains(entry.getKey())
                        && after.epoch() == 0L
                        && newSessions.containsKey(entry.getKey())
                        && newSessions.get(entry.getKey())
                                .currentRevision().after().blueId().equals(
                                        after.afterBlueId());
                boolean completeRollback = !result.commits()
                        && expectedAbsent.contains(entry.getKey())
                        && after.epoch() == 0L
                        && after.afterBlueId().equals(after.beforeBlueId());
                if (!completeCommit && !completeRollback) {
                    throw new IllegalStateException(
                            "Process receipt new lineage is not fully staged "
                                    + entry.getKey());
                }
                continue;
            }
            if (!before.blueId().equals(after.beforeBlueId())) {
                throw new IllegalStateException(
                        "Process receipt result predecessor differs from its "
                                + "exact head fence for " + entry.getKey());
            }
            boolean unchanged = after.epoch() == before.epoch()
                    && after.afterBlueId().equals(before.blueId());
            boolean advanced = after.epoch()
                    == Math.addExact(before.epoch(), 1L)
                    && documentUpdates.containsKey(entry.getKey())
                    && documentUpdates.get(entry.getKey()).revision().after()
                            .blueId().equals(after.afterBlueId());
            if (result.commits() ? !unchanged && !advanced : !unchanged) {
                throw new IllegalStateException(
                        "Process receipt result epoch/head transition is not "
                                + "fully staged for " + entry.getKey());
            }
            if (unchanged && documentUpdates.containsKey(entry.getKey())) {
                throw new IllegalStateException(
                        "An unchanged process result staged a document revision "
                                + entry.getKey());
            }
        }
        if (result.commits()) {
            if (stagedGraphGeneration == null
                    || stagedClosureSubscriptions == null) {
                throw new IllegalStateException(
                        "A committing process receipt requires complete graph "
                                + "and subscription staging");
            }
            requireSameClosureResult(stagedGraphGeneration, result);
            requireSameClosureResult(stagedClosureSubscriptions, result);
            if (!stagedOutbox.equals(result.publicEvents())
                    || !stagedCheckpointEvidence.equals(
                            result.checkpointWrites())
                    || !stagedComponentStates.equals(
                            result.resultingComponents())) {
                throw new IllegalStateException(
                        "A committing process receipt requires the exact "
                                + "component, outbox, and checkpoint result");
            }
            return;
        }
        if (!documentUpdates.isEmpty()
                || stagedOccurrenceInventory != null
                || !stagedComponentStates.isEmpty()
                || stagedGraphGeneration != null
                || stagedClosureSubscriptions != null
                || !stagedOutbox.isEmpty()
                || !stagedCheckpointEvidence.isEmpty()) {
            throw new IllegalStateException(
                    "A non-committing process receipt must be receipt-only");
        }
    }

    private void requireClosurePublicationResult(
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedOccurrenceInventory resultingInventory,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            List<ComponentSnapshot> resultingComponents,
            ClosureSubscriptionInventory resultingClosureSubscriptions) {
        ContractsClosurePublicationReceipt receipt =
                stagedClosurePublicationReceipt;
        if (receipt == null || !receipt.commits()) {
            return;
        }
        requireExactResultState(
                receipt.attempt().processResult(),
                new LinkedHashSet<>(receipt.documentIds()),
                resultingSessions,
                resultingInventory,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions,
                "Process receipt",
                store.metrics());
    }

    private void requireAdmissionPublicationResult(
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedOccurrenceInventory resultingInventory,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            List<ComponentSnapshot> resultingComponents,
            ClosureSubscriptionInventory resultingClosureSubscriptions) {
        if (stagedAdmissionReceipt == null) {
            return;
        }
        requireExactResultState(
                stagedAdmissionReceipt.attempt().processResult(),
                new LinkedHashSet<>(stagedAdmissionReceipt.documentIds()),
                resultingSessions,
                resultingInventory,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions,
                "Admission receipt",
                store.metrics());
    }

    private static void requireExactResultState(
            ClosureProcessResult result,
            Set<DocumentId> members,
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedOccurrenceInventory resultingInventory,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            List<ComponentSnapshot> resultingComponents,
            ClosureSubscriptionInventory resultingClosureSubscriptions,
            String label,
            EngineMetrics metrics) {
        for (DocumentId member : members) {
            if (!resultingSessions.containsKey(member)
                    || resultingGraphGenerations.require(member)
                            != result.graphGeneration()) {
                throw new IllegalStateException(
                        label + " graph/document state is incomplete for "
                                + member);
            }
        }

        List<OccurrenceRow> expectedRows = result.occurrenceBindings().stream()
                .map(OccurrenceRow::from)
                .toList();
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
                resultingInventory.rows().size());
        List<OccurrenceRow> actualRows = resultingInventory.rows().stream()
                // Affected-closure occurrence evidence is source-owned.
                // An ambient source that points into this forward closure is
                // neither an input member nor an output row of the Contracts
                // result, and must remain durable without widening capture to
                // a weakly connected component.
                .filter(row -> members.contains(DocumentId.of(
                        row.sourceDocumentId().value())))
                .map(OccurrenceRow::from)
                .toList();
        if (!expectedRows.equals(actualRows)) {
            throw new IllegalStateException(
                    label + " occurrence state is not the exact result");
        }

        List<String> expectedComponents = result.resultingComponents().stream()
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
        ContractsStructuralWorkMetrics.recordGlobalPass(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
                resultingComponents.size());
        List<String> actualComponents = resultingComponents.stream()
                .filter(component -> component.orderedMemberDocumentIds()
                        .stream().anyMatch(member -> members.contains(
                                DocumentId.of(member.value()))))
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
        if (!expectedComponents.equals(actualComponents)) {
            throw new IllegalStateException(
                    label + " component state is not the exact result");
        }

        ContractsStructuralWorkMetrics.recordGlobalPasses(
                metrics,
                ContractsStructuralWorkMetrics
                        .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
                members.size(),
                Math.multiplyExact(
                        (long) resultingClosureSubscriptions.states().size(),
                        members.size()));
        for (DocumentId member : members) {
            List<blue.language.processor.closure.SubscriptionState> states =
                    resultingClosureSubscriptions.statesFor(member);
            for (blue.language.processor.closure.SubscriptionState state
                    : states) {
                if (state.graphGeneration() != result.graphGeneration()) {
                    throw new IllegalStateException(
                            label + " subscription state is graph-stale "
                                    + "for " + member);
                }
            }
        }
    }

    private void requireComponentStateFences(
            InMemoryDocumentStore.StoreState before) {
        Map<String, String> actual = new LinkedHashMap<>();
        for (ComponentSnapshot component : before.componentStates()) {
            actual.put(
                    component.componentIdentity(),
                    component.componentStateIdentity());
        }
        for (Map.Entry<String, String> expected
                : expectedComponentStates.entrySet()) {
            String found = actual.get(expected.getKey());
            if (!expected.getValue().equals(found)) {
                throw new AtomicPublicationCasException(
                        "Stale component state " + expected.getKey()
                                + ": expected " + expected.getValue()
                                + " but found " + found);
            }
        }
    }

    private static DocumentSession requireSession(
            InMemoryDocumentStore.StoreState state,
            DocumentId documentId) {
        DocumentSession session = state.sessions().get(documentId);
        if (session == null) {
            throw new AtomicPublicationCasException(
                    "Missing expected document head " + documentId);
        }
        return session;
    }

    private static void requireRevisionTransition(
            DocumentSession current,
            DocumentUpdate update) {
        DocumentRevision revision = update.revision();
        requireSafeInteger(revision.epoch(), "revision epoch");
        requireSafeInteger(
                revision.rootApplicationOrder(),
                "revision rootApplicationOrder");
        if (revision.kind() == DocumentRevision.Kind.INITIALIZATION) {
            throw new IllegalArgumentException(
                    "Existing document publication cannot stage initialization");
        }
        InMemoryDocumentStore.DocumentHead expected =
                new InMemoryDocumentStore.DocumentHead(
                        current.epoch(),
                        current.currentRevision().after().blueId());
        String beforeBlueId = revision.before()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Atomic publication revision requires before state"))
                .blueId();
        if (!expected.blueId().equals(beforeBlueId)) {
            throw new IllegalArgumentException(
                    "Revision before state does not equal durable head for "
                            + current.documentId());
        }
        if (revision.epoch() != Math.addExact(expected.epoch(), 1L)) {
            throw new IllegalArgumentException(
                    "Revision epoch is not the next durable epoch for "
                            + current.documentId());
        }
        if (!revision.after().blueId().equals(
                update.resultingLayout().rootBlueId())) {
            throw new IllegalArgumentException(
                    "Resulting layout does not identify revision after state for "
                            + current.documentId());
        }
    }

    private void requireGenerationTransitions(
            InMemoryDocumentStore.StoreState before,
            ManagedOccurrenceInventory resultingInventory,
            long resultingInventoryGeneration,
            long resultingIndexGeneration,
            Collection<DocumentId> resultingDocuments) {
        boolean inventoryChanged = !sameInventory(
                before.occurrenceInventory(), resultingInventory);
        long requiredInventoryGeneration = inventoryChanged
                ? InMemoryDocumentStore.increment(
                        before.occurrenceInventoryGeneration(),
                        "occurrence inventory generation")
                : before.occurrenceInventoryGeneration();
        if (resultingInventoryGeneration != requiredInventoryGeneration) {
            throw new IllegalArgumentException(
                    "Occurrence inventory generation must "
                            + (inventoryChanged ? "advance exactly once" : "remain unchanged"));
        }

        boolean topologyChanged = !sameComponentProjection(
                before.occurrenceInventory(),
                resultingInventory,
                before.componentIndex().documents(),
                resultingDocuments);
        long requiredIndexGeneration = topologyChanged
                ? InMemoryDocumentStore.increment(
                        before.componentIndexGeneration(),
                        "component index generation")
                : before.componentIndexGeneration();
        if (resultingIndexGeneration != requiredIndexGeneration) {
            throw new IllegalArgumentException(
                    "Component index generation must "
                            + (topologyChanged ? "advance exactly once" : "remain unchanged"));
        }
    }

    private List<ComponentSnapshot> mergeComponentStates(
            InMemoryDocumentStore.StoreState before,
            Map<DocumentId, DocumentSession> resultingSessions,
            ProcessEmbeddedComponentIndex resultingIndex) {
        ArrayList<ComponentSnapshot> staged = new ArrayList<>(
                stagedComponentStates);
        Set<String> stagedLineages = new LinkedHashSet<>();
        Set<String> stagedStates = new LinkedHashSet<>();
        Set<DocumentId> stagedDocuments = new LinkedHashSet<>();
        for (ComponentSnapshot component : staged) {
            if (!stagedLineages.add(component.componentIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate staged component lineage "
                                + component.componentIdentity());
            }
            if (!stagedStates.add(component.componentStateIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate staged component state "
                                + component.componentStateIdentity());
            }
            validateComponentState(
                    component, resultingSessions, resultingIndex);
            for (blue.language.processor.closure.DocumentId member
                    : component.orderedMemberDocumentIds()) {
                DocumentId documentId = DocumentId.of(member.value());
                if (!stagedDocuments.add(documentId)) {
                    throw new IllegalArgumentException(
                            "Staged component states overlap at " + documentId);
                }
                if (!expectedHeads.containsKey(documentId)
                        && !expectedAbsent.contains(documentId)) {
                    throw new IllegalStateException(
                            "Component state has no exact present/absent fence "
                                    + documentId);
                }
            }
        }
        for (DocumentId updated : documentUpdates.keySet()) {
            if (!stagedDocuments.contains(updated)) {
                throw new IllegalStateException(
                        "Updated document has no staged component state "
                                + updated);
            }
        }
        for (DocumentId admitted : newSessions.keySet()) {
            if (!stagedDocuments.contains(admitted)) {
                throw new IllegalStateException(
                        "Admitted document has no staged component state "
                                + admitted);
            }
        }

        LinkedHashMap<String, ComponentSnapshot> merged =
                new LinkedHashMap<>();
        ContractsStructuralWorkMetrics.recordGlobalPass(
                store.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
                before.componentStates().size());
        for (ComponentSnapshot existing : before.componentStates()) {
            if (!stagedLineages.contains(existing.componentIdentity())
                    && existing.orderedMemberDocumentIds().stream()
                            .map(member -> DocumentId.of(member.value()))
                            .noneMatch(stagedDocuments::contains)
                    && isCurrentComponentState(
                            existing, resultingSessions, resultingIndex)) {
                merged.put(existing.componentIdentity(), existing);
            }
        }
        for (ComponentSnapshot component : staged) {
            merged.put(component.componentIdentity(), component);
        }
        Map<List<DocumentId>, ComponentSnapshot> byMembers =
                new LinkedHashMap<>();
        ContractsStructuralWorkMetrics.recordGlobalPass(
                store.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
                merged.size());
        for (ComponentSnapshot component : merged.values()) {
            List<DocumentId> members = component.orderedMemberDocumentIds()
                    .stream()
                    .map(member -> DocumentId.of(member.value()))
                    .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                    .toList();
            if (byMembers.putIfAbsent(members, component) != null) {
                throw new IllegalStateException(
                        "More than one component state for members " + members);
            }
        }
        List<ComponentSnapshot> ordered = new ArrayList<>();
        ContractsStructuralWorkMetrics.recordGlobalPass(
                store.metrics(),
                ContractsStructuralWorkMetrics
                        .GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
                resultingIndex.components().size());
        for (ProcessEmbeddedComponentIndex.Component component
                : resultingIndex.components()) {
            ComponentSnapshot state = byMembers.remove(component.members());
            if (state != null) {
                ordered.add(state);
            }
        }
        if (!byMembers.isEmpty()) {
            throw new IllegalStateException(
                    "Component states are absent from the resulting graph: "
                            + byMembers.keySet());
        }
        return List.copyOf(ordered);
    }

    private static boolean isCurrentComponentState(
            ComponentSnapshot component,
            Map<DocumentId, DocumentSession> sessions,
            ProcessEmbeddedComponentIndex index) {
        try {
            validateComponentState(component, sessions, index);
            return true;
        } catch (RuntimeException stale) {
            return false;
        }
    }

    private static void validateComponentState(
            ComponentSnapshot component,
            Map<DocumentId, DocumentSession> sessions,
            ProcessEmbeddedComponentIndex index) {
        ArrayList<DocumentId> members = new ArrayList<>();
        for (blue.language.processor.closure.DocumentId member
                : component.orderedMemberDocumentIds()) {
            members.add(DocumentId.of(member.value()));
        }
        ProcessEmbeddedComponentIndex.Component indexed =
                index.component(members.get(0));
        if (!indexed.members().equals(members)) {
            throw new IllegalArgumentException(
                    "Component state does not match indexed membership "
                            + members);
        }
        ComponentKind expectedKind = indexed.cyclic()
                ? ComponentKind.CYCLIC : ComponentKind.ACYCLIC;
        if (component.kind() != expectedKind) {
            throw new IllegalArgumentException(
                    "Component state kind does not match indexed topology "
                            + members);
        }
        for (int indexPosition = 0;
                indexPosition < members.size();
                indexPosition++) {
            DocumentSession session = sessions.get(members.get(indexPosition));
            if (session == null) {
                throw new IllegalArgumentException(
                        "Component state names an unmanaged document "
                                + members.get(indexPosition));
            }
            String actualBlueId = session.currentRevision().after().blueId();
            if (!actualBlueId.equals(component.orderedMemberBlueIds().get(
                    indexPosition))) {
                throw new IllegalArgumentException(
                        "Component state has stale document head "
                                + members.get(indexPosition));
            }
        }
    }

    private static void requireContiguousPublicEventOrdinals(
            List<PublicEventOccurrence> events) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).publicEventOrdinal() != index) {
                throw new IllegalArgumentException(
                        "Public event ordinal gap at " + index);
            }
        }
    }

    private static void requireContiguousCheckpointOrdinals(
            List<CheckpointWrite> checkpoints) {
        for (int index = 0; index < checkpoints.size(); index++) {
            if (checkpoints.get(index).checkpointWriteOrdinal() != index) {
                throw new IllegalArgumentException(
                        "Checkpoint write ordinal gap at " + index);
            }
        }
    }

    private static boolean sameInventory(
            ManagedOccurrenceInventory first,
            ManagedOccurrenceInventory second) {
        return occurrenceRows(first.rows()).equals(
                occurrenceRows(second.rows()));
    }

    private static boolean sameComponentProjection(
            ManagedOccurrenceInventory first,
            ManagedOccurrenceInventory second,
            Collection<DocumentId> firstDocuments,
            Collection<DocumentId> secondDocuments) {
        Set<DocumentId> firstMembership = new LinkedHashSet<>(firstDocuments);
        firstMembership.addAll(first.documentIds());
        Set<DocumentId> secondMembership = new LinkedHashSet<>(secondDocuments);
        secondMembership.addAll(second.documentIds());
        return firstMembership.equals(secondMembership)
                && activeEdges(first.activeRows()).equals(
                        activeEdges(second.activeRows()));
    }

    private static List<OccurrenceRow> occurrenceRows(
            Collection<ManagedOccurrenceBinding> rows) {
        return rows.stream().map(OccurrenceRow::from).toList();
    }

    private static List<ActiveEdge> activeEdges(
            Collection<ManagedOccurrenceBinding> rows) {
        return rows.stream().map(row -> new ActiveEdge(
                row.occurrenceIdentity(),
                row.sourceDocumentId().value(),
                row.targetDocumentId().value())).toList();
    }

    private void ensureOpen() {
        if (attempted) {
            throw new IllegalStateException(
                    "Publication transaction is one-shot");
        }
    }

    static long requireSafeInteger(long value, String label) {
        if (value < 0L || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    label + " must be a portable non-negative safe integer");
        }
        return value;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static void requireSameClosureResult(
            ClosureProcessResult first,
            ClosureProcessResult second) {
        ClosureProcessResult left = Objects.requireNonNull(first, "first");
        ClosureProcessResult right = Objects.requireNonNull(second, "second");
        if (!left.invocationIdentity().equals(right.invocationIdentity())
                || !left.outputClosureIdentity().equals(
                        right.outputClosureIdentity())
                || left.graphGeneration() != right.graphGeneration()
                || !left.platformCommitCompanion().companionIdentity().equals(
                        right.platformCommitCompanion()
                                .companionIdentity())) {
            throw new IllegalArgumentException(
                    "Closure graph and subscription state came from "
                            + "different verified results");
        }
    }

    private record DocumentUpdate(
            DocumentRevision revision,
            EmbeddedOnlyLayout resultingLayout,
            ExternalOrderKey committedFrontier,
            List<SubscriptionDelta.Entry> resultingSubscriptions,
            String transitionReceipt) {
        private DocumentUpdate {
            revision = Objects.requireNonNull(revision, "revision");
            resultingLayout = Objects.requireNonNull(
                    resultingLayout, "resultingLayout");
            resultingSubscriptions = List.copyOf(Objects.requireNonNull(
                    resultingSubscriptions, "resultingSubscriptions"));
            transitionReceipt = requireText(
                    transitionReceipt, "transitionReceipt");
        }
    }

    private record OccurrenceRow(
            String sourceDocumentId,
            String sourcePath,
            long activationGeneration,
            String targetDocumentId,
            String expectedTargetBlueId,
            String bindingPolicyIdentity,
            String occurrenceIdentity,
            String bindingIdentity,
            boolean active,
            Long pendingHistoricalEpoch) {
        static OccurrenceRow from(ManagedOccurrenceBinding row) {
            return new OccurrenceRow(
                    row.sourceDocumentId().value(),
                    row.sourcePath(),
                    row.activationGeneration(),
                    row.targetDocumentId().value(),
                    row.expectedTargetBlueId(),
                    row.bindingPolicyIdentity(),
                    row.occurrenceIdentity(),
                    row.bindingIdentity(),
                    row.active(),
                    row.pendingHistoricalEpoch());
        }
    }

    private record ActiveEdge(
            String occurrenceIdentity,
            String sourceDocumentId,
            String targetDocumentId) {
    }

    static final class AtomicPublicationCasException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        AtomicPublicationCasException(String message) {
            super(message);
        }
    }
}
