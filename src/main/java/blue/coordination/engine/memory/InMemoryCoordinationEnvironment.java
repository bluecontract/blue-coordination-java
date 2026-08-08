package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.CoordinationEventShapeInstance;
import blue.coordination.engine.api.CoordinationEventShapeMetrics;
import blue.coordination.engine.api.CoordinationEventShapePatch;
import blue.coordination.engine.api.CoordinationEventShapeTemplate;
import blue.coordination.engine.api.CoordinationFragmentTransitionWorkSnapshot;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationRootViewCacheSnapshot;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.CoordinationTransitionPublicationGuard;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.fastpath.ReferenceCutConfiguration;
import blue.coordination.engine.fastpath.ReferenceCutMetrics;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;
import blue.coordination.engine.spi.CoordinationTransitionMemoStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Multi-session in-memory reference host over the storage-neutral engine.
 *
 * <p>The supplied Contracts and processor generation must be configured with
 * the same exact provider domain as the fragment store. The convenience host
 * owns no runtime unless explicitly requested by its builder.</p>
 */
public final class InMemoryCoordinationEnvironment implements AutoCloseable {

    private static final int PREPARATION_PARALLELISM = Math.max(
            1,
            Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int PREPARATION_QUEUE_CAPACITY = Math.max(
            16, PREPARATION_PARALLELISM * 4);
    private static final long EXECUTOR_SHUTDOWN_SECONDS = 5L;

    private static final CoordinationTransitionPublicationGuard
            PERMIT_PUBLICATION = new CoordinationTransitionPublicationGuard() {
                @Override
                public void validate(CoordinationTransition transition) {
                    Objects.requireNonNull(transition, "transition");
                }
            };
    private static final Runnable NO_HOST_EVENT_PUBLICATION = new Runnable() {
        @Override
        public void run() {
        }
    };

    private final CoordinationProcessingEngine engine;
    private final InMemoryCoordinationFragmentStore fragmentStore;
    private final InMemoryCoordinationSessionStore sessionStore;
    private final InMemoryCoordinationSubscriptionIndex subscriptionIndex;
    private final InMemoryStoredCoordinationEventStore eventStore;
    private final InMemorySessionIndexPublisher sessionIndexPublisher;
    private final InMemoryCoordinationDispatchLedger dispatchLedger;
    private final InMemoryCoordinationFanout defaultFanout;
    private final ThreadPoolExecutor rootPreparationExecutor;
    // Environment-created schedulers must participate in the checkpoint
    // quiescence barrier while callers retain them, but that barrier must not
    // become their lifetime owner. Weak keys preserve that distinction and
    // iteration below also expunges schedulers which callers released.
    private final Set<BoundedCoordinationRootScheduler<?>> parallelSchedulers =
            Collections.newSetFromMap(
                    new WeakHashMap<
                            BoundedCoordinationRootScheduler<?>, Boolean>());
    private final AtomicLong sessionSequence = new AtomicLong();
    private final BlueContracts contracts;
    private final DocumentProcessor documentProcessor;
    private final ReentrantReadWriteLock lifecycle =
            new ReentrantReadWriteLock(true);
    private boolean closed;

    private InMemoryCoordinationEnvironment(Builder builder) {
        this.contracts = Objects.requireNonNull(
                builder.contracts, "contracts");
        this.documentProcessor = Objects.requireNonNull(
                builder.documentProcessor, "documentProcessor");
        InMemoryCoordinationCheckpoint checkpoint = builder.checkpoint;
        if (checkpoint == null) {
            this.fragmentStore = builder.fragmentStore != null
                    ? builder.fragmentStore
                    : new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            this.sessionStore = builder.sessionStore != null
                    ? builder.sessionStore
                    : new InMemoryCoordinationSessionStore();
            this.subscriptionIndex = builder.subscriptionIndex != null
                    ? builder.subscriptionIndex
                    : new InMemoryCoordinationSubscriptionIndex();
            this.eventStore = new InMemoryStoredCoordinationEventStore();
            this.dispatchLedger = new InMemoryCoordinationDispatchLedger();
        } else {
            if (builder.fragmentStore != null
                    || builder.sessionStore != null
                    || builder.subscriptionIndex != null
                    || builder.bundleLoader != null
                    || builder.memoStore != null) {
                throw new IllegalStateException(
                        "checkpoint cannot be combined with explicit stores");
            }
            this.fragmentStore =
                    InMemoryCoordinationFragmentStore.fromCheckpoint(
                            checkpoint);
            this.sessionStore =
                    InMemoryCoordinationSessionStore.fromCheckpoint(
                            checkpoint);
            this.subscriptionIndex =
                    new InMemoryCoordinationSubscriptionIndex();
            // The index is derived state. Rebuild it only from authoritative
            // restored sessions; never copy potentially stale physical rows.
            for (ManagedDocumentSnapshot session : sessionStore.sessions()) {
                subscriptionIndex.replaceSession(session);
            }
            this.eventStore = checkpoint.storedEvents.copy();
            this.dispatchLedger =
                    checkpoint.dispatchLedger.copyAtQuiescence();
            this.sessionSequence.set(checkpoint.sessionSequence);
        }
        CoordinationProcessingEngine.Builder engineBuilder =
                CoordinationProcessingEngine.builder()
                        .contracts(contracts)
                        .documentProcessor(documentProcessor)
                        .fragmentStore(fragmentStore)
                        .sessionStore(sessionStore)
                        .bundleLoader(builder.bundleLoader != null
                                ? builder.bundleLoader
                                : new InMemoryCoordinationProcessingBundleLoader(
                                        fragmentStore,
                                        documentProcessor
                                                .administration()
                                                .runtimeAccess()
                                                .languageRuntime()
                                                .getNodeProvider()))
                        .transitionMemoStore(builder.memoStore)
                        .observer(builder.observer != null
                                ? builder.observer
                                : CoordinationProcessingEngineObserver.none())
                        .referenceCutConfiguration(
                                builder.referenceCutConfiguration)
                        .transferRuntimeOwnership(builder.ownsRuntimes);
        if (checkpoint != null) {
            engineBuilder
                    .retainedRootViews(checkpoint.currentRootViews);
            if (checkpoint.preparedRootState != null) {
                engineBuilder.preparedCheckpointState(
                        checkpoint.preparedRootState);
            }
        }
        if (builder.rootViewCacheMaximumSize != null) {
            engineBuilder.rootViewCacheMaximumSize(
                    builder.rootViewCacheMaximumSize.intValue());
        }
        if (builder.environmentIdentity != null) {
            engineBuilder.environmentIdentity(builder.environmentIdentity);
        }
        this.engine = engineBuilder.build();
        this.sessionIndexPublisher = new InMemorySessionIndexPublisher(
                engine, sessionStore, subscriptionIndex);
        if (checkpoint != null && checkpoint.preparedRootState != null) {
            for (ManagedDocumentSnapshot restored : sessionStore.sessions()) {
                if (restored.status() == ManagedDocumentStatus.ACTIVE) {
                    engine.restorePreparedRootContextFromCheckpoint(
                            restored,
                            checkpoint.preparedRootState);
                }
            }
        }
        this.defaultFanout = fanout(
                dispatchLedger,
                (event, target, prefetchPolicy) -> {
                    processIndexed(target, event, prefetchPolicy);
                    return sessionStore.committedDeliveries().require(
                            event.eventBlueId(), target.sessionId());
                });
        this.rootPreparationExecutor = newRootPreparationExecutor(
                builder.rootPreparationParallelism,
                builder.rootPreparationQueueCapacity);
    }

    public static Builder builder() { return new Builder(); }

    /** Adds a new independent session with a deterministic local identifier. */
    public synchronized DocumentSessionId addDocument(Node exactDocument) {
        lifecycle.readLock().lock();
        try {
            long sequence = sessionSequence.incrementAndGet();
            DocumentSessionId id = DocumentSessionId.of(
                    "in-memory-session-" + sequence);
            ExternalOrderKey frontier = ExternalOrderKey.of(
                    Arrays.<Object>asList(0L, "admission", sequence));
            return addDocument(id, exactDocument, frontier);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Adds or attaches one explicitly identified host session. */
    public synchronized DocumentSessionId addDocument(
            DocumentSessionId id,
            Node exactDocument,
            ExternalOrderKey activationFrontier) {
        lifecycle.readLock().lock();
        try {
            DocumentAdmissionResult result =
                    sessionIndexPublisher.admitAndPublish(
                            DocumentRegistration.openOrCreate(
                                    Objects.requireNonNull(id, "id"),
                                    Objects.requireNonNull(
                                            exactDocument, "exactDocument"),
                                    Objects.requireNonNull(
                                            activationFrontier,
                                            "activationFrontier")));
            if (!result.succeeded()) {
                throw new IllegalStateException(
                        "Document admission failed: " + result.status() + " "
                                + result.diagnostic().orElse(""));
            }
            return id;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Compiles one immutable first-seen event shape for this environment. */
    public CoordinationEventShapeTemplate compileEventShape(
            String shapeIdentity,
            Node resolvedPrototype,
            Collection<String> volatileLeafPointers) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            return engine.compileEventShape(
                    shapeIdentity,
                    resolvedPrototype,
                    volatileLeafPointers);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Instantiates a shared shape and records work in this environment. */
    public CoordinationEventShapeInstance instantiateEventShape(
            CoordinationEventShapeTemplate template,
            Collection<CoordinationEventShapePatch> patches) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            return engine.instantiateEventShape(template, patches);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Admits one exact shape instance once and returns its verified handle. */
    public StoredCoordinationEvent prepareEvent(
            CoordinationEventShapeInstance instance,
            ExternalOrderKey eventOrderKey) {
        PreparedEventPublication prepared =
                prepareEventOnceForPublication(instance, eventOrderKey);
        publishPreparedEvent(prepared);
        return prepared.event();
    }

    /** Admits one exact event graph once and returns its verified handle. */
    public StoredCoordinationEvent prepareEvent(
            Node exactEvent,
            ExternalOrderKey eventOrderKey) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            final Node checkedEvent = Objects.requireNonNull(
                    exactEvent, "exactEvent");
            final ExternalOrderKey checkedOrder = Objects.requireNonNull(
                    eventOrderKey, "eventOrderKey");
            InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                    StoredCoordinationEvent> staged =
                    fragmentStore.stageVerifiedEventAdmission(
                            () -> engine.prepareEvent(
                                    checkedEvent, checkedOrder));
            PreparedEventPublication prepared = preparedEventPublication(
                    staged);
            publishPreparedEvent(prepared);
            return prepared.event();
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Returns one canonical event handle without splitting it on retry. */
    public synchronized StoredCoordinationEvent prepareEventOnce(
            String claimedEventBlueId,
            Node exactEvent,
            ExternalOrderKey eventOrderKey) {
        PreparedEventPublication prepared = prepareEventOnceForPublication(
                claimedEventBlueId, exactEvent, eventOrderKey);
        publishPreparedEvent(prepared);
        return prepared.event();
    }

    /**
     * Stages a shape-compiled first-seen event without re-materializing or
     * re-splitting its exact graph. A duplicate is bound by both event and
     * inventory identity before publication is skipped.
     */
    public synchronized PreparedEventPublication
            prepareEventOnceForPublication(
                    CoordinationEventShapeInstance instance,
                    ExternalOrderKey eventOrderKey) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            CoordinationEventShapeInstance checked = Objects.requireNonNull(
                    instance, "instance");
            ExternalOrderKey checkedOrder = Objects.requireNonNull(
                    eventOrderKey, "eventOrderKey");
            StoredCoordinationEvent existing = eventStore.find(
                    checked.eventBlueId()).orElse(null);
            if (existing != null) {
                if (!existing.orderKey().equals(checkedOrder)) {
                    throw new IllegalStateException(
                            "Stored event order conflict for "
                                    + checked.eventBlueId());
                }
                if (!existing.fragmentInventoryIdentity().equals(
                        checked.admission().inventory()
                                .inventoryIdentity())) {
                    throw new IllegalStateException(
                            "Stored event inventory conflict for "
                                    + checked.eventBlueId());
                }
                return new PreparedEventPublication(
                        this,
                        null,
                        eventStore.prepareCanonical(existing));
            }
            InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                    StoredCoordinationEvent> staged =
                    fragmentStore.stageVerifiedEventAdmission(
                            () -> engine.prepareEvent(
                                    checked, checkedOrder));
            if (!checked.eventBlueId().equals(
                    staged.result().eventBlueId())) {
                throw new IllegalStateException(
                        "Shape-compiled event identity changed at admission");
            }
            return preparedEventPublication(staged);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Fully validates and materializes a first-seen event append without
     * changing the fragment, inventory, or canonical-event stores.
     */
    public synchronized PreparedEventPublication
            prepareEventOnceForPublication(
                    String claimedEventBlueId,
                    Node exactEvent,
                    ExternalOrderKey eventOrderKey) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            String checkedBlueId = requireText(
                    claimedEventBlueId, "claimedEventBlueId");
            final Node checkedEvent = Objects.requireNonNull(
                    exactEvent, "exactEvent");
            final ExternalOrderKey checkedOrder = Objects.requireNonNull(
                    eventOrderKey, "eventOrderKey");
            StoredCoordinationEvent existing = eventStore.find(checkedBlueId)
                    .orElse(null);
            if (existing != null) {
                if (!checkedBlueId.equals(
                        DirectBlueIdCalculator.calculateBlueId(checkedEvent))) {
                    throw new IllegalArgumentException(
                            "Claimed event BlueId differs from exact event");
                }
                if (!existing.orderKey().equals(checkedOrder)) {
                    throw new IllegalStateException(
                            "Stored event order conflict for "
                                    + checkedBlueId);
                }
                return new PreparedEventPublication(
                        this,
                        null,
                        eventStore.prepareCanonical(existing));
            }
            InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                    StoredCoordinationEvent> staged =
                    fragmentStore.stageVerifiedEventAdmission(
                            () -> engine.prepareEvent(
                                    checkedBlueId,
                                    checkedEvent,
                                    checkedOrder));
            if (!checkedBlueId.equals(staged.result().eventBlueId())) {
                throw new IllegalArgumentException(
                        "Claimed event BlueId differs from exact event");
            }
            return preparedEventPublication(staged);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Publishes one prevalidated event delta at the fragment/event store lock
     * boundary. Every touched immutable key is checked before either store is
     * changed; unrelated prepared events therefore do not make it stale.
     */
    public void publishPreparedEvent(PreparedEventPublication publication) {
        publishPreparedEvent(publication, NO_HOST_EVENT_PUBLICATION);
    }

    /**
     * Publishes the event and a prevalidated host delta while all direct
     * fragment/event readers remain behind the same store monitors. The host
     * action must perform only no-callback authoritative pointer/map writes;
     * every operation that can reject the append belongs above this method.
     */
    public void publishPreparedEvent(
            PreparedEventPublication publication,
            Runnable prevalidatedHostPublication) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            PreparedEventPublication checked = Objects.requireNonNull(
                    publication, "publication");
            if (checked.owner != this) {
                throw new IllegalArgumentException(
                        "Prepared event belongs to another environment");
            }
            Runnable hostPublication = Objects.requireNonNull(
                    prevalidatedHostPublication,
                    "prevalidatedHostPublication");
            synchronized (fragmentStore) {
                synchronized (eventStore) {
                    checked.requireUnpublished();
                    if (checked.fragmentAdmission != null) {
                        fragmentStore
                                .validatePreparedVerifiedEventAdmission(
                                        checked.fragmentAdmission);
                    }
                    eventStore.validatePreparedCanonical(
                            checked.eventPublication);
                    if (checked.fragmentAdmission != null) {
                        fragmentStore
                                .publishPreparedVerifiedEventAdmissionUnchecked(
                                        checked.fragmentAdmission);
                    }
                    eventStore.publishPreparedCanonicalUnchecked(
                            checked.eventPublication);
                    hostPublication.run();
                    checked.published = true;
                }
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private PreparedEventPublication preparedEventPublication(
            InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                    StoredCoordinationEvent> staged) {
        InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent> checked = Objects.requireNonNull(
                        staged, "staged");
        return new PreparedEventPublication(
                this,
                checked.prepared(),
                eventStore.prepareCanonical(checked.result()));
    }

    /** Processes through the explicit current-Root compatibility lane. */
    public DemoTransition process(
            DocumentSessionId id,
            Node exactEvent,
            ExternalOrderKey eventOrderKey) {
        return process(
                id,
                exactEvent,
                eventOrderKey,
                DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY,
                Collections.<String>emptyList(),
                PrefetchPolicy.BALANCED);
    }

    /** Processes through the exact externally indexed candidate lane. */
    public DemoTransition processIndexed(
            DocumentSessionId id,
            Node exactEvent,
            ExternalOrderKey eventOrderKey,
            List<String> orderedOccurrenceKeys,
            PrefetchPolicy prefetchPolicy) {
        return process(
                id,
                exactEvent,
                eventOrderKey,
                DeliveryPlanningMode.INDEXED,
                orderedOccurrenceKeys,
                prefetchPolicy);
    }

    /** Processes an already admitted event without splitting it again. */
    public DemoTransition processIndexed(
            DocumentSessionId id,
            StoredCoordinationEvent event,
            List<String> orderedOccurrenceKeys,
            PrefetchPolicy prefetchPolicy) {
        lifecycle.readLock().lock();
        try {
            DocumentSessionId checkedId = Objects.requireNonNull(id, "id");
            CoordinationProcessingPlan plan = engine.planIndexed(
                    checkedId,
                    engine.session(checkedId).currentEpoch(),
                    Objects.requireNonNull(event, "event"),
                    Objects.requireNonNull(
                            orderedOccurrenceKeys, "orderedOccurrenceKeys"),
                    Objects.requireNonNull(
                            prefetchPolicy, "prefetchPolicy"));
            CoordinationTransition transition = engine.execute(plan);
            return sessionIndexPublisher.commitAndPublish(transition);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Processes one frozen route target against its exact planned revision. */
    public DemoTransition processIndexed(
            IndexedSessionCandidates target,
            StoredCoordinationEvent event,
            PrefetchPolicy prefetchPolicy) {
        return processIndexed(
                target, event, prefetchPolicy, PERMIT_PUBLICATION);
    }

    /**
     * Processes one frozen route target and lets a host reject the complete
     * transition before its authoritative session/index publication.
     */
    public DemoTransition processIndexed(
            IndexedSessionCandidates target,
            StoredCoordinationEvent event,
            PrefetchPolicy prefetchPolicy,
            CoordinationTransitionPublicationGuard publicationGuard) {
        lifecycle.readLock().lock();
        try {
            CoordinationTransitionPublicationGuard checkedGuard =
                    Objects.requireNonNull(
                            publicationGuard, "publicationGuard");
            IndexedSessionCandidates checkedTarget = Objects.requireNonNull(
                    target, "target");
            ManagedDocumentSnapshot current = engine.session(
                    checkedTarget.sessionId());
            if (current.currentEpoch() != checkedTarget.plannedEpoch()
                    || !current.currentRootBlueId().equals(
                            checkedTarget.plannedRootBlueId())
                    || !current.subscriptions().digest().equals(
                            checkedTarget.subscriptionSnapshotIdentity())) {
                throw new IllegalStateException(
                        "Frozen route target is stale for "
                                + checkedTarget.sessionId());
            }
            CoordinationProcessingPlan plan = engine.planIndexed(
                    checkedTarget.sessionId(),
                    checkedTarget.plannedEpoch(),
                    Objects.requireNonNull(event, "event"),
                    checkedTarget.orderedOccurrenceKeys(),
                    Objects.requireNonNull(
                            prefetchPolicy, "prefetchPolicy"));
            CoordinationTransition transition = engine.execute(plan);
            checkedGuard.validate(transition);
            return sessionIndexPublisher.commitAndPublish(transition);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private DemoTransition process(
            DocumentSessionId id,
            Node exactEvent,
            ExternalOrderKey eventOrderKey,
            DeliveryPlanningMode mode,
            List<String> orderedOccurrenceKeys,
            PrefetchPolicy prefetchPolicy) {
        lifecycle.readLock().lock();
        try {
            ProcessRequest request = new ProcessRequest(
                    Objects.requireNonNull(id, "id"),
                    engine.session(id).currentEpoch(),
                    Objects.requireNonNull(exactEvent, "exactEvent"),
                    Objects.requireNonNull(eventOrderKey, "eventOrderKey"),
                    Objects.requireNonNull(mode, "mode"),
                    Objects.requireNonNull(
                            orderedOccurrenceKeys, "orderedOccurrenceKeys"),
                    Objects.requireNonNull(
                            prefetchPolicy, "prefetchPolicy"),
                    true);
            CoordinationProcessingPlan plan = engine.plan(request);
            CoordinationTransition transition = engine.execute(plan);
            return sessionIndexPublisher.commitAndPublish(transition);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public CoordinationProcessingEngine engine() { return engine; }
    public InMemoryCoordinationFragmentStore fragmentStore() {
        return fragmentStore;
    }
    public InMemoryCoordinationSessionStore sessionStore() {
        return sessionStore;
    }
    public InMemoryCoordinationSubscriptionIndex subscriptionIndex() {
        return subscriptionIndex;
    }
    public InMemoryStoredCoordinationEventStore eventStore() {
        return eventStore;
    }

    public CoordinationEventAdmissionMetrics.Snapshot
            eventAdmissionMetrics() {
        return engine.eventAdmissionMetrics();
    }

    public CoordinationEventShapeMetrics.Snapshot eventShapeMetrics() {
        return engine.eventShapeMetrics();
    }

    /** Opaque identity of evidence accepted by this environment. */
    public String eventAdmissionDomainIdentity() {
        return engine.eventAdmissionDomainIdentity();
    }

    /** Exact cumulative incremental-projection work in this environment. */
    public FastPathWorkMetrics.Snapshot projectionFastPathMetrics() {
        return engine.projectionFastPathMetrics();
    }

    /** Exact cumulative verified fragment-transition work. */
    public CoordinationFragmentTransitionWorkSnapshot
            fragmentTransitionWorkSnapshot() {
        return engine.fragmentTransitionWorkSnapshot();
    }

    /** Current hard-bounded exact Root-view cache occupancy and work. */
    public CoordinationRootViewCacheSnapshot rootViewCacheSnapshot() {
        return engine.rootViewCacheSnapshot();
    }

    /** Exact cumulative reference-cut work performed by the live engine. */
    public ReferenceCutMetrics.Snapshot referenceCutMetrics() {
        return engine.referenceCutMetrics();
    }

    /** Cache-only preparation; authoritative stores remain unchanged. */
    public void primeEventAdmission(
            String claimedEventBlueId,
            Node exactEvent) {
        lifecycle.readLock().lock();
        try {
            engine.primeEventAdmission(
                    requireText(claimedEventBlueId, "claimedEventBlueId"),
                    Objects.requireNonNull(exactEvent, "exactEvent"));
        } finally {
            lifecycle.readLock().unlock();
        }
    }
    public CoordinationCommittedDeliveryProbe committedDeliveryProbe() {
        return sessionStore.committedDeliveries();
    }

    /**
     * Captures all authoritative in-memory stores at one quiescent boundary.
     * New work cannot enter while the write lock is held, and the dispatch
     * ledger rejects an incomplete freeze or in-flight Root claim.
     */
    public InMemoryCoordinationCheckpoint checkpoint() {
        lifecycle.writeLock().lock();
        try {
            requireOpen();
            requireParallelSchedulersQuiescent();
            Map<String, Node> currentRootViews =
                    engine.checkpointCurrentRootViews(
                            sessionStore.sessions());
            CoordinationProcessingEngine.PreparedCheckpointState
                    preparedRootState = engine.checkpointPreparedState(
                            sessionStore.sessions());
            return fragmentStore.fragmentCheckpoint(
                    sessionStore,
                    eventStore,
                    dispatchLedger,
                    currentRootViews,
                    preparedRootState,
                    sessionSequence.get());
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /** Dispatches one canonical event to every matching Root. */
    public CoordinationDispatchSnapshot dispatch(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            int maximumRootsPerChunk,
            PrefetchPolicy prefetchPolicy) {
        lifecycle.readLock().lock();
        try {
            return defaultFanout.dispatch(
                    event,
                    exactEventSubscriptionKeys,
                    sourceChannel,
                    maximumRootsPerChunk,
                    prefetchPolicy);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Resumes a prior environment-owned dispatch without re-querying routes. */
    public CoordinationDispatchSnapshot resume(
            String dispatchIdentity,
            PrefetchPolicy prefetchPolicy) {
        lifecycle.readLock().lock();
        try {
            return defaultFanout.resume(dispatchIdentity, prefetchPolicy);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Creates an environment-bound fan-out whose target generation is opened
     * at the combined authoritative-session/derived-route boundary.
     */
    public InMemoryCoordinationFanout fanout(
            InMemoryCoordinationDispatchLedger ledger,
            CoordinationIndexedDeliveryExecutor executor) {
        return new InMemoryCoordinationFanout(
                subscriptionIndex,
                Objects.requireNonNull(ledger, "ledger"),
                Objects.requireNonNull(executor, "executor"),
                sessionStore.committedDeliveries(),
                sessionIndexPublisher::openAuthoritativeCandidates);
    }

    /**
     * Creates the environment-bound two-phase adapter. Both preparation and
     * ordered publication participate in this environment's lifecycle gate.
     */
    public InMemoryCoordinationTwoPhaseDeliveryExecutor
            twoPhaseDeliveryExecutor(
                    CoordinationTransitionPublicationGuard publicationGuard) {
        lifecycle.readLock().lock();
        try {
            requireOpen();
            return new InMemoryCoordinationTwoPhaseDeliveryExecutor(
                    engine,
                    sessionIndexPublisher,
                    sessionStore,
                    Objects.requireNonNull(
                            publicationGuard, "publicationGuard"),
                    lifecycle.readLock(),
                    this::requireOpen);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Creates and registers a scheduler backed by the environment's single
     * bounded preparation pool.
     */
    public <P> BoundedCoordinationRootScheduler<P> parallelScheduler(
            CoordinationTwoPhaseDeliveryExecutor<P> deliveryExecutor,
            CoordinationParallelismPolicy policy,
            CoordinationRootPreparationObserver observer) {
        lifecycle.writeLock().lock();
        try {
            requireOpen();
            return registerParallelScheduler(
                    deliveryExecutor, policy, observer);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * Creates a parallel fan-out over the environment-owned bounded pool.
     * The caller retains ownership of its dispatch ledger. Target freeze uses
     * the same combined publication boundary as the serial environment path.
     */
    public <P> InMemoryCoordinationFanout parallelFanout(
            InMemoryCoordinationDispatchLedger ledger,
            CoordinationTwoPhaseDeliveryExecutor<P> deliveryExecutor,
            CoordinationParallelismPolicy policy,
            CoordinationRootPreparationObserver observer) {
        lifecycle.writeLock().lock();
        try {
            requireOpen();
            BoundedCoordinationRootScheduler<P> scheduler =
                    registerParallelScheduler(
                            deliveryExecutor, policy, observer);
            return InMemoryCoordinationFanout.parallel(
                    subscriptionIndex,
                    Objects.requireNonNull(ledger, "ledger"),
                    scheduler,
                    sessionStore.committedDeliveries(),
                    sessionIndexPublisher::openAuthoritativeCandidates);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private <P> BoundedCoordinationRootScheduler<P>
            registerParallelScheduler(
                    CoordinationTwoPhaseDeliveryExecutor<P> deliveryExecutor,
                    CoordinationParallelismPolicy policy,
                    CoordinationRootPreparationObserver observer) {
        BoundedCoordinationRootScheduler<P> scheduler =
                new BoundedCoordinationRootScheduler<P>(
                        rootPreparationExecutor,
                        Objects.requireNonNull(
                                deliveryExecutor, "deliveryExecutor"),
                        Objects.requireNonNull(policy, "policy"),
                        Objects.requireNonNull(observer, "observer"),
                        lifecycle.readLock(),
                        this::requireOpen);
        parallelSchedulers.add(scheduler);
        return scheduler;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            rootPreparationExecutor.shutdown();
            try {
                engine.close();
            } catch (RuntimeException problem) {
                failure = problem;
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
        RuntimeException shutdownFailure = awaitExecutorShutdown();
        if (shutdownFailure != null) {
            if (failure == null) {
                failure = shutdownFailure;
            } else {
                failure.addSuppressed(shutdownFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireParallelSchedulersQuiescent() {
        for (BoundedCoordinationRootScheduler<?> scheduler
                : parallelSchedulers) {
            if (!scheduler.isQuiescent()) {
                throw new IllegalStateException(
                        "Cannot checkpoint with parallel Root work: active="
                                + scheduler.activePreparationCount()
                                + ", outstanding="
                                + scheduler.outstandingResultCount());
            }
        }
    }

    private RuntimeException awaitExecutorShutdown() {
        try {
            if (rootPreparationExecutor.awaitTermination(
                    EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            rootPreparationExecutor.shutdownNow();
            if (rootPreparationExecutor.awaitTermination(
                    EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            return new IllegalStateException(
                    "Coordination preparation executor did not terminate");
        } catch (InterruptedException interrupted) {
            rootPreparationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            return new IllegalStateException(
                    "Interrupted while closing Coordination preparation "
                            + "executor",
                    interrupted);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "In-memory Coordination environment is closed");
        }
    }

    private static ThreadPoolExecutor newRootPreparationExecutor(
            int parallelism,
            int queueCapacity) {
        if (parallelism <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Root-preparation executor bounds must be positive");
        }
        return new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity),
                new DaemonPreparationThreadFactory(),
                new RunInCallerBackpressurePolicy());
    }

    /** Returns real executor work rather than an inferred test counter. */
    public CoordinationRootPreparationPoolSnapshot
            rootPreparationPoolSnapshot() {
        lifecycle.readLock().lock();
        try {
            return new CoordinationRootPreparationPoolSnapshot(
                    rootPreparationExecutor.getCorePoolSize(),
                    rootPreparationExecutor.getActiveCount(),
                    rootPreparationExecutor.getPoolSize(),
                    rootPreparationExecutor.getQueue().size(),
                    rootPreparationExecutor.getCompletedTaskCount(),
                    rootPreparationExecutor.getLargestPoolSize());
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }

    /** Immutable handle for one validated but not yet visible event. */
    public static final class PreparedEventPublication {
        private final InMemoryCoordinationEnvironment owner;
        private final InMemoryCoordinationFragmentStore
                .PreparedVerifiedEventAdmission fragmentAdmission;
        private final InMemoryStoredCoordinationEventStore
                .PreparedCanonicalPut eventPublication;
        private boolean published;

        private PreparedEventPublication(
                InMemoryCoordinationEnvironment owner,
                InMemoryCoordinationFragmentStore
                        .PreparedVerifiedEventAdmission fragmentAdmission,
                InMemoryStoredCoordinationEventStore
                        .PreparedCanonicalPut eventPublication) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.fragmentAdmission = fragmentAdmission;
            this.eventPublication = Objects.requireNonNull(
                    eventPublication, "eventPublication");
        }

        public StoredCoordinationEvent event() {
            return eventPublication.result();
        }

        private void requireUnpublished() {
            if (published) {
                throw new IllegalStateException(
                        "Prepared event was already published");
            }
        }
    }

    private static final class DaemonPreparationThreadFactory
            implements ThreadFactory {
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    Objects.requireNonNull(task, "task"),
                    "blue-coordination-prepare-"
                            + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class RunInCallerBackpressurePolicy
            implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(
                Runnable task,
                ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException(
                        "Coordination preparation executor is closed");
            }
            task.run();
        }
    }

    /** Mutable configuration for one reference in-memory environment. */
    public static final class Builder {
        private BlueContracts contracts;
        private DocumentProcessor documentProcessor;
        private InMemoryCoordinationFragmentStore fragmentStore;
        private InMemoryCoordinationSessionStore sessionStore;
        private InMemoryCoordinationSubscriptionIndex subscriptionIndex;
        private CoordinationProcessingBundleLoader bundleLoader;
        private CoordinationTransitionMemoStore memoStore;
        private CoordinationProcessingEngineObserver observer;
        private String environmentIdentity;
        private ReferenceCutConfiguration referenceCutConfiguration =
                ReferenceCutConfiguration.disabled();
        private int rootPreparationParallelism = PREPARATION_PARALLELISM;
        private int rootPreparationQueueCapacity =
                PREPARATION_QUEUE_CAPACITY;
        private Integer rootViewCacheMaximumSize;
        private boolean ownsRuntimes;
        private InMemoryCoordinationCheckpoint checkpoint;

        public Builder contracts(BlueContracts value) {
            contracts = Objects.requireNonNull(value, "contracts");
            return this;
        }
        public Builder documentProcessor(DocumentProcessor value) {
            documentProcessor = Objects.requireNonNull(
                    value, "documentProcessor");
            return this;
        }
        public Builder fragmentStore(
                InMemoryCoordinationFragmentStore value) {
            fragmentStore = Objects.requireNonNull(value, "fragmentStore");
            return this;
        }
        public Builder sessionStore(InMemoryCoordinationSessionStore value) {
            sessionStore = Objects.requireNonNull(value, "sessionStore");
            return this;
        }
        public Builder subscriptionIndex(
                InMemoryCoordinationSubscriptionIndex value) {
            subscriptionIndex = Objects.requireNonNull(
                    value, "subscriptionIndex");
            return this;
        }
        public Builder bundleLoader(CoordinationProcessingBundleLoader value) {
            bundleLoader = Objects.requireNonNull(value, "bundleLoader");
            return this;
        }
        public Builder transitionMemoStore(
                CoordinationTransitionMemoStore value) {
            memoStore = value;
            return this;
        }
        public Builder observer(CoordinationProcessingEngineObserver value) {
            observer = Objects.requireNonNull(value, "observer");
            return this;
        }
        public Builder environmentIdentity(String value) {
            environmentIdentity = Objects.requireNonNull(
                    value, "environmentIdentity");
            return this;
        }
        public Builder referenceCutConfiguration(
                ReferenceCutConfiguration value) {
            referenceCutConfiguration = Objects.requireNonNull(
                    value, "referenceCutConfiguration");
            return this;
        }
        /** Configures the engine's exact retained Root/planning entry bound. */
        public Builder rootViewCacheMaximumSize(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "rootViewCacheMaximumSize must be positive");
            }
            rootViewCacheMaximumSize = Integer.valueOf(value);
            return this;
        }
        /** Bounds concurrent expensive Root preparation for this host. */
        public Builder rootPreparationParallelism(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "rootPreparationParallelism must be positive");
            }
            rootPreparationParallelism = value;
            return this;
        }

        /** Bounds queued Root preparations; saturation runs in the caller. */
        public Builder rootPreparationQueueCapacity(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "rootPreparationQueueCapacity must be positive");
            }
            rootPreparationQueueCapacity = value;
            return this;
        }

        public Builder transferRuntimeOwnership(boolean value) {
            ownsRuntimes = value;
            return this;
        }
        public Builder checkpoint(InMemoryCoordinationCheckpoint value) {
            checkpoint = Objects.requireNonNull(value, "checkpoint");
            return this;
        }
        public InMemoryCoordinationEnvironment build() {
            return new InMemoryCoordinationEnvironment(this);
        }
    }
}
