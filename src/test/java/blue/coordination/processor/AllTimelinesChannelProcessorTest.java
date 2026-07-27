package blue.coordination.processor;

import blue.language.Blue;
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
    void allTimelinesWithSeveralMatchingChildrenDeliversOnce() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = matchingChildren();
        contracts.put("all", allTimelines());
        contracts.put("handler", fixedHandler("union"));
        Node initialized = initializedDocument(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                TIMELINE,
                ACTOR,
                10,
                "hello");

        assertChatCount(result.events(), "union", 1);
        assertAllCheckpointSubject(
                checkpoint(result.document(), "all"),
                BigInteger.TEN,
                "childA");
        assertNull(checkpoint(result.document(), "all::childA"));
        assertNull(checkpoint(result.document(), "all::childB"));
    }

    @Test
    void allTimelinesMatchingChildSelectionUsesOrderThenKey() {
        Fixture fixture = configuredFixture();
        Map<String, Node> ordered = matchingChildren();
        ordered.get("childB").properties("order", new Node().value(-1));
        ordered.put("all", allTimelines());
        ordered.put("handler", fixedHandler("union"));

        DocumentProcessingResult orderWinner = process(fixture,
                initializedDocument(fixture, ordered),
                TIMELINE,
                ACTOR,
                1,
                "order");

        assertChatCount(orderWinner.events(), "union", 1);
        assertAllCheckpointSubject(
                checkpoint(orderWinner.document(), "all"),
                BigInteger.ONE,
                "childB");

        Fixture keyFixture = configuredFixture();
        Map<String, Node> tied = new LinkedHashMap<String, Node>();
        tied.put("childB", TestTimelineProvider.channel(TIMELINE, ACTOR));
        tied.put("childA", TestTimelineProvider.channel(TIMELINE, ACTOR));
        tied.put("all", allTimelines());
        tied.put("handler", fixedHandler("union"));

        DocumentProcessingResult keyWinner = process(keyFixture,
                initializedDocument(keyFixture, tied),
                TIMELINE,
                ACTOR,
                1,
                "key");

        assertChatCount(keyWinner.events(), "union", 1);
        assertAllCheckpointSubject(
                checkpoint(keyWinner.document(), "all"),
                BigInteger.ONE,
                "childA");
    }

    @Test
    void allTimelinesAcceptsEqualTimestampFromDifferentTimeline() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("alice", TestTimelineProvider.channel("alice-timeline", "alice-actor"));
        contracts.put("bob", TestTimelineProvider.channel("bob-timeline", "bob-actor"));
        contracts.put("all", allTimelines());
        Node initialized = initializedDocument(fixture, contracts);

        DocumentProcessingResult alice = process(fixture,
                initialized,
                "alice-timeline",
                "alice-actor",
                100,
                "alice");
        DocumentProcessingResult bob = process(fixture,
                alice.document(),
                "bob-timeline",
                "bob-actor",
                100,
                "bob");

        assertAllCheckpointSubject(
                checkpoint(bob.document(), "all"),
                BigInteger.valueOf(100),
                "bob");
        assertDirectCheckpointSubject(
                checkpoint(bob.document(), "alice"),
                BigInteger.valueOf(100));
        assertDirectCheckpointSubject(
                checkpoint(bob.document(), "bob"),
                BigInteger.valueOf(100));
    }

    @Test
    void allTimelinesRejectsEntryThatMatchesNoDeclaredTimelineChannel() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("child", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("all", allTimelines());
        contracts.put("triggered", new Node().type("Triggered Event Channel"));

        DocumentProcessingResult result = process(fixture,
                initializedDocument(fixture, contracts),
                "unknown-timeline",
                "unknown-actor",
                1,
                "unknown");

        assertNull(checkpoint(result.document(), "all"));
    }

    @Test
    void allTimelinesWithNoTimelineMembersAcceptsNothing() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("all", allTimelines());
        Node initialized = initializedDocument(fixture, contracts);

        DocumentProcessingResult result = process(
                fixture,
                initialized,
                TIMELINE,
                ACTOR,
                1,
                "unmatched");

        assertEquals(
                ProcessorStatus.SUCCESS,
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
                .blue(fixture.repository.typeAliasBlue())
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
        assertNotNull(subject);
        assertEquals(4, subject.getProperties().size());
        assertEquals(
                AllTimelinesExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertEquals(memberKey, subject.getAsText("/memberKey"));
        assertNotNull(subject.getAsText("/memberDomain"));
    }

    private static void assertDirectCheckpointSubject(
            Node subject,
            BigInteger timestamp) {
        assertNotNull(subject);
        assertEquals(2, subject.getProperties().size());
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
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
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(BlueRepository repository, Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
