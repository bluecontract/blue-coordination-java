package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
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
    private ManagedRepresentationVerificationMemo representationVerifications = new ManagedRepresentationVerificationMemo();
    private final boolean coldPublicationProjection;
    private StoredPublicationReceiptReuse storedPublicationReuse;
    private Object publicationRetentionEpoch;
    private StoreState state;
    private StoredHistoricalSources historicalSources;

    synchronized void bindHistoricalSources(StoredHistoricalSources sources) {
        if (historicalSources != null) throw new IllegalStateException("Historical source access already bound");
        historicalSources = Objects.requireNonNull(sources);
    }

    synchronized Optional<DocumentSession> sourceAdmission(DocumentId id) {
        return historicalSources == null ? find(id) : historicalSources.admission(id);
    }

    synchronized Optional<DocumentSession> sourceBefore(DocumentId id, blue.language.processor.ExternalOrderKey cutoff) {
        return historicalSources == null ? find(id) : historicalSources.before(id, cutoff);
    }

    synchronized Optional<DocumentSession> sourceAdmission(DocumentId id, Set<DocumentId> observers) {
        if (historicalSources == null) return sourceAdmission(id);
        return historicalSources.sourceInstance(id, observers.stream().map(this::require).toList()).flatMap(historicalSources::admission);
    }

    synchronized Optional<DocumentSession> sourceBefore(DocumentId id, blue.language.processor.ExternalOrderKey cutoff,
            Set<DocumentId> observers) {
        if (historicalSources == null) return sourceBefore(id, cutoff);
        return historicalSources.sourceInstance(id, observers.stream().map(this::require).toList())
                .flatMap(ref -> historicalSources.before(ref, cutoff));
    }

    /** Route lineage belongs to the exact retained source and its recorded forward roles. */
    synchronized Optional<DocumentSession> retainedSourceBefore(DocumentId target, DocumentSession retainedSource,
            blue.language.processor.ExternalOrderKey cutoff, Set<DocumentId> observers) {
        if (target.equals(retainedSource.documentId())) return Optional.of(retainedSource);
        if (historicalSources == null) return sourceBefore(target, cutoff);
        return historicalSources.sourceInstance(retainedSource.documentId(), observers.stream().map(this::require).toList())
                .flatMap(ref -> historicalSources.beforeFrom(target, ref, retainedSource, cutoff));
    }

    synchronized <T> T historicalRead(java.util.function.Supplier<T> reader) {
        return historicalSources == null ? reader.get() : historicalSources.protectRead(reader);
    }
    synchronized blue.coordination.api.DocumentRevision observedRevision(DocumentId source, long epoch, Set<DocumentId> observers) {
        return historicalRead(() -> observedSource(source, observers).revision(epoch));
    }
    synchronized String observedHistoryIdentity(DocumentId source, Set<DocumentId> observers) {
        return historicalRead(() -> observedSource(source, observers).requireRootedHistory().identity());
    }

    synchronized DocumentSession observedSource(DocumentId source, Set<DocumentId> observers) {
        if (historicalSources == null || observers.contains(source)) return require(source);
        var ref = historicalSources.sourceInstance(source, observers.stream().map(this::require).toList()).orElseThrow(
                () -> new IllegalStateException("Historical source has no retained execution association"));
        return historicalSources.latest(ref);
    }
    synchronized ManagedEpochEvidence managedEpochEvidence(DocumentId source, long epoch, Set<DocumentId> observers) {
        if (historicalSources == null || observers.contains(source)) return managedEpochEvidence(source, epoch);
        return historicalSources.sourceInstance(source, observers.stream().map(this::require).toList())
                .map(ref -> historicalSources.receipt(ref, epoch)).orElse(new ManagedEpochEvidence(null, null));
    }

    synchronized blue.coordination.api.DocumentInstanceRef pendingSourceInstance(DocumentId source, Set<DocumentId> observers) {
        if (historicalSources == null) throw new IllegalStateException("Pending instance association requires logical history");
        return historicalSources.sourceInstance(source, observers.stream().map(this::require).toList())
                .orElseGet(() -> LogicalDocumentInstances.initialReference(source));
    }

    synchronized Optional<String> sourceWorkBlock(DocumentId id, Set<DocumentId> observers) {
        return historicalSources == null ? Optional.empty()
                : historicalSources.sourceWorkBlock(id, observers.stream().map(this::require).toList());
    }

    synchronized OccurrenceResolutionSnapshot historicalOccurrenceResolutionSnapshot(
            blue.language.processor.ExternalOrderKey cutoff, Set<DocumentId> owners) {
        var current = occurrenceResolutionSnapshot();
        if (historicalSources == null) return current;
        return new OccurrenceResolutionSnapshot(historicalSources.lineages(cutoff, current.lineageIndex(), owners,
                        id -> historicalSources.sourceInstance(id, owners.stream().map(this::require).toList())),
                current.occurrenceInventory(), current.componentIndex(), current.occurrenceInventoryGeneration(),
                current.componentIndexGeneration());
    }

    InMemoryDocumentStore() {
        this(new EngineMetrics());
    }

    InMemoryDocumentStore(EngineMetrics metrics) {
        this(metrics, StoreState.empty());
    }

    /** Installs an already complete selected state; never enumerates, replays or derives missing rows. */
    InMemoryDocumentStore(EngineMetrics metrics, StoreState selectedState) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        registerExactReadCounters();
        state = Objects.requireNonNull(selectedState, "selectedState");
        coldPublicationProjection = state.closurePublicationReceiptIndex().valueProjectionIdentity() != null;
    }

    /** Bound once, before a restored engine is returned to its caller; never a global identity cache. */
    synchronized void bindStoredPublicationReuse(StoredPublicationReceiptReuse reuse) {
        if (!coldPublicationProjection || storedPublicationReuse != null)
            throw new IllegalStateException("Publication reuse requires a fresh cold document store");
        storedPublicationReuse = Objects.requireNonNull(reuse);
        publicationRetentionEpoch = reuse.retentionEpoch();
        clearRepresentationVerifications();
    }

    private boolean retainedForProofReuse(ContractsClosurePublicationReceipt publication) {
        if (!coldPublicationProjection) return true;
        if (storedPublicationReuse == null) return false;
        Object current = storedPublicationReuse.retentionEpoch();
        if (current != publicationRetentionEpoch) {
            // No uncharged proof may keep an unbounded series of evicted deep receipt graphs alive.
            clearRepresentationVerifications(); publicationRetentionEpoch = current;
        }
        return storedPublicationReuse.contains(publication);
    }

    private ManagedRepresentationVerificationMemo representationMemo(ContractsClosurePublicationReceipt publication) {
        // Retire any owner-local deep references even when this lookup can use the process entry.
        boolean ownerRetained = retainedForProofReuse(publication);
        if (coldPublicationProjection && storedPublicationReuse != null) {
            var shared = storedPublicationReuse.processProofs(publication);
            if (shared != null) return shared;
        }
        return ownerRetained ? representationVerifications : null;
    }

    synchronized StoreState storedState() { return state; }

    /** Exact existing catalog order without materializing its session values. */
    synchronized List<DocumentId> sessionIds() { return state.sessionIndex().keys(); }

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

    /** Dedicated lifecycle operation; its caller proves topology/work eligibility first. */
    synchronized Set<DocumentId> retainedIncomingSources(blue.coordination.api.DocumentInstanceRef retiring) {
        var retained = new LinkedHashSet<DocumentId>();
        if (historicalSources == null) return retained;
        for (var source : state.componentIndex().directSources(retiring.documentId())) {
            var rows = state.occurrenceInventory().activeRowsFrom(source).stream().filter(row ->
                    row.targetDocumentId().value().equals(retiring.documentId().value())).toList();
            if (rows.isEmpty()) throw new IllegalStateException("Incoming topology has no exact active occurrence");
            if (rows.stream().allMatch(row -> historicalSources.targetsRetiredOtherInstance(source, row, retiring))) retained.add(source);
        }
        return Set.copyOf(retained);
    }

    synchronized void retireIndependentOwner(DocumentId owner, Set<DocumentId> retainedIncoming) {
        require(owner);
        var nextTopology = state.componentIndex().withoutIndependentOwner(owner, retainedIncoming);
        state = StoreState.trustedTransition(state.sessionIndex().remove(owner).map(),
                state.lineageIndex().withoutLineage(owner), state.occurrenceInventory().replaceSources(Set.of(owner), List.of()).inventory(),
                state.occurrenceInventoryGeneration(), nextTopology, state.componentIndexGeneration(),
                state.graphGenerations().withoutDocument(owner), state.componentStateInventory().replaceAffected(Set.of(owner), List.of()),
                state.closureSubscriptions().withoutDocument(owner), state.outboxLog(), state.checkpointEvidenceLog(),
                state.publicationReceiptIndex(), state.admissionReceiptIndex(), state.closurePublicationReceiptIndex(),
                state.rootedProviderFrontiers(), state.closureApplicationResults(), state.managedEpochReceipts(), state.catchUpPlans());
    }

    synchronized void startInstanceAtBasis(DocumentSession basis, ManagedEpochReceiptStore.DocumentHistory originalReceipts) {
        var owner = basis.documentId();
        if (state.sessionIndex().get(owner) != null) throw new IllegalStateException("Starting session already exists");
        var session = basis.copyForAtomicPublication(); var view = session.rootedView();
        var receipts = state.managedEpochReceipts().withStartingHistory(session, originalReceipts);
        var component = view.snapshot().components().stream().filter(row -> row.orderedMemberDocumentIds()
                .contains(ContractsClosureAdapter.closureId(owner))).findFirst().orElseThrow();
        var replacement = StoreState.trustedTransition(state.sessionIndex().put(owner, session).map(),
                state.lineageIndex().withNewLineage(session), state.occurrenceInventory().replaceSources(Set.of(owner),
                        view.snapshot().occurrences().stream().filter(row -> row.sourceDocumentId().equals(ContractsClosureAdapter.closureId(owner))).toList()).inventory(),
                state.occurrenceInventoryGeneration(), state.componentIndex().withStartingOwner(session), state.componentIndexGeneration(),
                state.graphGenerations().withStartingDocument(owner, view.snapshot().graphGeneration()),
                state.componentStateInventory().replaceAffected(Set.of(owner), List.of(component)),
                state.closureSubscriptions().withStartingOwner(owner, view.subscriptions()), state.outboxLog(), state.checkpointEvidenceLog(),
                state.publicationReceiptIndex(), state.admissionReceiptIndex(), state.closurePublicationReceiptIndex(),
                state.rootedProviderFrontiers(), state.closureApplicationResults(), receipts, state.catchUpPlans());
        historicalSources.published(Set.of(owner), replacement); state = replacement;
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

    synchronized void requireGlobalDrainSupported() { if (historicalSources != null) historicalSources.requireGlobalDrainSupported(); }
    synchronized boolean hasInstanceStorage() { return historicalSources != null; }

    synchronized Optional<blue.coordination.api.DocumentInstanceRef> activeInstance(DocumentId owner) {
        return historicalSources == null ? Optional.empty() : Optional.of(historicalSources.activeInstance(owner));
    }
    synchronized void requireRetainedInstance(blue.coordination.api.DocumentInstanceRef ref) {
        if (historicalSources == null) throw new IllegalStateException("Retained instance selection requires logical storage");
        historicalSources.requireRetainedInstance(ref);
    }

    synchronized <T> Optional<T> retiredOriginalAdmission(String identity, DocumentId document, java.util.function.Function<DocumentSession, T> projection) {
        if (historicalSources == null) return Optional.empty();
        var receipt = admissionReceipt(identity).orElseThrow(() -> new IllegalArgumentException("Unknown original admission"));
        return historicalSources.retiredOriginalAdmission(identity, receipt.documentIds(), document, projection);
    }

    synchronized Optional<ContractsClosurePublicationReceipt> executionPublicationReceipt(String identity, DocumentId observer) {
        if (historicalSources == null || observer == null) return closurePublicationReceipt(identity);
        return historicalSources.executionReceipt(observer, identity);
    }

    synchronized Optional<ContractsClosurePublicationReceipt> executionPublicationReceipt(blue.coordination.api.DocumentInstanceRef observer, String identity) {
        return historicalSources.executionReceipt(observer, identity);
    }
    synchronized List<DocumentRevision> executionCausal(blue.coordination.api.DocumentInstanceRef observer, String identity, DocumentId member, String entry) {
        return historicalSources.executionCausal(observer, identity, member, entry);
    }
    synchronized boolean hasExecutionPublication(String identity, DocumentId observer) {
        return historicalSources == null || observer == null ? state.hasPublicationReceipt(identity)
                : historicalSources.hasExecution(observer, identity);
    }

    /** Looks up one typed process receipt without opening document heads. */
    synchronized Optional<ContractsClosurePublicationReceipt>
            closurePublicationReceipt(String publicationIdentity) {
        return Optional.ofNullable(state.closurePublicationReceipts().get(
                Objects.requireNonNull(
                        publicationIdentity, "publicationIdentity")));
    }

    /** Selects one current durable row and its pure proof under the same membership check. */
    blue.language.processor.closure.ManagedRepresentationTransition proveRetainedRepresentation(
            String publicationIdentity, blue.language.processor.closure.DocumentId document,
            long epoch, String anchor, String predecessor, String receipt) {
        ContractsClosurePublicationReceipt publication;
        ManagedRepresentationVerificationMemo.Request request;
        ManagedRepresentationVerificationMemo memo;
        synchronized (this) {
            // This is the ordinary physical selection, including dependency adoption and crosslinks.
            // Selecting again before a warm hit would reopen the same complete receipt envelope.
            publication = state.closurePublicationReceipts().get(
                    Objects.requireNonNull(publicationIdentity, "publicationIdentity"));
            request = representationRequest(publication, document, epoch, anchor, predecessor, receipt);
            memo = representationMemo(publication);
            var found = memo == null ? null : memo.find(request);
            if (found != null) return found;
        }
        return completeRepresentationProof(publication, request, memo);
    }

    /** Supplied/staged objects still require an independent current-membership check before reuse. */
    blue.language.processor.closure.ManagedRepresentationTransition proveRepresentation(
            ContractsClosurePublicationReceipt publication, blue.language.processor.closure.DocumentId document,
            long epoch, String anchor, String predecessor, String receipt) {
        var request = representationRequest(publication, document, epoch, anchor, predecessor, receipt);
        ManagedRepresentationVerificationMemo memo;
        synchronized (this) {
            // Physical selection, dependency adoption and current membership still precede every proof hit.
            memo = state.closurePublicationReceipts().get(publication.publicationIdentity()) == publication
                    ? representationMemo(publication) : null;
            var found = memo == null ? null : memo.find(request);
            if (found != null) return found;
        }
        return completeRepresentationProof(publication, request, memo);
    }

    private static ManagedRepresentationVerificationMemo.Request representationRequest(
            ContractsClosurePublicationReceipt publication, blue.language.processor.closure.DocumentId document,
            long epoch, String anchor, String predecessor, String receipt) {
        if (publication == null || !publication.commits())
            throw new IllegalArgumentException("Original representation commit is unavailable");
        var input = publication.managedSurfaceEvidence().originalInvocation();
        if (!publication.documentIds().contains(ContractsClosureAdapter.coordinationId(document)) || input == null)
            throw new IllegalArgumentException("Original representation classification input is unavailable");
        return new ManagedRepresentationVerificationMemo.Request(publication, document, epoch, anchor, predecessor,
                input, publication.attempt().processResult(), receipt);
    }

    private blue.language.processor.closure.ManagedRepresentationTransition completeRepresentationProof(
            ContractsClosurePublicationReceipt publication, ManagedRepresentationVerificationMemo.Request request,
            ManagedRepresentationVerificationMemo memo) {
        // The expensive pure proof never holds the store monitor. Concurrent cold proofs are benign.
        var proved = request.prove();
        synchronized (this) {
            // Never retain a proposal that was staged on entry, even if it committed during proof.
            if (memo != null
                    && state.closurePublicationReceipts().get(publication.publicationIdentity()) == publication
                    && representationMemo(publication) == memo)
                return memo.retain(request, proved);
        }
        return proved;
    }

    synchronized void clearRepresentationVerifications() {
        representationVerifications.clear();
        // Opaque memo identity also invalidates any cold construction that began before this clear.
        representationVerifications = new ManagedRepresentationVerificationMemo();
    }

    /** Checks retained local-provider promises before accepting another exact entry. */
    synchronized void requireAfterRootedProviderFrontier(blue.coordination.api.TimelineEntry entry) {
        // The in-memory provider closes each required source through the
        // selected input when retaining its terminal admission evidence. The
        // promise survives response loss/restart with that atomic receipt;
        // unrelated sources never inherit a global environment watermark.
        state.rootedProviderFrontiers().requireAfter(entry, state.publicationReceiptIndex(),
                state.closurePublicationReceiptIndex());
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

    /** Resolves a retained rooted terminal through the already authenticated application result. */
    synchronized Optional<ContractsClosurePublicationReceipt> closureReceiptForApplication(
            ManagedEpochApplicationReceipt application) {
        ContractsClosurePublicationReceipt legacy = state.closurePublicationReceipts().get(application.workIdentity());
        if (legacy != null) return Optional.of(legacy);
        String publicationIdentity = state.closureApplicationResults().publication(
                application.contractsResultIdentity(), application.commitCompanionIdentity());
        if (publicationIdentity == null) return Optional.empty();
        var receipt = state.closurePublicationReceipts().get(publicationIdentity);
        if (receipt == null || !receipt.attempt().processResult().commits()
                || !receipt.attempt().processResult().outputClosureIdentity().equals(application.contractsResultIdentity())
                || !receipt.attempt().processResult().commitCompanion().companionIdentity().equals(application.commitCompanionIdentity()))
            throw new IllegalStateException("Retained application result differs from its publication index");
        return Optional.of(receipt);
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

    synchronized Optional<ManagedEpochApplicationWork> nextCatchUpWork(CatchUpConsumerScope consumers) {
        return Optional.ofNullable(state.catchUpPlans().nextDueWork(consumers).work());
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
        if (historicalSources != null) {
            historicalSources.published(selected.retainedSourceOwners(), replacement);
            historicalSources.completedExecution(selected.closureReceipt(), replacement);
        }
        state = replacement;
    }

    static ProcessEmbeddedComponentIndex componentIndex(
            Collection<DocumentSession> sessions,
            ManagedOccurrenceInventory inventory) {
        List<DocumentId> documents = sessions.stream()
                .map(DocumentSession::documentId)
                .toList();
        return ProcessEmbeddedComponentIndex
                .fromDocumentsAndOccurrenceInventory(documents, inventory)
                .withReconstructedPendingJoins(sessions);
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
        private final RootedProviderFrontiers rootedProviderFrontiers;
        private final ClosureApplicationResultIndex closureApplicationResults;
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
            if (componentIndex.hasRootedViews()) {
                requireRootedInventoryOrder(canonicalComponents, componentIndex);
            } else {
                requireCondensationOrder(canonicalComponents, componentIndex);
            }
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
                        requireRetainedClosureReceipt(exact, this.sessions, "Process receipt");
                    });
            this.closurePublicationReceiptIndex =
                    closurePublicationReceiptIndex(processReceipts);
            this.closurePublicationReceipts = new PersistentMapView<>(
                    closurePublicationReceiptIndex);
            this.rootedProviderFrontiers = RootedProviderFrontiers.from(processReceipts.values());
            this.closureApplicationResults = ClosureApplicationResultIndex.from(processReceipts.values());
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
                RootedProviderFrontiers rootedProviderFrontiers,
                ClosureApplicationResultIndex closureApplicationResults,
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
            this.rootedProviderFrontiers = Objects.requireNonNull(rootedProviderFrontiers, "rootedProviderFrontiers");
            this.closureApplicationResults = Objects.requireNonNull(closureApplicationResults, "closureApplicationResults");
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
                RootedProviderFrontiers rootedProviderFrontiers,
                ClosureApplicationResultIndex closureApplicationResults,
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
                    rootedProviderFrontiers,
                    closureApplicationResults,
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
            // A full reconstruction must not silently discard an unverified supplied frontier projection.
            rootedProviderFrontiers.rows();
            closureApplicationResults.rows();
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

        RootedProviderFrontiers rootedProviderFrontiers() { return rootedProviderFrontiers; }
        ClosureApplicationResultIndex closureApplicationResults() { return closureApplicationResults; }

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
                    rootedProviderFrontiers,
                    closureApplicationResults,
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
                    rootedProviderFrontiers,
                    closureApplicationResults,
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

        /** A host-rejected draft retains its authenticated input, not the unpublished successful result heads. */
        static void requireRetainedClosureReceipt(ContractsClosurePublicationReceipt receipt,
                Map<DocumentId, DocumentSession> sessions, String label) {
            var plan = receipt.rejectedDraftPlan();
            var terminal = receipt.rootedTerminalEvidence();
            var result = receipt.attempt().processResult();
            if (plan == null || terminal == null) {
                requireRetainedResult(receipt.documentIds(), result, sessions, label, receipt.publicationDocuments().values());
                return;
            }
            terminal.requireRejectedDraftPlan(plan, result, receipt.publicationIdentity());
            var rooted = terminal.storedState().rooted();
            var input = terminal.input().snapshot();
            var target = input.managedDocument(ContractsClosureAdapter.closureId(plan.targetDocumentId()));
            var targetFence = rooted.publicationFences().get(plan.targetDocumentId());
            if (receipt.commits() || !result.commits() || receipt.managedSurfaceEvidence().present()
                    || target == null || target.epoch() != plan.targetEpoch() || !target.blueId().equals(plan.targetBlueId())
                    || targetFence == null || targetFence.head().epoch() != plan.targetEpoch()
                    || !targetFence.head().blueId().equals(plan.targetBlueId())
                    || !rooted.histories().keySet().equals(new LinkedHashSet<>(terminal.entryOwners()))
                    || plan.drafts().keySet().stream().anyMatch(rooted.publicationFences()::containsKey)) {
                throw new IllegalArgumentException(label + " rejection differs from its original target or absence fences");
            }
            var members = terminal.entryOwners().stream().map(owner -> Map.of(
                    "documentId", owner.value(), "historyBasisIdentity", rooted.histories().get(owner).identity())).toList();
            if (!members.equals(rooted.context().ownerDescriptor().get("members"))) {
                throw new IllegalArgumentException(label + " rejection changed its original owner histories");
            }
            var publicationDocuments = receipt.publicationDocuments();
            if (!new LinkedHashSet<>(receipt.documentIds()).equals(publicationDocuments.keySet())) {
                throw new IllegalArgumentException(label + " rejection changed its exact publication owners");
            }
            for (var owner : receipt.documentIds()) {
                var before = input.managedDocument(ContractsClosureAdapter.closureId(owner));
                var after = publicationDocuments.get(owner);
                if (before == null || !before.blueId().equals(after.beforeBlueId())) {
                    throw new IllegalArgumentException(label + " rejection changed its original input for " + owner);
                }
                var fence = rooted.publicationFences().get(owner);
                if (fence == null) {
                    var draft = plan.drafts().get(owner);
                    if (draft == null || before.epoch() != 0L || before.initialized()
                            || !before.blueId().equals(draft.initial().blueId())) {
                        throw new IllegalArgumentException(label + " rejection lacks an exact absent draft for " + owner);
                    }
                    // Absence belongs to the original atomic decision. A later
                    // successful operation may admit this same lineage.
                    continue;
                }
                var session = sessions.get(owner);
                if (session == null || !session.documentId().equals(owner)
                        || !session.retainsStoredPosition(before.epoch(), before.blueId())
                        || !session.retainsStoredPosition(fence.head().epoch(), fence.head().blueId())) {
                    throw new IllegalArgumentException(label + " rejection input or publication fence is absent from durable history for " + owner);
                }
                var history = rooted.histories().get(owner);
                if (history != null) {
                    var retained = session.requireRootedHistory();
                    if (!history.identity().equals(retained.identity())
                            || !history.admissionInvocationIdentity().equals(retained.admissionInvocationIdentity())
                            || !history.admissionCompanionIdentity().equals(retained.admissionCompanionIdentity())) {
                        throw new IllegalArgumentException(label + " rejection belongs to another admitted history for " + owner);
                    }
                }
            }
        }

        static void requireRetainedResult(
                List<DocumentId> receiptDocuments,
                blue.language.processor.closure.ClosureProcessResult result,
                Map<DocumentId, DocumentSession> sessions,
                String label) {
            requireRetainedResult(receiptDocuments, result, sessions, label, RootedResultScope.documents(result));
        }

        static void requireRetainedResult(
                List<DocumentId> receiptDocuments,
                blue.language.processor.closure.ClosureProcessResult result,
                Map<DocumentId, DocumentSession> sessions, String label,
                Collection<ResultingDocument> authoritativeRecords) {
            TreeMap<DocumentId, ResultingDocument> resulting =
                    new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
            for (ResultingDocument document : authoritativeRecords) {
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
                                result, exact, session)
                        && !retainsAuthenticatedCheckpointSettlement(
                                result, exact, session)) {
                    throw new IllegalArgumentException(
                            label + " result head is absent from durable "
                                    + "history for " + entry.getKey()
                                    + " (result epoch=" + exact.epoch()
                                    + ", after=" + exact.afterBlueId()
                                    + ", retained head epoch=" + session.epoch() + ")");
                }
            }
            if (!retainedDocument) {
                throw new IllegalArgumentException(
                        label + " has no retained document");
            }
        }

        private static boolean retainsAuthenticatedCheckpointSettlement(
                blue.language.processor.closure.ClosureProcessResult result,
                ResultingDocument document, DocumentSession session) {
            if (!result.commits() || result.platformCommitCompanion() == null
                    || !result.platformCommitCompanion().bindsManagedTransitionReceipts()
                    || document.epoch() >= session.epoch()) {
                return false;
            }
            var transition = result.managedTransitionReceipts().stream()
                    .filter(row -> row.documentId().equals(document.documentId()))
                    .findFirst().orElse(null);
            if (!ContractsClosureAdapter.isVerifiedCheckpointSettlementChange(result,
                    session.documentId(), new DocumentHead(document.epoch(), document.beforeBlueId()),
                    document, transition)) {
                return false;
            }
            // The live publisher advances exactly one Coordination revision for
            // this verified settlement while Contracts retains its work epoch.
            // Do not accept a later matching body or replace the original result.
            var revision = session.revision(document.epoch() + 1L);
            if ((revision.kind() != DocumentRevision.Kind.TIMELINE_ENTRY
                    && revision.kind() != DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                    || !revision.before().map(before -> before.blueId().equals(document.beforeBlueId())).orElse(false)
                    || !revision.after().blueId().equals(document.afterBlueId())
                    || revision.managedEpochReceipt().isEmpty()) {
                return false;
            }
            var receipt = revision.managedEpochReceipt().orElseThrow();
            var sourceEntry = receipt.sourceEntry().orElse(null);
            if (sourceEntry != null && !revision.causalEntryBlueId().filter(sourceEntry.blueId()::equals).isPresent()
                    || revision.kind() == DocumentRevision.Kind.TIMELINE_ENTRY
                    && (sourceEntry == null || !revision.sourceEntry().filter(entry -> entry.blueId().equals(sourceEntry.blueId())
                            && entry.sourceOrderKey().equals(sourceEntry.sourceOrderKey())).isPresent())) {
                return false;
            }
            var expected = ManagedEpochReceiptMapper.map(session.documentId(), revision.epoch(), revision.kind(),
                    revision.before().orElseThrow(), revision.after(), sourceEntry,
                    revision.sourceOrderKey().orElse(null), transition, result.platformCommitCompanion());
            return receipt.receiptIdentity().equals(expected.receiptIdentity());
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

        /** Root-local proof inventories are scalar ordered; their global edge union is not a semantic DAG. */
        private static void requireRootedInventoryOrder(List<ComponentSnapshot> states,
                ProcessEmbeddedComponentIndex index) {
            DocumentId prior = null;
            for (ComponentSnapshot state : states) {
                List<DocumentId> members = state.orderedMemberDocumentIds().stream()
                        .map(member -> DocumentId.of(member.value())).toList();
                if (members.isEmpty() || prior != null
                        && EmbeddingBinding.DOCUMENT_ORDER.compare(prior, members.get(0)) >= 0) {
                    throw new IllegalArgumentException("Rooted component inventory is not in first-member scalar order");
                }
                for (DocumentId member : members) {
                    if (!index.component(member).members().equals(members)) {
                        throw new IllegalArgumentException("Rooted component inventory differs from its exact member index: " + member);
                    }
                }
                prior = members.get(0);
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
