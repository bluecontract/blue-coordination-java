package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.PrincipalActor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineProviderSupportFinalSemanticsTest {

    @Test
    void shouldEnsureThatLegacyFilterValidatesOnlyExactImmutableTimelineHeaders() {
        // Given
        Timeline timeline =
                new Timeline().timelineId("timeline-a");
        PrincipalActor actor =
                new PrincipalActor().accountId("actor");
        TimelineChannel contract = new TimelineChannel()
                .timeline(timeline)
                .actor(actor);
        // When
        try (Blue blue =
                     BlueRepository.latest()
                             .configure(new Blue())) {
            Node matching = entry(
                    blue.objectToNode(timeline),
                    10,
                    "matching")
                    .properties(
                            "actor",
                            blue.objectToNode(actor));
            Node wrongTimeline = entry(
                    blue.objectToNode(
                            new Timeline().timelineId("timeline-b")),
                    10,
                    "wrong timeline")
                    .properties(
                            "actor",
                            blue.objectToNode(actor));
            Node wrongActor = matching.clone()
                    .properties(
                            "actor",
                            blue.objectToNode(
                                    new PrincipalActor()
                                            .accountId("other actor")));

            // Then
            assertTrue(TimelineExternalSubscriptionFunctions.INSTANCE.accepts(
                    contract, matching));
            assertFalse(TimelineExternalSubscriptionFunctions.INSTANCE.accepts(
                    contract, wrongTimeline));
            assertFalse(TimelineExternalSubscriptionFunctions.INSTANCE.accepts(
                    contract, wrongActor));
            assertFalse(TimelineExternalSubscriptionFunctions.INSTANCE.accepts(
                    contract,
                    new Node().value("not a Timeline Entry")));
            assertFalse(TimelineExternalSubscriptionFunctions.INSTANCE.accepts(
                    null, matching));
            assertFalse(TimelineExternalSubscriptionFunctions.INSTANCE.accepts(
                    contract, null));
        }
    }

    @Test
    void shouldRetainTheExactFixedTimelineCheckpointKey() {
        // Given
        Node exactEntry = entry(
                "timeline-a",
                10,
                "checkpointed");
        CoordinationEventNodes.TimelineEntryView view =
                CoordinationEventNodes.timelineEntry(exactEntry);

        // When
        Node subject =
                TimelineProviderSupport.timelineOrderSubject(view);

        // Then
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(BigInteger.TEN, subject.get("/timestamp"));
        assertEquals(
                exactBlueId("timeline-a"),
                subject.getAsText("/timelineBlueId"));
        assertNull(TimelineProviderSupport.property(
                subject, "sequence"));
        assertEquals(
                TimelineProviderSupport.eventId(exactEntry),
                subject.getAsText("/entryBlueId"));
    }

    @Test
    void shouldNotInventSequenceInCheckpointSubject() {
        // Given
        Node exactEntry =
                entry("timeline-a", 11,
                        "fixed-shape");

        // When
        Node subject =
                TimelineProviderSupport.timelineOrderSubject(
                        CoordinationEventNodes.timelineEntry(
                                exactEntry));

        // Then
        assertNull(TimelineProviderSupport.property(
                subject, "sequence"));
    }

    @Test
    void shouldEnsureThatCheckpointSubjectSemanticsAreRotatedTogether() {
        // Given
        // When
        // Then
        assertTrue(TimelineExternalSubscriptionFunctions
                .TIMELINE_ORDER_SUBJECT_VERSION.endsWith("-v3"));
        assertTrue(CompositeTimelineExternalSubscriptionFunctions
                .ORDER_SUBJECT_VERSION.endsWith("-v3"));
        assertTrue(AllTimelinesExternalSubscriptionFunctions
                .ORDER_SUBJECT_VERSION.endsWith("-v3"));
    }

    @Test
    void shouldPreserveEveryImmutableTimelineEntryHeader() {
        // Given
        Node previous = entry("timeline-a", 9, "previous");
        Node event = entry("timeline-a", 10, "message")
                .properties("prevEntry", new Node().blueId(
                        TimelineProviderSupport.eventId(previous)))
                .properties("source", exactReference("source"))
                .properties("onBehalfOf", exactReference("authority"));
        // When
        CoordinationEventNodes.TimelineEntryView view =
                CoordinationEventNodes.timelineEntry(event);

        // Then
        assertNotNull(view);
        assertEquals(exactBlueId("timeline-a"),
                view.timeline().getBlueId());
        assertEquals(
                TimelineProviderSupport.eventId(previous),
                view.prevEntry().getBlueId());
        assertEquals(BigInteger.TEN, view.timestamp());
        assertEquals(BigInteger.TEN, view.timestampNode().getValue());
        assertEquals(exactBlueId("actor"),
                view.actor().getBlueId());
        assertEquals(exactBlueId("source"),
                view.source().getBlueId());
        assertEquals(exactBlueId("authority"),
                view.onBehalfOf().getBlueId());
        assertEquals("message", view.message().getValue());
        assertEquals(
                TimelineProviderSupport.eventId(event),
                view.entryBlueId());
    }

    @Test
    void shouldDefensivelyCopyTimelineEntryHeaders() {
        // Given
        Node event = entry(
                "timeline-a", 10,
                "message")
                .properties(
                        "source",
                        exactReference("source"));
        CoordinationEventNodes.TimelineEntryView view =
                CoordinationEventNodes.timelineEntry(event);

        // When
        event.getProperties().remove("source");
        view.timeline().blueId("mutated");
        view.message().value("mutated");

        // Then
        assertEquals(exactBlueId("timeline-a"),
                view.timeline().getBlueId());
        assertEquals(exactBlueId("source"),
                view.source().getBlueId());
        assertEquals("message", view.message().getValue());
        assertNotNull(view.exactEntry().getProperties().get("source"));
    }

    @Test
    void shouldEnsureThatCommittedFrontierIsExclusiveAndExactTimelineBound() {
        // Given
        Node timeline = exactReference("timeline-a");
        Node before = entry("timeline-a", 99, "before");
        // When
        Node at = entry("timeline-a", 100, "at");

        // Then
        assertTrue(TimelineProviderSupport.isBehindCommittedFrontier(
                before, timeline, BigInteger.valueOf(100)));
        assertFalse(TimelineProviderSupport.isBehindCommittedFrontier(
                at, timeline, BigInteger.valueOf(100)));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineProviderSupport.isBehindCommittedFrontier(
                        before,
                        exactReference("timeline-b"),
                        BigInteger.valueOf(100)));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineProviderSupport.isBehindCommittedFrontier(
                        before, timeline, null));
    }

    @Test
    void shouldEnsureThatPredecessorMustBindExactEntryTimelineAndDefaultOrder() {
        // Given
        Node previous = entry("timeline-a", 10, "previous");
        // When
        Node current = entry("timeline-a", 11, "current")
                .properties("prevEntry", new Node().blueId(
                        TimelineProviderSupport.eventId(previous)));

        // Then
        assertTrue(TimelineProviderSupport.followsExactPredecessor(
                current, previous));

        Node wrongIdentity = current.clone().properties(
                "prevEntry", exactReference("wrong"));
        assertFalse(TimelineProviderSupport.followsExactPredecessor(
                wrongIdentity, previous));

        Node wrongTimeline = entry("timeline-b", 11, "current")
                .properties("prevEntry", new Node().blueId(
                        TimelineProviderSupport.eventId(previous)));
        assertFalse(TimelineProviderSupport.followsExactPredecessor(
                wrongTimeline, previous));

        Node backdated = entry("timeline-a", 9, "current")
                .properties("prevEntry", new Node().blueId(
                        TimelineProviderSupport.eventId(previous)));
        assertFalse(TimelineProviderSupport.followsExactPredecessor(
                backdated, previous));
    }

    @Test
    void shouldRejectAnEqualTimestampPredecessorEdge() {
        // Given
        Node previous = entry("timeline-a", 10, "previous");
        // When
        Node equalTimestamp = withPredecessor(
                entry("timeline-a", 10, "equal"),
                previous);

        // Then
        assertFalse(TimelineProviderSupport.followsExactPredecessor(
                equalTimestamp, previous));
    }

    @Test
    void shouldPreserveVerifiedPlatformOrderAcrossTimelines() {
        // Given
        Node timelineA = exactReference("timeline-a");
        Node timelineB = exactReference("timeline-b");
        Node a1 = entry("timeline-a", 100, "A1");
        Node b1 = entry("timeline-b", 90, "B1");
        Node a2 = entry("timeline-a", 110, "A2");
        Map<String, BigInteger> frontiers =
                new LinkedHashMap<String, BigInteger>();
        frontiers.put(
                timelineA.getBlueId(),
                BigInteger.valueOf(120));
        frontiers.put(
                timelineB.getBlueId(),
                BigInteger.valueOf(120));

        // When
        TimelineProviderSupport.CompletenessWindow window =
                TimelineProviderSupport.evaluateCompletenessWindow(
                        Arrays.asList(a1, b1, a2),
                        Arrays.asList(timelineB, timelineA),
                        frontiers);

        // Then
        assertTrue(window.ready());
        assertEquals(BigInteger.valueOf(110),
                window.maximumTimestamp());
        assertEquals(
                Arrays.asList(
                        TimelineProviderSupport.eventId(a1),
                        TimelineProviderSupport.eventId(b1),
                        TimelineProviderSupport.eventId(a2)),
                entryBlueIds(window.orderedEntries()));
        assertTrue(window.incompleteTimelineBlueIds().isEmpty());

        window.orderedEntries().get(0)
                .getProperties().get("message")
                .value("mutated");
        assertEquals("A1", window.orderedEntries().get(0)
                .getProperties().get("message").getValue());
    }

    @Test
    void shouldRejectEqualTimestampsWithinOneTimelineWindow() {
        // Given
        Node timelineA = exactReference("timeline-a");
        Node first = entry("timeline-a", 100, "first");
        Node second = entry("timeline-a", 100, "second");
        Map<String, BigInteger> frontiers =
                Collections.singletonMap(
                        timelineA.getBlueId(),
                        BigInteger.valueOf(101));

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TimelineProviderSupport
                                .evaluateCompletenessWindow(
                                        Arrays.asList(first, second),
                                        Collections.singletonList(timelineA),
                                        frontiers));

        // Then
        assertTrue(failure.getMessage().contains(
                "timestamps must be strictly increasing"));
    }

    @Test
    void shouldRejectDecreasingTimestampsWithinOneTimelineWindow() {
        // Given
        Node timelineA = exactReference("timeline-a");
        Node later = entry("timeline-a", 101, "later");
        Node earlier = entry("timeline-a", 100, "earlier");
        Map<String, BigInteger> frontiers =
                Collections.singletonMap(
                        timelineA.getBlueId(),
                        BigInteger.valueOf(102));

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TimelineProviderSupport
                                .evaluateCompletenessWindow(
                                        Arrays.asList(later, earlier),
                                        Collections.singletonList(timelineA),
                                        frontiers));

        // Then
        assertTrue(failure.getMessage().contains(
                "timestamps must be strictly increasing"));
    }

    @Test
    void shouldFailClosedForInsufficientCompleteness() {
        // Given
        Node timelineA = exactReference("timeline-a");
        Node timelineB = exactReference("timeline-b");
        Node a1 = entry("timeline-a", 100, "A1");
        Node b1 = entry("timeline-b", 90, "B1");
        Map<String, BigInteger> frontiers =
                new LinkedHashMap<String, BigInteger>();
        frontiers.put(
                timelineA.getBlueId(),
                BigInteger.valueOf(120));
        frontiers.put(
                timelineB.getBlueId(),
                BigInteger.valueOf(80));

        // When
        TimelineProviderSupport.CompletenessWindow window =
                TimelineProviderSupport.evaluateCompletenessWindow(
                        Arrays.asList(a1, b1),
                        Arrays.asList(timelineA, timelineB),
                        frontiers);

        // Then
        assertFalse(window.ready());
        assertTrue(window.orderedEntries().isEmpty());
        assertEquals(
                Arrays.asList(timelineB.getBlueId()),
                window.incompleteTimelineBlueIds());
    }

    @Test
    void shouldRejectInconsistentCompletenessInputs() {
        // Given
        Node timelineA = exactReference("timeline-a");
        Node timelineB = exactReference("timeline-b");
        Node a1 = entry("timeline-a", 100, "A1");
        Node b1 = entry("timeline-b", 90, "B1");
        Map<String, BigInteger> frontiers =
                new LinkedHashMap<String, BigInteger>();
        frontiers.put(
                timelineA.getBlueId(),
                BigInteger.valueOf(120));
        frontiers.put(
                timelineB.getBlueId(),
                BigInteger.valueOf(80));
        Map<String, BigInteger> extra =
                new LinkedHashMap<String, BigInteger>(
                        frontiers);
        extra.put(
                exactBlueId("inactive"),
                BigInteger.valueOf(120));

        // When
        // Then
        assertThrows(IllegalArgumentException.class,
                () -> TimelineProviderSupport
                        .evaluateCompletenessWindow(
                                Arrays.asList(a1, b1),
                                Arrays.asList(
                                        timelineA, timelineB),
                                extra));
        assertThrows(IllegalArgumentException.class,
                () -> TimelineProviderSupport
                        .evaluateCompletenessWindow(
                                Arrays.asList(a1, b1),
                                Arrays.asList(timelineA),
                                Collections.singletonMap(
                                        timelineA.getBlueId(),
                                        BigInteger.valueOf(120))));
    }

    private static Node entry(
            String timelineBlueId,
            long timestamp,
            String message) {
        return entry(
                exactReference(timelineBlueId),
                timestamp,
                message);
    }

    private static Node entry(
            Node timeline,
            long timestamp,
            String message) {
        return new Node()
                .type(new Node().blueId(TimelineEntry.blueId()))
                .properties("timeline", timeline)
                .properties("timestamp", new Node().value(
                        BigInteger.valueOf(timestamp)))
                .properties("actor", exactReference("actor"))
                .properties("message", new Node().value(message));
    }

    private static Node withPredecessor(
            Node entry,
            Node predecessor) {
        return entry.properties(
                "prevEntry",
                new Node().blueId(
                        TimelineProviderSupport.eventId(predecessor)));
    }

    private static Node exactReference(String label) {
        return new Node().blueId(exactBlueId(label));
    }

    private static List<String> entryBlueIds(
            List<Node> entries) {
        List<String> blueIds =
                new ArrayList<String>(entries.size());
        for (Node entry : entries) {
            blueIds.add(
                    TimelineProviderSupport.eventId(entry));
        }
        return blueIds;
    }

    private static String exactBlueId(String label) {
        return TimelineProviderSupport.eventId(
                new Node().properties(
                        "fixtureIdentity",
                        new Node().value(label)));
    }
}
