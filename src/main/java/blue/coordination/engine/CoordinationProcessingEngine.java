package blue.coordination.engine;

import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.gas.BexGasCounter;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationEventAdmissionCompiler;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationRootViewCacheSnapshot;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.CoordinationVerifiedEventAdmission;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentAdmissionCommit;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentAdmissionStatus;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentRemovalResult;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentMetadataRecord;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.api.ProcessingBundlePlanBinding;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.api.TransitionMemoKey;
import blue.coordination.engine.internal.CoordinationFragmentTransitionPlanner;
import blue.coordination.engine.internal.CoordinationProcessingViews;
import blue.coordination.engine.internal.CoordinationTransitionMemoPolicy;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationLocalityDiagnosticsProvider;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;
import blue.coordination.engine.spi.CoordinationSessionStore;
import blue.coordination.engine.spi.CoordinationTransitionMemoStore;
import blue.coordination.engine.spi.CoordinationVerifiedEventAdmissionStore;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.engine.memory.CoordinationEventAdmissionReceipt;
import blue.coordination.engine.fastpath.RequestDigestMemo;
import blue.coordination.engine.fastpath.VerifiedProcessOutput;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.fastpath.HybridResultFrontier;
import blue.coordination.engine.fastpath.IndexedRetainedReferenceResolver;
import blue.coordination.engine.fastpath.PreparedRootContextCache;
import blue.coordination.engine.fastpath.PreparedRootExecutionContext;
import blue.coordination.engine.fastpath.RetainedReferenceIndex;
import blue.coordination.engine.fastpath.VerifiedHybridResultFrontier;
import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.coordination.fastpath.AdmittedProjection;
import blue.coordination.fastpath.CacheMetrics;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.fastpath.ProjectionGenerationCache;
import blue.coordination.fastpath.ProjectionGenerationKey;
import blue.coordination.processor.CoordinationCommitProjectionEvidence;
import blue.coordination.processor.CoordinationCommitProjectionEvidenceBuilder;
import blue.coordination.processor.CoordinationContractsHost;
import blue.coordination.processor.CoordinationDeltaSubscriptionProjector;
import blue.coordination.processor.CoordinationDeliveryPlanning;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.coordination.processor.CoordinationHostQuotaSchedule;
import blue.coordination.processor.CoordinationIndexedDeliveryPlanner;
import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.coordination.processor.CoordinationPreparedDeliveryMemoizer;
import blue.coordination.processor.CoordinationPlanningProjectionCompiler;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.coordination.processor.CoordinationSubscriptionProjector;
import blue.coordination.processor.CoordinationSubscriptionSnapshot;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Storage-neutral host facade for exact Coordination admission and PROCESS.
 *
 * <p>One engine manages many independent host sessions while immutable Blue
 * content remains globally deduplicated by the supplied fragment store. One
 * event advances one session only. The facade never infers session identity
 * from a Root BlueId and never performs cross-session propagation.</p>
 */
public final class CoordinationProcessingEngine implements AutoCloseable {

    /** Default maximum number of complete semantic Roots retained per engine. */
    public static final int DEFAULT_ROOT_VIEW_CACHE_MAXIMUM_SIZE =
            CoordinationInventoryRootViewCache.DEFAULT_MAXIMUM_SIZE;
    private static final long DEFAULT_PLANNING_CACHE_MAXIMUM_WEIGHT =
            64L * 1024L * 1024L;

    /**
     * Unforgeable engine capability for zero-copy access to verified Nodes.
     *
     * <p>The type is public only so low-level fast-path holders can require
     * it without exposing their mutable Node. Instances are created and kept
     * privately by one engine; no public API returns this authority.</p>
     */
    public static final class VerifiedNodeAccessAuthority {
        private VerifiedNodeAccessAuthority() { }
    }

    /**
     * Engine-owned capability for reusing values verified at exact admission.
     *
     * <p>The constructor is private to the owning engine. Planning boundaries
     * compare instances by reference and also verify the exact processor and
     * Contracts generation to which the instance was issued.</p>
     */
    public static final class AdmittedPlanningAuthority {
        private final DocumentProcessor processorDomain;
        private final BlueContracts contractsDomain;

        private AdmittedPlanningAuthority(
                DocumentProcessor processorDomain,
                BlueContracts contractsDomain) {
            this.processorDomain = Objects.requireNonNull(
                    processorDomain, "processorDomain");
            this.contractsDomain = Objects.requireNonNull(
                    contractsDomain, "contractsDomain");
        }

        /** Verifies the complete identity-bound planning domain. */
        public void requireDomain(
                DocumentProcessor processor,
                BlueContracts contracts) {
            if (processorDomain != Objects.requireNonNull(
                    processor, "processor")
                    || contractsDomain != Objects.requireNonNull(
                    contracts, "contracts")) {
                throw new SecurityException(
                        "Admitted planning authority belongs to another "
                                + "processor or Contracts domain");
            }
        }

        /** Verifies the Contracts half of the identity-bound domain. */
        public void requireContractsDomain(BlueContracts contracts) {
            if (contractsDomain != Objects.requireNonNull(
                    contracts, "contracts")) {
                throw new SecurityException(
                        "Admitted planning authority belongs to another "
                                + "Contracts domain");
            }
        }
    }

    private final BlueContracts contracts;
    private final DocumentProcessor documentProcessor;
    private final CoordinationFragmentStore fragmentStore;
    private final CoordinationSessionStore sessionStore;
    private final CoordinationProcessingBundleLoader bundleLoader;
    private final CoordinationTransitionMemoStore transitionMemoStore;
    private final CoordinationProcessingEngineObserver observer;
    private final CoordinationAtomicCommitCoordinator commitCoordinator;
    private final CoordinationHostQuotaSchedule hostQuotaSchedule;
    private final CoordinationContractsHost contractsHost;
    private final CoordinationDocumentSplitter splitter;
    private final CoordinationSubscriptionProjector subscriptionProjector;
    private final CoordinationDeltaSubscriptionProjector
            deltaSubscriptionProjector;
    private final CoordinationCommitProjectionEvidenceBuilder
            commitProjectionEvidenceBuilder;
    private final FastPathWorkMetrics projectionFastPathMetrics;
    private final CoordinationIndexedDeliveryPlanner indexedPlanner;
    private final AdmittedPlanningAuthority admittedPlanningAuthority;
    private final FastPathWorkMetrics planningFastPathMetrics;
    private final CoordinationPlanningProjectionCompiler
            planningProjectionCompiler;
    private final ProjectionGenerationCache planningProjectionCache;
    private final CoordinationPreparedDeliveryMemoizer
            preparedDeliveryMemoizer;
    private final String planningRuntimeIdentity;
    private final CoordinationFragmentTransitionPlanner transitionPlanner;
    private final CoordinationInventoryRootViewCache rootViewCache;
    private final NodeProvider runtimeProvider;
    private final String environmentIdentity;
    private final String gasScheduleIdentity;
    private final CoordinationEventAdmissionCompiler eventAdmissionCompiler;
    private final CoordinationEventAdmissionMetrics eventAdmissionMetrics;
    private final PreparedRootContextCache preparedRootContexts;
    private final Object preparedRootOwnership;
    private final VerifiedNodeAccessAuthority verifiedNodeAccessAuthority;
    private final LinkedHashMap<String, PreparedRootExecutionContext>
            pendingPreparedRootContexts;
    private final int pendingPreparedRootContextMaximumSize;
    private final boolean ownsRuntimes;

    private volatile boolean closed;

    private CoordinationProcessingEngine(Builder builder) {
        this.contracts = Objects.requireNonNull(builder.contracts, "contracts");
        this.documentProcessor = Objects.requireNonNull(
                builder.documentProcessor, "documentProcessor");
        this.fragmentStore = Objects.requireNonNull(
                builder.fragmentStore, "fragmentStore");
        this.sessionStore = Objects.requireNonNull(
                builder.sessionStore, "sessionStore");
        this.transitionMemoStore = builder.transitionMemoStore;
        this.observer = builder.observer != null
                ? builder.observer
                : CoordinationProcessingEngineObserver.none();
        this.hostQuotaSchedule = builder.hostQuotaSchedule != null
                ? builder.hostQuotaSchedule
                : CoordinationHostQuotaSchedule.defaults();
        this.gasScheduleIdentity = builder.gasScheduleIdentity != null
                ? requireText(
                        builder.gasScheduleIdentity,
                        "gasScheduleIdentity")
                : GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY
                        + "|bex="
                        + BexGasCounter.MANIFEST_IDENTITY;
        this.ownsRuntimes = builder.ownsRuntimes;

        requireCurrentRuntimeGeneration();
        if (!CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID.equals(
                fragmentStore.fragmentationProfileIdentity())) {
            throw new IllegalArgumentException(
                    "The fragment store uses another fragmentation profile");
        }
        this.contractsHost = new CoordinationContractsHost(contracts);
        this.splitter = new CoordinationDocumentSplitter(
                contracts, fragmentStore.canonicalFragmentProvider());
        this.subscriptionProjector =
                CoordinationDeliveryPlanning.subscriptionProjector(
                        documentProcessor, contracts);
        this.deltaSubscriptionProjector =
                new CoordinationDeltaSubscriptionProjector();
        this.commitProjectionEvidenceBuilder =
                new CoordinationCommitProjectionEvidenceBuilder();
        this.projectionFastPathMetrics = new FastPathWorkMetrics();
        this.admittedPlanningAuthority = new AdmittedPlanningAuthority(
                documentProcessor, contracts);
        this.indexedPlanner = CoordinationDeliveryPlanning.indexed(
                documentProcessor,
                contracts,
                admittedPlanningAuthority);
        this.rootViewCache = new CoordinationInventoryRootViewCache(
                builder.rootViewCacheMaximumSize);
        this.preparedRootContexts = new PreparedRootContextCache(
                builder.rootViewCacheMaximumSize);
        this.preparedRootOwnership = new Object();
        this.verifiedNodeAccessAuthority =
                new VerifiedNodeAccessAuthority();
        this.pendingPreparedRootContexts =
                new LinkedHashMap<String, PreparedRootExecutionContext>(
                        Math.min(16, builder.rootViewCacheMaximumSize),
                        0.75f,
                        true);
        this.pendingPreparedRootContextMaximumSize =
                builder.rootViewCacheMaximumSize;
        for (Map.Entry<String, Node> entry
                : builder.retainedRootViews.entrySet()) {
            CoordinationFragmentInventory inventory =
                    fragmentStore.requireInventory(entry.getKey());
            rootViewCache.install(inventory, entry.getValue());
        }
        this.transitionPlanner = new CoordinationFragmentTransitionPlanner(
                splitter,
                fragmentStore);
        this.runtimeProvider = documentProcessor.administration()
                .runtimeAccess()
                .languageRuntime()
                .getNodeProvider();
        this.environmentIdentity = builder.environmentIdentity != null
                ? requireText(
                        builder.environmentIdentity, "environmentIdentity")
                : deriveEnvironmentIdentity(builder);
        LanguageRuntimeAccess languageGeneration = contracts.runtimeAccess()
                .languageRuntime();
        this.eventAdmissionMetrics =
                new CoordinationEventAdmissionMetrics();
        this.eventAdmissionCompiler =
                new CoordinationEventAdmissionCompiler(
                        environmentIdentity,
                        identity(
                                "language-generation",
                                languageGeneration.languageVersion(),
                                languageGeneration
                                        .canonicalRegistryIdentity()),
                        builder.providerEvidenceDomain != null
                                ? builder.providerEvidenceDomain
                                : environmentIdentity + "|provider="
                                        + runtimeProvider.getClass().getName(),
                        splitter,
                        builder.maximumCachedEventAdmissions,
                        builder.maximumCachedEventAdmissionWeightBytes,
                        builder.maximumCachedFragmentEvidence,
                        builder.maximumCachedFragmentEvidenceWeightBytes,
                        eventAdmissionMetrics);
        this.planningRuntimeIdentity = identity(
                "admitted-planning-runtime",
                environmentIdentity,
                CoordinationSubscriptionSnapshot.VERSION,
                CoordinationSubscriptionSnapshot.ALGORITHM_IDENTITY);
        this.planningFastPathMetrics = new FastPathWorkMetrics();
        this.planningProjectionCompiler =
                new CoordinationPlanningProjectionCompiler(
                        planningFastPathMetrics);
        this.planningProjectionCache = new ProjectionGenerationCache(
                builder.rootViewCacheMaximumSize,
                DEFAULT_PLANNING_CACHE_MAXIMUM_WEIGHT);
        this.preparedDeliveryMemoizer =
                new CoordinationPreparedDeliveryMemoizer(
                        indexedPlanner,
                        admittedPlanningAuthority,
                        builder.rootViewCacheMaximumSize,
                        DEFAULT_PLANNING_CACHE_MAXIMUM_WEIGHT);
        this.bundleLoader = Objects.requireNonNull(
                builder.bundleLoader, "bundleLoader");
        this.commitCoordinator = new CoordinationAtomicCommitCoordinator(
                fragmentStore,
                sessionStore,
                environmentIdentity);
    }

