package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.Compute;
import blue.repo.coordination.TerminateProcessing;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executable coverage for the fixed Repository Chat Workflow Operation.
 */
final class ChatWorkflowOperationIntegrationTest {

    @Test
    void shouldEmitSeededChatMessageBeforeAppendedWorkflowEvent() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(
                fixture,
                chatDocument(
                        fixture.repository,
                        "hello",
                        appendedChatMessage("moderation-complete")));
        Node request = TestTimelineProvider.chatMessage("hello");
        Node event = CoordinationTestResources.operationRequestEvent(
                fixture.blue,
                fixture.repository,
                "alice",
                100,
                "chat",
                "alice",
                request);

        // When
        DocumentProcessingResult result =
                fixture.blue.processDocument(document, event);

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertChatMessages(
                result.events(),
                "hello",
                "moderation-complete");
    }

    @Test
    void shouldAdvanceSourceCheckpointOnceForRoutedChatRequest() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(
                fixture,
                chatDocument(fixture.repository, "hello"));
        Node event = CoordinationTestResources.operationRequestEvent(
                fixture.blue,
                fixture.repository,
                "alice",
                100,
                "chat",
                "alice",
                TestTimelineProvider.chatMessage("hello"));

        // When
        DocumentProcessingResult first =
                fixture.blue.processDocument(document, event);
        DocumentProcessingResult replay =
                fixture.blue.processDocument(
                        first.document(), event);

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                first.status(),
                ProcessingResultTestSupport.diagnosticMessage(first));
        assertEquals(
                BigInteger.valueOf(100),
                first.document().get(
                        "/contracts/checkpoint/entries/alice/subject/timestamp"));
        assertEquals(1, first.events().size());
        assertEquals(
                ProcessorStatus.STALE,
                replay.status(),
                ProcessingResultTestSupport.diagnosticMessage(replay));
        assertEquals(0, replay.events().size());
        assertEquals(
                BigInteger.valueOf(100),
                replay.document().get(
                        "/contracts/checkpoint/entries/alice/subject/timestamp"));
    }

    @Test
    void shouldTerminateAfterInheritedChatWorkflowPrefix() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(
                fixture,
                chatDocument(
                        fixture.repository,
                        "hello",
                        terminateProcessing("inherited-prefix-complete")));
        Node event = CoordinationTestResources.operationRequestEvent(
                fixture.blue,
                fixture.repository,
                "alice",
                100,
                "chat",
                "alice",
                TestTimelineProvider.chatMessage("hello"));

        // When
        DocumentProcessingResult result =
                fixture.blue.processDocument(document, event);

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(1, result.events().size());
        assertEquals(
                ChatMessage.blueId(),
                result.events().get(0).getType().getBlueId());
        assertEquals("hello", result.events().get(0).get("/message"));
        assertEquals(
                TerminateProcessing.blueId(),
                result.document().get("/contracts/terminated/cause"));
        assertEquals(
                "inherited-prefix-complete",
                result.document().get("/contracts/terminated/reason"));
    }

    private static Fixture configuredFixture() {
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                CoordinationTestResources.configuredBlue(
                        repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static Node initializedDocument(
            Fixture fixture,
            Node authored) {
        DocumentProcessingResult result =
                fixture.blue.initializeDocument(
                        fixture.blue.preprocess(authored));
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        return result.document();
    }

    private static Node chatDocument(
            BlueRepository repository,
            String acceptedMessage,
            Node... appendedSteps) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "alice",
                TestTimelineProvider.channel("alice"));
        Node workflow =
                new Node()
                        .type(ChatWorkflowOperation.qualifiedName())
                        .properties(
                                "channel",
                                new Node().value("alice"))
                        .properties(
                                "request",
                                TestTimelineProvider.chatMessage(
                                        acceptedMessage))
                        .properties(
                                "steps",
                                chatSteps(
                                        appendedSteps));
        contracts.put(
                "chat",
                workflow);
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Chat document")
                .properties(
                        "contracts",
                        new Node().properties(contracts));
    }

    private static Node chatSteps(
            Node... appendedSteps) {
        List<Node> steps = new ArrayList<Node>();
        steps.add(inheritedChatEmissionStep());
        for (Node appended : appendedSteps) {
            steps.add(appended.clone());
        }
        return new Node()
                .type(
                        new Node().blueId(
                                blue.language.utils.Properties
                                        .LIST_TYPE_BLUE_ID))
                .mergePolicy("append-only")
                .items(steps);
    }

    private static Node inheritedChatEmissionStep() {
        Node eventExpression =
                new Node()
                        .type(
                                new Node().blueId(
                                        blue.language.utils.Properties
                                                .TEXT_TYPE_BLUE_ID))
                        .value("/message/request");
        return new Node()
                .name("Emit Arrived Chat Event")
                .description(
                        "Emits the Chat Message payload from the "
                                + "arriving Operation Request.")
                .type(
                        new Node().blueId(
                                Compute.blueId()))
                .properties(
                        "do",
                        new Node().items(
                                new Node().properties(
                                        "$appendEvent",
                                        new Node().properties(
                                                "$event",
                                                eventExpression))));
    }

    private static Node appendedChatMessage(
            String message) {
        return new Node()
                .type("Coordination/Trigger Event")
                .properties(
                        "event",
                        new Node()
                                .type(
                                        ChatMessage.qualifiedName())
                                .properties(
                                        "message",
                                        new Node().value(message)));
    }

    private static Node terminateProcessing(
            String reason) {
        return new Node()
                .type(TerminateProcessing.qualifiedName())
                .properties(
                        "reason",
                        new Node().value(reason));
    }

    private static void assertChatMessages(
            List<Node> events,
            String... expectedMessages) {
        assertEquals(expectedMessages.length, events.size());
        for (int index = 0;
                index < expectedMessages.length;
                index++) {
            Node event = events.get(index);
            assertEquals(
                    ChatMessage.blueId(),
                    event.getType().getBlueId());
            assertEquals(
                    expectedMessages[index],
                    event.get("/message"));
        }
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
