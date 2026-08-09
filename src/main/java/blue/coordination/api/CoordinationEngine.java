package blue.coordination.api;

import blue.coordination.internal.DefaultCoordinationEngine;

import java.util.List;
import java.util.Set;

/**
 * Deterministic single-process Coordination environment.
 *
 * <p>The first production implementation is deliberately in-memory. Closing
 * the engine releases its borrowed Language, Contracts, and BEX runtimes.</p>
 */
public interface CoordinationEngine extends AutoCloseable {
    /** Creates the supported single-process in-memory engine. */
    static CoordinationEngine inMemory() {
        return builder().inMemory().build();
    }

    /** Starts configuration of a Coordination engine. */
    static Builder builder() {
        return new Builder();
    }

    /** Registers one authenticated append-only Timeline. */
    Timeline registerTimeline(String timelineId, String actorId);

    /** Admits one authored document atomically and returns its initial state. */
    DocumentSnapshot startDocument(DocumentId documentId, String authoredYaml);

    /** Resolves and retains one complete exact YAML value. */
    ExactValue exactValue(String sourceYaml);

    /** Builds a structurally shared request that refers to an exact value. */
    ExactValue referenceRequest(String field, ExactValue exactValue);

    /** Atomically appends one operation without dispatching it. */
    TimelineEntry append(Timeline timeline, Operation operation);

    /** Appends one operation at an explicit positive logical timestamp. */
    TimelineEntry appendAt(
            Timeline timeline,
            Operation operation,
            long timestampMicros);

    /** Routes and publishes an already appended exact Timeline Entry. */
    DispatchResult dispatch(TimelineEntry entry);

    /** Appends and dispatches one operation in a single engine call. */
    DispatchResult appendAndDispatch(Timeline timeline, Operation operation);

    /** Returns the number of autonomous Roots selected by the route index. */
    int routeTargetCount(TimelineEntry entry);

    /** Reads the immutable current state of one managed document. */
    DocumentSnapshot document(DocumentId documentId);

    /** Reads the immutable ordered revision stream of one document. */
    List<DocumentRevision> history(DocumentId documentId);

    /** Returns source Timeline IDs reachable by a document and its children. */
    Set<String> effectiveTimelineIds(DocumentId documentId);

    /** Captures immutable counters, phase timers, and in-memory gauges. */
    CoordinationMetrics metrics();

    /** Releases runtime resources; repeated close calls are harmless. */
    @Override
    void close();

    /** Builder kept intentionally small until a durable host boundary exists. */
    final class Builder {
        private boolean inMemory;

        /** Selects the supported in-memory implementation. */
        public Builder inMemory() {
            inMemory = true;
            return this;
        }

        /** Validates the configuration and creates the engine. */
        public CoordinationEngine build() {
            if (!inMemory) {
                throw new CoordinationException(
                        CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                        "Select the supported in-memory engine");
            }
            return DefaultCoordinationEngine.create();
        }
    }
}
