package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.MarkerContract;
import blue.repo.BlueRepository;
import blue.repo.coordination.APICall;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineEntry;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateAuthority;
import blue.repo.myos.PrincipalActor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
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
                event(fixture, TIMELINE, ACTOR, 100, "hello")).document();

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
                .properties("timestamp", new Node().value(1))
                .properties("message", TestTimelineProvider.chatMessage("lookalike"));

        DocumentProcessingResult result = process(fixture, document, event);

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNull(checkpointEvent(result.document()));
    }

    @Test
    void invalidTimelineEntryReferenceFailsDeterministically() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        invalid.getProperties().put("timeline", new Node().blueId("not-a-blue-id"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CoordinationEventNodes.timelineEntry(invalid));

        assertTrue(failure.getMessage().contains("Semantic identity reference"), failure.getMessage());
    }

    @Test
    void sameTimelineDifferentActorRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();

        Node processed = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, "different-account", 1, "wrong actor")).document();

        assertNull(checkpointEvent(processed));
    }

    @Test
    void sameActorDifferentTimelineRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();

        Node processed = process(fixture, initializedDocument(fixture),
                event(fixture, "different-timeline", ACTOR, 1, "wrong timeline")).document();

        assertNull(checkpointEvent(processed));
    }

    @Test
    void pureReferenceEqualsEquivalentMaterializedBinding() {
        Fixture fixture = configuredFixture();
        Node timeline = fixture.blue.objectToNode(new Timeline().timelineId(TIMELINE));
        Node actor = fixture.blue.objectToNode(new PrincipalActor().accountId(ACTOR));

        Node timelineReference = new Node().blueId(fixture.blue.calculateSemanticBlueId(timeline));
        Node actorReference = new Node().blueId(fixture.blue.calculateSemanticBlueId(actor));

        assertTrue(BlueSemanticIdentity.equals(timelineReference, timeline));
        assertTrue(BlueSemanticIdentity.equals(actorReference, actor));
    }

    @Test
    void completedAndMinimalMaterializedBindingsAreEqual() {
        Fixture fixture = configuredFixture();
        Node minimalEntry = event(fixture, TIMELINE, ACTOR, 1, "entry");
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
    void sequenceFreeTimelineEntryMatchesAndCheckpoints() {
        Fixture fixture = configuredFixture();
        Node entry = event(fixture, TIMELINE, ACTOR, 100, "sequence-free");

        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), entry);

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertNotNull(checkpointEvent(result.document()));
        assertEquals(BigInteger.valueOf(100), checkpointEvent(result.document()).get("/timestamp"));
        assertFalse(checkpointEvent(result.document()).getProperties().containsKey("sequence"));
    }

    @Test
    void firstValidTimestampIsAccepted() {
        Fixture fixture = configuredFixture();
        BigInteger firstTimestamp = new BigInteger("-92233720368547758081234567890");

        DocumentProcessingResult result = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, firstTimestamp, "first"));

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertEquals(1, result.triggeredEvents().size());
        assertEquals(firstTimestamp, checkpointEvent(result.document()).get("/timestamp"));
    }

    @Test
    void higherTimestampLowerProviderSequenceAccepts() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                providerSequencedEvent(fixture, 10, 100, "first")).document();

        DocumentProcessingResult result = process(fixture, first,
                providerSequencedEvent(fixture, 1, 101, "second"));

        assertEquals("second", checkpointEvent(result.document()).getAsText("/message/message"));
        assertEquals(BigInteger.valueOf(101), checkpointEvent(result.document()).get("/timestamp"));
        assertEquals(BigInteger.ONE, checkpointEvent(result.document()).get("/sequence"));
    }

    @Test
    void lowerTimestampHigherProviderSequenceRejectsWithoutEffectsOrCheckpointMutation() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, observingDocument(fixture),
                providerSequencedEvent(fixture, 1, 100, "first")).document();
        Node checkpointBefore = checkpointEvent(first).clone();

        DocumentProcessingResult result = process(fixture, first,
                providerSequencedEvent(fixture, 2, 99, "stale"));

        assertTrue(result.triggeredEvents().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(result.document())));
    }

    @Test
    void equalTimestampHigherProviderSequenceRejectsAsEquivocationWithoutMutation() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, observingDocument(fixture),
                providerSequencedEvent(fixture, 1, 100, "first")).document();
        Node checkpointBefore = checkpointEvent(first).clone();

        DocumentProcessingResult result = process(fixture, first,
                providerSequencedEvent(fixture, 2, 100, "different"));

        assertTrue(result.triggeredEvents().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(result.document())));
    }

    @Test
    void higherTimestampAcceptsWithGaps() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document();

        Node second = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 1_000_000, "second")).document();

        assertEquals(BigInteger.valueOf(1_000_000), checkpointEvent(second).get("/timestamp"));
        assertEquals("second", checkpointEvent(second).getAsText("/message/message"));
    }

    @Test
    void lowerTimestampRejectsWithoutEffectsOrCheckpointMutation() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document();
        Node checkpointBefore = checkpointEvent(first).clone();

        DocumentProcessingResult stale = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 99, "stale"));

        assertTrue(stale.triggeredEvents().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(stale.document())));
    }

    @Test
    void sameTimelineReferenceAndMaterializedFormsCompareTogether() {
        Fixture fixture = configuredFixture();
        Node previous = event(fixture, TIMELINE, ACTOR, 100, "first");
        Node checkpointTimeline = previous.getAsNode("/timeline");
        previous.getProperties().put("timeline",
                new Node().blueId(fixture.blue.calculateSemanticBlueId(checkpointTimeline)));
        Node current = event(fixture, TIMELINE, ACTOR, 101, "next");

        assertTrue(TimelineProviderSupport.isNewerOrSameTimelineEvent(
                ChannelCheckpointContext.of("/", "ownerChannel", current, "current",
                        previous, "previous", Collections.<String, MarkerContract>emptyMap())));
    }

    @Test
    void exactEventReplayDoesNotRunHandlersAgain() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("replayObserver", replayObserver());
        Node event = event(fixture, TIMELINE, ACTOR, 100, "same");
        DocumentProcessingResult first = process(fixture, initializedDocument(fixture, contracts), event);
        Node checkpointBefore = checkpointEvent(first.document()).clone();

        DocumentProcessingResult replay = process(fixture, first.document(), event.clone());

        assertEquals(1, first.triggeredEvents().size());
        assertEquals("handled once", first.triggeredEvents().get(0).getAsText("/message"));
        assertTrue(replay.triggeredEvents().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(replay.document())));
    }

    @Test
    void equalTimestampDifferentContentRejectsAsProviderEquivocation() {
        Fixture fixture = configuredFixture();
        Node first = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document();
        Node checkpointBefore = checkpointEvent(first).clone();

        DocumentProcessingResult equivocation = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 100, "different"));

        assertTrue(equivocation.triggeredEvents().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(equivocation.document())));
    }

    @Test
    void timestampBeyondLongRangeRemainsExact() {
        Fixture fixture = configuredFixture();
        BigInteger firstTimestamp = new BigInteger("9223372036854775808123456789");
        BigInteger secondTimestamp = firstTimestamp.add(BigInteger.ONE);
        Node firstEvent = event(fixture, TIMELINE, ACTOR, firstTimestamp, "first");
        assertEquals(firstTimestamp, firstEvent.get("/timestamp"));
        assertNotNull(CoordinationEventNodes.timelineEntry(firstEvent));
        DocumentProcessingResult firstResult = process(fixture, initializedDocument(fixture),
                firstEvent);
        assertNotNull(checkpointEvent(firstResult.document()), firstResult.failureReason());

        DocumentProcessingResult secondResult = process(fixture, firstResult.document(),
                event(fixture, TIMELINE, ACTOR, secondTimestamp, "second"));
        assertNotNull(checkpointEvent(secondResult.document()), secondResult.failureReason());

        assertEquals(secondTimestamp, checkpointEvent(secondResult.document()).get("/timestamp"));
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
    void missingTimestampRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("timestamp");
    }

    @Test
    void invalidTimestampRejectsWithoutCheckpoint() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        invalid.getProperties().put("timestamp", new Node().value("1"));

        assertRejected(fixture, invalid);
    }

    @Test
    void decimalTimestampRejectsWithoutTruncation() {
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        invalid.getProperties().put("timestamp", new Node().value(new BigDecimal("1.5")));

        assertRejected(fixture, invalid);
    }

    @Test
    void malformedPreviousCheckpointFailsClosedWithoutEffectsOrMutation() {
        Fixture fixture = configuredFixture();
        Node malformed = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document().clone();
        checkpointEvent(malformed).getProperties().remove("timestamp");
        Node checkpointBefore = checkpointEvent(malformed).clone();

        DocumentProcessingResult result = process(fixture, malformed,
                event(fixture, TIMELINE, ACTOR, 101, "next"));

        assertTrue(result.triggeredEvents().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(result.document())));
    }

    @Test
    void missingMessageRejectsWithoutCheckpoint() {
        assertMissingFieldRejects("message");
    }

    @Test
    void optionalSourceSurvivesDeliveryUnchanged() {
        Fixture fixture = configuredFixture();
        TimelineEntry attributed = baseEntry(fixture, BigInteger.ONE, "source")
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
                        .type(PrincipalActor.qualifiedName())
                        .properties("accountId", new Node().value("represented-account")))
                .properties("mandate", authorityMandate());
        Node event = fixture.blue.preprocess(fixture.blue.objectToNode(
                        baseEntry(fixture, BigInteger.ONE, "authority"))
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
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        invalid.getProperties().remove(field);
        assertRejected(fixture, invalid);
    }

    private static void assertRejected(Fixture fixture, Node event) {
        DocumentProcessingResult result = process(fixture, observingDocument(fixture), event);
        assertTrue(result.triggeredEvents().isEmpty());
        assertNull(checkpointEvent(result.document()), result.failureReason());
    }

    private static Node initializedDocument(Fixture fixture) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel(TIMELINE, ACTOR));
        return initializedDocument(fixture, contracts);
    }

    private static Node observingDocument(Fixture fixture) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("observer", replayObserver());
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
                              long timestamp,
                              String message) {
        return event(fixture,
                timelineId,
                actorId,
                BigInteger.valueOf(timestamp),
                message);
    }

    private static Node event(Fixture fixture,
                              String timelineId,
                              String actorId,
                              BigInteger timestamp,
                              String message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                timelineId,
                actorId,
                timestamp,
                TestTimelineProvider.chatMessage(message));
    }

    private static Node providerSequencedEvent(Fixture fixture,
                                               long providerSequence,
                                               long timestamp,
                                               String message) {
        return TestTimelineProvider.timelineEntryWithProviderSequence(fixture.blue,
                fixture.repository,
                TIMELINE,
                ACTOR,
                BigInteger.valueOf(providerSequence),
                BigInteger.valueOf(timestamp),
                TestTimelineProvider.chatMessage(message));
    }

    private static TimelineEntry baseEntry(Fixture fixture,
                                           BigInteger timestamp,
                                           String message) {
        return new TimelineEntry()
                .timeline(new Timeline().timelineId(TIMELINE))
                .actor(new PrincipalActor().accountId(ACTOR))
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
