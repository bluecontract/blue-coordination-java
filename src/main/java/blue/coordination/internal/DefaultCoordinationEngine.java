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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * In-memory Process Embedded temporal-profile engine.
 *
 * <p>Its execution boundary is intentionally sequential:</p>
 * <ul>
 *   <li>one whole request and one whole Timeline Entry;</li>
 *   <li>operation-aware managed-document routing;</li>
 *   <li>exactly one frozen Contracts call per selected Root;</li>
 *   <li>only Process Embedded documents are cut;</li>
 *   <li>embedded sessions process source history once;</li>
 *   <li>parents consume exact child revisions under a catch-up barrier.</li>
 * </ul>
 */
public final class DefaultCoordinationEngine
        implements CoordinationEngine {
    enum FailurePoint {
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
    private SequentialDrainCoordinator drainCoordinator;
    private final Map<String, Timeline> timelines = new LinkedHashMap<>();
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private long logicalClockMicros = BASE_TIMESTAMP_MICROS;
    private long applicationClockMicros = BASE_TIMESTAMP_MICROS;
    private boolean closed;

    private DefaultCoordinationEngine() {
        metrics = new EngineMetrics();
        objects = new WholeObjectStore(metrics);
        runtime = BlueRuntime.create(objects, metrics);
        entryFactory = new WholeRequestEntryFactory(runtime, objects, metrics);
        journal = new InMemoryTimelineJournal(entryFactory, metrics);
        documents = new InMemoryDocumentStore();
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
    }

    public static DefaultCoordinationEngine create() {
        return new DefaultCoordinationEngine();
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
                    admissionFrontier);

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
            objects.commit(objectMark);
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
        try {
            return snapshot(start(documentId, authoredYaml));
        } catch (RuntimeException failure) {
            throw translateStartFailure(documentId, failure);
        }
    }

    @Override
    public synchronized void configureEmbeddedAdmission(
            DocumentId documentId,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough) {
        ensureOpen();
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


    /** Registers one exact test type in the same whole-object provider. */
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

    /**
     * Retains one managed document whole and returns one whole request that
     * points to it. Attachment never serializes or copies the document through
     * the request/Compute boundary.
     */
    synchronized ExactValue embeddedDocumentRequest(
            String exactDocumentYaml) {
        return referencedValueRequest("document", exactDocumentYaml);
    }

    /** Returns one whole request containing one exact whole-value reference. */
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

    /** Measures the already-compiled exact route index without execution. */
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

    /**
     * Reconstructs the coordinator and route index from the retained in-memory
     * document, journal, graph, cursor, barrier, and entry-frame stores.
     * Runtime caches and exact immutable objects remain reusable.
     */
    synchronized void restartFromStores() {
        ensureOpen();
        clearFailureInjection();
        routeIndex.clear();
        documents.sessions().stream()
                .sorted(Comparator.comparing(DocumentSession::documentId))
                .forEach(session -> routeIndex.replace(
                        session.documentId(),
                        session.layout().routingSurface(),
                        session.activeSubscriptions()));
        drainCoordinator = drainCoordinator.restartFromStores(this::inject);
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

    /** External source Timelines reachable through this Root and its links. */
    synchronized Set<String> effectiveTimelineIds(String documentId) {
        ensureOpen();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(drainCoordinator.effectiveTimelineIds(
                DocumentId.of(documentId)));
        return Collections.unmodifiableSet(result);
    }

    /** Direct Process Embedded path to managed child DocumentId. */
    synchronized Map<String, String> embeddedDocuments(
            String documentId) {
        ensureOpen();
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

    /** Language's bounded high-throughput cache evidence for diagnostics. */
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

    /** Package migration diagnostic; represented by metrics after promotion. */
    synchronized int documentCount() {
        return documents.size();
    }

    /** Package migration diagnostic; represented by metrics after promotion. */
    synchronized int routeRowCount() {
        return routeIndex.rowCount();
    }

    /** Package migration diagnostic for append publication atomicity. */
    synchronized long logicalClockMicros() {
        return logicalClockMicros;
    }

    synchronized InMemoryDocumentStore documents() {
        return documents;
    }

    synchronized void inject(FailurePoint point) {
        failureInjector.accept(Objects.requireNonNull(point, "point"));
    }

    EngineMetrics engineMetrics() {
        return metrics;
    }

    @Override
    public synchronized DocumentSnapshot document(DocumentId documentId) {
        DocumentSession session = requireDocument(documentId);
        String readinessFailure = drainCoordinator.applicationReadinessFailure(
                session);
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
        Map<String, DocumentId> children = new LinkedHashMap<>();
        drainCoordinator.bindingsForParent(session.documentId())
                .forEach(binding -> children.put(
                        binding.absolutePath(),
                        binding.childDocumentId()));
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

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        runtime.close();
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
        ExternalOrderKey processed = drainCoordinator.processedThrough();
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
