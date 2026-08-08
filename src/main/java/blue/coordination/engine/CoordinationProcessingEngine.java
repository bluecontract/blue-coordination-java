package blue.coordination.engine;

import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.gas.BexGasCounter;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationEventAdmissionCompiler;
import blue.coordination.engine.api.CoordinationEventShapeCompiler;
import blue.coordination.engine.api.CoordinationEventShapeInstance;
import blue.coordination.engine.api.CoordinationEventShapeMetrics;
import blue.coordination.engine.api.CoordinationEventShapePatch;
import blue.coordination.engine.api.CoordinationEventShapeTemplate;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationFragmentTransitionWorkSnapshot;
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
import blue.coordination.engine.spi.CoordinationCanonicalFragmentHandleStore;
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
import blue.coordination.engine.fastpath.FastFragmentDelta;
import blue.coordination.engine.fastpath.HybridResultFrontier;
import blue.coordination.engine.fastpath.IndexedRetainedReferenceResolver;
import blue.coordination.engine.fastpath.PreparedRootContextCache;
import blue.coordination.engine.fastpath.PreparedRootExecutionContext;
import blue.coordination.engine.fastpath.RetainedReferenceIndex;
import blue.coordination.engine.fastpath.VerifiedHybridResultFrontier;
import blue.coordination.engine.fastpath.VerifiedFragmentTransitionFrontier;
import blue.coordination.engine.fastpath.ActivePathSet;
import blue.coordination.engine.fastpath.ReferenceCutConfiguration;
import blue.coordination.engine.fastpath.ReferenceCutDecision;
import blue.coordination.engine.fastpath.InventoryReferenceCutRootCompiler;
import blue.coordination.engine.fastpath.ReferenceCutFragmentSource;
import blue.coordination.engine.fastpath.ReferenceCutMetrics;
import blue.coordination.engine.fastpath.ReferenceCutPlan;
import blue.coordination.engine.fastpath.ReferenceCutPlanner;
import blue.coordination.engine.fastpath.ReferenceCutPolicy;
import blue.coordination.engine.fastpath.ReferenceCutRootArtifact;
import blue.coordination.engine.fastpath.ReferenceCutRootCache;
import blue.coordination.engine.fastpath.ReferenceCutRootCacheKey;
import blue.coordination.engine.fastpath.ReferenceCutRootCompiler;
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
import blue.language.model.Schema;
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

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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
    private static final String PLANNING_PROJECTION_CACHE_ALGORITHM_IDENTITY =
            "blue.coordination/shared-planning-projection-cache/1.0";
    private static final String PLATFORM_CONTRACTS_PATH = "/contracts";
    private static final String PREPARED_CHECKPOINT_ALGORITHM_IDENTITY =
            "blue.coordination/prepared-root-checkpoint/2.0";

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
    private final ProjectionGenerationCache.SharedBacking
            planningProjectionCacheBacking;
    private final ProjectionGenerationCache planningProjectionCache;
    private final CoordinationPreparedDeliveryMemoizer
            preparedDeliveryMemoizer;
    private final String planningRuntimeIdentity;
    private final CoordinationFragmentTransitionPlanner transitionPlanner;
    private final CoordinationInventoryRootViewCache rootViewCache;
    private final NodeProvider runtimeProvider;
    private final String environmentIdentity;
    private final String gasScheduleIdentity;
    private final String referenceCutProviderStorageGenerationAuthority;
    private final String preparedCheckpointBindingIdentity;
    private final CoordinationEventAdmissionCompiler eventAdmissionCompiler;
    private final CoordinationEventAdmissionMetrics eventAdmissionMetrics;
    private final CoordinationEventShapeMetrics eventShapeMetrics;
    private final CoordinationEventShapeCompiler eventShapeCompiler;
    private final PreparedRootContextCache preparedRootContexts;
    private final ReferenceCutConfiguration referenceCutConfiguration;
    private final ReferenceCutMetrics referenceCutMetrics;
    private final ReferenceCutPlanner referenceCutPlanner;
    private final ReferenceCutRootCompiler referenceCutRootCompiler;
    private final InventoryReferenceCutRootCompiler
            inventoryReferenceCutRootCompiler;
    private final ReferenceCutRootCache.SharedBacking
            referenceCutRootCacheBacking;
    private final ReferenceCutRootCache referenceCutRootCache;
    private final Object preparedRootOwnership;
    private final PreparedCheckpointState acceptedPreparedCheckpointState;
    private final PreparedCheckpointLease acceptedPreparedCheckpointLease;
    private final VerifiedNodeAccessAuthority verifiedNodeAccessAuthority;
    private final LinkedHashMap<String, PendingPreparedGeneration>
            pendingPreparedRootContexts;
    private final int pendingPreparedRootContextMaximumSize;
    private final long pendingPreparedRootContextMaximumWeightBytes;
    private long pendingPreparedRootContextWeightBytes;
    private final LinkedHashMap<String, PlannedReferenceCutRoot>
            plannedReferenceCutRoots;
    private final int plannedReferenceCutRootMaximumSize;
    private final long plannedReferenceCutRootMaximumWeightBytes;
    private long plannedReferenceCutRootWeightBytes;
    private final boolean ownsRuntimes;
    private final AtomicLong checkpointPreparedContextReuses =
            new AtomicLong();
    private final AtomicLong checkpointPreparedContextFallbacks =
            new AtomicLong();
    private final AtomicLong checkpointPreparedContextRebuilds =
            new AtomicLong();
    private final AtomicLong transitionFrontierBoundaryGrafts =
            new AtomicLong();
    private final AtomicLong transitionExpandedNodesVisited =
            new AtomicLong();
    private final AtomicLong transitionFullRootMaterializations =
            new AtomicLong();
    private final AtomicLong transitionRetainedIndexFullScans =
            new AtomicLong();

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
        this.projectionFastPathMetrics = new FastPathWorkMetrics();
        this.deltaSubscriptionProjector =
                new CoordinationDeltaSubscriptionProjector(
                        projectionFastPathMetrics);
        this.commitProjectionEvidenceBuilder =
                new CoordinationCommitProjectionEvidenceBuilder(
                        projectionFastPathMetrics);
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
        this.referenceCutConfiguration = Objects.requireNonNull(
                builder.referenceCutConfiguration,
                "referenceCutConfiguration");
        this.referenceCutMetrics = new ReferenceCutMetrics();
        this.referenceCutPlanner = new ReferenceCutPlanner(
                ReferenceCutPolicy.strictDefaults());
        this.referenceCutRootCompiler = new ReferenceCutRootCompiler(
                referenceCutPlanner,
                referenceCutMetrics);
        this.inventoryReferenceCutRootCompiler =
                new InventoryReferenceCutRootCompiler(
                        referenceCutPlanner,
                        ReferenceCutFragmentSource.bestAvailable(
                                fragmentStore,
                                referenceCutMetrics),
                        referenceCutMetrics);
        this.verifiedNodeAccessAuthority =
                new VerifiedNodeAccessAuthority();
        this.pendingPreparedRootContexts =
                new LinkedHashMap<String, PendingPreparedGeneration>(
                        Math.min(16, builder.rootViewCacheMaximumSize),
                        0.75f,
                        true);
        this.pendingPreparedRootContextMaximumSize =
                builder.rootViewCacheMaximumSize;
        this.pendingPreparedRootContextMaximumWeightBytes =
                Math.addExact(
                        preparedRootContexts.maximumWeightBytes(),
                        DEFAULT_PLANNING_CACHE_MAXIMUM_WEIGHT);
        this.plannedReferenceCutRoots =
                new LinkedHashMap<String, PlannedReferenceCutRoot>(
                        Math.min(16, builder.rootViewCacheMaximumSize),
                        0.75f,
                        true);
        this.plannedReferenceCutRootMaximumSize =
                builder.rootViewCacheMaximumSize;
        this.plannedReferenceCutRootMaximumWeightBytes =
                referenceCutConfiguration.maximumCacheWeightBytes();
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
        String providerGenerationAuthority =
                builder.providerEvidenceDomain != null
                        ? builder.providerEvidenceDomain
                        : environmentIdentity + "|provider="
                                + runtimeProvider.getClass().getName();
        String storageGenerationAuthority =
                fragmentStore
                        instanceof CoordinationCanonicalFragmentHandleStore
                        ? requireText(
                                ((CoordinationCanonicalFragmentHandleStore)
                                        fragmentStore)
                                        .canonicalFragmentStorageGenerationAuthority(),
                                "canonicalFragmentStorageGenerationAuthority")
                        : requireText(
                                fragmentStore.storageGenerationAuthority(),
                                "storageGenerationAuthority");
        this.referenceCutProviderStorageGenerationAuthority = identity(
                "reference-cut-provider-storage-generation",
                providerGenerationAuthority,
                storageGenerationAuthority);
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
                        referenceCutProviderStorageGenerationAuthority,
                        splitter,
                        builder.maximumCachedEventAdmissions,
                        builder.maximumCachedEventAdmissionWeightBytes,
                        builder.maximumCachedFragmentEvidence,
                        builder.maximumCachedFragmentEvidenceWeightBytes,
                        eventAdmissionMetrics);
        this.eventShapeMetrics = new CoordinationEventShapeMetrics();
        this.eventShapeCompiler = new CoordinationEventShapeCompiler(
                eventAdmissionCompiler, eventShapeMetrics);
        this.planningRuntimeIdentity = identity(
                "admitted-planning-runtime",
                environmentIdentity,
                CoordinationSubscriptionSnapshot.VERSION,
                CoordinationSubscriptionSnapshot.ALGORITHM_IDENTITY);
        this.preparedCheckpointBindingIdentity = identity(
                PREPARED_CHECKPOINT_ALGORITHM_IDENTITY,
                environmentIdentity,
                planningRuntimeIdentity,
                gasScheduleIdentity,
                fragmentStore.fragmentationProfileIdentity(),
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                referenceCutProviderStorageGenerationAuthority,
                referenceCutConfigurationIdentity(
                        referenceCutConfiguration),
                PLANNING_PROJECTION_CACHE_ALGORITHM_IDENTITY,
                Integer.toString(builder.rootViewCacheMaximumSize),
                Long.toString(DEFAULT_PLANNING_CACHE_MAXIMUM_WEIGHT));
        PreparedCheckpointState suppliedCheckpointState =
                builder.preparedCheckpointState;
        PreparedCheckpointLease checkpointLease =
                suppliedCheckpointState == null
                ? null
                : suppliedCheckpointState.tryAcquire(
                        preparedCheckpointBindingIdentity,
                        contracts,
                        documentProcessor);
        if (checkpointLease != null) {
            this.preparedRootOwnership =
                    checkpointLease.ownerCapability;
            this.acceptedPreparedCheckpointState =
                    suppliedCheckpointState;
            this.acceptedPreparedCheckpointLease = checkpointLease;
        } else {
            this.preparedRootOwnership = new Object();
            this.acceptedPreparedCheckpointState = null;
            this.acceptedPreparedCheckpointLease = null;
        }
        this.referenceCutRootCacheBacking =
                acceptedPreparedCheckpointLease != null
                        ? acceptedPreparedCheckpointLease
                                .referenceCutRootCacheBacking
                        : ReferenceCutRootCache.sharedBacking(
                                referenceCutConfiguration
                                        .maximumCacheWeightBytes());
        this.referenceCutRootCache = new ReferenceCutRootCache(
                referenceCutRootCacheBacking,
                referenceCutMetrics);
        this.planningFastPathMetrics = new FastPathWorkMetrics();
        this.planningProjectionCompiler =
                new CoordinationPlanningProjectionCompiler(
                        planningFastPathMetrics);
        this.planningProjectionCacheBacking =
                acceptedPreparedCheckpointLease != null
                        ? acceptedPreparedCheckpointLease
                                .planningProjectionCacheBacking
                        : ProjectionGenerationCache.sharedBacking(
                                builder.rootViewCacheMaximumSize,
                                DEFAULT_PLANNING_CACHE_MAXIMUM_WEIGHT);
        this.planningProjectionCache = new ProjectionGenerationCache(
                planningProjectionCacheBacking);
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
                environmentIdentity,
                verifiedNodeAccessAuthority);
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
                inventory,
                exactRootPlanningProvider(
                        graph.rootBlueId(),
                        exactDocument,
                        inventory,
                        session.subscriptions()));
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
            removePlannedReferenceCutRoots(checked.value());
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

    /**
     * Compiles one immutable operation/event shape. The authoritative full
     * splitter runs only for the sentinel prototype, never for a future exact
     * timestamp/previous-entry instance.
     */
    public CoordinationEventShapeTemplate compileEventShape(
            String shapeIdentity,
            Node resolvedPrototype,
            Collection<String> volatileLeafPointers) {
        requireOpen();
        return eventShapeCompiler.compile(
                shapeIdentity,
                materializeExact(resolvedPrototype, "resolvedPrototype"),
                volatileLeafPointers);
    }

    /** Instantiates a shared shape while charging work to this engine. */
    public CoordinationEventShapeInstance instantiateEventShape(
            CoordinationEventShapeTemplate template,
            Collection<CoordinationEventShapePatch> patches) {
        requireOpen();
        return Objects.requireNonNull(template, "template").instantiate(
                Objects.requireNonNull(patches, "patches"),
                eventShapeMetrics);
    }

    /** Admits an already verified first-seen shape instance without a split. */
    public StoredCoordinationEvent prepareEvent(
            CoordinationEventShapeInstance instance,
            ExternalOrderKey eventOrderKey) {
        requireOpen();
        CoordinationEventShapeInstance checked = Objects.requireNonNull(
                instance, "instance");
        return admitCompiledEvent(
                checked.admission(),
                Objects.requireNonNull(eventOrderKey, "eventOrderKey"));
    }

    public CoordinationEventShapeMetrics.Snapshot eventShapeMetrics() {
        return eventShapeMetrics.snapshot();
    }

    public CoordinationEventAdmissionMetrics.Snapshot
            eventAdmissionMetrics() {
        return eventAdmissionMetrics.snapshot();
    }

    /** Opaque identity of evidence accepted by this engine's event domain. */
    public String eventAdmissionDomainIdentity() {
        return eventAdmissionCompiler.admissionDomainIdentity();
    }

    /** Returns exact delta-projection hit/fallback work counters. */
    public FastPathWorkMetrics.Snapshot projectionFastPathMetrics() {
        return projectionFastPathMetrics.snapshot();
    }

    /** Returns measured production work for verified fragment transitions. */
    public CoordinationFragmentTransitionWorkSnapshot
            fragmentTransitionWorkSnapshot() {
        CoordinationFragmentTransitionWorkSnapshot planner =
                transitionPlanner.workSnapshot();
        return new CoordinationFragmentTransitionWorkSnapshot(
                planner.deltaHits(),
                planner.typedFallbacksByReason(),
                planner.fullBlueprintAttempts(),
                planner.sparseFrontierNodes(),
                planner.changedFragmentsHashed(),
                planner.unchangedFragmentsShared(),
                planner.fullResultClones(),
                transitionFullRootMaterializations.get(),
                transitionFrontierBoundaryGrafts.get(),
                transitionExpandedNodesVisited.get(),
                transitionRetainedIndexFullScans.get(),
                planner.inventoryRecordsReused(),
                planner.inventoryRecordsRebuilt(),
                planner.edgeRecordsReused(),
                planner.edgeRecordsRebuilt());
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
        ReferenceCutRootSelection rootSelection =
                referenceCutRootSelection(
                        session,
                        rootInventory,
                        () -> exactRootForIndexedPlanning(rootInventory),
                        planningScopePaths(
                                session.subscriptions(),
                                candidates,
                                rootInventory));
        Node exactRoot = rootSelection.root;
        Node exactEvent = exactRootForIndexedPlanning(eventInventory);
        NodeProvider planningProvider = exactPlanningProvider(
                session.currentRootBlueId(),
                exactRoot,
                rootInventory,
                event.eventBlueId(),
                exactEvent,
                eventInventory,
                session.subscriptions());
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
        retainPlannedReferenceCutRoot(result, rootSelection);
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
        ReferenceCutRootSelection rootSelection = null;
        if (checked.planningMode() == DeliveryPlanningMode.INDEXED) {
            rootSelection = referenceCutRootSelection(
                    session,
                    rootInventory,
                    () -> exactRootForIndexedPlanning(rootInventory),
                    planningScopePaths(
                            session.subscriptions(),
                            checked.orderedIndexedOccurrenceKeys(),
                            rootInventory));
            Node exactRoot = rootSelection.root;
            NodeProvider planningProvider = exactPlanningProvider(
                    session.currentRootBlueId(),
                    exactRoot,
                    rootInventory,
                    eventGraph.rootBlueId(),
                    exactEvent,
                    eventInventory,
                    session.subscriptions());
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
                                    eventInventory,
                                    session.subscriptions()),
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
        retainPlannedReferenceCutRoot(result, rootSelection);
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
                rootInventory,
                exactProvider);
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
                session.sessionId().value(),
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
            CoordinationFragmentInventory inventory,
            NodeProvider exactProvider) {
        try {
            return planningProjectionCache.getOrCompile(
                    generation,
                    ignored -> planningProjectionCompiler.compileAdmitted(
                            generation,
                            snapshot,
                            exactRoot,
                            Objects.requireNonNull(
                                    exactProvider,
                                    "exactProvider")));
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            planningFastPathMetrics.coldProjectionFallback();
            return null;
        }
    }

    /**
     * Builds an immutable successor projection from commit-local proof only.
     * The candidate remains private to the transition until the authoritative
     * session CAS succeeds; failed or losing transitions can never seed a
     * future planning generation.
     */
    private AdmittedProjection prepareIncrementalPlanningProjection(
            ManagedDocumentSnapshot previousSession,
            CoordinationFragmentInventory previousInventory,
            ManagedDocumentSnapshot resultingSession,
            CoordinationFragmentInventory resultingInventory,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            CoordinationCommitProjectionEvidence evidence,
            VerifiedFragmentTransitionFrontier frontier) {
        if (evidence == null || frontier == null) {
            planningFastPathMetrics.coldProjectionFallback();
            return null;
        }
        try {
            ProjectionGenerationKey previousGeneration = planningGeneration(
                    previousSession, previousInventory);
            AdmittedProjection previous = planningProjectionCache.find(
                    previousGeneration);
            if (previous == null) {
                planningFastPathMetrics.coldProjectionFallback();
                return null;
            }
            ProjectionGenerationKey nextGeneration = planningGeneration(
                    resultingSession, resultingInventory);
            return planningProjectionCompiler.advance(
                    previous,
                    nextGeneration,
                    subscriptionUpdate,
                    evidence,
                    frontier.sparseResultRoot());
        } catch (RuntimeException unavailableDerivedEvidence) {
            planningFastPathMetrics.coldProjectionFallback();
            return null;
        }
    }

    private ReferenceCutRootSelection referenceCutRootSelection(
            ManagedDocumentSnapshot session,
            CoordinationFragmentInventory inventory,
            Supplier<Node> exactRootSupplier,
            Collection<String> activePaths) {
        Supplier<Node> fullRoot = Objects.requireNonNull(
                exactRootSupplier, "exactRootSupplier");
        if (!referenceCutConfiguration.enabled()) {
            referenceCutMetrics.fullRootUsed();
            return ReferenceCutRootSelection.full(fullRoot.get());
        }
        ActivePathSet active = ActivePathSet.of(activePaths);
        ReferenceCutRootCacheKey key = new ReferenceCutRootCacheKey(
                session.currentRootBlueId(),
                inventory.inventoryIdentity(),
                active.paths(),
                environmentIdentity,
                gasScheduleIdentity,
                session.subscriptions().digest(),
                planningRuntimeIdentity,
                referenceCutProviderStorageGenerationAuthority,
                InventoryReferenceCutRootCompiler.ALGORITHM_VERSION);
        ReferenceCutRootArtifact artifact = referenceCutRootCache.peek(key);
        if (artifact == null) {
            ReferenceCutPlan preflight = referenceCutPlanner.plan(
                    inventory, active);
            if (preflight.cuts().isEmpty()
                    || preflight.cuts().size()
                            > referenceCutConfiguration.maximumCuts()
                    || InventoryReferenceCutRootCompiler
                            .estimatedFragmentReduction(inventory, preflight)
                            < referenceCutConfiguration
                                    .minimumNodeReduction()) {
                referenceCutMetrics.fullRootUsed();
                return ReferenceCutRootSelection.full(fullRoot.get());
            }
            artifact = referenceCutRootCache.getOrBuild(
                    key,
                    () -> inventoryReferenceCutRootCompiler.compile(
                            inventory, active, preflight));
        }
        ReferenceCutDecision decision = ReferenceCutDecision.evaluate(
                referenceCutConfiguration, artifact);
        if (!decision.useSparseRoot()) {
            referenceCutMetrics.fullRootUsed();
            return ReferenceCutRootSelection.full(fullRoot.get());
        }
        if (referenceCutConfiguration.mode()
                == blue.coordination.engine.fastpath.ReferenceCutMode
                        .SHADOW_DIFFERENTIAL) {
            Node exact = fullRoot.get();
            ReferenceCutRootArtifact oracle = referenceCutRootCompiler.compile(
                    inventory, exact, active);
            if (!blue.language.model.NodeWireForm.get(
                    oracle.copyForFrozenBoundary()).equals(
                            blue.language.model.NodeWireForm.get(
                                    artifact.copyForFrozenBoundary()))) {
                throw new IllegalStateException(
                        "Direct sparse-Root assembly differs from the "
                                + "full-Root reference-cut oracle");
            }
        }
        referenceCutMetrics.sparseUsed();
        return ReferenceCutRootSelection.sparse(
                artifact.copyForFrozenBoundary(),
                artifact,
                active.paths());
    }

    private void retainPlannedReferenceCutRoot(
            CoordinationProcessingPlan plan,
            ReferenceCutRootSelection selection) {
        if (selection == null || selection.artifact == null) return;
        CoordinationProcessingPlan checked = Objects.requireNonNull(
                plan, "plan");
        if (!referenceCutRoleSurfacesMatch(
                selection.activePaths,
                preparedScopePaths(
                        checked.preparedDelivery(),
                        checked.session().subscriptions(),
                        checked.rootInventory()))) {
            return;
        }
        PlannedReferenceCutRoot retained = new PlannedReferenceCutRoot(
                checked,
                selection.artifact,
                selection.activePaths,
                environmentIdentity,
                gasScheduleIdentity,
                referenceCutProviderStorageGenerationAuthority);
        synchronized (plannedReferenceCutRoots) {
            long weight = retained.approximateRetainedWeightBytes();
            if (weight > plannedReferenceCutRootMaximumWeightBytes) return;
            PlannedReferenceCutRoot previous = plannedReferenceCutRoots.put(
                    checked.planIdentity(), retained);
            if (previous != null) {
                plannedReferenceCutRootWeightBytes -=
                        previous.approximateRetainedWeightBytes();
            }
            plannedReferenceCutRootWeightBytes = Math.addExact(
                    plannedReferenceCutRootWeightBytes, weight);
            while (!plannedReferenceCutRoots.isEmpty()
                    && (plannedReferenceCutRoots.size()
                            > plannedReferenceCutRootMaximumSize
                    || plannedReferenceCutRootWeightBytes
                            > plannedReferenceCutRootMaximumWeightBytes)) {
                Map.Entry<String, PlannedReferenceCutRoot> eldest =
                        plannedReferenceCutRoots.entrySet()
                                .iterator().next();
                plannedReferenceCutRootWeightBytes -= eldest.getValue()
                        .approximateRetainedWeightBytes();
                plannedReferenceCutRoots.remove(eldest.getKey());
            }
        }
    }

    private PlannedReferenceCutRoot takePlannedReferenceCutRoot(
            String planIdentity) {
        String checked = requireText(planIdentity, "planIdentity");
        synchronized (plannedReferenceCutRoots) {
            PlannedReferenceCutRoot removed =
                    plannedReferenceCutRoots.remove(checked);
            if (removed != null) {
                plannedReferenceCutRootWeightBytes -=
                        removed.approximateRetainedWeightBytes();
            }
            return removed;
        }
    }

    private void removePlannedReferenceCutRoots(String sessionId) {
        String checked = requireText(sessionId, "sessionId");
        synchronized (plannedReferenceCutRoots) {
            java.util.Iterator<Map.Entry<String, PlannedReferenceCutRoot>>
                    iterator = plannedReferenceCutRoots.entrySet().iterator();
            while (iterator.hasNext()) {
                PlannedReferenceCutRoot candidate =
                        iterator.next().getValue();
                if (candidate.sessionId.equals(checked)) {
                    plannedReferenceCutRootWeightBytes -= candidate
                            .approximateRetainedWeightBytes();
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Builds the exact sparse planning surface for selected occurrences.
     * Invalid or duplicate keys fail with the same authoritative rejection as
     * admitted projection selection. Selected scope chains retain their
     * non-contract state because ancestor handlers may execute alongside the
     * routed leaf occurrence.
     */
    static List<String> planningScopePaths(
            CoordinationSubscriptionSnapshot snapshot,
            Collection<String> occurrenceKeys,
            CoordinationFragmentInventory inventory) {
        CoordinationSubscriptionSnapshot checkedSnapshot =
                Objects.requireNonNull(snapshot, "snapshot");
        CoordinationFragmentInventory checkedInventory =
                Objects.requireNonNull(inventory, "inventory");
        LinkedHashSet<String> paths = new LinkedHashSet<String>();
        paths.add(JsonPointer.ROOT);
        paths.add(PLATFORM_CONTRACTS_PATH);
        LinkedHashSet<String> selectedDependencyBlueIds =
                new LinkedHashSet<String>();
        LinkedHashSet<String> selectedScopeChainPaths =
                new LinkedHashSet<String>();
        LinkedHashSet<String> selectedScopePaths =
                new LinkedHashSet<String>();
        LinkedHashSet<String> activeRecognitionBlueIds =
                new LinkedHashSet<String>();
        LinkedHashSet<String> activeContractsMapPaths =
                new LinkedHashSet<String>();
        LinkedHashSet<String> activeScopePaths =
                new LinkedHashSet<String>();
        LinkedHashSet<String> executableBodyPaths =
                new LinkedHashSet<String>();
        for (FragmentMetadataRecord metadata : checkedInventory.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter.FragmentKind
                            .EXECUTABLE_BODY
                    && metadata.pointer() != null) {
                executableBodyPaths.add(metadata.pointer());
            }
        }
        /* Frozen Contracts verifies the complete active interval surface
         * before it evaluates the selected physical candidates. Keep every
         * active contract/type recognition header concrete in the sparse
         * Root, while candidate bodies and ordinary dependencies remain
         * limited to the requested occurrences below. */
        for (CoordinationSubscriptionOccurrence occurrence
                : checkedSnapshot.occurrences()) {
            activeScopePaths.add(JsonPointer.canonicalize(
                    occurrence.scopePath()));
            addScopeChainRecognitionSurfaces(
                    paths,
                    activeContractsMapPaths,
                    occurrence.scopePath());
            activeRecognitionBlueIds.add(
                    occurrence.effectiveTypeBlueId());
            activeRecognitionBlueIds.add(
                    occurrence.headerIdentityBlueId());
            activeRecognitionBlueIds.addAll(
                    occurrence.sourceContributionNodeBlueIds());
        }
        LinkedHashSet<String> uniqueKeys = new LinkedHashSet<String>();
        for (String suppliedKey : Objects.requireNonNull(
                occurrenceKeys, "occurrenceKeys")) {
            String occurrenceKey = requireText(
                    suppliedKey, "occurrenceKey");
            if (!uniqueKeys.add(occurrenceKey)) {
                throw new IllegalArgumentException(
                        "duplicate candidate: " + occurrenceKey);
            }
            CoordinationSubscriptionOccurrence occurrence =
                    checkedSnapshot.candidateOccurrence(occurrenceKey);
            if (occurrence == null) {
                throw new IllegalArgumentException(
                        "stale or unknown occurrence: " + occurrenceKey);
            }
            addPathAndAncestors(paths, occurrence.scopePath());
            addProcessScopeSurface(paths, occurrence.scopePath());
            addPathAndAncestors(
                    selectedScopeChainPaths, occurrence.scopePath());
            selectedScopePaths.add(JsonPointer.canonicalize(
                    occurrence.scopePath()));
            selectedDependencyBlueIds.addAll(
                    occurrence.sourceContributionNodeBlueIds());
            selectedDependencyBlueIds.addAll(
                    occurrence.dependencyNodeBlueIds());
        }
        // Historical inventories remain body-free and index-free. Scan the
        // immutable edge vector once for this selected candidate set instead
        // of retaining an unbounded child-identity map on every revision.
        for (FragmentEdgeRecord edge : checkedInventory.edges()) {
            if (edge.rootKind()
                    != CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT) {
                continue;
            }
            boolean recognitionHeader = activeRecognitionBlueIds.contains(
                    edge.childBlueId())
                    || (edge.edgeKind()
                    == CoordinationDocumentSplitter.EdgeKind
                            .DOCUMENT_DIRECT_CHILD
                    && isActiveContractHeaderPath(
                            edge.absolutePointer(),
                            activeContractsMapPaths))
                    || (edge.ownerScopePath() != null
                    && activeScopePaths.contains(JsonPointer.canonicalize(
                            edge.ownerScopePath()))
                    && isContractsDescendantPath(edge.absolutePointer()));
            recognitionHeader = recognitionHeader
                    && !isAtOrBelowAny(
                            edge.absolutePointer(), executableBodyPaths);
            boolean selectedDependency = selectedDependencyBlueIds.contains(
                    edge.childBlueId())
                    && !isContractsDescendantPath(edge.absolutePointer());
            String ownerScopePath = edge.ownerScopePath() == null
                    ? null
                    : JsonPointer.canonicalize(edge.ownerScopePath());
            boolean selectedScopeValue = ownerScopePath != null
                    && selectedScopeChainPaths.contains(ownerScopePath)
                    && (selectedScopePaths.contains(ownerScopePath)
                            ? isDirectChildOfAnyScope(
                                    edge.absolutePointer(),
                                    selectedScopePaths)
                            : !isAtOrBelowNestedScope(
                                    edge.absolutePointer(),
                                    ownerScopePath,
                                    activeScopePaths))
                    && !isContractsDescendantPath(edge.absolutePointer());
            if (recognitionHeader
                    || selectedDependency
                    || selectedScopeValue) {
                paths.add(edge.absolutePointer());
            }
        }
        return ActivePathSet.of(paths).paths();
    }

    private static void addPathAndAncestors(
            Set<String> paths,
            String suppliedPath) {
        List<String> segments = JsonPointer.split(
                JsonPointer.canonicalize(Objects.requireNonNull(
                        suppliedPath, "scopePath")));
        paths.add(JsonPointer.ROOT);
        for (int length = 1; length <= segments.size(); length++) {
            paths.add(JsonPointer.toPointer(
                    segments.subList(0, length)));
        }
    }

    private static List<String> preparedScopePaths(
            CoordinationPreparedDelivery prepared,
            CoordinationSubscriptionSnapshot snapshot,
            CoordinationFragmentInventory inventory) {
        CoordinationPreparedDelivery checkedPrepared = Objects.requireNonNull(
                prepared, "prepared");
        return planningScopePaths(
                Objects.requireNonNull(snapshot, "snapshot"),
                checkedPrepared.preselectedOccurrenceOrder(),
                Objects.requireNonNull(inventory, "inventory"));
    }

    /** Exact canonical predicate governing planning-to-PROCESS handoff. */
    static boolean referenceCutRoleSurfacesMatch(
            Collection<String> planningPaths,
            Collection<String> processPaths) {
        return ActivePathSet.of(Objects.requireNonNull(
                        planningPaths, "planningPaths")).paths().equals(
                ActivePathSet.of(Objects.requireNonNull(
                        processPaths, "processPaths")).paths());
    }

    /**
     * Keeps only the selected scope and its contracts-map header concrete.
     * Active-path planning expands the ancestor chain automatically. Handler,
     * contribution, and dependency bodies deliberately stay as references so
     * frozen PROCESS resolves them through the exact request-local provider.
     */
    static void addProcessScopeSurface(
            Set<String> paths,
            String suppliedScopePath) {
        Set<String> checked = Objects.requireNonNull(paths, "paths");
        String scopePath = JsonPointer.canonicalize(
                Objects.requireNonNull(suppliedScopePath, "scopePath"));
        checked.add(scopePath);
        List<String> contracts = new ArrayList<String>(
                JsonPointer.split(scopePath));
        contracts.add("contracts");
        checked.add(JsonPointer.toPointer(contracts));
    }

    private static String contractsPath(String suppliedScopePath) {
        List<String> contracts = new ArrayList<String>(
                JsonPointer.split(JsonPointer.canonicalize(
                        Objects.requireNonNull(
                                suppliedScopePath, "scopePath"))));
        contracts.add("contracts");
        return JsonPointer.toPointer(contracts);
    }

    private static void addScopeChainRecognitionSurfaces(
            Set<String> paths,
            Set<String> contractsMapPaths,
            String suppliedScopePath) {
        List<String> segments = JsonPointer.split(
                JsonPointer.canonicalize(Objects.requireNonNull(
                        suppliedScopePath, "scopePath")));
        for (int length = 0; length <= segments.size(); length++) {
            String scopePath = JsonPointer.toPointer(
                    segments.subList(0, length));
            paths.add(scopePath);
            String contracts = contractsPath(scopePath);
            paths.add(contracts);
            contractsMapPaths.add(contracts);
        }
    }

    private static boolean isActiveContractHeaderPath(
            String suppliedPath,
            Set<String> contractsMapPaths) {
        List<String> segments = JsonPointer.split(
                JsonPointer.canonicalize(Objects.requireNonNull(
                        suppliedPath, "path")));
        for (int index = 0; index < segments.size(); index++) {
            if ("contracts".equals(segments.get(index))
                    && contractsMapPaths.contains(JsonPointer.toPointer(
                            segments.subList(0, index + 1)))) {
                return index + 1 < segments.size();
            }
        }
        return false;
    }

    private static boolean isAtOrBelowAny(
            String suppliedPath,
            Set<String> ancestorPaths) {
        List<String> segments = JsonPointer.split(
                JsonPointer.canonicalize(Objects.requireNonNull(
                        suppliedPath, "path")));
        if (ancestorPaths.contains(JsonPointer.ROOT)) return true;
        for (int length = 1; length <= segments.size(); length++) {
            if (ancestorPaths.contains(JsonPointer.toPointer(
                    segments.subList(0, length)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirectChildOfAnyScope(
            String suppliedPath,
            Set<String> scopePaths) {
        List<String> segments = JsonPointer.split(
                JsonPointer.canonicalize(Objects.requireNonNull(
                        suppliedPath, "path")));
        if (segments.isEmpty()) return false;
        return scopePaths.contains(JsonPointer.toPointer(
                segments.subList(0, segments.size() - 1)));
    }

    private static boolean isAtOrBelowNestedScope(
            String suppliedPath,
            String suppliedOwnerScope,
            Set<String> activeScopePaths) {
        List<String> path = JsonPointer.split(JsonPointer.canonicalize(
                Objects.requireNonNull(suppliedPath, "path")));
        int ownerDepth = JsonPointer.split(JsonPointer.canonicalize(
                Objects.requireNonNull(
                        suppliedOwnerScope, "ownerScope"))).size();
        for (int length = ownerDepth + 1;
                length <= path.size();
                length++) {
            if (activeScopePaths.contains(JsonPointer.toPointer(
                    path.subList(0, length)))) {
                return true;
            }
        }
        return false;
    }

    static boolean isContractsDescendantPath(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        for (int index = 0; index < segments.size() - 1; index++) {
            if ("contracts".equals(segments.get(index))) return true;
        }
        return false;
    }

    /** Round-4 evidence: real sparse-root compile/cache work. */
    public ReferenceCutMetrics.Snapshot referenceCutMetrics() {
        return referenceCutMetrics.snapshot();
    }

    private NodeProvider exactRootPlanningProvider(
            String rootBlueId,
            Node exactRoot,
            CoordinationFragmentInventory rootInventory,
            CoordinationSubscriptionSnapshot snapshot) {
        return exactPlanningProvider(
                rootBlueId,
                exactRoot,
                rootInventory,
                rootBlueId,
                exactRoot,
                rootInventory,
                snapshot);
    }

    private NodeProvider exactPlanningProvider(
            String rootBlueId,
            Node exactRoot,
            CoordinationFragmentInventory rootInventory,
            String eventBlueId,
            Node exactEvent,
            CoordinationFragmentInventory eventInventory,
            CoordinationSubscriptionSnapshot snapshot) {
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
        return new AdmittedReferenceClosureProvider(
                admitted,
                runtimeProvider,
                externalReferenceTargets(rootInventory, eventInventory));
    }

    /**
     * Resolves only the transitive semantic closure of an authored reference.
     *
     * <p>An inventory can prove the outer reference to a runtime contracts
     * map without containing that map's nested contract-header references.
     * Once the exact outer value has been demanded and identity-verified by
     * the frozen processor, those directly reachable references become part
     * of the same request-local admitted closure. Arbitrary provider misses
     * still fail closed; this is not a runtime fallback.</p>
     */
    private static final class AdmittedReferenceClosureProvider
            implements NodeProvider {
        private final NodeProvider admitted;
        private final NodeProvider semantic;
        private final Set<String> allowedExternalReferences;

        private AdmittedReferenceClosureProvider(
                NodeProvider admitted,
                NodeProvider semantic,
                Collection<String> directExternalReferences) {
            this.admitted = Objects.requireNonNull(admitted, "admitted");
            this.semantic = Objects.requireNonNull(semantic, "semantic");
            this.allowedExternalReferences = new LinkedHashSet<String>(
                    Objects.requireNonNull(
                            directExternalReferences,
                            "directExternalReferences"));
        }

        @Override
        public synchronized List<Node> fetchByBlueId(String requestedBlueId) {
            String checkedBlueId = requireText(
                    requestedBlueId, "requestedBlueId");
            List<Node> selected = admitted.fetchByBlueId(checkedBlueId);
            if (selected == null) selected = Collections.emptyList();
            if (!selected.isEmpty()
                    && !(selected.size() == 1
                    && selected.get(0).isReferenceOnly()
                    && allowedExternalReferences.contains(checkedBlueId))) {
                return selected;
            }
            if (!allowedExternalReferences.contains(checkedBlueId)) {
                throw new IllegalStateException(
                        "Indexed planning requested a value outside the "
                                + "admitted Root/Event reference closure: "
                                + checkedBlueId);
            }
            List<Node> resolved = semantic.fetchByBlueId(checkedBlueId);
            if (resolved == null) resolved = Collections.emptyList();
            if (resolved.size() == 1 && !resolved.get(0).isReferenceOnly()) {
                addDirectReferenceTargets(
                        resolved.get(0), allowedExternalReferences);
            }
            return resolved;
        }
    }

    /** Adds references visible inside one demanded exact semantic value. */
    private static void addDirectReferenceTargets(
            Node exactRoot,
            Set<String> result) {
        ArrayDeque<Node> pending = new ArrayDeque<Node>();
        IdentityHashMap<Node, Boolean> visited =
                new IdentityHashMap<Node, Boolean>();
        pending.add(Objects.requireNonNull(exactRoot, "exactRoot"));
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (visited.put(node, Boolean.TRUE) != null) continue;
            if (node.isReferenceOnly()) {
                result.add(requireText(node.getBlueId(), "referenceBlueId"));
                continue;
            }
            addIfPresent(pending, node.getType());
            addIfPresent(pending, node.getItemType());
            addIfPresent(pending, node.getKeyType());
            addIfPresent(pending, node.getValueType());
            addIfPresent(pending, node.getContracts());
            addIfPresent(pending, node.getBlue());
            if (node.getItems() != null) pending.addAll(node.getItems());
            if (node.getProperties() != null) {
                pending.addAll(node.getProperties().values());
            }
            addSchemaReferenceTargets(node.getSchema(), pending, result);
        }
    }

    private static void addSchemaReferenceTargets(
            Schema schema,
            ArrayDeque<Node> pending,
            Set<String> result) {
        if (schema == null) return;
        if (schema.isReferenceOnly()) {
            result.add(requireText(schema.getBlueId(), "schemaReferenceBlueId"));
            return;
        }
        addIfPresent(pending, schema.getRequired());
        addIfPresent(pending, schema.getMinLength());
        addIfPresent(pending, schema.getMaxLength());
        addIfPresent(pending, schema.getMinimum());
        addIfPresent(pending, schema.getMaximum());
        addIfPresent(pending, schema.getExclusiveMinimum());
        addIfPresent(pending, schema.getExclusiveMaximum());
        addIfPresent(pending, schema.getMultipleOf());
        addIfPresent(pending, schema.getMinItems());
        addIfPresent(pending, schema.getMaxItems());
        addIfPresent(pending, schema.getUniqueItems());
        addIfPresent(pending, schema.getMinFields());
        addIfPresent(pending, schema.getMaxFields());
        if (schema.getEnum() != null) pending.addAll(schema.getEnum());
    }

    private static void addIfPresent(
            ArrayDeque<Node> pending,
            Node value) {
        if (value != null) pending.addLast(value);
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
        PlannedReferenceCutRoot plannedReferenceCutRoot =
                takePlannedReferenceCutRoot(checked.planIdentity());

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
        Supplier<Node> exactPriorProofRoot = preparedRoot != null
                ? () -> preparedRoot.borrowRootVerified(
                        preparedOwner,
                        verifiedNodeAccessAuthority)
                : () -> exactRootForIndexedPlanning(checked.rootInventory());
        List<String> processScopePaths = preparedScopePaths(
                checked.preparedDelivery(),
                current.subscriptions(),
                checked.rootInventory());
        boolean reusedPlannedReferenceCut =
                plannedReferenceCutRoot != null
                && plannedReferenceCutRoot.matches(
                        checked,
                        current,
                        processScopePaths,
                        environmentIdentity,
                        gasScheduleIdentity,
                        referenceCutProviderStorageGenerationAuthority);
        if (plannedReferenceCutRoot != null) {
            if (reusedPlannedReferenceCut) {
                referenceCutMetrics.plannedArtifactReused();
            } else {
                referenceCutMetrics.plannedArtifactFallback();
            }
        } else {
            referenceCutMetrics.plannedArtifactNotApplicable();
        }
        ReferenceCutRootSelection processRootSelection =
                reusedPlannedReferenceCut
                ? ReferenceCutRootSelection.sparse(
                        plannedReferenceCutRoot.artifact
                                .copyForFrozenBoundary(),
                        plannedReferenceCutRoot.artifact,
                        processScopePaths)
                : referenceCutRootSelection(
                        current,
                        checked.rootInventory(),
                        exactPriorProofRoot,
                        processScopePaths);
        if (reusedPlannedReferenceCut) {
            referenceCutMetrics.sparseUsed();
        }
        referenceCutMetrics.processSelection(
                processScopePaths.size(),
                processRootSelection.artifact);
        Node exactRoot = processRootSelection.root;
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
        VerifiedFragmentTransitionFrontier fragmentTransitionFrontier = null;
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
                fragmentTransitionFrontier = projectionFrontier
                        .snapshotForFragmentTransition(
                                resultingRoot,
                                verifiedOutput.resultingRootBlueId());
            } catch (DeltaProjectionApplier
                    .ColdProjectionRequiredException cold) {
                projectionFrontierFailure = cold;
            } finally {
                notifyHybridFrontierProofTiming(
                        checked,
                        elapsedNanos(hybridFrontierStartedNanos));
            }
        }
        String resultingRootBlueId = rootCommit
                ? verifiedOutput.resultingRootBlueId()
                : current.currentRootBlueId();
        long resultingEpoch = rootCommit
                ? current.currentEpoch() + 1L
                : current.currentEpoch();
        long retainedReferenceMaterializationNanos = 0L;
        Node exactResultingRoot = rootCommit ? null : resultingRoot;
        if (rootCommit && preparedRoot == null) {
            long materializationStartedNanos = System.nanoTime();
            exactResultingRoot = materializeVerifiedResultingRoot(
                    resultingRoot,
                    null,
                    null,
                    exactRoot,
                    fragmentTransitionFrontier);
            retainedReferenceMaterializationNanos += elapsedNanos(
                    materializationStartedNanos);
        }

        long transitionStartedNanos = System.nanoTime();
        long subscriptionProjectionStartedNanos = System.nanoTime();
        CoordinationSubscriptionUpdate subscriptionUpdate;
        CoordinationCommitProjectionEvidence incrementalProjectionEvidence =
                null;
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
                boolean requiresProjectionCatalog =
                        !membershipDelta.isEmpty()
                                || !projectionFrontier
                                        .processEmbeddedBoundaryBlueIdByPath()
                                        .isEmpty();
                if (requiresProjectionCatalog
                        && exactResultingRoot == null) {
                    long materializationStartedNanos = System.nanoTime();
                    exactResultingRoot = materializeVerifiedResultingRoot(
                            resultingRoot,
                            preparedRoot,
                            preparedOwner,
                            exactRoot,
                            fragmentTransitionFrontier);
                    retainedReferenceMaterializationNanos += elapsedNanos(
                            materializationStartedNanos);
                }
                EffectiveFragmentationCatalog projectionCatalog =
                        requiresProjectionCatalog
                        ? contracts.effectiveFragmentationCatalog(
                                exactResultingRoot)
                        : null;
                incrementalProjectionEvidence =
                        commitProjectionEvidenceBuilder.build(
                                current.subscriptions(),
                                projectionFrontier,
                                exactPriorProofRoot.get(),
                                requiresProjectionCatalog
                                        ? exactResultingRoot
                                        : resultingRoot,
                                resultingRootBlueId,
                                companion.resultingRootRevision(),
                                companion.eventOrderKey(),
                                membershipDelta,
                                projectionCatalog);
                subscriptionUpdate = deltaSubscriptionProjector.apply(
                        current.subscriptions(),
                        incrementalProjectionEvidence);
            } catch (DeltaProjectionApplier
                    .ColdProjectionRequiredException cold) {
                projectionFastPathMetrics.fullProjectorFallback();
                notifySubscriptionProjectionColdFallback(
                        checked, cold.getMessage());
                if (exactResultingRoot == null) {
                    long materializationStartedNanos = System.nanoTime();
                    exactResultingRoot = materializeVerifiedResultingRoot(
                            resultingRoot,
                            preparedRoot,
                            preparedOwner,
                            exactRoot,
                            fragmentTransitionFrontier);
                    retainedReferenceMaterializationNanos += elapsedNanos(
                            materializationStartedNanos);
                }
                subscriptionUpdate = subscriptionProjector
                        .applyPlatformCommit(
                                current.subscriptions(),
                                platform,
                                exactResultingRoot);
            }
        }
        if (rootCommit && exactResultingRoot == null) {
            long materializationStartedNanos = System.nanoTime();
            exactResultingRoot = materializeVerifiedResultingRoot(
                    resultingRoot,
                    preparedRoot,
                    preparedOwner,
                    exactRoot,
                    fragmentTransitionFrontier);
            retainedReferenceMaterializationNanos += elapsedNanos(
                    materializationStartedNanos);
        }
        notifyRetainedReferenceMaterializationTiming(
                checked,
                retainedReferenceMaterializationNanos);
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
                        fragmentTransitionFrontier,
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
        AdmittedProjection pendingPlanningProjection = rootCommit
                ? prepareIncrementalPlanningProjection(
                        current,
                        checked.rootInventory(),
                        resultingSession,
                        fragmentTransition.resultingInventory(),
                        subscriptionUpdate,
                        incrementalProjectionEvidence,
                        fragmentTransitionFrontier)
                : null;
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
            FastFragmentDelta verifiedFragmentDelta =
                    fragmentTransition.verifiedDelta(
                            verifiedNodeAccessAuthority);
            if (verifiedFragmentDelta != null) {
                for (Map.Entry<String, ExactNodeHandle> changedView
                        : verifiedFragmentDelta.changedProcessingViews(
                                verifiedNodeAccessAuthority).entrySet()) {
                    resultingProcessingViews.put(
                            changedView.getKey(),
                            changedView.getValue().rebind(
                                    verifiedNodeAccessAuthority,
                                    requestDigests));
                }
            } else {
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
        }
        PreparedRootExecutionContext preparedResult = rootCommit
                ? buildPreparedResultContext(
                        resultingSession,
                        fragmentTransition.resultingInventory(),
                        verifiedOutput,
                        requestDigests,
                        resultingProcessingViews,
                        preparedRoot,
                        preparedOwner,
                        fragmentTransitionFrontier)
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
                    transitionIdentity,
                    preparedResult,
                    pendingPlanningProjection);
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
        if (!checkedOutcome.committed()
                || !checkedOutcome.transitionIdentity().equals(
                        transitionIdentity)) {
            return false;
        }
        try {
        PendingPreparedGeneration pending =
                pendingPreparedRootContext(transitionIdentity);
        if (pending == null) return false;
        PreparedRootExecutionContext candidate = pending.context;
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
        boolean installed;
        try {
            Optional<ManagedDocumentSnapshot> stillCurrent =
                    sessionStore.findSession(expected.sessionId());
            if (!stillCurrent.isPresent()
                    || !sameSessionGeneration(
                            stillCurrent.get(), expected)) {
                return false;
            }
            if (takePendingPreparedRootContext(
                    transitionIdentity) != pending) {
                return false;
            }
            installed = preparedRootContexts.installIfCurrent(
                    candidate);
        } catch (RuntimeException derivedCacheFailure) {
            installed = false;
        }
        if (pending.planningProjection != null) {
            try {
                ProjectionGenerationKey expectedGeneration =
                        planningGeneration(
                                expected,
                                checkedTransition.fragmentTransition()
                                        .resultingInventory());
                if (expectedGeneration.equals(
                        pending.planningProjection.generation())) {
                    planningProjectionCache.publish(
                            pending.planningProjection);
                }
            } catch (RuntimeException derivedCacheFailure) {
                // The authoritative CAS already won. Derived evidence is
                // optional and must never turn a committed result into failure.
            }
        }
        retirePublishedPlanningGeneration(
                checkedTransition,
                expected);
        return installed;
        } catch (RuntimeException postPublicationFailure) {
            // Authoritative publication already succeeded. Storage probes and
            // all acceleration maintenance are observational and fail closed.
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
                preparedDeliveryMemoizer.generationCommitted(
                        transition.plan().session().sessionId().value(),
                        previous);
                planningProjectionCache.retainOnly(published);
            }
        } catch (RuntimeException derivedCacheFailure) {
            // Publication is authoritative; derived-cache maintenance is not.
        }
    }

    private void retainPendingPreparedRootContext(
            String transitionIdentity,
            PreparedRootExecutionContext context,
            AdmittedProjection planningProjection) {
        String identity = requireText(
                transitionIdentity, "transitionIdentity");
        PendingPreparedGeneration checked = new PendingPreparedGeneration(
                context, planningProjection);
        long weight = checked.approximateRetainedWeightBytes();
        if (weight <= 0L) {
            throw new IllegalArgumentException(
                    "prepared context weight must be positive");
        }
        synchronized (pendingPreparedRootContexts) {
            if (weight > pendingPreparedRootContextMaximumWeightBytes) {
                return;
            }
            PendingPreparedGeneration previous =
                    pendingPreparedRootContexts.put(identity, checked);
            if (previous != null) {
                pendingPreparedRootContextWeightBytes -= previous
                        .approximateRetainedWeightBytes();
            }
            pendingPreparedRootContextWeightBytes = Math.addExact(
                    pendingPreparedRootContextWeightBytes, weight);
            while (!pendingPreparedRootContexts.isEmpty()
                    && (pendingPreparedRootContexts.size()
                            > pendingPreparedRootContextMaximumSize
                    || pendingPreparedRootContextWeightBytes
                            > pendingPreparedRootContextMaximumWeightBytes)) {
                Map.Entry<String, PendingPreparedGeneration> eldest =
                        pendingPreparedRootContexts.entrySet()
                                .iterator().next();
                pendingPreparedRootContextWeightBytes -= eldest.getValue()
                        .approximateRetainedWeightBytes();
                pendingPreparedRootContexts.remove(eldest.getKey());
            }
        }
    }

    private PendingPreparedGeneration takePendingPreparedRootContext(
            String transitionIdentity) {
        synchronized (pendingPreparedRootContexts) {
            String identity = Objects.requireNonNull(
                    transitionIdentity, "transitionIdentity");
            PendingPreparedGeneration removed =
                    pendingPreparedRootContexts.remove(identity);
            if (removed != null) {
                pendingPreparedRootContextWeightBytes -= removed
                        .approximateRetainedWeightBytes();
            }
            return removed;
        }
    }

    private PendingPreparedGeneration pendingPreparedRootContext(
            String transitionIdentity) {
        synchronized (pendingPreparedRootContexts) {
            return pendingPreparedRootContexts.get(
                    Objects.requireNonNull(
                            transitionIdentity, "transitionIdentity"));
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
     * Captures only already-retained prepared contexts for a local,
     * quiescent checkpoint. Cache misses are intentionally absent and rebuild
     * lazily after restore; checkpointing never expands warm state to every
     * active session. The opaque sidecar strongly owns only this bounded,
     * immutable acceleration capsule. Runtime service domains remain weak, so
     * a long-lived checkpoint cannot pin its source engine/service graph.
     */
    public PreparedCheckpointState checkpointPreparedState(
            Collection<ManagedDocumentSnapshot> suppliedSessions) {
        requireOpen();
        Map<String, ManagedDocumentSnapshot> active =
                new LinkedHashMap<String, ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot supplied : Objects.requireNonNull(
                suppliedSessions, "suppliedSessions")) {
            ManagedDocumentSnapshot checked = Objects.requireNonNull(
                    supplied, "session");
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
            if (current.status() == ManagedDocumentStatus.ACTIVE) {
                active.put(current.sessionId().value(), current);
            }
        }
        Map<String, PreparedRootExecutionContext> contexts =
                new LinkedHashMap<String, PreparedRootExecutionContext>();
        for (PreparedRootExecutionContext context
                : preparedRootContexts.retainedContextsSnapshot()) {
            ManagedDocumentSnapshot current = active.get(
                    context.sessionId());
            if (current == null
                    || !context.matches(
                            current.sessionId().value(),
                            current.currentEpoch(),
                            current.currentRootBlueId(),
                            current.fragmentInventoryIdentity())) {
                continue;
            }
            context.borrowRootVerified(
                    preparedRootOwnership,
                    verifiedNodeAccessAuthority);
            contexts.put(current.sessionId().value(), context);
        }
        return new PreparedCheckpointState(
                preparedCheckpointBindingIdentity,
                contracts,
                documentProcessor,
                preparedRootOwnership,
                referenceCutRootCacheBacking,
                planningProjectionCacheBacking,
                contexts);
    }

    /**
     * Installs the exact immutable context retained by a compatible local
     * checkpoint. A false result leaves the bounded cache cold; the ordinary
     * first request rebuilds from authoritative fragments on demand.
     */
    public boolean restorePreparedRootContextFromCheckpoint(
            ManagedDocumentSnapshot supplied,
            PreparedCheckpointState state) {
        RootContextRestore restore = requireRootContextRestore(supplied);
        PreparedCheckpointState checked = Objects.requireNonNull(
                state, "state");
        if (checked != acceptedPreparedCheckpointState
                || acceptedPreparedCheckpointLease == null) {
            checkpointPreparedContextFallbacks.incrementAndGet();
            return false;
        }
        PreparedRootExecutionContext context =
                acceptedPreparedCheckpointLease.contextsBySession.get(
                        restore.session.sessionId().value());
        if (context == null
                || !context.matches(
                        restore.session.sessionId().value(),
                        restore.session.currentEpoch(),
                        restore.session.currentRootBlueId(),
                        restore.session.fragmentInventoryIdentity())) {
            checkpointPreparedContextFallbacks.incrementAndGet();
            return false;
        }
        try {
            context.borrowRootVerified(
                    preparedRootOwnership,
                    verifiedNodeAccessAuthority);
        } catch (IllegalArgumentException | SecurityException mismatch) {
            checkpointPreparedContextFallbacks.incrementAndGet();
            return false;
        }
        if (!preparedRootContexts.installIfCurrent(context)) {
            checkpointPreparedContextFallbacks.incrementAndGet();
            return false;
        }
        checkpointPreparedContextReuses.incrementAndGet();
        return true;
    }

    /** Exact number of prepared checkpoint contexts installed by reference. */
    public long checkpointPreparedContextReuseCount() {
        return checkpointPreparedContextReuses.get();
    }

    /** Exact number of checkpoint contexts rejected for exact rebuilding. */
    public long checkpointPreparedContextFallbackCount() {
        return checkpointPreparedContextFallbacks.get();
    }

    /** Exact number of checkpoint contexts rebuilt with full verification. */
    public long checkpointPreparedContextRebuildCount() {
        return checkpointPreparedContextRebuilds.get();
    }

    /**
     * Identity-only audit probe; no context, cache, owner, or Node escapes.
     */
    public boolean reusesPreparedCheckpointContext(
            ManagedDocumentSnapshot supplied,
            PreparedCheckpointState state) {
        ManagedDocumentSnapshot checked = Objects.requireNonNull(
                supplied, "supplied");
        PreparedCheckpointState checkpointState = Objects.requireNonNull(
                state, "state");
        if (checkpointState != acceptedPreparedCheckpointState) return false;
        PreparedRootExecutionContext retained =
                acceptedPreparedCheckpointLease.contextsBySession.get(
                        checked.sessionId().value());
        return retained != null
                && retained == preparedRootContexts.get(
                        checked.sessionId().value(),
                        checked.currentEpoch(),
                        checked.currentRootBlueId(),
                        checked.fragmentInventoryIdentity());
    }

    /** Identity-only probe for the opaque checkpoint-shared sparse kernel. */
    public boolean reusesReferenceCutCheckpointKernel(
            PreparedCheckpointState state) {
        PreparedCheckpointState checked = Objects.requireNonNull(
                state, "state");
        return checked == acceptedPreparedCheckpointState
                && acceptedPreparedCheckpointLease != null
                && referenceCutRootCacheBacking
                        == acceptedPreparedCheckpointLease
                                .referenceCutRootCacheBacking;
    }

    /** Identity-only probe for the opaque checkpoint-shared planning kernel. */
    public boolean reusesPlanningProjectionCheckpointKernel(
            PreparedCheckpointState state) {
        PreparedCheckpointState checked = Objects.requireNonNull(
                state, "state");
        return checked == acceptedPreparedCheckpointState
                && acceptedPreparedCheckpointLease != null
                && planningProjectionCacheBacking
                        == acceptedPreparedCheckpointLease
                                .planningProjectionCacheBacking;
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
        checkpointPreparedContextRebuilds.incrementAndGet();
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
     * <p>Historical revisions remain body-free. A cache miss stays cold and
     * is rebuilt lazily by the first request in a fork; checkpoint creation
     * never materializes every tenant Root.</p>
     */
    public Map<String, Node> checkpointCurrentRootViews(
            Collection<ManagedDocumentSnapshot> sessions) {
        requireOpen();
        Set<String> currentInventoryIdentities =
                new LinkedHashSet<String>();
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
            currentInventoryIdentities.add(inventory.inventoryIdentity());
        }
        Map<String, Node> retained = rootViewCache.snapshotRetainedRoots();
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (String inventoryIdentity : currentInventoryIdentities) {
            Node root = retained.get(inventoryIdentity);
            if (root != null) result.put(inventoryIdentity, root);
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
            Map<String, ExactNodeHandle> processingViews,
            PreparedRootExecutionContext priorPreparedRoot,
            Object priorPreparedOwner,
            VerifiedFragmentTransitionFrontier transitionFrontier) {
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
        Node exactPreparedRoot = rootHandle.borrowVerified(
                preparedRootOwnership,
                verifiedNodeAccessAuthority);
        RetainedReferenceIndex retained;
        if (priorPreparedRoot != null && transitionFrontier != null) {
            retained = priorPreparedRoot.retainedReferences()
                    .graftVerifiedExpanded(
                            exactPreparedRoot,
                            transitionFrontier.expandedBlueIdByPath(),
                            Objects.requireNonNull(
                                    priorPreparedOwner,
                                    "priorPreparedOwner"),
                            preparedRootOwnership,
                            owner,
                            verifiedNodeAccessAuthority);
        } else {
            transitionRetainedIndexFullScans.incrementAndGet();
            retained = RetainedReferenceIndex.scanOnce(
                    rootHandle,
                    preparedRootOwnership,
                    owner);
        }
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

    /** Grafts retained epoch values without traversing or copying them. */
    private Node materializeVerifiedResultingRoot(
            Node processResult,
            PreparedRootExecutionContext preparedRoot,
            Object preparedOwner,
            Node priorExactRoot,
            VerifiedFragmentTransitionFrontier transitionFrontier) {
        if (preparedRoot == null) {
            transitionFullRootMaterializations.incrementAndGet();
            return materializeRetainedResultReferences(
                    processResult, priorExactRoot);
        }
        if (transitionFrontier != null) {
            transitionExpandedNodesVisited.addAndGet(
                    transitionFrontier.sparseExpandedNodeCount());
            transitionFrontierBoundaryGrafts.addAndGet(
                    transitionFrontier.retainedBlueIdByPath().size());
        }
        return new IndexedRetainedReferenceResolver(
                preparedRoot.retainedReferences(),
                Objects.requireNonNull(
                        preparedOwner, "preparedOwner"))
                .resolveRequestOwned(processResult);
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

    private static String referenceCutConfigurationIdentity(
            ReferenceCutConfiguration configuration) {
        ReferenceCutConfiguration checked = Objects.requireNonNull(
                configuration, "configuration");
        return identity(
                "reference-cut-configuration",
                checked.mode().name(),
                Long.toString(checked.maximumCacheWeightBytes()),
                Double.toString(checked.minimumNodeReduction()),
                Integer.toString(checked.maximumCuts()));
    }

    /**
     * Opaque in-process checkpoint acceleration capsule.
     *
     * <p>Contracts and processor domains are weak identity guards so this
     * sidecar cannot retain an obsolete runtime service graph. The owner,
     * bounded cache backings, and already-retained immutable contexts form one
     * strong acceleration lease. It therefore survives source-engine close
     * and GC deterministically while remaining constrained by the same count
     * and retained-weight limits as the live caches.</p>
     */
    public static final class PreparedCheckpointState {
        private final String bindingIdentity;
        private final WeakReference<BlueContracts> contractsDomain;
        private final WeakReference<DocumentProcessor> processorDomain;
        private final PreparedCheckpointLease acceleration;

        private PreparedCheckpointState(
                String bindingIdentity,
                BlueContracts contractsDomain,
                DocumentProcessor processorDomain,
                Object ownerCapability,
                ReferenceCutRootCache.SharedBacking
                        referenceCutRootCacheBacking,
                ProjectionGenerationCache.SharedBacking
                        planningProjectionCacheBacking,
                Map<String, PreparedRootExecutionContext>
                        contextsBySession) {
            this.bindingIdentity = requireText(
                    bindingIdentity, "bindingIdentity");
            this.contractsDomain = new WeakReference<BlueContracts>(
                    Objects.requireNonNull(
                            contractsDomain, "contractsDomain"));
            this.processorDomain = new WeakReference<DocumentProcessor>(
                    Objects.requireNonNull(
                            processorDomain, "processorDomain"));
            Map<String, PreparedRootExecutionContext> retained =
                    new LinkedHashMap<String,
                            PreparedRootExecutionContext>();
            for (Map.Entry<String, PreparedRootExecutionContext> entry
                    : Objects.requireNonNull(
                            contextsBySession,
                            "contextsBySession").entrySet()) {
                String sessionId = requireText(
                        entry.getKey(), "sessionId");
                PreparedRootExecutionContext context =
                        Objects.requireNonNull(
                                entry.getValue(), "prepared context");
                if (!sessionId.equals(context.sessionId())) {
                    throw new IllegalArgumentException(
                            "Prepared checkpoint session key mismatch");
                }
                retained.put(sessionId, context);
            }
            this.acceleration = new PreparedCheckpointLease(
                    Objects.requireNonNull(
                            ownerCapability, "ownerCapability"),
                    Objects.requireNonNull(
                            referenceCutRootCacheBacking,
                            "referenceCutRootCacheBacking"),
                    Objects.requireNonNull(
                            planningProjectionCacheBacking,
                            "planningProjectionCacheBacking"),
                    retained);
        }

        private PreparedCheckpointLease tryAcquire(
                String expectedBindingIdentity,
                BlueContracts expectedContracts,
                DocumentProcessor expectedProcessor) {
            BlueContracts contracts = contractsDomain.get();
            DocumentProcessor processor = processorDomain.get();
            if (!bindingIdentity.equals(expectedBindingIdentity)
                    || contracts != expectedContracts
                    || processor != expectedProcessor) {
                return null;
            }
            return acceleration;
        }
    }

    /** Strong bounded acceleration lease shared by compatible restored engines. */
    private static final class PreparedCheckpointLease {
        private final Object ownerCapability;
        private final ReferenceCutRootCache.SharedBacking
                referenceCutRootCacheBacking;
        private final ProjectionGenerationCache.SharedBacking
                planningProjectionCacheBacking;
        private final Map<String, PreparedRootExecutionContext>
                contextsBySession;

        private PreparedCheckpointLease(
                Object ownerCapability,
                ReferenceCutRootCache.SharedBacking
                        referenceCutRootCacheBacking,
                ProjectionGenerationCache.SharedBacking
                        planningProjectionCacheBacking,
                Map<String, PreparedRootExecutionContext>
                        contextsBySession) {
            this.ownerCapability = Objects.requireNonNull(
                    ownerCapability, "ownerCapability");
            this.referenceCutRootCacheBacking = Objects.requireNonNull(
                    referenceCutRootCacheBacking,
                    "referenceCutRootCacheBacking");
            this.planningProjectionCacheBacking = Objects.requireNonNull(
                    planningProjectionCacheBacking,
                    "planningProjectionCacheBacking");
            this.contextsBySession = Collections.unmodifiableMap(
                    new LinkedHashMap<String,
                            PreparedRootExecutionContext>(
                            Objects.requireNonNull(
                                    contextsBySession,
                                    "contextsBySession")));
        }
    }

    private static final class ReferenceCutRootSelection {
        private final Node root;
        private final ReferenceCutRootArtifact artifact;
        private final List<String> activePaths;

        private ReferenceCutRootSelection(
                Node root,
                ReferenceCutRootArtifact artifact,
                Collection<String> activePaths) {
            this.root = Objects.requireNonNull(root, "root");
            this.artifact = artifact;
            this.activePaths = Collections.unmodifiableList(
                    new ArrayList<String>(Objects.requireNonNull(
                            activePaths, "activePaths")));
        }

        private static ReferenceCutRootSelection full(Node root) {
            return new ReferenceCutRootSelection(
                    root,
                    null,
                    Collections.<String>emptyList());
        }

        private static ReferenceCutRootSelection sparse(
                Node root,
                ReferenceCutRootArtifact artifact,
                Collection<String> activePaths) {
            return new ReferenceCutRootSelection(
                    root,
                    Objects.requireNonNull(artifact, "artifact"),
                    activePaths);
        }
    }

    /** One bounded pre-publication handoff for successor acceleration state. */
    private static final class PendingPreparedGeneration {
        private final PreparedRootExecutionContext context;
        private final AdmittedProjection planningProjection;

        private PendingPreparedGeneration(
                PreparedRootExecutionContext context,
                AdmittedProjection planningProjection) {
            this.context = Objects.requireNonNull(context, "context");
            this.planningProjection = planningProjection;
        }

        private long approximateRetainedWeightBytes() {
            long contextWeight = context.approximateRetainedWeightBytes();
            long projectionWeight = planningProjection == null
                    ? 0L
                    : planningProjection.estimatedWeight();
            return Math.addExact(contextWeight, projectionWeight);
        }
    }

    /** One bounded, consume-on-execute sparse artifact handoff. */
    private static final class PlannedReferenceCutRoot {
        private final String planIdentity;
        private final String sessionId;
        private final long epoch;
        private final String rootBlueId;
        private final String inventoryIdentity;
        private final String eventBlueId;
        private final String subscriptionDigest;
        private final String environmentIdentity;
        private final String gasScheduleIdentity;
        private final String providerStorageGenerationAuthority;
        private final String algorithmVersion;
        private final Set<String> activePaths;
        private final ReferenceCutRootArtifact artifact;

        private PlannedReferenceCutRoot(
                CoordinationProcessingPlan plan,
                ReferenceCutRootArtifact artifact,
                Collection<String> activePaths,
                String environmentIdentity,
                String gasScheduleIdentity,
                String providerStorageGenerationAuthority) {
            CoordinationProcessingPlan checked = Objects.requireNonNull(
                    plan, "plan");
            this.planIdentity = checked.planIdentity();
            this.sessionId = checked.session().sessionId().value();
            this.epoch = checked.session().currentEpoch();
            this.rootBlueId = checked.rootInventory().rootBlueId();
            this.inventoryIdentity =
                    checked.rootInventory().inventoryIdentity();
            this.eventBlueId = checked.eventInventory().rootBlueId();
            this.subscriptionDigest =
                    checked.session().subscriptions().digest();
            this.environmentIdentity = requireText(
                    environmentIdentity, "environmentIdentity");
            this.gasScheduleIdentity = requireText(
                    gasScheduleIdentity, "gasScheduleIdentity");
            this.providerStorageGenerationAuthority = requireText(
                    providerStorageGenerationAuthority,
                    "providerStorageGenerationAuthority");
            this.algorithmVersion =
                    InventoryReferenceCutRootCompiler.ALGORITHM_VERSION;
            this.activePaths = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(Objects.requireNonNull(
                            activePaths, "activePaths")));
            this.artifact = Objects.requireNonNull(artifact, "artifact");
            if (!rootBlueId.equals(artifact.rootBlueId())
                    || !inventoryIdentity.equals(
                            artifact.inventoryIdentity())) {
                throw new IllegalArgumentException(
                        "Planned sparse artifact changed Root generation");
            }
        }

        private boolean matches(
                CoordinationProcessingPlan plan,
                ManagedDocumentSnapshot current,
                Collection<String> requiredPaths,
                String expectedEnvironmentIdentity,
                String expectedGasScheduleIdentity,
                String expectedProviderStorageGenerationAuthority) {
            CoordinationProcessingPlan checked = Objects.requireNonNull(
                    plan, "plan");
            ManagedDocumentSnapshot session = Objects.requireNonNull(
                    current, "current");
            return planIdentity.equals(checked.planIdentity())
                    && sessionId.equals(session.sessionId().value())
                    && epoch == session.currentEpoch()
                    && rootBlueId.equals(session.currentRootBlueId())
                    && inventoryIdentity.equals(
                            session.fragmentInventoryIdentity())
                    && rootBlueId.equals(
                            checked.rootInventory().rootBlueId())
                    && inventoryIdentity.equals(
                            checked.rootInventory().inventoryIdentity())
                    && eventBlueId.equals(
                            checked.eventInventory().rootBlueId())
                    && subscriptionDigest.equals(
                            session.subscriptions().digest())
                    && environmentIdentity.equals(
                            expectedEnvironmentIdentity)
                    && gasScheduleIdentity.equals(
                            expectedGasScheduleIdentity)
                    && providerStorageGenerationAuthority.equals(
                            expectedProviderStorageGenerationAuthority)
                    && algorithmVersion.equals(
                            InventoryReferenceCutRootCompiler
                                    .ALGORITHM_VERSION)
                    && activePaths.equals(new LinkedHashSet<String>(
                            ActivePathSet.of(Objects.requireNonNull(
                                    requiredPaths,
                                    "requiredPaths")).paths()));
        }

        private long approximateRetainedWeightBytes() {
            long weight = Math.addExact(
                    artifact.approximateRetainedWeightBytes(), 512L);
            weight = addTextWeight(weight, planIdentity);
            weight = addTextWeight(weight, sessionId);
            weight = addTextWeight(weight, rootBlueId);
            weight = addTextWeight(weight, inventoryIdentity);
            weight = addTextWeight(weight, eventBlueId);
            weight = addTextWeight(weight, subscriptionDigest);
            weight = addTextWeight(weight, environmentIdentity);
            weight = addTextWeight(weight, gasScheduleIdentity);
            weight = addTextWeight(
                    weight, providerStorageGenerationAuthority);
            weight = addTextWeight(weight, algorithmVersion);
            for (String activePath : activePaths) {
                weight = addTextWeight(weight, activePath);
            }
            return weight;
        }

        private static long addTextWeight(long current, String value) {
            return Math.addExact(
                    current,
                    Math.addExact(48L,
                            Math.multiplyExact(2L, value.length())));
        }
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
        private ReferenceCutConfiguration referenceCutConfiguration =
                ReferenceCutConfiguration.disabled();
        private PreparedCheckpointState preparedCheckpointState;
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
        /**
         * Configures identity-equivalent sparse Roots at frozen planning and
         * PROCESS boundaries. Disabled by default outside explicitly migrated
         * hosts.
         */
        public Builder referenceCutConfiguration(
                ReferenceCutConfiguration value) {
            referenceCutConfiguration = Objects.requireNonNull(
                    value, "referenceCutConfiguration");
            return this;
        }
        /** Seeds an opaque, exact-bound local checkpoint optimization. */
        public Builder preparedCheckpointState(
                PreparedCheckpointState value) {
            preparedCheckpointState = Objects.requireNonNull(
                    value, "preparedCheckpointState");
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
