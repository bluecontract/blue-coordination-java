package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSTimeline;
import blue.repo.myos.MyOSTimelineChannel;
import blue.repo.myos.PrincipalActor;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimelineSubtypeAggregateTest {
    private static final String TIMELINE = "myos-timeline";
    private static final String ACTOR = "myos-account";

    @Test
    void shouldIncludeGeneratedMyosMembersInCompositeAndCoalesceTheirDelivery() {
        // Given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = subtypeCatalog(fixture);
        contracts.put(
                "aggregate",
                fixture.blue.objectToNode(
                        new CompositeTimelineChannel()
                                .channels(Arrays.asList(
                                        "myos-b",
                                        "unrelated-timeline",
                                        "myos-a",
                                        "myos-a"))));
        contracts.put(
                "handler",
                fixedHandler("aggregate", "composite-delivery"));
        Node initialized = initializedDocument(fixture, contracts);

        // When
        DocumentProcessingResult result = fixture.blue.processDocument(
                initialized,
                myosEntry(fixture, BigInteger.TEN));

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertChatCount(
                result.events(),
                "composite-delivery",
                1);
        assertAggregateWinner(
                checkpoint(result.document(), "aggregate"),
                CompositeTimelineExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                "myos-a");
        assertNotNull(checkpoint(result.document(), "myos-a"));
        assertNotNull(checkpoint(result.document(), "myos-b"));
        assertNull(checkpoint(
                result.document(),
                "unrelated-timeline"));
        assertNull(checkpoint(
                result.document(),
                "unrelated-channel"));
        assertNull(checkpoint(
                result.document(),
                "aggregate::myos-a"));
    }

    @Test
    void shouldIncludeGeneratedMyosMembersInAllTimelinesAndExcludeUnrelatedChannels() {
        // Given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = subtypeCatalog(fixture);
        contracts.put(
                "aggregate",
                fixture.blue.objectToNode(
                        new AllTimelinesChannel()));
        contracts.put(
                "handler",
                fixedHandler("aggregate", "all-delivery"));
        Node initialized = initializedDocument(fixture, contracts);

        // When
        DocumentProcessingResult result = fixture.blue.processDocument(
                initialized,
                myosEntry(fixture, BigInteger.ONE));

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertChatCount(
                result.events(),
                "all-delivery",
                1);
        assertAggregateWinner(
                checkpoint(result.document(), "aggregate"),
                AllTimelinesExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                "myos-a");
        assertNotNull(checkpoint(result.document(), "myos-a"));
        assertNotNull(checkpoint(result.document(), "myos-b"));
        assertNull(checkpoint(
                result.document(),
                "unrelated-timeline"));
        assertNull(checkpoint(
                result.document(),
                "unrelated-channel"));
    }

    private static Map<String, Node> subtypeCatalog(
            Fixture fixture) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "myos-a",
                myosChannel(fixture, "a@example.test"));
        contracts.put(
                "myos-b",
                myosChannel(fixture, "b@example.test"));
        contracts.put(
                "unrelated-timeline",
                fixture.blue.objectToNode(
                        new TimelineChannel()
                                .timeline(
                                        new Timeline()
                                                .timelineId(
                                                        "other-timeline"))
                                .actor(
                                        new PrincipalActor()
                                                .accountId(
                                                        "other-actor"))));
        contracts.put(
                "unrelated-channel",
                new Node().type("Triggered Event Channel"));
        return contracts;
    }

    private static Node myosChannel(
            Fixture fixture,
            String email) {
        MyOSTimeline timeline =
                new MyOSTimeline();
        timeline.timelineId(TIMELINE);
        MyOSTimelineChannel channel =
                new MyOSTimelineChannel()
                        .accountId(ACTOR)
                        .email(email);
        channel.timeline(timeline);
        channel.actor(
                new PrincipalActor()
                        .accountId(ACTOR));
        return fixture.blue.objectToNode(channel);
    }

    private static Node myosEntry(
            Fixture fixture,
            BigInteger timestamp) {
        MyOSTimeline timeline =
                new MyOSTimeline();
        timeline.timelineId(TIMELINE);
        TimelineEntry entry =
                new TimelineEntry()
                        .timeline(timeline)
                        .actor(
                                new PrincipalActor()
                                        .accountId(ACTOR))
                        .timestamp(timestamp);
        Node event = fixture.blue.objectToNode(entry)
                .properties(
                        "timestamp",
                        new Node().value(timestamp))
                .properties(
                        "message",
                        TestTimelineProvider.chatMessage(
                                "source"))
                .blue(fixture.repository.typeAliasBlue());
        return fixture.blue.preprocess(event).blue(null);
    }

    private static Node fixedHandler(
            String channel,
            String message) {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties(
                        "channel",
                        new Node().value(channel))
                .properties(
                        "steps",
                        new Node().items(
                                new Node()
                                        .type(
                                                "Coordination/Trigger Event")
                                        .properties(
                                                "event",
                                                TestTimelineProvider
                                                        .chatMessage(
                                                                message))));
    }

    private static Node initializedDocument(
            Fixture fixture,
            Map<String, Node> contracts) {
        Node document = new Node()
                .blue(fixture.repository.typeAliasBlue())
                .name("Timeline subtype aggregate test")
                .properties(
                        "contracts",
                        new Node().properties(contracts));
        DocumentProcessingResult initialized =
                fixture.blue.initializeDocument(
                        fixture.blue.preprocess(document));
        assertEquals(
                ProcessorStatus.SUCCESS,
                initialized.status(),
                ProcessingResultTestSupport.diagnosticMessage(
                        initialized));
        return initialized.document();
    }

    private static Node checkpoint(
            Node document,
            String key) {
        try {
            return document.getAsNode(
                    "/contracts/checkpoint/entries/"
                            + escapePointerSegment(key)
                            + "/subject");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String escapePointerSegment(
            String value) {
        return value.replace("~", "~0")
                .replace("/", "~1");
    }

    private static void assertAggregateWinner(
            Node subject,
            String semantics,
            String memberKey) {
        assertNotNull(
                subject,
                "Language checkpoint coalescing defect: "
                        + "aggregate checkpoint was erased by a later "
                        + "handler-group marker write");
        assertEquals(
                semantics,
                subject.getAsText("/semantics"));
        assertEquals(
                memberKey,
                subject.getAsText("/memberKey"));
        assertNotNull(
                subject.getAsText("/memberDomain"));
        assertNotNull(
                subject.getAsText("/entryBlueId"));
    }

    private static void assertChatCount(
            List<Node> events,
            String message,
            int expected) {
        int count = 0;
        for (Node event : events) {
            try {
                if (message.equals(
                        event.get("/message"))) {
                    count++;
                }
            } catch (IllegalArgumentException ignored) {
                // A non-chat emitted event cannot satisfy this assertion.
            }
        }
        assertEquals(expected, count);
    }

    private static Fixture configuredFixture() {
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                CoordinationTestResources
                        .configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        CoordinationProcessors.registerTimelineSubtype(
                blue,
                MyOSTimelineChannel.class);
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(
                BlueRepository repository,
                Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
