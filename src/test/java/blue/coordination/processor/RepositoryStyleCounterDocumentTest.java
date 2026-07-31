package blue.coordination.processor;

import blue.coordination.processor.CoordinationProcessors;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.repo.BlueRepository;
import blue.repo.coordination.OperationRequest;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryStyleCounterDocumentTest {
    private static final String TIMELINE_ID = "bb13b2d9-3df9-5fea-9fdf-dd4f0ae74486";

    @Test
    void shouldInitializeRichCounterWithoutCheckpointState() {
        // Given
        Fixture fixture = configuredFixture();
        Node authored = richCounterDocument(fixture);

        // When
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(authored);

        // Then
        assertNull(property(property(authored, "contracts"), "initialized"));
        assertNull(property(property(authored, "contracts"), "checkpoint"));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(initialized), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(initialized));
        assertTrue(fixture.blue.isInitialized(initialized.document()));
        assertNotNull(ProcessingResultTestSupport.snapshot(fixture.blue, initialized));
        assertNotNull(ProcessingResultTestSupport.blueId(initialized));
        Node initializedDocument =
                ProcessingResultTestSupport
                        .snapshot(fixture.blue, initialized)
                        .canonicalNodeAt(
                                "/contracts/initialized/document");
        assertNotNull(initializedDocument);
        assertNotNull(initializedDocument.getBlueId());
        assertNull(
                property(
                        property(
                                ProcessingResultTestSupport
                                        .resolvedDocument(
                                                fixture.blue,
                                                initialized)
                                        .getContracts(),
                                "initialized"),
                        "documentId"));
        assertNull(property(property(ProcessingResultTestSupport.resolvedDocument(
                fixture.blue, initialized), "contracts"), "checkpoint"));
    }

    @Test
    void shouldProcessIncrementAndWriteTimelineCheckpoint() {
        // Given
        Fixture fixture = configuredFixture();
        Node authored = richCounterDocument(fixture);
        DocumentProcessingResult initialized =
                fixture.blue.initializeDocument(authored);
        Node initializedDocument =
                ProcessingResultTestSupport
                        .snapshot(fixture.blue, initialized)
                        .canonicalNodeAt(
                                "/contracts/initialized/document");
        assertNotNull(initializedDocument);
        String initializedDocumentBlueId =
                initializedDocument.getBlueId();
        assertNotNull(initializedDocumentBlueId);
        Node event = TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                TIMELINE_ID,
                1777987926,
                operationRequest("increment", 5));

        // When
        DocumentProcessingResult result = fixture.blue.processDocument(
                ProcessingResultTestSupport.snapshot(fixture.blue, initialized), event);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNotNull(ProcessingResultTestSupport.snapshot(fixture.blue, result));
        assertNotNull(ProcessingResultTestSupport.blueId(result));
        assertEquals(BigInteger.valueOf(5),
                ProcessingResultTestSupport.resolvedDocument(fixture.blue, result)
                        .get("/counter"));
        assertEquals(1, result.events().size());
        assertEquals("Counter was incremented by 5 and is now 5",
                result.events().get(0).getAsText("/message"));

        Node resolved = ProcessingResultTestSupport.resolvedDocument(
                fixture.blue, result);
        Node retainedInitializedDocument =
                ProcessingResultTestSupport
                        .snapshot(fixture.blue, result)
                        .canonicalNodeAt(
                                "/contracts/initialized/document");
        assertNotNull(retainedInitializedDocument);
        assertEquals(
                initializedDocumentBlueId,
                retainedInitializedDocument.getBlueId());
        Node checkpoint = property(
                property(resolved, "contracts"), "checkpoint");
        Node checkpointEntries = property(checkpoint, "entries");
        Node checkpointEntry = property(checkpointEntries, "ownerChannel");
        Node checkpointSubject = property(checkpointEntry, "subject");
        assertNotNull(checkpointSubject);
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                checkpointSubject.getAsText("/semantics"));
        assertEquals(BigInteger.valueOf(1777987926L),
                checkpointSubject.get("/timestamp"));
        assertNull(property(checkpointSubject, "timeline"));
        assertNull(property(checkpointSubject, "message"));
    }

    private static Node richCounterDocument(Fixture fixture) {
        Node parsed = fixture.blue.yamlToNode(richCounterDocumentYaml());
        return fixture.blue.preprocess(parsed.blue(fixture.repository.typeAliasBlue()));
    }

    private static String richCounterDocumentYaml() {
        return String.join("\n",
                "name: Counter - 2026-04-21T09:47:18.314Z",
                "description: Target Blue document to be bootstrapped",
                "counter: 0",
                "contracts:",
                "  ownerChannel:",
                "    type: Coordination/Timeline Channel",
                "    order:",
                "      description: Deterministic sort key within a scope; missing == 0.",
                "      type: Integer",
                "    event:",
                "      description: Optional matcher payload used by the channel's processor to further restrict which incoming events it accepts at this scope.",
                "    timeline:",
                "      description: Timeline whose entries this channel delivers.",
                "      type: Coordination/Timeline",
                "      providerId: test-provider",
                "      timelineId:",
                "        type: Text",
                "        value: " + TIMELINE_ID,
                "    actor:",
                "      description: Actor whose entries this channel delivers.",
                "      type: MyOS/Principal Actor",
                "      accountId:",
                "        type: Text",
                "        value: " + TIMELINE_ID,
                "  increment:",
                "    description: Increment the counter by the given number",
                "    type: Coordination/Sequential Workflow Operation",
                "    order:",
                "      description: Deterministic sort key within a scope; missing == 0.",
                "      type: Integer",
                "    channel:",
                "      description: Contracts-map key of the Channel in this scope on which Operation Request events are sent to invoke this operation.",
                "      type: Text",
                "      value: ownerChannel",
                "    request:",
                "      description: Represents a value by which counter will be incremented",
                "      type: Integer",
                "    event:",
                "      description: Optional matcher payload used by the handler's processor to further restrict events.",
                "    steps:",
                "      description: Ordered list of steps to execute (positional semantics).",
                "      type: List",
                "      itemType: Coordination/Sequential Workflow Step",
                "      items:",
                "        - name: ApplyIncrement",
                "          type: Coordination/Compute",
                "          do:",
                "            - $appendChange:",
                "                op: replace",
                "                path: /counter",
                "                val:",
                "                  $add:",
                "                    - $document: /counter",
                "                    - $binding:",
                "                        name: event",
                "                        path: /message/request",
                "            - $return: {}",
                "        - name: CreateMessageEvent",
                "          type: Coordination/Compute",
                "          do:",
                "            - $appendEvent:",
                "                $merge:",
                "                  - type: Coordination/Chat Message",
                "                  - message:",
                "                      $concat:",
                "                        - Counter was incremented by",
                "                        - \" \"",
                "                        - $binding:",
                "                            name: event",
                "                            path: /message/request",
                "                        - \" and is now \"",
                "                        - $text:",
                "                            $document: /counter",
                "            - $return: {}",
                "  decrement:",
                "    description: Decrement the counter by the given number",
                "    type: Coordination/Sequential Workflow Operation",
                "    order:",
                "      description: Deterministic sort key within a scope; missing == 0.",
                "      type: Integer",
                "    channel:",
                "      description: Contracts-map key of the Channel in this scope on which Operation Request events are sent to invoke this operation.",
                "      type: Text",
                "      value: ownerChannel",
                "    request:",
                "      description: Value to subtract",
                "      type: Integer",
                "    event:",
                "      description: Optional matcher payload used by the handler's processor to further restrict events.",
                "    steps:",
                "      description: Ordered list of steps to execute (positional semantics).",
                "      type: List",
                "      itemType: Coordination/Sequential Workflow Step",
                "      items:",
                "        - name: ApplyDecrement",
                "          type: Coordination/Compute",
                "          do:",
                "            - $appendChange:",
                "                op: replace",
                "                path: /counter",
                "                val:",
                "                  $subtract:",
                "                    - $document: /counter",
                "                    - $binding:",
                "                        name: event",
                "                        path: /message/request",
                "            - $return: {}",
                "        - name: CreateMessageEvent",
                "          type: Coordination/Compute",
                "          do:",
                "            - $appendEvent:",
                "                $merge:",
                "                  - type: Coordination/Chat Message",
                "                  - message:",
                "                      $concat:",
                "                        - Counter was decremented by",
                "                        - \" \"",
                "                        - $binding:",
                "                            name: event",
                "                            path: /message/request",
                "                        - \" and is now \"",
                "                        - $text:",
                "                            $document: /counter",
                "            - $return: {}");
    }

    private static Node operationRequest(String operation, int request) {
        OperationRequest operationRequest = new OperationRequest()
                .operation(operation)
                .channel("ownerChannel")
                .request(new Node().value(request));
        return new Node()
                .type(OperationRequest.qualifiedName())
                .properties("operation", new Node().value(operationRequest.getOperation()))
                .properties("channel", new Node().value(operationRequest.getChannel()))
                .properties("request", operationRequest.getRequest());
    }

    private static Node property(Node node, String key) {
        if (node == null) {
            return null;
        }
        if ("contracts".equals(key)) {
            return node.getContracts();
        }
        if (node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
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
