package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.APICall;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineEntry;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateAuthority;
import blue.repo.myos.MyOSPrincipalActor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineChannelProcessorTest {
    private static final String TIMELINE = "owner-timeline";
    private static final String ACTOR = "owner-account";

    @Test
    void matchingTimelineAndActorAccept() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture);

        Node processed = process(fixture, document,
                event(fixture, TIMELINE, ACTOR, 10, 100, "hello")).document();

        assertEquals(TIMELINE, checkpointEvent(processed).getAsText("/timeline/timelineId"));
        assertEquals(ACTOR, checkpointEvent(processed).getAsText("/actor/accountId"));
        assertEquals("hello", checkpointEvent(processed).getAsText("/message/message"));
    }

    @Test
    void unrelatedTypedLookalikeRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture);
        Node event = new Node()
                .type(ChatMessage.repositoryType().reference())
                .properties("timeline", new Node().blueId("not-a-blue-id"))
                .properties("actor", new Node().blueId("not-a-blue-id"))
                .properties("sequence", new Node().value(1))
                .properties("timestamp", new Node().value(1))
                .properties("message", TestTimelineProvider.chatMessage("lookalike"));

        DocumentProcessingResult result = process(fixture, document, event);

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNull(checkpointEvent(result.document()));
    }

    @Test
    void untypedTimelineLookalikeRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture);
        Node event = new Node()
                .properties("timeline", new Node().blueId("not-a-blue-id"))
                .properties("actor", new Node().blueId("not-a-blue-id"))
                .properties("sequence", new Node().value(1))
                .properties("timestamp", new Node().value(1))
                .properties("message", TestTimelineProvider.chatMessage("lookalike"));

        DocumentProcessingResult result = process(fixture, document, event);

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNull(checkpointEvent(result.document()));
    }

    @Test
    void invalidTimelineEntryReferenceFailsDeterministically() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, 1, "invalid");
        invalid.getProperties().put("timeline", new Node().blueId("not-a-blue-id"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CoordinationEventNodes.timelineEntry(invalid));

        assertTrue(failure.getMessage().contains("Semantic identity reference"), failure.getMessage());
    }

    @Test
    void sameTimelineDifferentActorRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();

        Node processed = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, "different-account", 1, 1, "wrong actor")).document();

        assertNull(checkpointEvent(processed));
    }

    @Test
    void sameActorDifferentTimelineRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();

        Node processed = process(fixture, initializedDocument(fixture),
                event(fixture, "different-timeline", ACTOR, 1, 1, "wrong timeline")).document();

        assertNull(checkpointEvent(processed));
    }

    @Test
    void pureReferenceEqualsEquivalentMaterializedBinding() {
        Fixture fixture = configuredFixture();
        Node timeline = fixture.blue.objectToNode(new Timeline().timelineId(TIMELINE));
        Node actor = fixture.blue.objectToNode(new MyOSPrincipalActor().accountId(ACTOR));

        Node timelineReference = new Node().blueId(fixture.blue.calculateSemanticBlueId(timeline));
        Node actorReference = new Node().blueId(fixture.blue.calculateSemanticBlueId(actor));

        assertTrue(BlueSemanticIdentity.equals(timelineReference, timeline));
        assertTrue(BlueSemanticIdentity.equals(actorReference, actor));
    }

    @Test
    void completedAndMinimalMaterializedBindingsAreEqual() {
        Fixture fixture = configuredFixture();
        Node minimalEntry = event(fixture, TIMELINE, ACTOR, 1, 1, "entry");
        Node completedEntry = fixture.blue.resolve(minimalEntry.clone());

        assertTrue(BlueSemanticIdentity.equals(
                minimalEntry.getAsNode("/timeline"), completedEntry.getAsNode("/timeline")));
        assertTrue(BlueSemanticIdentity.equals(
                minimalEntry.getAsNode("/actor"), completedEntry.getAsNode("/actor")));
    }

    @Test
    void sameTypeDifferentContentDoesNotEqual() {
        Fixture fixture = configuredFixture();
        Node first = fixture.blue.objectToNode(new Timeline().timelineId("first"));
        Node second = fixture.blue.objectToNode(new Timeline().timelineId("second"));

        assertFalse(BlueSemanticIdentity.equals(first, second));
    }

    @Test
    void equalTimestampHigherSequenceAccepts() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 10, 100, "first")).document();

        Node second = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 11, 100, "second")).document();

        assertEquals(BigInteger.valueOf(11), checkpointEvent(second).get("/sequence"));
        assertEquals("second", checkpointEvent(second).getAsText("/message/message"));
    }

    @Test
    void higherTimestampLowerSequenceRejects() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 10, 100, "first")).document();

        Node stale = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 9, 101, "stale")).document();

        assertEquals(BigInteger.TEN, checkpointEvent(stale).get("/sequence"));
        assertEquals("first", checkpointEvent(stale).getAsText("/message/message"));
    }

    @Test
    void lowerTimestampHigherSequenceUsesSequence() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 10, 100, "first")).document();

        Node newer = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 11, 99, "newer")).document();

        assertEquals(BigInteger.valueOf(11), checkpointEvent(newer).get("/sequence"));
        assertEquals(BigInteger.valueOf(99), checkpointEvent(newer).get("/timestamp"));
    }

    @Test
    void exactEventReplayDoesNotRunHandlersAgain() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("replayObserver", replayObserver());
        Node event = event(fixture, TIMELINE, ACTOR, 10, 100, "same");
        DocumentProcessingResult first = process(fixture, initializedDocument(fixture, contracts), event);

        DocumentProcessingResult replay = process(fixture, first.document(), event.clone());

        assertEquals(1, first.triggeredEvents().size());
        assertEquals("handled once", first.triggeredEvents().get(0).getAsText("/message"));
        assertTrue(replay.triggeredEvents().isEmpty());
        assertEquals("same", checkpointEvent(replay.document()).getAsText("/message/message"));
    }

    @Test
    void differentContentAtCheckpointedSequenceRejectsAsEquivocation() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 10, 100, "first")).document();

        Node equivocation = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 10, 100, "different")).document();

        assertEquals("first", checkpointEvent(equivocation).getAsText("/message/message"));
    }

    @Test
    void sequenceBeyondLongRangeRemainsExact() {
        Fixture fixture = configuredFixture();
        BigInteger firstSequence = new BigInteger("9223372036854775808123456789");
        BigInteger secondSequence = firstSequence.add(BigInteger.ONE);
        Node firstEvent = event(fixture, TIMELINE, ACTOR, firstSequence, BigInteger.ONE, "first");
        assertEquals(firstSequence, firstEvent.get("/sequence"));
        assertNotNull(CoordinationEventNodes.timelineEntry(firstEvent));
        DocumentProcessingResult firstResult = process(fixture, initializedDocument(fixture),
                firstEvent);
        assertNotNull(checkpointEvent(firstResult.document()), firstResult.failureReason());

        DocumentProcessingResult secondResult = process(fixture, firstResult.document(),
                event(fixture, TIMELINE, ACTOR, secondSequence, BigInteger.ONE, "second"));
        assertNotNull(checkpointEvent(secondResult.document()), secondResult.failureReason());

        assertEquals(secondSequence, checkpointEvent(secondResult.document()).get("/sequence"));
    }

    @Test
    void missingTimelineRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("timeline");
    }

    @Test
    void missingActorRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("actor");
    }

    @Test
    void missingSequenceRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("sequence");
    }

    @Test
    void invalidSequenceRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, 1, "invalid");
        invalid.getProperties().put("sequence", new Node().value("1"));

        assertRejected(fixture, invalid);
    }

    @Test
    void decimalSequenceRejectsWithoutTruncation() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, 1, "invalid");
        invalid.getProperties().put("sequence", new Node().value(new BigDecimal("1.5")));

        assertRejected(fixture, invalid);
    }

    @Test
    void missingTimestampRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("timestamp");
    }

    @Test
    void invalidTimestampRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, 1, "invalid");
        invalid.getProperties().put("timestamp", new Node().value("1"));

        assertRejected(fixture, invalid);
    }

    @Test
    void missingMessageRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("message");
    }

    @Test
    void optionalSourceSurvivesDeliveryUnchanged() {
        Fixture fixture = configuredFixture();
        TimelineEntry attributed = baseEntry(fixture, BigInteger.ONE, BigInteger.ONE, "source")
                .source(new APICall().apiKeyId("api-key-7"));

        Node event = fixture.blue.preprocess(fixture.blue.objectToNode(attributed)
                .blue(fixture.repository.typeAliasBlue())).blue(null);
        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), event);
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        Node processed = result.document();

        assertEquals("api-key-7", checkpointEvent(processed).getAsText("/source/apiKeyId"));
    }

    @Test
    void optionalOnBehalfOfSurvivesDeliveryUnchanged() {
        Fixture fixture = configuredFixture();
        Node authority = new Node()
                .type(MandateAuthority.qualifiedName())
                .properties("actor", new Node()
                        .type(MyOSPrincipalActor.qualifiedName())
                        .properties("accountId", new Node().value("represented-account")))
                .properties("mandate", authorityMandate());
        Node event = fixture.blue.preprocess(fixture.blue.objectToNode(
                        baseEntry(fixture, BigInteger.ONE, BigInteger.ONE, "authority"))
                .properties("onBehalfOf", authority)
                .blue(fixture.repository.typeAliasBlue())).blue(null);
        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), event);
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        Node processed = result.document();

        assertEquals("represented-account",
                checkpointEvent(processed).getAsText("/onBehalfOf/actor/accountId"));
        assertEquals("Timeline Authority Mandate",
                checkpointEvent(processed).getAsText("/onBehalfOf/mandate/name"));
        assertNotNull(checkpointEvent(result.snapshot().resolvedRoot()).getAsNode(
                "/onBehalfOf/mandate/contracts/mandateGuarantorChannel/type"));
    }

    private static void assertMissingFieldRejects(String field) {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, 1, "invalid");
        invalid.getProperties().remove(field);
        assertRejected(fixture, invalid);
    }

    private static void assertRejected(Fixture fixture, Node event) {
        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), event);
        assertNull(checkpointEvent(result.document()), result.failureReason());
    }

    private static Node initializedDocument(Fixture fixture) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel(TIMELINE, ACTOR));
        return initializedDocument(fixture, contracts);
    }

    private static Node initializedDocument(Fixture fixture, Map<String, Node> contracts) {
        Node document = new Node()
                .blue(fixture.repository.typeAliasBlue())
                .name("Timeline V2 Test")
                .properties("contracts", new Node().properties(contracts));
        DocumentProcessingResult result = fixture.blue.initializeDocument(fixture.blue.preprocess(document));
        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNotNull(result.snapshot());
        return result.document();
    }

    private static Node replayObserver() {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("ownerChannel"))
                .properties("steps", new Node().items(new Node()
                        .type("Coordination/Trigger Event")
                        .properties("event", TestTimelineProvider.chatMessage("handled once"))));
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static Node event(Fixture fixture,
                              String timelineId,
                              String actorId,
                              long sequence,
                              long timestamp,
                              String message) {
        return event(fixture,
                timelineId,
                actorId,
                BigInteger.valueOf(sequence),
                BigInteger.valueOf(timestamp),
                message);
    }

    private static Node event(Fixture fixture,
                              String timelineId,
                              String actorId,
                              BigInteger sequence,
                              BigInteger timestamp,
                              String message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                timelineId,
                actorId,
                sequence,
                timestamp,
                TestTimelineProvider.chatMessage(message));
    }

    private static TimelineEntry baseEntry(Fixture fixture,
                                           BigInteger sequence,
                                           BigInteger timestamp,
                                           String message) {
        return new TimelineEntry()
                .timeline(new Timeline().timelineId(TIMELINE))
                .actor(new MyOSPrincipalActor().accountId(ACTOR))
                .sequence(sequence)
                .timestamp(timestamp)
                .message(fixture.blue.objectToNode(new ChatMessage().message(message)));
    }

    private static Node authorityMandate() {
        return new Node()
                .name("Timeline Authority Mandate")
                .type(Mandate.qualifiedName())
                .properties("contracts", requiredMandateChannels());
    }

    private static Node requiredMandateChannels() {
        return new Node().properties("mandateGuarantorChannel", TestTimelineProvider.channel("guarantor"))
                .properties("authorityHolderChannel", TestTimelineProvider.channel("holder"))
                .properties("authorizedActorChannel", TestTimelineProvider.channel("authorized"));
    }

    private static DocumentProcessingResult process(Fixture fixture, Node document, Node event) {
        return fixture.blue.processDocument(document, event);
    }

    private static Node checkpointEvent(Node document) {
        return nodeAt(document, "/contracts/checkpoint/lastEvents/ownerChannel");
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
