package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeChannelsTest {

    @Test
    void shouldEnsureThatRuntimeDocumentUpdateChannelReceivesUpdateEvents() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("updates", documentUpdateChannel("/counter"));
        contracts.put("writer", directWorkflow("owner", updateDocumentStep("replace", "/counter", new Node().value(5))));
        contracts.put("observer", directWorkflowMatching("updates",
                new Node().type("Document Update"),
                computeAppendChatMessageStep(documentUpdateMessage())));
        Node document = initializedDocument(fixture, document(fixture.repository, 0, contracts));

        // when
        DocumentProcessingResult result = processChat(fixture, document, 1);

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(BigInteger.valueOf(5), result.document().get("/counter"));
        assertContainsChatMessage(result.events(), "updated /counter from 0 to 5");
    }

    @Test
    void shouldEnsureThatDocumentUpdateChannelPathFilteringUsesRepositoryTypes() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("counterUpdates", documentUpdateChannel("/counter"));
        contracts.put("nameUpdates", documentUpdateChannel("/name"));
        contracts.put("writer", directWorkflow("owner", updateDocumentStep("replace", "/counter", new Node().value(5))));
        contracts.put("counterObserver", directWorkflowMatching("counterUpdates",
                new Node().type("Document Update"),
                triggerEventStep(chatMessageEvent("counter updated"))));
        contracts.put("nameObserver", directWorkflowMatching("nameUpdates",
                new Node().type("Document Update"),
                triggerEventStep(chatMessageEvent("name updated"))));
        Node document = initializedDocument(fixture, document(fixture.repository, 0, contracts));

        // when
        DocumentProcessingResult result = processChat(fixture, document, 1);

        // then
        assertContainsChatMessage(result.events(), "counter updated");
        assertNoChatMessage(result.events(), "name updated");
    }

    @Test
    void shouldEnsureThatNestedUpdatesPropagateToParentWatchers() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("profileUpdates", documentUpdateChannel("/profile"));
        contracts.put("writer", directWorkflow("owner",
                updateDocumentStep("replace", "/profile/name", new Node().value("Ada"))));
        contracts.put("observer", directWorkflowMatching("profileUpdates",
                new Node().type("Document Update"),
                computeAppendChatMessageStep(documentUpdateMessage())));
        Node document = document(fixture.repository, 0, contracts)
                .properties("profile", new Node()
                        .properties("name", new Node().value("Grace")));
        Node initialized = initializedDocument(fixture, document);

        // when
        DocumentProcessingResult result = processChat(fixture, initialized, 1);

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals("Ada", result.document()
                .getProperties().get("profile")
                .getProperties().get("name")
                .getValue());
        assertContainsChatMessage(result.events(), "updated /profile/name from Grace to Ada");
    }

    @Test
    void shouldEnsureThatUpdateEventCanBeMatchedMoreSpecifically() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("allUpdates", documentUpdateChannel("/"));
        contracts.put("writer", directWorkflow("owner",
                updateDocumentStep("replace", "/counter", new Node().value(5)),
                updateDocumentStep("add", "/other", new Node().value(9))));
        contracts.put("observer", directWorkflowMatching("allUpdates",
                new Node()
                        .type("Document Update")
                        .properties("path", new Node().value("/counter"))
                        .properties("op", new Node().value("replace")),
                triggerEventStep(chatMessageEvent("specific replace"))));
        Node document = initializedDocument(fixture, document(fixture.repository, 0, contracts));

        // when
        DocumentProcessingResult result = processChat(fixture, document, 1);

        // then
        assertEquals(BigInteger.valueOf(5), result.document().get("/counter"));
        assertEquals(BigInteger.valueOf(9), result.document().get("/other"));
        assertSingleChatMessage(result.events(), "specific replace");
    }

    @Test
    void shouldEnsureThatEmbeddedChildProcessesExternalEventWithRealProcessEmbeddedType() {
        // given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, embeddedOperationDocument(fixture.repository));

        // when
        DocumentProcessingResult result = fixture.blue.processDocument(document,
                operationRequestEvent(fixture, 1, "increment", new Node().value(7)));

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(BigInteger.valueOf(100), result.document().get("/counter"));
        assertEquals(BigInteger.valueOf(7), result.document().get("/child/counter"));
    }

    @Test
    void shouldEnsureThatParentCannotPatchIntoEmbeddedScope() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("embedded", processEmbedded("/child"));
        contracts.put("writer", directWorkflow("owner",
                updateDocumentStep("replace", "/child/counter", new Node().value(99))));
        Node document = initializedDocument(fixture, document(fixture.repository, 0, contracts)
                .properties("child", childDocument(1, new LinkedHashMap<String, Node>())));
        String inputJson = fixture.blue.nodeToJson(document);

        // when
        DocumentProcessingResult result = processChat(fixture, document, 1);

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(inputJson, fixture.blue.nodeToJson(result.document()),
                "the boundary violation must roll back the complete invocation");
        assertEquals(BigInteger.valueOf(1), result.document().get("/child/counter"));
        assertTrue(result.events().isEmpty());
        assertNull(nodeAt(result.document(), "/contracts/terminated"));
    }

    @Test
    void shouldEnsureThatReplacingEmbeddedNodeCutsOffChildScopeWithinRun() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> childContracts = ownerChannelContracts();
        childContracts.put("probe", directWorkflow("owner",
                triggerEventStep(chatMessageEvent("pre-cutoff")),
                updateDocumentStep("replace", "/marker", new Node().value(1)),
                triggerEventStep(chatMessageEvent("post-cutoff"))));

        Map<String, Node> rootContracts = ownerChannelContracts();
        rootContracts.put("embedded", processEmbedded("/child"));
        rootContracts.put("childUpdates", documentUpdateChannel("/child/marker"));
        rootContracts.put("cutChild", directWorkflowMatching("childUpdates",
                new Node().type("Document Update"),
                updateDocumentStep("replace", "/child", new Node()
                        .name("Replacement Child")
                        .properties("counter", new Node().value(0)))));
        Node document = initializedDocument(fixture, document(fixture.repository, 0, rootContracts)
                .properties("child", childDocument(0, childContracts)));

        // when
        DocumentProcessingResult result = processChat(fixture, document, 1);

        // then
        assertEquals("Replacement Child", nodeAt(result.document(), "/child").getName());
        assertNull(nodeAt(result.document(), "/child/marker"));
        assertNoChatMessage(result.events(), "post-cutoff");
    }

    @Test
    void shouldEnsureThatEmbeddedNodeChannelBridgesConfiguredChildEmissions() {
        // given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, embeddedBridgeDocument(fixture.repository, "/child"));

        // when
        ProcessingDebugResult debug =
                processChatWithTrace(
                        fixture, document, 1);
        DocumentProcessingResult result =
                debug.processResult();

        // then
        boolean childHandlerExecuted = false;
        boolean childEventEnqueued = false;
        boolean rootObserverExecuted = false;
        for (ProcessingTraceRecord record :
                debug.trace().records()) {
            if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .HANDLER_EXECUTION) {
                childHandlerExecuted |= "/child".equals(
                        record.scopePath())
                        && "emit".equals(
                        record.contractKey());
                rootObserverExecuted |= "/".equals(
                        record.scopePath())
                        && "childObserver".equals(
                        record.contractKey());
            }
            if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .EVENT_ENQUEUED
                    && record.node() != null) {
                childEventEnqueued |= "child emitted"
                        .equals(
                                nodeValueAt(
                                        record.node(),
                                        "/message"));
            }
        }
        boolean parentObserved =
                containsChatMessage(
                        result.events(),
                        "parent saw child emitted");
        ExternalBlockerProbeAssertions.classify(
                "embedded-node-channel-bridge",
                "Language Embedded Node Channel bridge defect:",
                result.status() == ProcessorStatus.SUCCESS
                        && childHandlerExecuted
                        && childEventEnqueued
                        && !rootObserverExecuted
                        && !parentObserved,
                result.status() == ProcessorStatus.SUCCESS
                        && parentObserved,
                ExternalBlockerProbeAssertions
                        .resultTuple(result)
                        + ", childHandlerExecuted="
                        + childHandlerExecuted
                        + ", childEventEnqueued="
                        + childEventEnqueued
                        + ", rootObserverExecuted="
                        + rootObserverExecuted
                        + ", parentObserved="
                        + parentObserved);
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(
                containsChatMessage(
                        result.events(),
                        "parent saw child emitted"),
                "Language Embedded Node Channel bridge defect: "
                        + "the configured child emission did not reach "
                        + "the root observer");
        assertNoChatMessage(result.events(), "parent saw other child emitted");
    }

    @Test
    void shouldEnsureThatEmbeddedNodeChannelDoesNotBridgeWrongChildPath() {
        // given
        Fixture fixture = configuredFixture();
        Node document = initializedDocument(fixture, embeddedBridgeDocument(fixture.repository, "/missingChild"));

        // when
        DocumentProcessingResult result = processChat(fixture, document, 1);

        // then
        assertNoChatMessage(result.events(), "parent saw child emitted");
        assertNoChatMessage(result.events(), "parent saw other child emitted");
    }

    @Test
    void shouldEnsureThatDuplicateExternalEventsAreSkippedWithRealRepositoryChannelCheckpointShape() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        contracts.put("writer", directWorkflow("owner",
                computePatchStep("replace", "/counter", bexAdd(bexDocument("/counter"), new Node().value(1)))));
        Node initialized = initializedDocument(fixture, document(fixture.repository, 0, contracts));
        Node event = chatTimelineEntry(fixture, 1);

        Node afterFirst = fixture.blue.processDocument(initialized, event).document();
        // when
        Node afterSecond = fixture.blue.processDocument(afterFirst, event).document();

        // then
        assertEquals(BigInteger.ONE, afterSecond.get("/counter"));
        Node checkpoint = nodeAt(afterSecond, "/contracts/checkpoint");
        assertNotNull(checkpoint);
        assertNotNull(nodeAt(checkpoint, "/entries/owner/subject"));
    }

    @Test
    void shouldEnsureThatCheckpointDeclaredUnderWrongKeyFails() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        // when
        contracts.put("wrongCheckpoint", new Node().type("Channel Event Checkpoint"));

        // then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> fixture.blue.initializeDocument(fixture.blue.preprocess(document(fixture.repository, 0, contracts))));

        assertTrue(ex.getMessage().contains("Channel Event Checkpoint"));
    }

    @Test
    void shouldEnsureThatMultipleCheckpointMarkersInOneScopeFail() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = ownerChannelContracts();
        Node initialized = initializedDocument(fixture, document(fixture.repository, 0, contracts));
        initialized.getContracts().properties("checkpoint", new Node()
                .type(new Node().blueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                .properties("entries", new Node().properties(new LinkedHashMap<String, Node>())));
        initialized.getContracts().properties("extraCheckpoint", new Node()
                .type(new Node().blueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT)));

        // when
        DocumentProcessingResult result =
                fixture.blue.processDocument(
                        fixture.blue.preprocess(initialized),
                        chatTimelineEntry(fixture, 1));

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(
                ProcessingResultTestSupport
                        .diagnosticMessage(result)
                        .contains(
                                "Channel Event Checkpoint must use "
                                        + "reserved key 'checkpoint'"),
                ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static Node embeddedOperationDocument(BlueRepository repository) {
        Map<String, Node> childContracts = ownerChannelContracts();
        childContracts.put("increment", sequentialWorkflowOperation("owner",
                computePatchStep("replace", "/counter",
                        bexAdd(bexBinding("event", "/message/request"), bexDocument("/counter")))));

        Map<String, Node> rootContracts = new LinkedHashMap<String, Node>();
        rootContracts.put("embedded", processEmbedded("/child"));
        return document(repository, 100, rootContracts)
                .properties("child", childDocument(0, childContracts));
    }

    private static Node embeddedBridgeDocument(BlueRepository repository, String sourcePath) {
        Map<String, Node> childContracts = ownerChannelContracts();
        childContracts.put("emit", directWorkflow("owner", triggerEventStep(chatMessageEvent("child emitted"))));

        Map<String, Node> otherChildContracts = ownerChannelContracts();
        otherChildContracts.put("emit", directWorkflow("owner", triggerEventStep(chatMessageEvent("other child emitted"))));

        Map<String, Node> rootContracts = ownerChannelContracts();
        rootContracts.put("embedded", new Node()
                .type("Process Embedded")
                .properties("paths", new Node().items(
                        new Node().value("/child"),
                        new Node().value("/otherChild"))));
        rootContracts.put("embeddedEvents", new Node()
                .type("Embedded Node Channel")
                .properties("sourcePath", new Node().value(sourcePath)));
        rootContracts.put("childObserver", directWorkflowMatching("embeddedEvents",
                new Node()
                        .type("Coordination/Chat Message")
                        .properties("message", new Node().value("child emitted")),
                triggerEventStep(chatMessageEvent("parent saw child emitted"))));
        rootContracts.put("otherChildObserver", directWorkflowMatching("embeddedEvents",
                new Node()
                        .type("Coordination/Chat Message")
                        .properties("message", new Node().value("other child emitted")),
                triggerEventStep(chatMessageEvent("parent saw other child emitted"))));
        return document(repository, 0, rootContracts)
                .properties("child", childDocument(0, childContracts))
                .properties("otherChild", childDocument(0, otherChildContracts));
    }

    private static Map<String, Node> ownerChannelContracts() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("owner", TestTimelineProvider.channel("owner"));
        return contracts;
    }

    private static Node documentUpdateChannel(String path) {
        return new Node()
                .type("Document Update Channel")
                .properties("path", new Node().value(path));
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type("Process Embedded")
                .properties("paths", new Node().items(new Node().value(path)));
    }

    private static Node sequentialWorkflowOperation(String channel, Node... steps) {
        return new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value(channel))
                .properties("request", new Node().type("Integer"))
                .properties("steps", new Node().items(steps));
    }

    private static Node directWorkflow(String channel, Node... steps) {
        Node workflow = new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(steps));
        return workflow;
    }

    private static Node directWorkflowMatching(String channel, Node event, Node... steps) {
        Node workflow = new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(steps));
        workflow.properties("event", event);
        return workflow;
    }

    private static Node updateDocumentStep(String op, String path, Node value) {
        return new Node()
                .type("Coordination/Update Document")
                .properties("changeset", new Node().items(new Node()
                        .properties("op", new Node().value(op))
                        .properties("path", new Node().value(path))
                        .properties("val", value)));
    }

    private static Node triggerEventStep(Node event) {
        return new Node()
                .type("Coordination/Trigger Event")
                .properties("event", event);
    }

    private static Node computePatchStep(String op, String path, Node value) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$appendChange", new Node()
                                .properties("op", new Node().value(op))
                                .properties("path", new Node().value(path))
                                .properties("val", value)),
                        new Node().properties("$return", new Node().value(true))));
    }

    private static Node computeAppendChatMessageStep(Node message) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$appendEvent", chatMessageBexEvent(message)),
                        new Node().properties("$return", new Node().value(true))));
    }

    private static Node chatMessageEvent(String message) {
        return new Node()
                .type("Coordination/Chat Message")
                .properties("message", new Node().value(message));
    }

    private static Node chatMessageBexEvent(Node message) {
        return new Node().properties("$merge", new Node().items(
                new Node().properties("type", new Node().value("Coordination/Chat Message")),
                new Node().properties("message", message)));
    }

    private static Node documentUpdateMessage() {
        return bexConcat(
                new Node().value("updated "),
                bexBinding("event", "/path"),
                new Node().value(" from "),
                bexText(bexBinding("event", "/before")),
                new Node().value(" to "),
                bexText(bexBinding("event", "/after")));
    }

    private static Node bexAdd(Node... values) {
        return new Node().properties("$add", new Node().items(values));
    }

    private static Node bexConcat(Node... values) {
        return new Node().properties("$concat", new Node().items(values));
    }

    private static Node bexText(Node value) {
        return new Node().properties("$text", value);
    }

    private static Node bexDocument(String path) {
        return new Node().properties("$document", new Node().value(path));
    }

    private static Node bexBinding(String name, String path) {
        return new Node().properties("$binding", new Node().value(name + path));
    }

    private static Node childDocument(int counter, Map<String, Node> contracts) {
        Node child = new Node()
                .name("Child")
                .properties("counter", new Node().value(counter));
        if (!contracts.isEmpty()) {
            child.properties("contracts", new Node().properties(contracts));
        }
        return child;
    }

    private static Node document(BlueRepository repository, int counter, Map<String, Node> contracts) {
        return new Node()
                .blue(repository.importsDirective())
                .name("Runtime Channel Test")
                .properties("counter", new Node().value(counter))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node initializedDocument(Fixture fixture, Node document) {
        DocumentProcessingResult result =
                fixture.blue.initializeDocument(
                        fixture.blue.preprocess(document));
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        return result.document();
    }

    private static DocumentProcessingResult processChat(Fixture fixture, Node document, int timestamp) {
        return fixture.blue.processDocument(document, chatTimelineEntry(fixture, timestamp));
    }

    private static ProcessingDebugResult processChatWithTrace(
            Fixture fixture,
            Node document,
            int timestamp) {
        return fixture.blue.processor()
                .processDocumentWithTrace(
                        document,
                        chatTimelineEntry(
                                fixture, timestamp));
    }

    private static Node chatTimelineEntry(Fixture fixture, int timestamp) {
        return TestTimelineProvider.timelineEntry(
                fixture.blue, fixture.repository, "owner", timestamp, chatMessageEvent("run"));
    }

    private static Node operationRequestEvent(Fixture fixture,
                                              int timestamp,
                                              String operation,
                                              Node request) {
        Node operationRequest = new Node()
                .type("Coordination/Operation Request")
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value("owner"))
                .properties("request", request);
        return TestTimelineProvider.timelineEntry(
                fixture.blue, fixture.repository, "owner", timestamp, operationRequest);
    }

    private static Node nodeAt(Node node, String pointer) {
        try {
            Object value = node.get(pointer);
            return value instanceof Node ? (Node) value : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Object nodeValueAt(
            Node node,
            String pointer) {
        try {
            return node != null
                    ? node.get(pointer)
                    : null;
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime blue =
                CoordinationTestResources.configuredBlue(repository);
        return new Fixture(repository, blue);
    }

    private static void assertSingleChatMessage(List<Node> events, String expectedMessage) {
        int count = 0;
        for (Node event : events) {
            if (isChatMessage(event, expectedMessage)) {
                count++;
            }
        }
        assertEquals(1, count, "Expected exactly one chat message: " + expectedMessage);
    }

    private static void assertContainsChatMessage(List<Node> events, String expectedMessage) {
        assertTrue(
                containsChatMessage(
                        events, expectedMessage),
                "Expected chat message: " + expectedMessage);
    }

    private static boolean containsChatMessage(
            List<Node> events,
            String expectedMessage) {
        for (Node event : events) {
            if (isChatMessage(event, expectedMessage)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoChatMessage(List<Node> events, String message) {
        for (Node event : events) {
            assertFalse(isChatMessage(event, message), "Unexpected chat message: " + message);
        }
    }

    private static boolean isChatMessage(Node event, String message) {
        try {
            return message.equals(event.get("/message"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
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
