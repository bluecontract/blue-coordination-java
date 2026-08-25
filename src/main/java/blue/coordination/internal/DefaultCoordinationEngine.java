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
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.TimelineAppendReceipt;
import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.processor.TimelineProviderSupport;

import blue.language.api.BlueCacheStats;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExternalOrderKey;
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
    enum FailurePoint {
        AFTER_MANAGED_DRAFT_PLAN_REGISTERED,
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
    private final Map<String, Timeline> timelines = new LinkedHashMap<>();
    private final Map<String, String> timelineActorKinds =
            new LinkedHashMap<>();
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private long logicalClockMicros = BASE_TIMESTAMP_MICROS;
    private long applicationClockMicros = BASE_TIMESTAMP_MICROS;
    private boolean closed;

    private DefaultCoordinationEngine(
            ContractsBootstrap contractsConfiguration) {
        metrics = new EngineMetrics();
        objects = new WholeObjectStore(metrics);
        runtime = BlueRuntime.create(objects, metrics);
        entryFactory = new WholeRequestEntryFactory(
                runtime, objects, metrics, this::timelineActorKind);
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
        if (contractsConfiguration == null) {
            contractsClosureAdapter = null;
            contractsClosureAdmissionAdapter = null;
            contractsClosureProfile = null;
            contractsActiveSourceTimelines = null;
            contractsRecoveryState = null;
            contractsFeederCoordinator = null;
            contractsJournalCoordinator = null;
        } else {
            ContractsClosureProfile profile = ContractsClosureProfile
                    .release10(
                            contractsConfiguration
                                    .blueLanguageSpecificationIdentity(),
                            contractsConfiguration
                                    .contractsSpecificationIdentity(),
                            contractsConfiguration.publicRootDocumentIds());
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
                    contractsActiveSourceTimelines);
            contractsClosureAdmissionAdapter =
                    new ContractsClosureAdmissionAdapter(
                            runtime,
                            objects,
                            layoutBuilder,
                            documents,
                            routeIndex,
                            profile,
                            contractsActiveSourceTimelines);
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
                Set.of()));
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
            return snapshot(start(documentId, authoredYaml));
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
                        BigInteger.valueOf(Long.MIN_VALUE),
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
                        BigInteger.valueOf(Long.MIN_VALUE),
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
                input,
                selectedPolicy,
                frontier,
                Contracts10StaticEmbeddedAdmissionCompiler.verifiedProvider(
                        Objects.requireNonNull(
                                exactNodeProvider, "exactNodeProvider")));
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
                    verifiedFrontier));
        } catch (RuntimeException failure) {
            throw translateStartFailure(documentId, failure);
        }
    }


    synchronized ExactValue registerType(String sourceYaml) {
        ensureOpen();
        return runtime.exactSource(sourceYaml, objects, "test-type");
    }

    synchronized ExactValue exactRequest(String requestYaml) {
        ensureOpen();
        return entryFactory.parseExactRequest(requestYaml);
    }

    @Override
    public synchronized ExactValue exactValue(String sourceYaml) {
        ensureOpen();
        return runtime.exactSource(
                sourceYaml, objects, "external-exact-value");
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
        ensureOpen();
        if (contractsClosureAdapter == null) {
            throw new IllegalStateException(
                    "Contracts 1.0 was not enabled for this engine");
        }
        Timeline canonicalTimeline = requireRegisteredTimeline(timeline);
        ContractsManagedDraftPlan plan = Objects.requireNonNull(
                managedDraftPlan, "managedDraftPlan");
        contractsClosureAdapter.preflightManagedDraftPlan(plan);
        long previousLogicalClock = logicalClockMicros;
        long candidateTimestamp = Math.addExact(logicalClockMicros, 1L);
        InMemoryTimelineJournal.Mark mark = journal.mark();
        WholeObjectStore.Mark objectMark = objects.mark();
        String registeredEntryBlueId = null;
        try {
            TimelineEntry entry = metrics.timed(
                    "append.total",
                    () -> journal.append(
                            canonicalTimeline,
                            operation,
                            candidateTimestamp));
            requireAfterProcessedFrontier(entry);
            if (!contractsClosureAdapter.registerManagedDraftPlan(
                    entry.blueId(), plan)) {
                throw new IllegalStateException(
                        "Managed draft plan already exists for new entry "
                                + entry.blueId());
            }
            registeredEntryBlueId = entry.blueId();
            inject(FailurePoint.AFTER_MANAGED_DRAFT_PLAN_REGISTERED);
            logicalClockMicros = candidateTimestamp;
            objects.commit(objectMark);
            return entry;
        } catch (RuntimeException failure) {
            if (registeredEntryBlueId != null) {
                try {
                    contractsClosureAdapter.unregisterManagedDraftPlan(
                            registeredEntryBlueId, plan);
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

    synchronized void inject(FailurePoint point) {
        failureInjector.accept(Objects.requireNonNull(point, "point"));
    }

    EngineMetrics engineMetrics() {
        return metrics;
    }

    @Override
    public synchronized DocumentSnapshot document(DocumentId documentId) {
        DocumentSession session = requireDocument(documentId);
        String readinessFailure = contractsClosureAdapter == null
                ? drainCoordinator.applicationReadinessFailure(session)
                : contractsReadinessFailure(session);
        if (readinessFailure != null) {
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
        return snapshot(session);
    }

    @Override
    public synchronized DocumentSnapshot auditDocument(
            DocumentId documentId) {
        return snapshot(requireDocument(documentId));
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

    private DocumentSnapshot snapshot(DocumentSession session) {
        Map<String, DocumentId> children = contractsClosureAdapter == null
                ? legacyChildren(session.documentId())
                : closureChildren(session.documentId());
        EmbeddedOnlyLayout layout = session.layout();
        Map<String, ExactValue> physicalObjects = new LinkedHashMap<>();
        layout.scopePaths().forEach(path -> physicalObjects.put(
                path, layout.stored(path)));
        return new DocumentSnapshot(
                session.documentId(),
                session.epoch(),
                session.status(),
                session.readyThrough(),
                session.authoredInitialBlueId(),
                session.currentRevision().after(),
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
        documents.publicationSnapshot().occurrenceInventory().activeRows()
                .stream()
                .filter(row -> row.sourceDocumentId().value().equals(
                        parent.value()))
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
        InMemoryDocumentStore.PublicationSnapshot publication =
                documents.publicationSnapshot();
        InMemoryDocumentStore.DocumentHead head = publication.requireHead(
                session.documentId());
        if (head.epoch() != session.epoch()
                || !head.blueId().equals(
                        session.currentRevision().after().blueId())) {
            return "session state disagrees with the durable document head";
        }
        try {
            publication.graphGenerations().require(
                    session.documentId());
        } catch (RuntimeException missing) {
            return "no durable Contracts graph generation";
        }
        for (blue.language.processor.closure.ComponentSnapshot component
                : publication.componentStates()) {
            for (int index = 0;
                    index < component.orderedMemberDocumentIds().size();
                    index++) {
                if (!component.orderedMemberDocumentIds().get(index).value()
                        .equals(session.documentId().value())) {
                    continue;
                }
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
                        contractsRecoveryState.feederWindow));
    }

    private ProcessingDrainReceipt drainContracts(
            ExternalOrderKey inclusiveCutoff,
            CoordinationEngine.DrainBudget budget) {
        long started = System.nanoTime();
        ContractsJournalDrainCoordinator.DrainProgress progress =
                contractsJournalCoordinator.drainThrough(
                        inclusiveCutoff, budget);
        Map<String, List<DocumentDispatchOutcome>> outcomes =
                new LinkedHashMap<>();
        Map<String, List<ContractsClosureDispatchAttempt>> attempts =
                new LinkedHashMap<>();
        for (ContractsRootFeederCoordinator.EventProgress attempt
                : progress.attempts()) {
            TimelineEntry entry = attempt.batch().entry();
            List<DocumentDispatchOutcome> entryOutcomes = new ArrayList<>();
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
                        exact.automaticRetryCount()));
                if (!cohort.outcome().published()
                        || cohort.outcome().replayed()) {
                    continue;
                }
                for (DocumentId member
                        : cohort.outcome().publicationMembers()) {
                    documents.require(member).revisionForEntry(entry.blueId())
                            .ifPresent(revision -> entryOutcomes.add(
                                    new DocumentDispatchOutcome(
                                            member, revision, 0L)));
                }
            }
            if (!entryOutcomes.isEmpty()) {
                outcomes.put(entry.blueId(), List.copyOf(entryOutcomes));
            }
            if (!entryAttempts.isEmpty()) {
                attempts.computeIfAbsent(
                        entry.blueId(), ignored -> new ArrayList<>())
                        .addAll(entryAttempts);
            }
        }
        long committed = outcomes.values().stream()
                .mapToLong(List::size)
                .sum();
        return new ProcessingDrainReceipt(
                progress.completedEntries(),
                outcomes,
                attempts,
                progress.processedThrough(),
                progress.quiescent(),
                progress.paused(),
                committed,
                System.nanoTime() - started);
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
            Set<DocumentId> publicRootDocumentIds) {
        private ContractsBootstrap {
            blueLanguageSpecificationIdentity = requireSha256Identity(
                    blueLanguageSpecificationIdentity,
                    "blueLanguageSpecificationIdentity");
            contractsSpecificationIdentity = requireSha256Identity(
                    contractsSpecificationIdentity,
                    "contractsSpecificationIdentity");
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