    /** Starts a mutable, single-owner engine configuration builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Splits, verifies, stores, projects, and atomically admits epoch zero. */
    public DocumentAdmissionResult addDocument(
            DocumentRegistration registration) {
        requireOpen();
        DocumentRegistration request = Objects.requireNonNull(
                registration, "registration");
        Node exactDocument = materializeExact(
                request.exactDocument(), "document");
        CoordinationDocumentSplitter.SplitGraph graph =
                splitter.splitDocument(exactDocument);
        CoordinationFragmentInventory inventory =
                CoordinationFragmentInventory.from(graph);
        admitGraph(graph, inventory);

        CoordinationSubscriptionSnapshot subscriptions =
                subscriptionProjector.projectCurrent(
                        exactDocument,
                        1L,
                        request.activationFrontier());
        String epochZeroIdentity = identity(
                "epoch-zero",
                request.sessionId().value(),
                graph.rootBlueId(),
                inventory.inventoryIdentity(),
                subscriptions.digest(),
                environmentIdentity);
        ManagedDocumentSnapshot session = new ManagedDocumentSnapshot(
                request.sessionId(),
                graph.rootBlueId(),
                graph.rootBlueId(),
                0L,
                environmentIdentity,
                request.activationFrontier(),
                inventory.inventoryIdentity(),
                subscriptions,
                ManagedDocumentStatus.ACTIVE);
        DocumentEpochSnapshot epochZero = new DocumentEpochSnapshot(
                request.sessionId(),
                0L,
                graph.rootBlueId(),
                null,
                null,
                null,
                inventory.inventoryIdentity(),
                subscriptions.digest(),
                Collections.<String>emptyList(),
                0L,
                epochZeroIdentity);
        admittedPlanningProjectionOrNull(
                planningGeneration(session, inventory),
                session.subscriptions(),
                exactDocument,
                inventory);
        DocumentAdmissionResult result = sessionStore.admit(
                new DocumentAdmissionCommit(
                        request, session, epochZero, inventory));
        if (result.session().isPresent()) {
            markPreparedContextAuthoritative(result.session().get());
        }
        if (result.status() == DocumentAdmissionStatus.CREATED) {
            installPreparedRootContext(
                    session,
                    inventory,
                    exactDocument);
        }
        notifyAdmission(request, result);
        return result;
    }

    /** Removes only the managed occurrence state; immutable fragments remain. */
    public DocumentRemovalResult removeDocument(
            DocumentSessionId sessionId,
            long expectedEpoch) {
        requireOpen();
        DocumentSessionId checked = Objects.requireNonNull(
                sessionId, "sessionId");
        DocumentRemovalResult result = sessionStore.remove(
                checked,
                expectedEpoch);
        if (result.session().isPresent()
                && result.session().get().status()
                        == ManagedDocumentStatus.REMOVED) {
            preparedRootContexts.removeSession(checked.value());
        }
        return result;
    }

    /**
     * Splits, verifies, and stores one immutable event graph for reuse across
     * any number of independently managed Root sessions.
     */
    public StoredCoordinationEvent prepareEvent(
            Node exactEvent,
            ExternalOrderKey eventOrderKey) {
        requireOpen();
        Node event = materializeExact(exactEvent, "event");
        CoordinationVerifiedEventAdmission compiled =
                eventAdmissionCompiler.compile(event);
        return admitCompiledEvent(
                compiled,
                Objects.requireNonNull(eventOrderKey, "eventOrderKey"));
    }

    /**
     * Canonical admission with an identity already calculated by the entry
     * builder. The untrusted claim is checked by the admission compiler.
     */
    public StoredCoordinationEvent prepareEvent(
            String claimedEventBlueId,
            Node exactEvent,
            ExternalOrderKey eventOrderKey) {
        requireOpen();
        Node event = materializeExact(exactEvent, "event");
        CoordinationVerifiedEventAdmission compiled =
                eventAdmissionCompiler.compile(
                        claimedEventBlueId, event);
        return admitCompiledEvent(
                compiled,
                Objects.requireNonNull(eventOrderKey, "eventOrderKey"));
    }

    public CoordinationEventAdmissionMetrics.Snapshot
            eventAdmissionMetrics() {
        return eventAdmissionMetrics.snapshot();
    }

    /** Returns exact delta-projection hit/fallback work counters. */
    public FastPathWorkMetrics.Snapshot projectionFastPathMetrics() {
        return projectionFastPathMetrics.snapshot();
    }

    CacheMetrics planningProjectionCacheMetricsForTest() {
        return planningProjectionCache.metrics();
    }

    CacheMetrics preparedDeliveryCacheMetricsForTest() {
        return preparedDeliveryMemoizer.metrics();
    }

    /** Compiles cache-only evidence without publishing authoritative state. */
    public void primeEventAdmission(
            String claimedEventBlueId,
            Node exactEvent) {
        requireOpen();
        Node event = materializeExact(exactEvent, "event");
        eventAdmissionCompiler.compile(claimedEventBlueId, event);
    }

    /**
     * Plans indexed delivery from an event graph admitted by
     * {@link #prepareEvent(Node, ExternalOrderKey)}. The event is never split
     * or admitted again on this path.
     */
    public CoordinationProcessingPlan planIndexed(
            DocumentSessionId sessionId,
            long expectedEpoch,
            StoredCoordinationEvent storedEvent,
            List<String> orderedOccurrenceKeys,
            PrefetchPolicy prefetchPolicy) {
        long planStartedNanos = System.nanoTime();
        requireOpen();
        DocumentSessionId checkedSessionId = Objects.requireNonNull(
                sessionId, "sessionId");
        StoredCoordinationEvent event = Objects.requireNonNull(
                storedEvent, "storedEvent");
        List<String> candidates = Objects.requireNonNull(
                orderedOccurrenceKeys, "orderedOccurrenceKeys");
        PrefetchPolicy policy = Objects.requireNonNull(
                prefetchPolicy, "prefetchPolicy");
        ManagedDocumentSnapshot session = requireActiveSession(
                checkedSessionId);
        if (expectedEpoch != session.currentEpoch()) {
            throw new IllegalStateException(
                    "Expected epoch is stale: " + expectedEpoch
                            + " != " + session.currentEpoch());
        }
        requireEnvironment(session);
        if (event.orderKey().compareTo(session.committedFrontier()) <= 0) {
            throw new IllegalArgumentException(
                    "Event order must advance beyond committed frontier");
        }

        CoordinationFragmentInventory rootInventory =
                fragmentStore.requireInventory(
                        session.fragmentInventoryIdentity());
        CoordinationFragmentInventory eventInventory =
                fragmentStore.requireInventory(
                        event.fragmentInventoryIdentity());
        if (!event.eventBlueId().equals(eventInventory.rootBlueId())) {
            throw new IllegalStateException(
                    "Stored event handle does not bind its inventory Root");
        }
        Node exactRoot = exactRootForIndexedPlanning(rootInventory);
        Node exactEvent = exactRootForIndexedPlanning(eventInventory);
        NodeProvider planningProvider = exactPlanningProvider(
                session.currentRootBlueId(),
                exactRoot,
                rootInventory,
                event.eventBlueId(),
                exactEvent,
                eventInventory);
        CoordinationPreparedDelivery prepared = prepareIndexedAdmitted(
                session,
                rootInventory,
                event.eventBlueId(),
                eventInventory.inventoryIdentity(),
                exactRoot,
                exactEvent,
                candidates,
                planningProvider,
                event.orderKey());
        List<String> preferred = preferredPrefetch(
                policy, prepared, eventInventory);
        String planIdentity = identity(
                "processing-plan",
                session.sessionId().value(),
                Long.toString(session.currentEpoch()),
                session.currentRootBlueId(),
                event.eventBlueId(),
                session.subscriptions().digest(),
                prepared.deliveryPlanIdentity(),
                environmentIdentity,
                policy.name());
        CoordinationProcessingPlan result = new CoordinationProcessingPlan(
                session,
                new Node().blueId(session.currentRootBlueId()),
                new Node().blueId(event.eventBlueId()),
                prepared,
                rootInventory,
                eventInventory,
                prepared.requiredSeedFragmentIdentities(),
                preferred,
                prepared.demandBoundary(),
                planIdentity,
                policy);
        notifyIndexedPlanTiming(
                result,
                elapsedNanos(planStartedNanos));
        notifyPlan(result);
        return result;
    }

    /** Builds one immutable plan without mutating the managed Root/session. */
    public CoordinationProcessingPlan plan(ProcessRequest request) {
        long planStartedNanos = System.nanoTime();
        requireOpen();
        ProcessRequest checked = Objects.requireNonNull(request, "request");
        ManagedDocumentSnapshot session = requireActiveSession(
                checked.sessionId());
        if (checked.expectedEpoch() != null
                && checked.expectedEpoch().longValue()
                != session.currentEpoch()) {
            throw new IllegalStateException(
                    "Expected epoch is stale: " + checked.expectedEpoch()
                            + " != " + session.currentEpoch());
        }
        requireEnvironment(session);
        if (checked.eventOrderKey().compareTo(
                session.committedFrontier()) <= 0) {
            throw new IllegalArgumentException(
                    "Event order must advance beyond committed frontier");
        }

        CoordinationFragmentInventory rootInventory =
                fragmentStore.requireInventory(
                        session.fragmentInventoryIdentity());
        Node exactEvent = materializeExact(checked.event(), "event");
        CoordinationDocumentSplitter.SplitGraph eventGraph =
                splitter.splitEvent(exactEvent);
        CoordinationFragmentInventory eventInventory =
                CoordinationFragmentInventory.from(eventGraph);
        admitGraph(eventGraph, eventInventory);

        CoordinationPreparedDelivery prepared;
        if (checked.planningMode() == DeliveryPlanningMode.INDEXED) {
            Node exactRoot = exactRootForIndexedPlanning(rootInventory);
            NodeProvider planningProvider = exactPlanningProvider(
                    session.currentRootBlueId(),
                    exactRoot,
                    rootInventory,
                    eventGraph.rootBlueId(),
                    exactEvent,
                    eventInventory);
            prepared = prepareIndexedAdmitted(
                    session,
                    rootInventory,
                    eventGraph.rootBlueId(),
                    eventInventory.inventoryIdentity(),
                    exactRoot,
                    exactEvent,
                    checked.orderedIndexedOccurrenceKeys(),
                    planningProvider,
                    checked.eventOrderKey());
        } else {
            Node exactRoot = exactRoot(rootInventory);
            prepared = CoordinationDeliveryPlanning
                    .prepareCurrentRootCompatibility(
                            documentProcessor,
                            contracts,
                            exactRoot,
                            exactEvent,
                            session.subscriptions(),
                            exactPlanningProvider(
                                    session.currentRootBlueId(),
                                    exactRoot,
                                    rootInventory,
                                    eventGraph.rootBlueId(),
                                    exactEvent,
                                    eventInventory),
                            session.subscriptions().rootRevision(),
                            checked.eventOrderKey());
        }
        List<String> preferred = preferredPrefetch(
                checked.prefetchPolicy(),
                prepared,
                eventInventory);
        String planIdentity = identity(
                "processing-plan",
                session.sessionId().value(),
                Long.toString(session.currentEpoch()),
                session.currentRootBlueId(),
                eventGraph.rootBlueId(),
                session.subscriptions().digest(),
                prepared.deliveryPlanIdentity(),
                environmentIdentity,
                checked.prefetchPolicy().name());
        CoordinationProcessingPlan result = new CoordinationProcessingPlan(
                session,
                new Node().blueId(session.currentRootBlueId()),
                new Node().blueId(eventGraph.rootBlueId()),
                prepared,
                rootInventory,
                eventInventory,
                prepared.requiredSeedFragmentIdentities(),
                preferred,
                prepared.demandBoundary(),
                planIdentity,
                checked.prefetchPolicy());
        notifyPlanTiming(
                checked, result, elapsedNanos(planStartedNanos));
        notifyPlan(result);
        return result;
    }

