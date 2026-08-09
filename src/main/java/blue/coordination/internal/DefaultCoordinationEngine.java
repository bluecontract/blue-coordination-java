package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.Timeline;

import blue.coordination.api.SessionStatus;

import blue.coordination.api.Operation;

import blue.coordination.api.ExactValue;

import blue.coordination.api.EnvironmentFrontier;

import blue.coordination.api.DocumentRevision;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DispatchResult;
import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentSnapshot;

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
 * Clean, in-memory Coordination vertical slice for the basic acceptance suite.
 *
 * <p>Its rules are intentionally small:</p>
 * <ul>
 *   <li>one whole request and one whole Timeline Entry;</li>
 *   <li>operation-aware autonomous-Root routing;</li>
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
    private final EmbeddedGraphCoordinator embeddedGraph;
    private final InternalRevisionEventFactory internalEvents;
    private final Map<String, Timeline> timelines = new LinkedHashMap<>();
    private final Map<String, InternalProcessOutcome> deliveryReceipts =
            new LinkedHashMap<>();
    private final Set<String> revisionApplicationReceipts =
            new LinkedHashSet<>();
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private long logicalClockMicros = BASE_TIMESTAMP_MICROS;
    private boolean closed;

    private DefaultCoordinationEngine() {
        metrics = new EngineMetrics();
        objects = new WholeObjectStore(metrics);
        runtime = BlueRuntime.create(objects);
        entryFactory = new WholeRequestEntryFactory(runtime, objects, metrics);
        journal = new InMemoryTimelineJournal(entryFactory, metrics);
        routeIndex = new OperationRouteIndex(metrics);
        layoutBuilder = new EmbeddedOnlyLayoutBuilder(
                runtime, objects, metrics);
        processor = new DocumentTransitionProcessor(
                runtime, objects, layoutBuilder, metrics,
                this::inject);
        documents = new InMemoryDocumentStore();
        embeddedGraph = new EmbeddedGraphCoordinator(this);
        internalEvents = new InternalRevisionEventFactory(
                objects, journal, metrics);
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
        ensureOpen();
        if (documents.find(documentId).isPresent()) {
            throw new IllegalArgumentException(
                    "Duplicate document session " + documentId);
        }
        WholeObjectStore.Mark objectMark = objects.mark();
        try {
            DocumentSession candidate = processor.admit(
                    documentId,
                    authoredYaml,
                    currentAdmissionFrontier(documentId));
            validateTopLevelAdmission(candidate);

            documents.insert(candidate);
            routeIndex.replace(
                    candidate.documentId(),
                    candidate.layout().routingSurface());
            metrics.increment("sessionsCreated");
            return candidate;
        } catch (RuntimeException failure) {
            objects.rollbackTo(objectMark);
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
     * Retains one autonomous document whole and returns one whole request that
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
        long candidateTimestamp = Math.addExact(logicalClockMicros, 1L);
        TimelineEntry entry = metrics.timed(
                "append.total",
                () -> journal.append(
                        timeline,
                        operation,
                        candidateTimestamp));
        logicalClockMicros = candidateTimestamp;
        return entry;
    }

    @Override
    public synchronized TimelineEntry appendAt(
            Timeline timeline,
            Operation operation,
            long timestampMicros) {
        ensureOpen();
        long nextClock = Math.max(logicalClockMicros, timestampMicros);
        TimelineEntry entry = metrics.timed("append.total",
                () -> journal.append(timeline, operation, timestampMicros));
        logicalClockMicros = nextClock;
        return entry;
    }

    @Override
    public synchronized DispatchResult appendAndDispatch(
            Timeline timeline,
            Operation operation) {
        return dispatch(append(timeline, operation));
    }

    /** Measures the already-compiled exact route index without execution. */
    @Override
    public synchronized int routeTargetCount(TimelineEntry entry) {
        ensureOpen();
        return metrics.timed("process.routeLookup",
                () -> routeIndex.route(Objects.requireNonNull(entry, "entry"))
                        .size());
    }

    @Override
    public synchronized DispatchResult dispatch(TimelineEntry entry) {
        try {
            return publicResult(dispatchInternal(entry));
        } catch (RuntimeException failure) {
            throw translateDispatchFailure(failure);
        }
    }

    private InternalDispatchResult dispatchInternal(TimelineEntry entry) {
        ensureOpen();
        long started = System.nanoTime();
        List<DocumentId> routed = metrics.timed(
                "process.routeLookup", () -> routeIndex.route(entry));
        List<InternalProcessOutcome> outcomes = new ArrayList<>();
        List<DocumentSession> selected = new ArrayList<>();
        for (DocumentId id : routed.stream().distinct().sorted().toList()) {
            InternalProcessOutcome receipt = deliveryReceipts.get(
                    deliveryReceiptKey(entry, id));
            if (receipt != null) {
                outcomes.add(receipt);
                metrics.increment("process.duplicateEntriesSkipped");
            } else {
                selected.add(documents.require(id));
            }
        }
        for (DocumentSession session : selected) {
            if (session.status() != SessionStatus.READY) {
                throw new IllegalStateException(
                        "Root " + session.documentId()
                                + " cannot accept live work while "
                                + session.status());
            }
        }

        if (selected.isEmpty()) {
            InternalDispatchResult result = new InternalDispatchResult(
                    entry, outcomes, System.nanoTime() - started);
            metrics.addNanos("process.total", result.elapsedNanos());
            return result;
        }

        EngineState before = snapshotState();
        boolean published = false;
        try {
            List<DocumentTransitionProcessor.Prepared> prepared = new ArrayList<>();
            for (DocumentSession session : selected) {
                prepared.add(processor.prepare(session, entry));
            }
            List<InternalProcessOutcome> fresh = new ArrayList<>();
            for (DocumentTransitionProcessor.Prepared transition : prepared) {
                InternalProcessOutcome outcome = processor.commit(transition);
                fresh.add(outcome);
                outcomes.add(outcome);
            }
            for (InternalProcessOutcome outcome : fresh) {
                embeddedGraph.afterCommit(outcome, entry);
            }
            inject(FailurePoint.BEFORE_COMMIT_VALIDATION);
            metrics.add("revisionApplicationReceiptsCommitted",
                    revisionApplicationReceipts.size()
                            - before.revisionApplicationReceipts().size());
            for (InternalProcessOutcome outcome : fresh) {
                deliveryReceipts.put(deliveryReceiptKey(
                        entry, outcome.session().documentId()), outcome);
                metrics.increment("deliveryReceiptsCommitted");
            }
            published = true;
            inject(FailurePoint.AFTER_STATE_SWAP_BEFORE_RETURN);
            metrics.increment("dispatch.entries");
            metrics.add("dispatch.roots", fresh.size());
            InternalDispatchResult result = new InternalDispatchResult(
                    entry, outcomes, System.nanoTime() - started);
            metrics.addNanos("process.total", result.elapsedNanos());
            return result;
        } catch (RuntimeException failure) {
            if (!published) {
                restoreState(before);
                metrics.increment("transactionRetries");
            }
            metrics.addNanos("process.total", System.nanoTime() - started);
            throw failure;
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

    synchronized List<CatchUpPlan> catchUpPlans() {
        return embeddedGraph.plans();
    }

    /** External source Timelines reachable through this Root and its links. */
    synchronized Set<String> effectiveTimelineIds(String documentId) {
        ensureOpen();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectTimelineIds(
                documents.require(DocumentId.of(documentId)),
                result,
                new LinkedHashSet<>());
        return Collections.unmodifiableSet(result);
    }

    /** Direct Process Embedded path -> autonomous child DocumentId. */
    synchronized Map<String, String> embeddedDocuments(
            String documentId) {
        ensureOpen();
        Map<String, String> result = new LinkedHashMap<>();
        documents.require(DocumentId.of(documentId)).linksByPath()
                .forEach((path, link) -> result.put(
                        path, link.childDocumentId().value()));
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

    synchronized DocumentSession admitEmbedded(
            EmbeddedOccurrence occurrence,
            TimelineEntry.CatchUpCause cause,
            EnvironmentFrontier cutoff,
            ExternalOrderKey cutoffOrderKey) {
        DocumentSession existing = documents.find(
                occurrence.childDocumentId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        DocumentSession child = processor.admitExact(
                occurrence.childDocumentId(),
                occurrence.suppliedState(),
                DocumentTransitionProcessor.fullHistoryFrontier(
                        occurrence.childDocumentId()),
                cause);
        documents.insert(child);
        routeIndex.replace(
                child.documentId(), child.layout().routingSurface());
        metrics.increment("embedding.childSessionsCreated");
        metrics.increment("sessionsCreated");
        inject(FailurePoint.AFTER_STAGING_CHILD_SESSION);
        embeddedGraph.synchronizeAdmission(
                child, cause, cutoff, cutoffOrderKey);
        return child;
    }

    synchronized List<TimelineEntry> journalEntriesThrough(
            DocumentSession child,
            EnvironmentFrontier cutoff) {
        List<TimelineEntry> result = new ArrayList<>();
        for (String timelineId : child.layout().routingSurface()
                .externalTimelineIds()) {
            journal.entriesThrough(timelineId, cutoff).stream()
                    .filter(entry -> !entry.processorManaged())
                    .forEach(result::add);
        }
        result.sort(Comparator.comparingLong(
                TimelineEntry::globalSequence));
        return Collections.unmodifiableList(result);
    }

    synchronized boolean routesTo(
            DocumentId documentId,
            TimelineEntry entry) {
        return routeIndex.routesTo(documentId, entry);
    }

    synchronized InternalProcessOutcome processTarget(
            DocumentSession session,
            TimelineEntry entry) {
        DocumentTransitionProcessor.Prepared prepared =
                processor.prepare(session, entry);
        InternalProcessOutcome outcome = processor.commit(prepared);
        embeddedGraph.afterCommit(outcome, entry);
        return outcome;
    }

    synchronized TimelineEntry appendInternalRevision(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision childRevision) {
        return internalEvents.append(
                parent,
                link,
                childRevision,
                nextTimestamp());
    }

    synchronized InternalProcessOutcome materializeEmbeddedRevision(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision childRevision) {
        return processor.materializeEmbeddedRevision(parent, link, childRevision);
    }

    synchronized boolean hasRevisionApplicationReceipt(String key) {
        return revisionApplicationReceipts.contains(key);
    }

    synchronized void commitRevisionApplicationReceipt(String key) {
        revisionApplicationReceipts.add(Objects.requireNonNull(key, "key"));
    }

    synchronized void inject(FailurePoint point) {
        failureInjector.accept(Objects.requireNonNull(point, "point"));
    }

    EngineMetrics engineMetrics() {
        return metrics;
    }

    @Override
    public synchronized DocumentSnapshot document(DocumentId documentId) {
        ensureOpen();
        try {
            return snapshot(documents.require(documentId));
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
        document(documentId);
        return documents.require(documentId).revisions();
    }

    @Override
    public synchronized Set<String> effectiveTimelineIds(
            DocumentId documentId) {
        document(documentId);
        return effectiveTimelineIds(documentId.value());
    }

    @Override
    public synchronized CoordinationMetrics metrics() {
        EngineMetrics.MetricsSnapshot current = metrics.snapshot();
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
        session.linksByPath().forEach((path, link) -> children.put(
                path, link.childDocumentId()));
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
                                + definition.timelineId() + "|"
                                + definition.actorId())
                        .toList(),
                layout.physicalObjectCount(),
                layout.processingFrozen().blueId());
    }

    private static DispatchResult publicResult(InternalDispatchResult result) {
        List<DocumentDispatchOutcome> outcomes = result.outcomes().stream()
                .map(outcome -> new DocumentDispatchOutcome(
                        outcome.session().documentId(),
                        outcome.revision(),
                        outcome.totalNanos()))
                .toList();
        return new DispatchResult(
                result.entry(), outcomes, result.elapsedNanos());
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
                : message.contains("autonomous child")
                ? CoordinationErrorCode.AUTONOMOUS_OWNERSHIP_VIOLATION
                : message.contains("subscription membership change")
                ? CoordinationErrorCode.UNSUPPORTED_DYNAMIC_MEMBERSHIP
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

    private void collectTimelineIds(
            DocumentSession session,
            Set<String> result,
            Set<DocumentId> visited) {
        if (!visited.add(session.documentId())) {
            return;
        }
        result.addAll(session.layout().routingSurface().externalTimelineIds());
        for (EmbeddedLink link : session.linksByPath().values()) {
            collectTimelineIds(
                    documents.require(link.childDocumentId()),
                    result,
                    visited);
        }
    }

    private long nextTimestamp() {
        logicalClockMicros = Math.addExact(logicalClockMicros, 1L);
        return logicalClockMicros;
    }

    private static void validateTopLevelAdmission(DocumentSession candidate) {
        if (!candidate.layout().directOccurrences().isEmpty()) {
            throw new IllegalStateException(
                    "Top-level start with pre-existing Process Embedded children "
                            + "must use an explicit admission/catch-up operation");
        }
    }

    private ExternalOrderKey currentAdmissionFrontier(DocumentId documentId) {
        List<TimelineEntry> entries = journal.allEntries();
        if (entries.isEmpty()) {
            return ExternalOrderKey.of(List.of(
                    BigInteger.ZERO,
                    "admission",
                    documentId.value()));
        }
        return entries.get(entries.size() - 1).sourceOrderKey();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("DefaultCoordinationEngine is closed");
        }
    }

    private EngineState snapshotState() {
        return new EngineState(
                documents.snapshot(),
                embeddedGraph.snapshot(),
                new LinkedHashMap<>(deliveryReceipts),
                new LinkedHashSet<>(revisionApplicationReceipts),
                journal.mark(),
                logicalClockMicros);
    }

    private void restoreState(EngineState state) {
        documents.restore(state.documents());
        deliveryReceipts.clear();
        deliveryReceipts.putAll(state.deliveryReceipts());
        revisionApplicationReceipts.clear();
        revisionApplicationReceipts.addAll(
                state.revisionApplicationReceipts());
        embeddedGraph.restore(state.embeddedGraph());
        journal.rollbackTo(state.journalMark());
        logicalClockMicros = state.logicalClockMicros();
        routeIndex.clear();
        for (DocumentSession session : documents.sessions()) {
            routeIndex.replace(
                    session.documentId(), session.layout().routingSurface());
        }
    }

    private static String deliveryReceiptKey(
            TimelineEntry entry,
            DocumentId documentId) {
        return entry.blueId() + "|" + documentId.value();
    }

    private record EngineState(
            Map<DocumentId, DocumentSession> documents,
            EmbeddedGraphCoordinator.State embeddedGraph,
            Map<String, InternalProcessOutcome> deliveryReceipts,
            Set<String> revisionApplicationReceipts,
            InMemoryTimelineJournal.Mark journalMark,
            long logicalClockMicros) {
    }
}
