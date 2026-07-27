package blue.coordination.processor;

import blue.coordination.processor.CoordinationProcessors;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.StatusCompleted;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerEventStepExecutorTest {

    @Test
    void emitsStaticEventPayload() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, directWorkflowDocument(fixture.repository,
                0,
                triggerEventStep(chatMessageEvent("Hello World"))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(1, result.events().size());
        assertEventType(result.events().get(0), ChatMessage.qualifiedName(), ChatMessage.blueId());
        assertEquals("Hello World", result.events().get(0).get("/message"));
    }

    @Test
    void staticPayloadPreservesNonStringValues() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, directWorkflowDocument(fixture.repository,
                1,
                triggerEventStep(new Node()
                        .type("Coordination/Event")
                        .properties("amount", new Node().value(2)))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(BigInteger.valueOf(2), result.events().get(0).get("/amount"));
    }

    @Test
    void dollarPrefixedLiteralPayloadIsEmittedExactly() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, directWorkflowDocument(fixture.repository,
                0,
                triggerEventStep(new Node().properties("$document", new Node().value("/counter")))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(1, result.events().size());
        assertEquals("/counter", result.events().get(0).get("/$document"));
    }

    @Test
    void missingEventFailsClearly() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, directWorkflowDocument(fixture.repository,
                0,
                new Node().type("Coordination/Trigger Event")));

        DocumentProcessingResult result = processChat(fixture, document);

        assertRuntimeFatal(result, "Trigger Event step must declare event payload");
    }

    @Test
    void namedEventOnlyRemainsAnExactIdentityBearingPayload() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, directWorkflowDocument(fixture.repository,
                0,
                triggerEventStep(new Node().name("Named Event Only"))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(1, result.events().size());
        assertEquals("Named Event Only", result.events().get(0).getName());
    }

    @Test
    void emptyListEventRemainsAnExactListPayload() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(
                fixture,
                directWorkflowDocument(
                        fixture.repository,
                        0,
                        triggerEventStep(
                                new Node().items(
                                        Collections.<Node>emptyList()))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(1, result.events().size());
        assertTrue(result.events().get(0).getItems().isEmpty());
    }

    @Test
    void emptyObjectEventRemainsAnExactOccurrence() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(
                fixture,
                directWorkflowDocument(
                        fixture.repository,
                        0,
                        triggerEventStep(
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(1, result.events().size());
        assertTrue(result.events().get(0).getProperties() == null
                || result.events().get(0).getProperties().isEmpty());
    }

    @Test
    void emittedEventIsDeliveredToRuntimeTriggeredChannel() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, triggeredConsumerDocument(fixture.repository));

        DocumentProcessingResult result = processChat(fixture, document);

        assertContainsEventType(result.events(), StatusCompleted.qualifiedName(), StatusCompleted.blueId());
        assertContainsChatMessage(result.events(), "Triggered consumer ran");
    }

    @Test
    void lifecycleProducerCanTriggerConsumer() {
        Fixture fixture = configuredFixture();

        DocumentProcessingResult result = fixture.blue.initializeDocument(
                fixture.blue.preprocess(lifecycleProducerDocument(fixture.repository)));

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertContainsEventType(result.events(), StatusCompleted.qualifiedName(), StatusCompleted.blueId());
        assertContainsChatMessage(result.events(), "Init triggered consumer");
    }

    @Test
    void triggerEventDoesNotMutateDocumentState() {
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, directWorkflowDocument(fixture.repository,
                9,
                triggerEventStep(chatMessageEvent("state is external"))));

        DocumentProcessingResult result = processChat(fixture, document);

        assertEquals(BigInteger.valueOf(9), result.document().get("/counter"));
        assertTriggeredChatMessage(result, "state is external");
    }

    private static Node directWorkflowDocument(BlueRepository repository, int counter, Node... steps) {
        return directWorkflowDocument(repository, counter, null, steps);
    }

    private static Node directWorkflowDocument(BlueRepository repository,
                                               int counter,
                                               String description,
                                               Node... steps) {
        Map<String, Node> contracts = ownerChannelContracts();
        Node workflow = new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("ownerChannel"))
                .properties("steps", new Node().items(steps));
        if (description != null) {
            workflow.description(description);
        }
        contracts.put("direct", workflow);
        return document(repository, counter, contracts);
    }

    private static Node triggeredConsumerDocument(BlueRepository repository) {
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("triggered", new Node()
                .type("Triggered Event Channel"));
        contracts.put("producer", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("ownerChannel"))
                .properties("steps", new Node().items(
                        triggerEventStep(new Node().type("Coordination/Status Completed")))));
        contracts.put("consumer", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("triggered"))
                .properties("event", new Node().type("Coordination/Status Completed"))
                .properties("steps", new Node().items(
                        triggerEventStep(chatMessageEvent("Triggered consumer ran")))));
        return document(repository, 0, contracts);
    }

    private static Node lifecycleProducerDocument(BlueRepository repository) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("life", new Node()
                .type("Lifecycle Event Channel"));
        contracts.put("triggered", new Node()
                .type("Triggered Event Channel"));
        contracts.put("onInit", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("life"))
                .properties("event", new Node().type("Document Processing Initiated"))
                .properties("steps", new Node().items(
                        triggerEventStep(new Node().type("Coordination/Status Completed")))));
        contracts.put("consumer", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("triggered"))
                .properties("event", new Node().type("Coordination/Status Completed"))
                .properties("steps", new Node().items(
                        triggerEventStep(chatMessageEvent("Init triggered consumer")))));
        return document(repository, 0, contracts);
    }

    private static Map<String, Node> ownerChannelContracts() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel("owner"));
        return contracts;
    }

    private static Node triggerEventStep(Node event) {
        return new Node()
                .type("Coordination/Trigger Event")
                .properties("event", event);
    }

    private static Node chatMessageEvent(String message) {
        return chatMessageEvent(new Node().value(message));
    }

    private static Node chatMessageEvent(Node message) {
        return new Node()
                .type("Coordination/Chat Message")
                .properties("message", message);
    }

    private static Node document(BlueRepository repository, int counter, Map<String, Node> contracts) {
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Trigger Event Test")
                .properties("counter", new Node().value(counter))
                .properties("contracts", new Node().properties(contracts));
    }

    private static DocumentProcessingResult processChat(Fixture fixture, Node document) {
        return fixture.blue.processDocument(document, chatTimelineEntry(fixture));
    }

    private static Node chatTimelineEntry(Fixture fixture) {
        return TestTimelineProvider.timelineEntry(
                fixture.blue, fixture.repository, "owner", 1, chatMessageEvent("run"));
    }

    private static Node initializedDocument(Fixture fixture, Node document) {
        return fixture.blue.initializeDocument(fixture.blue.preprocess(document)).document();
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static void assertTriggeredChatMessage(DocumentProcessingResult result, String expectedMessage) {
        assertEquals(1, result.events().size());
        assertContainsChatMessage(result.events(), expectedMessage);
    }

    private static void assertContainsChatMessage(List<Node> events, String expectedMessage) {
        for (Node event : events) {
            if (isEventType(event, ChatMessage.qualifiedName(), ChatMessage.blueId())
                    && expectedMessage.equals(event.get("/message"))) {
                return;
            }
        }
        assertFalse(true, "Expected triggered chat message: "
                + expectedMessage + " in " + events);
    }

    private static void assertContainsEventType(List<Node> events, String qualifiedName, String blueId) {
        for (Node event : events) {
            if (isEventType(event, qualifiedName, blueId)) {
                return;
            }
        }
        assertFalse(true, "Expected triggered event type: "
                + qualifiedName + " in " + events);
    }

    private static void assertEventType(Node event, String qualifiedName, String blueId) {
        assertTrue(isEventType(event, qualifiedName, blueId),
                "Expected event type " + qualifiedName + " but was " + event);
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result, String expectedMessage) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result) != null && blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(expectedMessage),
                blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static boolean isEventType(Node event, String qualifiedName, String blueId) {
        if (event == null) {
            return false;
        }
        Node type = event.getType();
        if (type != null) {
            if (qualifiedName.equals(type.getValue())) {
                return true;
            }
            if (blueId.equals(type.getBlueId())) {
                return true;
            }
        }
        if (event.getProperties() == null) {
            return false;
        }
        Node typeProperty = event.getProperties().get("type");
        Object value = typeProperty != null ? typeProperty.getValue() : null;
        if (qualifiedName.equals(value)) {
            return true;
        }
        int slash = qualifiedName.indexOf('/');
        String localName = slash >= 0 ? qualifiedName.substring(slash + 1) : qualifiedName;
        return localName.equals(value);
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
