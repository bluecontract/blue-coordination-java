package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AllTimelinesChannelProcessorTest {
    private static final String TIMELINE = "shared-timeline";
    private static final String ACTOR = "shared-actor";

    @Test
    void shouldEnsureThatAllTimelinesWithSeveralMatchingChildrenDeliversOnce() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = matchingChildren();
        contracts.put("all", allTimelines());
        contracts.put("handler", fixedHandler("union"));
        Node initialized = initializedDocument(fixture, contracts);

        // when
        DocumentProcessingResult result = process(fixture,
                initialized,
                TIMELINE,
                ACTOR,
                10,
                "hello");

        // then
        assertChatCount(result.events(), "union", 1);
        assertAllCheckpointSubject(
                checkpoint(result.document(), "all"),
                BigInteger.TEN,
                "childA");
        assertNull(checkpoint(result.document(), "all::childA"));
        assertNull(checkpoint(result.document(), "all::childB"));
    }

    @Test
    void shouldSelectTheLowestOrderMatchingAllTimelinesChild() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> ordered = matchingChildren();
        ordered.get("childB").properties("order", new Node().value(-1));
        ordered.put("all", allTimelines());
        ordered.put("handler", fixedHandler("union"));

        // when
        DocumentProcessingResult orderWinner = process(fixture,
                initializedDocument(fixture, ordered),
                TIMELINE,
                ACTOR,
                1,
                "order");

        // then
        assertChatCount(orderWinner.events(), "union", 1);
        assertAllCheckpointSubject(
                checkpoint(orderWinner.document(), "all"),
                BigInteger.ONE,
                "childB");
    }

    @Test
    void shouldSelectTheFirstMatchingAllTimelinesChildKeyWhenOrdersTie() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> tied = new LinkedHashMap<String, Node>();
        tied.put("childB", TestTimelineProvider.channel(TIMELINE, ACTOR));
        tied.put("childA", TestTimelineProvider.channel(TIMELINE, ACTOR));
        tied.put("all", allTimelines());
        tied.put("handler", fixedHandler("union"));

        // when
        DocumentProcessingResult keyWinner = process(fixture,
                initializedDocument(fixture, tied),
                TIMELINE,
                ACTOR,
                1,
                "key");

        // then
        assertChatCount(keyWinner.events(), "union", 1);
        assertAllCheckpointSubject(
                checkpoint(keyWinner.document(), "all"),
                BigInteger.ONE,
                "childA");
    }

    @Test
    void shouldConsumePlatformDeliveryOrderAcrossTimelines() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("alice", TestTimelineProvider.channel("alice-timeline", "alice-actor"));
        contracts.put("bob", TestTimelineProvider.channel("bob-timeline", "bob-actor"));
        contracts.put("all", allTimelines());
        Node initialized = initializedDocument(fixture, contracts);
        Node aliceEvent = event(
                fixture,
                "alice-timeline",
                "alice-actor",
                100,
                "alice");
        Node bobEvent = event(
                fixture,
                "bob-timeline",
                "bob-actor",
                100,
                "bob");
        String aliceTimelineBlueId =
                TimelineProviderSupport.eventId(
                        CoordinationEventNodes.timelineEntry(
                                aliceEvent).timeline());
        String bobTimelineBlueId =
                TimelineProviderSupport.eventId(
                        CoordinationEventNodes.timelineEntry(
                                bobEvent).timeline());
        Node platformFirst =
                aliceTimelineBlueId.compareTo(
                        bobTimelineBlueId) > 0
                ? aliceEvent
                : bobEvent;
        Node platformSecond = platformFirst == aliceEvent
                ? bobEvent
                : aliceEvent;
        String secondMember = platformSecond == aliceEvent
                ? "alice"
                : "bob";

        DocumentProcessingResult first =
                fixture.blue.processDocument(
                        initialized, platformFirst);

        // when
        DocumentProcessingResult second =
                fixture.blue.processDocument(
                        first.document(), platformSecond);

        // then
        assertAllCheckpointSubject(
                checkpoint(second.document(), "all"),
                BigInteger.valueOf(100),
                secondMember);
        assertDirectCheckpointSubject(
                checkpoint(second.document(), "alice"),
                BigInteger.valueOf(100));
        assertDirectCheckpointSubject(
                checkpoint(second.document(), "bob"),
                BigInteger.valueOf(100));
    }

    @Test
    void shouldEnsureThatAllTimelinesRejectsEntryThatMatchesNoDeclaredTimelineChannel() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("child", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("all", allTimelines());
        contracts.put("triggered", new Node().type("Triggered Event Channel"));

        // when
        DocumentProcessingResult result = process(fixture,
                initializedDocument(fixture, contracts),
                "unknown-timeline",
                "unknown-actor",
                1,
                "unknown");

        // then
        assertNull(checkpoint(result.document(), "all"));
    }

    @Test
    void shouldEnsureThatAllTimelinesWithNoTimelineMembersAcceptsNothing() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("all", allTimelines());
        Node initialized = initializedDocument(fixture, contracts);

        // when
        DocumentProcessingResult result = process(
                fixture,
                initialized,
                TIMELINE,
                ACTOR,
                1,
                "unmatched");

        // then
        assertEquals(
                ProcessorStatus.NO_MATCH,
                result.status(),
                blue.coordination.processor.ProcessingResultTestSupport
                        .diagnosticMessage(result));
        assertNull(checkpoint(result.document(), "all"));
    }

    private static Map<String, Node> matchingChildren() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("childA", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("childB", TestTimelineProvider.channel(TIMELINE, ACTOR));
        return contracts;
    }

    private static Node allTimelines() {
        return new Node().type("Coordination/All Timelines Channel");
    }

    private static Node fixedHandler(String message) {
        Node step = new Node()
                .type("Coordination/Trigger Event")
                .properties(
                        "event",
                        TestTimelineProvider.chatMessage(message));
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("all"))
                .properties("steps", new Node().items(step));
    }

    private static Node initializedDocument(Fixture fixture, Map<String, Node> contracts) {
        Node document = new Node()
                .blue(fixture.repository.importsDirective())
                .name("All Timelines V2 Test")
                .properties("contracts", new Node().properties(contracts));
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(fixture.blue.preprocess(document));
        assertEquals(ProcessorStatus.SUCCESS, initialized.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(initialized));
        return initialized.document();
    }

    private static DocumentProcessingResult process(Fixture fixture,
                                                    Node document,
                                                    String timeline,
                                                    String actor,
                                                    long timestamp,
                                                    String message) {
        return fixture.blue.processDocument(document,
                event(fixture, timeline, actor, timestamp, message));
    }

    private static Node event(Fixture fixture,
                              String timeline,
                              String actor,
                              long timestamp,
                              String message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                timeline,
                actor,
                BigInteger.valueOf(timestamp),
                TestTimelineProvider.chatMessage(message));
    }

    private static Node checkpoint(Node document, String key) {
        try {
            return document.getAsNode(
                    "/contracts/checkpoint/entries/"
                            + escapePointerSegment(key)
                            + "/subject");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String escapePointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void assertAllCheckpointSubject(
            Node subject,
            BigInteger timestamp,
            String memberKey) {
        assertNotNull(
                subject,
                "Language checkpoint coalescing defect: "
                        + "aggregate checkpoint was erased by a later "
                        + "handler-group marker write");
        assertEquals(
                AllTimelinesExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertNotNull(subject.getAsText("/timelineBlueId"));
        assertNotNull(subject.getAsText("/entryBlueId"));
        assertEquals(memberKey, subject.getAsText("/memberKey"));
        assertNotNull(subject.getAsText("/memberDomain"));
    }

    private static void assertDirectCheckpointSubject(
            Node subject,
            BigInteger timestamp) {
        assertNotNull(
                subject,
                "Language checkpoint coalescing defect: "
                        + "direct checkpoint was erased by a later "
                        + "handler-group marker write");
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertNotNull(subject.getAsText("/timelineBlueId"));
        assertNotNull(subject.getAsText("/entryBlueId"));
    }

    private static void assertChatCount(List<Node> events, String message, int expected) {
        int count = 0;
        for (Node event : events) {
            try {
                if (message.equals(event.get("/message"))) {
                    count++;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        assertEquals(expected, count);
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime blue =
                CoordinationTestResources.configuredBlue(repository);
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final CoordinationTestRuntime blue;

        private Fixture(
                BlueRepository repository,
                CoordinationTestRuntime blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
