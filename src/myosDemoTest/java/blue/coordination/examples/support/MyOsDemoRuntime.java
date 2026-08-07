package blue.coordination.examples.support;

import blue.coordination.engine.CoordinationFragmentSliceLoader;
import blue.coordination.engine.CoordinationFragmentSlicePlanner;
import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryReceipt;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationFragmentSlice;
import blue.coordination.engine.api.CoordinationFragmentSlicePlan;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.memory.DemoTransition;
import blue.coordination.engine.memory.CoordinationEngineWorkRecorder;
import blue.coordination.engine.memory.CoordinationEngineWorkSnapshot;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.engine.memory.CoordinationParallelismPolicy;
import blue.coordination.engine.memory.CoordinationRootPreparationObserver;
import blue.coordination.engine.memory.CoordinationTwoPhaseDeliveryExecutor;
import blue.coordination.engine.memory.InMemoryCoordinationCheckpoint;
import blue.coordination.engine.memory.InMemoryCoordinationDispatchLedger;
import blue.coordination.engine.memory.InMemoryCoordinationEnvironment;
import blue.coordination.engine.memory.InMemoryCoordinationEnvironment
        .PreparedEventPublication;
import blue.coordination.engine.memory.InMemoryCoordinationFanout;
import blue.coordination.engine.memory.InMemoryCoordinationTwoPhaseDeliveryExecutor;
import blue.coordination.engine.memory.InMemoryPreparedRootDelivery;
import blue.coordination.processor.CoordinationTestRuntime;
import blue.coordination.processor.mandate.MandateEligibilityDecision;
import blue.coordination.processor.mandate.OperationMandateEligibility;
import blue.language.api.NodeProviderOutcome;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compact executable host for MyOS demo documents.
 *
 * <p>Authored Blue documents and Timeline Entries enter this class as YAML
 * text. Parsing, preprocessing, initialization, splitting, indexed delivery,
 * PROCESS, fragment transition, and atomic commit use the real current
 * Language/Contracts/BEX/Coordination stack.</p>
 */
public final class MyOsDemoRuntime implements AutoCloseable {

    enum AppendFailureBoundary {
        BEFORE_FRAGMENT_ADMISSION,
        AFTER_FRAGMENT_ADMISSION,
        AFTER_JOURNAL_STAGING
    }

    private static final int DEFAULT_ROOTS_PER_CHUNK = 128;
    private static final long BASE_TIMESTAMP_MICROS =
            1_785_000_000_000_000L;
    private static final String INITIALIZATION_PUBLICATION_SCOPE =
            "initialization-snapshots";

    private final CoordinationTestRuntime runtime;
    private final InMemoryCoordinationEnvironment environment;
    private final InMemoryCoordinationDispatchLedger dispatchLedger;
    private final InMemoryCoordinationFanout fanout;
    private final MyOsDemoEvidence evidence;
    private final MyOsOperationTimingRecorder operationTiming;
    private final CoordinationEngineWorkRecorder engineWork;
    private final Map<String, MyOsDemoDocument> documents =
            new LinkedHashMap<>();
    private final Map<DocumentSessionId, MyOsDemoDocument> documentsBySession =
            new LinkedHashMap<>();
    private final Map<String, String> initialBlueIds =
            new LinkedHashMap<>();
    private final Map<String, String> canonicalIdentityInputBlueIds =
            new LinkedHashMap<>();
    private final Map<String, MyOsDemoTimeline> timelines =
            new LinkedHashMap<>();
    private final Map<String, CachedRootView> cachedRootViews =
            new LinkedHashMap<>();
    private final Map<String, MyOsDemoEntry> authoredEntriesByBlueId =
            new LinkedHashMap<>();
    private final Map<String, MyOsDocumentIdentity> identitiesByDocumentKey =
            new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, String> documentKeysByIdentity =
            new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, List<MyOsManagedEmbedding>>
            managedEmbeddings = new LinkedHashMap<>();
    private final Map<String, Map<DocumentSessionId, DemoTransition>>
            transitionsByEvent = new LinkedHashMap<>();
    private final MyOsPositionedTimelineJournal journal;
    private final MyOsEventInventoryRegistry eventInventories;
    private final MyOsTopologyCatalog topology;
    private final MyOsInitializationCoordinator<MyOsDemoDocument>
            initialization;
    private final MyOsTimelineDocumentIndex timelineIndex;
    private final MyOsDeliveryLedger topologyDeliveryLedger;
    private final Map<String, DeliveryCapture> activeDispatches =
            new ConcurrentHashMap<>();
    private final MyOsWorkRecorder work = new MyOsWorkRecorder();
    private final Object exactPublicationOwner = new Object();
    private final Map<String, Node> ownedInitializationEvidence =
            new LinkedHashMap<>();
    private final Map<String, Map<String, Node>> currentExactScopes =
            new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, String>
            publishedManagedInventories = new LinkedHashMap<>();

    private long admissionSequence;
    private long timelineEntrySequence;
    private AppendFailureBoundary nextAppendFailureBoundary;
    private RuntimeException nextAppendFailure;

    private MyOsDemoRuntime(String exampleId, String caseId) {
        evidence = MyOsDemoEvidence.begin(exampleId, caseId);
        operationTiming = MyOsOperationTimingRecorder.begin(
                exampleId, caseId);
        runtime = MyOsDemoKernel.runtime();
        engineWork = new CoordinationEngineWorkRecorder();
        environment = InMemoryCoordinationEnvironment.builder()
                .contracts(runtime.contracts())
                .documentProcessor(runtime.processor())
                .observer(MyOsProcessingEngineObservers.compose(
                        operationTiming, engineWork))
                .environmentIdentity("blue-coordination/myos-demo-suite/1.0")
                .build();
        dispatchLedger = new InMemoryCoordinationDispatchLedger();
        journal = new MyOsPositionedTimelineJournal();
        eventInventories = new MyOsEventInventoryRegistry();
        topology = new MyOsTopologyCatalog();
        initialization = new MyOsInitializationCoordinator<>();
        timelineIndex = new MyOsTimelineDocumentIndex();
        topologyDeliveryLedger = new MyOsDeliveryLedger();
        fanout = parallelFanout();
    }

