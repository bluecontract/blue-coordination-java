package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
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

    private final EngineMetrics metrics;
    private StoreState state;

    InMemoryDocumentStore() {
        this(new EngineMetrics());
    }

    InMemoryDocumentStore(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
                state.componentIndex());
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
                    session.currentRevision().after().blueId()));
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

    /** Immutable durable publication image; exactly one instance is swapped. */
    static final class StoreState {
        private final Map<DocumentId, DocumentSession> sessions;
        private final ManagedLineageIndex lineageIndex;
        private final ManagedOccurrenceInventory occurrenceInventory;
        private final long occurrenceInventoryGeneration;
        private final ProcessEmbeddedComponentIndex componentIndex;
        private final long componentIndexGeneration;
        private final ClosureGraphGenerationInventory graphGenerations;
        private final List<ComponentSnapshot> componentStates;
        private final Map<DocumentId, ComponentSnapshot>
                componentStateByDocument;
        private final Map<String, Integer> componentStateOrder;
        private final ClosureSubscriptionInventory closureSubscriptions;
        private final List<PublicEventOccurrence> outbox;
        private final List<CheckpointWrite> checkpointEvidence;
        private final Set<String> publicationReceipts;
        private final Map<String, ContractsClosureAdmissionReceipt>
                admissionReceipts;
        private final Map<String, ContractsClosurePublicationReceipt>
                closurePublicationReceipts;

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
                        closurePublicationReceipts) {
            this.sessions = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            sessions, "sessions")));
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
            this.componentStates = List.copyOf(canonicalComponents);
            this.componentStateByDocument = Collections.unmodifiableMap(
                    byDocument);
            this.componentStateOrder = Collections.unmodifiableMap(
                    orderByIdentity);
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
                if (!session.currentRevision().after().blueId()
                        .equals(state.documentBlueId())) {
                    throw new IllegalArgumentException(
                            "Closure subscription does not identify the durable "
                                    + "document head " + owner);
                }
                ComponentSnapshot component = componentStateByDocument.get(
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
            this.outbox = List.copyOf(Objects.requireNonNull(
                    outbox, "outbox"));
            this.checkpointEvidence = List.copyOf(Objects.requireNonNull(
                    checkpointEvidence, "checkpointEvidence"));
            this.publicationReceipts = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            publicationReceipts, "publicationReceipts")));
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
            this.admissionReceipts = Collections.unmodifiableMap(
                    typedReceipts);
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
            this.closurePublicationReceipts = Collections.unmodifiableMap(
                    processReceipts);
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
                    Map.of());
        }

        StoreState withSessions(
                Map<DocumentId, DocumentSession> replacementSessions,
                ManagedLineageIndex replacementLineages,
                ProcessEmbeddedComponentIndex replacementIndex,
                long replacementIndexGeneration) {
            List<ComponentSnapshot> retainedComponents = componentStates.stream()
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
                    outbox,
                    checkpointEvidence,
                    publicationReceipts,
                    admissionReceipts,
                    closurePublicationReceipts);
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
            return componentStates;
        }

        List<ComponentSnapshot> componentStatesFor(
                Collection<DocumentId> documentIds) {
            LinkedHashMap<String, ComponentSnapshot> selected =
                    new LinkedHashMap<>();
            for (DocumentId documentId : documentIds) {
                ComponentSnapshot component = componentStateByDocument.get(
                        Objects.requireNonNull(documentId, "documentId"));
                if (component != null) {
                    selected.putIfAbsent(
                            component.componentStateIdentity(), component);
                }
            }
            ArrayList<ComponentSnapshot> canonical = new ArrayList<>(
                    selected.values());
            canonical.sort((left, right) -> Integer.compare(
                    componentStateOrder.get(left.componentStateIdentity()),
                    componentStateOrder.get(right.componentStateIdentity())));
            return List.copyOf(canonical);
        }

        ClosureSubscriptionInventory closureSubscriptions() {
            return closureSubscriptions;
        }

        List<PublicEventOccurrence> outbox() {
            return outbox;
        }

        List<CheckpointWrite> checkpointEvidence() {
            return checkpointEvidence;
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
                                .equals(exact.afterBlueId())) {
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
            ProcessEmbeddedComponentIndex componentIndex) {
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
                            session.currentRevision().after().blueId())));
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
