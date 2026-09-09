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
        AFTER_STATE_SWAP_BEFORE_RETURN
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
    private final ContractsActiveSourceTimelineIndex
            contractsActiveSourceTimelines;
    private final ContractsRecoveryState contractsRecoveryState;
    private SequentialDrainCoordinator drainCoordinator;
    private ContractsRootFeederCoordinator contractsFeederCoordinator;
    private ContractsJournalDrainCoordinator contractsJournalCoordinator;
    private final RootedSourceDiscoveryCoordinator rootedSourceDiscoveries;
    private final blue.coordination.sdk.ExactNodeProvider rootedSourceProvider;
    private final Map<String, Timeline> timelines = new LinkedHashMap<>();
    private final Map<String, String> timelineActorKinds =
            new LinkedHashMap<>();
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private long logicalClockMicros = BASE_TIMESTAMP_MICROS;
    private long applicationClockMicros = BASE_TIMESTAMP_MICROS;
    private boolean closed;

    private DefaultCoordinationEngine(
            ContractsBootstrap contractsBootstrap) {
        this(contractsBootstrap, null);
    }

    private DefaultCoordinationEngine(
            ContractsBootstrap contractsBootstrap,
            blue.coordination.sdk.ExactNodeProvider exactNodeProvider) {
        metrics = new EngineMetrics();
        rootedSourceProvider = exactNodeProvider == null ? id -> Optional.empty() : exactNodeProvider;
        objects = new WholeObjectStore(metrics);
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
        journal = new InMemoryTimelineJournal(entryFactory, metrics);
        documents = new InMemoryDocumentStore(metrics);
        routeIndex = new OperationRouteIndex(
                metrics, documentId -> documents.find(documentId).orElse(null));
        layoutBuilder = new EmbeddedOnlyLayoutBuilder(
                runtime, objects, metrics);
        processor = new DocumentTransitionProcessor(
                runtime, objects, layoutBuilder, metrics,
                this::inject);
        drainCoordinator = new SequentialDrainCoordinator(
                runtime,
                objects,
                entryFactory,
                journal,
                routeIndex,
                processor,
                documents,
                metrics,
                this::nextApplicationTimestamp,
                this::inject);
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
            contractsActiveSourceTimelines =
                    new ContractsActiveSourceTimelineIndex(
                            profile.publicRoots());
            contractsClosureAdapter = new ContractsClosureAdapter(
                    runtime,
                    objects,
                    layoutBuilder,
                    documents,
                    routeIndex,
                    profile,
                    contractsActiveSourceTimelines,
                    journal::entries);
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
                    contractsClosureAdapter, journal, layoutBuilder, routeIndex, timelines, rootedSourceProvider) : null;
            if (rootedSourceDiscoveries != null) contractsClosureAdapter.sourceDiscoveryCoordinator(rootedSourceDiscoveries);
            contractsRecoveryState = new ContractsRecoveryState();
            contractsFeederCoordinator = createContractsFeederCoordinator();
            contractsJournalCoordinator = createContractsJournalCoordinator();
        }
    }

    /** Creates the legacy Process Embedded temporal-profile engine. */
    public static DefaultCoordinationEngine create() {
        return new DefaultCoordinationEngine(null);
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
        long candidateTimestamp = Math.addExact(logicalClockMicros, 1L);
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
            logicalClockMicros = candidateTimestamp;
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
        long previousLogicalClock = logicalClockMicros;
        long candidateTimestamp = Math.addExact(logicalClockMicros, 1L);
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
            logicalClockMicros = candidateTimestamp;
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
            logicalClockMicros = previousLogicalClock;
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
        long nextClock = Math.max(logicalClockMicros, timestampMicros);
        InMemoryTimelineJournal.Mark mark = journal.mark();
        WholeObjectStore.Mark objectMark = objects.mark();
        try {
            TimelineEntry entry = metrics.timed("append.total",
                    () -> journal.append(
                            canonicalTimeline, operation, timestampMicros));
            requireAfterProcessedFrontier(entry);
            logicalClockMicros = nextClock;
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
            TimelineProviderSupport.validateExactEnvelope(canonical);
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
            logicalClockMicros = Math.max(logicalClockMicros, timestamp);
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

    @Override
    public synchronized ProcessingDrainReceipt drain() {
        return drain(CoordinationEngine.DrainBudget.unlimited());
    }

    @Override
    public synchronized ProcessingDrainReceipt drain(
            CoordinationEngine.DrainBudget budget) {
        try {
            ensureOpen();
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
        try {
            ensureOpen();
            TimelineEntry entry = journal.requireCanonical(Objects.requireNonNull(input, "input"));
            long started = System.nanoTime();
            ContractsClosureAdapter.FrozenBatch batch = contractsClosureAdapter.captureRoot(
                    Objects.requireNonNull(root, "root"), entry, policy);
            return rootedReadiness(root, executeRootBatch(batch, started), started);
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
                complete &= exact.attempt().isComplete();
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
                    committed++;
                    for (DocumentId member : exact.publicationMembers()) {
                        documents.require(member).revisionForEntry(entry.blueId()).ifPresent(revision ->
                                outcomes.add(new DocumentDispatchOutcome(member, revision, 0L)));
                    }
                }
            }
            return new ProcessingDrainReceipt(complete ? List.of(entry) : List.of(),
                    Map.of(entry.blueId(), outcomes), Map.of(entry.blueId(), attempts),
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
        var replay = rootedSourceDiscoveries.completed(expected);
        if (replay.isPresent()) return replay.orElseThrow();
        var committed = rootedSourceDiscoveries.committedSelection(expected);
        var selected = committed.orElseGet(() -> rootedSourceDiscoveries.requireSelection(expected));
        blue.coordination.api.SourceHistoryPrerequisiteResult result;
        if (selected.admission() != null) {
            var admission = selected.admission();
            authorizeContractsPublicRoots(Set.of(admission.rootDocumentId()));
            var activation = admission.activationInputs();
            var receipt = admitContractsClosure(admission.invocation(), activation.policy(), activation.verifiedFrontier(),
                    rootedSourceProvider);
            result = new blue.coordination.api.SourceHistoryPrerequisiteResult(expected, Optional.of(receipt), Optional.empty(), committed.isPresent());
        } else {
            var receipt = executeRootSelection(Objects.requireNonNull(selected.step()), System.nanoTime());
            result = new blue.coordination.api.SourceHistoryPrerequisiteResult(expected, Optional.empty(), Optional.of(receipt), committed.isPresent());
        }
        rootedSourceDiscoveries.retain(result);
        return result;
    }

    /** Processes at most one earliest eligible obligation from the selected root's exact progress. */
    public synchronized ProcessingDrainReceipt processNextRoot(DocumentId root) {
        return processNextRoot(root, null);
    }

    /** Executes only the exact retained local work selected for this root, before any mutation. */
    public synchronized ProcessingDrainReceipt processNextRoot(DocumentId root, String expectedLocalWork) {
        try {
            ensureOpen();
            long started = System.nanoTime();
            RootedCheckpointDriver.Selection next = new RootedCheckpointDriver(documents, contractsClosureAdapter)
                    .select(Objects.requireNonNull(root, "root"), journal.entries());
            if (expectedLocalWork != null && (next.localHistorical() == null
                    || !expectedLocalWork.equals(next.localHistorical().work().workIdentity()))) {
                throw new IllegalArgumentException("Selected root no longer requires this exact retained work: " + expectedLocalWork);
            }
            return rootedReadiness(root, executeRootSelection(next, started), started);
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    private ProcessingDrainReceipt executeRootSelection(RootedCheckpointDriver.Selection next, long started) {
        if (next.live() != null) return executeRootBatch(next.live(), started);
        if (next.localHistorical() != null) {
            var step = next.localHistorical();
            var batch = contractsClosureAdapter.localHistoryBatch(step);
            var completed = executeRootBatch(batch, started);
            return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null,
                    completed.quiescent(), completed.paused(), completed.committedProcessTransitions(),
                    completed.elapsedNanos()).withRootedRetainedAttempts(completed.contractsAttemptsFor(step.anchor().blueId())
                            .stream().map(attempt -> new ProcessingDrainReceipt.RootedRetainedAttempt(
                                    step.root(), step.work(), attempt)).toList());
        }
        if (next.historical() != null) {
            try {
                var outcome = contractsClosureAdapter.executeManagedEpochApplication(next.historical(), next.excludedConsumers());
                return new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null,
                        outcome.published(), false, outcome.published() && !outcome.replayed() ? 1L : 0L,
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
        ensureOpen();
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
                contractsRecoveryState.managedEpochTurn = true;
            }
            return drained;
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    @Override
    public synchronized ProcessingDrainReceipt
            drainManagedEpochApplication(String expectedWorkIdentity) {
        ensureOpen();
        String expected = Objects.requireNonNull(
                expectedWorkIdentity, "expectedWorkIdentity");
        if (!expected.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "expectedWorkIdentity must be a lowercase sha256 identity");
        }
        ProcessingSelection next = auditNextProcessingSelection();
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
            var driver = new RootedCheckpointDriver(documents, contractsClosureAdapter);
            var selected = driver.scan(journal.entries(), null).heads().get(0);
            long started = System.nanoTime();
            var completed = executeRootSelection(selected.selection(), started);
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
            ensureOpen();
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
        clearFailureInjection();
        routeIndex.clear();
        documents.sessions().stream()
                .sorted(Comparator.comparing(DocumentSession::documentId,
                        EmbeddingBinding.DOCUMENT_ORDER))
                .forEach(session -> routeIndex.replace(
                        session.documentId(),
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
        drainCoordinator = drainCoordinator.restartFromStores(this::inject);
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
        return logicalClockMicros;
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
        boolean pendingTopLevelAdmission = drainCoordinator
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
            if (supplied.journalAdmissionAvailable()) return ProcessingSelection.journal();
            if (scan.heads().isEmpty()) return ProcessingSelection.none();
            var next = scan.heads().get(0).selection();
            if (next.localHistorical() != null) return ProcessingSelection.rootedRetained(next.localHistorical().root(), next.localHistorical().work());
            return next.historical() == null ? ProcessingSelection.journal()
                    : ProcessingSelection.managedEpochApplication(next.historical());
        }
        Optional<ManagedEpochApplicationWork> managed =
                nextFairManagedEpochApplicationWork();
        boolean journal = contractsJournalCoordinator
                .hasPendingJournalTurn()
                || supplied.journalAdmissionAvailable();
        if (contractsRecoveryState.managedEpochTurn
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
                logicalClockMicros);
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

    private static CoordinationException translateStartFailure(
            DocumentId documentId,
            RuntimeException failure) {
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
                    journal, contractsJournalCoordinator,
                    head -> executeRootSelection(head.selection(), System.nanoTime()))
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
                    && contractsRecoveryState.managedEpochTurn;
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
                        contractsRecoveryState.managedEpochTurn = true;
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
                        contractsRecoveryState.managedEpochTurn = false;
                        madeProgress = true;
                        continue;
                    }
                    if (managed.isEmpty()) {
                        if (!selectedManaged) {
                            contractsRecoveryState.managedEpochTurn = false;
                        }
                        break;
                    }
                    selectedManaged = true;
                    selectedEntries = Math.addExact(selectedEntries, 1L);
                    contractsRecoveryState.managedEpochTurn = false;
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
    private static final class ContractsRecoveryState {
        private final ContractsRootFeederWindow.DurableState feederWindow =
                new ContractsRootFeederWindow.DurableState();
        private final ContractsJournalDrainCoordinator.DurableState
                journalDrain =
                new ContractsJournalDrainCoordinator.DurableState();
        private final LinkedHashSet<DocumentId> deferredManagedConsumers =
                new LinkedHashSet<>();
        /** Failed/suspended consumers isolated across exact processing slices. */
        private final LinkedHashSet<DocumentId> isolatedManagedConsumers =
                new LinkedHashSet<>();
        /** Retained fair turn between external and managed transition lanes. */
        private boolean managedEpochTurn;

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

    private long nextApplicationTimestamp() {
        applicationClockMicros = Math.addExact(
                Math.max(applicationClockMicros, logicalClockMicros), 1L);
        return applicationClockMicros;
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
