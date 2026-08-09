package blue.coordination.basic.engine;

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
public final class BasicCoordinationEngine
        implements AutoCloseable, EmbeddedGraphCoordinator.EngineAccess {
    public enum FailurePoint {
        BEFORE_FROZEN_PROCESS,
        AFTER_FROZEN_BEFORE_STAGE,
        AFTER_STAGING_CHILD_SESSION,
        AFTER_APPLYING_CHILD_REVISION,
        BEFORE_COMMIT_VALIDATION,
        AFTER_STATE_SWAP_BEFORE_RETURN
    }

    public static final class InjectedFailureException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InjectedFailureException(FailurePoint point) {
            super("Injected basicTest failure at " + point);
        }
    }

    private static final long BASE_TIMESTAMP_MICROS =
            1_800_000_000_000_000L;

    private final EngineMetrics metrics;
    private final WholeObjectStore objects;
    private final FrozenBlueRuntime runtime;
    private final WholeRequestEntryFactory entryFactory;
    private final InMemoryTimelineJournal journal;
    private final OperationRouteIndex routeIndex;
    private final EmbeddedOnlyLayoutBuilder layoutBuilder;
    private final BasicDocumentProcessor processor;
    private final InMemoryDocumentStore documents;
    private final EmbeddedGraphCoordinator embeddedGraph;
    private final InternalRevisionEventFactory internalEvents;
    private final Map<String, Timeline> timelines = new LinkedHashMap<>();
    private final Map<String, ProcessOutcome> deliveryReceipts =
            new LinkedHashMap<>();
    private final Set<String> revisionApplicationReceipts =
            new LinkedHashSet<>();
    private Consumer<FailurePoint> failureInjector = ignored -> { };
    private long logicalClockMicros = BASE_TIMESTAMP_MICROS;
    private boolean closed;

    private BasicCoordinationEngine() {
        metrics = new EngineMetrics();
        objects = new WholeObjectStore(metrics);
        runtime = FrozenBlueRuntime.create(objects);
        entryFactory = new WholeRequestEntryFactory(runtime, objects, metrics);
        journal = new InMemoryTimelineJournal(entryFactory, metrics);
        routeIndex = new OperationRouteIndex(metrics);
        layoutBuilder = new EmbeddedOnlyLayoutBuilder(
                runtime, objects, metrics);
        processor = new BasicDocumentProcessor(
                runtime, objects, layoutBuilder, routeIndex, metrics,
                this::inject);
        documents = new InMemoryDocumentStore();
        embeddedGraph = new EmbeddedGraphCoordinator(this);
        internalEvents = new InternalRevisionEventFactory(
                objects, journal, metrics);
    }

    public static BasicCoordinationEngine create() {
        return new BasicCoordinationEngine();
    }

    public synchronized Timeline timeline(
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

    public synchronized DocumentSession start(
            String documentId,
            String authoredYaml) {
        return start(DocumentId.of(documentId), authoredYaml);
    }

    public synchronized DocumentSession start(
            DocumentId documentId,
            String authoredYaml) {
        ensureOpen();
        if (documents.find(documentId).isPresent()) {
            throw new IllegalArgumentException(
                    "Duplicate document session " + documentId);
        }
        DocumentSession session = processor.admit(
                documentId,
                authoredYaml,
                currentAdmissionFrontier(documentId));
        documents.insert(session);
        metrics.increment("sessionsCreated");
        if (!session.layout().directOccurrences().isEmpty()) {
            throw new IllegalStateException(
                    "Top-level start with pre-existing Process Embedded children "
                            + "must use an explicit admission/catch-up operation");
        }
        return session;
    }


    /** Registers one exact test type in the same whole-object provider. */
    public synchronized ExactNodeValue registerType(String sourceYaml) {
        ensureOpen();
        return runtime.exactSource(sourceYaml, objects, "test-type");
    }

    public synchronized ExactNodeValue exactRequest(String requestYaml) {
        ensureOpen();
        return entryFactory.parseExactRequest(requestYaml);
    }

    /**
     * Retains one autonomous document whole and returns one whole request that
     * points to it. Attachment never serializes or copies the document through
     * the request/Compute boundary.
     */
    public synchronized ExactNodeValue embeddedDocumentRequest(
            String exactDocumentYaml) {
        return referencedValueRequest("document", exactDocumentYaml);
    }

    /** Returns one whole request containing one exact whole-value reference. */
    public synchronized ExactNodeValue referencedValueRequest(
            String field,
            String exactValueYaml) {
        ensureOpen();
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        ExactNodeValue value = runtime.exactSource(
                exactValueYaml,
                objects,
                "referenced-request-value");
        return objects.put(
                new Node().properties(
                        field, value.referenceNode()),
                "timeline-request");
    }

    public synchronized ExactTimelineEntry append(
            Timeline timeline,
            BasicOperation operation) {
        return appendAt(timeline, operation, nextTimestamp());
    }

    public synchronized ExactTimelineEntry appendAt(
            Timeline timeline,
            BasicOperation operation,
            long timestampMicros) {
        ensureOpen();
        logicalClockMicros = Math.max(logicalClockMicros, timestampMicros);
        return metrics.timed("append.total",
                () -> journal.append(timeline, operation, timestampMicros));
    }

    public synchronized DispatchResult appendAndDispatch(
            Timeline timeline,
            BasicOperation operation) {
        return dispatch(append(timeline, operation));
    }

    /** Measures the already-compiled exact route index without execution. */
    public synchronized int routeTargetCount(ExactTimelineEntry entry) {
        ensureOpen();
        return metrics.timed("process.routeLookup",
                () -> routeIndex.route(Objects.requireNonNull(entry, "entry"))
                        .size());
    }

    public synchronized DispatchResult dispatch(ExactTimelineEntry entry) {
        ensureOpen();
        long started = System.nanoTime();
        List<DocumentId> routed = metrics.timed(
                "process.routeLookup", () -> routeIndex.route(entry));
        List<ProcessOutcome> outcomes = new ArrayList<>();
        List<DocumentSession> selected = new ArrayList<>();
        for (DocumentId id : routed.stream().distinct().sorted().toList()) {
            ProcessOutcome receipt = deliveryReceipts.get(
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
            DispatchResult result = new DispatchResult(
                    entry, outcomes, System.nanoTime() - started);
            metrics.addNanos("process.total", result.elapsedNanos());
            return result;
        }

        EngineState before = snapshotState();
        boolean published = false;
        try {
            List<BasicDocumentProcessor.Prepared> prepared = new ArrayList<>();
            for (DocumentSession session : selected) {
                prepared.add(processor.prepare(session, entry));
            }
            List<ProcessOutcome> fresh = new ArrayList<>();
            for (BasicDocumentProcessor.Prepared transition : prepared) {
                ProcessOutcome outcome = processor.commit(transition);
                fresh.add(outcome);
                outcomes.add(outcome);
            }
            for (ProcessOutcome outcome : fresh) {
                embeddedGraph.afterCommit(outcome, entry);
            }
            inject(FailurePoint.BEFORE_COMMIT_VALIDATION);
            metrics.add("revisionApplicationReceiptsCommitted",
                    revisionApplicationReceipts.size()
                            - before.revisionApplicationReceipts().size());
            for (ProcessOutcome outcome : fresh) {
                deliveryReceipts.put(deliveryReceiptKey(
                        entry, outcome.session().documentId()), outcome);
                metrics.increment("deliveryReceiptsCommitted");
            }
            published = true;
            inject(FailurePoint.AFTER_STATE_SWAP_BEFORE_RETURN);
            metrics.increment("dispatch.entries");
            metrics.add("dispatch.roots", fresh.size());
            DispatchResult result = new DispatchResult(
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

    public synchronized void failOnceAt(FailurePoint point) {
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

    public synchronized void clearFailureInjection() {
        failureInjector = ignored -> { };
    }

    public synchronized DocumentSession session(String documentId) {
        ensureOpen();
        return documents.require(DocumentId.of(documentId));
    }

    public synchronized Node currentRoot(String documentId) {
        ensureOpen();
        return session(documentId).layout().reconstructRoot();
    }

    public synchronized Node value(String documentId, String path) {
        Node selected = NodePathEditor.getOrNull(
                currentRoot(documentId), path);
        if (selected == null) {
            throw new IllegalArgumentException(
                    "No value at " + documentId + path);
        }
        return selected.clone();
    }

    public synchronized List<DocumentRevision> history(String documentId) {
        return session(documentId).revisions();
    }

    public synchronized List<CatchUpPlan> catchUpPlans() {
        return embeddedGraph.plans();
    }

    /** External source Timelines reachable through this Root and its links. */
    public synchronized Set<String> effectiveTimelineIds(String documentId) {
        ensureOpen();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectTimelineIds(
                documents.require(DocumentId.of(documentId)),
                result,
                new LinkedHashSet<>());
        return Collections.unmodifiableSet(result);
    }

    /** Direct Process Embedded path -> autonomous child DocumentId. */
    public synchronized Map<String, String> embeddedDocuments(
            String documentId) {
        ensureOpen();
        Map<String, String> result = new LinkedHashMap<>();
        documents.require(DocumentId.of(documentId)).linksByPath()
                .forEach((path, link) -> result.put(
                        path, link.childDocumentId().value()));
        return Collections.unmodifiableMap(result);
    }

    public synchronized EngineMetrics.MetricsSnapshot metricsSnapshot() {
        return metrics.snapshot();
    }

    /** Language's bounded high-throughput cache evidence for diagnostics. */
    public synchronized BlueCacheStats languageCacheStats() {
        ensureOpen();
        return runtime.cacheStats();
    }

    public synchronized int journalSize() {
        return journal.size();
    }

    public synchronized int wholeObjectCount() {
        return objects.size();
    }

    @Override
    public synchronized InMemoryDocumentStore documents() {
        return documents;
    }

    @Override
    public synchronized DocumentSession admitEmbedded(
            EmbeddedOccurrence occurrence,
            CatchUpCause cause,
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
                BasicDocumentProcessor.fullHistoryFrontier(
                        occurrence.childDocumentId()),
                cause);
        documents.insert(child);
        metrics.increment("embedding.childSessionsCreated");
        metrics.increment("sessionsCreated");
        inject(FailurePoint.AFTER_STAGING_CHILD_SESSION);
        embeddedGraph.synchronizeAdmission(
                child, cause, cutoff, cutoffOrderKey);
        return child;
    }

    @Override
    public synchronized List<ExactTimelineEntry> journalEntriesThrough(
            DocumentSession child,
            EnvironmentFrontier cutoff) {
        List<ExactTimelineEntry> result = new ArrayList<>();
        for (String timelineId : child.layout().routingSurface()
                .externalTimelineIds()) {
            journal.entriesThrough(timelineId, cutoff).stream()
                    .filter(entry -> !entry.processorManaged())
                    .forEach(result::add);
        }
        result.sort(Comparator.comparingLong(
                ExactTimelineEntry::globalSequence));
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized boolean routesTo(
            DocumentId documentId,
            ExactTimelineEntry entry) {
        return routeIndex.routesTo(documentId, entry);
    }

    @Override
    public synchronized ProcessOutcome processTarget(
            DocumentSession session,
            ExactTimelineEntry entry) {
        BasicDocumentProcessor.Prepared prepared =
                processor.prepare(session, entry);
        ProcessOutcome outcome = processor.commit(prepared);
        embeddedGraph.afterCommit(outcome, entry);
        return outcome;
    }

    @Override
    public synchronized ExactTimelineEntry appendInternalRevision(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision childRevision) {
        return internalEvents.append(
                parent,
                link,
                childRevision,
                nextTimestamp());
    }

    @Override
    public synchronized ProcessOutcome materializeEmbeddedRevision(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision childRevision) {
        return processor.materializeEmbeddedRevision(parent, link, childRevision);
    }

    @Override
    public synchronized boolean hasRevisionApplicationReceipt(String key) {
        return revisionApplicationReceipts.contains(key);
    }

    @Override
    public synchronized void commitRevisionApplicationReceipt(String key) {
        revisionApplicationReceipts.add(Objects.requireNonNull(key, "key"));
    }

    @Override
    public synchronized void inject(FailurePoint point) {
        failureInjector.accept(Objects.requireNonNull(point, "point"));
    }

    @Override
    public EngineMetrics metrics() {
        return metrics;
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

    private ExternalOrderKey currentAdmissionFrontier(DocumentId documentId) {
        List<ExactTimelineEntry> entries = journal.allEntries();
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
            throw new IllegalStateException("BasicCoordinationEngine is closed");
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
            ExactTimelineEntry entry,
            DocumentId documentId) {
        return entry.blueId() + "|" + documentId.value();
    }

    private record EngineState(
            Map<DocumentId, DocumentSession> documents,
            EmbeddedGraphCoordinator.State embeddedGraph,
            Map<String, ProcessOutcome> deliveryReceipts,
            Set<String> revisionApplicationReceipts,
            InMemoryTimelineJournal.Mark journalMark,
            long logicalClockMicros) {
    }
}
