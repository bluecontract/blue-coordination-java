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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact initialization-event evidence crossing frozen parent PROCESS. */
final class InitializationLifecycleEventOrderingIntegrationTest {
    private static final String PARENT =
            "r12-initialization-event-parent";
    private static final String PARENT_RESOURCE =
            "examples/round12/initialization-event-observer-parent.yaml";
    private static final String PARENT_TIMELINE =
            "examples/round12/initialization-events/parent";
    private static final String PARENT_ACTOR =
            "round12-initialization-event-observer";

    @Test
    void lifecycleHandlerRetainsTwoIdenticalInitializationEvents()
            throws Exception {
        String childId = "round12-duplicate-initialization-events";
        try (TestEngine engine = TestEngine.create()) {
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            TimelineEntry attachment = attach(engine,
                    "examples/round12/initialization-duplicate-events-child.yaml");

            DocumentRevision initialization = initialization(
                    engine, childId, attachment);
            List<Node> events = initialization.emittedEvents();
            assertEquals(2, events.size());
            assertEquals("Round12/Identical Initialization Event",
                    kind(events.get(0)));
            assertEquals(kind(events.get(0)), kind(events.get(1)));

            String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                    events.get(0));
            assertEquals(eventBlueId,
                    DirectBlueIdCalculator.calculateBlueId(events.get(1)),
                    "the lifecycle handler deliberately emits one exact value twice");

            assertParentEvidence(engine, eventBlueId, eventBlueId,
                    kind(events.get(0)), kind(events.get(1)));
            assertEquals(1L, integer(engine, childId,
                    "/initializationCount"));
            assertInitializationWork(engine, before);
        }
    }

    @Test
    void lifecycleHandlerRetainsInitializationEventsInEmissionOrder()
            throws Exception {
        String childId = "round12-ordered-initialization-events";
        try (TestEngine engine = TestEngine.create()) {
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            TimelineEntry attachment = attach(engine,
                    "examples/round12/initialization-ordered-events-child.yaml");

            DocumentRevision initialization = initialization(
                    engine, childId, attachment);
            List<Node> events = initialization.emittedEvents();
            assertEquals(2, events.size());
            assertEquals("Round12/Initialization Event A",
                    kind(events.get(0)));
            assertEquals("Round12/Initialization Event B",
                    kind(events.get(1)));

            String firstBlueId = DirectBlueIdCalculator.calculateBlueId(
                    events.get(0));
            String secondBlueId = DirectBlueIdCalculator.calculateBlueId(
                    events.get(1));
            assertNotEquals(firstBlueId, secondBlueId);
            assertParentEvidence(engine, firstBlueId, secondBlueId,
                    kind(events.get(0)), kind(events.get(1)));
            assertEquals(1L, integer(engine, childId,
                    "/initializationCount"));
            assertInitializationWork(engine, before);
        }
    }

    private static TimelineEntry attach(
            TestEngine engine,
            String childResource) throws Exception {
        Timeline parentTimeline = engine.timeline(
                PARENT_TIMELINE, PARENT_ACTOR);
        engine.start(PARENT, resource(PARENT_RESOURCE));
        TimelineEntry attachment = engine.append(
                parentTimeline,
                Operation.exact(
                        "attachChild",
                        "parentChannel",
                        engine.embeddedDocumentRequest(
                                resource(childResource))));
        engine.dispatch(attachment);
        return attachment;
    }

    private static DocumentRevision initialization(
            TestEngine engine,
            String childId,
            TimelineEntry attachment) {
        List<DocumentRevision> history = engine.history(childId);
        assertEquals(1, history.size(),
                "the embedded child must initialize exactly once");
        DocumentRevision initialization = history.get(0);
        assertEquals(DocumentRevision.Kind.INITIALIZATION,
                initialization.kind());
        assertTrue(initialization.sourceEntry().isEmpty(),
                "initialization is not an external Timeline Entry");
        assertEquals(attachment.blueId(),
                initialization.causalEntryBlueId().orElseThrow());
        assertTrue(initialization.sourceOrderKey().isPresent());
        assertFalse(initialization.catchUpCause().isEmpty());
        return initialization;
    }

    private static void assertParentEvidence(
            TestEngine engine,
            String firstBlueId,
            String secondBlueId,
            String firstKind,
            String secondKind) {
        assertEquals(1L, integer(engine, PARENT,
                "/initializationApplications"));
        assertEquals(2L, integer(engine, PARENT,
                "/observedEventCount"));
        assertEquals(0L, integer(engine, PARENT,
                "/firstOccurrenceIndex"));
        assertEquals(1L, integer(engine, PARENT,
                "/secondOccurrenceIndex"));
        assertEquals(firstBlueId, text(engine, PARENT,
                "/firstEventBlueId"));
        assertEquals(secondBlueId, text(engine, PARENT,
                "/secondEventBlueId"));
        assertEquals(firstKind, text(engine, PARENT,
                "/firstEventKind"));
        assertEquals(secondKind, text(engine, PARENT,
                "/secondEventKind"));

        List<DocumentRevision> parentHistory = engine.history(PARENT);
        assertEquals(1L, parentHistory.stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                .count());
    }

    private static void assertInitializationWork(
            TestEngine engine,
            EngineMetrics.MetricsSnapshot before) {
        EngineTestSupport.MetricDelta work = delta(
                before, engine.metricsSnapshot());
        assertEquals(1L, work.counter(
                "temporal.initializationEpochApplications"));
        assertEquals(2L, work.counter(
                "temporal.initializationEventOccurrencesForwarded"));
    }

    private static String kind(Node event) {
        return (String) event.getProperties().get("kind").getValue();
    }
}
