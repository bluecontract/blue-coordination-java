package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.Timeline;

import blue.coordination.api.SessionStatus;

import blue.coordination.api.Operation;

import blue.coordination.api.ExactValue;

import blue.coordination.api.DocumentRevision;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedEpochApplicationAttempt;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochEvidenceFailure;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.ProcessingAvailability;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.api.ProcessingReadiness;
import blue.coordination.api.TimelineAppendReceipt;
import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.EmbeddedCollectionPlanningAudit;
import blue.coordination.processor.TimelineProviderSupport;

import blue.language.api.BlueCacheStats;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.SubscriptionSurfaceInvalidException;
import blue.language.processor.closure.ClosureInvocationInput;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Sequential in-memory Process Embedded temporal-profile engine. */
public final class DefaultCoordinationEngine
        implements CoordinationEngine {
    private static final BigInteger PORTABLE_FULL_HISTORY_ORDER =
            BigInteger.valueOf(-9_007_199_254_740_991L);

    enum FailurePoint {
        AFTER_MANAGED_DRAFT_PLAN_REGISTERED,
        AFTER_MANAGED_EPOCH_SELECTION_PLAN_REGISTERED,
        BEFORE_FROZEN_PROCESS,
        AFTER_FROZEN_BEFORE_STAGE,
        AFTER_STAGING_CHILD_SESSION,
        AFTER_APPLYING_CHILD_REVISION,
        BEFORE_COMMIT_VALIDATION,
        AFTER_STATE_SWAP_BEFORE_RETURN,
        BEFORE_ROOTED_READINESS
    }

    static final class InjectedFailureException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InjectedFailureException(FailurePoint point) {
            super("Injected coordination failure at " + point);
        }
    }

    /** Narrow immutable projection used by advanced diagnostic adapters. */
    public record ManagedOccurrenceAuditView(
            DocumentId targetDocumentId,
            long activationGeneration,
            boolean active) {
        public ManagedOccurrenceAuditView {
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            if (activationGeneration < 1L) {
                throw new IllegalArgumentException(
                        "activationGeneration must be positive");
            }
        }
    }

    /** Narrow immutable source address used by advanced diagnostic adapters. */
    public record OperationRouteSourceAuditView(
            String timelineId,
            String actorId) {
        public OperationRouteSourceAuditView {
            timelineId = requireAuditText(timelineId, "timelineId");
            actorId = requireAuditText(actorId, "actorId");
        }
    }

    /** Narrow immutable compiled route used by advanced diagnostic adapters. */
    public record OperationRouteAuditView(
            String scopePath,
            String operation,
            String channel,
            Optional<ExactValue> requestPattern,
            List<OperationRouteSourceAuditView> acceptedSources) {
        public OperationRouteAuditView {
            scopePath = requireAuditText(scopePath, "scopePath");
            operation = requireAuditText(operation, "operation");
            channel = requireAuditText(channel, "channel");
            requestPattern = Objects.requireNonNull(
                    requestPattern, "requestPattern");
            acceptedSources = List.copyOf(Objects.requireNonNull(
                    acceptedSources, "acceptedSources"));
        }
    }

    private static String requireAuditText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static final long BASE_TIMESTAMP_MICROS =
            1_800_000_000_000_000L;

    private final EngineMetrics metrics;
    private final WholeObjectStore objects;
    private final blue.language.provider.NodeProvider
            applicationExactNodeProvider;
    private final BlueRuntime runtime;
    private final WholeRequestEntryFactory entryFactory;
    private final InMemoryTimelineJournal journal;
    private final OperationRouteIndex routeIndex;
    private final EmbeddedOnlyLayoutBuilder layoutBuilder;
    private final DocumentTransitionProcessor processor;
    private final InMemoryDocumentStore documents;
    private final ContractsClosureAdapter contractsClosureAdapter;
    private final ContractsClosureAdmissionAdapter
            contractsClosureAdmissionAdapter;
    private final ContractsClosureProfile contractsClosureProfile;
    private final ContractsRuntimeBinding contractsRuntimeBinding;
    private final ContractsActiveSourceTimelineIndex
            contractsActiveSourceTimelines;
    private final ContractsRecoveryState contractsRecoveryState;
    private SequentialDrainCoordinator drainCoordinator;
    private ContractsRootFeederCoordinator contractsFeederCoordinator;
    private ContractsJournalDrainCoordinator contractsJournalCoordinator;
    private final RootedSourceDiscoveryCoordinator rootedSourceDiscoveries;
    private final blue.coordination.sdk.ExactNodeProvider rootedSourceProvider;
    private final Map<String, Timeline> timelines;
    private final Map<String, String> timelineActorKinds;
    private final LogicalEngineControl logicalControl;
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private long logicalClockMicros = BASE_TIMESTAMP_MICROS;
    private long applicationClockMicros = BASE_TIMESTAMP_MICROS;
    private boolean closed;

    // Physical subcomponent only: no document, journal, route or provider restore.
    synchronized EngineControlStorageCodec.State controlStateForStorage() {
        return controlStateForStorage(true);
    }

    private EngineControlStorageCodec.State controlStateForStorage(boolean requireEmptyPendingComponents) {
        ensureOpen();
        if (contractsRecoveryState == null || !contractsClosureProfile.rootedCheckpoint())
            throw new blue.coordination.api.storage.CoordinationObjectStorageException("Control storage requires the rooted Contracts profile");
        if (requireEmptyPendingComponents) {
            rootedSourceDiscoveries.requireStorageSupported();
            contractsClosureAdapter.requireControlStorageSupported();
        }
        return new EngineControlStorageCodec.State(contractsRuntimeBinding,
                timelines, timelineActorKinds, logicalClockMicros, applicationClockMicros,
                List.copyOf(contractsClosureProfile.publicRoots()), contractsRecoveryState.rootedSchedule.storageState(),
                List.copyOf(contractsRecoveryState.deferredManagedConsumers),
                List.copyOf(contractsRecoveryState.isolatedManagedConsumers), contractsRecoveryState.managedEpochTurn(),
                contractsRecoveryState.feederWindow.storageState(requireEmptyPendingComponents), contractsRecoveryState.journalDrain.storageState());
    }

    /** Control-only restoration; the full factory installs the separate feeder evidence maps as well. */
    static ContractsRecoveryState recoveryStateFromStorage(EngineControlStorageCodec.State state) {
        return recoveryStateFromStorage(state, ContractsRootFeederWindow.DurableState.StoredMaps.empty());
    }

    static ContractsRecoveryState recoveryStateFromStorage(EngineControlStorageCodec.State state,
            ContractsRootFeederWindow.DurableState.StoredMaps feederMaps) {
        return new ContractsRecoveryState(RootedProcessingSchedule.fromStorage(state.rootedSchedule()),
                ContractsRootFeederWindow.DurableState.fromStorage(state.feeder(), feederMaps),
                ContractsJournalDrainCoordinator.DurableState.fromStorage(state.journal()),
                state.deferredManagedConsumers(), state.isolatedManagedConsumers(), state.managedEpochTurn());
    }

    private DefaultCoordinationEngine(
            ContractsBootstrap contractsBootstrap) {
        this(contractsBootstrap, null);
    }

    private DefaultCoordinationEngine(
            ContractsBootstrap contractsBootstrap,
            blue.coordination.sdk.ExactNodeProvider exactNodeProvider) {
        this(contractsBootstrap, exactNodeProvider, null);
    }

    private DefaultCoordinationEngine(
            ContractsBootstrap contractsBootstrap,
            blue.coordination.sdk.ExactNodeProvider exactNodeProvider,
            blue.coordination.api.TimelineJournalStore journalStore) {
        this(contractsBootstrap, exactNodeProvider, journalStore, null);
    }

    /** All families are installed together; this constructor never replays admission or rebuilds routes. */
    private DefaultCoordinationEngine(
            ContractsBootstrap contractsBootstrap,
            blue.coordination.sdk.ExactNodeProvider exactNodeProvider,
            blue.coordination.api.TimelineJournalStore journalStore, StoredParts stored) {
        contractsRuntimeBinding = contractsBootstrap == null ? null : new ContractsRuntimeBinding(
                contractsBootstrap.blueLanguageSpecificationIdentity(), contractsBootstrap.contractsSpecificationIdentity(),
                contractsBootstrap.executionPolicy());
        metrics = new EngineMetrics();
        logicalControl = stored == null ? null : stored.logicalControl();
        timelines = logicalControl == null ? new LinkedHashMap<>() : logicalControl.timelines();
        timelineActorKinds = logicalControl == null ? new LinkedHashMap<>() : logicalControl.actorKinds();
        if (stored != null) {
            if (!stored.control().binding().equals(contractsRuntimeBinding) || journalStore == null)
                throw new blue.coordination.api.storage.CoordinationObjectStorageException("Complete engine storage has another release or no exact journal");
            if (logicalControl == null) {
                timelines.putAll(stored.control().timelines()); timelineActorKinds.putAll(stored.control().actorKinds());
            }
            logicalClockMicros = stored.control().logicalClockMicros();
            applicationClockMicros = stored.control().applicationClockMicros();
        }
        rootedSourceProvider = exactNodeProvider == null ? id -> Optional.empty() : exactNodeProvider;
        objects = new WholeObjectStore(metrics, stored == null ? WholeObjectBacking.EMPTY : stored.objects());
        applicationExactNodeProvider = exactNodeProvider == null
                ? null
                : Contracts10StaticEmbeddedAdmissionCompiler
                        .verifiedProvider(exactNodeProvider);
        runtime = BlueRuntime.create(
                objects, metrics, applicationExactNodeProvider);
        entryFactory = new WholeRequestEntryFactory(
                runtime, objects, metrics, this::timelineActorKind,
                contractsBootstrap != null && ContractsClosureProfile.ROOTED_CONTRACTS_SPECIFICATION
                        .equals(contractsBootstrap.contractsSpecificationIdentity()));
        journal = journalStore == null ? new InMemoryTimelineJournal(entryFactory, metrics)
                : new InMemoryTimelineJournal(entryFactory, metrics, journalStore);
        documents = stored == null ? new InMemoryDocumentStore(metrics) : new InMemoryDocumentStore(metrics, stored.documents());
        routeIndex = stored == null ? new OperationRouteIndex(metrics, documentId -> documents.find(documentId).orElse(null))
                : OperationRouteIndex.restoreIndexes(stored.routes(), metrics,
                        documentId -> documents.find(documentId).orElse(null),
                        documentId -> documents.find(documentId).map(session -> session.currentRepresentation().blueId()).orElse(null));
        routeIndex.operationMessageResolver(runtime::materializeExact);
        routeIndex.generalDeliveryResolver((row, entry) -> runtime.generalDelivery(
                documents.require(row.documentId()).layout().processingFrozen().toNode(), row.channelKey(), entry));
        layoutBuilder = new EmbeddedOnlyLayoutBuilder(
                runtime, objects, metrics);
        processor = new DocumentTransitionProcessor(
                runtime, objects, layoutBuilder, metrics,
                this::inject);
        drainCoordinator = contractsBootstrap == null ? new SequentialDrainCoordinator(
                runtime,
                objects,
                entryFactory,
                journal,
                routeIndex,
                processor,
                documents,
                metrics,
                this::nextApplicationTimestamp,
                this::inject) : null;
        if (contractsBootstrap == null) {
            contractsClosureAdapter = null;
            contractsClosureAdmissionAdapter = null;
            contractsClosureProfile = null;
            contractsActiveSourceTimelines = null;
            contractsRecoveryState = null;
            contractsFeederCoordinator = null;
            contractsJournalCoordinator = null;
            rootedSourceDiscoveries = null;
        } else {
            ContractsClosureProfile profile = ContractsClosureProfile
                    .release10(
                            contractsBootstrap
                                    .blueLanguageSpecificationIdentity(),
                            contractsBootstrap
                                    .contractsSpecificationIdentity(),
                            contractsBootstrap.executionPolicy(),
                            contractsBootstrap.publicRootDocumentIds());
            contractsClosureProfile = profile;
            contractsActiveSourceTimelines = stored == null
                    ? new ContractsActiveSourceTimelineIndex(profile.publicRoots(), metrics)
                    : ContractsActiveSourceTimelineIndex.restoreIndexes(stored.activeSources(), metrics);
            if (logicalControl != null) profile.bindLogicalPublicRoots(contractsActiveSourceTimelines.logicalPublicRoots());
            contractsClosureAdapter = new ContractsClosureAdapter(
                    runtime,
                    objects,
                    layoutBuilder,
                    documents,
                    routeIndex,
                    profile,
                    contractsActiveSourceTimelines,
                    journal::entries, stored == null ? ContractsClosureAdapter.StoredPlans.empty() : stored.plans());
            contractsClosureAdmissionAdapter =
                    new ContractsClosureAdmissionAdapter(
                            runtime,
                            objects,
                            layoutBuilder,
                            documents,
                            routeIndex,
                            profile,
                            contractsActiveSourceTimelines,
                            (input, result, project) -> RootedBeginningAdmission.verify(
                                    input, result, journal, timelines, this::timelineActorKind, project));
            rootedSourceDiscoveries = profile.rootedCheckpoint() ? new RootedSourceDiscoveryCoordinator(this, documents,
                    contractsClosureAdapter, journal, layoutBuilder, routeIndex, timelines, rootedSourceProvider,
                    stored == null ? RootedSourceDiscoveryCoordinator.StoredMaps.empty() : stored.sources()) : null;
            if (rootedSourceDiscoveries != null) contractsClosureAdapter.sourceDiscoveryCoordinator(rootedSourceDiscoveries);
            contractsRecoveryState = stored == null ? new ContractsRecoveryState()
                    : logicalControl == null ? recoveryStateFromStorage(stored.control(), stored.feederMaps())
                    : logicalControl.recovery(stored.feederMaps());
            contractsClosureAdapter.feederDecisions(contractsRecoveryState.feederWindow);
            contractsFeederCoordinator = createContractsFeederCoordinator();
            contractsJournalCoordinator = createContractsJournalCoordinator();
        }
    }

    /** Complete typed assembly, supplied only by the private physical-storage boundary. */
    record StoredParts(EngineControlStorageCodec.State control, WholeObjectBacking objects,
            InMemoryDocumentStore.StoreState documents, OperationRouteIndex.StoredIndexes routes,
            ContractsActiveSourceTimelineIndex.StoredIndexes activeSources, ContractsClosureAdapter.StoredPlans plans,
            RootedSourceDiscoveryCoordinator.StoredMaps sources, ContractsRootFeederWindow.DurableState.StoredMaps feederMaps,
            LogicalEngineControl logicalControl) {
        StoredParts(EngineControlStorageCodec.State control, WholeObjectBacking objects,
                InMemoryDocumentStore.StoreState documents, OperationRouteIndex.StoredIndexes routes,
                ContractsActiveSourceTimelineIndex.StoredIndexes activeSources, ContractsClosureAdapter.StoredPlans plans,
                RootedSourceDiscoveryCoordinator.StoredMaps sources, ContractsRootFeederWindow.DurableState.StoredMaps feederMaps) {
            this(control, objects, documents, routes, activeSources, plans, sources, feederMaps, null);
        }
        StoredParts {
            Objects.requireNonNull(control); Objects.requireNonNull(objects); Objects.requireNonNull(documents);
            Objects.requireNonNull(routes); Objects.requireNonNull(activeSources); Objects.requireNonNull(plans); Objects.requireNonNull(sources);
            Objects.requireNonNull(feederMaps);
        }
    }

    static DefaultCoordinationEngine restoreRooted(StoredParts stored,
            blue.coordination.sdk.ExactNodeProvider provider, blue.coordination.api.TimelineJournalStore journalStore) {
        Objects.requireNonNull(stored); var b = stored.control().binding();
        return new DefaultCoordinationEngine(new ContractsBootstrap(b.languageSpecificationIdentity(), b.contractsSpecificationIdentity(),
                b.executionPolicy(), new java.util.LinkedHashSet<>(stored.control().publicRoots())), provider,
                Objects.requireNonNull(journalStore), stored);
    }

    /** Current owning scope only; the storage layer must detach every family before retiring this runtime. */
    synchronized StoredParts storedParts(WholeObjectBacking retainedObjects) {
        ensureOpen();
        return new StoredParts(logicalControl == null ? controlStateForStorage(false) : logicalControl.initialState(), retainedObjects, documents.storedState(), routeIndex.storedIndexes(),
                contractsActiveSourceTimelines.storedIndexes(), contractsClosureAdapter.storedPlans(), rootedSourceDiscoveries.storedMaps(),
                contractsRecoveryState.feederWindow.storedMaps(), logicalControl);
    }

    /** Creates the legacy Process Embedded temporal-profile engine. */
    public static DefaultCoordinationEngine create() {
        return new DefaultCoordinationEngine(null);
    }

    /** Journal-only physical test seam; no other runtime state is restored. */
    static DefaultCoordinationEngine createWithJournalStore(
            blue.coordination.api.TimelineJournalStore store) {
        return new DefaultCoordinationEngine(null, null, Objects.requireNonNull(store, "store"));
    }

    /**
     * Creates an engine that owns a Contracts 1.0 closure runtime.
     *
     * <p>The caller supplies the exact final artifact identities and public
     * Root lineages; the engine never substitutes placeholder identities.</p>
     *
     * @param configuration exact Contracts 1.0 host configuration
     * @return a new closure-capable engine
     */
    public static DefaultCoordinationEngine createContracts10(
            Contracts10Configuration configuration) {
        Contracts10Configuration selected = Objects.requireNonNull(
                configuration, "configuration");
        return new DefaultCoordinationEngine(new ContractsBootstrap(
                selected.blueLanguageSpecificationIdentity(),
                selected.contractsSpecificationIdentity(),
                ContractsExecutionPolicy.releaseDefault(),
                selected.publicRootDocumentIds()));
    }

    /**
     * Creates the SDK Contracts runtime before authored public Roots are known.
     * Every Root must still be authorized before its atomic admission.
     */
    public static DefaultCoordinationEngine createContracts10Sdk(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity) {
        return new DefaultCoordinationEngine(new ContractsBootstrap(
                blueLanguageSpecificationIdentity,
                contractsSpecificationIdentity,
                ContractsExecutionPolicy.releaseDefault(),
                Set.of()));
    }

    /**
     * Creates the SDK Contracts runtime with one verified application exact
     * node provider available to ordinary Language resolution.
     */
    public static DefaultCoordinationEngine createContracts10Sdk(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            blue.coordination.sdk.ExactNodeProvider exactNodeProvider) {
        return new DefaultCoordinationEngine(
                new ContractsBootstrap(
                        blueLanguageSpecificationIdentity,
                        contractsSpecificationIdentity,
                        ContractsExecutionPolicy.releaseDefault(),
                        Set.of()),
                Objects.requireNonNull(
                        exactNodeProvider, "exactNodeProvider"));
    }

    /**
     * Creates the SDK runtime with one verified provider and explicit exact
     * Contracts closure execution policy.
     */
    public static DefaultCoordinationEngine createContracts10Sdk(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            blue.coordination.sdk.ExactNodeProvider exactNodeProvider,
            ContractsExecutionPolicy executionPolicy) {
        return new DefaultCoordinationEngine(
                new ContractsBootstrap(
                        blueLanguageSpecificationIdentity,
                        contractsSpecificationIdentity,
                        Objects.requireNonNull(
                                executionPolicy, "executionPolicy"),
                        Set.of()),
                Objects.requireNonNull(
                        exactNodeProvider, "exactNodeProvider"));
    }

    /**
     * Authorizes additional public Root lineages for the SDK host profile.
     *
     * <p>This mutates only host routing configuration. It does not admit a
     * document, create graph evidence, or select recipients. The following
     * closure admission remains responsible for proving and atomically
     * publishing every declared Root.</p>
     */
    public synchronized void authorizeContractsPublicRoots(
            java.util.Collection<DocumentId> publicRoots) {
        ensureOpen();
        if (contractsClosureProfile == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        java.util.Collection<DocumentId> checked = Objects.requireNonNull(
                publicRoots, "publicRoots");
        contractsClosureProfile.addPublicRoots(checked);
        contractsActiveSourceTimelines.addPublicRoots(checked);
    }

    /**
     * Exposes one already-admitted lineage as a public Root.
     *
     * <p>This changes only the host's Root/source catalogs. It does not append
     * an entry, execute a process, change document content, or advance an
     * epoch.</p>
     */
    public synchronized void promoteContractsPublicRoot(DocumentId id) {
        ensureOpen();
        if (contractsClosureProfile == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        DocumentId selected = Objects.requireNonNull(id, "id");
        requireDocument(selected);
        contractsClosureProfile.addPublicRoots(Set.of(selected));
        contractsActiveSourceTimelines.addPublicRoots(Set.of(selected));
        contractsActiveSourceTimelines.refresh(Set.of(selected), documents);
    }

    @Override
    public synchronized Timeline registerTimeline(
            String timelineId,
            String actorId) {
        ensureOpen();
        Timeline proposed = new Timeline(timelineId, actorId);
        Timeline existing = timelines.putIfAbsent(timelineId, proposed);
        if (existing != null && !existing.equals(proposed)) {
            throw new IllegalArgumentException(
                    "Timeline " + timelineId + " already belongs to actor "
                            + existing.actorId());
        }
        return existing == null ? proposed : existing;
    }

    /** Selects the exact actor contract used for SDK-authored entries. */
    public synchronized void registerTimelineActorType(
            String timelineId,
            String actorType) {
        if (!timelines.containsKey(timelineId)) {
            throw new IllegalArgumentException(
                    "Timeline is not registered: " + timelineId);
        }
        String checked = Objects.requireNonNull(actorType, "actorType");
        String existing = timelineActorKinds.putIfAbsent(
                timelineId, checked);
        if (existing != null && !existing.equals(checked)) {
            throw new IllegalArgumentException(
                    "Timeline " + timelineId + " already uses " + existing);
        }
    }

    /** Returns the exact actor contract used for SDK-authored entries. */
    public synchronized String timelineActorKind(String timelineId) {
        return timelineActorKinds.getOrDefault(
                timelineId, "MyOS/Principal Actor");
    }

    /**
     * Reads one actual registration without treating an empty journal as absence.
     * This diagnostic neither registers a Timeline nor grants append authority.
     * @param timelineId exact registered Timeline identity
     * @return the immutable registration, including registered-empty Timelines
     */
    public synchronized Optional<Timeline> auditRegisteredTimeline(String timelineId) {
        ensureOpen();
        return Optional.ofNullable(timelines.get(requireAuditText(timelineId, "timelineId")));
    }

    /**
     * Reads retained catalog membership without loading a session body. Existence
     * does not certify the current body, readiness, or permission to execute it.
     * @param documentId exact retained document identity
     * @return whether the selected catalog contains this identity
     */
    public synchronized boolean hasStoredDocument(DocumentId documentId) {
        ensureOpen();
        return documents.storedState().sessionIndex().containsKeyWithoutValue(
                Objects.requireNonNull(documentId, "documentId"));
    }

    /**
     * Explicit identity inventory of the selected catalog, without session bodies.
     * This is linear in catalog size; it is not a next-work or graph-selection API.
     * @return detached identities, in stable host presentation order
     */
    public synchronized List<DocumentId> storedDocumentIds() {
        ensureOpen();
        return documents.sessionIds().stream().sorted(java.util.Comparator.comparing(DocumentId::value)).toList();
    }

    /**
     * Exact configuration of this engine's actual Contracts bootstrap.
     * @param languageSpecificationIdentity selected Language specification
     * @param contractsSpecificationIdentity selected Contracts specification
     * @param executionPolicy exact configured default policy
     */
    public record ContractsRuntimeBinding(String languageSpecificationIdentity,
            String contractsSpecificationIdentity, ContractsExecutionPolicy executionPolicy) { }

    /**
     * Reports actual immutable configuration for matching a storage component.
     * It is not a caller-selected policy or a restoration/authority factory.
     * @return exact bootstrap binding
     * @throws IllegalStateException if this is not a Contracts engine
     */
    public synchronized ContractsRuntimeBinding contractsRuntimeBinding() {
        ensureOpen();
        if (contractsRuntimeBinding == null) throw new IllegalStateException("Engine has no Contracts bootstrap");
        return contractsRuntimeBinding;
    }

    /** Returns one canonical retained Timeline Entry for read-only audit. */
    public synchronized Optional<TimelineEntry> auditTimelineEntry(
            String entryBlueId) {
        ensureOpen();
        return journal.byBlueId(requireAuditText(
                entryBlueId, "entryBlueId"));
    }

    /** Returns every canonical retained Timeline Entry in append order. */
    public synchronized List<TimelineEntry> auditTimelineEntries() {
        ensureOpen();
        return journal.entries();
    }

    /** Returns canonical retained entries for one Timeline in append order. */
    public synchronized List<TimelineEntry> auditTimeline(
            String timelineId) {
        ensureOpen();
        return journal.entries(requireAuditText(timelineId, "timelineId"));
    }

    /** Reads only the selected head and maximum-order entry, under one journal view. */
    public synchronized blue.coordination.api.TimelineJournalPosition auditTimelinePosition(String timelineId) {
        ensureOpen();
        return journal.position(requireAuditText(timelineId, "timelineId"));
    }

    synchronized Timeline timeline(String timelineId, String actorId) {
        return registerTimeline(timelineId, actorId);
    }

    synchronized DocumentSession start(
            String documentId,
            String authoredYaml) {
        return start(DocumentId.of(documentId), authoredYaml);
    }

    synchronized DocumentSession start(
            DocumentId documentId,
            String authoredYaml) {
        return start(
                documentId,
                authoredYaml,
                CoordinationEngine.AdmissionPolicy.FROM_NOW,
                null);
    }

    private synchronized DocumentSession start(
            DocumentId documentId,
            String authoredYaml,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier) {
        ensureOpen();
        if (documents.find(documentId).isPresent()) {
            throw new IllegalArgumentException(
                    "Duplicate document session " + documentId);
        }
        WholeObjectStore.Mark objectMark = objects.mark();
        try {
            ExternalOrderKey admissionFrontier = switch (policy) {
                case FULL_HISTORY ->
                        DocumentTransitionProcessor.fullHistoryFrontier(
                                documentId);
                case FROM_FRONTIER -> requireRetainedFrontier(
                        verifiedFrontier);
                case FROM_NOW -> currentAdmissionFrontier(documentId);
            };
            DocumentSession candidate = processor.admit(
                    documentId,
                    authoredYaml,
                    admissionFrontier,
                    policy);
            documents.insert(candidate);
            routeIndex.replace(
                    candidate.documentId(),
                    candidate.layout().routingSurface(),
                    candidate.activeSubscriptions());
            drainCoordinator.admitTopLevel(
                    candidate,
                    Objects.requireNonNull(policy, "policy"),
                    verifiedFrontier);
            metrics.increment("sessionsCreated");
            metrics.timed("process.commitReadinessPublication",
                    () -> objects.commit(objectMark));
            return candidate;
        } catch (RuntimeException failure) {
            boolean retainAdmission = documents.find(documentId).isPresent()
                    && drainCoordinator.retainFailedAdmission(documentId);
            if (retainAdmission) {
                objects.commit(objectMark);
                metrics.increment("temporal.failedAdmissionsRetained");
            } else {
                documents.remove(documentId);
                routeIndex.remove(documentId);
                objects.rollbackTo(objectMark);
            }
            throw failure;
        }
    }

    @Override
    public synchronized DocumentSnapshot startDocument(
            DocumentId documentId,
            String authoredYaml) {
        requireLegacyOnly("startDocument");
        try {
            return snapshot(start(documentId, authoredYaml), true);
        } catch (RuntimeException failure) {
            throw translateStartFailure(documentId, failure);
        }
    }

    @Override
    public synchronized ContractsClosureAdmissionReceipt
            admitContractsClosure(
                    ClosureInvocationInput input,
                    CoordinationEngine.AdmissionPolicy policy,
                    ExternalOrderKey verifiedFrontier) {
        ensureOpen();
        if (contractsClosureAdapter == null) {
            throw new CoordinationException(
                    CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                    "admitContractsClosure requires Contracts 1.0 mode");
        }
        CoordinationEngine.AdmissionPolicy selectedPolicy =
                Objects.requireNonNull(policy, "policy");
        ExternalOrderKey frontier = switch (selectedPolicy) {
            case FULL_HISTORY -> {
                requireNoExplicitFrontier(selectedPolicy, verifiedFrontier);
                yield ExternalOrderKey.of(List.of(
                        PORTABLE_FULL_HISTORY_ORDER,
                        "contracts-full-history-admission",
                        Objects.requireNonNull(input, "input")
                                .invocationIdentity()));
            }
            case FROM_FRONTIER -> requireRetainedFrontier(verifiedFrontier);
            case FROM_NOW -> {
                requireNoExplicitFrontier(selectedPolicy, verifiedFrontier);
                yield currentContractsAdmissionFrontier(
                        Objects.requireNonNull(input, "input"));
            }
        };
        return contractsClosureAdmissionAdapter.admitAndPublish(
                input, selectedPolicy, frontier);
    }

    /** Admits a compiler-authenticated closure with its supplied exact type bodies. */
    public synchronized ContractsClosureAdmissionReceipt admitContractsClosure(
            Contracts10AuthoredClosureCompiler.CompiledClosure compiled) {
        ensureOpen();
        Objects.requireNonNull(compiled, "compiled").retainInlineTypeEvidence(objects);
        var activation = compiled.activationInputs();
        return admitContractsClosure(compiled.invocation(), activation.policy(),
                activation.verifiedFrontier());
    }

    /**
     * SDK static-admission seam for resolving exact referenced occurrence
     * content without changing the provider used by ordinary operations.
     */
    public synchronized ContractsClosureAdmissionReceipt
            admitContractsClosure(
                    ClosureInvocationInput input,
                    CoordinationEngine.AdmissionPolicy policy,
                    ExternalOrderKey verifiedFrontier,
                    blue.coordination.sdk.ExactNodeProvider exactNodeProvider) {
        return admitContractsClosure(
                input,
                policy,
                verifiedFrontier,
                exactNodeProvider,
                null);
    }

    /** Static-admission seam with occurrence-specific retained selectors. */
    public synchronized ContractsClosureAdmissionReceipt
            admitContractsClosure(
                    ClosureInvocationInput input,
                    CoordinationEngine.AdmissionPolicy policy,
                    ExternalOrderKey verifiedFrontier,
                    blue.coordination.sdk.ExactNodeProvider exactNodeProvider,
                    ContractsManagedEpochSelectionPlan selectionPlan) {
        ensureOpen();
        if (contractsClosureAdapter == null) {
            throw new CoordinationException(
                    CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                    "admitContractsClosure requires Contracts 1.0 mode");
        }
        CoordinationEngine.AdmissionPolicy selectedPolicy =
                Objects.requireNonNull(policy, "policy");
        ExternalOrderKey frontier = switch (selectedPolicy) {
            case FULL_HISTORY -> {
                requireNoExplicitFrontier(selectedPolicy, verifiedFrontier);
                yield ExternalOrderKey.of(List.of(
                        PORTABLE_FULL_HISTORY_ORDER,
                        "contracts-full-history-admission",
                        Objects.requireNonNull(input, "input")
                                .invocationIdentity()));
            }
            case FROM_FRONTIER -> requireRetainedFrontier(verifiedFrontier);
            case FROM_NOW -> {
                requireNoExplicitFrontier(selectedPolicy, verifiedFrontier);
                yield currentContractsAdmissionFrontier(
                        Objects.requireNonNull(input, "input"));
            }
        };
        blue.language.provider.NodeProvider verifiedProvider =
                Contracts10StaticEmbeddedAdmissionCompiler.verifiedProvider(
                        Objects.requireNonNull(
                                exactNodeProvider, "exactNodeProvider"));
        return selectionPlan == null
                ? contractsClosureAdmissionAdapter.admitAndPublish(
                        input,
                        selectedPolicy,
                        frontier,
                        verifiedProvider)
                : contractsClosureAdmissionAdapter.admitAndPublish(
                        input,
                        selectedPolicy,
                        frontier,
                        verifiedProvider,
                        selectionPlan);
    }

    @Override
    public synchronized void configureEmbeddedAdmission(
            DocumentId documentId,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough) {
        ensureOpen();
        requireLegacyOnly("configureEmbeddedAdmission");
        drainCoordinator.configureEmbeddedAdmission(
                documentId, mode, verifiedCompleteThrough);
    }

    @Override
    public synchronized void configureEmbeddedAdmission(
            DocumentId parentDocumentId,
            String absoluteChildPath,
            DocumentId childDocumentId,
            String admittedStateBlueId,
            Long admittedEpoch,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough,
            String completenessProofIdentity,
            String expectedAttachmentEntryBlueId) {
        ensureOpen();
        requireLegacyOnly("configureEmbeddedAdmission");
        drainCoordinator.configureEmbeddedAdmission(
                parentDocumentId, absoluteChildPath, childDocumentId,
                admittedStateBlueId, admittedEpoch, mode,
                verifiedCompleteThrough, completenessProofIdentity,
                expectedAttachmentEntryBlueId);
    }

    @Override
    public synchronized DocumentSnapshot startDocument(
            DocumentId documentId,
            String authoredYaml,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier) {
        requireLegacyOnly("startDocument");
        try {
            return snapshot(start(
                    documentId,
                    authoredYaml,
                    policy,
                    verifiedFrontier), true);
        } catch (RuntimeException failure) {
            throw translateStartFailure(documentId, failure);
        }
    }


    synchronized ExactValue registerType(String sourceYaml) {
        ensureOpen();
        return runtime.exactProcessingSource(sourceYaml, objects, "test-type");
    }

    synchronized ExactValue exactRequest(String requestYaml) {
        ensureOpen();
        return entryFactory.parseExactRequest(requestYaml);
    }

    synchronized ExactValue exactProcessingSource(String sourceYaml) {
        ensureOpen();
        return runtime.exactProcessingSource(sourceYaml, objects, "static processing admission");
    }

    @Override
    public synchronized ExactValue exactValue(String sourceYaml) {
        ensureOpen();
        return runtime.exactProcessingSource(
                sourceYaml, objects, "external-exact-value");
    }

    /** Reads an already retained exact body without resolving to a current document head. */
    public synchronized Optional<ExactValue> retainedExactValue(String blueId) {
        ensureOpen();
        Objects.requireNonNull(blueId, "blueId");
        return objects.contains(blueId) ? Optional.of(objects.require(blueId)) : Optional.empty();
    }

    /** Reads the exact terminal processor result retained at the atomic publication boundary. */
    public synchronized Optional<blue.language.processor.closure.ClosureProcessResult> auditClosureExecution(
            String publicationIdentity) {
        ensureOpen();
        return documents.closurePublicationReceipt(Objects.requireNonNull(publicationIdentity, "publicationIdentity"))
                .map(receipt -> receipt.attempt().processResult());
    }

    /**
     * Reads resolver facts from one authenticated committing receipt. It does not
     * open the selected sources' current heads or infer authority from a caller's DTO.
     */
    public synchronized Optional<List<ContractsClosureDispatchAttempt.ManagedOccurrenceResolution>>
            auditCommittedOccurrenceResolutions(String publicationIdentity) {
        ensureOpen();
        return documents.closurePublicationReceipt(Objects.requireNonNull(publicationIdentity))
                .filter(ContractsClosurePublicationReceipt::commits)
                .map(receipt -> {
                    var owned = receipt.attempt().processResult().rootedProjection();
                    return managedOccurrenceResolutions(receipt.managedSurfaceEvidence()).stream()
                            .filter(resolution -> owned == null || owned.owns(resolution.occurrence().sourceDocumentId()))
                            .toList();
                });
    }

    /** Reads the original immutable input retained with a terminal closure decision. */
    public synchronized Optional<blue.language.processor.closure.ClosureInvocationInput> auditClosureInvocation(
            String publicationIdentity) {
        ensureOpen();
        return documents.closurePublicationReceipt(Objects.requireNonNull(publicationIdentity, "publicationIdentity"))
                .map(receipt -> receipt.rootedTerminalEvidence() == null
                        ? receipt.managedSurfaceEvidence().originalInvocation() : receipt.rootedTerminalEvidence().input());
    }

    /**
     * Parses provider content, preprocesses runtime aliases, and retains its
     * direct identity without resolving the value's type as an instance.
     *
     * <p>This public method is an internal cross-package bridge for the
     * developer SDK. Applications use
     * {@link blue.coordination.sdk.ExactValues#providerContentYaml(String)}.
     * The returned value is not installed in the engine object store.</p>
     */
    public synchronized ExactValue exactProviderValue(String sourceYaml) {
        ensureOpen();
        return runtime.exactProviderSource(sourceYaml);
    }

    /** Read-only exact content and cyclic proofs for isolated closure verification. */
    blue.language.provider.NodeProvider retainedExactNodeProvider() {
        ensureOpen();
        return BlueRuntime.retainedExactProvider(objects, applicationExactNodeProvider);
    }

    synchronized ExactValue embeddedDocumentRequest(
            String exactDocumentYaml) {
        return referencedValueRequest("document", exactDocumentYaml);
    }

    synchronized ExactValue referencedValueRequest(
            String field,
            String exactValueYaml) {
        ensureOpen();
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        ExactValue value = runtime.exactSource(
                exactValueYaml,
                objects,
                "referenced-request-value");
        return objects.put(
                new Node().properties(
                        field, value.referenceNode()),
                "timeline-request");
    }

    @Override
    public synchronized ExactValue referenceRequest(
            String field,
            ExactValue exactValue) {
        ensureOpen();
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        ExactValue retained = objects.put(
                Objects.requireNonNull(exactValue, "exactValue"),
                "referenced-request-value");
        return objects.put(
                new Node().properties(field, retained.referenceNode()),
                "timeline-request");
    }

    @Override
    public synchronized TimelineEntry append(
            Timeline timeline,
            Operation operation) {
        ensureOpen();
        Timeline canonicalTimeline = requireRegisteredTimeline(timeline);
        long candidateTimestamp = Math.addExact(currentLogicalClock(), 1L);
        InMemoryTimelineJournal.Mark mark = journal.mark();
        WholeObjectStore.Mark objectMark = objects.mark();
        try {
            TimelineEntry entry = metrics.timed(
                    "append.total",
                    () -> journal.append(
                            canonicalTimeline,
                            operation,
                            candidateTimestamp));
            requireAfterProcessedFrontier(entry);
            setLogicalClock(candidateTimestamp);
            objects.commit(objectMark);
            return entry;
        } catch (RuntimeException failure) {
            journal.rollbackTo(mark);
            objects.rollbackTo(objectMark);
            throw failure;
        }
    }

    /**
     * Atomically appends one Contracts operation and its exact managed-draft
     * host evidence before either can be observed by the drain.
     */
    public synchronized TimelineEntry append(
            Timeline timeline,
            Operation operation,
            ContractsManagedDraftPlan managedDraftPlan) {
        return appendManagedOperation(
                timeline,
                operation,
                Objects.requireNonNull(
                        managedDraftPlan, "managedDraftPlan"),
                null);
    }

    /**
     * Atomically appends one Contracts operation and its exact epoch selectors.
     */
    public synchronized TimelineEntry append(
            Timeline timeline,
            Operation operation,
            ContractsManagedEpochSelectionPlan managedEpochSelectionPlan) {
        return appendManagedOperation(
                timeline,
                operation,
                null,
                Objects.requireNonNull(
                        managedEpochSelectionPlan,
                        "managedEpochSelectionPlan"));
    }

    /**
     * Atomically appends one Contracts operation with draft and epoch evidence.
     */
    public synchronized TimelineEntry append(
            Timeline timeline,
            Operation operation,
            ContractsManagedDraftPlan managedDraftPlan,
            ContractsManagedEpochSelectionPlan managedEpochSelectionPlan) {
        return appendManagedOperation(
                timeline,
                operation,
                Objects.requireNonNull(
                        managedDraftPlan, "managedDraftPlan"),
                Objects.requireNonNull(
                        managedEpochSelectionPlan,
                        "managedEpochSelectionPlan"));
    }

    private TimelineEntry appendManagedOperation(
            Timeline timeline,
            Operation operation,
            ContractsManagedDraftPlan managedDraftPlan,
            ContractsManagedEpochSelectionPlan managedEpochSelectionPlan) {
        ensureOpen();
        if (contractsClosureAdapter == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        Timeline canonicalTimeline = requireRegisteredTimeline(timeline);
        if (managedDraftPlan == null && managedEpochSelectionPlan == null) {
            throw new IllegalArgumentException(
                    "Managed operation append requires exact host evidence");
        }
        if (managedDraftPlan != null) {
            contractsClosureAdapter.preflightManagedDraftPlan(
                    managedDraftPlan);
        }
        if (managedEpochSelectionPlan != null) {
            contractsClosureAdapter.preflightManagedEpochSelectionPlan(
                    managedEpochSelectionPlan);
        }
        if (managedDraftPlan != null
                && managedEpochSelectionPlan != null
                && (!managedDraftPlan.targetDocumentId().equals(
                        managedEpochSelectionPlan.targetDocumentId())
                || managedDraftPlan.targetEpoch()
                        != managedEpochSelectionPlan.targetEpoch()
                || !managedDraftPlan.targetBlueId().equals(
                        managedEpochSelectionPlan.targetBlueId()))) {
            throw new IllegalArgumentException(
                    "Managed draft and epoch selector plans capture different "
                            + "operation target heads");
        }
        long previousLogicalClock = currentLogicalClock();
        long candidateTimestamp = Math.addExact(currentLogicalClock(), 1L);
        InMemoryTimelineJournal.Mark mark = journal.mark();
        WholeObjectStore.Mark objectMark = objects.mark();
        String registeredDraftEntryBlueId = null;
        String registeredSelectionEntryBlueId = null;
        try {
            TimelineEntry entry = metrics.timed(
                    "append.total",
                    () -> journal.append(
                            canonicalTimeline,
                            operation,
                            candidateTimestamp));
            requireAfterProcessedFrontier(entry);
            if (managedDraftPlan != null) {
                if (!contractsClosureAdapter.registerManagedDraftPlan(
                        entry.blueId(), managedDraftPlan)) {
                    throw new IllegalStateException(
                            "Managed draft plan already exists for new entry "
                                    + entry.blueId());
                }
                registeredDraftEntryBlueId = entry.blueId();
                inject(FailurePoint.AFTER_MANAGED_DRAFT_PLAN_REGISTERED);
            }
            if (managedEpochSelectionPlan != null) {
                if (!contractsClosureAdapter
                        .registerManagedEpochSelectionPlan(
                                entry.blueId(),
                                managedEpochSelectionPlan)) {
                    throw new IllegalStateException(
                            "Managed epoch selection plan already exists for "
                                    + "new entry " + entry.blueId());
                }
                registeredSelectionEntryBlueId = entry.blueId();
                inject(FailurePoint
                        .AFTER_MANAGED_EPOCH_SELECTION_PLAN_REGISTERED);
            }
            setLogicalClock(candidateTimestamp);
            objects.commit(objectMark);
            return entry;
        } catch (RuntimeException failure) {
            if (registeredSelectionEntryBlueId != null) {
                try {
                    contractsClosureAdapter
                            .unregisterManagedEpochSelectionPlan(
                                    registeredSelectionEntryBlueId,
                                    managedEpochSelectionPlan);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (registeredDraftEntryBlueId != null) {
                try {
                    contractsClosureAdapter.unregisterManagedDraftPlan(
                            registeredDraftEntryBlueId,
                            managedDraftPlan);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                journal.rollbackTo(mark);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                objects.rollbackTo(objectMark);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            setLogicalClock(previousLogicalClock);
            throw failure;
        }
    }

    @Override
    public synchronized TimelineEntry appendAt(
            Timeline timeline,
            Operation operation,
            long timestampMicros) {
        ensureOpen();
        Timeline canonicalTimeline = requireRegisteredTimeline(timeline);
        long nextClock = Math.max(currentLogicalClock(), timestampMicros);
        InMemoryTimelineJournal.Mark mark = journal.mark();
        WholeObjectStore.Mark objectMark = objects.mark();
        try {
            TimelineEntry entry = metrics.timed("append.total",
                    () -> journal.append(
                            canonicalTimeline, operation, timestampMicros));
            requireAfterProcessedFrontier(entry);
            setLogicalClock(nextClock);
            objects.commit(objectMark);
            return entry;
        } catch (RuntimeException failure) {
            journal.rollbackTo(mark);
            objects.rollbackTo(objectMark);
            throw failure;
        }
    }

    @Override
    public synchronized TimelineAppendReceipt appendTimelineEntry(
            Node exactEntry) {
        ensureOpen();
        long started = System.nanoTime();
        WholeObjectStore.Mark objectMark = objects.mark();
        InMemoryTimelineJournal.Mark journalMark = journal.mark();
        try {
            ExactValue supplied = ExactValue.verified(
                    Objects.requireNonNull(exactEntry, "exactEntry"));
            Node canonical = supplied.copyNode();
            String timelineId = requiredTextAt(
                    canonical, "/timeline/timelineId");
            String actorId = requiredTextAt(
                    canonical, "/actor/accountId");
            long timestamp = requiredLongAt(canonical, "/timestamp");
            Timeline timeline = requireRegisteredTimeline(
                    new Timeline(timelineId, actorId));
            boolean stored = journal.byBlueId(supplied.blueId()).isEmpty();
            TimelineEntry admitted = journal.appendExact(timeline, supplied);
            if (stored) {
                requireAfterProcessedFrontier(admitted);
            }
            setLogicalClock(Math.max(currentLogicalClock(), timestamp));
            objects.commit(objectMark);
            return new TimelineAppendReceipt(
                    admitted,
                    stored,
                    journal.size(),
                    System.nanoTime() - started);
        } catch (RuntimeException failure) {
            journal.rollbackTo(journalMark);
            objects.rollbackTo(objectMark);
            throw failure;
        } finally {
            metrics.addNanos("append.total", System.nanoTime() - started);
        }
    }

    @Override
    public synchronized int routeTargetCount(TimelineEntry entry) {
        ensureOpen();
        return routeIndex.route(journal.requireCanonical(
                Objects.requireNonNull(entry, "entry"))).size();
    }

    /** Validates aggregate execution before a convenience command appends or selects any work. */
    public synchronized void requireGlobalDrainSupported() { ensureOpen(); documents.requireGlobalDrainSupported(); }

    @Override
    public synchronized ProcessingDrainReceipt drain() {
        return drain(CoordinationEngine.DrainBudget.unlimited());
    }

    @Override
    public synchronized ProcessingDrainReceipt drain(
            CoordinationEngine.DrainBudget budget) {
        try {
            ensureOpen(); requireGlobalDrainSupported();
            if (contractsJournalCoordinator != null) {
                return drainContracts(
                        null, Objects.requireNonNull(budget, "budget"));
            }
            return drainCoordinator.drain(null, Objects.requireNonNull(
                    budget, "budget"));
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    /**
     * Executes one already supplied exact input relative to a selected root.
     * The caller's ordered driver owns input completeness; this method does
     * not select an input by wall clock or advance the global journal cursor.
     */
    public synchronized ProcessingDrainReceipt processRootInput(DocumentId root, TimelineEntry input) {
        return processRootInput(root, input, null);
    }

    /** Executes the supplied root input using one frozen invocation budget. */
    public synchronized ProcessingDrainReceipt processRootInput(DocumentId root, TimelineEntry input,
            blue.coordination.api.ContractsExecutionPolicy policy) {
        return processRootInput(root, input, policy, true);
    }

    /**
     * Completes only the supplied root stage. Receipt flags describe this stage,
     * not the existence of later eligible work. No readiness selection is made.
     * @param root authoritative root
     * @param input exact accepted journal input
     * @param policy optional invocation policy, or null for the configured policy
     * @return complete current-stage evidence, still subject to host publication
     */
    public synchronized ProcessingDrainReceipt processRootInputStage(DocumentId root, TimelineEntry input,
            blue.coordination.api.ContractsExecutionPolicy policy) {
        return processRootInput(root, input, policy, false);
    }

    private ProcessingDrainReceipt processRootInput(DocumentId root, TimelineEntry input,
            blue.coordination.api.ContractsExecutionPolicy policy, boolean inspectReadiness) {
        try {
            ensureOpen();
            TimelineEntry entry = journal.requireCanonical(Objects.requireNonNull(input, "input"));
            long started = System.nanoTime();
            ContractsClosureAdapter.FrozenBatch batch = contractsClosureAdapter.captureRoot(
                    Objects.requireNonNull(root, "root"), entry, policy);
            var completed = executeRootBatch(batch, started);
            return inspectReadiness ? rootedReadiness(root, completed, started) : completed;
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    private ProcessingDrainReceipt executeRootBatch(ContractsClosureAdapter.FrozenBatch batch, long started) {
        TimelineEntry entry = batch.entry();
            List<ContractsClosureDispatchAttempt> attempts = new ArrayList<>();
            List<DocumentDispatchOutcome> outcomes = new ArrayList<>();
            boolean complete = true;
            long committed = 0L;
            for (ContractsClosureAdapter.CohortInvocation invocation : batch.invocations()) {
                ContractsClosureAdapter.CohortOutcome exact = contractsClosureAdapter.executeAndPublish(batch, invocation);
                complete &= exact.attempt().isComplete() || exact.rejectedBirth() != null;
                attempts.add(new ContractsClosureDispatchAttempt(entry.blueId(), exact.publicationMembers(),
                        exact.attempt(), exact.published(), exact.publicationIdentity(), exact.replayed(),
                        exact.automaticRetryCount(), managedOccurrenceResolutions(exact.managedSurfaceEvidence()),
                        exact.managedSurfaceEvidence().inputComponents(),
                        exact.managedSurfaceEvidence().operationRouteChanges().stream()
                                .map(DefaultCoordinationEngine::operationRouteChange).toList(),
                        exact.unresolvedDemands().stream().map(unresolved ->
                                new ContractsClosureDispatchAttempt.ManagedOccurrenceResolutionIssue(
                                        unresolved.demand().demandIdentity(),
                                        ContractsClosureDispatchAttempt.ResolutionStatus.valueOf(unresolved.status().name()),
                                        unresolved.diagnostic())).toList()));
                if (exact.published() && !exact.replayed()) {
                    committed = Math.addExact(committed,
                            RootedResultScope.processTransitionCount(exact.attempt().processResult()));
                    for (DocumentId member : exact.publicationMembers()) {
                        documents.require(member).revisionForEntry(entry.blueId()).ifPresent(revision ->
                                outcomes.add(new DocumentDispatchOutcome(member, revision, 0L)));
                    }
                }
            }
            return new ProcessingDrainReceipt(complete ? List.of(entry) : List.of(),
                    outcomes.isEmpty() && !complete ? Map.of() : Map.of(entry.blueId(), outcomes),
                    attempts.isEmpty() && !complete ? Map.of() : Map.of(entry.blueId(), attempts),
                    complete ? entry.sourceOrderKey() : null, complete, false, committed,
                    System.nanoTime() - started);
    }

    /** Reconciles only actual exact retained source publications after response loss. */
    boolean sourceHistoryPrerequisiteCommitted(RootedSourceDiscoveryCoordinator.Prepared selected) {
        if (selected.admission() != null) {
            var input = selected.admission().invocation();
            if (selected.admission().activationInputs().policy() != CoordinationEngine.AdmissionPolicy.FULL_HISTORY)
                throw new IllegalArgumentException("Discovered sources require FULL_HISTORY");
            var frontier = ExternalOrderKey.of(List.of(PORTABLE_FULL_HISTORY_ORDER,
                    "contracts-full-history-admission", input.invocationIdentity()));
            String identity = ContractsClosureAdmissionAdapter.publicationIdentity(input,
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY, frontier);
            return documents.admissionReceipt(identity).map(ContractsClosureAdmissionReceipt::published).orElse(false);
        }
        var step = Objects.requireNonNull(selected.step());
        if (step.historical() != null) {
            var application = documents.catchUpApplicationByWork(step.historical().workIdentity());
            return application.isPresent() && documents.closureReceiptForApplication(application.orElseThrow())
                    .map(ContractsClosurePublicationReceipt::commits).orElse(false);
        }
        var batch = step.live() != null ? step.live()
                : contractsClosureAdapter.localHistoryBatch(Objects.requireNonNull(step.localHistorical()));
        if (batch.invocations().size() != 1) throw new IllegalStateException("Source prerequisite requires exactly one rooted invocation");
        return contractsClosureAdapter.publicationReceipt(batch, batch.invocations().get(0))
                .map(ContractsClosurePublicationReceipt::commits).orElse(false);
    }

    /**
     * Selects separately owned source prerequisites from actual suspended rooted attempts.
     * Provider reads may retain immutable exact evidence; no source or parent is processed.
     * @param root requesting root whose exact input remains pending
     * @return bounded individual source actions and explicit resource waits
     */
    public synchronized List<blue.coordination.api.SourceHistoryPrerequisite> sourceHistoryPrerequisites(DocumentId root) {
        ensureOpen();
        if (rootedSourceDiscoveries == null) throw new IllegalStateException("Source prerequisites require the rooted profile");
        return rootedSourceDiscoveries.selections(Objects.requireNonNull(root, "root"));
    }

    /** Selects native instance authority alongside each unchanged source descriptor. */
    public synchronized List<blue.coordination.api.SourceHistoryRequest> sourceHistoryRequests(DocumentId root) {
        ensureOpen(); return rootedSourceDiscoveries.requests(root);
    }
    /** Resolves descriptor-only reconciliation to its original native source request. */
    public synchronized blue.coordination.api.SourceHistoryRequest originalSourceHistoryRequest(blue.coordination.api.SourceHistoryPrerequisite expected) {
        ensureOpen(); return rootedSourceDiscoveries.originalRequest(expected);
    }
    /** Finds a complete original descriptor; unknown or caller-mismatched operands are absent. */
    public synchronized Optional<blue.coordination.api.SourceHistoryRequest> findOriginalSourceHistoryRequest(blue.coordination.api.SourceHistoryPrerequisite expected) {
        ensureOpen(); return rootedSourceDiscoveries.findOriginalRequest(expected);
    }
    /** Authenticates a native source request against its actual suspended request association. */
    public synchronized void requireSourceHistoryRequest(blue.coordination.api.SourceHistoryRequest request) {
        ensureOpen(); rootedSourceDiscoveries.requireRequest(request);
    }
    /** Observes the explicitly selected original requester and source instances. */
    public synchronized blue.coordination.api.SourceHistoryPrerequisiteObservation observeSourceHistoryPrerequisite(
            blue.coordination.api.SourceHistoryRequest request) {
        ensureOpen(); return rootedSourceDiscoveries.observe(request.prerequisite(), request);
    }

    /**
     * Observes a previously emitted prerequisite without executing its source or requesting parent.
     * Frozen root, invocation, demand, source, authored identity and cutoff must remain unchanged;
     * physical selection fields may refresh. Missing correlation or a terminal requester is STALE,
     * never SATISFIED. Provider reads may retain immutable exact evidence.
     * @param expected original descriptor whose frozen logical authority is being observed
     * @return current typed observation, with a fresh descriptor only when still pending
     * @throws IllegalArgumentException if retained correlation has different frozen logical operands
     */
    public synchronized blue.coordination.api.SourceHistoryPrerequisiteObservation observeSourceHistoryPrerequisite(
            blue.coordination.api.SourceHistoryPrerequisite expected) {
        ensureOpen();
        if (rootedSourceDiscoveries == null) throw new IllegalStateException("Source prerequisites require the rooted profile");
        return rootedSourceDiscoveries.observe(Objects.requireNonNull(expected, "expected"));
    }

    /**
     * Revalidates and executes exactly one separately reported source prerequisite.
     * The requesting parent is never retried inside this call.
     * @param expected exact descriptor from sourceHistoryPrerequisites
     * @return real source admission or one-step processing evidence
     */
    public synchronized blue.coordination.api.SourceHistoryPrerequisiteResult processSourceHistoryPrerequisite(
            blue.coordination.api.SourceHistoryPrerequisite expected) {
        ensureOpen();
        if (rootedSourceDiscoveries == null) throw new IllegalStateException("Source prerequisites require the rooted profile");
        Objects.requireNonNull(expected, "expected");
        if (documents.hasInstanceStorage()) return processSourceHistoryPrerequisite(rootedSourceDiscoveries.originalRequest(expected));
        var replay = rootedSourceDiscoveries.completed(expected);
        if (replay.isPresent()) return replay.orElseThrow();
        var committed = rootedSourceDiscoveries.committedSelection(expected);
        var selected = committed.orElseGet(() -> rootedSourceDiscoveries.requireSelection(expected));
        return executeSourcePrerequisite(expected, selected, committed.isPresent(), null);
    }

    /** Executes or reconciles only the authenticated instance-bound source request. */
    public synchronized blue.coordination.api.SourceHistoryPrerequisiteResult processSourceHistoryPrerequisite(
            blue.coordination.api.SourceHistoryRequest request) {
        ensureOpen(); var expected = request.prerequisite();
        var replay = rootedSourceDiscoveries.completed(expected, request);
        if (replay.isPresent()) return replay.orElseThrow();
        var committed = rootedSourceDiscoveries.committedSelection(expected, request);
        var selected = committed.orElseGet(() -> rootedSourceDiscoveries.requireSelection(expected, request));
        if (!committed.isPresent()) rootedSourceDiscoveries.retainStage(request, RootedStageCapture.source(expected, selected, null, documents));
        return executeSourcePrerequisite(expected, selected, committed.isPresent(), request);
    }

    private blue.coordination.api.SourceHistoryPrerequisiteResult executeSourcePrerequisite(
            blue.coordination.api.SourceHistoryPrerequisite expected, RootedSourceDiscoveryCoordinator.Prepared selected,
            boolean replayed, blue.coordination.api.SourceHistoryRequest request) {
        blue.coordination.api.SourceHistoryPrerequisiteResult result;
        if (selected.admission() != null) {
            var admission = selected.admission();
            authorizeContractsPublicRoots(Set.of(admission.rootDocumentId()));
            var activation = admission.activationInputs();
            var receipt = admitContractsClosure(admission.invocation(), activation.policy(), activation.verifiedFrontier(),
                    rootedSourceProvider);
            result = new blue.coordination.api.SourceHistoryPrerequisiteResult(expected, Optional.of(receipt), Optional.empty(), replayed);
        } else {
            var receipt = executeRootSelection(Objects.requireNonNull(selected.step()), System.nanoTime());
            result = new blue.coordination.api.SourceHistoryPrerequisiteResult(expected, Optional.empty(), Optional.of(receipt), replayed);
        }
        rootedSourceDiscoveries.retain(result, request);
        return result;
    }

    private SelectedSourceStage pendingSourceStage;

    /** Freezes exact source-owned admission/history work before the host acquires execution authority. */
    public synchronized SelectedSourceStage selectSourceHistoryStage(blue.coordination.api.SourceHistoryPrerequisite expected) {
        ensureOpen(); Objects.requireNonNull(expected);
        if (documents.hasInstanceStorage()) return selectSourceHistoryStage(rootedSourceDiscoveries.originalRequest(expected));
        return selectSourceHistoryStage(expected, null);
    }
    /** Freezes the explicitly authenticated requester/source instances, or their original completed stage. */
    public synchronized SelectedSourceStage selectSourceHistoryStage(blue.coordination.api.SourceHistoryRequest request) {
        ensureOpen(); return selectSourceHistoryStage(request.prerequisite(), request);
    }
    private SelectedSourceStage selectSourceHistoryStage(blue.coordination.api.SourceHistoryPrerequisite expected,
            blue.coordination.api.SourceHistoryRequest request) {
        if (rootedSourceDiscoveries == null) throw new IllegalStateException("Source prerequisites require the rooted profile");
        if (expected.kind() == blue.coordination.api.SourceHistoryPrerequisite.Kind.WAIT)
            throw new IllegalArgumentException("A resource wait is not executable source work");
        var replay = rootedSourceDiscoveries.completed(expected, request).orElse(null);
        var committed = replay == null ? rootedSourceDiscoveries.committedSelection(expected, request) : Optional.<RootedSourceDiscoveryCoordinator.Prepared>empty();
        var selected = replay != null ? null : committed.orElseGet(() -> rootedSourceDiscoveries.requireSelection(expected, request));
        var context = request != null && (replay != null || committed.isPresent()) ? rootedSourceDiscoveries.retainedStage(request)
                : RootedStageCapture.source(expected, selected, replay, documents);
        if (request != null && replay == null && committed.isEmpty()) rootedSourceDiscoveries.retainStage(request, context);
        pendingSourceStage = new SelectedSourceStage(context, selected, replay, committed.isPresent(), request);
        return pendingSourceStage;
    }

    /** Single-use source stage; no requesting-parent retry or successor selection is performed. */
    public final class SelectedSourceStage {
        private final Thread owner = Thread.currentThread();
        private final blue.coordination.api.SourceHistoryStageContext context;
        private final RootedSourceDiscoveryCoordinator.Prepared selected;
        private final blue.coordination.api.SourceHistoryPrerequisiteResult replay;
        private final boolean committed;
        private final blue.coordination.api.SourceHistoryRequest request;
        /** Original native authority, absent only for resident engines. */
        public Optional<blue.coordination.api.SourceHistoryRequest> request() { return Optional.ofNullable(request); }
        private SelectedSourceStage(blue.coordination.api.SourceHistoryStageContext context,
                RootedSourceDiscoveryCoordinator.Prepared selected, blue.coordination.api.SourceHistoryPrerequisiteResult replay,
                boolean committed, blue.coordination.api.SourceHistoryRequest request) {
            this.context = context; this.selected = selected; this.replay = replay; this.committed = committed; this.request = request;
        }
        public blue.coordination.api.SourceHistoryStageContext context() { return context; }
        public blue.coordination.api.SourceHistoryStageResult execute() {
            synchronized (DefaultCoordinationEngine.this) {
                if (closed || pendingSourceStage != this || owner != Thread.currentThread())
                    throw new IllegalStateException("Source stage is retired, used or belongs to another thread");
                pendingSourceStage = null;
                try {
                    var result = replay != null ? replay : executeSourcePrerequisite(context.prerequisite(), selected, committed, request);
                    var owners = new java.util.TreeSet<DocumentId>();
                    context.entryOwners().forEach(value -> owners.add(value.documentId()));
                    result.admission().ifPresent(receipt -> owners.addAll(receipt.documentIds()));
                    result.processing().ifPresent(receipt -> owners.addAll(RootedStageCapture.publishedOwners(receipt)));
                    boolean invalidated = result.admission().map(ContractsClosureAdmissionReceipt::published).orElse(false)
                            || result.processing().map(RootedStageCapture::invalidatesSelection).orElse(false);
                    var completed = new blue.coordination.api.SourceHistoryStageResult(context, result, List.copyOf(owners), invalidated);
                    if (!completed.committable()) DefaultCoordinationEngine.this.close();
                    return completed;
                } catch (RuntimeException | Error failure) {
                    try { DefaultCoordinationEngine.this.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }
    }

    /** Processes at most one earliest eligible obligation from the selected root's exact progress. */
    public synchronized ProcessingDrainReceipt processNextRoot(DocumentId root) {
        return processNextRoot(root, null);
    }

    /** Reads the selected root's runnable obligation without reserving work or changing global fairness. */
    public synchronized ProcessingSelection auditNextRootProcessingSelection(DocumentId root) {
        ensureOpen();
        var next = new RootedCheckpointDriver(documents, contractsClosureAdapter)
                .select(Objects.requireNonNull(root, "root"), journal.entries());
        if (next.localHistorical() != null && next.historical() == null)
            return ProcessingSelection.rootedRetained(next.localHistorical().root(), next.localHistorical().work());
        if (next.historical() != null) return ProcessingSelection.managedEpochApplication(next.historical());
        return next.live() != null ? ProcessingSelection.journal() : ProcessingSelection.none();
    }

    /** Executes only the exact retained local work selected for this root, before any mutation. */
    public synchronized ProcessingDrainReceipt processNextRoot(DocumentId root, String expectedLocalWork) {
        return processNextRoot(root, expectedLocalWork, true);
    }

    private SelectedRootStage pendingRootStage;

    /** Internal SDK bridge: freezes one exact stage before the host acquires known owners. */
    public synchronized SelectedRootStage selectRootStage(DocumentId root, TimelineEntry input) {
        return selectRootStage(root, input, null);
    }

    /** Freezes only the exact root-local retained obligation, before any PROCESS mutation. */
    public synchronized SelectedRootStage selectRetainedRootStage(DocumentId root, String expectedWorkIdentity) {
        return selectRootStage(root, null, Objects.requireNonNull(expectedWorkIdentity));
    }

    private SelectedRootStage selectRootStage(DocumentId root, TimelineEntry input, String expectedWorkIdentity) {
        ensureOpen(); Objects.requireNonNull(root);
        RootedCheckpointDriver.Selection selected;
        if (input != null) {
            var entry = journal.requireCanonical(input);
            selected = new RootedCheckpointDriver.Selection(contractsClosureAdapter.captureRoot(root, entry, null), null,
                    CatchUpConsumerScope.owners(RootedStageCapture.owners(root, documents)), false);
        } else {
            var driver = new RootedCheckpointDriver(documents, contractsClosureAdapter, true);
            selected = documents.storedState().sessionIndex().isLogical()
                    ? driver.select(root, owner -> contractsClosureAdapter.rootedJournalEntries(owner, journal))
                    : driver.select(root, journal.entries());
        }
        if (expectedWorkIdentity != null && (selected.localHistorical() == null
                || !expectedWorkIdentity.equals(selected.localHistorical().work().workIdentity())))
            throw new IllegalArgumentException("Selected root no longer requires this exact retained work: " + expectedWorkIdentity);
        pendingRootStage = new SelectedRootStage(root, selected, RootedStageCapture.describe(root, selected, documents), input == null);
        return pendingRootStage;
    }

    /** Observes current global readiness without executing or publishing a stage. */
    public synchronized ProcessingReadiness auditRootedProcessingReadiness() {
        ensureOpen();
        if (!contractsClosureProfile.rootedCheckpoint()) throw new IllegalStateException("Durable readiness requires the rooted checkpoint profile");
        return rootedStageReadiness(null);
    }

    /** Readiness through the original accepted input, excluding later inputs and their join fences. */
    public synchronized ProcessingReadiness auditRootedProcessingReadinessThrough(TimelineEntry inclusiveEntry) {
        ensureOpen();
        if (!contractsClosureProfile.rootedCheckpoint()) throw new IllegalStateException("Durable readiness requires the rooted checkpoint profile");
        return rootedStageReadiness(journal.requireCanonical(Objects.requireNonNull(inclusiveEntry)).sourceOrderKey());
    }

    private ProcessingReadiness rootedStageReadiness(ExternalOrderKey cutoff) {
        var entries = journal.entries().stream().filter(entry -> cutoff == null || entry.sourceOrderKey().compareTo(cutoff) <= 0).toList();
        var scan = new RootedCheckpointDriver(documents, contractsClosureAdapter, true, cutoff).scan(entries, cutoff);
        return new ProcessingReadiness(scan.quiescent(), !scan.heads().isEmpty());
    }

    /** Freezes the exact managed fair turn, retaining its included publication owners and no future readiness. */
    public synchronized SelectedRootStage selectManagedApplicationStage(String expectedWorkIdentity) {
        ensureOpen();
        String expected = Objects.requireNonNull(expectedWorkIdentity);
        if (!expected.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("expectedWorkIdentity must be a lowercase sha256 identity");
        if (!contractsClosureProfile.rootedCheckpoint())
            throw new IllegalStateException("Durable stages require the rooted checkpoint profile");
        var driver = new RootedCheckpointDriver(documents, contractsClosureAdapter, true);
        var head = contractsRecoveryState.rootedSchedule.next(driver.scan(journal.entries(), null), false, Set.of());
        var next = head == null ? auditNextProcessingSelection() : rootedSelection(head.selection());
        if (head == null || next.kind() != ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION
                || !next.managedEpochApplicationWork().map(ManagedEpochApplicationWork::workIdentity).filter(expected::equals).isPresent())
            throw processingSelectionMismatch("MANAGED_EPOCH_APPLICATION", next, expected);
        pendingRootStage = new SelectedRootStage(head.root(), head.selection(),
                RootedStageCapture.describe(head.root(), head.selection(), documents), true);
        return pendingRootStage;
    }

    /** Freezes one global journal fair turn. Absence is a scheduling observation, not global quiescence. */
    public synchronized Optional<SelectedRootStage> selectJournalStage() {
        return selectJournalStageThrough(null);
    }

    /** Freezes a global journal turn through one already accepted exact input without reauthoring it. */
    public synchronized Optional<SelectedRootStage> selectJournalStageThrough(TimelineEntry inclusiveEntry) {
        ensureOpen();
        if (!contractsClosureProfile.rootedCheckpoint())
            throw new IllegalStateException("Durable stages require the rooted checkpoint profile");
        var cutoffEntry = inclusiveEntry == null ? null : journal.requireCanonical(inclusiveEntry);
        var cutoff = cutoffEntry == null ? null : cutoffEntry.sourceOrderKey();
        var entries = journal.entries().stream().filter(entry -> cutoff == null || entry.sourceOrderKey().compareTo(cutoff) <= 0).toList();
        var driver = new RootedCheckpointDriver(documents, contractsClosureAdapter, true, cutoff);
        var head = contractsRecoveryState.rootedSchedule.next(driver.scan(entries, cutoff), false, Set.of());
        if (head == null || rootedSelection(head.selection()).kind() != ProcessingSelection.Kind.JOURNAL) {
            pendingRootStage = null;
            return Optional.empty();
        }
        var captured = RootedStageCapture.describe(head.root(), head.selection(), documents);
        var context = cutoffEntry == null ? captured : new blue.coordination.api.ProcessingStageContext(captured.kind(),
                captured.root(), captured.causes(), captured.entryOwners(), captured.invocationIdentities(), cutoffEntry.blueId());
        pendingRootStage = new SelectedRootStage(head.root(), head.selection(), context, true);
        return Optional.of(pendingRootStage);
    }

    /** Freezes the next causal stage no later than an original accepted input; later inputs are not readiness evidence. */
    public synchronized SelectedRootStage selectRootStageThrough(DocumentId root, TimelineEntry inclusiveEntry) {
        ensureOpen(); Objects.requireNonNull(root);
        var cutoff = journal.requireCanonical(Objects.requireNonNull(inclusiveEntry));
        var driver = new RootedCheckpointDriver(documents, contractsClosureAdapter, true, cutoff.sourceOrderKey());
        var selected = driver.select(root, owner -> contractsClosureAdapter.rootedJournalEntries(owner, journal, cutoff.sourceOrderKey()));
        var captured = RootedStageCapture.describe(root, selected, documents);
        var context = new blue.coordination.api.ProcessingStageContext(captured.kind(), captured.root(), captured.causes(),
                captured.entryOwners(), captured.invocationIdentities(), cutoff.blueId());
        pendingRootStage = new SelectedRootStage(root, selected, context, true);
        return pendingRootStage;
    }

    /** Single-use, thread-affine frozen selection. No other engine work may intervene. */
    public final class SelectedRootStage {
        private final Thread owner = Thread.currentThread();
        private final DocumentId root;
        private final RootedCheckpointDriver.Selection selected;
        private final blue.coordination.api.ProcessingStageContext context;
        private final boolean scheduled;
        private SelectedRootStage(DocumentId root, RootedCheckpointDriver.Selection selected,
                blue.coordination.api.ProcessingStageContext context, boolean scheduled) {
            this.root = root; this.selected = selected; this.context = context; this.scheduled = scheduled;
        }
        public blue.coordination.api.ProcessingStageContext context() { return context; }
        public ProcessingDrainReceipt execute() {
            synchronized (DefaultCoordinationEngine.this) {
                if (closed || pendingRootStage != this || owner != Thread.currentThread())
                    throw new IllegalStateException("Stage selection is retired, used or belongs to another thread");
                pendingRootStage = null;
                try {
                    var completed = executeRootSelection(selected, System.nanoTime());
                    var anchor = selected.localHistorical() != null ? selected.localHistorical().root() : root;
                    if (scheduled) contractsRecoveryState.rootedSchedule.completed(anchor, selected, completed);
                    return completed;
                } catch (RuntimeException | Error failure) {
                    try { DefaultCoordinationEngine.this.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }
        public List<DocumentId> resultOwners(ProcessingDrainReceipt completed) { return RootedStageCapture.resultOwners(context, completed); }
        public boolean invalidatesSelection(ProcessingDrainReceipt completed) { return RootedStageCapture.invalidatesSelection(completed); }
    }

    /**
     * Executes one selected LIVE, retained-local or managed historical stage
     * without inspecting future readiness. This is an internal bridge for the
     * durable host boundary; the legacy convenience drain retains lookahead.
     * @param root authoritative root requesting progress
     * @param expectedLocalWork optional exact retained-local work identity
     * @return current-stage evidence, not a claim that the root is quiescent
     */
    public synchronized ProcessingDrainReceipt processNextRootStage(DocumentId root, String expectedLocalWork) {
        return processNextRoot(root, expectedLocalWork, false);
    }

    private ProcessingDrainReceipt processNextRoot(DocumentId root, String expectedLocalWork, boolean inspectReadiness) {
        try {
            ensureOpen();
            long started = System.nanoTime();
            var driver = new RootedCheckpointDriver(documents, contractsClosureAdapter, !inspectReadiness);
            Objects.requireNonNull(root, "root");
            RootedCheckpointDriver.Selection next = !inspectReadiness && documents.storedState().sessionIndex().isLogical()
                    ? driver.select(root, selected -> contractsClosureAdapter.rootedJournalEntries(selected, journal))
                    : driver.select(root, journal.entries());
            if (expectedLocalWork != null && (next.localHistorical() == null
                    || !expectedLocalWork.equals(next.localHistorical().work().workIdentity()))) {
                throw new IllegalArgumentException("Selected root no longer requires this exact retained work: " + expectedLocalWork);
            }
            var anchor = next.localHistorical() != null ? next.localHistorical().root()
                    : !inspectReadiness ? root : documents.sessionIds().stream()
                    .filter(id -> next.consumers().contains(id))
                    .min(EmbeddingBinding.DOCUMENT_ORDER).orElse(root);
            var completed = executeRootSelection(next, started);
            contractsRecoveryState.rootedSchedule.completed(anchor, next, completed);
            return inspectReadiness ? rootedReadiness(root, completed, started) : completed;
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    private ProcessingDrainReceipt executeScheduledRoot(RootedCheckpointDriver.Head head) {
        var result = executeRootSelection(head.selection(), System.nanoTime());
        contractsRecoveryState.rootedSchedule.completed(head.root(), head.selection(), result);
        return result;
    }

    private ProcessingDrainReceipt executeRootSelection(RootedCheckpointDriver.Selection next, long started) {
        if (next.live() != null) return executeRootBatch(next.live(), started);
        if (next.localHistorical() != null && next.historical() == null) {
            var step = next.localHistorical();
            var batch = contractsClosureAdapter.localHistoryBatch(step);
            var completed = executeRootBatch(batch, started);
            var attempts = completed.contractsAttemptsFor(step.anchor().blueId());
            // A terminal LIVE rejection consumes its input, but failed retained work remains pending.
            boolean published = !attempts.isEmpty() && attempts.stream().allMatch(ContractsClosureDispatchAttempt::published);
            return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null,
                    published, completed.paused(), completed.committedProcessTransitions(),
                    completed.elapsedNanos()).withRootedRetainedAttempts(attempts
                            .stream().map(attempt -> new ProcessingDrainReceipt.RootedRetainedAttempt(
                                    step.root(), step.work(), attempt)).toList());
        }
        if (next.historical() != null) {
            try {
                var outcome = next.localHistorical() == null
                        ? contractsClosureAdapter.executeManagedEpochApplication(next.historical(), next.consumers())
                        : contractsClosureAdapter.executeRootedJoinApplication(next.historical(), next.consumers(), next.localHistorical());
                return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null,
                        outcome.published(), false, outcome.published() && !outcome.replayed()
                                ? RootedResultScope.processTransitionCount(outcome.attempt().processResult()) : 0L,
                        System.nanoTime() - started, outcome.receipt().stream().toList(),
                        List.of(managedApplicationAttempt(outcome)), List.of());
            } catch (ManagedEpochEvidenceException failure) {
                documents.recordManagedEpochEvidenceFailure(failure);
                return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null, false, false, 0L,
                        System.nanoTime() - started, List.of(), List.of(), List.of(new ManagedEpochEvidenceFailure(
                                failure.work(), failure.planStatus(), failure.code(), failure.message())));
            }
        }
        return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null, !next.blocked(), false,
                0L, System.nanoTime() - started);
    }

    /** Recomputes pending work after the selected step; the one-step budget is not a resource wait. */
    private ProcessingDrainReceipt rootedReadiness(DocumentId root, ProcessingDrainReceipt completed, long started) {
        if (!completed.quiescent()) return completed;
        inject(FailurePoint.BEFORE_ROOTED_READINESS);
        RootedCheckpointDriver.Selection remaining = new RootedCheckpointDriver(documents, contractsClosureAdapter)
                .select(root, journal.entries());
        boolean pending = remaining.live() != null || remaining.historical() != null || remaining.localHistorical() != null;
        boolean quiescent = !pending && !remaining.blocked();
        return new ProcessingDrainReceipt(completed.processedEntries(), completed.outcomesByEntry(),
                completed.contractsAttemptsByEntry(), completed.processedThrough().orElse(null),
                quiescent, pending && !remaining.blocked(), completed.committedProcessTransitions(),
                System.nanoTime() - started, completed.managedEpochApplications(),
                completed.managedEpochApplicationAttempts(), completed.managedEpochEvidenceFailures())
                .withRootedRetainedAttempts(completed.rootedRetainedAttempts());
    }

    @Override
    public synchronized ProcessingDrainReceipt drainJournal(
            CoordinationEngine.DrainBudget budget) {
        return drainJournalThroughOrder(null, budget);
    }

    /**
     * Drains one ordinary selection at or before an exact retained input.
     * Managed application turns remain separately scheduled.
     * @param inclusiveEntry exact retained entry defining the inclusive cutoff
     * @param budget deterministic between-invocation limits
     * @return the complete bounded journal result
     */
    public synchronized ProcessingDrainReceipt drainJournalThrough(
            TimelineEntry inclusiveEntry, CoordinationEngine.DrainBudget budget) {
        ensureOpen();
        return drainJournalThroughOrder(journal.requireCanonical(
                Objects.requireNonNull(inclusiveEntry, "inclusiveEntry")).sourceOrderKey(), budget);
    }

    private ProcessingDrainReceipt drainJournalThroughOrder(
            ExternalOrderKey cutoff, CoordinationEngine.DrainBudget budget) {
        ensureOpen(); requireGlobalDrainSupported();
        CoordinationEngine.DrainBudget selected = Objects.requireNonNull(
                budget, "budget");
        ProcessingSelection next = auditNextProcessingSelection();
        if (next.kind() != ProcessingSelection.Kind.JOURNAL) {
            throw processingSelectionMismatch(
                    "JOURNAL", next, null);
        }
        try {
            ProcessingDrainReceipt drained = drainContracts(
                    cutoff,
                    new CoordinationEngine.DrainBudget(
                            selected.maxCommittedProcessTransitions(), 1L),
                    false);
            boolean journalSelected = !drained.processedEntries().isEmpty()
                    || !drained.contractsAttemptsByEntry().isEmpty();
            if (!journalSelected
                    && nextFairManagedEpochApplicationWork().isPresent()) {
                // The ordinary phase inspected only already-terminal,
                // inactive, or resource-blocked journal rows. A compatibility
                // drain would now fall through to its managed phase in this
                // same call; retain that continuation as the next sliced turn.
                contractsRecoveryState.managedEpochTurn(true);
            }
            return drained;
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    @Override
    public synchronized ProcessingDrainReceipt
            drainManagedEpochApplication(String expectedWorkIdentity) {
        ensureOpen(); requireGlobalDrainSupported();
        String expected = Objects.requireNonNull(
                expectedWorkIdentity, "expectedWorkIdentity");
        if (!expected.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "expectedWorkIdentity must be a lowercase sha256 identity");
        }
        // Retain the exact head validated here for execution. Fairness is still
        // evaluated on every command, not cached as part of the derived scan.
        var driver = contractsClosureProfile.rootedCheckpoint()
                ? new RootedCheckpointDriver(documents, contractsClosureAdapter) : null;
        var head = driver == null ? null : contractsRecoveryState.rootedSchedule.next(
                driver.scan(journal.entries(), null), false, Set.of());
        ProcessingSelection next = head == null ? auditNextProcessingSelection()
                : rootedSelection(head.selection());
        String actual = next.managedEpochApplicationWork()
                .map(ManagedEpochApplicationWork::workIdentity)
                .orElse(null);
        if (next.kind()
                        != ProcessingSelection.Kind
                                .MANAGED_EPOCH_APPLICATION
                || !expected.equals(actual)) {
            throw processingSelectionMismatch(
                    "MANAGED_EPOCH_APPLICATION", next, expected);
        }
        if (contractsClosureProfile.rootedCheckpoint()) {
            long started = System.nanoTime();
            var completed = executeScheduledRoot(Objects.requireNonNull(head));
            if (!completed.quiescent()) return completed;
            var remaining = driver.scan(journal.entries(), null);
            return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null,
                    remaining.quiescent(), !remaining.heads().isEmpty(), completed.committedProcessTransitions(),
                    System.nanoTime() - started, completed.managedEpochApplications(),
                    completed.managedEpochApplicationAttempts(), completed.managedEpochEvidenceFailures())
                .withRootedRetainedAttempts(completed.rootedRetainedAttempts());
        }
        try {
            ProcessingDrainReceipt drained = drainContracts(
                    null, new CoordinationEngine.DrainBudget(1L, 1L));
            boolean selected = drained.managedEpochApplicationAttempts()
                            .stream()
                            .map(ManagedEpochApplicationAttempt::work)
                            .map(ManagedEpochApplicationWork::workIdentity)
                            .anyMatch(expected::equals)
                    || drained.managedEpochEvidenceFailures().stream()
                            .map(ManagedEpochEvidenceFailure::work)
                            .map(ManagedEpochApplicationWork::workIdentity)
                            .anyMatch(expected::equals);
            if (!selected || !drained.processedEntries().isEmpty()) {
                throw new IllegalStateException(
                        "Targeted managed drain selected different work");
            }
            return drained;
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    private static CoordinationException processingSelectionMismatch(
            String expectedKind,
            ProcessingSelection actual,
            String expectedWorkIdentity) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("expectedKind", expectedKind);
        details.put("actualKind", actual.kind().name());
        if (expectedWorkIdentity != null) {
            details.put("expectedWorkIdentity", expectedWorkIdentity);
        }
        actual.managedEpochApplicationWork().ifPresent(work ->
                details.put("actualWorkIdentity", work.workIdentity()));
        return new CoordinationException(
                CoordinationErrorCode.PROCESSING_SELECTION_MISMATCH,
                "Targeted processing call disagrees with the retained fair "
                        + "selection",
                null,
                details);
    }

    @Override
    public synchronized ProcessingDrainReceipt drainThrough(
            ExternalOrderKey inclusiveCutoff) {
        try {
            ensureOpen(); requireGlobalDrainSupported();
            if (contractsJournalCoordinator != null) {
                return drainContracts(
                        Objects.requireNonNull(
                                inclusiveCutoff, "inclusiveCutoff"),
                        CoordinationEngine.DrainBudget.unlimited());
            }
            return drainCoordinator.drain(Objects.requireNonNull(
                    inclusiveCutoff, "inclusiveCutoff"),
                    CoordinationEngine.DrainBudget.unlimited());
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    synchronized void failOnceAt(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        failureInjector = new Consumer<>() {
            private boolean pending = true;

            @Override
            public void accept(FailurePoint observed) {
                if (pending && observed == point) {
                    pending = false;
                    throw new InjectedFailureException(point);
                }
            }
        };
    }

    synchronized void clearFailureInjection() {
        failureInjector = ignored -> { };
    }

    synchronized void restartFromStores() {
        ensureOpen();
        documents.clearRepresentationVerifications();
        clearFailureInjection();
        routeIndex.clear();
        documents.sessions().stream()
                .sorted(Comparator.comparing(DocumentSession::documentId,
                        EmbeddingBinding.DOCUMENT_ORDER))
                .forEach(session -> routeIndex.replace(
                        session.documentId(),
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
        if (drainCoordinator != null) drainCoordinator = drainCoordinator.restartFromStores(this::inject);
        if (contractsClosureAdapter != null) {
            // Retain ContractsRecoveryState, including the deterministic
            // managed-consumer deferral round, across route reconstruction.
            contractsClosureAdapter
                    .resetManagedEpochReconciliationAfterRouteRebuild();
            contractsActiveSourceTimelines.rebuild(documents);
            contractsFeederCoordinator = createContractsFeederCoordinator();
            contractsJournalCoordinator = createContractsJournalCoordinator();
        }
    }

    synchronized ContractsRootFeederCoordinator contractsFeederCoordinator() {
        ensureOpen();
        if (contractsFeederCoordinator == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        return contractsFeederCoordinator;
    }

    synchronized ContractsClosureAdmissionAdapter
            contractsClosureAdmissionAdapter() {
        ensureOpen();
        if (contractsClosureAdmissionAdapter == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        return contractsClosureAdmissionAdapter;
    }

    synchronized List<String> instanceRetirementBlocks(blue.coordination.api.DocumentInstanceRef instance) {
        return InstanceRetirementProjection.blocks(documents.storedState(), instance.documentId(),
                rootedSourceDiscoveries.pendingForRetirement(instance),
                plan -> RetainedInstancePlan.witnessed(instance, plan, documents, contractsClosureAdapter, journal),
                documents.retainedIncomingSources(instance));
    }

    synchronized void retireIndependentInstance(blue.coordination.api.DocumentInstanceRef instance) {
        var owner = instance.documentId();
        documents.retireIndependentOwner(owner, documents.retainedIncomingSources(instance));
        routeIndex.remove(owner); contractsActiveSourceTimelines.removeLogicalRoot(owner);
        Objects.requireNonNull(logicalControl, "Instance retirement requires logical storage").retire(owner);
    }

    synchronized boolean isPublicRoot(DocumentId owner) { return contractsClosureProfile.publicRoots().contains(owner); }

    synchronized void startInstanceAtBasis(DocumentSession basis, ManagedEpochReceiptStore.DocumentHistory receipts, boolean publicRoot) {
        documents.startInstanceAtBasis(basis, receipts);
        routeIndex.replace(basis.documentId(), basis.layout().routingSurface(), basis.rootedView().routes(basis.documentId()));
        if (publicRoot) contractsActiveSourceTimelines.addPublicRoots(List.of(basis.documentId()));
        contractsActiveSourceTimelines.refresh(List.of(basis.documentId()), documents);
    }

    synchronized ContractsClosureAdapter contractsClosureAdapter() {
        ensureOpen();
        if (contractsClosureAdapter == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        return contractsClosureAdapter;
    }

    synchronized ContractsJournalDrainCoordinator
            contractsJournalCoordinator() {
        ensureOpen();
        if (contractsJournalCoordinator == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        return contractsJournalCoordinator;
    }

    synchronized void makeHistoricalUnavailable(String diagnostic) {
        ensureOpen();
        journal.makeHistoricalUnavailable(Objects.requireNonNull(
                diagnostic, "diagnostic"));
    }

    synchronized void makeHistoricalAvailable() {
        ensureOpen();
        journal.makeHistoricalAvailable();
    }

    synchronized void invalidateHistoricalEvidence(String diagnostic) {
        ensureOpen();
        journal.invalidateHistoricalEvidence(Objects.requireNonNull(
                diagnostic, "diagnostic"));
    }

    synchronized DocumentSession session(String documentId) {
        ensureOpen();
        return documents.require(DocumentId.of(documentId));
    }

    synchronized Node currentRoot(String documentId) {
        ensureOpen();
        return session(documentId).layout().reconstructRoot();
    }

    synchronized Node value(String documentId, String path) {
        Node selected = NodePathEditor.getOrNull(
                currentRoot(documentId), path);
        if (selected == null) {
            throw new IllegalArgumentException(
                    "No value at " + documentId + path);
        }
        return selected.clone();
    }

    synchronized List<DocumentRevision> history(String documentId) {
        return session(documentId).revisions();
    }

    synchronized List<TemporalCatchUpEvidence> catchUpEvidence() {
        requireLegacyOnly("catchUpEvidence");
        Map<String, CatchUpBarrier.Status> statusByBinding =
                new LinkedHashMap<>();
        drainCoordinator.barrierEvidence().forEach(barrier ->
                barrier.bindingIds().forEach(bindingId ->
                        statusByBinding.put(bindingId, barrier.status())));
        return drainCoordinator.graphSnapshot().bindings().stream()
                .map(binding -> new TemporalCatchUpEvidence(
                        binding.parentDocumentId(),
                        binding.childDocumentId(),
                        binding.absolutePath(),
                        drainCoordinator.cursor(binding.bindingId())
                                .appliedChildEpoch(),
                        statusByBinding.getOrDefault(
                                binding.bindingId(),
                                CatchUpBarrier.Status.COMPLETE).name(),
                        binding.activationGeneration()))
                .toList();
    }

    synchronized Set<String> effectiveTimelineIds(String documentId) {
        ensureOpen();
        if (contractsClosureAdapter != null) {
            return contractsSourceSurface(DocumentId.of(documentId))
                    .timelineIds();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(drainCoordinator.effectiveTimelineIds(
                DocumentId.of(documentId)));
        return Collections.unmodifiableSet(result);
    }

    synchronized Map<String, String> embeddedDocuments(
            String documentId) {
        ensureOpen();
        if (contractsClosureAdapter != null) {
            Map<String, String> result = new LinkedHashMap<>();
            closureChildren(DocumentId.of(documentId)).forEach(
                    (path, child) -> result.put(path, child.value()));
            return Collections.unmodifiableMap(result);
        }
        Map<String, String> result = new LinkedHashMap<>();
        drainCoordinator.bindingsForParent(DocumentId.of(documentId))
                .forEach(binding -> result.put(
                        binding.absolutePath(),
                        binding.childDocumentId().value()));
        return Collections.unmodifiableMap(result);
    }

    synchronized EngineMetrics.MetricsSnapshot metricsSnapshot() {
        return metrics.snapshot();
    }
    synchronized Map<String, Long> workCounters() {
        return metrics.publicSnapshot().counters();
    }
    synchronized void observeTransitions(
            Consumer<SequentialDrainCoordinator.TransitionTrace> observer) {
        requireLegacyOnly("observeTransitions");
        drainCoordinator.observeTransitions(observer);
    }
    synchronized BlueCacheStats languageCacheStats() {
        ensureOpen();
        return runtime.cacheStats();
    }

    synchronized int journalSize() {
        return journal.size();
    }

    synchronized int wholeObjectCount() {
        return objects.size();
    }

    synchronized int documentCount() {
        return documents.size();
    }

    synchronized int routeRowCount() {
        return routeIndex.rowCount();
    }

    synchronized long logicalClockMicros() {
        return currentLogicalClock();
    }

    synchronized InMemoryDocumentStore documents() {
        return documents;
    }

    synchronized WholeObjectStore objects() { return objects; }

    synchronized BlueRuntime runtime() { return runtime; }

    synchronized void inject(FailurePoint point) {
        failureInjector.accept(Objects.requireNonNull(point, "point"));
    }

    EngineMetrics engineMetrics() {
        return metrics;
    }

    @Override
    public synchronized DocumentSnapshot document(DocumentId documentId) {
        DocumentSession session = requireDocument(documentId);
        boolean pendingTopLevelAdmission = drainCoordinator != null && drainCoordinator
                .hasPendingTopLevelAdmission(session.documentId());
        String readinessFailure = pendingTopLevelAdmission
                ? "top-level historical admission remains pending"
                : contractsClosureAdapter == null
                        ? drainCoordinator.applicationReadinessFailure(session)
                        : contractsReadinessFailure(session);
        boolean mayReadEarlierReadyHead = session.status()
                        == SessionStatus.CATCHING_UP
                && !pendingTopLevelAdmission;
        if (readinessFailure != null
                && !mayReadEarlierReadyHead) {
            metrics.increment("temporal.applicationReadsRejected");
            throw new CoordinationException(
                    CoordinationErrorCode.DOCUMENT_NOT_READY,
                    "Document " + documentId + " is not application-ready: "
                            + readinessFailure,
                    null,
                    Map.of("documentId", documentId.value(),
                            "status", session.status().name(),
                            "epoch", Long.toString(session.epoch()),
                            "readyEpoch", Long.toString(session.readyEpoch()),
                            "graphPublishedEpoch", Long.toString(
                                    session.graphPublishedEpoch())));
        }
        return snapshot(session, true);
    }

    /** Reports the installed native instance storage capability without reading any record. */
    public synchronized boolean hasDocumentInstanceStorage() { ensureOpen(); return documents.hasInstanceStorage(); }

    /** Native SDK observer binding; resident engines have no execution-instance storage. */
    public synchronized Optional<blue.coordination.api.DocumentInstanceRef> activeDocumentInstance(DocumentId owner) {
        ensureOpen(); return documents.activeInstance(owner);
    }
    /** Authenticates a retained SDK observer without selecting today's binding. */
    public synchronized void requireRetainedDocumentInstance(blue.coordination.api.DocumentInstanceRef ref) {
        ensureOpen(); documents.requireRetainedInstance(ref);
    }

    /** Immutable original admission outcome, present only after that exact instance retired. */
    public record ArchivedAdmissionDocument(DocumentSnapshot snapshot, List<DocumentRevision> revisions, ExactValue authored) {
        public ArchivedAdmissionDocument { revisions = List.copyOf(revisions); }
    }

    /** SDK reconciliation bridge; never resolves an original receipt through a replacement head. */
    public synchronized Optional<ArchivedAdmissionDocument> retiredOriginalAdmission(String identity, DocumentId document) {
        ensureOpen();
        return documents.retiredOriginalAdmission(identity, document, session ->
                new ArchivedAdmissionDocument(snapshot(session, true), session.revisions(), session.revision(0).before().orElseThrow()));
    }

    @Override
    public synchronized DocumentSnapshot auditDocument(
            DocumentId documentId) {
        return snapshot(requireDocument(documentId), false);
    }

    @Override
    public synchronized Optional<ManagedEpochReceipt> auditManagedEpoch(
            DocumentId documentId,
            long epoch) {
        ensureOpen();
        requireDocument(Objects.requireNonNull(documentId, "documentId"));
        if (epoch < 0L
                || epoch > MultiDocumentPublicationTransaction
                        .MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "epoch must be a non-negative JSON safe integer");
        }
        return documents.managedEpochReceipt(documentId, epoch);
    }

    @Override
    public synchronized List<ManagedEpochReceipt> auditManagedEpochs(
            DocumentId documentId) {
        ensureOpen();
        requireDocument(Objects.requireNonNull(documentId, "documentId"));
        return documents.managedEpochReceipts(documentId);
    }

    @Override
    public synchronized Optional<ManagedEpochReceipt>
            auditManagedEpochReceipt(String receiptIdentity) {
        ensureOpen();
        return documents.managedEpochReceipt(Objects.requireNonNull(
                receiptIdentity, "receiptIdentity"));
    }

    @Override
    public synchronized Optional<ManagedOccurrenceCatchUpPlan>
            auditManagedCatchUpPlan(String planIdentity) {
        ensureOpen();
        return documents.catchUpPlan(Objects.requireNonNull(
                planIdentity, "planIdentity"));
    }

    @Override
    public synchronized List<ManagedOccurrenceCatchUpPlan>
            auditManagedCatchUpPlans(DocumentId consumerDocumentId) {
        ensureOpen();
        requireDocument(Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId"));
        return documents.catchUpPlans(consumerDocumentId);
    }

    @Override
    public synchronized Optional<ManagedCatchUpBarrier>
            auditManagedCatchUpBarrier(String barrierIdentity) {
        ensureOpen();
        return documents.catchUpBarrier(Objects.requireNonNull(
                barrierIdentity, "barrierIdentity"));
    }

    @Override
    public synchronized Optional<ManagedEpochApplicationWork>
            auditManagedEpochApplicationWork(String workIdentity) {
        ensureOpen();
        String selected = Objects.requireNonNull(workIdentity, "workIdentity");
        var independent = documents.catchUpWork(selected);
        if (independent.isPresent() || !contractsClosureProfile.rootedCheckpoint()) return independent;
        return new RootedCheckpointDriver(documents, contractsClosureAdapter).scan(journal.entries(), null)
                .heads().stream().map(head -> head.selection().localHistorical()).filter(Objects::nonNull)
                .map(RootedLocalHistory.Step::work).filter(work -> selected.equals(work.workIdentity())).findFirst();
    }

    @Override
    public synchronized Map<String, Optional<ManagedEpochApplicationWork>>
            auditManagedEpochApplicationWorks(List<String> workIdentities) {
        ensureOpen();
        var identities = List.copyOf(Objects.requireNonNull(workIdentities, "workIdentities"));
        var result = new LinkedHashMap<String, Optional<ManagedEpochApplicationWork>>();
        for (String identity : identities) result.computeIfAbsent(identity, documents::catchUpWork);
        if (contractsClosureProfile.rootedCheckpoint() && result.values().stream().anyMatch(Optional::isEmpty)) {
            // One synchronized observation: no cache survives into another call,
            // where document, journal or provider evidence may have changed.
            var scan = new RootedCheckpointDriver(documents, contractsClosureAdapter).scan(journal.entries(), null);
            for (var head : scan.heads()) {
                var local = head.selection().localHistorical();
                if (local == null) continue;
                String identity = local.work().workIdentity();
                // Keep direct managed-index precedence and first-match behavior
                // identical to the individual query. Not-next is not absence.
                if (result.containsKey(identity) && result.get(identity).isEmpty())
                    result.put(identity, Optional.of(local.work()));
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    @Override
    public synchronized ProcessingSelection auditNextProcessingSelection() {
        return auditNextProcessingSelection(ProcessingAvailability.none());
    }

    @Override
    public synchronized ProcessingSelection auditNextProcessingSelection(
            ProcessingAvailability availability) {
        ensureOpen();
        ProcessingAvailability supplied = Objects.requireNonNull(
                availability, "availability");
        if (contractsJournalCoordinator == null) {
            return ProcessingSelection.none();
        }
        if (contractsClosureProfile.rootedCheckpoint()) {
            var scan = new RootedCheckpointDriver(documents, contractsClosureAdapter).scan(journal.entries(), null);
            var selected = contractsRecoveryState.rootedSchedule.next(scan, supplied.journalAdmissionAvailable(), Set.of());
            if (selected == null) return supplied.journalAdmissionAvailable()
                    || contractsJournalCoordinator.hasCompletableRootedTransport(scan)
                    ? ProcessingSelection.journal() : ProcessingSelection.none();
            return rootedSelection(selected.selection());
        }
        Optional<ManagedEpochApplicationWork> managed =
                nextFairManagedEpochApplicationWork();
        boolean journal = contractsJournalCoordinator
                .hasPendingJournalTurn()
                || supplied.journalAdmissionAvailable();
        if (contractsRecoveryState.managedEpochTurn()
                && managed.isPresent()) {
            return ProcessingSelection.managedEpochApplication(
                    managed.orElseThrow());
        }
        if (journal) {
            return ProcessingSelection.journal();
        }
        return managed
                .map(ProcessingSelection::managedEpochApplication)
                .orElseGet(ProcessingSelection::none);
    }

    private static ProcessingSelection rootedSelection(RootedCheckpointDriver.Selection next) {
        if (next.localHistorical() != null && next.historical() == null)
            return ProcessingSelection.rootedRetained(next.localHistorical().root(), next.localHistorical().work());
        return next.historical() == null ? ProcessingSelection.journal()
                : ProcessingSelection.managedEpochApplication(next.historical());
    }

    /** Mirrors managed round rollover without mutating the retained round. */
    private Optional<ManagedEpochApplicationWork>
            nextFairManagedEpochApplicationWork() {
        Set<DocumentId> deferred = contractsRecoveryState
                .deferredManagedConsumers();
        Set<DocumentId> isolated = contractsRecoveryState
                .isolatedManagedConsumers();
        LinkedHashSet<DocumentId> excluded = new LinkedHashSet<>(deferred);
        excluded.addAll(isolated);
        Optional<ManagedEpochApplicationWork> selected = documents
                .nextCatchUpWorkExcluding(excluded);
        if (selected.isPresent()) {
            return selected;
        }
        if (!deferred.isEmpty()) {
            selected = documents.nextCatchUpWorkExcluding(isolated);
            if (selected.isPresent()) {
                return selected;
            }
        }
        if (isolated.isEmpty()) {
            return Optional.empty();
        }
        // Every independently progressing lane is exhausted. Expose the
        // canonical isolated lane as the next explicit retry frontier without
        // mutating either retained round during an audit.
        return documents.nextCatchUpWorkExcluding(Set.of());
    }

    /** Projects only the exact receipt already published with this application. */
    private ManagedSurfacePublicationEvidence committedManagedApplicationSurface(
            ContractsClosureAdapter.ManagedApplicationOutcome outcome) {
        if (!outcome.published()) {
            return ManagedSurfacePublicationEvidence.empty();
        }
        ContractsClosurePublicationReceipt retained = documents
                .closureReceiptForApplication(outcome.receipt().orElseThrow())
                .orElseThrow(() -> new IllegalStateException(
                        "Committed managed application is missing publication evidence"));
        var application = outcome.receipt().orElseThrow();
        var result = retained.attempt().processResult();
        if (!retained.commits()
                || !result.invocationIdentity().equals(application.contractsInvocationIdentity())
                || !result.outputClosureIdentity().equals(application.contractsResultIdentity())
                || !result.platformCommitCompanion().companionIdentity().equals(application.commitCompanionIdentity())) {
            throw new IllegalStateException("Managed application publication evidence binding mismatch");
        }
        return retained.managedSurfaceEvidence();
    }

    @Override
    public synchronized Optional<ManagedEpochApplicationReceipt>
            auditManagedEpochApplicationReceipt(
                    String applicationReceiptIdentity) {
        ensureOpen();
        return documents.catchUpApplication(Objects.requireNonNull(
                applicationReceiptIdentity,
                "applicationReceiptIdentity"));
    }

    @Override
    public synchronized Optional<ManagedDocumentReadiness>
            auditManagedDocumentReadiness(DocumentId documentId) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        if (documents.find(selected).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(documents.managedReadiness(selected));
    }

    /** Whether this document currently feeds an incomplete catch-up plan. */
    public synchronized boolean hasActiveManagedCatchUpFrom(
            DocumentId sourceDocumentId) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        requireDocument(selected);
        return documents.hasActiveCatchUpFrom(selected);
    }

    /** Reads one retained managed occurrence without opening document heads. */
    public synchronized Optional<ManagedOccurrenceAuditView>
            auditManagedOccurrence(
                    DocumentId sourceDocumentId,
                    String sourcePath) {
        ensureOpen();
        return documents.occurrenceInventory()
                .find(sourceDocumentId, sourcePath)
                .map(row -> new ManagedOccurrenceAuditView(
                        DocumentId.of(row.targetDocumentId().value()),
                        row.activationGeneration(),
                        row.active()));
    }

    /** Reads the current processor-compiled operation routes without mutation. */
    public synchronized List<OperationRouteAuditView> auditOperationRoutes(
            DocumentId documentId) {
        ensureOpen();
        return requireDocument(Objects.requireNonNull(
                        documentId, "documentId"))
                .layout()
                .routingSurface()
                .operationDefinitions()
                .stream()
                .map(definition -> new OperationRouteAuditView(
                        definition.scopePath(),
                        definition.operation(),
                        definition.channelKey(),
                        Optional.ofNullable(definition.requestPattern())
                                .map(ExactValue::fromFrozen),
                        definition.sources().stream()
                                .map(source ->
                                        new OperationRouteSourceAuditView(
                                                source.timelineId(),
                                                source.actorId()))
                                .toList()))
                .toList();
    }

    @Override
    public synchronized List<EmbeddedCollectionPlanningAudit>
            auditEmbeddedCollections(DocumentId documentId) {
        ensureOpen();
        return requireDocument(Objects.requireNonNull(
                        documentId, "documentId"))
                .layout()
                .plan()
                .collectionAudits();
    }

    private DocumentSession requireDocument(DocumentId documentId) {
        ensureOpen();
        try {
            return documents.require(Objects.requireNonNull(
                    documentId, "documentId"));
        } catch (RuntimeException failure) {
            throw new CoordinationException(
                    CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                    "Unknown document " + documentId,
                    failure,
                    Map.of("documentId", documentId.value()));
        }
    }

    @Override
    public synchronized List<DocumentRevision> history(DocumentId documentId) {
        return requireDocument(documentId).revisions();
    }

    /**
     * SDK assembly point read. Unlike the explicit history audit this selects
     * one numbered revision, preserving the caller's exact READY/current epoch.
     * @param documentId retained document identity
     * @param epoch exact numbered position, not the ambient latest head
     * @return the immutable revision at that position
     */
    public synchronized DocumentRevision revisionAt(DocumentId documentId, long epoch) {
        return requireDocument(documentId).revision(epoch);
    }

    /**
     * SDK change-summary selector equivalent to the first and last full-history causal match.
     * Includes initialization and imported revisions without a direct Timeline source entry.
     * @param documentId retained document identity
     * @param entryBlueId exact causal entry identity
     * @return no match, one matching revision, or the first and last matching revisions
     */
    /** Projects causal endpoints from the original execution publication, without current-head authority. */
    public synchronized List<DocumentRevision> causalRevisionEndpoints(blue.coordination.api.DocumentInstanceRef observer,
            String publicationIdentity, DocumentId documentId, String entryBlueId) {
        ensureOpen(); return documents.executionCausal(observer, publicationIdentity, documentId, entryBlueId);
    }
    public synchronized List<DocumentRevision> causalRevisionEndpoints(DocumentId documentId, String entryBlueId) {
        return requireDocument(documentId).causalRevisionEndpoints(entryBlueId);
    }

    @Override
    public synchronized Set<String> effectiveTimelineIds(
            DocumentId documentId) {
        requireDocument(documentId);
        return effectiveTimelineIds(documentId.value());
    }

    @Override
    public synchronized CoordinationMetrics metrics() {
        EngineMetrics.MetricsSnapshot current = metrics.publicSnapshot();
        return new CoordinationMetrics(
                current.counters(),
                current.phaseNanos(),
                documents.size(),
                routeIndex.rowCount(),
                journal.size(),
                objects.size(),
                currentLogicalClock());
    }

    private DocumentSnapshot snapshot(
            DocumentSession session,
            boolean applicationReadyHead) {
        Map<String, DocumentId> children = contractsClosureAdapter == null
                ? legacyChildren(session.documentId())
                : applicationReadyHead
                        ? session.readyEmbeddedChildren()
                        : closureChildren(session.documentId());
        EmbeddedOnlyLayout layout = applicationReadyHead
                ? session.readyLayout() : session.layout();
        DocumentRevision revision = applicationReadyHead
                ? session.readyRevision() : session.currentRevision();
        Map<String, ExactValue> physicalObjects = new LinkedHashMap<>();
        layout.scopePaths().forEach(path -> physicalObjects.put(
                path, layout.stored(path)));
        return new DocumentSnapshot(
                session.documentId(),
                revision.epoch(),
                session.status(),
                session.readyThrough(),
                session.authoredInitialBlueId(),
                layout.semanticRoot(),
                physicalObjects,
                children,
                layout.boundaries().stream()
                        .map(EmbeddedBoundary::childScopePath)
                        .toList(),
                layout.routingSurface().definitions().stream()
                        .map(definition -> definition.operation() + "|"
                                + definition.channelKey() + "|"
                                + definition.sources())
                        .toList(),
                layout.physicalObjectCount(),
                layout.processingFrozen().blueId());
    }

    private static RuntimeException translateStartFailure(
            DocumentId documentId,
            RuntimeException failure) {
        if (failure instanceof blue.coordination.api.TimelineJournalStorageException) {
            return failure;
        }
        if (failure instanceof ExecutionEvidenceUnavailableException
                unavailable) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("documentId", documentId.value());
            if (!unavailable.requiredExactBlueIds().isEmpty()) {
                details.put("requiredExactBlueIds", String.join(",",
                        unavailable.requiredExactBlueIds()));
            }
            return new CoordinationException(
                    CoordinationErrorCode.NEEDS_RESOURCES,
                    unavailable.getMessage(),
                    unavailable,
                    details);
        }
        if (failure instanceof SubscriptionSurfaceInvalidException invalid
                && invalid.diagnostic().category()
                == ProcessorErrorCategory.EmbeddedCollectionMustBeObject) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("documentId", documentId.value());
            details.put("collectionPlanningState",
                    EmbeddedCollectionPlanningAudit.State.INVALID_KIND.name());
            return new CoordinationException(
                    CoordinationErrorCode.FROZEN_PROCESSING_FAILED,
                    invalid.getMessage(),
                    invalid,
                    details,
                    null,
                    invalid.diagnostic());
        }
        String message = failure.getMessage() == null
                ? "Document admission failed"
                : failure.getMessage();
        CoordinationErrorCode code = message.startsWith(
                "Duplicate document session")
                ? CoordinationErrorCode.DUPLICATE_DOCUMENT
                : message.contains("cycle")
                ? CoordinationErrorCode.PROCESS_EMBEDDED_CYCLE
                : message.contains("DocumentId")
                || message.contains("document identity")
                ? CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY
                : CoordinationErrorCode.ATOMIC_COMMIT_FAILED;
        return new CoordinationException(
                code,
                message,
                failure,
                Map.of("documentId", documentId.value()));
    }

    private static RuntimeException translateDispatchFailure(
            RuntimeException failure) {
        if (failure instanceof blue.language.processor.NoncommittingExecutionException) {
            return failure;
        }
        if (failure instanceof InjectedFailureException) {
            return failure;
        }
        if (failure instanceof UnsupportedNestedNewLineageException nested) {
            return new CoordinationException(
                    CoordinationErrorCode.UNSUPPORTED_NESTED_NEW_LINEAGE,
                    nested.getMessage(),
                    nested,
                    nested.details());
        }
        String message = failure.getMessage() == null
                ? "Frozen document processing failed"
                : failure.getMessage();
        CoordinationErrorCode code = message.contains(
                "cannot accept live work")
                ? CoordinationErrorCode.DOCUMENT_NOT_READY
                : message.contains("admission evidence")
                || message.contains("Ambiguous historical state")
                ? CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE
                : message.contains("managed child")
                ? CoordinationErrorCode.MANAGED_CHILD_OWNERSHIP_VIOLATION
                : message.contains("subscription")
                ? CoordinationErrorCode.INVALID_SUBSCRIPTION_EVIDENCE
                : CoordinationErrorCode.FROZEN_PROCESSING_FAILED;
        return new CoordinationException(
                code, message, failure, Map.of());
    }

    private Map<String, DocumentId> legacyChildren(DocumentId parent) {
        Map<String, DocumentId> result = new LinkedHashMap<>();
        drainCoordinator.bindingsForParent(parent).forEach(binding ->
                result.put(
                        binding.absolutePath(),
                        binding.childDocumentId()));
        return result;
    }

    private Map<String, DocumentId> closureChildren(DocumentId parent) {
        Map<String, DocumentId> result = new LinkedHashMap<>();
        documents.activeOccurrencesFrom(parent).stream()
                .forEach(row -> {
                    DocumentId child = DocumentId.of(
                            row.targetDocumentId().value());
                    DocumentId duplicate = result.putIfAbsent(
                            row.sourcePath(), child);
                    if (duplicate != null && !duplicate.equals(child)) {
                        throw new IllegalStateException(
                                "Active closure occurrences disagree at "
                                        + parent + row.sourcePath());
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private String contractsReadinessFailure(DocumentSession session) {
        if (session.status() == SessionStatus.BLOCKED) {
            return "session is administratively blocked";
        }
        InMemoryDocumentStore.ManagedReadSnapshot publication;
        try {
            publication = documents.managedReadSnapshot(
                    session.documentId());
        } catch (IllegalArgumentException missing) {
            return "no durable Contracts graph generation";
        }
        InMemoryDocumentStore.DocumentHead head = publication.head();
        if (head.epoch() != session.epoch()
                || !head.blueId().equals(
                        session.currentRepresentation().blueId())) {
            return "session state disagrees with the durable document head";
        }
        blue.language.processor.closure.ComponentSnapshot component =
                publication.componentState();
        if (component == null) {
            return "no durable Contracts component state";
        }
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size();
                index++) {
            if (component.orderedMemberDocumentIds().get(index).value()
                    .equals(session.documentId().value())) {
                return component.orderedMemberBlueIds().get(index)
                        .equals(head.blueId())
                        ? null
                        : "durable component state has a stale document head";
            }
        }
        return "no durable Contracts component state";
    }

    private void requireLegacyOnly(String operation) {
        ensureOpen();
        if (contractsClosureAdapter != null) {
            throw new CoordinationException(
                    CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                    operation + " requires ADMIT_CLOSURE support in "
                            + "Contracts 1.0 mode; legacy admission state is "
                            + "not accepted",
                    null,
                    Map.of("operation", operation));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        documents.clearRepresentationVerifications();
        try {
            if (contractsClosureAdapter != null) {
                try {
                    contractsClosureAdmissionAdapter.close();
                } finally {
                    contractsClosureAdapter.close();
                }
            }
        } finally {
            runtime.close();
        }
    }

    private ContractsRootFeederCoordinator createContractsFeederCoordinator() {
        return new ContractsRootFeederCoordinator(
                contractsClosureAdapter,
                new ContractsRootFeederWindow(
                        contractsRecoveryState.feederWindow),
                contractsClosureAdapter::executeAndPublish,
                this::eligibleThroughCatchUpFrontier);
    }

    private boolean eligibleThroughCatchUpFrontier(
            ContractsClosureAdapter.CohortInvocation invocation) {
        CatchUpPlanStore plans = documents.catchUpPlansSnapshot();
        for (DocumentId member : invocation.existingMemberSet()) {
            if (documents.require(member).status() == SessionStatus.CATCHING_UP) {
                return false;
            }
            // An inactive historical occurrence already creates a temporal
            // dependency. A future source entry cannot extend its captured
            // attachment cutoff or overtake an earlier waiting consumer entry.
            for (ManagedOccurrenceCatchUpPlan plan : plans.plansForSource(member).plans()) {
                ManagedCatchUpBarrier barrier = plans.barrier(plan.barrierIdentity()).barrier();
                if (plan.status() != blue.coordination.api.ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED
                        && barrier.status() != blue.coordination.api.ManagedCatchUpBarrierStatus.COMPLETE
                        && invocation.input().cause() instanceof blue.language.processor.closure.ExternalEventCause cause
                        && cause.sourceOrder().compareTo(barrier.causeOrder()) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private ProcessingDrainReceipt drainContracts(
            ExternalOrderKey inclusiveCutoff,
            CoordinationEngine.DrainBudget budget) {
        return drainContracts(inclusiveCutoff, budget, true);
    }

    private ProcessingDrainReceipt drainContracts(
            ExternalOrderKey inclusiveCutoff,
            CoordinationEngine.DrainBudget budget,
            boolean managedAllowed) {
        if (contractsClosureProfile.rootedCheckpoint()) {
            return new RootedDrainCoordinator(new RootedCheckpointDriver(documents, contractsClosureAdapter),
                    journal, contractsJournalCoordinator, contractsRecoveryState.rootedSchedule,
                    this::executeScheduledRoot)
                    .drain(inclusiveCutoff, budget, managedAllowed);
        }
        long started = System.nanoTime();
        CoordinationEngine.DrainBudget limits = Objects.requireNonNull(
                budget, "budget");
        ArrayList<ContractsJournalDrainCoordinator.DrainProgress> progresses =
                new ArrayList<>();
        ArrayList<blue.coordination.api.ManagedEpochApplicationReceipt>
                managedApplications = new ArrayList<>();
        ArrayList<ManagedEpochApplicationAttempt> managedAttempts =
                new ArrayList<>();
        ArrayList<ManagedEpochEvidenceFailure> managedEvidenceFailures =
                new ArrayList<>();
        Set<DocumentId> failedManagedConsumers = new LinkedHashSet<>();
        LinkedHashMap<String, TimelineEntry> completedEntries =
                new LinkedHashMap<>();
        long committedTransitions = 0L;
        long selectedEntries = 0L;
        boolean madeProgress;
        do {
            madeProgress = false;
            boolean managedFirst = managedAllowed
                    && contractsRecoveryState.managedEpochTurn();
            int phaseCount = managedAllowed ? 2 : 1;
            for (int phase = 0; phase < phaseCount; phase++) {
                if (committedTransitions
                                >= limits.maxCommittedProcessTransitions()
                        || selectedEntries
                                >= limits.maxSelectedEntries()) {
                    break;
                }
                boolean managedPhase = managedFirst == (phase == 0);
                if (!managedPhase) {
                    CoordinationEngine.DrainBudget remaining =
                            new CoordinationEngine.DrainBudget(
                                    Math.subtractExact(
                                            limits
                                                    .maxCommittedProcessTransitions(),
                                            committedTransitions),
                                    Math.subtractExact(
                                            limits.maxSelectedEntries(),
                                            selectedEntries));
                    ContractsJournalDrainCoordinator.DrainProgress progress =
                            contractsJournalCoordinator.drainThrough(
                                    inclusiveCutoff, remaining);
                    progresses.add(progress);
                    for (TimelineEntry entry : progress.completedEntries()) {
                        completedEntries.putIfAbsent(entry.blueId(), entry);
                    }
                    long selectedThisPass = progress.attempts().size();
                    selectedEntries = Math.addExact(
                            selectedEntries, selectedThisPass);
                    committedTransitions = Math.addExact(
                            committedTransitions,
                            progress.committedTransitions());
                    if (selectedThisPass != 0L
                            || progress.committedTransitions() != 0L) {
                        contractsRecoveryState.managedEpochTurn(true);
                    }
                    madeProgress = madeProgress
                            || !progress.completedEntries().isEmpty()
                            || progress.committedTransitions() != 0L;
                    continue;
                }

                boolean selectedManaged = false;
                while (committedTransitions
                                < limits.maxCommittedProcessTransitions()
                        && selectedEntries
                                < limits.maxSelectedEntries()) {
                    Optional<ContractsClosureAdapter.ManagedApplicationOutcome>
                            managed;
                    try {
                        managed = selectNextManagedEpochApplication(
                                failedManagedConsumers);
                    } catch (ManagedEpochEvidenceException failure) {
                        selectedEntries = Math.addExact(selectedEntries, 1L);
                        documents.recordManagedEpochEvidenceFailure(failure);
                        managedEvidenceFailures.add(
                                new ManagedEpochEvidenceFailure(
                                        failure.work(),
                                        failure.planStatus(),
                                        failure.code(),
                                        failure.message()));
                        if (!failedManagedConsumers.add(
                                failure.work().consumerDocumentId())) {
                            throw new IllegalStateException(
                                    "Failed managed consumer was selected "
                                            + "twice",
                                    failure);
                        }
                        contractsRecoveryState.deferManagedEpochConsumer(
                                failure.work().consumerDocumentId());
                        selectedManaged = true;
                        contractsRecoveryState.managedEpochTurn(false);
                        madeProgress = true;
                        continue;
                    }
                    if (managed.isEmpty()) {
                        if (!selectedManaged) {
                            contractsRecoveryState.managedEpochTurn(false);
                        }
                        break;
                    }
                    selectedManaged = true;
                    selectedEntries = Math.addExact(selectedEntries, 1L);
                    contractsRecoveryState.managedEpochTurn(false);
                    ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                            managed.orElseThrow();
                    managedAttempts.add(managedApplicationAttempt(outcome));
                    contractsRecoveryState.deferManagedEpochConsumer(
                            outcome.work().consumerDocumentId());
                    if (!outcome.published()) {
                        if (!failedManagedConsumers.add(
                                outcome.work().consumerDocumentId())) {
                            throw new IllegalStateException(
                                    "Failed managed consumer was selected "
                                            + "twice");
                        }
                        if (outcome.publicationFailure().isEmpty()) {
                            contractsRecoveryState
                                    .isolateManagedEpochConsumer(
                                            outcome.work()
                                                    .consumerDocumentId());
                        }
                        continue;
                    }
                    contractsRecoveryState.completeManagedEpochIsolation(
                            outcome.work().consumerDocumentId());
                    madeProgress = true;
                    if (!outcome.replayed()) {
                        managedApplications.add(
                                outcome.receipt().orElseThrow());
                        committedTransitions = Math.addExact(
                                committedTransitions, 1L);
                    }
                    break;
                }
            }
        } while (madeProgress
                && committedTransitions
                        < limits.maxCommittedProcessTransitions()
                && selectedEntries < limits.maxSelectedEntries());

        Map<String, List<DocumentDispatchOutcome>> outcomes =
                new LinkedHashMap<>();
        Map<String, List<ContractsClosureDispatchAttempt>> attempts =
                new LinkedHashMap<>();
        for (ContractsJournalDrainCoordinator.DrainProgress progress
                : progresses) {
            for (ContractsRootFeederCoordinator.EventProgress attempt
                    : progress.attempts()) {
                TimelineEntry entry = attempt.batch().entry();
                List<DocumentDispatchOutcome> entryOutcomes =
                        new ArrayList<>();
                List<ContractsClosureDispatchAttempt> entryAttempts =
                        new ArrayList<>();
                for (ContractsRootFeederCoordinator.CohortProgress cohort
                        : attempt.cohorts()) {
                    ContractsClosureAdapter.CohortOutcome exact =
                            cohort.outcome();
                    entryAttempts.add(new ContractsClosureDispatchAttempt(
                        entry.blueId(),
                        exact.publicationMembers(),
                        exact.attempt(),
                        exact.published(),
                        exact.publicationIdentity(),
                        exact.replayed(),
                        exact.automaticRetryCount(),
                        managedOccurrenceResolutions(exact.managedSurfaceEvidence()),
                        exact.managedSurfaceEvidence().inputComponents(),
                            exact.managedSurfaceEvidence()
                                    .operationRouteChanges()
                                    .stream()
                                    .map(DefaultCoordinationEngine
                                            ::operationRouteChange)
                                    .toList(),
                            exact.unresolvedDemands().stream()
                                    .map(unresolved -> new
                                            ContractsClosureDispatchAttempt
                                                    .ManagedOccurrenceResolutionIssue(
                                                    unresolved.demand()
                                                            .demandIdentity(),
                                                    ContractsClosureDispatchAttempt
                                                            .ResolutionStatus
                                                            .valueOf(
                                                                    unresolved
                                                                            .status()
                                                                            .name()),
                                                    unresolved.diagnostic()))
                                    .toList()));
                    if (!cohort.outcome().published()
                            || cohort.outcome().replayed()) {
                        continue;
                    }
                    for (DocumentId member
                            : cohort.outcome().publicationMembers()) {
                        documents.require(member)
                                .revisionForEntry(entry.blueId())
                                .ifPresent(revision -> entryOutcomes.add(
                                        new DocumentDispatchOutcome(
                                                member, revision, 0L)));
                    }
                }
                if (!entryOutcomes.isEmpty()) {
                    outcomes.computeIfAbsent(
                            entry.blueId(), ignored -> new ArrayList<>())
                            .addAll(entryOutcomes);
                }
                if (!entryAttempts.isEmpty()) {
                    attempts.computeIfAbsent(
                            entry.blueId(), ignored -> new ArrayList<>())
                            .addAll(entryAttempts);
                }
            }
        }
        boolean activeCatchUp = documents.hasActiveCatchUp();
        ContractsJournalDrainCoordinator.DrainProgress last = progresses
                .isEmpty() ? null : progresses.get(progresses.size() - 1);
        boolean journalQuiescent = last == null
                ? journal.nextExternal(
                        contractsJournalCoordinator.processedThrough(),
                        inclusiveCutoff).isEmpty()
                : last.quiescent();
        boolean remainingWork = activeCatchUp || !journalQuiescent;
        boolean budgetExhausted = committedTransitions
                        >= limits.maxCommittedProcessTransitions()
                || selectedEntries >= limits.maxSelectedEntries();
        boolean quiescent = !remainingWork;
        boolean paused = remainingWork && budgetExhausted;
        return new ProcessingDrainReceipt(
                new ArrayList<>(completedEntries.values()),
                outcomes,
                attempts,
                contractsJournalCoordinator.processedThrough(),
                quiescent,
                paused,
                committedTransitions,
                System.nanoTime() - started,
                managedApplications,
                managedAttempts,
                managedEvidenceFailures);
    }

    private ManagedEpochApplicationAttempt managedApplicationAttempt(
            ContractsClosureAdapter.ManagedApplicationOutcome outcome) {
        ManagedSurfacePublicationEvidence managedSurface = committedManagedApplicationSurface(outcome);
        return new ManagedEpochApplicationAttempt(
                            outcome.work(),
                            outcome.attempt(),
                            outcome.published(),
                            outcome.replayed(),
                            outcome.receipt(),
                            outcome.automaticRetryCount(),
                            outcome.automaticResolutionStopReason()
                                    .map(reason -> ManagedEpochApplicationAttempt
                                            .AutomaticResolutionStopReason
                                            .valueOf(reason.name())),
                            outcome.unresolvedDemands().stream()
                                    .map(unresolved -> new
                                            ManagedEpochApplicationAttempt
                                                    .ManagedOccurrenceResolutionIssue(
                                                    unresolved.demand()
                                                            .demandIdentity(),
                                                    ManagedEpochApplicationAttempt
                                                            .ResolutionStatus
                                                            .valueOf(
                                                                    unresolved
                                                                            .status()
                                                                            .name()),
                                                    unresolved.diagnostic()))
                                    .toList(),
                            outcome.publicationFailure().map(failure ->
                                    new ManagedEpochApplicationAttempt
                                            .PublicationFailure(
                                            failure.code(),
                                            failure.message(),
                                            failure.details())),
                            managedOccurrenceResolutions(managedSurface),
                            managedSurface.inputComponents(),
                            managedSurface.operationRouteChanges().stream()
                                    .map(DefaultCoordinationEngine::operationRouteChange).toList());
    }

    private Optional<ContractsClosureAdapter.ManagedApplicationOutcome>
            selectNextManagedEpochApplication(
                    Set<DocumentId> failedManagedConsumers) {
        Set<DocumentId> failed = Set.copyOf(Objects.requireNonNull(
                failedManagedConsumers, "failedManagedConsumers"));
        LinkedHashSet<DocumentId> excluded = new LinkedHashSet<>(
                contractsRecoveryState.deferredManagedConsumers());
        excluded.addAll(
                contractsRecoveryState.isolatedManagedConsumers());
        excluded.addAll(failed);
        Optional<ContractsClosureAdapter.ManagedApplicationOutcome> selected =
                contractsClosureAdapter.processNextManagedEpochApplication(
                        excluded);
        if (selected.isPresent()) {
            return selected;
        }

        if (contractsRecoveryState.hasDeferredManagedConsumers()) {
            // Every non-failed lane in this deterministic fairness round has
            // been visited. Start the next round without allowing one lane to
            // run twice in the current drain call or an isolated lane to
            // overtake independent work in a later exact slice.
            contractsRecoveryState.completeManagedEpochFairnessRound();
            LinkedHashSet<DocumentId> nextRoundExcluded =
                    new LinkedHashSet<>(contractsRecoveryState
                            .isolatedManagedConsumers());
            nextRoundExcluded.addAll(failed);
            selected = contractsClosureAdapter
                    .processNextManagedEpochApplication(nextRoundExcluded);
            if (selected.isPresent()) {
                return selected;
            }
        }

        if (!contractsRecoveryState.hasIsolatedManagedConsumers()) {
            return Optional.empty();
        }

        // No independent due lane remains. Begin one deterministic retry
        // sweep, still excluding failures already attempted by this same
        // monolithic drain call. Exact sliced calls reconstruct this frontier
        // from retained recovery state.
        Set<DocumentId> isolated = contractsRecoveryState
                .isolatedManagedConsumers();
        contractsRecoveryState.completeManagedEpochIsolationSweep();
        try {
            selected = contractsClosureAdapter
                    .processNextManagedEpochApplication(failed);
            if (selected.isEmpty()) {
                contractsRecoveryState.restoreManagedEpochIsolation(
                        isolated);
            }
            return selected;
        } catch (ManagedEpochEvidenceException retainedFailure) {
            throw retainedFailure;
        } catch (RuntimeException failure) {
            contractsRecoveryState.restoreManagedEpochIsolation(isolated);
            throw failure;
        }
    }

    private static List<ContractsClosureDispatchAttempt.ManagedOccurrenceResolution>
            managedOccurrenceResolutions(ManagedSurfacePublicationEvidence surface) {
        return surface.resolvedOccurrences().stream()
                .map(resolution -> new ContractsClosureDispatchAttempt.ManagedOccurrenceResolution(
                        resolution.demandIdentity(), resolution.occurrence(),
                        ContractsClosureDispatchAttempt.TargetKind.valueOf(resolution.targetKind().name()),
                        Optional.ofNullable(resolution.authoredInitial())))
                .toList();
    }

    private static ContractsClosureDispatchAttempt.OperationRouteChange
            operationRouteChange(
                    OperationRouteIndex.OperationRouteChange change) {
        return new ContractsClosureDispatchAttempt.OperationRouteChange(
                ContractsClosureDispatchAttempt.OperationRouteChangeKind
                        .valueOf(change.kind().name()),
                change.documentId(),
                change.before().map(
                        DefaultCoordinationEngine::operationRouteState),
                change.after().map(
                        DefaultCoordinationEngine::operationRouteState));
    }

    private static ContractsClosureDispatchAttempt.OperationRouteState
            operationRouteState(
                    OperationRouteIndex.OperationRouteState state) {
        return new ContractsClosureDispatchAttempt.OperationRouteState(
                state.scopePath(),
                state.operation(),
                state.channel(),
                state.acceptedSources().stream()
                        .map(source -> new Timeline(
                                source.timelineId(), source.actorId()))
                        .toList());
    }

    private ContractsJournalDrainCoordinator createContractsJournalCoordinator() {
        return new ContractsJournalDrainCoordinator(
                journal,
                contractsFeederCoordinator,
                contractsRecoveryState.journalDrain,
                contractsActiveSourceTimelines::timelineIds,
                entry -> contractsClosureAdapter.completeManagedDraftPlan(
                        entry.blueId()));
    }

    private ContractsRootSourceSurface.Surface contractsSourceSurface(
            DocumentId root) {
        return ContractsRootSourceSurface.resolve(
                ContractsRootFeederWindow.LaneId.publicRoots(List.of(root)),
                documents.occurrenceInventory(),
                documentId -> documents.find(documentId)
                        .map(session -> session.layout().routingSurface()
                                .externalTimelineIds())
                        .orElse(List.of()));
    }

    private record ContractsBootstrap(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            ContractsExecutionPolicy executionPolicy,
            Set<DocumentId> publicRootDocumentIds) {
        private ContractsBootstrap {
            blueLanguageSpecificationIdentity = requireSha256Identity(
                    blueLanguageSpecificationIdentity,
                    "blueLanguageSpecificationIdentity");
            contractsSpecificationIdentity = requireSha256Identity(
                    contractsSpecificationIdentity,
                    "contractsSpecificationIdentity");
            executionPolicy = Objects.requireNonNull(
                    executionPolicy, "executionPolicy");
            publicRootDocumentIds = Set.copyOf(Objects.requireNonNull(
                    publicRootDocumentIds, "publicRootDocumentIds"));
        }

        private static String requireSha256Identity(
                String value,
                String label) {
            String checked = Objects.requireNonNull(value, label);
            if (!checked.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        label + " must be a lowercase sha256 identity");
            }
            return checked;
        }
    }

    /** In-memory stand-in for the durable feeder publication boundary. */
    static final class ContractsRecoveryState {
        private final RootedProcessingSchedule rootedSchedule;
        private final ContractsRootFeederWindow.DurableState feederWindow;
        private final ContractsJournalDrainCoordinator.DurableState
                journalDrain;
        private final Set<DocumentId> deferredManagedConsumers;
        /** Failed/suspended consumers isolated across exact processing slices. */
        private final Set<DocumentId> isolatedManagedConsumers;
        /** Retained fair turn between external and managed transition lanes. */
        private boolean managedEpochTurn;
        private final LogicalEngineControl logical;

        private ContractsRecoveryState() {
            this(new RootedProcessingSchedule(), new ContractsRootFeederWindow.DurableState(),
                    new ContractsJournalDrainCoordinator.DurableState(), List.of(), List.of(), false);
        }

        private ContractsRecoveryState(RootedProcessingSchedule schedule,
                ContractsRootFeederWindow.DurableState feeder, ContractsJournalDrainCoordinator.DurableState journal,
                List<DocumentId> deferred, List<DocumentId> isolated, boolean managedTurn) {
            rootedSchedule = schedule; feederWindow = feeder; journalDrain = journal; logical = null;
            deferredManagedConsumers = new LinkedHashSet<>(deferred); isolatedManagedConsumers = new LinkedHashSet<>(isolated);
            managedEpochTurn = managedTurn;
        }

        private ContractsRecoveryState(RootedProcessingSchedule schedule,
                ContractsRootFeederWindow.DurableState feeder, ContractsJournalDrainCoordinator.DurableState journal,
                Set<DocumentId> deferred, Set<DocumentId> isolated, LogicalEngineControl logical) {
            rootedSchedule = schedule; feederWindow = feeder; journalDrain = journal;
            deferredManagedConsumers = deferred; isolatedManagedConsumers = isolated; this.logical = logical;
        }
        static ContractsRecoveryState logical(RootedProcessingSchedule schedule,
                ContractsRootFeederWindow.DurableState feeder, ContractsJournalDrainCoordinator.DurableState journal,
                Set<DocumentId> deferred, Set<DocumentId> isolated, LogicalEngineControl logical) {
            return new ContractsRecoveryState(schedule, feeder, journal, deferred, isolated, logical);
        }
        private boolean managedEpochTurn() { return logical == null ? managedEpochTurn : logical.managedTurn(); }
        private void managedEpochTurn(boolean value) {
            if (logical == null) managedEpochTurn = value; else logical.managedTurn(value);
        }

        private Set<DocumentId> deferredManagedConsumers() {
            return Set.copyOf(deferredManagedConsumers);
        }

        private boolean hasDeferredManagedConsumers() {
            return !deferredManagedConsumers.isEmpty();
        }

        private Set<DocumentId> isolatedManagedConsumers() {
            return Set.copyOf(isolatedManagedConsumers);
        }

        private boolean hasIsolatedManagedConsumers() {
            return !isolatedManagedConsumers.isEmpty();
        }

        private void deferManagedEpochConsumer(DocumentId consumer) {
            deferredManagedConsumers.add(Objects.requireNonNull(
                    consumer, "consumer"));
        }

        private void completeManagedEpochFairnessRound() {
            deferredManagedConsumers.clear();
        }

        private void isolateManagedEpochConsumer(DocumentId consumer) {
            isolatedManagedConsumers.add(Objects.requireNonNull(
                    consumer, "consumer"));
        }

        private void completeManagedEpochIsolation(DocumentId consumer) {
            isolatedManagedConsumers.remove(Objects.requireNonNull(
                    consumer, "consumer"));
        }

        private void completeManagedEpochIsolationSweep() {
            isolatedManagedConsumers.clear();
        }

        private void restoreManagedEpochIsolation(
                Set<DocumentId> consumers) {
            isolatedManagedConsumers.clear();
            isolatedManagedConsumers.addAll(Set.copyOf(
                    Objects.requireNonNull(consumers, "consumers")));
        }
    }

    private long currentLogicalClock() { return logicalControl == null ? logicalClockMicros : logicalControl.clock("logical"); }
    private void setLogicalClock(long value) {
        if (logicalControl == null) logicalClockMicros = value; else logicalControl.clock("logical", value);
    }

    private long nextApplicationTimestamp() {
        long next = Math.addExact(Math.max(logicalControl == null ? applicationClockMicros
                : logicalControl.clock("application"), currentLogicalClock()), 1L);
        if (logicalControl == null) applicationClockMicros = next; else logicalControl.clock("application", next);
        return next;
    }

    private ExternalOrderKey currentAdmissionFrontier(DocumentId documentId) {
        ExternalOrderKey latest = journal.latestExternalOrder();
        if (latest == null) {
            return ExternalOrderKey.of(List.of(
                    BigInteger.ZERO,
                    "admission",
                    documentId.value()));
        }
        return latest;
    }

    private ExternalOrderKey currentContractsAdmissionFrontier(
            ClosureInvocationInput input) {
        ExternalOrderKey latest = journal.latestExternalOrder();
        if (latest != null) {
            return latest;
        }
        if (contractsClosureProfile.rootedCheckpoint()) return RootedBeginningAdmission.BOUND;
        return ExternalOrderKey.of(List.of(
                BigInteger.ZERO,
                "contracts-admission",
                input.invocationIdentity()));
    }

    private static void requireNoExplicitFrontier(
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier) {
        if (verifiedFrontier != null) {
            throw new IllegalArgumentException(
                    policy + " does not accept verifiedFrontier");
        }
    }

    private ExternalOrderKey requireRetainedFrontier(
            ExternalOrderKey frontier) {
        ExternalOrderKey checked = Objects.requireNonNull(
                frontier, "verifiedFrontier is required for FROM_FRONTIER");
        if (!journal.containsExternalOrder(checked)) {
            throw new IllegalArgumentException(
                    "Frontier has no exact retained journal evidence");
        }
        return checked;
    }

    private Timeline requireRegisteredTimeline(Timeline supplied) {
        Timeline checked = Objects.requireNonNull(supplied, "timeline");
        Timeline canonical = timelines.get(checked.timelineId());
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "Timeline must be registered before append: "
                            + checked.timelineId());
        }
        if (!canonical.equals(checked)) {
            throw new IllegalArgumentException(
                    "Timeline actor/provider evidence does not match registered "
                            + "Timeline " + checked.timelineId());
        }
        return canonical;
    }

    private void requireAfterProcessedFrontier(TimelineEntry entry) {
        if (contractsClosureProfile != null && contractsClosureProfile.rootedCheckpoint()) {
            documents.requireAfterRootedProviderFrontier(entry);
            return;
        }
        ExternalOrderKey processed = contractsJournalCoordinator == null
                ? drainCoordinator.processedThrough()
                : contractsJournalCoordinator.processedThrough();
        if (processed != null
                && entry.sourceOrderKey().compareTo(processed) <= 0) {
            throw new IllegalArgumentException(
                    "Timeline Entry order " + entry.sourceOrderKey()
                            + " is not after completed environment frontier "
                            + processed);
        }
    }

    private static String requiredTextAt(Node root, String pointer) {
        Node selected = NodePathEditor.getOrNull(
                Objects.requireNonNull(root, "root"), pointer);
        Object value = selected == null ? null : selected.getValue();
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Timeline Entry requires non-blank Text at " + pointer);
        }
        return text;
    }

    private static long requiredLongAt(Node root, String pointer) {
        Node selected = NodePathEditor.getOrNull(
                Objects.requireNonNull(root, "root"), pointer);
        Object value = selected == null ? null : selected.getValue();
        long result;
        if (value instanceof BigInteger integer) {
            result = integer.longValueExact();
        } else if (value instanceof Number number) {
            result = number.longValue();
        } else {
            throw new IllegalArgumentException(
                    "Timeline Entry requires Integer at " + pointer);
        }
        if (result <= 0L) {
            throw new IllegalArgumentException(
                    "Timeline Entry timestamp must be positive");
        }
        return result;
    }

    private void ensureOpen() {
        if (pendingSourceStage != null) throw new IllegalStateException("Execute or discard the frozen source stage before other engine work");
        if (pendingRootStage != null) throw new IllegalStateException("Execute or discard the frozen stage before other engine work");
        if (closed) {
            throw new IllegalStateException("DefaultCoordinationEngine is closed");
        }
    }

    record TemporalCatchUpEvidence(
            DocumentId parentDocumentId,
            DocumentId childDocumentId,
            String occurrencePath,
            long appliedChildEpoch,
            String status,
            long activationGeneration) {
    }
}