    private CoordinationPreparedDelivery prepareIndexedAdmitted(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory rootInventory,
            String eventBlueId,
            String eventInventoryIdentity,
            Node exactRoot,
            Node exactEvent,
            List<String> orderedOccurrenceKeys,
            NodeProvider exactProvider,
            ExternalOrderKey eventOrder) {
        ProjectionGenerationKey generation = planningGeneration(
                session, rootInventory);
        AdmittedProjection projection = admittedPlanningProjectionOrNull(
                generation,
                session.subscriptions(),
                exactRoot,
                rootInventory);
        if (projection == null) {
            return indexedPlanner.prepareAdmitted(
                    admittedPlanningAuthority,
                    session.currentRootBlueId(),
                    exactRoot,
                    eventBlueId,
                    exactEvent,
                    session.subscriptions(),
                    orderedOccurrenceKeys,
                    exactProvider,
                    session.subscriptions().rootRevision(),
                    eventOrder);
        }
        return preparedDeliveryMemoizer.prepareAdmitted(
                generation,
                projection,
                eventBlueId,
                eventInventoryIdentity,
                eventOrder,
                exactRoot,
                exactEvent,
                session.subscriptions(),
                orderedOccurrenceKeys,
                exactProvider);
    }

    private ProjectionGenerationKey planningGeneration(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory inventory) {
        ManagedDocumentSnapshot exactSession = Objects.requireNonNull(
                session, "session");
        CoordinationFragmentInventory exactInventory =
                Objects.requireNonNull(inventory, "inventory");
        requireEnvironment(exactSession);
        if (!exactSession.currentRootBlueId().equals(
                exactInventory.rootBlueId())
                || !exactSession.fragmentInventoryIdentity().equals(
                        exactInventory.inventoryIdentity())
                || !exactSession.currentRootBlueId().equals(
                        exactSession.subscriptions().rootBlueId())) {
            throw new IllegalStateException(
                    "Planning generation does not bind the authoritative "
                            + "session, inventory, and subscription Root");
        }
        return new ProjectionGenerationKey(
                environmentIdentity,
                exactSession.sessionId().value(),
                exactSession.currentRootBlueId(),
                exactSession.subscriptions().rootRevision(),
                exactInventory.inventoryIdentity(),
                exactSession.subscriptions().digest(),
                planningRuntimeIdentity);
    }

    /**
     * Compiles only a derived optimization. Any unavailable reference or
     * incomplete projection returns to the already admitted semantic planner;
     * no partial projection is cached or consumed.
     */
    private AdmittedProjection admittedPlanningProjectionOrNull(
            ProjectionGenerationKey generation,
            CoordinationSubscriptionSnapshot snapshot,
            Node exactRoot,
            CoordinationFragmentInventory inventory) {
        try {
            return planningProjectionCache.getOrCompile(
                    generation,
                    ignored -> planningProjectionCompiler.compileAdmitted(
                            generation,
                            snapshot,
                            exactRoot,
                            exactRootPlanningProvider(
                                    generation.rootBlueId(),
                                    exactRoot,
                                    inventory)));
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            planningFastPathMetrics.coldProjectionFallback();
            return null;
        }
    }

    private NodeProvider exactRootPlanningProvider(
            String rootBlueId,
            Node exactRoot,
            CoordinationFragmentInventory rootInventory) {
        return exactPlanningProvider(
                rootBlueId,
                exactRoot,
                rootInventory,
                rootBlueId,
                exactRoot,
                rootInventory);
    }

    private NodeProvider exactPlanningProvider(
            String rootBlueId,
            Node exactRoot,
            CoordinationFragmentInventory rootInventory,
            String eventBlueId,
            Node exactEvent,
            CoordinationFragmentInventory eventInventory) {
        NodeProvider invocationRoots = requestedBlueId -> {
            /* This invocation-local provider is consumed only by the indexed
             * planner. Its exact-lookup boundary takes the one defensive
             * snapshot before validation, so cloning the complete Root here
             * would duplicate linear work without adding isolation. */
            if (rootBlueId.equals(requestedBlueId)) {
                return Collections.singletonList(exactRoot);
            }
            if (eventBlueId.equals(requestedBlueId)) {
                return Collections.singletonList(exactEvent);
            }
            return Collections.emptyList();
        };
        NodeProvider admitted = new SequentialNodeProvider(
                invocationRoots,
                inventoryExactProvider(
                        rootInventory,
                        exactRoot,
                        eventInventory,
                        exactEvent),
                fragmentStore.canonicalFragmentProvider());
        Set<String> externalReferences = externalReferenceTargets(
                rootInventory, eventInventory);
        return requestedBlueId -> {
            List<Node> selected = admitted.fetchByBlueId(requestedBlueId);
            if (selected.size() == 1
                    && selected.get(0).isReferenceOnly()
                    && externalReferences.contains(requestedBlueId)) {
                List<Node> semantic = runtimeProvider.fetchByBlueId(
                        requestedBlueId);
                if (semantic.size() == 1
                        && !semantic.get(0).isReferenceOnly()) {
                    return semantic;
                }
            }
            if (!selected.isEmpty()) {
                return selected;
            }
            if (!externalReferences.contains(requestedBlueId)) {
                throw new IllegalStateException(
                        "Indexed planning requested a value outside the "
                                + "admitted Root/Event inventories: "
                                + requestedBlueId);
            }
            return runtimeProvider.fetchByBlueId(requestedBlueId);
        };
    }

    private static Set<String> externalReferenceTargets(
            CoordinationFragmentInventory rootInventory,
            CoordinationFragmentInventory eventInventory) {
        Set<String> result = new LinkedHashSet<String>();
        addExternalReferenceTargets(
                rootInventory, eventInventory, result);
        addExternalReferenceTargets(
                eventInventory, rootInventory, result);
        return Collections.unmodifiableSet(result);
    }

    private static void addExternalReferenceTargets(
            CoordinationFragmentInventory inventory,
            CoordinationFragmentInventory peerInventory,
            Set<String> result) {
        for (FragmentEdgeRecord edge : inventory.edges()) {
            if (edge.originalPureReference()
                    && !inventory.ownsExactBody(edge.childBlueId())
                    && !peerInventory.ownsExactBody(edge.childBlueId())) {
                result.add(edge.childBlueId());
            }
        }
    }

    /** Resolves trusted inventory fragments from already acquired exact views. */
    private static NodeProvider inventoryExactProvider(
            CoordinationFragmentInventory rootInventory,
            Node exactRoot,
            CoordinationFragmentInventory eventInventory,
            Node exactEvent) {
        return requestedBlueId -> {
            Node selected = inventoryNode(
                    eventInventory, exactEvent, requestedBlueId);
            if (selected == null) {
                selected = inventoryNode(
                        rootInventory, exactRoot, requestedBlueId);
            }
            return selected == null
                    ? Collections.<Node>emptyList()
                    : Collections.singletonList(selected);
        };
    }

    private static Node inventoryNode(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            String requestedBlueId) {
        if (!inventory.fragmentBlueIds().contains(requestedBlueId)) {
            return null;
        }
        if (inventory.rootBlueId().equals(requestedBlueId)) {
            return exactRoot;
        }
        for (FragmentMetadataRecord metadata : inventory.metadata()) {
            if (!requestedBlueId.equals(metadata.blueId())
                    || metadata.pointer() == null) {
                continue;
            }
            Node selected = exactNodeAt(exactRoot, metadata.pointer());
            if (selected != null && !selected.isReferenceOnly()) {
                return selected;
            }
        }
        return null;
    }

