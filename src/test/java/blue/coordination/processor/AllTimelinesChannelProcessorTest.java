package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.repo.BlueRepository;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AllTimelinesChannelProcessorTest {

    @Test
    void allTimelinesChannelLetsDeclaredTimelineParticipantsCallChatOperation() {
        Fixture fixture = configuredFixture();
        Node initialized = initializedDocument(fixture, chatDocument(fixture.repository));

        DocumentProcessingResult alice = processChat(fixture, initialized, "alice", 1, "bob", "hello bob");
        DocumentProcessingResult bob = processChat(fixture, alice.document(), "bob", 1, "alice", "hello alice");
        DocumentProcessingResult charlie = processChat(fixture, bob.document(), "charlie", 1, "alice", "not allowed");

        assertSuccessful(alice);
        assertSuccessful(bob);
        assertSuccessful(charlie);
        assertChatMessage(alice, "bob", "hello bob");
        assertChatMessage(bob, "alice", "hello alice");
        assertEquals(0, charlie.triggeredEvents().size());
        assertEquals("alice", alice.document()
                .getAsText("/contracts/checkpoint/lastEvents/aliceTimeline/timeline/timelineId"));
        assertEquals("alice", alice.document()
                .getAsText("/contracts/checkpoint/lastEvents/allTimelines/timeline/timelineId"));
        assertEquals("bob", bob.document()
                .getAsText("/contracts/checkpoint/lastEvents/bobTimeline/timeline/timelineId"));
        assertEquals("bob", bob.document()
                .getAsText("/contracts/checkpoint/lastEvents/allTimelines/timeline/timelineId"));
        assertNull(nodeAt(bob.document(), "/contracts/checkpoint/lastEvents/allTimelines::alice"));
        assertNull(nodeAt(bob.document(), "/contracts/checkpoint/lastEvents/allTimelines::bob"));
    }

    @Test
    void allTimelinesChannelLetsOneParticipantRespondToAnother() {
        Fixture fixture = configuredFixture();
        Node initialized = initializedDocument(fixture, chatDocument(fixture.repository));

        DocumentProcessingResult request = processChat(fixture, initialized, "alice", 1, "bob", "question");
        DocumentProcessingResult response = processChat(fixture, request.document(), "bob", 2, "alice", "answer", "question");

        assertSuccessful(request);
        assertSuccessful(response);
        assertChatMessage(request, "bob", "question");
        assertChatMessage(response, "alice", "answer");
        assertEquals("question", onlyChatEvent(response).getAsText("/inReplyTo"));
        assertEquals("alice", response.document()
                .getAsText("/contracts/checkpoint/lastEvents/aliceTimeline/timeline/timelineId"));
        assertEquals("bob", response.document()
                .getAsText("/contracts/checkpoint/lastEvents/bobTimeline/timeline/timelineId"));
        assertEquals("bob", response.document()
                .getAsText("/contracts/checkpoint/lastEvents/allTimelines/timeline/timelineId"));
        assertNull(nodeAt(response.document(), "/contracts/checkpoint/lastEvents/allTimelines::alice"));
        assertNull(nodeAt(response.document(), "/contracts/checkpoint/lastEvents/allTimelines::bob"));
    }

    @Test
    void allTimelinesChannelUsesDeclaredTimelineCheckpointsForIndependentRecency() {
        Fixture fixture = configuredFixture();
        Node initialized = initializedDocument(fixture, chatDocument(fixture.repository));

        DocumentProcessingResult alice = processChat(fixture, initialized, "alice", 2, "bob", "newer alice");
        DocumentProcessingResult bob = processChat(fixture, alice.document(), "bob", 1, "alice", "independent bob");
        DocumentProcessingResult staleAlice = processChat(fixture, bob.document(), "alice", 1, "bob", "stale alice");

        assertSuccessful(alice);
        assertSuccessful(bob);
        assertSuccessful(staleAlice);
        assertChatMessage(alice, "bob", "newer alice");
        assertChatMessage(bob, "alice", "independent bob");
        assertEquals(0, staleAlice.triggeredEvents().size());
        assertEquals(BigInteger.valueOf(2),
                staleAlice.document().get("/contracts/checkpoint/lastEvents/aliceTimeline/timestamp"));
        assertEquals(BigInteger.valueOf(1),
                staleAlice.document().get("/contracts/checkpoint/lastEvents/bobTimeline/timestamp"));
        assertEquals("bob", staleAlice.document()
                .getAsText("/contracts/checkpoint/lastEvents/allTimelines/timeline/timelineId"));
        assertNull(nodeAt(staleAlice.document(), "/contracts/checkpoint/lastEvents/allTimelines::alice"));
        assertNull(nodeAt(staleAlice.document(), "/contracts/checkpoint/lastEvents/allTimelines::bob"));
    }

    private static Node chatDocument(BlueRepository repository) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("allTimelines", new Node()
                .type("Coordination/All Timelines Channel"));
        contracts.put("aliceTimeline", TestTimelineProvider.channel("alice"));
        contracts.put("bobTimeline", TestTimelineProvider.channel("bob"));
        contracts.put("chat", chatWorkflowOperation());
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("All Timelines Chat")
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node chatWorkflowOperation() {
        return new Node()
                .type("Coordination/Chat Workflow Operation")
                .properties("channel", new Node().value("allTimelines"));
    }

    private static Node chatRequest(String to, String message) {
        return new Node()
                .type("Coordination/Chat Message")
                .properties("to", new Node().value(to))
                .properties("message", new Node().value(message));
    }

    private static Node chatRequest(String to, String message, String inReplyTo) {
        Node request = chatRequest(to, message);
        if (inReplyTo != null) {
            request.properties("inReplyTo", new Node().value(inReplyTo));
        }
        return request;
    }

    private static DocumentProcessingResult processChat(Fixture fixture,
                                                        Node document,
                                                        String timelineId,
                                                        int timestamp,
                                                        String to,
                                                        String message) {
        return processChat(fixture, document, timelineId, timestamp, to, message, null);
    }

    private static DocumentProcessingResult processChat(Fixture fixture,
                                                        Node document,
                                                        String timelineId,
                                                        int timestamp,
                                                        String to,
                                                        String message,
                                                        String inReplyTo) {
        return fixture.blue.processDocument(document,
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        timelineId,
                        timestamp,
                        CoordinationTestResources.operationRequest("chat", chatRequest(to, message, inReplyTo))));
    }

    private static Node initializedDocument(Fixture fixture, Node document) {
        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.blue.preprocess(document));
        assertSuccessful(result);
        return result.document();
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.v1_3_0();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        TestTimelineProvider.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static void assertSuccessful(DocumentProcessingResult result) {
        assertFalse(result.capabilityFailure(), result.failureReason());
    }

    private static void assertChatMessage(DocumentProcessingResult result, String expectedTo, String expectedMessage) {
        Node event = onlyChatEvent(result);
        assertEquals(expectedTo, event.getAsText("/to"));
        assertEquals(expectedMessage, event.getAsText("/message"));
    }

    private static Node onlyChatEvent(DocumentProcessingResult result) {
        List<Node> events = result.triggeredEvents();
        assertEquals(1, events.size());
        return events.get(0);
    }

    private static Node nodeAt(Node node, String path) {
        try {
            Object value = node.get(path);
            return value instanceof Node ? (Node) value : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
