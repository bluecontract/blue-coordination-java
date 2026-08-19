package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.CheckpointWrite;
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
        requireComponentStateFences(before);
        requireClosurePublicationShape();
        if (before.publicationReceipts().contains(publicationIdentity)) {
            throw new IllegalStateException(
                    "Duplicate publication receipt " + publicationIdentity);
        }
        failureInjector.accept(FailurePoint.AFTER_CAS_CHECKS);

        LinkedHashMap<DocumentId, DocumentSession> resultingSessions =
                new LinkedHashMap<>(before.sessions());
        for (Map.Entry<DocumentId, DocumentSession> entry
                : newSessions.entrySet()) {
            if (!expectedAbsent.contains(entry.getKey())) {
                throw new IllegalStateException(
                        "Staged new session has no expected-absent fence "
                                + entry.getKey());
            }
            resultingSessions.put(
                    entry.getKey(),
                    entry.getValue().copyForAtomicPublication());
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
            resultingSessions.put(documentId, replacement);
        }
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
                        : InMemoryDocumentStore.componentIndex(
                                resultingSessions.values(),
                                resultingInventory);
        long resultingIndexGeneration =
                stagedOccurrenceInventory == null
                        ? before.componentIndexGeneration()
                        : this.resultingComponentIndexGeneration;
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
        ClosureGraphGenerationInventory resultingGraphGenerations =
                stagedGraphGeneration == null
                        ? before.graphGenerations()
                        : stagedAdmissionResult
                        ? before.graphGenerations().admit(
                                stagedGraphGeneration, expectedAbsent)
                        : before.graphGenerations().apply(stagedGraphGeneration);
        ClosureSubscriptionInventory resultingClosureSubscriptions =
                stagedClosureSubscriptions == null
                        ? before.closureSubscriptions()
                        : before.closureSubscriptions().apply(
                                stagedClosureSubscriptions);
        List<PublicEventOccurrence> resultingOutbox = new ArrayList<>(
                before.outbox());
        requireContiguousPublicEventOrdinals(stagedOutbox);
        resultingOutbox.addAll(stagedOutbox);
        List<CheckpointWrite> resultingCheckpoints = new ArrayList<>(
                before.checkpointEvidence());
        requireContiguousCheckpointOrdinals(stagedCheckpointEvidence);
        resultingCheckpoints.addAll(stagedCheckpointEvidence);
        LinkedHashSet<String> resultingReceipts = new LinkedHashSet<>(
                before.publicationReceipts());
        resultingReceipts.add(publicationIdentity);
        LinkedHashMap<String, ContractsClosureAdmissionReceipt>
                resultingAdmissionReceipts = new LinkedHashMap<>(
                        before.admissionReceipts());
        if (stagedAdmissionReceipt != null) {
            resultingAdmissionReceipts.put(
                    publicationIdentity, stagedAdmissionReceipt);
        }
        LinkedHashMap<String, ContractsClosurePublicationReceipt>
                resultingClosurePublicationReceipts = new LinkedHashMap<>(
                        before.closurePublicationReceipts());
        if (stagedClosurePublicationReceipt != null) {
            resultingClosurePublicationReceipts.put(
                    publicationIdentity, stagedClosurePublicationReceipt);
        }
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

        InMemoryDocumentStore.StoreState replacement =
                new InMemoryDocumentStore.StoreState(
                        resultingSessions,
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
        if (!expectedHeads.isEmpty() || !documentUpdates.isEmpty()) {
            throw new IllegalStateException(
                    "Mixed existing/new closure admission is not supported; "
                            + "all admitted lineages must be absent");
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
        if (!resultDocuments.equals(expectedAbsent)
                || !companionDocuments.equals(expectedAbsent)
                || !new LinkedHashSet<>(
                        stagedAdmissionReceipt.documentIds())
                        .equals(expectedAbsent)
                || !stagedAdmissionReceipt.attempt().isComplete()
                || stagedAdmissionReceipt.attempt().processResult()
                        != stagedGraphGeneration) {
            throw new IllegalStateException(
                    "Admission result, companion, sessions, and receipt name "
                            + "different document sets or results");
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

    private void requireClosurePublicationShape() {
        ContractsClosurePublicationReceipt receipt =
                stagedClosurePublicationReceipt;
        if (receipt == null) {
            return;
        }
        if (stagedAdmissionResult || stagedAdmissionReceipt != null
                || !expectedAbsent.isEmpty() || !newSessions.isEmpty()) {
            throw new IllegalStateException(
                    "A process receipt cannot publish an admission");
        }
        Set<DocumentId> members = new LinkedHashSet<>(receipt.documentIds());
        if (!expectedHeads.keySet().equals(members)) {
            throw new IllegalStateException(
                    "A process receipt requires exact head fences for its "
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
                "Process receipt");
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
                "Admission receipt");
    }

    private static void requireExactResultState(
            ClosureProcessResult result,
            Set<DocumentId> members,
            Map<DocumentId, DocumentSession> resultingSessions,
            ManagedOccurrenceInventory resultingInventory,
            ClosureGraphGenerationInventory resultingGraphGenerations,
            List<ComponentSnapshot> resultingComponents,
            ClosureSubscriptionInventory resultingClosureSubscriptions,
            String label) {
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
        List<OccurrenceRow> actualRows = resultingInventory.rows().stream()
                .filter(row -> members.contains(DocumentId.of(
                        row.sourceDocumentId().value()))
                        || members.contains(DocumentId.of(
                                row.targetDocumentId().value())))
                .map(OccurrenceRow::from)
                .toList();
        if (!expectedRows.equals(actualRows)) {
            throw new IllegalStateException(
                    label + " occurrence state is not the exact result");
        }

        List<String> expectedComponents = result.resultingComponents().stream()
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
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