    private MyOsDemoRuntime(
            String exampleId,
            String caseId,
            MyOsDemoCheckpoint checkpoint) {
        MyOsDemoCheckpoint checked = Objects.requireNonNull(
                checkpoint, "checkpoint");
        evidence = MyOsDemoEvidence.begin(exampleId, caseId);
        operationTiming = MyOsOperationTimingRecorder.begin(
                exampleId, caseId);
        runtime = MyOsDemoKernel.runtime();
        engineWork = new CoordinationEngineWorkRecorder();
        environment = InMemoryCoordinationEnvironment.builder()
                .contracts(runtime.contracts())
                .documentProcessor(runtime.processor())
                .observer(MyOsProcessingEngineObservers.compose(
                        operationTiming, engineWork))
                .environmentIdentity(
                        "blue-coordination/myos-demo-suite/1.0")
                .checkpoint(checked.environment)
                .build();
        dispatchLedger = checked.fanoutLedger.copyAtQuiescence();
        journal = checked.journal.copy();
        eventInventories = checked.eventInventories.copy();
        topology = checked.topology.copy();
        initialization = checked.initialization.copyAtQuiescence();
        timelineIndex = checked.timelineIndex.copy();
        topologyDeliveryLedger = checked.topologyDeliveryLedger.copy();
        fanout = parallelFanout();

        try {
            documents.putAll(checked.documents);
            initialBlueIds.putAll(checked.initialBlueIds);
            canonicalIdentityInputBlueIds.putAll(
                    checked.canonicalIdentityInputBlueIds);
            ownedInitializationEvidence.putAll(cloneExactNodes(
                    checked.ownedInitializationEvidence));
            authoredEntriesByBlueId.putAll(checked.authoredEntries);
            managedEmbeddings.putAll(checked.managedEmbeddings);
            for (Map.Entry<String, Map<DocumentSessionId, DemoTransition>> entry
                    : checked.transitionsByEvent.entrySet()) {
                transitionsByEvent.put(
                        entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            for (MyOsDemoDocument document : documents.values()) {
                MyOsDemoKernel.registerExactDocument(
                        document.initialBlueId(),
                        document.exactInitialDocument());
                documentsBySession.put(document.sessionId(), document);
                MyOsDocumentIdentity identity = new MyOsDocumentIdentity(
                        document.sessionId().value(),
                        document.initialBlueId());
                identitiesByDocumentKey.put(document.key(), identity);
                documentKeysByIdentity.put(identity, document.key());
                evidence.recordDocument(
                        document,
                        canonicalIdentityInputBlueIds.get(document.key()),
                        initialization.requireTerminalReceipt(identity));
            }
            synchronizeManagedPublications();
            for (MyOsTimelineCheckpoint timeline : checked.timelines) {
                MyOsDemoTimeline restored = MyOsDemoTimeline.restore(
                        this, timeline);
                timelines.put(restored.timelineId(), restored);
            }
            admissionSequence = checked.admissionSequence;
            timelineEntrySequence = checked.timelineEntrySequence;
        } catch (RuntimeException | Error failure) {
            try {
                MyOsDemoKernel.releaseCurrentExactNodes(
                        exactPublicationOwner);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                environment.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static MyOsDemoRuntime create() {
        return create(
                MyOsDemoEvidence.unassignedExampleId(),
                MyOsDemoEvidence.unassignedExampleId());
    }

    /** Creates an isolated runtime whose reports retain a stable example id. */
    public static MyOsDemoRuntime create(String exampleId) {
        return create(exampleId, exampleId);
    }

    /** Creates an isolated runtime with explicit family and case identities. */
    public static MyOsDemoRuntime create(
            String exampleId,
            String caseId) {
        return new MyOsDemoRuntime(exampleId, caseId);
    }

    /** Restores a private mutable branch without parsing or replay. */
    public static MyOsDemoRuntime fork(
            String exampleId,
            String caseId,
            MyOsDemoCheckpoint checkpoint) {
        return new MyOsDemoRuntime(
                exampleId,
                caseId,
                Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    public String exampleId() {
        return evidence.exampleId();
    }

    public String caseId() {
        return evidence.caseId();
    }

    public synchronized MyOsDemoDocument addDocument(
            String key,
            String authoredYaml) {
        return addDocument(key, authoredYaml, List.of());
    }

    /**
     * Admits one logical document with explicit host-owned child identities.
     * BlueId equality is verified as evidence but never used to infer identity.
     */
    public synchronized MyOsDemoDocument addDocument(
            String key,
            String authoredYaml,
            List<MyOsManagedEmbedding> declaredEmbeddings) {
        Objects.requireNonNull(key, "key");
        if (documents.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate document key: " + key);
        }
        String resolvedYaml = MyOsDemoYaml.resolveInitialBlueIds(
                authoredYaml, initialBlueIds);
        Node source = runtime.parseSourceYaml(resolvedYaml);
        work.sourceParsed();
        String initialBlueId = runtime.calculateSourceDocumentBlueId(source);
        Node exactInitial = runtime.canonicalize(source);
        MyOsDemoKernel.registerExactDocument(
                initialBlueId, exactInitial);
        DocumentSessionId sessionId = DocumentSessionId.of(
                "myos-demo/" + key);
        MyOsDocumentIdentity identity = new MyOsDocumentIdentity(
                sessionId.value(), initialBlueId);
        long admissionHighWater = journal.highWaterSequence();
        EmbeddingAdmissionPlan embeddingPlan = planManagedEmbeddings(
                source, declaredEmbeddings);
        Node adoptedSource = new MyOsCurrentStateGraft().apply(
                source, embeddingPlan.replacements());
        admissionSequence++;
        ExternalOrderKey admissionOrder = journal.highWaterPosition()
                .map(MyOsJournalPosition::orderKey)
                .orElseGet(() -> ExternalOrderKey.of(
                        Arrays.<Object>asList(
                                BigInteger.ZERO,
                                "admission",
                                admissionSequence,
                                key)));
        MyOsTopologyCatalog.DocumentState stagedState =
                new MyOsTopologyCatalog.DocumentState(
                        key,
                        identity,
                        sessionId,
                        initialBlueId,
                        0L,
                        admissionHighWater,
                        admissionOrder);
        topology.validateRegistrationWithLinks(
                stagedState,
                admissionHighWater,
                embeddingPlan.desiredLinks());

        MyOsDemoDocument document = initialization.initialize(
                identity,
                sessionId.value(),
                () -> {
                    MyOsDemoDocument initialized = initializeDocument(
                            key,
                            resolvedYaml,
                            exactInitial,
                            initialBlueId,
                            sessionId,
                            adoptedSource,
                            admissionOrder);
                    return new MyOsInitializationCoordinator.Completed<>(
                            initialized,
                            environment.engine().session(sessionId)
                                    .currentRootBlueId());
                });
        ManagedDocumentSnapshot committed = environment.engine().session(
                sessionId);
        topology.registerWithLinks(
                new MyOsTopologyCatalog.DocumentState(
                        key,
                        identity,
                        sessionId,
                        committed.currentRootBlueId(),
                        committed.currentEpoch(),
                        admissionHighWater,
                        committed.committedFrontier()),
                admissionHighWater,
                embeddingPlan.desiredLinks());
        documents.put(key, document);
        documentsBySession.put(sessionId, document);
        identitiesByDocumentKey.put(key, identity);
        documentKeysByIdentity.put(identity, key);
        managedEmbeddings.put(
                identity, List.copyOf(embeddingPlan.declarations()));
        initialBlueIds.put(key, initialBlueId);
        String canonicalIdentityInputBlueId =
                runtime.calculateBlueId(exactInitial);
        canonicalIdentityInputBlueIds.put(
                key, canonicalIdentityInputBlueId);
        synchronizeManagedPublications();
        for (MyOsDemoTimeline timeline : timelines.values()) {
            topologyDeliveryLedger.admit(
                    deliveryStream(identity, timeline.timelineId()),
                    admissionHighWater);
        }
        refreshTimelineIndex(identity);
        evidence.recordDocument(
                document,
                canonicalIdentityInputBlueId,
                initialization.requireTerminalReceipt(identity));
        return document;
    }

    public synchronized MyOsDemoTimeline timeline(
            String timelineId,
            MyOsDemoActor actor) {
        MyOsDemoTimeline existing = timelines.get(timelineId);
        if (existing != null) {
            if (!existing.actor().equals(actor)) {
                throw new IllegalArgumentException(
                        "Timeline already belongs to another actor: "
                                + timelineId);
            }
            return existing;
        }
        MyOsDemoTimeline created = new MyOsDemoTimeline(
                this, timelineId, actor);
        timelines.put(timelineId, created);
        long highWater = journal.highWaterSequence();
        for (MyOsDocumentIdentity identity
                : identitiesByDocumentKey.values()) {
            topologyDeliveryLedger.admit(
                    deliveryStream(identity, timelineId), highWater);
        }
        return created;
    }

    public synchronized MyOsDemoEntry append(
            MyOsDemoTimeline timeline,
            MyOsDemoOperation operation) {
        long appendStartedNanos = System.nanoTime();
        MyOsDemoTimeline checkedTimeline = Objects.requireNonNull(
                timeline, "timeline");
        Objects.requireNonNull(operation, "operation");
        if (!checkedTimeline.belongsTo(this)) {
            throw new IllegalArgumentException(
                    "Timeline belongs to another demo runtime");
        }
        long entryBuildStartedNanos = System.nanoTime();
        PendingTimelineAppend pending = checkedTimeline.prepare(operation);
        MyOsDemoEntry entry = pending.entry();
        long entryBuildNanos = elapsedNanos(entryBuildStartedNanos);
        long duplicateCheckStartedNanos = System.nanoTime();
        MyOsDemoEntry prior = authoredEntriesByBlueId.get(entry.blueId());
        if (prior != null) {
            if (!NodeWireForm.get(prior.exactEntry()).equals(
                    NodeWireForm.get(entry.exactEntry()))
                    || !prior.orderKey().equals(entry.orderKey())) {
                throw new IllegalStateException(
                        "Conflicting authored entry " + entry.blueId());
            }
            operationTiming.recordAppend(
                    prior,
                    elapsedNanos(appendStartedNanos),
                    entryBuildNanos,
                    elapsedNanos(duplicateCheckStartedNanos),
                    0L,
                    0L);
            checkedTimeline.commit(pending);
            commitTimelineTimestamp(pending.entry().timestampMicros());
            return prior;
        }
        long duplicateCheckNanos = elapsedNanos(
                duplicateCheckStartedNanos);

        long eventPrepareStartedNanos = System.nanoTime();
        throwIfAppendFailure(AppendFailureBoundary.BEFORE_FRAGMENT_ADMISSION);
        work.eventPrepared();
        long fullEventSplitsBefore = environment.eventAdmissionMetrics()
                .fullEventSplits();
        PreparedEventPublication preparedEvent;
        try {
            preparedEvent = environment.prepareEventOnceForPublication(
                    entry.blueId(), entry.exactEntry(), entry.orderKey());
        } finally {
            long fullEventSplitsAfter = environment.eventAdmissionMetrics()
                    .fullEventSplits();
            work.eventSplits(Math.subtractExact(
                    fullEventSplitsAfter, fullEventSplitsBefore));
        }
        throwIfAppendFailure(AppendFailureBoundary.AFTER_FRAGMENT_ADMISSION);
        long eventPrepareNanos = elapsedNanos(eventPrepareStartedNanos);
        long journalStartedNanos = System.nanoTime();
        StoredCoordinationEvent stored = preparedEvent.event();
        MyOsEventInventoryRegistry.PreparedRecord preparedInventory =
                eventInventories.prepareRecord(
                        entry.blueId(),
                        stored.fragmentInventoryIdentity());
        MyOsPositionedTimelineJournal.PreparedAppend preparedJournal =
                journal.prepareAppend(
                entry,
                entry.binding(),
                stored.fragmentInventoryIdentity());
        checkedTimeline.validate(pending);
        validateTimelineTimestamp(entry.timestampMicros());
        long resultingTimelineEntrySequence = Math.addExact(
                timelineEntrySequence, 1L);
        MyOsTimelineDocumentIndex.PreparedReplacement preparedRoutes =
                prepareTimelineIndexPublication(pending);
        eventInventories.validate(preparedInventory);
        journal.validate(preparedJournal);
        timelineIndex.validate(preparedRoutes);
        throwIfAppendFailure(AppendFailureBoundary.AFTER_JOURNAL_STAGING);

        /* Every validation, hash, clone, query, and allocation-heavy
         * materialization is complete. No user callback runs below this
         * publication boundary. */
        environment.publishPreparedEvent(preparedEvent, () -> {
            eventInventories.publishPreparedUnchecked(preparedInventory);
            journal.publishPreparedUnchecked(preparedJournal);
            checkedTimeline.publish(pending);
            timelineEntrySequence = resultingTimelineEntrySequence;
            authoredEntriesByBlueId.put(entry.blueId(), entry);
            timelineIndex.publishPreparedUnchecked(preparedRoutes);
        });
        long journalNanos = elapsedNanos(journalStartedNanos);
        operationTiming.recordAppend(
                entry,
                elapsedNanos(appendStartedNanos),
                entryBuildNanos,
                duplicateCheckNanos,
                eventPrepareNanos,
                journalNanos);
        return entry;
    }

    long peekNextTimelineTimestampMicros() {
        return Math.addExact(
                BASE_TIMESTAMP_MICROS,
                Math.addExact(timelineEntrySequence, 1L));
    }

    private void commitTimelineTimestamp(long timestampMicros) {
        validateTimelineTimestamp(timestampMicros);
        publishTimelineTimestamp();
    }

    private void validateTimelineTimestamp(long timestampMicros) {
        long expected = peekNextTimelineTimestampMicros();
        if (timestampMicros != expected) {
            throw new IllegalStateException(
                    "stale Timeline timestamp: expected " + expected
                            + " but append used " + timestampMicros);
        }
    }

    private void publishTimelineTimestamp() {
        timelineEntrySequence = Math.addExact(timelineEntrySequence, 1L);
    }

    synchronized void failNextEventAdmissionForTest(
            RuntimeException failure) {
        failNextAppendAtForTest(
                AppendFailureBoundary.BEFORE_FRAGMENT_ADMISSION,
                failure);
    }

    synchronized void failNextAppendAtForTest(
            AppendFailureBoundary boundary,
            RuntimeException failure) {
        if (nextAppendFailure != null) {
            throw new IllegalStateException(
                    "an append failure is already armed");
        }
        nextAppendFailureBoundary = Objects.requireNonNull(
                boundary, "boundary");
        nextAppendFailure = Objects.requireNonNull(
                failure, "failure");
    }

    private void throwIfAppendFailure(AppendFailureBoundary boundary) {
        if (nextAppendFailure != null
                && nextAppendFailureBoundary == boundary) {
            RuntimeException failure = nextAppendFailure;
            nextAppendFailure = null;
            nextAppendFailureBoundary = null;
            throw failure;
        }
    }

    /**
     * Performs cache-only canonical preparation for the next exact append.
     * It publishes no event, fragment, inventory, journal, or cursor state.
     */
    void primeEventAdmission(MyOsDemoEntry entry) {
        MyOsDemoEntry checked = Objects.requireNonNull(entry, "entry");
        int eventsBefore = environment.eventStore().size();
        int fragmentsBefore = environment.fragmentStore()
                .physicalFragmentCount();
        int inventoriesBefore = environment.fragmentStore()
                .inventoryCount();
        long journalBefore = journal.highWaterSequence();
        environment.primeEventAdmission(
                checked.blueId(), checked.exactEntry());
        if (eventsBefore != environment.eventStore().size()
                || fragmentsBefore != environment.fragmentStore()
                        .physicalFragmentCount()
                || inventoriesBefore != environment.fragmentStore()
                        .inventoryCount()
                || journalBefore != journal.highWaterSequence()) {
            throw new IllegalStateException(
                    "Append priming changed authoritative state");
        }
    }

    /** Lets the environment discover and process every affected Root. */
    public MyOsDemoDispatch process(MyOsDemoEntry entry) {
        return process(entry, DEFAULT_ROOTS_PER_CHUNK);
    }

    /**
     * Processes a frozen canonical target list in bounded Root-session
     * chunks. One Root always receives one PROCESS call containing all of its
     * matching occurrences.
     */
    public synchronized MyOsDemoDispatch process(
            MyOsDemoEntry entry,
            int maximumRootsPerChunk) {
        long processStartedNanos = System.nanoTime();
        long validationStartedNanos = System.nanoTime();
        MyOsDemoEntry checkedEntry = Objects.requireNonNull(entry, "entry");
        if (maximumRootsPerChunk <= 0) {
            throw new IllegalArgumentException(
                    "maximumRootsPerChunk must be positive");
        }
        MyOsPositionedTimelineJournal.Stored stored = journal.require(
                checkedEntry.blueId());
        MyOsDemoEntry canonicalEntry = stored.entry();
        if (!NodeWireForm.get(canonicalEntry.exactEntry()).equals(
                NodeWireForm.get(checkedEntry.exactEntry()))) {
            throw new IllegalArgumentException(
                    "Entry wire form differs from the journal: "
                            + checkedEntry.blueId());
        }
        long validationNanos = elapsedNanos(validationStartedNanos);

        StoredCoordinationEvent event = environment.eventStore().require(
                canonicalEntry.blueId());
        if (!stored.eventInventoryIdentity().equals(
                event.fragmentInventoryIdentity())
                || !eventInventories.requireInventory(
                        canonicalEntry.blueId()).equals(
                        event.fragmentInventoryIdentity())) {
            throw new IllegalStateException(
                    "Timeline journal and canonical event store disagree");
        }

        MyOsWorkSnapshot before = work.snapshot();
        MyOsDemoTimeline timeline = timelines.get(canonicalEntry.timelineId());
        if (timeline == null
                || !timeline.actor().actorId().equals(
                        canonicalEntry.actorId())) {
            throw new IllegalArgumentException(
                    "Entry does not belong to a current runtime Timeline");
        }
        long routingStartedNanos = System.nanoTime();
        work.routeIndexProbed();
        DeliveryCapture capture = new DeliveryCapture(
                canonicalEntry, stored.position(), routingStartedNanos);
        if (activeDispatches.putIfAbsent(
                canonicalEntry.blueId(), capture) != null) {
            throw new IllegalStateException(
                    "Nested MyOS dispatch is unsupported");
        }
        CoordinationDispatchSnapshot canonicalDispatch;
        try {
            canonicalDispatch = fanout.dispatch(
                    event,
                    timeline.subscriptionKeys(),
                    canonicalEntry.sourceChannel(),
                    maximumRootsPerChunk,
                    PrefetchPolicy.MINIMUM_ROUND_TRIPS);
        } catch (RuntimeException | Error failure) {
            try {
                /* Fan-out commits one Root at a time. A later Root may fail,
                 * so publish the exact inventories of every child that did
                 * commit before exposing the retry boundary. */
                synchronizeManagedPublications();
            } catch (RuntimeException synchronizationFailure) {
                failure.addSuppressed(synchronizationFailure);
            }
            throw failure;
        } finally {
            activeDispatches.remove(canonicalEntry.blueId(), capture);
        }
        advanceManagedPublications(capture);
        if (canonicalDispatch.plan().targets().isEmpty()) {
            throw new IllegalStateException(
                    "No active Root matches Timeline Entry "
                            + canonicalEntry.blueId()
                            + "; subscriptionKeys="
                            + timeline.subscriptionKeys()
                            + "; indexedKeys="
                            + environment.subscriptionIndex()
                            .subscriptionKeys());
        }
        long routingNanos = capture.firstDeliveryStartedNanos() < 0L
                ? elapsedNanos(routingStartedNanos)
                : Math.max(0L, capture.firstDeliveryStartedNanos()
                        - routingStartedNanos);
        operationTiming.recordRouting(
                canonicalEntry,
                validationNanos,
                routingNanos,
                canonicalDispatch.plan().targets().size());

        Map<String, MyOsDemoResult> results = new LinkedHashMap<>();
        List<Integer> chunkSizes = new ArrayList<>();
        long hostBookkeepingNanos = capture.hostBookkeepingNanos();
        for (List<IndexedSessionCandidates> chunk
                : canonicalDispatch.plan().chunks()) {
            work.fanoutChunkProcessed();
            chunkSizes.add(chunk.size());
        }
        for (CoordinationDeliveryReceipt receipt
                : canonicalDispatch.receipts()) {
            if (!receipt.committed()) {
                throw new IllegalStateException(
                        "Canonical fanout did not commit " + receipt.sessionId());
            }
            MyOsDemoDocument document = documentsBySession.get(
                    receipt.sessionId());
            if (document == null) {
                throw new IllegalStateException(
                        "Route names unknown session " + receipt.sessionId());
            }
            DemoTransition transition = capture.transition(
                    receipt.sessionId());
            if (transition == null) {
                transition = transitionsByEvent
                        .getOrDefault(canonicalEntry.blueId(), Map.of())
                        .get(receipt.sessionId());
            }
            if (transition == null) {
                throw new IllegalStateException(
                        "Committed fanout receipt has no in-runtime transition "
                                + receipt.sessionId());
            }
            results.put(document.key(),
                    new MyOsDemoResult(canonicalEntry, transition));
        }
        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "No routed Root committed Timeline Entry "
                            + canonicalEntry.blueId());
        }
        long finalizationStartedNanos = System.nanoTime();
        MyOsDemoDispatch dispatch = new MyOsDemoDispatch(
                canonicalEntry,
                results,
                chunkSizes,
                work.snapshot().minus(before));
        hostBookkeepingNanos = Math.addExact(
                hostBookkeepingNanos,
                elapsedNanos(finalizationStartedNanos));
        operationTiming.endProcess(
                canonicalEntry,
                elapsedNanos(processStartedNanos),
                hostBookkeepingNanos);
        return dispatch;
    }

    public synchronized MandateEligibilityDecision mandateDecision(
            String targetDocumentKey,
            String mandateDocumentKey,
            MyOsDemoEntry entry) {
        MyOsDemoDocument target = requireDocument(targetDocumentKey);
        MyOsDemoDocument mandate = requireDocument(mandateDocumentKey);
        MyOsDemoTimeline timeline = timelines.get(entry.timelineId());
        boolean historyComplete = timeline != null
                && timeline.hasCompleteHistoryThrough(entry.blueId())
                && entry.equals(authoredEntriesByBlueId.get(entry.blueId()));
        return OperationMandateEligibility.evaluate(
                OperationMandateEligibility.Evidence.builder()
                        .mandateState(currentRoot(mandateDocumentKey))
                        .initialMandateDocument(
                                mandate.exactInitialDocument())
                        .event(entry.exactEntry())
                        .targetInitialDocument(
                                target.exactInitialDocument())
                        .currentDocument(currentRoot(targetDocumentKey))
                        .historyCompleteAtEventTime(historyComplete)
                        .build());
    }

    public synchronized MyOsDemoResult deliverMandateTargetWhenEligible(
            String targetDocumentKey,
            String mandateDocumentKey,
            MyOsDemoEntry entry) {
        MandateEligibilityDecision decision = mandateDecision(
                targetDocumentKey, mandateDocumentKey, entry);
        if (!decision.isEligible()) {
            throw new IllegalStateException(
                    "Mandate denied demo operation: " + decision.reason());
        }
        return process(entry).require(targetDocumentKey);
    }

    public Node exactEvent(String sourceYaml) {
        return resolvedExactEvent(sourceYaml).canonicalRoot();
    }

    /** Parses, preprocesses, and resolves one authored entry exactly once. */
    ResolvedSnapshot resolvedExactEvent(String sourceYaml) {
        Node source = runtime.parseSourceYaml(sourceYaml);
        Node preprocessed = runtime.preprocess(source);
        return runtime.resolveToSnapshot(preprocessed);
    }

    public String directBlueId(Node exact) {
        return runtime.calculateBlueId(exact);
    }

    public synchronized Node currentRoot(String key) {
        return cachedRootView(key).exactRoot();
    }

    public synchronized String currentRootBlueId(String key) {
        return environment.engine().session(
                requireDocument(key).sessionId()).currentRootBlueId();
    }

    public synchronized long currentEpoch(String key) {
        return environment.engine().session(
                requireDocument(key).sessionId()).currentEpoch();
    }

    public synchronized String processingViewAt(String key, String path) {
        ManagedDocumentSnapshot session = environment.engine().session(
                requireDocument(key).sessionId());
        var result = environment.fragmentStore().readProcessingAll(
                session.fragmentInventoryIdentity(),
                Collections.singletonList(session.currentRootBlueId()))
                .get(session.currentRootBlueId());
        if (result == null || result.nodes().size() != 1) {
            return "unavailable";
        }
        Node selected = NodePathEditor.getOrNull(
                result.nodes().get(0), path);
        return selected == null
                ? "absent"
                : String.valueOf(NodeWireForm.get(selected));
    }


    public synchronized int currentFragmentCount(String key) {
        return currentFragmentBlueIds(key).size();
    }

    public synchronized Set<String> currentFragmentBlueIds(String key) {
        ManagedDocumentSnapshot session = environment.engine().session(
                requireDocument(key).sessionId());
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                environment.fragmentStore().requireInventory(
                        session.fragmentInventoryIdentity())
                        .fragmentBlueIds()));
    }

    public synchronized Object value(String documentKey, String path) {
        FrozenNode selected = cachedRootView(documentKey)
                .resolvedSnapshot()
                .resolvedAt(path);
        if (selected == null) {
            return null;
        }
        Object scalar = selected.getValue();
        if (scalar == null && selected.isReferenceOnly()) {
            var materialized = environment.fragmentStore()
                    .fetchResultByBlueId(selected.getReferenceBlueId());
            if (materialized.nodes().size() == 1) {
                scalar = materialized.nodes().get(0).getValue();
            }
        }
        return scalar != null ? scalar : selected.toNode();
    }

    public Object value(Node node, String path) {
        ResolvedSnapshot snapshot = runtime.resolveToSnapshotPreservingPaths(
                node, Collections.singletonList(path));
        return snapshot.resolvedRoot().get(path);
    }

    public synchronized MyOsDemoDocument document(String key) {
        return requireDocument(key);
    }

    public synchronized int documentCount() {
        return documents.size();
    }

    public synchronized int timelineCount() {
        return timelines.size();
    }

    public synchronized List<MyOsDemoEntry> authoredEntries() {
        return List.copyOf(authoredEntriesByBlueId.values());
    }

    public synchronized int journalEntryCount() {
        return journal.size();
    }

    public synchronized int storedEventInventoryCount() {
        return eventInventories.storedInventoryCount();
    }

    public synchronized int canonicalStoredEventCount() {
        return environment.eventStore().size();
    }

    public synchronized int physicalFragmentCount() {
        return environment.fragmentStore().physicalFragmentCount();
    }

    public CoordinationEventAdmissionMetrics.Snapshot
            eventAdmissionMetrics() {
        return environment.eventAdmissionMetrics();
    }

    /** Labels the next raw timing record without doing work in its span. */
    public synchronized void labelNextOperationTimingSample(
            String sampleKind) {
        operationTiming.labelNextOperation(sampleKind);
    }

    /** Exact count of typed full-projection fallbacks in this runtime. */
    public long subscriptionProjectionColdFallbackCount() {
        return operationTiming.subscriptionProjectionColdFallbackCount();
    }

    static MyOsAppendTemplateMetrics.Snapshot appendTemplateMetrics() {
        return MyOsPreparedEntryTemplates.metrics();
    }

    public MyOsWorkRecorder work() {
        return work;
    }

    /** Captures a quiescent, isolated-fork checkpoint without replay. */
    public synchronized MyOsDemoCheckpoint checkpoint() {
        if (!activeDispatches.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot checkpoint while a dispatch is active");
        }
        if (nextAppendFailure != null) {
            throw new IllegalStateException(
                    "Cannot checkpoint with an armed append failure");
        }
        InMemoryCoordinationCheckpoint environmentCheckpoint =
                environment.checkpoint();
        InMemoryCoordinationDispatchLedger fanoutCheckpoint =
                dispatchLedger.copyAtQuiescence();
        List<MyOsTimelineCheckpoint> timelineCheckpoints =
                new ArrayList<>();
        List<MyOsDemoTimeline> orderedTimelines =
                new ArrayList<>(timelines.values());
        orderedTimelines.sort((left, right) ->
                ExternalOrderKey.compareTextCodePoints(
                        left.timelineId(), right.timelineId()));
        for (MyOsDemoTimeline timeline : orderedTimelines) {
            timelineCheckpoints.add(timeline.checkpoint());
        }
        String fingerprint = checkpointFingerprint(
                environmentCheckpoint,
                fanoutCheckpoint,
                timelineCheckpoints);
        return new MyOsDemoCheckpoint(
                environmentCheckpoint,
                fanoutCheckpoint,
                documents,
                initialBlueIds,
                canonicalIdentityInputBlueIds,
                ownedInitializationEvidence,
                authoredEntriesByBlueId,
                timelineCheckpoints,
                journal,
                eventInventories,
                topology,
                initialization,
                timelineIndex,
                topologyDeliveryLedger,
                managedEmbeddings,
                transitionsByEvent,
                admissionSequence,
                timelineEntrySequence,
                fingerprint);
    }

    /** Captures a checkpoint and binds its named boundary to runtime evidence. */
    public synchronized MyOsDemoCheckpoint checkpoint(String evidenceName) {
        MyOsDemoCheckpoint checkpoint = checkpoint();
        evidence.recordCheckpoint(evidenceName, checkpoint);
        return checkpoint;
    }

    /** Stable full checkpoint digest for branch-equivalence assertions. */
    public synchronized String stateFingerprint() {
        return checkpoint().stateFingerprint();
    }

    /** Snapshot of work with a real engine/store production call site. */
    public MyOsMeasuredWork measuredWork() {
        MyOsWorkSnapshot host = work.snapshot();
        return new MyOsMeasuredWork(
                host.sourceParses(),
                host.documentInitializations(),
                host.eventPreparations(),
                host.eventSplits(),
                host.routeIndexProbes(),
                host.fanoutChunks(),
                engineWork.snapshot(),
                environment.fragmentStore().singleReadCount(),
                environment.fragmentStore().batchReadCount(),
                environment.fragmentStore().requestedIdentityCount());
    }

    public CoordinationEngineWorkSnapshot engineWorkSnapshot() {
        return engineWork.snapshot();
    }

    public synchronized int initializationCount(String documentKey) {
        return initialization.initialized(
                requireDocumentIdentity(documentKey)) ? 1 : 0;
    }

    public synchronized MyOsInitializationCoordinator.Evidence
            initializationEvidence() {
        return initialization.evidence();
    }

    public synchronized List<MyOsInitializationCoordinator.Receipt>
            initializationReceipts() {
        return initialization.terminalReceipts();
    }

    public synchronized long admissionJournalHighWater(String documentKey) {
        return topology.state(requireDocumentIdentity(documentKey))
                .admissionJournalHighWater();
    }

    public synchronized long committedJournalHighWater(
            String documentKey,
            MyOsDemoTimeline timeline) {
        MyOsDemoTimeline checked = Objects.requireNonNull(timeline, "timeline");
        if (!checked.belongsTo(this)) {
            throw new IllegalArgumentException(
                    "Timeline belongs to another demo runtime");
        }
        return topologyDeliveryLedger.committedHighWater(
                deliveryStream(
                        requireDocumentIdentity(documentKey),
                        checked.timelineId()));
    }

    public synchronized Set<String> documentsForTimeline(
            MyOsDemoTimeline timeline) {
        MyOsDemoTimeline checked = Objects.requireNonNull(
                timeline, "timeline");
        if (!checked.belongsTo(this)) {
            throw new IllegalArgumentException(
                    "Timeline belongs to another demo runtime");
        }
        Set<String> result = new LinkedHashSet<>();
        for (MyOsDocumentIdentity identity
                : timelineIndex.documents(checked.binding())) {
            String key = documentKeysByIdentity.get(identity);
            if (key != null) result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    public synchronized Set<MyOsTimelineBinding> timelinesForDocument(
            String documentKey) {
        return timelineIndex.timelines(
                requireDocumentIdentity(documentKey));
    }

    public synchronized List<MyOsTopologyLink> childrenOf(String documentKey) {
        return topology.childrenOf(requireDocumentIdentity(documentKey));
    }

    public synchronized List<MyOsTopologyLink> parentsOf(String documentKey) {
        return List.copyOf(
                topology.parentsOf(requireDocumentIdentity(documentKey)));
    }

    public synchronized MyOsDocumentSlice slice(
            String rootDocumentKey,
            String absoluteEmbeddedPath) {
        long singleReadsBefore = environment.fragmentStore().singleReadCount();
        long batchReadsBefore = environment.fragmentStore().batchReadCount();
        long requestedBefore = environment.fragmentStore()
                .requestedIdentityCount();
        MyOsTopologyCatalog.Resolution resolved = topology.resolve(
                rootDocumentKey, absoluteEmbeddedPath);
        ManagedDocumentSnapshot owning = environment.engine().session(
                resolved.owningRoot().sessionId());
        CoordinationFragmentInventory inventory =
                environment.fragmentStore().requireInventory(
                        owning.fragmentInventoryIdentity());
        CoordinationFragmentSlicePlan plan =
                new CoordinationFragmentSlicePlanner().plan(
                        inventory, resolved.absolutePath());
        if (!plan.selectedRootBlueId().equals(
                resolved.selectedDocument().currentRootBlueId())) {
            throw new IllegalStateException(
                    "Physical selected Root differs from logical topology");
        }
        CoordinationFragmentSlice physical =
                new CoordinationFragmentSliceLoader().load(
                        environment.fragmentStore(), plan);
        MyOsDocumentSlice result = new MyOsDocumentSlice(
                owning.sessionId(),
                resolved.absolutePath(),
                resolved.selectedDocument().identity(),
                resolved.selectedDocument().currentRootBlueId(),
                resolved.chain(),
                physical);
        evidence.recordPhysicalSlice(
                rootDocumentKey,
                result,
                inventory.fragmentBlueIds().size(),
                Math.subtractExact(
                        environment.fragmentStore().singleReadCount(),
                        singleReadsBefore),
                Math.subtractExact(
                        environment.fragmentStore().batchReadCount(),
                        batchReadsBefore),
                Math.subtractExact(
                        environment.fragmentStore().requestedIdentityCount(),
                        requestedBefore));
        return result;
    }

    /**
     * Declares the only logical children that may occupy the supplied paths.
     * Present exact children are published immediately; absent paths remain
     * pending and are reconciled automatically after successful PROCESS.
     * Content equality never chooses a logical child.
     */
    public synchronized List<MyOsTopologyLink> reconcileManagedEmbeddings(
            String documentKey,
            List<MyOsManagedEmbedding> supplied) {
        MyOsDocumentIdentity parent = requireDocumentIdentity(documentKey);
        Node exactParent = currentRoot(documentKey);
        List<MyOsManagedEmbedding> declarations = new ArrayList<>(
                Objects.requireNonNull(supplied, "managedEmbeddings"));
        declarations.sort((left, right) ->
                ExternalOrderKey.compareTextCodePoints(
                        left.relativePath(), right.relativePath()));
        List<MyOsTopologyCatalog.DesiredLink> desired = new ArrayList<>();
        String priorPath = null;
        for (MyOsManagedEmbedding declaration : declarations) {
            MyOsManagedEmbedding checked = Objects.requireNonNull(
                    declaration, "managed embedding");
            if (checked.relativePath().equals(priorPath)) {
                throw new IllegalArgumentException(
                        "Duplicate managed embedding path " + priorPath);
            }
            priorPath = checked.relativePath();
            MyOsDocumentIdentity child = identitiesByDocumentKey.get(
                    checked.childKey());
            if (child == null) {
                throw new IllegalArgumentException(
                        "Managed child is not admitted: " + checked.childKey());
            }
            Node selected = NodePathEditor.getOrNull(
                    exactParent, checked.relativePath());
            if (selected != null) {
                String selectedBlueId = selected.isReferenceOnly()
                        ? selected.getBlueId()
                        : runtime.calculateBlueId(selected);
                if (!topology.state(child).currentRootBlueId().equals(
                        selectedBlueId)) {
                    throw new IllegalArgumentException(
                            "Managed path does not contain the declared child's "
                                    + "current exact Root: "
                                    + checked.relativePath());
                }
                desired.add(new MyOsTopologyCatalog.DesiredLink(
                        checked.relativePath(), child));
            }
        }
        MyOsTopologyCatalog.DocumentState state = topology.state(parent);
        List<MyOsTopologyLink> reconciled = topology.reconcile(
                parent,
                state.generation(),
                journal.highWaterSequence(),
                desired);
        managedEmbeddings.put(parent, List.copyOf(declarations));
        synchronizeManagedPublications();
        return reconciled;
    }

    private void reconcileConfiguredEmbeddings(
            MyOsDocumentIdentity parent,
            Node exactParent,
            long activationJournalSequence) {
        List<MyOsTopologyCatalog.DesiredLink> desired =
                desiredConfiguredEmbeddings(parent, exactParent);
        MyOsTopologyCatalog.DocumentState state = topology.state(parent);
        topology.reconcile(
                parent,
                state.generation(),
                activationJournalSequence,
                desired);
    }

    private void validateConfiguredEmbeddings(
            MyOsDocumentIdentity parent,
            Node exactParent,
            long activationJournalSequence) {
        List<MyOsTopologyCatalog.DesiredLink> desired =
                desiredConfiguredEmbeddings(parent, exactParent);
        MyOsTopologyCatalog.DocumentState state = topology.state(parent);
        topology.validateReconciliation(
                parent,
                state.generation(),
                activationJournalSequence,
                desired);
    }

    /**
     * Rejects an explicit managed-child reference that would already make the
     * host topology cyclic. This guard runs before PROCESS because Language
     * cannot materialize a cyclic value graph far enough for the ordinary
     * post-PROCESS reconciliation validator to inspect it. It is driven only
     * by declared managed paths and exact references in the canonical event;
     * operation names and business-specific routing never participate.
     */
    private void validateReferencedManagedLinks(
            MyOsDocumentIdentity parent,
            Node exactEvent,
            long activationJournalSequence) {
        List<MyOsManagedEmbedding> declarations = managedEmbeddings
                .getOrDefault(parent, List.of());
        if (declarations.isEmpty()) return;

        Object eventWire = NodeWireForm.get(exactEvent);
        List<MyOsTopologyCatalog.DesiredLink> prospective =
                new ArrayList<>();
        boolean addsReferencedChild = false;
        for (MyOsManagedEmbedding declaration : declarations) {
            MyOsDocumentIdentity child = identitiesByDocumentKey.get(
                    declaration.childKey());
            if (child == null) {
                throw new IllegalStateException(
                        "Declared managed child disappeared: "
                                + declaration.childKey());
            }
            if (isActiveManagedLink(
                    parent, declaration.relativePath(), child)) {
                prospective.add(new MyOsTopologyCatalog.DesiredLink(
                        declaration.relativePath(), child));
                continue;
            }
            String currentChildRoot = topology.state(child)
                    .currentRootBlueId();
            if (containsExactText(eventWire, currentChildRoot)) {
                prospective.add(new MyOsTopologyCatalog.DesiredLink(
                        declaration.relativePath(), child));
                addsReferencedChild = true;
            }
        }
        if (!addsReferencedChild) return;
        MyOsTopologyCatalog.DocumentState state = topology.state(parent);
        topology.validateReconciliation(
                parent,
                state.generation(),
                activationJournalSequence,
                prospective);
    }

    private static boolean containsExactText(Object value, String expected) {
        if (expected.equals(value)) return true;
        if (value instanceof Map<?, ?>) {
            for (Object child : ((Map<?, ?>) value).values()) {
                if (containsExactText(child, expected)) return true;
            }
        } else if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) {
                if (containsExactText(child, expected)) return true;
            }
        }
        return false;
    }

    private List<MyOsTopologyCatalog.DesiredLink>
            desiredConfiguredEmbeddings(
                    MyOsDocumentIdentity parent,
                    Node exactParent) {
        List<MyOsManagedEmbedding> declarations = managedEmbeddings
                .getOrDefault(parent, List.of());
        List<MyOsTopologyCatalog.DesiredLink> desired = new ArrayList<>();
        for (MyOsManagedEmbedding declaration : declarations) {
            MyOsDocumentIdentity child = identitiesByDocumentKey.get(
                    declaration.childKey());
            if (child == null) {
                throw new IllegalStateException(
                        "Declared managed child disappeared: "
                                + declaration.childKey());
            }
            Node selected = NodePathEditor.getOrNull(
                    exactParent, declaration.relativePath());
            if (selected == null) continue;
            String selectedBlueId = selected.isReferenceOnly()
                    ? selected.getBlueId()
                    : runtime.calculateBlueId(selected);
            /* A frozen fan-out may process a parent before its autonomous
             * child. The already-active logical edge remains authoritative
             * while both copies advance under that same entry. A new edge,
             * however, must point at the child's exact current Root. */
            if (!topology.state(child).currentRootBlueId().equals(
                    selectedBlueId)
                    && !isActiveManagedLink(
                            parent,
                            declaration.relativePath(),
                            child)) {
                throw new IllegalStateException(
                        "PROCESS published conflicting managed content at "
                                + declaration.relativePath());
            }
            desired.add(new MyOsTopologyCatalog.DesiredLink(
                    declaration.relativePath(), child));
        }
        return List.copyOf(desired);
    }

    private boolean isActiveManagedLink(
            MyOsDocumentIdentity parent,
            String relativePath,
            MyOsDocumentIdentity child) {
        return topology.childrenOf(parent).stream().anyMatch(link ->
                link.relativePath().equals(relativePath)
                        && link.child().equals(child));
    }

    /** Atomically publishes the complete exact surface owned by this runtime. */
    private void synchronizeManagedPublications() {
        Set<MyOsDocumentIdentity> desired = new LinkedHashSet<>();
        for (List<MyOsManagedEmbedding> declarations
                : managedEmbeddings.values()) {
            for (MyOsManagedEmbedding declaration : declarations) {
                MyOsDocumentIdentity child = identitiesByDocumentKey.get(
                        declaration.childKey());
                if (child == null) {
                    throw new IllegalStateException(
                            "Declared managed child disappeared: "
                                    + declaration.childKey());
                }
                desired.add(child);
            }
        }

        Map<MyOsDocumentIdentity, String> nextInventories =
                new LinkedHashMap<>();
        Map<String, Map<String, Node>> nextScopes = new LinkedHashMap<>();
        if (!ownedInitializationEvidence.isEmpty()) {
            nextScopes.put(
                    INITIALIZATION_PUBLICATION_SCOPE,
                    immutableExactNodes(ownedInitializationEvidence));
        }

        List<MyOsDocumentIdentity> ordered = new ArrayList<>(desired);
        Collections.sort(ordered);
        for (MyOsDocumentIdentity child : ordered) {
            ManagedDocumentSnapshot snapshot = environment.engine().session(
                    requireDocument(
                            documentKeysByIdentity.get(child)).sessionId());
            String inventoryIdentity = snapshot.fragmentInventoryIdentity();
            String scope = managedPublicationScope(child);
            Map<String, Node> exactNodes = null;
            if (inventoryIdentity.equals(
                    publishedManagedInventories.get(child))) {
                exactNodes = currentExactScopes.get(scope);
            }
            if (exactNodes == null) {
                CoordinationFragmentInventory inventory = environment
                        .fragmentStore().requireInventory(inventoryIdentity);
                exactNodes = immutableExactNodes(
                        currentExactSurface(inventory));
            }
            nextScopes.put(scope, exactNodes);
            nextInventories.put(child, inventoryIdentity);
        }

        if (nextInventories.equals(publishedManagedInventories)
                && nextScopes.keySet().equals(currentExactScopes.keySet())) {
            return;
        }
        replaceCurrentExactScopes(nextScopes);
        publishedManagedInventories.clear();
        publishedManagedInventories.putAll(nextInventories);
    }

    /** Advances bounded publications only after the whole fan-out commits. */
    private void advanceManagedPublications(DeliveryCapture capture) {
        boolean managedInventoryChanged = false;
        for (Map.Entry<DocumentSessionId, DemoTransition> entry
                : capture.transitions().entrySet()) {
            MyOsDemoDocument document = documentsBySession.get(
                    entry.getKey());
            if (document == null) {
                throw new IllegalStateException(
                        "Committed transition names an unknown document");
            }
            MyOsDocumentIdentity identity = requireDocumentIdentity(
                    document.key());
            if (!publishedManagedInventories.containsKey(identity)) {
                continue;
            }
            CoordinationFragmentTransition fragments = entry.getValue()
                    .transition().fragmentTransition();
            if (!fragments.resultingInventory().inventoryIdentity().equals(
                    publishedManagedInventories.get(identity))) {
                managedInventoryChanged = true;
            }
        }
        if (managedInventoryChanged) {
            synchronizeManagedPublications();
        }
    }

    private Map<String, Node> currentExactSurface(
            CoordinationFragmentInventory inventory) {
        Map<String, NodeProviderResult> loaded = environment.fragmentStore()
                .readAll(inventory.fragmentBlueIds());
        Map<String, Node> exactNodes = new LinkedHashMap<>();
        for (String blueId : inventory.fragmentBlueIds()) {
            NodeProviderResult result = loaded.get(blueId);
            if (result == null
                    || result.outcome() != NodeProviderOutcome.FOUND
                    || result.nodes().size() != 1) {
                throw new IllegalStateException(
                        "Managed child inventory lacks exact physical "
                                + "content for " + blueId);
            }
            exactNodes.put(blueId, result.nodes().get(0));
        }
        return exactNodes;
    }

    private void replaceOwnedInitializationEvidence(
            Map<String, Node> replacement) {
        Map<String, Node> retained = immutableClonedExactNodes(replacement);
        Map<String, Map<String, Node>> nextScopes = new LinkedHashMap<>(
                currentExactScopes);
        if (retained.isEmpty()) {
            nextScopes.remove(INITIALIZATION_PUBLICATION_SCOPE);
        } else {
            nextScopes.put(INITIALIZATION_PUBLICATION_SCOPE, retained);
        }
        replaceCurrentExactScopes(nextScopes);
        ownedInitializationEvidence.clear();
        ownedInitializationEvidence.putAll(retained);
    }

    private void replaceCurrentExactScopes(
            Map<String, Map<String, Node>> replacement) {
        MyOsDemoKernel.replaceCurrentExactNodes(
                exactPublicationOwner, replacement);
        currentExactScopes.clear();
        for (Map.Entry<String, Map<String, Node>> scope
                : replacement.entrySet()) {
            currentExactScopes.put(
                    scope.getKey(), immutableExactNodes(scope.getValue()));
        }
    }

    private static Map<String, Node> immutableExactNodes(
            Map<String, Node> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(source, "exact nodes")));
    }

    private static Map<String, Node> immutableClonedExactNodes(
            Map<String, Node> source) {
        return Collections.unmodifiableMap(cloneExactNodes(source));
    }

    private static Map<String, Node> cloneExactNodes(
            Map<String, Node> source) {
        Map<String, Node> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                source, "exact nodes").entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "blueId"),
                    Objects.requireNonNull(
                            entry.getValue(), "exact node").clone());
        }
        return copy;
    }

    private static String managedPublicationScope(
            MyOsDocumentIdentity identity) {
        return "managed-inventory\u0000" + identity.logicalId() + '\u0000'
                + identity.initialDocumentBlueId();
    }

    public InMemoryCoordinationEnvironment environment() {
        return environment;
    }

    private InMemoryCoordinationFanout parallelFanout() {
        InMemoryCoordinationTwoPhaseDeliveryExecutor engineDelivery =
                environment.twoPhaseDeliveryExecutor(
                        transition -> Objects.requireNonNull(
                                transition, "transition"));
        return environment.parallelFanout(
                dispatchLedger,
                new MyOsTwoPhaseDeliveryExecutor(engineDelivery),
                CoordinationParallelismPolicy.lowLatencyDefault(),
                CoordinationRootPreparationObserver.none());
    }

    private EmbeddingAdmissionPlan planManagedEmbeddings(
            Node authoredSource,
            List<MyOsManagedEmbedding> supplied) {
        List<MyOsManagedEmbedding> declarations = new ArrayList<>(
                Objects.requireNonNull(supplied, "declaredEmbeddings"));
        declarations.sort((left, right) -> {
            int path = ExternalOrderKey.compareTextCodePoints(
                    left.relativePath(), right.relativePath());
            return path != 0
                    ? path
                    : ExternalOrderKey.compareTextCodePoints(
                            left.childKey(), right.childKey());
        });
        List<MyOsTopologyCatalog.DesiredLink> desired = new ArrayList<>();
        List<MyOsCurrentStateGraft.Replacement> replacements =
                new ArrayList<>();
        String priorPath = null;
        for (MyOsManagedEmbedding declaration : declarations) {
            MyOsManagedEmbedding checked = Objects.requireNonNull(
                    declaration, "managed embedding");
            if (checked.relativePath().equals(priorPath)) {
                throw new IllegalArgumentException(
                        "Duplicate managed embedding path " + priorPath);
            }
            priorPath = checked.relativePath();
            MyOsDocumentIdentity child = identitiesByDocumentKey.get(
                    checked.childKey());
            if (child == null) {
                throw new IllegalArgumentException(
                        "Managed child is not admitted: " + checked.childKey());
            }
            Node authoredEvidence = NodePathEditor.getOrNull(
                    authoredSource, checked.relativePath());
            if (authoredEvidence == null
                    || !authoredEvidence.isReferenceOnly()
                    || !child.initialDocumentBlueId().equals(
                            authoredEvidence.getBlueId())) {
                throw new IllegalArgumentException(
                        "Managed child declaration lacks exact initial identity "
                                + "evidence at " + checked.relativePath());
            }
            desired.add(new MyOsTopologyCatalog.DesiredLink(
                    checked.relativePath(), child));
            replacements.add(new MyOsCurrentStateGraft.Replacement(
                    checked.relativePath(), currentRoot(checked.childKey())));
        }
        return new EmbeddingAdmissionPlan(
                declarations, desired, replacements);
    }

    private MyOsTimelineDocumentIndex.PreparedReplacement
            prepareTimelineIndexPublication(PendingTimelineAppend pending) {
        PendingTimelineAppend checked = Objects.requireNonNull(
                pending, "pending");
        Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>> desired =
                new LinkedHashMap<>();
        for (MyOsDocumentIdentity identity
                : identitiesByDocumentKey.values()) {
            String key = documentKeysByIdentity.get(identity);
            if (key == null) {
                continue;
            }
            DocumentSessionId sessionId = requireDocument(key).sessionId();
            Set<MyOsTimelineBinding> active = new LinkedHashSet<>();
            for (MyOsDemoTimeline timeline : timelines.values()) {
                MyOsTimelineBinding binding;
                if (timeline == checked.owner()) {
                    binding = checked.entry().binding();
                } else if (timeline.hasBinding()) {
                    binding = timeline.binding();
                } else {
                    continue;
                }
                if (environment.subscriptionIndex().sessionsFor(
                        timeline.subscriptionKeys()).contains(sessionId)) {
                    active.add(binding);
                }
            }
            desired.put(identity, active);
        }
        return timelineIndex.prepareDocumentBindings(desired);
    }

    private void refreshTimelineIndex(MyOsDocumentIdentity identity) {
        String key = documentKeysByIdentity.get(identity);
        if (key == null) return;
        DocumentSessionId sessionId = requireDocument(key).sessionId();
        Set<MyOsTimelineBinding> active = new LinkedHashSet<>();
        for (MyOsDemoTimeline timeline : timelines.values()) {
            if (timeline.hasBinding()
                    && environment.subscriptionIndex().sessionsFor(
                            timeline.subscriptionKeys()).contains(sessionId)) {
                active.add(timeline.binding());
            }
        }
        timelineIndex.replaceDocumentBindings(identity, active);
    }

    private static MyOsDeliveryLedger.StreamKey deliveryStream(
            MyOsDocumentIdentity identity,
            String timelineId) {
        return new MyOsDeliveryLedger.StreamKey(identity, timelineId);
    }

    private MyOsDemoDocument initializeDocument(
            String key,
            String resolvedYaml,
            Node exactInitial,
            String initialBlueId,
            DocumentSessionId sessionId,
            Node adoptedSource,
            ExternalOrderKey admissionOrder) {
        Node preprocessed = runtime.preprocess(adoptedSource);
        ResolvedSnapshot initializationSnapshot =
                runtime.resolveToSnapshot(preprocessed);
        Map<String, Node> previousEvidence = immutableClonedExactNodes(
                ownedInitializationEvidence);
        Map<String, Node> nextEvidence = new LinkedHashMap<>(
                previousEvidence);
        Node initializationRoot = initializationSnapshot.canonicalRoot();
        Node prior = nextEvidence.putIfAbsent(
                initializationSnapshot.blueId(), initializationRoot);
        if (prior != null
                && !NodeWireForm.get(prior).equals(
                        NodeWireForm.get(initializationRoot))) {
            throw new IllegalStateException(
                    "Initialization BlueId has conflicting exact content");
        }
        replaceOwnedInitializationEvidence(nextEvidence);
        try {
            DocumentProcessingResult initializationResult =
                    runtime.initializeDocument(initializationSnapshot);
            if (initializationResult.status() != ProcessorStatus.SUCCESS
                    || !initializationResult.commits()) {
                throw new IllegalStateException(
                        "Initialization failed for " + key + ": "
                                + initializationResult.status() + " "
                                + (initializationResult.diagnostic() == null
                                ? ""
                                : initializationResult.diagnostic()
                                .message()));
            }
            ResolvedSnapshot initializedSnapshot = runtime.resolveToSnapshot(
                    initializationResult.document());
            environment.addDocument(
                    sessionId,
                    initializationResult.document(),
                    admissionOrder);
            cachedRootViews.put(
                    key,
                    new CachedRootView(
                            0L,
                            initializationResult.document(),
                            initializedSnapshot));
            work.documentInitialized();
            return new MyOsDemoDocument(
                    key,
                    resolvedYaml,
                    exactInitial,
                    initialBlueId,
                    sessionId);
        } catch (RuntimeException | Error failure) {
            try {
                replaceOwnedInitializationEvidence(previousEvidence);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private MyOsDemoDocument requireDocument(String key) {
        MyOsDemoDocument document = documents.get(key);
        if (document == null) {
            throw new IllegalArgumentException("Unknown document key: " + key);
        }
        return document;
    }

    private MyOsDocumentIdentity requireDocumentIdentity(String key) {
        MyOsDocumentIdentity identity = identitiesByDocumentKey.get(key);
        if (identity == null) {
            throw new IllegalArgumentException("Unknown document key: " + key);
        }
        return identity;
    }

    private CachedRootView cachedRootView(String key) {
        MyOsDemoDocument document = requireDocument(key);
        ManagedDocumentSnapshot session = environment.engine().session(
                document.sessionId());
        CachedRootView cached = cachedRootViews.get(key);
        if (cached != null && cached.epoch() == session.currentEpoch()) {
            return cached;
        }
        CoordinationFragmentInventory inventory =
                environment.fragmentStore().requireInventory(
                        session.fragmentInventoryIdentity());
        work.fullRootReconstructed();
        Node exactRoot = inventory.reconstruct(
                environment.fragmentStore().canonicalFragmentProvider());
        CachedRootView current = new CachedRootView(
                session.currentEpoch(),
                exactRoot,
                runtime.resolveToSnapshot(exactRoot));
        cachedRootViews.put(key, current);
        return current;
    }

    private record CachedRootView(
            long epoch,
            Node exactRoot,
            ResolvedSnapshot resolvedSnapshot) {

        private CachedRootView {
            exactRoot = Objects.requireNonNull(
                    exactRoot, "exactRoot").clone();
            Objects.requireNonNull(resolvedSnapshot, "resolvedSnapshot");
        }

        @Override
        public Node exactRoot() {
            return exactRoot.clone();
        }
    }

    private record EmbeddingAdmissionPlan(
            List<MyOsManagedEmbedding> declarations,
            List<MyOsTopologyCatalog.DesiredLink> desiredLinks,
            List<MyOsCurrentStateGraft.Replacement> replacements) {

        private EmbeddingAdmissionPlan {
            declarations = List.copyOf(declarations);
            desiredLinks = List.copyOf(desiredLinks);
            replacements = List.copyOf(replacements);
        }
    }

    /**
     * Runs the expensive per-Root semantic work on bounded workers while the
     * scheduler keeps publication on the caller in frozen session order.
     */
    private final class MyOsTwoPhaseDeliveryExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<
                    MyOsPreparedRootDelivery> {
        private final InMemoryCoordinationTwoPhaseDeliveryExecutor delegate;

        private MyOsTwoPhaseDeliveryExecutor(
                InMemoryCoordinationTwoPhaseDeliveryExecutor delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public MyOsPreparedRootDelivery prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            DeliveryCapture capture = activeDispatches.get(
                    event.eventBlueId());
            if (capture == null
                    || !capture.entry().blueId().equals(
                            event.eventBlueId())) {
                throw new IllegalStateException(
                        "Fanout delivery has no matching MyOS dispatch context");
            }
            MyOsDemoDocument document = documentsBySession.get(
                    target.sessionId());
            if (document == null) {
                throw new IllegalStateException(
                        "Route names unknown session " + target.sessionId());
            }
            MyOsDocumentIdentity identity = requireDocumentIdentity(
                    document.key());
            validateReferencedManagedLinks(
                    identity,
                    capture.entry().exactEntry(),
                    capture.position().sequence());

            capture.markDeliveryStarted(System.nanoTime());
            operationTiming.beginDelivery(
                    capture.entry(),
                    document.key(),
                    target.orderedOccurrenceKeys().size());
            long deliveryStartedNanos = System.nanoTime();
            try {
                InMemoryPreparedRootDelivery prepared = delegate.prepare(
                        event, target, prefetchPolicy);
                validateConfiguredEmbeddings(
                        identity,
                        prepared.transition().platformResult()
                                .processResult().document(),
                        capture.position().sequence());
                MyOsOperationTimingRecorder.DeliveryTiming timing =
                        operationTiming.detachDelivery();
                return new MyOsPreparedRootDelivery(
                        prepared,
                        capture,
                        document,
                        identity,
                        timing,
                        deliveryStartedNanos);
            } catch (RuntimeException | Error failure) {
                operationTiming.endDelivery(
                        elapsedNanos(deliveryStartedNanos));
                throw failure;
            }
        }

        @Override
        public CoordinationCommittedDelivery commit(
                MyOsPreparedRootDelivery prepared) {
            MyOsPreparedRootDelivery checked = Objects.requireNonNull(
                    prepared, "prepared");
            operationTiming.attachDelivery(checked.timing);
            long bookkeepingStartedNanos = System.nanoTime();
            long delegateCommitNanos = 0L;
            MyOsDeliveryLedger.Claim progress = null;
            boolean progressCommitted = false;
            try {
                progress = topologyDeliveryLedger.claim(
                        deliveryStream(
                                checked.identity,
                                checked.capture.entry().timelineId()),
                        checked.capture.position());
                if (!progress.acquired()) {
                    throw new IllegalStateException(
                            "Fanout selected a non-deliverable topology "
                                    + "position: " + progress.outcome());
                }

                operationTiming.beginCommitPublication();
                long delegateCommitStartedNanos = System.nanoTime();
                CoordinationCommittedDelivery committed;
                try {
                    committed = delegate.commit(checked.prepared);
                    operationTiming.endCommitPublication();
                } finally {
                    delegateCommitNanos = elapsedNanos(
                            delegateCommitStartedNanos);
                }
                DemoTransition transition = checked.prepared
                        .committedTransition()
                        .orElseThrow(() -> new IllegalStateException(
                                "Committed Root lacks transition evidence"));
                topologyDeliveryLedger.commit(progress);
                progressCommitted = true;

                ManagedDocumentSnapshot snapshot = environment.engine()
                        .session(checked.prepared.target().sessionId());
                topology.advance(
                        checked.identity,
                        snapshot.currentRootBlueId(),
                        snapshot.committedFrontier());
                cachedRootViews.remove(checked.document.key());
                reconcileConfiguredEmbeddings(
                        checked.identity,
                        transition.transition().platformResult()
                                .processResult().document(),
                        checked.capture.position().sequence());
                refreshTimelineIndex(checked.identity);
                checked.capture.recordTransition(
                        checked.prepared.target().sessionId(), transition);
                transitionsByEvent.computeIfAbsent(
                                checked.prepared.event().eventBlueId(),
                                ignored -> new LinkedHashMap<>())
                        .put(checked.prepared.target().sessionId(), transition);
                evidence.recordIndexedTransition(
                        checked.document,
                        checked.capture.entry(),
                        transition);
                return committed;
            } catch (RuntimeException | Error failure) {
                if (progress != null
                        && progress.acquired()
                        && !progressCommitted) {
                    if (environment.committedDeliveryProbe()
                            .committedDelivery(
                                    checked.prepared.event(),
                                    checked.prepared.target().sessionId())
                            .isPresent()) {
                        topologyDeliveryLedger.commit(progress);
                    } else {
                        topologyDeliveryLedger.abandon(progress);
                    }
                }
                throw failure;
            } finally {
                checked.capture.addHostBookkeepingNanos(
                        Math.max(
                                0L,
                                elapsedNanos(bookkeepingStartedNanos)
                                        - delegateCommitNanos));
                operationTiming.endDelivery(
                        elapsedNanos(checked.deliveryStartedNanos));
            }
        }

        @Override
        public void discard(MyOsPreparedRootDelivery prepared) {
            MyOsPreparedRootDelivery checked = Objects.requireNonNull(
                    prepared, "prepared");
            operationTiming.attachDelivery(checked.timing);
            try {
                delegate.discard(checked.prepared);
            } finally {
                operationTiming.endDelivery(
                        elapsedNanos(checked.deliveryStartedNanos));
            }
        }
    }

    private static final class MyOsPreparedRootDelivery {
        private final InMemoryPreparedRootDelivery prepared;
        private final DeliveryCapture capture;
        private final MyOsDemoDocument document;
        private final MyOsDocumentIdentity identity;
        private final MyOsOperationTimingRecorder.DeliveryTiming timing;
        private final long deliveryStartedNanos;

        private MyOsPreparedRootDelivery(
                InMemoryPreparedRootDelivery prepared,
                DeliveryCapture capture,
                MyOsDemoDocument document,
                MyOsDocumentIdentity identity,
                MyOsOperationTimingRecorder.DeliveryTiming timing,
                long deliveryStartedNanos) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
            this.capture = Objects.requireNonNull(capture, "capture");
            this.document = Objects.requireNonNull(document, "document");
            this.identity = Objects.requireNonNull(identity, "identity");
            this.timing = timing;
            this.deliveryStartedNanos = deliveryStartedNanos;
        }
    }

    private static final class DeliveryCapture {
        private final MyOsDemoEntry entry;
        private final MyOsJournalPosition position;
        private final long routingStartedNanos;
        private final Map<DocumentSessionId, DemoTransition> transitions =
                new LinkedHashMap<>();
        private long firstDeliveryStartedNanos = -1L;
        private long hostBookkeepingNanos;

        private DeliveryCapture(
                MyOsDemoEntry entry,
                MyOsJournalPosition position,
                long routingStartedNanos) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.position = Objects.requireNonNull(position, "position");
            this.routingStartedNanos = routingStartedNanos;
        }

        private MyOsDemoEntry entry() { return entry; }
        private MyOsJournalPosition position() { return position; }

        private synchronized void markDeliveryStarted(long startedNanos) {
            if (firstDeliveryStartedNanos < 0L) {
                firstDeliveryStartedNanos = Math.max(
                        routingStartedNanos, startedNanos);
            }
        }

        private synchronized long firstDeliveryStartedNanos() {
            return firstDeliveryStartedNanos;
        }

        private synchronized void recordTransition(
                DocumentSessionId sessionId,
                DemoTransition transition) {
            if (transitions.putIfAbsent(
                    Objects.requireNonNull(sessionId, "sessionId"),
                    Objects.requireNonNull(transition, "transition")) != null) {
                throw new IllegalStateException(
                        "A Root transition was captured twice");
            }
        }

        private synchronized DemoTransition transition(
                DocumentSessionId sessionId) {
            return transitions.get(sessionId);
        }

        private synchronized Map<DocumentSessionId, DemoTransition>
                transitions() {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(transitions));
        }

        private synchronized void addHostBookkeepingNanos(long nanos) {
            hostBookkeepingNanos = Math.addExact(
                    hostBookkeepingNanos, Math.max(0L, nanos));
        }

        private synchronized long hostBookkeepingNanos() {
            return hostBookkeepingNanos;
        }
    }

    private String checkpointFingerprint(
            InMemoryCoordinationCheckpoint environmentCheckpoint,
            InMemoryCoordinationDispatchLedger fanoutCheckpoint,
            List<MyOsTimelineCheckpoint> timelineCheckpoints) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("environment:")
                .append(environmentCheckpoint.stateFingerprint())
                .append('\n')
                .append("fanout:")
                .append(fanoutCheckpoint.stateFingerprint())
                .append('\n')
                .append("sequences:")
                .append(admissionSequence).append(',')
                .append(timelineEntrySequence).append('\n')
                .append("initialization:")
                .append(initialization.evidence()).append('\n');

        List<String> initializationBlueIds = new ArrayList<>(
                ownedInitializationEvidence.keySet());
        initializationBlueIds.sort(
                ExternalOrderKey::compareTextCodePoints);
        for (String initializationBlueId : initializationBlueIds) {
            canonical.append("initializationExact:")
                    .append(initializationBlueId).append('\n');
        }

        for (MyOsPositionedTimelineJournal.Stored stored
                : journal.after(0L).values()) {
            canonical.append("journal:")
                    .append(stored.position().sequence()).append(',')
                    .append(stored.entry().blueId()).append(',')
                    .append(stored.binding()).append(',')
                    .append(stored.eventInventoryIdentity()).append(',')
                    .append(stored.entry().orderKey()).append('\n');
        }
        for (MyOsTimelineCheckpoint timeline : timelineCheckpoints) {
            canonical.append("timeline:")
                    .append(timeline.timelineId()).append(',')
                    .append(timeline.actor()).append(',')
                    .append(timeline.entryBlueIds()).append(',')
                    .append(timeline.previousEntryBlueId()).append(',')
                    .append(timeline.binding()).append('\n');
        }

        for (MyOsInitializationCoordinator.Receipt receipt
                : initialization.terminalReceipts()) {
            canonical.append("initializationReceipt:")
                    .append(receipt).append('\n');
        }

        List<String> documentKeys = new ArrayList<>(documents.keySet());
        documentKeys.sort(ExternalOrderKey::compareTextCodePoints);
        for (String key : documentKeys) {
            MyOsDemoDocument document = documents.get(key);
            MyOsDocumentIdentity identity = identitiesByDocumentKey.get(key);
            MyOsTopologyCatalog.DocumentState state = topology.state(identity);
            canonical.append("document:")
                    .append(key).append(',')
                    .append(document.sessionId().value()).append(',')
                    .append(document.initialBlueId()).append(',')
                    .append(canonicalIdentityInputBlueIds.get(key))
                    .append(',').append(state.currentRootBlueId())
                    .append(',').append(state.generation())
                    .append(',').append(state.admissionJournalHighWater())
                    .append(',').append(state.committedFrontier())
                    .append(',').append(initialization.initialized(identity))
                    .append(',').append(timelineIndex.timelines(identity))
                    .append(',').append(
                            managedEmbeddings.getOrDefault(
                                    identity, List.of()))
                    .append('\n');
            for (MyOsTopologyLink link : topology.childrenOf(identity)) {
                canonical.append("link:").append(link).append('\n');
            }
            for (MyOsTimelineCheckpoint timeline : timelineCheckpoints) {
                MyOsDeliveryLedger.StreamKey stream = deliveryStream(
                        identity, timeline.timelineId());
                canonical.append("delivery:")
                        .append(key).append(',')
                        .append(timeline.timelineId()).append(',')
                        .append(topologyDeliveryLedger
                                .admissionHighWater(stream))
                        .append(',').append(topologyDeliveryLedger
                                .committedHighWater(stream))
                        .append(',').append(topologyDeliveryLedger
                                .committedSequences(stream))
                        .append('\n');
            }
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(item & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        try {
            if (evidence.flush(
                    measuredWork(),
                    documentCount(),
                    timelineCount(),
                    journalEntryCount(),
                    storedEventInventoryCount())) {
                work.evidenceWritten();
            }
        } catch (RuntimeException problem) {
            failure = problem;
        }
        try {
            environment.close();
        } catch (RuntimeException problem) {
            if (failure == null) {
                failure = problem;
            } else {
                failure.addSuppressed(problem);
            }
        }
        try {
            operationTiming.flush();
        } catch (RuntimeException problem) {
            if (failure == null) {
                failure = problem;
            } else {
                failure.addSuppressed(problem);
            }
        }
        try {
            MyOsDemoKernel.releaseCurrentExactNodes(
                    exactPublicationOwner);
            publishedManagedInventories.clear();
            currentExactScopes.clear();
            ownedInitializationEvidence.clear();
        } catch (RuntimeException problem) {
            if (failure == null) {
                failure = problem;
            } else {
                failure.addSuppressed(problem);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static long elapsedNanos(long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos);
    }
}