    private static Node exactNodeAt(Node root, String pointer) {
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null || current.isReferenceOnly()) {
                return null;
            }
            current = NodePathEditor.getOrNull(
                    current,
                    JsonPointer.toPointer(
                            Collections.singletonList(segment)));
        }
        return current;
    }

    /**
     * Executes exactly one public platform-commit PROCESS call and returns an
     * immutable transaction proposal without advancing the session.
     */
    public CoordinationTransition execute(CoordinationProcessingPlan plan) {
        requireOpen();
        CoordinationProcessingPlan checked = Objects.requireNonNull(
                plan, "plan");
        ManagedDocumentSnapshot current = requireActiveSession(
                checked.session().sessionId());
        requireCurrentPlan(checked, current);

        TransitionMemoKey memoKey = new TransitionMemoKey(
                current.sessionId(),
                current.currentRootBlueId(),
                checked.eventReference().getBlueId(),
                checked.preparedDelivery().deliveryPlanIdentity(),
                environmentIdentity,
                gasScheduleIdentity,
                current.committedFrontier());
        if (transitionMemoStore != null) {
            Optional<CoordinationTransition> memoized =
                    transitionMemoStore.find(memoKey);
            if (memoized.isPresent()) {
                return memoized.get();
            }
        }

        long bundleLoadStartedNanos = System.nanoTime();
        LoadedProcessingBundle bundle = bundleLoader.load(
                current,
                checked,
                checked.preferredPrefetchBlueIds());
        notifyBundleLoadTiming(
                checked,
                bundle,
                elapsedNanos(bundleLoadStartedNanos));
        notifyBatchLoad(checked, bundle);

        NodeProvider invocationProvider = bundle.exactProvider();
        if (!(invocationProvider
                instanceof CoordinationLocalityDiagnosticsProvider)) {
            throw new IllegalArgumentException(
                    "The processing bundle provider must expose authoritative "
                            + "request-local locality diagnostics");
        }
        PlatformProcessInvocation invocation =
                PlatformProcessInvocation.builder()
                        .deliveryPlan(
                                checked.preparedDelivery().deliveryPlan())
                        .nodeProvider(invocationProvider)
                        .build();
        requireInvocationBindings(
                checked, current, bundle, invocation);

        /* The supplied immutable processor is required to use the same exact
         * provider/store generation. PROCESS is invoked once; trace or delta
         * evidence is never obtained through a replay. */
        long processInputMaterializationStartedNanos = System.nanoTime();
        PreparedRootExecutionContext preparedRoot =
                preparedRootContexts.get(
                        current.sessionId().value(),
                        current.currentEpoch(),
                        current.currentRootBlueId(),
                        current.fragmentInventoryIdentity());
        Object preparedOwner = preparedRoot == null
                ? null
                : preparedRootOwnership;
        Node exactRoot = preparedRoot != null
                ? preparedRoot.copyRootForPublicInvocation()
                : exactRootForIndexedPlanning(checked.rootInventory());
        Node exactPriorProofRoot = preparedRoot != null
                ? preparedRoot.borrowRootVerified(
                        preparedOwner,
                        verifiedNodeAccessAuthority)
                : exactRoot;
        Node exactEvent = exactRootForIndexedPlanning(
                checked.eventInventory());
        notifyProcessInputMaterializationTiming(
                checked,
                elapsedNanos(processInputMaterializationStartedNanos));
        long processStartedNanos = System.nanoTime();
        PlatformProcessingResult platform =
                contracts.processForPlatformCommit(
                        exactRoot,
                        exactEvent,
                        invocation);
        notifyPlatformProcessTiming(
                checked,
                platform,
                elapsedNanos(processStartedNanos));
        requirePlatformCompanion(current, checked, platform);
        DocumentProcessingResult process = platform.processResult();
        boolean rootCommit = process.commits();
        RequestDigestMemo requestDigests = new RequestDigestMemo();
        VerifiedProcessOutput verifiedOutput = rootCommit
                ? new VerifiedProcessOutput(
                        platform,
                        current.currentRootBlueId(),
                        requestDigests)
                : null;
        Node resultingRoot = rootCommit
                ? verifiedOutput.resultingRoot().borrowVerified(
                        requestDigests,
                        verifiedNodeAccessAuthority)
                : checked.rootReference();
        VerifiedHybridResultFrontier projectionFrontier = null;
        DeltaProjectionApplier.ColdProjectionRequiredException
                projectionFrontierFailure = null;
        if (rootCommit && preparedRoot != null) {
            long hybridFrontierStartedNanos = System.nanoTime();
            try {
                projectionFrontier =
                        HybridResultFrontier.proveRetainedBindings(
                                resultingRoot,
                                preparedRoot,
                                preparedOwner);
            } catch (DeltaProjectionApplier
                    .ColdProjectionRequiredException cold) {
                projectionFrontierFailure = cold;
            } finally {
                notifyHybridFrontierProofTiming(
                        checked,
                        elapsedNanos(hybridFrontierStartedNanos));
            }
        }
        long retainedMaterializationStartedNanos = System.nanoTime();
        Node exactResultingRoot = rootCommit
                ? preparedRoot != null
                        ? new IndexedRetainedReferenceResolver(
                                preparedRoot.retainedReferences(),
                                preparedOwner)
                                .resolveRequestOwned(resultingRoot)
                        : materializeRetainedResultReferences(
                                resultingRoot,
                                exactRoot)
                : resultingRoot;
        notifyRetainedReferenceMaterializationTiming(
                checked,
                elapsedNanos(retainedMaterializationStartedNanos));
        String resultingRootBlueId = rootCommit
                ? verifiedOutput.resultingRootBlueId()
                : current.currentRootBlueId();
        long resultingEpoch = rootCommit
                ? current.currentEpoch() + 1L
                : current.currentEpoch();

        long transitionStartedNanos = System.nanoTime();
        long subscriptionProjectionStartedNanos = System.nanoTime();
        CoordinationSubscriptionUpdate subscriptionUpdate;
        if (!rootCommit) {
            subscriptionUpdate = CoordinationSubscriptionUpdate.unchanged(
                    current.subscriptions(),
                    platform.commitCompanion().eventOrderKey());
        } else {
            try {
                if (projectionFrontierFailure != null) {
                    throw projectionFrontierFailure;
                }
                if (projectionFrontier == null) {
                    throw new DeltaProjectionApplier
                            .ColdProjectionRequiredException(
                            "prepared prior Root context is unavailable");
                }
                PlatformCommitCompanion companion =
                        platform.commitCompanion();
                SubscriptionDelta membershipDelta =
                        companion.subscriptionDelta();
                EffectiveFragmentationCatalog projectionCatalog =
                        !membershipDelta.isEmpty()
                                || !projectionFrontier
                                        .processEmbeddedBoundaryBlueIdByPath()
                                        .isEmpty()
                                ? contracts.effectiveFragmentationCatalog(
                                        exactResultingRoot)
                                : null;
                CoordinationCommitProjectionEvidence evidence =
                        commitProjectionEvidenceBuilder.build(
                                current.subscriptions(),
                                projectionFrontier,
                                exactPriorProofRoot,
                                exactResultingRoot,
                                resultingRootBlueId,
                                companion.resultingRootRevision(),
                                companion.eventOrderKey(),
                                membershipDelta,
                                projectionCatalog);
                subscriptionUpdate = deltaSubscriptionProjector.apply(
                        current.subscriptions(), evidence);
                projectionFastPathMetrics.deltaProjectionUpdated();
            } catch (DeltaProjectionApplier
                    .ColdProjectionRequiredException cold) {
                projectionFastPathMetrics.coldProjectionFallback();
                notifySubscriptionProjectionColdFallback(
                        checked, cold.getMessage());
                subscriptionUpdate = subscriptionProjector
                        .applyPlatformCommit(
                                current.subscriptions(),
                                platform,
                                exactResultingRoot);
            }
        }
        requireSubscriptionDelta(
                subscriptionUpdate,
                platform.commitCompanion().subscriptionDelta());
        notifySubscriptionProjectionTiming(
                checked,
                elapsedNanos(subscriptionProjectionStartedNanos));

        long fragmentTransitionStartedNanos = System.nanoTime();
        CoordinationFragmentTransition fragmentTransition = rootCommit
                ? transitionPlanner.planVerified(
                        verifiedNodeAccessAuthority,
                        checked.rootInventory(),
                        exactResultingRoot,
                        resultingRootBlueId,
                        checked.preparedDelivery(),
                        subscriptionUpdate)
                : new CoordinationFragmentTransition(
                        checked.rootInventory(),
                        Collections.<String, Node>emptyMap(),
                        checked.rootInventory().fragmentBlueIds(),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<CoordinationScopeTransition>emptyList());
        notifyFragmentTransitionPlanningTiming(
                checked,
                elapsedNanos(fragmentTransitionStartedNanos));
        notifySubscriptionAndFragmentTransitionTiming(
                checked,
                subscriptionUpdate,
                fragmentTransition,
                elapsedNanos(transitionStartedNanos));
        List<String> rootEventBlueIds = rootCommit
                ? verifiedOutput.emittedEventBlueIds()
                : rootEventBlueIds(process);
        String transitionIdentity = identity(
                "transition",
                current.sessionId().value(),
                Long.toString(current.currentEpoch()),
                current.currentRootBlueId(),
                checked.eventReference().getBlueId(),
                checked.preparedDelivery().deliveryPlanIdentity(),
                process.status().wireValue(),
                Long.toString(process.totalGas()),
                resultingRootBlueId,
                fragmentTransition.resultingInventory().inventoryIdentity(),
                subscriptionUpdate.snapshot().digest(),
                rootEventBlueIds.toString(),
                environmentIdentity);
        ManagedDocumentSnapshot resultingSession =
                new ManagedDocumentSnapshot(
                        current.sessionId(),
                        current.initialDocumentBlueId(),
                        resultingRootBlueId,
                        resultingEpoch,
                        current.environmentIdentity(),
                        platform.commitCompanion().eventOrderKey(),
                        fragmentTransition.resultingInventory()
                                .inventoryIdentity(),
                        subscriptionUpdate.snapshot(),
                        ManagedDocumentStatus.ACTIVE);
        long preparedResultContextStartedNanos = System.nanoTime();
        Map<String, ExactNodeHandle> resultingProcessingViews =
                new LinkedHashMap<String, ExactNodeHandle>();
        if (rootCommit && preparedRoot != null) {
            Map<String, ExactNodeHandle> retainedViews =
                    preparedRoot.selectedViews(
                            fragmentTransition.resultingInventory()
                                    .fragmentBlueIds());
            for (Map.Entry<String, ExactNodeHandle> retainedView
                    : retainedViews.entrySet()) {
                resultingProcessingViews.put(
                        retainedView.getKey(),
                        retainedView.getValue().rebind(
                                preparedOwner, requestDigests));
            }
        }
        if (rootCommit) {
            for (Map.Entry<String, Node> changedView
                    : fragmentTransition.processingViews().entrySet()) {
                resultingProcessingViews.put(
                        changedView.getKey(),
                        ExactNodeHandle.adoptAndVerify(
                                changedView.getKey(),
                                changedView.getValue(),
                                requestDigests));
            }
        }
        PreparedRootExecutionContext preparedResult = rootCommit
                ? buildPreparedResultContext(
                        resultingSession,
                        fragmentTransition.resultingInventory(),
                        verifiedOutput,
                        requestDigests,
                        resultingProcessingViews)
                : null;
        notifyPreparedResultContextTiming(
                checked,
                elapsedNanos(preparedResultContextStartedNanos));
        if (rootCommit) {
            rootViewCache.installOwnedVerified(
                    fragmentTransition.resultingInventory(),
                    exactResultingRoot,
                    resultingRootBlueId,
                    preparedResult.approximateRetainedWeightBytes());
        }
        DocumentEpochSnapshot epochSnapshot = rootCommit
                ? new DocumentEpochSnapshot(
                        current.sessionId(),
                        resultingEpoch,
                        resultingRootBlueId,
                        current.currentRootBlueId(),
                        checked.eventReference().getBlueId(),
                        platform.commitCompanion().eventOrderKey(),
                        fragmentTransition.resultingInventory()
                                .inventoryIdentity(),
                        subscriptionUpdate.snapshot().digest(),
                        rootEventBlueIds,
                        process.totalGas(),
                        transitionIdentity)
                : null;
        CoordinationAtomicCommitPlan commitPlan =
                new CoordinationAtomicCommitPlan(
                        current.sessionId(),
                        current.currentEpoch(),
                        current.currentRootBlueId(),
                        current.initialDocumentBlueId(),
                        current.environmentIdentity(),
                        current.committedFrontier(),
                        current.fragmentInventoryIdentity(),
                        current.subscriptions().digest(),
                        resultingEpoch,
                        resultingRootBlueId,
                        checked.eventReference().getBlueId(),
                        platform.commitCompanion().eventOrderKey(),
                        process,
                        platform.commitCompanion(),
                        fragmentTransition,
                        subscriptionUpdate,
                        rootEventBlueIds,
                        transitionIdentity,
                        resultingSession,
                        epochSnapshot,
                        verifiedOutput);
        LocalityDiagnostics locality =
                ((CoordinationLocalityDiagnosticsProvider)
                        invocationProvider).diagnostics();
        CoordinationTransition transition = new CoordinationTransition(
                checked,
                platform,
                fragmentTransition,
                subscriptionUpdate,
                commitPlan,
                locality);
        if (preparedResult != null) {
            retainPendingPreparedRootContext(
                    transitionIdentity, preparedResult);
        }
        if (transitionMemoStore != null
                && CoordinationTransitionMemoPolicy.permits(process)) {
            transitionMemoStore.put(memoKey, transition);
        }
        notifyFragmentTransition(fragmentTransition);
        notifyProcessComplete(transition);
        return transition;
    }

    /** Executes, admits immutable output, and performs one authoritative CAS. */
    public CommitOutcome processAndCommit(ProcessRequest request) {
        long endToEndStartedNanos = System.nanoTime();
        requireOpen();
        ProcessRequest checked = Objects.requireNonNull(request, "request");
        if (!checked.commit()) {
            throw new IllegalArgumentException(
                    "processAndCommit requires ProcessRequest.commit == true");
        }
        CoordinationTransition transition = execute(plan(checked));
        CommitOutcome outcome = commit(transition);
        installPreparedRootContextAfterPublication(transition, outcome);
        notifyProcessAndCommitTiming(
                checked,
                outcome,
                elapsedNanos(endToEndStartedNanos));
        return outcome;
    }

    /**
     * Installs a pre-CAS prepared context after authoritative session and
     * route publication. Candidate construction and validation completed in
     * {@link #execute(CoordinationProcessingPlan)}; this method performs only
     * a bounded derived-cache insertion and never fails publication.
     */
    public boolean installPreparedRootContextAfterPublication(
            CoordinationTransition transition,
            CommitOutcome outcome) {
        CoordinationTransition checkedTransition = Objects.requireNonNull(
                transition, "transition");
        CommitOutcome checkedOutcome = Objects.requireNonNull(
                outcome, "outcome");
        String transitionIdentity = checkedTransition.commitPlan()
                .transitionIdentity();
        PreparedRootExecutionContext candidate =
                pendingPreparedRootContext(transitionIdentity);
        if (candidate == null || !checkedOutcome.committed()
                || !checkedOutcome.transitionIdentity().equals(
                        transitionIdentity)) {
            return false;
        }
        Optional<ManagedDocumentSnapshot> published =
                checkedOutcome.session();
        if (!published.isPresent()) return false;
        ManagedDocumentSnapshot expected = checkedTransition.commitPlan()
                .resultingSession();
        if (!sameSessionGeneration(published.get(), expected)
                || !candidate.matches(
                        expected.sessionId().value(),
                        expected.currentEpoch(),
                        expected.currentRootBlueId(),
                        expected.fragmentInventoryIdentity())) {
            return false;
        }
        Optional<DocumentEpochSnapshot> committedEpoch =
                sessionStore.findEpoch(
                        expected.sessionId(), expected.currentEpoch());
        if (!committedEpoch.isPresent()
                || !transitionIdentity.equals(
                        committedEpoch.get().transitionIdentity())
                || !expected.currentRootBlueId().equals(
                        committedEpoch.get().rootBlueId())
                || !expected.fragmentInventoryIdentity().equals(
                        committedEpoch.get()
                                .fragmentInventoryIdentity())) {
            return false;
        }
        Optional<ManagedDocumentSnapshot> current =
                sessionStore.findSession(expected.sessionId());
        if (!current.isPresent()
                || !sameSessionGeneration(current.get(), expected)) {
            return false;
        }
        if (!removePendingPreparedRootContext(
                transitionIdentity, candidate)) {
            return false;
        }
        try {
            Optional<ManagedDocumentSnapshot> stillCurrent =
                    sessionStore.findSession(expected.sessionId());
            if (!stillCurrent.isPresent()
                    || !sameSessionGeneration(
                            stillCurrent.get(), expected)) {
                return false;
            }
            boolean installed = preparedRootContexts.installIfCurrent(
                    candidate);
            if (installed) {
                retirePublishedPlanningGeneration(
                        checkedTransition,
                        stillCurrent.get());
            }
            return installed;
        } catch (RuntimeException derivedCacheFailure) {
            return false;
        }
    }

    private void retirePublishedPlanningGeneration(
            CoordinationTransition transition,
            ManagedDocumentSnapshot current) {
        try {
            ProjectionGenerationKey previous = planningGeneration(
                    transition.plan().session(),
                    transition.plan().rootInventory());
            CoordinationFragmentInventory currentInventory =
                    fragmentStore.requireInventory(
                            current.fragmentInventoryIdentity());
            ProjectionGenerationKey published = planningGeneration(
                    current, currentInventory);
            if (!previous.equals(published)) {
                preparedDeliveryMemoizer.generationCommitted(previous);
                planningProjectionCache.retainOnly(published);
            }
        } catch (RuntimeException derivedCacheFailure) {
            // Publication is authoritative; derived-cache maintenance is not.
        }
    }

    private void retainPendingPreparedRootContext(
            String transitionIdentity,
            PreparedRootExecutionContext context) {
        synchronized (pendingPreparedRootContexts) {
            pendingPreparedRootContexts.put(
                    transitionIdentity,
                    Objects.requireNonNull(context, "context"));
            if (pendingPreparedRootContexts.size()
                    > pendingPreparedRootContextMaximumSize) {
                pendingPreparedRootContexts.remove(
                        pendingPreparedRootContexts.entrySet()
                                .iterator().next().getKey());
            }
        }
    }

    private PreparedRootExecutionContext pendingPreparedRootContext(
            String transitionIdentity) {
        synchronized (pendingPreparedRootContexts) {
            return pendingPreparedRootContexts.get(
                    Objects.requireNonNull(
                            transitionIdentity,
                            "transitionIdentity"));
        }
    }

    private boolean removePendingPreparedRootContext(
            String transitionIdentity,
            PreparedRootExecutionContext expected) {
        synchronized (pendingPreparedRootContexts) {
            String identity = Objects.requireNonNull(
                    transitionIdentity, "transitionIdentity");
            if (pendingPreparedRootContexts.get(identity) != expected) {
                return false;
            }
            pendingPreparedRootContexts.remove(identity);
            return true;
        }
    }

    private static boolean sameSessionGeneration(
            ManagedDocumentSnapshot actual,
            ManagedDocumentSnapshot expected) {
        ManagedDocumentSnapshot left = Objects.requireNonNull(
                actual, "actual");
        ManagedDocumentSnapshot right = Objects.requireNonNull(
                expected, "expected");
        return left.sessionId().equals(right.sessionId())
                && left.currentEpoch() == right.currentEpoch()
                && left.currentRootBlueId().equals(
                        right.currentRootBlueId())
                && left.initialDocumentBlueId().equals(
                        right.initialDocumentBlueId())
                && left.environmentIdentity().equals(
                        right.environmentIdentity())
                && left.committedFrontier().equals(
                        right.committedFrontier())
                && left.fragmentInventoryIdentity().equals(
                        right.fragmentInventoryIdentity())
                && left.subscriptions().digest().equals(
                        right.subscriptions().digest())
                && left.status() == right.status();
    }

    /**
     * Admits the immutable output of a previously executed current plan and
     * performs its single revision-bound authoritative session CAS.
     */
    public CommitOutcome commit(CoordinationTransition transition) {
        long commitStartedNanos = System.nanoTime();
        requireOpen();
        CoordinationTransition checked = Objects.requireNonNull(
                transition, "transition");
        CommitOutcome outcome = commitCoordinator.commit(checked);
        Optional<ManagedDocumentSnapshot> authoritative =
                sessionStore.findSession(
                        checked.commitPlan().sessionId());
        if (authoritative.isPresent()) {
            markPreparedContextAuthoritative(authoritative.get());
        }
        notifyCommitTiming(
                checked,
                outcome,
                elapsedNanos(commitStartedNanos));
        notifyCommit(outcome);
        return outcome;
    }

    /** Returns the authoritative current session or fails when absent. */
    public ManagedDocumentSnapshot session(DocumentSessionId sessionId) {
        requireOpen();
        return sessionStore.findSession(
                Objects.requireNonNull(sessionId, "sessionId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Managed session is absent: " + sessionId));
    }

    /** Returns an immutable historical epoch or fails when absent. */
    public DocumentEpochSnapshot epoch(
            DocumentSessionId sessionId,
            long epoch) {
        requireOpen();
        return sessionStore.findEpoch(
                Objects.requireNonNull(sessionId, "sessionId"), epoch)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Managed epoch is absent: " + sessionId + "/" + epoch));
    }

    public String environmentIdentity() {
        return environmentIdentity;
    }

    /**
     * Returns live bounded-cache work and occupancy evidence.
     *
     * @return immutable process-local cache metrics
     */
    public CoordinationRootViewCacheSnapshot rootViewCacheSnapshot() {
        return rootViewCache.snapshot();
    }

    /**
     * Rebuilds one in-process prepared epoch context from authoritative
     * restored session/inventory state. Hosts call this while restoring a
     * checkpoint, before accepting new event work.
     */
    public void prepareRootContext(ManagedDocumentSnapshot supplied) {
        RootContextRestore restore = requireRootContextRestore(supplied);
        ManagedDocumentSnapshot current = restore.session;
        CoordinationFragmentInventory inventory = restore.inventory;
        preparedRootContexts.getOrBuild(
                current.sessionId().value(),
                current.currentEpoch(),
                current.currentRootBlueId(),
                current.fragmentInventoryIdentity(),
                () -> buildPreparedRootContext(
                        current,
                        inventory,
                        exactRootForIndexedPlanning(inventory)));
    }

    /**
     * Rebuilds a warm context from exact PROCESS views retained by one local
     * in-process checkpoint. Values cross no old-engine ownership boundary:
     * this method snapshots, verifies, and copies every complete inventory
     * member into the new engine's private ownership domain.
     */
    public void prepareRootContextFromCheckpoint(
            ManagedDocumentSnapshot supplied,
            Map<String, Node> suppliedProcessingViews) {
        RootContextRestore restore = requireRootContextRestore(supplied);
        Map<String, Node> exactProcessingViews =
                checkpointProcessingViews(
                        restore.inventory,
                        suppliedProcessingViews);
        PreparedRootExecutionContext context = buildPreparedRootContext(
                restore.session,
                restore.inventory,
                exactRootForIndexedPlanning(restore.inventory),
                exactProcessingViews);
        if (!preparedRootContexts.installIfCurrent(context)) {
            throw new IllegalStateException(
                    "Checkpoint Root context is not current or exceeds its "
                            + "retained-memory budget");
        }
    }

    private RootContextRestore requireRootContextRestore(
            ManagedDocumentSnapshot supplied) {
        requireOpen();
        ManagedDocumentSnapshot session = Objects.requireNonNull(
                supplied, "session");
        ManagedDocumentSnapshot current = requireActiveSession(
                session.sessionId());
        if (current.currentEpoch() != session.currentEpoch()
                || !current.currentRootBlueId().equals(
                        session.currentRootBlueId())
                || !current.fragmentInventoryIdentity().equals(
                        session.fragmentInventoryIdentity())) {
            throw new IllegalArgumentException(
                    "Prepared-context session snapshot is stale");
        }
        markPreparedContextAuthoritative(current);
        CoordinationFragmentInventory inventory =
                fragmentStore.requireInventory(
                        current.fragmentInventoryIdentity());
        return new RootContextRestore(current, inventory);
    }

    private static Map<String, Node> checkpointProcessingViews(
            CoordinationFragmentInventory inventory,
            Map<String, Node> supplied) {
        Map<String, Node> values = Objects.requireNonNull(
                supplied, "suppliedProcessingViews");
        Set<String> expected = new LinkedHashSet<String>(
                inventory.fragmentBlueIds());
        if (!expected.equals(new LinkedHashSet<String>(values.keySet()))) {
            throw new IllegalArgumentException(
                    "Checkpoint PROCESS views do not exactly cover inventory "
                            + inventory.inventoryIdentity());
        }
        Map<String, Node> snapshot = new LinkedHashMap<String, Node>();
        for (String blueId : inventory.fragmentBlueIds()) {
            Node exact = Objects.requireNonNull(
                    values.get(blueId),
                    "checkpoint PROCESS view " + blueId);
            snapshot.put(blueId, exact.clone());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Captures only the current sessions' bounded exact Root views for an
     * in-process copy-on-write checkpoint.
     *
     * <p>Historical revisions remain body-free. A cache miss is reconstructed
     * and verified once at this explicit quiescent boundary, never on the
     * first operation in every fork.</p>
     */
    public Map<String, Node> checkpointCurrentRootViews(
            Collection<ManagedDocumentSnapshot> sessions) {
        requireOpen();
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (ManagedDocumentSnapshot session : Objects.requireNonNull(
                sessions, "sessions")) {
            ManagedDocumentSnapshot checked = Objects.requireNonNull(
                    session, "session");
            ManagedDocumentSnapshot current = session(checked.sessionId());
            if (current.currentEpoch() != checked.currentEpoch()
                    || !current.currentRootBlueId().equals(
                            checked.currentRootBlueId())
                    || !current.fragmentInventoryIdentity().equals(
                            checked.fragmentInventoryIdentity())
                    || current.status() != checked.status()) {
                throw new IllegalStateException(
                        "Checkpoint session snapshot is stale: "
                                + checked.sessionId());
            }
            CoordinationFragmentInventory inventory =
                    fragmentStore.requireInventory(
                            checked.fragmentInventoryIdentity());
            if (!inventory.rootBlueId().equals(
                    checked.currentRootBlueId())) {
                throw new IllegalStateException(
                        "Current session Root disagrees with its inventory: "
                                + checked.sessionId());
            }
            result.put(
                    inventory.inventoryIdentity(),
                    exactRoot(inventory));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!ownsRuntimes) return;
        RuntimeException failure = null;
        try {
            documentProcessor.close();
        } catch (RuntimeException problem) {
            failure = problem;
        }
        try {
            contracts.close();
        } catch (RuntimeException problem) {
            if (failure == null) failure = problem;
            else failure.addSuppressed(problem);
        }
        if (failure != null) throw failure;
    }

    private void admitGraph(
            CoordinationDocumentSplitter.SplitGraph graph,
            CoordinationFragmentInventory inventory) {
        CoordinationFragmentAdmissionVerifier.admitInventory(
                graph.fragmentationProfileIdentity(),
                graph.fragmentRoots(),
                graph.fragments(),
                graph.edgeOccurrences(),
                fragmentStore);
        fragmentStore.putInventory(inventory);
        fragmentStore.putProcessingViews(
                inventory.inventoryIdentity(),
                CoordinationProcessingViews.collect(graph));
        rootViewCache.install(inventory, graph.originalRoot());
    }

    private void installPreparedRootContext(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory inventory,
            Node exactRoot) {
        preparedRootContexts.installIfCurrent(buildPreparedRootContext(
                session, inventory, exactRoot));
    }

    private void markPreparedContextAuthoritative(
            ManagedDocumentSnapshot session) {
        ManagedDocumentSnapshot checked = Objects.requireNonNull(
                session, "session");
        preparedRootContexts.markAuthoritativeGeneration(
                checked.sessionId().value(),
                checked.currentEpoch(),
                checked.currentRootBlueId(),
                checked.fragmentInventoryIdentity());
    }

    private PreparedRootExecutionContext buildPreparedRootContext(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory inventory,
            Node exactRoot) {
        Map<String, NodeProviderResult> processingResults =
                fragmentStore.readProcessingAll(
                        inventory.inventoryIdentity(),
                        inventory.fragmentBlueIds());
        Map<String, Node> processingViews =
                new LinkedHashMap<String, Node>();
        for (String blueId : inventory.fragmentBlueIds()) {
            NodeProviderResult result = processingResults.get(blueId);
            if (result == null || result.nodes().size() != 1) {
                throw new IllegalStateException(
                        "Prepared PROCESS view is unavailable or ambiguous: "
                                + blueId);
            }
            processingViews.put(blueId, result.nodes().get(0));
        }
        return buildPreparedRootContext(
                session, inventory, exactRoot, processingViews);
    }

    private PreparedRootExecutionContext buildPreparedRootContext(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            Map<String, Node> suppliedProcessingViews) {
        ExactNodeHandle rootHandle = ExactNodeHandle.copyAndVerify(
                inventory.rootBlueId(),
                exactRoot,
                preparedRootOwnership);
        RequestDigestMemo digests = new RequestDigestMemo();
        digests.bindVerified(
                rootHandle.borrowVerified(
                        preparedRootOwnership,
                        verifiedNodeAccessAuthority),
                inventory.rootBlueId());
        RetainedReferenceIndex retained = RetainedReferenceIndex.scanOnce(
                rootHandle,
                preparedRootOwnership,
                digests);
        Map<String, ExactNodeHandle> processingViews =
                new LinkedHashMap<String, ExactNodeHandle>();
        List<ExactNodeHandle> projectionRoots =
                new ArrayList<ExactNodeHandle>();
        projectionRoots.add(rootHandle);
        RequestDigestMemo projectionDigests = new RequestDigestMemo();
        projectionDigests.bindVerified(
                rootHandle.borrowVerified(
                        preparedRootOwnership,
                        verifiedNodeAccessAuthority),
                rootHandle.blueId());
        for (Map.Entry<String, Node> view : Objects.requireNonNull(
                suppliedProcessingViews,
                "suppliedProcessingViews").entrySet()) {
            String blueId = view.getKey();
            if (!inventory.fragmentBlueIds().contains(blueId)) {
                throw new IllegalArgumentException(
                        "Prepared PROCESS view is outside inventory: "
                                + blueId);
            }
            ExactNodeHandle handle = ExactNodeHandle.copyAndVerify(
                    blueId,
                    view.getValue(),
                    preparedRootOwnership);
            processingViews.put(blueId, handle);
            projectionRoots.add(handle);
            projectionDigests.bindVerified(
                    handle.borrowVerified(
                            preparedRootOwnership,
                            verifiedNodeAccessAuthority),
                    blueId);
        }
        RetainedReferenceIndex projectionRetained =
                RetainedReferenceIndex.scanAll(
                        projectionRoots,
                        preparedRootOwnership,
                        projectionDigests);
        return new PreparedRootExecutionContext(
                session.sessionId().value(),
                session.currentEpoch(),
                inventory,
                rootHandle,
                retained,
                projectionRetained,
                processingViews,
                preparedRootOwnership);
    }

    /**
     * Prepares the next epoch before CAS from identities already verified by
     * PROCESS and transition planning. The exact result still requires one
     * complete retained-node index scan; unchanged PROCESS view handles are
     * rebound without clone/hash, and changed top-level views are verified
     * once without recursively rescanning every view graph.
     */
    private PreparedRootExecutionContext buildPreparedResultContext(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory inventory,
            VerifiedProcessOutput output,
            RequestDigestMemo requestDigests,
            Map<String, ExactNodeHandle> processingViews) {
        VerifiedProcessOutput verified = Objects.requireNonNull(
                output, "output");
        RequestDigestMemo owner = Objects.requireNonNull(
                requestDigests, "requestDigests");
        ExactNodeHandle requestRootHandle = verified.resultingRoot();
        ExactNodeHandle rootHandle = requestRootHandle.rebind(
                owner, preparedRootOwnership);
        if (!inventory.rootBlueId().equals(rootHandle.blueId())) {
            throw new IllegalArgumentException(
                    "Verified result Root does not match inventory");
        }
        rootHandle.borrowVerified(
                preparedRootOwnership,
                verifiedNodeAccessAuthority);
        RetainedReferenceIndex retained = RetainedReferenceIndex.scanOnce(
                rootHandle,
                preparedRootOwnership,
                owner);
        Map<String, ExactNodeHandle> views =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, ExactNodeHandle> view
                : Objects.requireNonNull(
                        processingViews, "processingViews").entrySet()) {
            views.put(
                    view.getKey(),
                    view.getValue().rebind(owner, preparedRootOwnership));
        }
        RetainedReferenceIndex projectionRetained =
                retained.withVerifiedHandles(
                        views.values(), preparedRootOwnership);
        return new PreparedRootExecutionContext(
                session.sessionId().value(),
                session.currentEpoch(),
                inventory,
                rootHandle,
                retained,
                projectionRetained,
                views,
                preparedRootOwnership);
    }

    private StoredCoordinationEvent admitCompiledEvent(
            CoordinationVerifiedEventAdmission compiled,
            ExternalOrderKey orderKey) {
        CoordinationFragmentInventory inventory = compiled.inventory();
        if (fragmentStore
                instanceof CoordinationVerifiedEventAdmissionStore) {
            CoordinationVerifiedEventAdmissionStore fastStore =
                    (CoordinationVerifiedEventAdmissionStore) fragmentStore;
            CoordinationEventAdmissionReceipt receipt =
                    fastStore.admitVerifiedEvent(compiled);
            for (int index = 0;
                    index < receipt.insertedFragmentBlueIds().size();
                    index++) {
                eventAdmissionMetrics.fragmentAdmitted();
                eventAdmissionMetrics.nodeMaterialized();
            }
            for (int index = 0;
                    index < receipt.retainedFragmentBlueIds().size();
                    index++) {
                eventAdmissionMetrics.fragmentReused();
            }
            for (int index = 0;
                    index < receipt.insertedProcessingViewCount();
                    index++) {
                eventAdmissionMetrics.nodeMaterialized();
            }
            /* The compiler's accessor materializes a fresh mutable Root from
             * immutable verified evidence.  Transfer that one materialization
             * directly into the engine-owned cache; the canonical split has
             * already established the Root identity. */
            rootViewCache.installOwnedVerified(
                    inventory,
                    compiled.exactEvent(),
                    compiled.key().eventBlueId());
        } else {
            // Portable stores retain the strict verifier path.
            eventAdmissionMetrics.fullEventSplit();
            CoordinationDocumentSplitter.SplitGraph graph = splitter
                    .splitEvent(compiled.exactEvent());
            CoordinationFragmentInventory portableInventory =
                    CoordinationFragmentInventory.from(graph);
            if (!portableInventory.inventoryIdentity().equals(
                    inventory.inventoryIdentity())) {
                throw new IllegalStateException(
                        "Portable event re-split changed inventory identity");
            }
            admitGraph(graph, portableInventory);
        }
        return compiled.storedEvent(orderKey);
    }

    private Node exactRoot(CoordinationFragmentInventory inventory) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        Node retained = rootViewCache.find(checked);
        if (retained != null) {
            return retained;
        }
        Node reconstructed = checked.reconstruct(
                fragmentStore.canonicalFragmentProvider());
        rootViewCache.install(checked, reconstructed);
        return reconstructed;
    }

    private Node exactRootForIndexedPlanning(
            CoordinationFragmentInventory inventory) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        Node retained = rootViewCache.findRetained(checked);
        if (retained != null) {
            return retained;
        }
        Node reconstructed = checked.reconstruct(
                fragmentStore.canonicalFragmentProvider());
        rootViewCache.install(checked, reconstructed);
        return reconstructed;
    }


    private Node materializeExact(Node supplied, String label) {
        Node value = Objects.requireNonNull(supplied, label).clone();
        if (!value.isReferenceOnly()) return value;
        BlueOperationResult<FrozenNode> materialized =
                contractsHost.materializeVerifiedExactReference(value);
        if (materialized.outcome() != BlueOperationOutcome.ESTABLISHED) {
            throw new IllegalStateException(
                    "Exact " + label + " could not be established: "
                            + materialized.outcome() + " "
                            + materialized.reason().orElse(""));
        }
        return materialized.requireEstablished().toNode();
    }

    /**
     * Re-expands exact PROCESS output references that are owned by the prior
     * admitted Root before incremental physical re-fragmentation.
     *
     * <p>Language deliberately returns the selected PROCESS representation,
     * so unchanged subtrees can remain pure references. Those references are
     * not authored external dependencies: they name exact content already
     * admitted by this session. Reconstructing them here prevents a later
     * transition from retaining a PROCESS view that points at a fragment no
     * longer present in the resulting inventory. Runtime/type references that
     * have no expanded prior-Root node remain cold.</p>
     */
    private static Node materializeRetainedResultReferences(
            Node processResult,
            Node priorExactRoot) {
        Map<String, Node> retainedByIdentity = new LinkedHashMap<>();
        indexExpandedNodes(
                Objects.requireNonNull(priorExactRoot, "priorExactRoot"),
                retainedByIdentity,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
        return expandRetainedReferences(
                Objects.requireNonNull(processResult, "processResult"),
                retainedByIdentity,
                new LinkedHashSet<String>());
    }

    private static void indexExpandedNodes(
            Node node,
            Map<String, Node> retainedByIdentity,
            Set<Node> visited) {
        if (!visited.add(node) || node.isReferenceOnly()) {
            return;
        }
        retainedByIdentity.putIfAbsent(
                DirectBlueIdCalculator.calculateBlueId(node),
                node);
        visitChildren(node, child -> indexExpandedNodes(
                child, retainedByIdentity, visited));
    }

    private static Node expandRetainedReferences(
            Node supplied,
            Map<String, Node> retainedByIdentity,
            Set<String> activeIdentities) {
        Node source = supplied;
        String activatedIdentity = null;
        if (source.isReferenceOnly()) {
            String identity = source.getBlueId();
            Node retained = retainedByIdentity.get(identity);
            if (retained == null || !activeIdentities.add(identity)) {
                return source.clone();
            }
            source = retained;
            activatedIdentity = identity;
        }

        Node result = source.clone();
        result.type(expandNullable(
                source.getType(), retainedByIdentity, activeIdentities));
        result.itemType(expandNullable(
                source.getItemType(), retainedByIdentity, activeIdentities));
        result.keyType(expandNullable(
                source.getKeyType(), retainedByIdentity, activeIdentities));
        result.valueType(expandNullable(
                source.getValueType(), retainedByIdentity, activeIdentities));
        result.contracts(expandNullable(
                source.getContracts(), retainedByIdentity, activeIdentities));
        result.blue(expandNullable(
                source.getBlue(), retainedByIdentity, activeIdentities));
        if (source.getItems() != null) {
            List<Node> items = new ArrayList<>();
            for (Node item : source.getItems()) {
                items.add(expandRetainedReferences(
                        item, retainedByIdentity, activeIdentities));
            }
            result.items(items);
        }
        if (source.getProperties() != null) {
            Map<String, Node> properties = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                properties.put(
                        entry.getKey(),
                        expandRetainedReferences(
                                entry.getValue(),
                                retainedByIdentity,
                                activeIdentities));
            }
            result.properties(properties);
        }
        if (activatedIdentity != null) {
            activeIdentities.remove(activatedIdentity);
        }
        return result;
    }

    private static Node expandNullable(
            Node value,
            Map<String, Node> retainedByIdentity,
            Set<String> activeIdentities) {
        return value == null
                ? null
                : expandRetainedReferences(
                        value, retainedByIdentity, activeIdentities);
    }

    private static void visitChildren(
            Node node,
            java.util.function.Consumer<Node> visitor) {
        if (node.getType() != null) visitor.accept(node.getType());
        if (node.getItemType() != null) visitor.accept(node.getItemType());
        if (node.getKeyType() != null) visitor.accept(node.getKeyType());
        if (node.getValueType() != null) visitor.accept(node.getValueType());
        if (node.getContracts() != null) visitor.accept(node.getContracts());
        if (node.getBlue() != null) visitor.accept(node.getBlue());
        if (node.getItems() != null) {
            node.getItems().forEach(visitor);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(visitor);
        }
    }

    private ManagedDocumentSnapshot requireActiveSession(
            DocumentSessionId id) {
        ManagedDocumentSnapshot session = session(id);
        if (session.status() != ManagedDocumentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Managed session is inactive: " + id);
        }
        return session;
    }

    private void requireEnvironment(ManagedDocumentSnapshot session) {
        if (!environmentIdentity.equals(session.environmentIdentity())) {
            throw new IllegalStateException(
                    "Managed session belongs to another runtime environment");
        }
    }

    private void requireCurrentPlan(
            CoordinationProcessingPlan plan,
            ManagedDocumentSnapshot current) {
        requireEnvironment(current);
        if (!plan.session().sessionId().equals(current.sessionId())
                || plan.session().currentEpoch() != current.currentEpoch()
                || !plan.session().currentRootBlueId().equals(
                        current.currentRootBlueId())
                || !plan.session().environmentIdentity().equals(
                        current.environmentIdentity())
                || !plan.session().committedFrontier().equals(
                        current.committedFrontier())
                || !plan.session().subscriptions().digest().equals(
                        current.subscriptions().digest())
                || !plan.session().fragmentInventoryIdentity().equals(
                        current.fragmentInventoryIdentity())) {
            throw new IllegalStateException(
                    "Processing plan is stale for the current session");
        }
    }

    private static void requireInvocationBindings(
            CoordinationProcessingPlan plan,
            ManagedDocumentSnapshot current,
            LoadedProcessingBundle bundle,
            PlatformProcessInvocation invocation) {
        CoordinationPreparedDelivery prepared = plan.preparedDelivery();
        String rootBlueId = plan.rootReference().getBlueId();
        String eventBlueId = plan.eventReference().getBlueId();
        Optional<ProcessingBundlePlanBinding> optionalBinding =
                bundle.planBinding();
        if (!optionalBinding.isPresent()) {
            throw new IllegalStateException(
                    "Processing bundle is not bound to an immutable plan");
        }
        ProcessingBundlePlanBinding binding = optionalBinding.get();
        if (!binding.sessionId().equals(current.sessionId())
                || binding.epoch() != current.currentEpoch()
                || !binding.rootBlueId().equals(rootBlueId)
                || !binding.eventBlueId().equals(eventBlueId)
                || !binding.planIdentity().equals(plan.planIdentity())
                || !binding.subscriptionDigest().equals(
                        current.subscriptions().digest())
                || !binding.environmentIdentity().equals(
                        current.environmentIdentity())) {
            throw new IllegalStateException(
                    "Processing bundle does not bind the exact current "
                            + "session, epoch, Root, event, plan, "
                            + "subscriptions, and environment");
        }
        if (!rootBlueId.equals(prepared.rootReference().getBlueId())
                || !eventBlueId.equals(
                        prepared.eventReference().getBlueId())
                || !rootBlueId.equals(prepared.evidence().rootBlueId())
                || !eventBlueId.equals(prepared.evidence().eventBlueId())
                || !current.subscriptions().digest().equals(
                        prepared.subscriptionSnapshotIdentity())
                || prepared.deliveryPlan().managedRootRevision()
                        != current.subscriptions().rootRevision()
                || prepared.deliveryPlan().indexedRootRevision()
                        != current.subscriptions().rootRevision()
                || !prepared.deliveryPlan().eventOrderKey().equals(
                        prepared.evidence().eventOrderKey())
                || invocation.deliveryPlan()
                        != prepared.deliveryPlan()
                || invocation.nodeProvider()
                        != bundle.exactProvider()) {
            throw new IllegalStateException(
                    "Platform invocation does not bind the exact current "
                            + "session, plan, Root, event, subscriptions, and "
                            + "request-local provider");
        }
    }

    private static void requirePlatformCompanion(
            ManagedDocumentSnapshot current,
            CoordinationProcessingPlan plan,
            PlatformProcessingResult platform) {
        DocumentProcessingResult process = platform.processResult();
        PlatformCommitCompanion companion = platform.commitCompanion();
        long expectedRevision = current.subscriptions().rootRevision();
        long resultingRevision = process.commits()
                ? expectedRevision + 1L
                : expectedRevision;
        if (!current.currentRootBlueId().equals(
                        companion.expectedRootBlueId())
                || !plan.eventReference().getBlueId().equals(
                        companion.eventBlueId())
                || companion.expectedRootRevision()
                        != expectedRevision
                || companion.resultingRootRevision()
                        != resultingRevision
                || !plan.preparedDelivery().deliveryPlan()
                        .eventOrderKey().equals(
                                companion.eventOrderKey())
                || companion.commitsRootAndOutbox()
                        != process.commits()) {
            throw new IllegalStateException(
                    "Platform commit companion does not bind the planned "
                            + "Root, revision, event, order, and commit decision");
        }
    }

    private List<String> preferredPrefetch(
            PrefetchPolicy policy,
            CoordinationPreparedDelivery prepared,
            CoordinationFragmentInventory eventInventory) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        result.addAll(prepared.requiredSeedFragmentIdentities());
        if (policy != PrefetchPolicy.MINIMUM_BYTES) {
            result.addAll(prepared.prefetchIdentities());
        }
        if (policy == PrefetchPolicy.MINIMUM_ROUND_TRIPS) {
            // The verified delivery preparation already contains every
            // statically proven selected-chain dependency. Sweeping all
            // metadata on an ancestor scope also selects unrelated operation
            // bodies that merely share Root, defeating physical locality.
            result.addAll(eventInventory.fragmentBlueIds());
        }
        return Collections.unmodifiableList(new ArrayList<String>(result));
    }

    private static void requireSubscriptionDelta(
            CoordinationSubscriptionUpdate update,
            SubscriptionDelta companion) {
        List<SubscriptionDelta.Entry> added =
                new ArrayList<SubscriptionDelta.Entry>();
        for (CoordinationSubscriptionOccurrence occurrence : update.added()) {
            added.add(occurrence.toSubscriptionDeltaEntry());
        }
        List<SubscriptionDelta.Entry> removed =
                new ArrayList<SubscriptionDelta.Entry>();
        for (CoordinationSubscriptionOccurrence occurrence : update.retired()) {
            removed.add(occurrence.toSubscriptionDeltaEntry());
        }
        SubscriptionDelta projected = new SubscriptionDelta(added, removed);
        if (!projected.added().equals(companion.added())
                || !projected.removed().equals(companion.removed())) {
            throw new IllegalStateException(
                    "Coordination subscription projection differs from the "
                            + "platform commit companion: projectedAdded="
                            + describeDeltaEntries(projected.added())
                            + ", companionAdded="
                            + describeDeltaEntries(companion.added())
                            + ", projectedRemoved="
                            + describeDeltaEntries(projected.removed())
                            + ", companionRemoved="
                            + describeDeltaEntries(companion.removed()));
        }
    }

    private static List<String> describeDeltaEntries(
            List<SubscriptionDelta.Entry> entries) {
        List<String> result = new ArrayList<String>();
        for (SubscriptionDelta.Entry entry : entries) {
            result.add(entry.scopePath() + "|" + entry.channelKey()
                    + "|" + entry.effectiveTypeBlueId()
                    + "|" + entry.sourceContributionNodeBlueIds()
                    + "|" + entry.order()
                    + "|" + entry.subscriptionKeys()
                    + "|" + entry.checkpointDomainBlueId()
                    + "|" + entry.dependencies()
                            .deterministicDependencyNodeBlueIds()
                    + "|" + entry.activationRootRevision()
                    + "|" + entry.startAfterExternalOrderKey()
                    + "|" + entry.endAtRootRevision());
        }
        return result;
    }

    private static List<String> rootEventBlueIds(
            DocumentProcessingResult process) {
        List<String> result = new ArrayList<String>();
        for (Node event : process.events()) {
            result.add(DirectBlueIdCalculator.calculateBlueId(event));
        }
        return Collections.unmodifiableList(result);
    }

    private void requireCurrentRuntimeGeneration() {
        blue.language.processor.ProcessorRuntimeAccess contractsAccess =
                contracts.runtimeAccess();
        blue.language.processor.ProcessorRuntimeAccess processorAccess =
                documentProcessor.administration().runtimeAccess();
        if (!contractsAccess.isCurrent()
                || !processorAccess.isCurrent()) {
            throw new IllegalStateException(
                    "Engine services do not expose a current immutable runtime");
        }
        LanguageRuntimeAccess contractsLanguage =
                contractsAccess.languageRuntime();
        LanguageRuntimeAccess processorLanguage =
                processorAccess.languageRuntime();
        if (!contractsLanguage.languageVersion().equals(
                        processorLanguage.languageVersion())
                || !contractsLanguage.canonicalRegistryIdentity().equals(
                        processorLanguage.canonicalRegistryIdentity())
                || !contractsLanguage.preprocessingAliases().equals(
                        processorLanguage.preprocessingAliases())
                || !contractsLanguage.environmentImports().equals(
                        processorLanguage.environmentImports())) {
            throw new IllegalArgumentException(
                    "BlueContracts and DocumentProcessor belong to different "
                            + "Language runtime generations");
        }
        String coordinationIdentity =
                CoordinationProcessors.runtimeRegistrationIdentity(
                        documentProcessor);
        requireText(coordinationIdentity, "coordinationRuntimeIdentity");
    }

    private String deriveEnvironmentIdentity(Builder builder) {
        LanguageRuntimeAccess language = contracts.runtimeAccess()
                .languageRuntime();
        String providerDomain = builder.providerEvidenceDomain != null
                ? builder.providerEvidenceDomain
                : fragmentStore.getClass().getName();
        String externalOrderPolicy = builder.externalOrderPolicyIdentity != null
                ? builder.externalOrderPolicyIdentity
                : "blue.coordination/external-order/host-supplied-total/1.0";
        String initialPolicy = builder.initialSubscriptionPolicyIdentity != null
                ? builder.initialSubscriptionPolicyIdentity
                : CoordinationSubscriptionSnapshot.ALGORITHM_IDENTITY;
        return identity(
                "engine-environment",
                language.languageVersion(),
                language.canonicalRegistryIdentity(),
                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY,
                contractRegistryIdentity(documentProcessor),
                GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY,
                CoordinationProcessors.runtimeRegistrationIdentity(
                        documentProcessor),
                BexCompiledProgramKey.BEX_RUNTIME_REGISTRY_IDENTITY,
                BexGasCounter.MANIFEST_IDENTITY,
                providerDomain,
                externalOrderPolicy,
                initialPolicy,
                fragmentStore.fragmentationProfileIdentity(),
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                hostQuotaSchedule.manifestSha256(),
                gasScheduleIdentity);
    }

    private static String contractRegistryIdentity(
            DocumentProcessor processor) {
        List<String> registrations = new ArrayList<String>();
        for (Map.Entry<String, ContractProcessor<? extends Contract>> entry
                : processor.administration()
                        .contractRegistry()
                        .processors().entrySet()) {
            ContractProcessor<? extends Contract> registered =
                    entry.getValue();
            Class<? extends Contract> contractType =
                    registered.contractType();
            registrations.add(
                    entry.getKey()
                            + "\u0000"
                            + registered.getClass().getName()
                            + "\u0000"
                            + (contractType == null
                            ? ""
                            : contractType.getName()));
        }
        Collections.sort(registrations);
        return identity(
                "contracts-registry",
                registrations.toArray(
                        new String[registrations.size()]));
    }

    private static String identity(String kind, String... values) {
        List<Node> items = new ArrayList<Node>();
        for (String value : values) {
            items.add(new Node().value(requireText(value, kind + " value")));
        }
        return DirectBlueIdCalculator.calculateBlueId(
                new Node()
                        .properties("kind", new Node().value(
                                "blue.coordination/engine/" + kind + "/1.0"))
                        .properties("values", new Node().items(items)));
    }

    private void notifyAdmission(
            DocumentRegistration registration,
            DocumentAdmissionResult result) {
        isolate(() -> observer.onAdmission(registration, result));
    }
    private void notifyPlan(CoordinationProcessingPlan plan) {
        isolate(() -> observer.onPlan(plan));
    }
    private void notifyPlanTiming(
            ProcessRequest request,
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onPlanTiming(
                request, plan, elapsedNanos));
    }
    private void notifyIndexedPlanTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onIndexedPlanTiming(plan, elapsedNanos));
    }
    private void notifyBatchLoad(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle) {
        isolate(() -> observer.onBatchLoad(plan, bundle));
    }
    private void notifyBundleLoadTiming(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle,
            long elapsedNanos) {
        isolate(() -> observer.onBundleLoadTiming(
                plan, bundle, elapsedNanos));
    }
    private void notifyPlatformProcessTiming(
            CoordinationProcessingPlan plan,
            PlatformProcessingResult result,
            long elapsedNanos) {
        isolate(() -> observer.onPlatformProcessTiming(
                plan, result, elapsedNanos));
    }
    private void notifyProcessInputMaterializationTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onProcessInputMaterializationTiming(
                plan, elapsedNanos));
    }
    private void notifyHybridFrontierProofTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onHybridFrontierProofTiming(
                plan, elapsedNanos));
    }
    private void notifyRetainedReferenceMaterializationTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onRetainedReferenceMaterializationTiming(
                plan, elapsedNanos));
    }
    private void notifySubscriptionProjectionTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onSubscriptionProjectionTiming(
                plan, elapsedNanos));
    }
    private void notifySubscriptionProjectionColdFallback(
            CoordinationProcessingPlan plan,
            String reason) {
        isolate(() -> observer.onSubscriptionProjectionColdFallback(
                plan, reason == null ? "unspecified" : reason));
    }
    private void notifyFragmentTransitionPlanningTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onFragmentTransitionPlanningTiming(
                plan, elapsedNanos));
    }
    private void notifyPreparedResultContextTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        isolate(() -> observer.onPreparedResultContextTiming(
                plan, elapsedNanos));
    }
    private void notifySubscriptionAndFragmentTransitionTiming(
            CoordinationProcessingPlan plan,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            CoordinationFragmentTransition fragmentTransition,
            long elapsedNanos) {
        isolate(() -> observer.onSubscriptionAndFragmentTransitionTiming(
                plan,
                subscriptionUpdate,
                fragmentTransition,
                elapsedNanos));
    }
    private void notifyProcessComplete(CoordinationTransition transition) {
        isolate(() -> observer.onProcessComplete(transition));
    }
    private void notifyFragmentTransition(
            CoordinationFragmentTransition transition) {
        isolate(() -> observer.onFragmentTransition(transition));
    }
    private void notifyCommit(CommitOutcome outcome) {
        isolate(() -> observer.onCommit(outcome));
    }
    private void notifyCommitTiming(
            CoordinationTransition transition,
            CommitOutcome outcome,
            long elapsedNanos) {
        isolate(() -> observer.onCommitTiming(
                transition, outcome, elapsedNanos));
    }
    private void notifyProcessAndCommitTiming(
            ProcessRequest request,
            CommitOutcome outcome,
            long elapsedNanos) {
        isolate(() -> observer.onProcessAndCommitTiming(
                request, outcome, elapsedNanos));
    }

    private static long elapsedNanos(long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    private static void isolate(Runnable notification) {
        try {
            notification.run();
        } catch (Throwable ignored) {
            // Observation is explicitly outside semantic execution/commit.
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("CoordinationProcessingEngine is closed");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }

    /** Exact authoritative state needed to rebuild one restored Root context. */
    private static final class RootContextRestore {
        private final ManagedDocumentSnapshot session;
        private final CoordinationFragmentInventory inventory;

        private RootContextRestore(
                ManagedDocumentSnapshot session,
                CoordinationFragmentInventory inventory) {
            this.session = Objects.requireNonNull(session, "session");
            this.inventory = Objects.requireNonNull(inventory, "inventory");
        }
    }

    /** Mutable single-owner configuration for one immutable engine. */
    public static final class Builder {
        private BlueContracts contracts;
        private DocumentProcessor documentProcessor;
        private CoordinationFragmentStore fragmentStore;
        private CoordinationSessionStore sessionStore;
        private CoordinationProcessingBundleLoader bundleLoader;
        private CoordinationTransitionMemoStore transitionMemoStore;
        private CoordinationHostQuotaSchedule hostQuotaSchedule;
        private CoordinationProcessingEngineObserver observer;
        private String environmentIdentity;
        private String providerEvidenceDomain;
        private String externalOrderPolicyIdentity;
        private String initialSubscriptionPolicyIdentity;
        private String gasScheduleIdentity;
        private int rootViewCacheMaximumSize =
                DEFAULT_ROOT_VIEW_CACHE_MAXIMUM_SIZE;
        private int maximumCachedEventAdmissions = 512;
        private long maximumCachedEventAdmissionWeightBytes =
                CoordinationEventAdmissionCompiler
                        .DEFAULT_EVENT_CACHE_MAXIMUM_WEIGHT_BYTES;
        private int maximumCachedFragmentEvidence = 16_384;
        private long maximumCachedFragmentEvidenceWeightBytes =
                CoordinationEventAdmissionCompiler
                        .DEFAULT_FRAGMENT_CACHE_MAXIMUM_WEIGHT_BYTES;
        private Map<String, Node> retainedRootViews =
                Collections.emptyMap();
        private boolean ownsRuntimes;

        public Builder contracts(BlueContracts value) {
            contracts = Objects.requireNonNull(value, "contracts");
            return this;
        }
        public Builder documentProcessor(DocumentProcessor value) {
            documentProcessor = Objects.requireNonNull(
                    value, "documentProcessor");
            return this;
        }
        public Builder fragmentStore(CoordinationFragmentStore value) {
            fragmentStore = Objects.requireNonNull(value, "fragmentStore");
            return this;
        }
        public Builder sessionStore(CoordinationSessionStore value) {
            sessionStore = Objects.requireNonNull(value, "sessionStore");
            return this;
        }
        public Builder bundleLoader(CoordinationProcessingBundleLoader value) {
            bundleLoader = Objects.requireNonNull(value, "bundleLoader");
            return this;
        }
        public Builder transitionMemoStore(
                CoordinationTransitionMemoStore value) {
            transitionMemoStore = value;
            return this;
        }
        public Builder hostQuotaSchedule(CoordinationHostQuotaSchedule value) {
            hostQuotaSchedule = Objects.requireNonNull(
                    value, "hostQuotaSchedule");
            return this;
        }
        public Builder observer(CoordinationProcessingEngineObserver value) {
            observer = Objects.requireNonNull(value, "observer");
            return this;
        }
        public Builder environmentIdentity(String value) {
            environmentIdentity = requireText(value, "environmentIdentity");
            return this;
        }
        public Builder providerEvidenceDomain(String value) {
            providerEvidenceDomain = requireText(
                    value, "providerEvidenceDomain");
            return this;
        }
        public Builder externalOrderPolicyIdentity(String value) {
            externalOrderPolicyIdentity = requireText(
                    value, "externalOrderPolicyIdentity");
            return this;
        }
        public Builder initialSubscriptionPolicyIdentity(String value) {
            initialSubscriptionPolicyIdentity = requireText(
                    value, "initialSubscriptionPolicyIdentity");
            return this;
        }
        public Builder gasScheduleIdentity(String value) {
            gasScheduleIdentity = requireText(value, "gasScheduleIdentity");
            return this;
        }
        /**
         * Sets the hard bound for process-local complete Root views.
         *
         * @param value positive maximum number of retained Roots
         * @return this builder
         */
        public Builder rootViewCacheMaximumSize(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "rootViewCacheMaximumSize must be positive");
            }
            rootViewCacheMaximumSize = value;
            return this;
        }
        /** Bounds immutable exact-event admission evidence per engine. */
        public Builder maximumCachedEventAdmissions(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "maximumCachedEventAdmissions must be positive");
            }
            maximumCachedEventAdmissions = value;
            return this;
        }
        /** Bounds retained exact-event admission graphs in bytes. */
        public Builder maximumCachedEventAdmissionWeightBytes(long value) {
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "maximumCachedEventAdmissionWeightBytes must be "
                                + "positive");
            }
            maximumCachedEventAdmissionWeightBytes = value;
            return this;
        }
        /** Bounds shared canonical direct-fragment evidence per engine. */
        public Builder maximumCachedFragmentEvidence(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "maximumCachedFragmentEvidence must be positive");
            }
            maximumCachedFragmentEvidence = value;
            return this;
        }
        /** Bounds retained canonical fragment graphs in bytes. */
        public Builder maximumCachedFragmentEvidenceWeightBytes(long value) {
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "maximumCachedFragmentEvidenceWeightBytes must be "
                                + "positive");
            }
            maximumCachedFragmentEvidenceWeightBytes = value;
            return this;
        }
        /** Seeds verified current Root views restored from a local checkpoint. */
        public Builder retainedRootViews(Map<String, Node> value) {
            Map<String, Node> copied = new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                    value, "retainedRootViews").entrySet()) {
                copied.put(
                        requireText(entry.getKey(), "inventoryIdentity"),
                        Objects.requireNonNull(
                                entry.getValue(), "retainedRootView").clone());
            }
            retainedRootViews = Collections.unmodifiableMap(copied);
            return this;
        }
        public Builder transferRuntimeOwnership(boolean value) {
            ownsRuntimes = value;
            return this;
        }
        public CoordinationProcessingEngine build() {
            return new CoordinationProcessingEngine(this);
        }
    }
}
