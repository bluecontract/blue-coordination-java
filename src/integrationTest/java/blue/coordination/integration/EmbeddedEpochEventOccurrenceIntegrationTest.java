package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Exact indexed child-event evidence crossing the frozen parent boundary. */
final class EmbeddedEpochEventOccurrenceIntegrationTest {
    private static final String PARENT = "duplicate-event-parent";
    private static final String CHILD = "duplicate-event-child";

    @Test
    void duplicateEventIdentitiesRetainBothOrderedOccurrencesInParentProcess()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            Timeline childTimeline = engine.timeline(
                    "examples/duplicate-events/child", "alice");
            engine.start(PARENT, resource(
                    "examples/clean/duplicate-event-embedded-parent.yaml"));
            int parentHistoryBefore = engine.history(PARENT).size();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            TimelineEntry entry = engine.append(
                    childTimeline,
                    Operation.yaml("advance", "childChannel", "{}"));
            engine.drain();
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            DocumentRevision childRevision = last(engine.history(CHILD));
            DocumentRevision parentRevision = last(engine.history(PARENT));
            List<Node> events = childRevision.emittedEvents();
            assertEquals(2, events.size());
            String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                    events.get(0));

            // then
            assertEquals(eventBlueId,
                    DirectBlueIdCalculator.calculateBlueId(events.get(1)),
                    "the two semantic events deliberately share one BlueId");

            assertEquals(0L, integer(engine, PARENT,
                    "/oldChildValueSeen"),
                    "parent PROCESS must start from the old child state");
            assertEquals(1L, integer(engine, PARENT,
                    "/incomingChildValueSeen"));
            assertEquals(0L, integer(engine, PARENT,
                    "/firstOccurrenceIndexSeen"));
            assertEquals(1L, integer(engine, PARENT,
                    "/secondOccurrenceIndexSeen"));
            assertEquals(eventBlueId, text(engine, PARENT,
                    "/firstEventBlueIdSeen"));
            assertEquals(eventBlueId, text(engine, PARENT,
                    "/secondEventBlueIdSeen"));
            assertEquals(text(engine, PARENT,
                            "/firstEventTypeBlueIdSeen"),
                    text(engine, PARENT,
                            "/secondEventTypeBlueIdSeen"));
            assertFalse(text(engine, PARENT,
                    "/firstEventTypeBlueIdSeen").isBlank());

            assertEquals(DocumentRevision.Kind.TIMELINE_ENTRY,
                    childRevision.kind());
            assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    parentRevision.kind());
            assertEquals(entry.blueId(), childRevision.causalEntryBlueId()
                    .orElseThrow());
            assertEquals(entry.blueId(), parentRevision.causalEntryBlueId()
                    .orElseThrow());
            assertEquals(childRevision.before().orElseThrow().blueId(),
                    parentRevision.before().orElseThrow()
                            .canonicalBlueIdAt("/child"));
            assertEquals(childRevision.after().blueId(),
                    parentRevision.after().canonicalBlueIdAt("/child"));
            assertEquals(parentHistoryBefore + 1,
                    engine.history(PARENT).size());
            assertEquals(1L, work.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(1L, work.counter(
                    "temporal.parentEpochApplications"));
        }
    }

    private static DocumentRevision last(List<DocumentRevision> history) {
        return history.get(history.size() - 1);
    }
}
