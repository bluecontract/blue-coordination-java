package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.APICall;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateAuthority;
import blue.repo.myos.PrincipalActor;
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
    void shouldEnsureThatMatchingTimelineAndActorAccept() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture);

        // When
        Node processed = process(fixture, document,
                event(fixture, TIMELINE, ACTOR, 100, "hello")).document();

        // Then
        assertDirectCheckpointSubject(
                checkpointEvent(processed), BigInteger.valueOf(100));
    }

    @Test
    void shouldEnsureThatRecognizedTimelineEntriesUseTheConservativePreselectionKey() {
        // Given
        Fixture fixture = configuredFixture();
        TimelineChannel contract = fixture.blue.nodeToObject(
                TestTimelineProvider.channel(TIMELINE, ACTOR),
                TimelineChannel.class);
        Node accepted = event(fixture, TIMELINE, ACTOR, 100, "accepted");
        // When
        Node rejected = event(
                fixture, "different-timeline", ACTOR, 100, "rejected");

        // Then
        assertTrue(TimelineExternalSubscriptionFunctions.INSTANCE
                .channelKeys(contract).containsAll(
                        TimelineExternalSubscriptionFunctions.INSTANCE
                                .eventKeys(accepted)));
        assertEquals(
                TimelineExternalSubscriptionFunctions.INSTANCE
                        .eventKeys(accepted),
                TimelineExternalSubscriptionFunctions.INSTANCE
                        .eventKeys(rejected));
    }

    @Test
    void shouldEnsureThatUnrelatedTypedLookalikeRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture);
        Node event = new Node()
                .type(ChatMessage.repositoryType().reference())
                .properties("timeline", new Node().blueId("not-a-blue-id"))
                .properties("actor", new Node().blueId("not-a-blue-id"))
                .properties("timestamp", new Node().value(1))
                .properties("message", TestTimelineProvider.chatMessage("lookalike"));

        // When
        DocumentProcessingResult result = process(fixture, document, event);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNull(checkpointEvent(result.document()));
    }

    @Test
    void shouldEnsureThatUntypedTimelineLookalikeRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture);
        Node event = new Node()
                .properties("timeline", new Node().blueId("not-a-blue-id"))
                .properties("actor", new Node().blueId("not-a-blue-id"))
                .properties("timestamp", new Node().value(1))
                .properties("message", TestTimelineProvider.chatMessage("lookalike"));

        // When
        DocumentProcessingResult result = process(fixture, document, event);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNull(checkpointEvent(result.document()));
    }

    @Test
    void shouldEnsureThatInvalidTimelineEntryReferenceFailsDeterministically() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        invalid.getProperties().put("timeline", new Node().blueId("not-a-blue-id"));
        TimelineChannel contract = fixture.blue.nodeToObject(
                TestTimelineProvider.channel(TIMELINE, ACTOR),
                TimelineChannel.class);

        // When
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TimelineExternalSubscriptionFunctions.INSTANCE
                        .accepts(contract, invalid));

        // Then
        assertTrue(failure.getMessage().contains("Semantic identity reference"), failure.getMessage());
    }

    @Test
    void shouldEnsureThatSameTimelineDifferentActorRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        Node processed = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, "different-account", 1, "wrong actor")).document();

        // Then
        assertNull(checkpointEvent(processed));
    }

    @Test
    void shouldEnsureThatSameActorDifferentTimelineRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        Node processed = process(fixture, initializedDocument(fixture),
                event(fixture, "different-timeline", ACTOR, 1, "wrong timeline")).document();

        // Then
        assertNull(checkpointEvent(processed));
    }

    @Test
    void shouldEnsureThatPureReferenceEqualsEquivalentMaterializedBinding() {
        // Given
        Fixture fixture = configuredFixture();
        Node timeline = fixture.blue.objectToNode(new Timeline().timelineId(TIMELINE));
        Node actor = fixture.blue.objectToNode(new PrincipalActor().accountId(ACTOR));

        Node timelineReference = new Node().blueId(fixture.blue.calculateSemanticBlueId(timeline));
        // When
        Node actorReference = new Node().blueId(fixture.blue.calculateSemanticBlueId(actor));

        // Then
        assertTrue(BlueSemanticIdentity.equals(timelineReference, timeline));
        assertTrue(BlueSemanticIdentity.equals(actorReference, actor));
    }

    @Test
    void shouldEnsureThatCompletedAndMinimalMaterializedBindingsAreEqual() {
        // Given
        Fixture fixture = configuredFixture();
        Node minimalEntry = event(fixture, TIMELINE, ACTOR, 1, "entry");
        // When
        Node completedEntry = fixture.blue.resolve(minimalEntry.clone());

        // Then
        assertTrue(BlueSemanticIdentity.equals(
                minimalEntry.getAsNode("/timeline"), completedEntry.getAsNode("/timeline")));
        assertTrue(BlueSemanticIdentity.equals(
                minimalEntry.getAsNode("/actor"), completedEntry.getAsNode("/actor")));
    }

    @Test
    void shouldEnsureThatSameTypeDifferentContentDoesNotEqual() {
        // Given
        Fixture fixture = configuredFixture();
        Node first = fixture.blue.objectToNode(new Timeline().timelineId("first"));
        // When
        Node second = fixture.blue.objectToNode(new Timeline().timelineId("second"));

        // Then
        assertFalse(BlueSemanticIdentity.equals(first, second));
    }

    @Test
    void shouldAcceptFixedTimelineEntryWithoutInventedSequence() {
        // Given
        Fixture fixture = configuredFixture();
        Node entry = event(fixture, TIMELINE, ACTOR, 100, "fixed-shape");

        // When
        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), entry);

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertDirectCheckpointSubject(
                checkpointEvent(result.document()), BigInteger.valueOf(100));
    }

    @Test
    void shouldEnsureThatFirstValidTimestampIsAccepted() {
        // Given
        Fixture fixture = configuredFixture();
        BigInteger firstTimestamp = new BigInteger("-92233720368547758081234567890");

        // When
        DocumentProcessingResult result = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, firstTimestamp, "first"));

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(1, result.events().size());
        assertEquals(firstTimestamp, checkpointEvent(result.document()).get("/timestamp"));
    }

    @Test
    void shouldEnsureThatHigherTimestampAcceptsWithGaps() {
        // Given
        Fixture fixture = configuredFixture();
        Node first = process(fixture, initializedDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document();

        // When
        Node second = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 1_000_000, "second")).document();

        // Then
        assertDirectCheckpointSubject(
                checkpointEvent(second), BigInteger.valueOf(1_000_000));
    }

    @Test
    void shouldEnsureThatLowerTimestampRejectsWithoutEffectsOrCheckpointMutation() {
        // Given
        Fixture fixture = configuredFixture();
        Node first = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document();
        Node checkpointBefore = checkpointEvent(first).clone();

        // When
        DocumentProcessingResult stale = process(fixture, first,
                event(fixture, TIMELINE, ACTOR, 99, "stale"));

        // Then
        assertTrue(stale.events().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(stale.document())));
    }

    @Test
    void shouldEnsureThatSameTimelineReferenceAndMaterializedFormsAcceptTogether() {
        // Given
        Fixture fixture = configuredFixture();
        Node referenced = event(fixture, TIMELINE, ACTOR, 100, "first");
        Node timeline = referenced.getAsNode("/timeline");
        referenced.getProperties().put("timeline",
                new Node().blueId(fixture.blue.calculateSemanticBlueId(timeline)));
        Node materialized = event(fixture, TIMELINE, ACTOR, 101, "next");
        // When
        TimelineChannel contract = fixture.blue.nodeToObject(
                TestTimelineProvider.channel(TIMELINE, ACTOR),
                TimelineChannel.class);

        // Then
        assertTrue(TimelineExternalSubscriptionFunctions.INSTANCE
                .accepts(contract, referenced));
        assertTrue(TimelineExternalSubscriptionFunctions.INSTANCE
                .accepts(contract, materialized));
    }

    @Test
    void shouldEnsureThatUnrelatedValidPureReferencesDoNotCompareEqual() {
        // Given
        Node expected = new Node().blueId(
                blue.language.utils.BlueIdCalculator.calculateBlueId(
                        new Node().value("expected-timeline")));
        // When
        Node unrelated = new Node().blueId(
                blue.language.utils.BlueIdCalculator.calculateBlueId(
                        new Node().value("unrelated-timeline")));

        // Then
        assertFalse(BlueSemanticIdentity.equals(
                unrelated, expected));
    }

    @Test
    void shouldEnsureThatExactEventReplayDoesNotRunHandlersAgain() {
        // Given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("replayObserver", replayObserver());
        Node event = event(fixture, TIMELINE, ACTOR, 100, "same");
        DocumentProcessingResult first = process(fixture, initializedDocument(fixture, contracts), event);
        Node checkpointBefore = checkpointEvent(first.document()).clone();

        // When
        DocumentProcessingResult replay = process(fixture, first.document(), event.clone());

        // Then
        assertEquals(1, first.events().size());
        assertEquals("handled once", first.events().get(0).getAsText("/message"));
        assertTrue(replay.events().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(replay.document())));
    }

    @Test
    void shouldRejectDistinctEntryAtEqualTimestampWithoutCheckpointMutation() {
        // Given
        Fixture fixture = configuredFixture();
        Node firstEvent =
                event(fixture, TIMELINE, ACTOR, 100, "first");
        Node equalTimestampEvent =
                event(fixture, TIMELINE, ACTOR, 100, "second");
        Node first = process(fixture,
                observingDocument(fixture),
                firstEvent).document();
        Node checkpointBefore = checkpointEvent(first).clone();

        // When
        DocumentProcessingResult result =
                process(fixture, first, equalTimestampEvent);

        // Then
        assertTrue(result.events().isEmpty());
        assertEquals(
                fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(
                        checkpointEvent(result.document())));
    }

    @Test
    void shouldEnsureThatTimestampBeyondLongRangeRemainsExact() {
        // Given
        Fixture fixture = configuredFixture();
        BigInteger firstTimestamp = new BigInteger("9223372036854775808123456789");
        BigInteger secondTimestamp = firstTimestamp.add(BigInteger.ONE);
        // When
        Node firstEvent = event(fixture, TIMELINE, ACTOR, firstTimestamp, "first");
        // Then
        assertEquals(firstTimestamp, firstEvent.get("/timestamp"));
        assertNotNull(CoordinationEventNodes.timelineEntry(firstEvent));
        DocumentProcessingResult firstResult = process(fixture, initializedDocument(fixture),
                firstEvent);
        assertNotNull(checkpointEvent(firstResult.document()), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(firstResult));

        DocumentProcessingResult secondResult = process(fixture, firstResult.document(),
                event(fixture, TIMELINE, ACTOR, secondTimestamp, "second"));
        assertNotNull(checkpointEvent(secondResult.document()), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(secondResult));

        assertEquals(secondTimestamp, checkpointEvent(secondResult.document()).get("/timestamp"));
    }

    @Test
    void shouldEnsureThatMissingTimelineRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(
                fixture, TIMELINE, ACTOR, 1, "invalid");

        // When
        invalid.getProperties().remove("timeline");

        // Then
        assertRejected(fixture, invalid);
    }

    @Test
    void shouldEnsureThatMissingActorRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(
                fixture, TIMELINE, ACTOR, 1, "invalid");

        // When
        invalid.getProperties().remove("actor");

        // Then
        assertRejected(fixture, invalid);
    }

    @Test
    void shouldEnsureThatMissingTimestampRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(
                fixture, TIMELINE, ACTOR, 1, "invalid");

        // When
        invalid.getProperties().remove("timestamp");

        // Then
        assertRejected(fixture, invalid);
    }

    @Test
    void shouldEnsureThatInvalidTimestampRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        // When
        invalid.getProperties().put("timestamp", new Node().value("1"));

        // Then
        assertRejected(fixture, invalid);
    }

    @Test
    void shouldEnsureThatDecimalTimestampRejectsWithoutTruncation() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(fixture, TIMELINE, ACTOR, 1, "invalid");
        // When
        invalid.getProperties().put("timestamp", new Node().value(new BigDecimal("1.5")));

        // Then
        assertRejected(fixture, invalid);
    }

    @Test
    void shouldEnsureThatMalformedPreviousCheckpointFailsClosedWithoutEffectsOrMutation() {
        // Given
        Fixture fixture = configuredFixture();
        Node malformed = process(fixture, observingDocument(fixture),
                event(fixture, TIMELINE, ACTOR, 100, "first")).document().clone();
        checkpointEvent(malformed).getProperties().remove("timestamp");
        Node checkpointBefore = checkpointEvent(malformed).clone();

        // When
        DocumentProcessingResult result = process(fixture, malformed,
                event(fixture, TIMELINE, ACTOR, 101, "next"));

        // Then
        assertTrue(result.events().isEmpty());
        assertEquals(fixture.blue.calculateBlueId(checkpointBefore),
                fixture.blue.calculateBlueId(checkpointEvent(result.document())));
    }

    @Test
    void shouldEnsureThatMissingMessageRejectsWithoutCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node invalid = event(
                fixture, TIMELINE, ACTOR, 1, "invalid");

        // When
        invalid.getProperties().remove("message");

        // Then
        assertRejected(fixture, invalid);
    }

    @Test
    void shouldEnsureThatOptionalSourceDoesNotExpandCheckpointSubject() {
        // Given
        Fixture fixture = configuredFixture();
        TimelineEntry attributed = baseEntry(fixture, BigInteger.ONE, "source")
                .source(new APICall().apiKeyId("api-key-7"));

        Node event = fixture.blue.preprocess(fixture.blue.objectToNode(attributed)
                .blue(fixture.repository.typeAliasBlue())).blue(null);
        // When
        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), event);
        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertDirectCheckpointSubject(
                checkpointEvent(result.document()), BigInteger.ONE);
    }

    @Test
    void shouldEnsureThatOptionalOnBehalfOfDoesNotExpandCheckpointSubject() {
        // Given
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
        // When
        DocumentProcessingResult result = process(fixture, initializedDocument(fixture), event);
        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertDirectCheckpointSubject(
                checkpointEvent(result.document()), BigInteger.ONE);
    }

    private static void assertRejected(Fixture fixture, Node event) {
        DocumentProcessingResult result = process(fixture, observingDocument(fixture), event);
        assertTrue(result.events().isEmpty());
        assertNull(checkpointEvent(result.document()), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
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
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNotNull(ProcessingResultTestSupport.snapshot(fixture.blue, result));
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
        return nodeAt(document,
                "/contracts/checkpoint/entries/ownerChannel/subject");
    }

    private static void assertDirectCheckpointSubject(
            Node subject,
            BigInteger timestamp) {
        assertNotNull(subject);
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertNotNull(subject.getAsText("/timelineBlueId"));
        assertNotNull(subject.getAsText("/entryBlueId"));
        assertNull(TimelineProviderSupport.property(
                subject, "sequence"));
        assertNull(nodeAt(subject, "/timeline"));
        assertNull(nodeAt(subject, "/actor"));
        assertNull(nodeAt(subject, "/message"));
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
