package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DispatchResult;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.api.BlueCacheStats;
import blue.language.model.Node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compact acceptance DSL over the published production API. It contains no
 * Coordination processing implementation; only String-id conveniences and
 * immutable diagnostic projections used by the migrated tests.
 */
final class TestEngine implements AutoCloseable {
    private final CoordinationEngine engine;
    private final CoordinationTestControl control;

    private TestEngine(CoordinationEngine engine) {
        this.engine = engine;
        this.control = CoordinationTestControl.attach(engine);
    }

    static TestEngine create() {
        return new TestEngine(CoordinationEngine.inMemory());
    }

    Timeline timeline(String timelineId, String actorId) {
        return engine.registerTimeline(timelineId, actorId);
    }

    Timeline registerTimeline(String timelineId, String actorId) {
        return timeline(timelineId, actorId);
    }

    DocumentView start(String documentId, String sourceYaml) {
        try {
            return new DocumentView(engine.startDocument(
                    DocumentId.of(documentId), sourceYaml));
        } catch (RuntimeException failure) {
            throw original(failure);
        }
    }

    ExactValue registerType(String sourceYaml) {
        return engine.exactValue(sourceYaml);
    }

    ExactValue exactRequest(String sourceYaml) {
        return engine.exactValue(sourceYaml);
    }

    ExactValue embeddedDocumentRequest(String exactDocumentYaml) {
        return engine.referenceRequest(
                "document", engine.exactValue(exactDocumentYaml));
    }

    ExactValue referencedValueRequest(String field, String exactValueYaml) {
        return engine.referenceRequest(
                field, engine.exactValue(exactValueYaml));
    }

    TimelineEntry append(Timeline timeline, Operation operation) {
        return engine.append(timeline, operation);
    }

    TimelineEntry appendAt(
            Timeline timeline,
            Operation operation,
            long timestampMicros) {
        return engine.appendAt(timeline, operation, timestampMicros);
    }

    DispatchResult appendAndDispatch(Timeline timeline, Operation operation) {
        return dispatch(append(timeline, operation));
    }

    DispatchResult dispatch(TimelineEntry entry) {
        try {
            return engine.dispatch(entry);
        } catch (RuntimeException failure) {
            if (control.isInjectedFailure(failure)) {
                throw new InjectedFailureException();
            }
            throw original(failure);
        }
    }

    int routeTargetCount(TimelineEntry entry) {
        return engine.routeTargetCount(entry);
    }

    DocumentView session(String documentId) {
        return new DocumentView(engine.document(DocumentId.of(documentId)));
    }

    Node value(String documentId, String pointer) {
        return session(documentId).snapshot().valueAt(pointer).copyNode();
    }

    List<DocumentRevision> history(String documentId) {
        return engine.history(DocumentId.of(documentId));
    }

    Set<String> effectiveTimelineIds(String documentId) {
        return engine.effectiveTimelineIds(DocumentId.of(documentId));
    }

    Map<String, String> embeddedDocuments(String documentId) {
        Map<String, String> result = new LinkedHashMap<>();
        session(documentId).snapshot().embeddedChildren().forEach(
                (path, id) -> result.put(path, id.value()));
        return Collections.unmodifiableMap(result);
    }

    EngineMetrics.MetricsSnapshot metricsSnapshot() {
        CoordinationMetrics metrics = engine.metrics();
        return new EngineMetrics.MetricsSnapshot(
                metrics.counters(), metrics.phaseNanos());
    }

    int journalSize() {
        return engine.metrics().journalEntryCount();
    }

    int wholeObjectCount() {
        return engine.metrics().wholeObjectCount();
    }

    int documentCount() {
        return engine.metrics().documentCount();
    }

    int routeRowCount() {
        return engine.metrics().routeRowCount();
    }

    long logicalClockMicros() {
        return engine.metrics().logicalClockMicros();
    }

    BlueCacheStats languageCacheStats() {
        return control.languageCacheStats();
    }

    List<CatchUpPlan> catchUpPlans() {
        return control.catchUpEvidence().stream()
                .map(evidence -> new CatchUpPlan(
                        new CatchUpPlan.Link(
                                DocumentId.of(evidence.parentDocumentId()),
                                DocumentId.of(evidence.childDocumentId()),
                                evidence.occurrencePath(),
                                evidence.appliedChildEpoch()),
                        CatchUpPlan.Status.valueOf(evidence.status())))
                .toList();
    }

    void failOnceAt(FailurePoint point) {
        control.failOnceAt(CoordinationTestControl.FailurePoint.valueOf(
                point.name()));
    }

    void clearFailureInjection() {
        control.clearFailureInjection();
    }

    @Override
    public void close() {
        engine.close();
    }

    private static RuntimeException original(RuntimeException failure) {
        if (failure instanceof CoordinationException
                && failure.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return failure;
    }

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
    }

    record DocumentView(DocumentSnapshot snapshot) {
        long epoch() {
            return snapshot.epoch();
        }

        SessionStatus status() {
            return snapshot.status();
        }

        String authoredInitialBlueId() {
            return snapshot.authoredInitialBlueId();
        }

        EmbeddedOnlyLayout layout() {
            return new EmbeddedOnlyLayout(snapshot);
        }
    }
}
