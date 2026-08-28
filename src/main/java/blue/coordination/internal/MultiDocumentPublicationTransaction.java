package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ResultingDocument;
import blue.language.provider.CyclicSetProof;

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
    private final Map<DocumentId, Long> expectedGraphGenerations =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Set<DocumentId> expectedAbsent = new java.util.TreeSet<>(
            EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, DocumentUpdate> documentUpdates =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, ComponentRepresentationUpdate>
            componentRepresentationUpdates = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<DocumentId, DocumentSession> newSessions =
            new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
    private final Map<String, String> expectedComponentStates =
            new LinkedHashMap<>();
    private final List<ComponentSnapshot> stagedComponentStates =
            new ArrayList<>();
    private final Map<DocumentId,
            List<ClosureSubscriptionInventory.EmbeddedDemand>>
            stagedEmbeddedDemands = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
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
    private final List<ManagedReceiptStage> stagedManagedEpochReceipts =
            new ArrayList<>();
    private CatchUpPlanStore expectedCatchUpPlans;
    private CatchUpPlanStore stagedCatchUpPlans;
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

    /** Adds one exact per-lineage graph-generation CAS fence. */
    synchronized MultiDocumentPublicationTransaction expectGraphGeneration(
            DocumentId documentId,
            long expectedGeneration) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        if (expectedAbsent.contains(selected)) {
            throw new IllegalArgumentException(
                    "Document already has an expected-absent fence "
                            + selected);
        }
        long exact = requireSafeInteger(
                expectedGeneration, "expectedGraphGeneration");
        if (expectedGraphGenerations.putIfAbsent(selected, exact) != null) {
            throw new IllegalArgumentException(
                    "Duplicate graph-generation fence " + selected);
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
                || expectedGraphGenerations.containsKey(selected)
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
        return stageDocument(
                revision,
                resultingLayout,
                committedFrontier,
                resultingSubscriptions,
                false,
                transitionReceipt);
    }

    /** Stages a revision and its verified processor terminal disposition. */
    synchronized MultiDocumentPublicationTransaction stageDocument(
            DocumentRevision revision,
            EmbeddedOnlyLayout resultingLayout,
            ExternalOrderKey committedFrontier,
            List<SubscriptionDelta.Entry> resultingSubscriptions,
            boolean terminated,
            String transitionReceipt) {
        ensureOpen();
        DocumentUpdate update = new DocumentUpdate(
                revision,
                resultingLayout,
                committedFrontier,
                resultingSubscriptions,
                terminated,
                transitionReceipt);
        if (componentRepresentationUpdates.containsKey(
                        update.revision().documentId())
                || documentUpdates.putIfAbsent(
                update.revision().documentId(), update) != null) {
            throw new IllegalArgumentException(
                    "Duplicate staged document revision "
                            + update.revision().documentId());
        }
        return this;
    }

    /**
     * Stages one managed-application-only same-epoch component representation.
     * The exact Contracts result remains the authority for all proof fields.
     */
    synchronized MultiDocumentPublicationTransaction
            stageComponentRepresentationRebind(
                    ManagedEpochApplicationWork work,
                    ResultingDocument resultingDocument,
                    EmbeddedOnlyLayout resultingLayout,
                    List<SubscriptionDelta.Entry> resultingSubscriptions,
                    ManagedDocumentTransitionReceipt transitionReceipt) {
        ensureOpen();
        ComponentRepresentationUpdate update =
                ComponentRepresentationUpdate.forManagedApplication(
                        Objects.requireNonNull(work, "work"),
                        Objects.requireNonNull(
                                resultingDocument, "resultingDocument"),
                        Objects.requireNonNull(
                                resultingLayout, "resultingLayout"),
                        resultingSubscriptions,
                        Objects.requireNonNull(
                                transitionReceipt, "transitionReceipt"));
        DocumentId documentId = DocumentId.of(
                update.resultingDocument().documentId().value());
        if (!documentId.equals(update.work().sourceDocumentId())
                || update.work().sourceDocumentId().equals(
                        update.work().consumerDocumentId())) {
            throw new IllegalArgumentException(
                    "A component representation rebind must be the distinct "
                            + "managed-application source lineage");
        }
        if (!update.resultingLayout().rootBlueId().equals(
                update.resultingDocument().afterBlueId())) {
            throw new IllegalArgumentException(
                    "Component representation layout does not identify its "
                            + "Contracts result");
        }
        if (documentUpdates.containsKey(documentId)
                || newSessions.containsKey(documentId)
                || componentRepresentationUpdates.putIfAbsent(
                        documentId, update) != null) {
            throw new IllegalArgumentException(
                    "Duplicate staged component representation " + documentId);
        }
        return this;
    }

    /**
     * Stages an eventless same-epoch representation finalized indirectly by
     * an ordinary closure invocation. Directly delivered Roots are excluded:
     * their changes remain ordinary source revisions.
     */
    synchronized MultiDocumentPublicationTransaction
            stageIndirectComponentRepresentationRebind(
                    Collection<DocumentId> directTargetDocumentIds,
                    ResultingDocument resultingDocument,
                    EmbeddedOnlyLayout resultingLayout,
                    List<SubscriptionDelta.Entry> resultingSubscriptions,
                    ManagedDocumentTransitionReceipt transitionReceipt) {
        ensureOpen();
        ComponentRepresentationUpdate update =
                ComponentRepresentationUpdate.forIndirectClosureMember(
                        directTargetDocumentIds,
                        Objects.requireNonNull(
                                resultingDocument, "resultingDocument"),
                        Objects.requireNonNull(
                                resultingLayout, "resultingLayout"),
                        resultingSubscriptions,
                        Objects.requireNonNull(
                                transitionReceipt, "transitionReceipt"));
        DocumentId documentId = DocumentId.of(
                update.resultingDocument().documentId().value());
        if (update.directTargetDocumentIds().contains(documentId)) {
            throw new IllegalArgumentException(
                    "A directly delivered Root cannot use a component "
                            + "representation rebind");
        }
        if (!update.resultingLayout().rootBlueId().equals(
                update.resultingDocument().afterBlueId())) {
            throw new IllegalArgumentException(
                    "Component representation layout does not identify its "
                            + "Contracts result");
        }
        if (documentUpdates.containsKey(documentId)
                || newSessions.containsKey(documentId)
                || componentRepresentationUpdates.putIfAbsent(
                        documentId, update) != null) {
            throw new IllegalArgumentException(
                    "Duplicate staged component representation " + documentId);
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
                || componentRepresentationUpdates.containsKey(documentId)
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

    synchronized MultiDocumentPublicationTransaction stageEmbeddedDemands(
            DocumentId documentId,
            Collection<ClosureSubscriptionInventory.EmbeddedDemand> demands) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        List<ClosureSubscriptionInventory.EmbeddedDemand> canonical =
                List.copyOf(Objects.requireNonNull(demands, "demands"));
        if (stagedEmbeddedDemands.putIfAbsent(
                selected, canonical) != null) {
            throw new IllegalStateException(
                    "Embedded dependency surface is already staged for "
                            + selected);
        }
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

    /**
     * Stages one complete Coordination epoch receipt and its authenticated
     * Contracts transition input in the same copy-on-write publication.
     */
    synchronized MultiDocumentPublicationTransaction stageManagedEpochReceipt(
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        ensureOpen();
        ManagedReceiptStage selected = new ManagedReceiptStage(
                Objects.requireNonNull(receipt, "receipt"),
                Objects.requireNonNull(
                        transitionReceipt, "transitionReceipt"),
                null);
        stageManagedReceipt(selected);
        return this;
    }

    /**
     * Stages the consumer epoch required by one successful application whose
     * exact Contracts result contains no consumer transition. Contracts does
     * not manufacture a transition receipt for an unchanged, eventless Root;
     * the Coordination receipt is instead fenced to the exact application
     * work and verified committing result.
     */
    synchronized MultiDocumentPublicationTransaction
            stageManagedEpochApplicationReceipt(
                    ManagedEpochReceipt receipt,
                    ManagedEpochApplicationWork work) {
        ensureOpen();
        ManagedReceiptStage selected = new ManagedReceiptStage(
                Objects.requireNonNull(receipt, "receipt"),
                null,
                Objects.requireNonNull(work, "work"));
        stageManagedReceipt(selected);
        return this;
    }

    private void stageManagedReceipt(ManagedReceiptStage selected) {
        boolean duplicate = stagedManagedEpochReceipts.stream().anyMatch(
                existing -> existing.receipt().receiptIdentity().equals(
                        selected.receipt().receiptIdentity())
                        || existing.receipt().documentId().equals(
                                selected.receipt().documentId())
                        && existing.receipt().epoch()
                                == selected.receipt().epoch());
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Duplicate staged managed epoch receipt "
                            + selected.receipt().receiptIdentity());
        }
        stagedManagedEpochReceipts.add(selected);
    }

    /** Stages one exact immutable catch-up-index replacement behind a CAS. */
    synchronized MultiDocumentPublicationTransaction stageCatchUpPlans(
            CatchUpPlanStore expected,
            CatchUpPlanStore replacement) {
        ensureOpen();
        if (expectedCatchUpPlans != null) {
            throw new IllegalStateException(
                    "Catch-up plan state is already staged");
        }
        expectedCatchUpPlans = Objects.requireNonNull(expected, "expected");
        stagedCatchUpPlans = Objects.requireNonNull(
                replacement, "replacement");
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
        requireGraphGenerationFences(before);
        requireHeadFences(before);
        requireAbsentFences(before);
        requireComponentStateFences(before);
        requireClosurePublicationShape();
        if (expectedCatchUpPlans != null
                && before.catchUpPlans() != expectedCatchUpPlans) {
            throw new AtomicPublicationCasException(
                    "Catch-up plan state changed during publication");
        }
        if (before.hasPublicationReceipt(publicationIdentity)) {
            throw new IllegalStateException(
                    "Duplicate publication receipt " + publicationIdentity);
        }
        failureInjector.accept(FailurePoint.AFTER_CAS_CHECKS);

        CatchUpPlanStore publicationCatchUpPlans = stagedCatchUpPlans == null
                ? before.catchUpPlans() : stagedCatchUpPlans;
        ManagedOccurrenceInventory resultingInventory =
                stagedOccurrenceInventory == null
                        ? before.occurrenceInventory()
                        : stagedOccurrenceInventory;
        long resultingInventoryGeneration =
                stagedOccurrenceInventory == null
                        ? before.occurrenceInventoryGeneration()
                        : this.resultingOccurrenceInventoryGeneration;

        PersistentOrderedMap<DocumentId, DocumentSession> resultingSessionIndex =
                before.sessionIndex();
        long sessionIndexComparisons = 0L;
        long sessionIndexNodesCopied = 0L;
        ManagedLineageIndex resultingLineages = before.lineageIndex();
        ManagedEpochReceiptStore resultingManagedEpochReceipts =
                before.managedEpochReceipts();
        for (Map.Entry<DocumentId, DocumentSession> entry
                : newSessions.entrySet()) {
            if (!expectedAbsent.contains(entry.getKey())) {
                throw new IllegalStateException(
                        "Staged new session has no expected-absent fence "
                                + entry.getKey());
            }
            DocumentSession replacement =
                    entry.getValue().copyForAtomicPublication();
            replacement.restoreReadyEmbeddedChildren(
                    activeChildren(resultingInventory, entry.getKey()));
            if (publicationCatchUpPlans.hasActiveBarrierForConsumer(
                    entry.getKey())) {
                replacement.markCatchingUp();
            }
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
                if (update.terminated()) {
                    replacement.markTerminated(activeChildren(
                            resultingInventory, documentId));
                } else if (!publicationCatchUpPlans.hasActiveBarrierForConsumer(
                        documentId)) {
                    replacement.markReady(
                            update.committedFrontier(),
                            activeChildren(resultingInventory, documentId));
                }
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
        for (Map.Entry<DocumentId, ComponentRepresentationUpdate> entry
                : componentRepresentationUpdates.entrySet()) {
            DocumentId documentId = entry.getKey();
            ComponentRepresentationUpdate update = entry.getValue();
            if (!expectedHeads.containsKey(documentId)) {
                throw new IllegalStateException(
                        "Staged component representation has no exact head "
                                + "fence " + documentId);
            }
            DocumentSession current = requireSession(before, documentId);
            if (!current.activeSubscriptions().equals(
                    update.resultingSubscriptions())) {
                throw new IllegalStateException(
                        "A component representation rebind changed the managed "
                                + "Root route surface for " + documentId);
            }
            DocumentSession replacement =
                    current.copyForAtomicPublication();
            replacement.rebindComponentRepresentation(
                    update.resultingDocument().epoch(),
                    update.resultingLayout(),
                    update.resultingSubscriptions(),
                    update.transitionReceipt().transitionReceiptIdentity());
            replacement.markGraphPublished();
            if (!publicationCatchUpPlans.hasActiveBarrierForConsumer(
                    documentId)) {
                replacement.markReady(
                        current.readyThrough(),
                        activeChildren(resultingInventory, documentId));
            }
            PersistentOrderedMap.Mutation<DocumentId, DocumentSession>
                    mutation = resultingSessionIndex.put(
                            documentId, replacement);
            resultingSessionIndex = mutation.map();
            sessionIndexComparisons = Math.addExact(
                    sessionIndexComparisons, mutation.comparisons());
            sessionIndexNodesCopied = Math.addExact(
                    sessionIndexNodesCopied, mutation.copiedNodes());
            resultingLineages = resultingLineages
                    .withComponentRepresentationRebound(replacement);
            resultingManagedEpochReceipts = resultingManagedEpochReceipts
                    .withComponentRepresentationRebind(
                            documentId,
                            update.resultingDocument().epoch(),
                            update.resultingDocument().beforeBlueId(),
                            update.resultingDocument().afterBlueId(),
                            update.transitionReceipt());
        }
        if (expectedCatchUpPlans != null) {
            for (DocumentId documentId : expectedHeads.keySet()) {
                if (documentUpdates.containsKey(documentId)
                        || componentRepresentationUpdates.containsKey(
                                documentId)
                        || !expectedCatchUpPlans
                                .hasActiveBarrierForConsumer(documentId)
                        || publicationCatchUpPlans
                                .hasActiveBarrierForConsumer(documentId)) {
                    continue;
                }
                DocumentSession current = requireSession(before, documentId);
                if (current.status()
                        != blue.coordination.api.SessionStatus.CATCHING_UP) {
                    throw new IllegalStateException(
                            "A completed catch-up barrier belongs to a "
                                    + "non-catching-up session " + documentId);
                }
                DocumentSession replacement =
                        current.copyForAtomicPublication();
                replacement.markReady(
                        current.readyThrough(),
                        activeChildren(resultingInventory, documentId));
                PersistentOrderedMap.Mutation<DocumentId, DocumentSession>
                        mutation = resultingSessionIndex.put(
                                documentId, replacement);
                resultingSessionIndex = mutation.map();
                sessionIndexComparisons = Math.addExact(
                        sessionIndexComparisons, mutation.comparisons());
                sessionIndexNodesCopied = Math.addExact(
                        sessionIndexNodesCopied, mutation.copiedNodes());
            }
        }
        metrics.add("store.sessionIndexComparisons", sessionIndexComparisons);
        metrics.add("store.sessionIndexNodesCopied", sessionIndexNodesCopied);
        Map<DocumentId, DocumentSession> resultingSessions =
                new PersistentMapView<>(resultingSessionIndex);
        failureInjector.accept(FailurePoint.AFTER_DOCUMENTS_STAGED);

        Set<DocumentId> affectedDocuments = affectedDocuments();
        ProcessEmbeddedComponentIndex resultingIndex =
                stagedOccurrenceInventory == null
                        ? before.componentIndex()
                        : before.componentIndex().replaceForwardClosure(
                                affectedDocuments, resultingInventory);
        long resultingIndexGeneration =
                stagedOccurrenceInventory == null
                        ? before.componentIndexGeneration()
                        : this.resultingComponentIndexGeneration;
        requireGenerationTransitions(
                before,
                resultingInventory,
                resultingInventoryGeneration,
                resultingIndexGeneration,
                affectedDocuments);

        ComponentStateInventory resultingComponents = mergeComponentStates(
                before,
                resultingSessions,
                resultingIndex,
                affectedDocuments);
        ClosureGraphGenerationInventory resultingGraphGenerations =
                stagedGraphGeneration == null
                        ? before.graphGenerations()
                        : stagedAdmissionResult
                        ? stagedAdmissionInput != null
                                && !expectedHeads.isEmpty()
                                ? before.graphGenerations().applyExpansion(
                                        stagedGraphGeneration,
                                        expectedGraphGenerations,
                                        expectedAbsent)
                                : before.graphGenerations().admit(
                                        stagedGraphGeneration, expectedAbsent)
                        : stagedManagedExpansionInput != null
                        ? before.graphGenerations().applyExpansion(
                                stagedGraphGeneration,
                                expectedGraphGenerations,
                                expectedAbsent)
                        : before.graphGenerations().apply(
                                stagedGraphGeneration,
                                expectedGraphGenerations);
        ClosureSubscriptionInventory resultingClosureSubscriptions =
                applyClosureSubscriptions(before);
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
        for (ManagedReceiptStage stage : stagedManagedEpochReceipts) {
            resultingManagedEpochReceipts = resultingManagedEpochReceipts
                    .withReceipt(stage.receipt(), stage.transitionReceipt());
        }
        requireManagedEpochReceiptPublication(
                resultingSessions, resultingManagedEpochReceipts);
        CatchUpPlanStore resultingCatchUpPlans = publicationCatchUpPlans;
        requireClosurePublicationResult(
                resultingSessions,
                resultingInventory,
                resultingIndex,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions);
        requireAdmissionPublicationResult(
                resultingSessions,
                resultingInventory,
                resultingIndex,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions);
        failureInjector.accept(FailurePoint.AFTER_TOPOLOGY_STAGED);

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
                        resultingClosurePublicationReceipts,
                        resultingManagedEpochReceipts,
                        resultingCatchUpPlans);
        failureInjector.accept(FailurePoint.BEFORE_SWAP);
        return replacement;
    }

    private void requireManagedEpochReceiptPublication(
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedEpochReceiptStore resultingReceipts) {
        for (ManagedReceiptStage stage : stagedManagedEpochReceipts) {
            ManagedEpochReceipt receipt = stage.receipt();
            DocumentSession session = resultingSessions.get(
                    receipt.documentId());
            if (session == null) {
                throw new IllegalStateException(
                        "Managed epoch receipt belongs to an absent document "
                                + receipt.documentId());
            }
            DocumentRevision revision = session.revision(receipt.epoch());
            if (!revision.managedEpochReceipt()
                            .map(ManagedEpochReceipt::receiptIdentity)
                            .filter(receipt.receiptIdentity()::equals)
                            .isPresent()
                    || !resultingReceipts.exact(
                                    receipt.documentId(), receipt.epoch())
                            .found()) {
                throw new IllegalStateException(
                        "Managed epoch receipt and revision were not published "
                                + "atomically for " + receipt.documentId()
                                + " epoch " + receipt.epoch());
            }
        }
    }

    private ClosureSubscriptionInventory applyClosureSubscriptions(
            InMemoryDocumentStore.StoreState before) {
        ClosureSubscriptionInventory resulting =
                stagedClosureSubscriptions == null
                        ? before.closureSubscriptions()
                        : before.closureSubscriptions().apply(
                                stagedClosureSubscriptions,
                                expectedGraphGenerations);
        for (Map.Entry<DocumentId,
                List<ClosureSubscriptionInventory.EmbeddedDemand>> entry
                : stagedEmbeddedDemands.entrySet()) {
            resulting = resulting.replaceEmbeddedDemands(
                    entry.getKey(), entry.getValue());
        }
        return resulting;
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

    private void requireGraphGenerationFences(
            InMemoryDocumentStore.StoreState before) {
        if (!expectedHeads.keySet().containsAll(
                expectedGraphGenerations.keySet())) {
            throw new IllegalStateException(
                    "Every graph-generation fence requires an exact head "
                            + "fence");
        }
        if (stagedGraphGeneration != null
                && !expectedHeads.keySet().equals(
                        expectedGraphGenerations.keySet())) {
            throw new IllegalStateException(
                    "A closure publication must fence every existing member's "
                            + "exact graph generation");
        }
        for (Map.Entry<DocumentId, Long> entry
                : expectedGraphGenerations.entrySet()) {
            long actual = before.graphGenerations().require(entry.getKey());
            if (actual != entry.getValue().longValue()) {
                throw new AtomicPublicationCasException(
                        "Stale graph generation for " + entry.getKey()
                                + ": expected " + entry.getValue()
                                + " but found " + actual);
            }
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
                            session.currentRepresentation().blueId());
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
        if (!documentUpdates.isEmpty()
                || !componentRepresentationUpdates.isEmpty()) {
            throw new IllegalStateException(
                    "Static admission cannot change existing document heads");
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
            if (!componentRepresentationUpdates.isEmpty()) {
                throw new IllegalStateException(
                        "A component representation rebind requires one exact "
                                + "Contracts process receipt");
            }
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
            boolean representationRebound = result.commits()
                    && after.epoch() == before.epoch()
                    && !after.afterBlueId().equals(before.blueId())
                    && stagesVerifiedComponentRepresentationRebind(
                            result, entry.getKey(), before, after);
            boolean resultUnchanged = after.epoch() == before.epoch()
                    && after.afterBlueId().equals(before.blueId());
            boolean sameStateAdvanced = result.commits()
                    && resultUnchanged
                    && stagesReceiptBackedSameStateAdvance(
                            result, entry.getKey(), before);
            boolean checkpointSettlementAdvanced = result.commits()
                    && after.epoch() == before.epoch()
                    && !after.afterBlueId().equals(before.blueId())
                    && stagesVerifiedCheckpointSettlementAdvance(
                            result, entry.getKey(), before, after);
            boolean unchanged = resultUnchanged && !sameStateAdvanced;
            boolean advanced = sameStateAdvanced
                    || checkpointSettlementAdvanced
                    || after.epoch() == Math.addExact(before.epoch(), 1L)
                            && documentUpdates.containsKey(entry.getKey())
                            && documentUpdates.get(entry.getKey())
                                    .revision().after().blueId().equals(
                                            after.afterBlueId());
            if (result.commits()
                    ? !unchanged && !representationRebound && !advanced
                    : !unchanged) {
                throw new IllegalStateException(
                        "Process receipt result epoch/head transition is not "
                                + "fully staged for " + entry.getKey());
            }
            if (unchanged && (documentUpdates.containsKey(entry.getKey())
                    || componentRepresentationUpdates.containsKey(
                            entry.getKey()))) {
                throw new IllegalStateException(
                        "An unchanged process result staged a document change "
                                + entry.getKey());
            }
            if (representationRebound
                    && documentUpdates.containsKey(entry.getKey())) {
                throw new IllegalStateException(
                        "A component representation rebind staged a source "
                                + "revision " + entry.getKey());
            }
            if (advanced && documentUpdates.get(entry.getKey()).terminated()
                    != after.terminated()) {
                throw new IllegalStateException(
                        "Process receipt terminal state differs from its "
                                + "staged document revision " + entry.getKey());
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
                || !componentRepresentationUpdates.isEmpty()
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

    private boolean stagesVerifiedCheckpointSettlementAdvance(
            ClosureProcessResult result,
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before,
            ResultingDocument after) {
        DocumentUpdate update = documentUpdates.get(documentId);
        boolean supportedKind = update != null
                && (update.revision().kind()
                            == DocumentRevision.Kind.TIMELINE_ENTRY
                        || update.revision().kind()
                            == DocumentRevision.Kind
                                    .EMBEDDED_REVISION_APPLICATION);
        if (!supportedKind
                || update.revision().epoch()
                        != Math.addExact(before.epoch(), 1L)
                || !update.revision().before().map(value -> value.blueId()
                        .equals(before.blueId())).orElse(false)
                || !update.revision().after().blueId().equals(
                        after.afterBlueId())) {
            return false;
        }
        return stagedManagedEpochReceipts.stream().anyMatch(stage ->
                stage.transitionReceipt() != null
                        && stage.receipt().documentId().equals(documentId)
                        && stage.receipt().kind()
                                == update.revision().kind()
                        && ContractsClosureAdapter
                                .isVerifiedCheckpointSettlementChange(
                                        result,
                                        documentId,
                                        before,
                                        after,
                                        stage.transitionReceipt()));
    }

    /**
     * Recognizes the sole Contracts exception which may change a durable head
     * identity without advancing the document's own source epoch. The source
     * Root is not executed here: Contracts only finalizes its representation
     * as a member of the resulting component.
     */
    private boolean stagesVerifiedComponentRepresentationRebind(
            ClosureProcessResult result,
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before,
            ResultingDocument after) {
        ComponentRepresentationUpdate update =
                componentRepresentationUpdates.get(documentId);
        boolean managedApplication = update != null
                && update.work() != null
                && publicationIdentity.equals(update.work().workIdentity())
                && documentId.equals(update.work().sourceDocumentId())
                && !update.work().sourceDocumentId().equals(
                        update.work().consumerDocumentId())
                && update.work().sourceEpoch() == before.epoch()
                && after.epoch() == update.work().sourceEpoch();
        boolean indirectClosureMember = update != null
                && update.work() == null
                && !update.directTargetDocumentIds().isEmpty()
                && expectedHeads.keySet().containsAll(
                        update.directTargetDocumentIds())
                && !update.directTargetDocumentIds().contains(documentId)
                && after.epoch() == before.epoch();
        if (update == null
                || !managedApplication && !indirectClosureMember
                || !after.initialized()
                || after.terminated()
                || !sameResultingDocument(
                        update.resultingDocument(), after)
                || !update.resultingLayout().semanticRoot().sameExactValue(
                        ExactValue.fromVerifiedClosureResult(
                                result, documentId))) {
            return false;
        }

        ManagedDocumentTransitionReceipt transition =
                update.transitionReceipt();
        if (!transition.documentId().value().equals(documentId.value())
                || !transition.sourceInvocationIdentity().equals(
                        result.invocationIdentity())
                || !transition.beforeBlueId().equals(before.blueId())
                || !transition.afterBlueId().equals(after.afterBlueId())
                || !transition.emittedRootEvents().isEmpty()
                || result.platformCommitCompanion() == null
                || !result.platformCommitCompanion()
                        .bindsManagedTransitionReceipts()
                || result.managedTransitionReceipts().stream().noneMatch(
                        candidate -> candidate.transitionReceiptIdentity()
                                .equals(transition
                                        .transitionReceiptIdentity()))) {
            return false;
        }

        List<ComponentSnapshot> components = result.resultingComponents()
                .stream()
                .filter(component -> component.orderedMemberDocumentIds()
                        .stream().anyMatch(member -> member.value().equals(
                                documentId.value())))
                .toList();
        if (components.size() != 1) {
            return false;
        }
        ComponentSnapshot component = components.get(0);
        int memberIndex = -1;
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size(); index++) {
            if (component.orderedMemberDocumentIds().get(index).value().equals(
                    documentId.value())) {
                memberIndex = index;
                break;
            }
        }
        return memberIndex >= 0
                && component.componentGeneration()
                        == after.componentGeneration()
                && component.componentIdentity().equals(
                        after.componentIdentity())
                && component.componentStateIdentity().equals(
                        after.componentStateIdentity())
                && component.orderedMemberBlueIds().get(memberIndex).equals(
                        after.afterBlueId());
    }

    private static boolean sameResultingDocument(
            ResultingDocument left,
            ResultingDocument right) {
        return left.documentId().equals(right.documentId())
                && left.beforeBlueId().equals(right.beforeBlueId())
                && left.afterBlueId().equals(right.afterBlueId())
                && left.initialized() == right.initialized()
                && left.terminated() == right.terminated()
                && left.publicRoot() == right.publicRoot()
                && left.epoch() == right.epoch()
                && left.componentGeneration() == right.componentGeneration()
                && left.componentIdentity().equals(right.componentIdentity())
                && left.componentStateIdentity().equals(
                        right.componentStateIdentity())
                && Objects.equals(left.memberIndex(), right.memberIndex());
    }

    /**
     * Contracts keeps its state epoch stable when a Root emits events without
     * changing its exact value. Coordination still records one contiguous
     * receipt-backed epoch: EVENT_ONLY for direct processing or
     * EMBEDDED_REVISION_APPLICATION for retained catch-up.
     */
    private boolean stagesReceiptBackedSameStateAdvance(
            ClosureProcessResult result,
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before) {
        DocumentUpdate update = documentUpdates.get(documentId);
        if (update == null) {
            return false;
        }
        DocumentRevision revision = update.revision();
        boolean supportedKind = revision.kind()
                == DocumentRevision.Kind.EVENT_ONLY
                || revision.kind()
                        == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION;
        if (!supportedKind
                || revision.epoch() != Math.addExact(before.epoch(), 1L)
                || !revision.before().map(value -> value.blueId()
                        .equals(before.blueId())).orElse(false)
                || !revision.after().blueId().equals(before.blueId())) {
            return false;
        }
        String retainedIdentity = revision.managedEpochReceipt()
                .map(ManagedEpochReceipt::receiptIdentity)
                .orElse(null);
        if (retainedIdentity == null) {
            return false;
        }
        return stagedManagedEpochReceipts.stream().anyMatch(stage -> {
            ManagedEpochReceipt receipt = stage.receipt();
            if (!receipt.documentId().equals(documentId)
                    || receipt.kind() != revision.kind()
                    || !receipt.receiptIdentity().equals(retainedIdentity)) {
                return false;
            }
            ManagedDocumentTransitionReceipt transition =
                    stage.transitionReceipt();
            if (transition != null) {
                return transition.beforeBlueId().equals(before.blueId())
                        && transition.afterBlueId().equals(before.blueId());
            }
            return stagesVerifiedEventlessApplicationAdvance(
                    result, documentId, before, revision, receipt, stage.work());
        });
    }

    /** Reflection-only compatibility seam retained for focused validators. */
    @SuppressWarnings("unused")
    private boolean stagesReceiptBackedSameStateAdvance(
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before) {
        return stagesReceiptBackedSameStateAdvance(null, documentId, before);
    }

    private boolean stagesVerifiedEventlessApplicationAdvance(
            ClosureProcessResult result,
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before,
            DocumentRevision revision,
            ManagedEpochReceipt receipt,
            ManagedEpochApplicationWork work) {
        if (work == null
                || result == null
                || !publicationIdentity.equals(work.workIdentity())
                || !documentId.equals(work.consumerDocumentId())
                || work.expectedConsumerCommittedEpoch() != before.epoch()
                || !work.expectedConsumerCommittedBlueId().equals(
                        before.blueId())
                || !receipt.originalCauseIdentity().equals(
                        work.workIdentity())
                || !receipt.beforeBlueId().filter(
                        before.blueId()::equals).isPresent()
                || !receipt.afterBlueId().equals(before.blueId())
                || !receipt.emittedEvents().isEmpty()
                || !revision.emittedEvents().isEmpty()
                || receipt.processingGas() != result.totalGas()
                || result.platformCommitCompanion() == null
                || !receipt.commitCompanionIdentity().equals(
                        result.platformCommitCompanion()
                                .companionIdentity())
                || !receipt.afterBlueId().equals(
                        ExactValue.fromVerifiedClosureResult(
                                result, documentId).blueId())) {
            return false;
        }
        CyclicSetProof afterCyclicProof = ManagedEpochReceiptMapper
                .resultingCyclicProof(
                        result, documentId, receipt.afterBlueId());
        ManagedRevisionCause sourceEvidence = ClosureEvidenceFactory
                .managedRevisionCause(
                        work.targetOccurrenceIdentity(),
                        ContractsClosureAdapter.closureId(documentId),
                        before.epoch(),
                        revision.epoch(),
                        before.blueId(),
                        before.blueId(),
                        receipt.afterDocument().copyNode(),
                        work.workIdentity(),
                        afterCyclicProof);
        return receipt.contractsTransitionReceiptIdentity().equals(
                sourceEvidence.sourceRevisionReceiptIdentity())
                && result.managedTransitionReceipts().stream().noneMatch(
                        transition -> transition.documentId().value().equals(
                                documentId.value()));
    }

    private void requireClosurePublicationResult(
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedOccurrenceInventory resultingInventory,
            ProcessEmbeddedComponentIndex resultingIndex,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            ComponentStateInventory resultingComponents,
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
                resultingIndex,
                resultingGraphGenerations,
                resultingComponents,
                resultingClosureSubscriptions,
                "Process receipt",
                store.metrics());
    }

    private void requireAdmissionPublicationResult(
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedOccurrenceInventory resultingInventory,
            ProcessEmbeddedComponentIndex resultingIndex,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            ComponentStateInventory resultingComponents,
            ClosureSubscriptionInventory resultingClosureSubscriptions) {
        if (stagedAdmissionReceipt == null) {
            return;
        }
        requireExactResultState(
                stagedAdmissionReceipt.attempt().processResult(),
                new LinkedHashSet<>(stagedAdmissionReceipt.documentIds()),
                resultingSessions,
                resultingInventory,
                resultingIndex,
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
            ProcessEmbeddedComponentIndex resultingIndex,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            ComponentStateInventory resultingComponents,
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
        List<OccurrenceRow> durableRows = members.stream()
                .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .flatMap(member -> resultingInventory.rowsFrom(member).stream())
                .sorted()
                .map(OccurrenceRow::from)
                .toList();
        // Coordination retains an inactive, non-pending source/path
        // reservation after Contracts stops projecting that absent path. It
        // is immutable cursor evidence, not an active Contracts result row.
        // Every result row must still match exactly, and no active or pending
        // durable row may exist outside the authenticated result.
        List<OccurrenceRow> actualRows = durableRows.stream()
                .filter(expectedRows::contains)
                .toList();
        boolean invalidExtra = durableRows.stream()
                .filter(row -> !expectedRows.contains(row))
                .anyMatch(row -> row.active()
                        || row.pendingHistoricalEpoch() != null);
        if (invalidExtra || !expectedRows.equals(actualRows)) {
            throw new IllegalStateException(
                    label + " occurrence state is not the exact result");
        }

        List<String> expectedComponents = result.resultingComponents().stream()
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
        List<String> actualComponents = resultingComponents
                .statesFor(members, resultingIndex)
                .stream()
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
        if (!expectedComponents.equals(actualComponents)) {
            throw new IllegalStateException(
                    label + " component state is not the exact result");
        }

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
        for (Map.Entry<String, String> expected
                : expectedComponentStates.entrySet()) {
            ComponentSnapshot actual = before.componentState(
                    expected.getKey());
            String found = actual == null
                    ? null : actual.componentStateIdentity();
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
                        current.currentRepresentation().blueId());
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
            Collection<DocumentId> affectedSources) {
        boolean inventoryChanged = affectedSources.stream().anyMatch(source ->
                !occurrenceRows(before.occurrenceInventory().rowsFrom(source))
                        .equals(occurrenceRows(
                                resultingInventory.rowsFrom(source))));
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

        boolean topologyChanged = !newSessions.isEmpty()
                || affectedSources.stream().anyMatch(source ->
                        !activeEdges(before.occurrenceInventory()
                                        .activeRowsFrom(source))
                                .equals(activeEdges(resultingInventory
                                        .activeRowsFrom(source))));
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

    private Set<DocumentId> affectedDocuments() {
        java.util.TreeSet<DocumentId> affected = new java.util.TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        affected.addAll(expectedHeads.keySet());
        affected.addAll(expectedAbsent);
        return java.util.Collections.unmodifiableSet(affected);
    }

    private ComponentStateInventory mergeComponentStates(
            InMemoryDocumentStore.StoreState before,
            Map<DocumentId, DocumentSession> resultingSessions,
            ProcessEmbeddedComponentIndex resultingIndex,
            Set<DocumentId> affectedDocuments) {
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
        for (DocumentId rebound : componentRepresentationUpdates.keySet()) {
            if (!stagedDocuments.contains(rebound)) {
                throw new IllegalStateException(
                        "Rebound component representation has no staged "
                                + "component state " + rebound);
            }
        }
        for (DocumentId admitted : newSessions.keySet()) {
            if (!stagedDocuments.contains(admitted)) {
                throw new IllegalStateException(
                        "Admitted document has no staged component state "
                                + admitted);
            }
        }
        return before.componentStateInventory().replaceAffected(
                stagedDocuments, staged);
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
            String actualBlueId = session.currentRepresentation().blueId();
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

    private static List<OccurrenceRow> occurrenceRows(
            Collection<ManagedOccurrenceBinding> rows) {
        return rows.stream().map(OccurrenceRow::from).toList();
    }

    private static Map<String, DocumentId> activeChildren(
            ManagedOccurrenceInventory inventory,
            DocumentId parent) {
        LinkedHashMap<String, DocumentId> children = new LinkedHashMap<>();
        for (ManagedOccurrenceBinding row : Objects.requireNonNull(
                inventory, "inventory").activeRowsFrom(
                        Objects.requireNonNull(parent, "parent"))) {
            DocumentId child = DocumentId.of(row.targetDocumentId().value());
            DocumentId duplicate = children.putIfAbsent(
                    row.sourcePath(), child);
            if (duplicate != null && !duplicate.equals(child)) {
                throw new IllegalStateException(
                        "Active closure occurrences disagree at "
                                + parent + row.sourcePath());
            }
        }
        return children;
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
            boolean terminated,
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

    private record ComponentRepresentationUpdate(
            ManagedEpochApplicationWork work,
            List<DocumentId> directTargetDocumentIds,
            ResultingDocument resultingDocument,
            EmbeddedOnlyLayout resultingLayout,
            List<SubscriptionDelta.Entry> resultingSubscriptions,
            ManagedDocumentTransitionReceipt transitionReceipt) {
        private ComponentRepresentationUpdate {
            directTargetDocumentIds = List.copyOf(Objects.requireNonNull(
                    directTargetDocumentIds, "directTargetDocumentIds"));
            if ((work == null) == directTargetDocumentIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "A component representation rebind requires exactly "
                                + "one managed or indirect authority");
            }
            resultingDocument = Objects.requireNonNull(
                    resultingDocument, "resultingDocument");
            resultingLayout = Objects.requireNonNull(
                    resultingLayout, "resultingLayout");
            resultingSubscriptions = List.copyOf(Objects.requireNonNull(
                    resultingSubscriptions, "resultingSubscriptions"));
            transitionReceipt = Objects.requireNonNull(
                    transitionReceipt, "transitionReceipt");
        }

        private static ComponentRepresentationUpdate forManagedApplication(
                ManagedEpochApplicationWork work,
                ResultingDocument resultingDocument,
                EmbeddedOnlyLayout resultingLayout,
                List<SubscriptionDelta.Entry> resultingSubscriptions,
                ManagedDocumentTransitionReceipt transitionReceipt) {
            return new ComponentRepresentationUpdate(
                    Objects.requireNonNull(work, "work"),
                    List.of(),
                    resultingDocument,
                    resultingLayout,
                    resultingSubscriptions,
                    transitionReceipt);
        }

        private static ComponentRepresentationUpdate forIndirectClosureMember(
                Collection<DocumentId> directTargetDocumentIds,
                ResultingDocument resultingDocument,
                EmbeddedOnlyLayout resultingLayout,
                List<SubscriptionDelta.Entry> resultingSubscriptions,
                ManagedDocumentTransitionReceipt transitionReceipt) {
            TreeMap<DocumentId, Boolean> canonical = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
            for (DocumentId documentId : Objects.requireNonNull(
                    directTargetDocumentIds, "directTargetDocumentIds")) {
                canonical.put(
                        Objects.requireNonNull(documentId, "directTarget"),
                        Boolean.TRUE);
            }
            if (canonical.isEmpty()) {
                throw new IllegalArgumentException(
                        "An indirect component representation rebind requires "
                                + "at least one direct Root");
            }
            return new ComponentRepresentationUpdate(
                    null,
                    List.copyOf(canonical.keySet()),
                    resultingDocument,
                    resultingLayout,
                    resultingSubscriptions,
                    transitionReceipt);
        }
    }

    private record ManagedReceiptStage(
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transitionReceipt,
            ManagedEpochApplicationWork work) {
        private ManagedReceiptStage {
            receipt = Objects.requireNonNull(receipt, "receipt");
            if ((transitionReceipt == null) == (work == null)) {
                throw new IllegalArgumentException(
                        "Managed receipt requires exactly one Contracts "
                                + "transition or application work binding");
            }
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
