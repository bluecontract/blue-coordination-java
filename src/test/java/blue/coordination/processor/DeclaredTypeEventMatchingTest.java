package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerMatchContextFactory;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeProviderWrapper;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Request;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclaredTypeEventMatchingTest {
    private static final String CHANNEL = "operations";
    private static final String OPERATION = "run";

    @Test
    void directWorkflowAcceptsExactAndChildTypesButRejectsUnrelatedTypedShapes() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.configuredBlue();
        SequentialWorkflow workflow = workflow(types.pattern(types.expectedId));
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();

        assertTrue(processor.matches(workflow, context(blue, types.event(types.expectedId))));
        assertTrue(processor.matches(workflow, context(blue, types.event(types.childId))));
        assertFalse(processor.matches(workflow, context(blue, types.event(types.unrelatedSameShapeId))));
        assertFalse(processor.matches(workflow, context(blue, types.differentEvent())));
    }

    @Test
    void compatibleDeclaredTypesStillSatisfyEveryAdditionalConstraint() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();

        Node requiredKind = new Node().schema(new Schema().required(true));
        assertFalse(processor.matches(
                workflow(types.pattern(types.expectedId).properties("kind", requiredKind)),
                context(blue, types.eventWithoutKind(types.childId))));
        assertFalse(processor.matches(
                workflow(types.pattern(types.expectedId)
                        .properties("kind", new Node().value("required-value"))),
                context(blue, types.event(types.childId))));
        assertFalse(processor.matches(
                workflow(types.pattern(types.expectedId)
                        .properties("kind", new Node().schema(new Schema().minLength(12)))),
                context(blue, types.event(types.childId))));
    }

    @Test
    void untypedEventsAndPatternsWithoutTypesRetainStructuralMatching() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();

        assertTrue(processor.matches(
                workflow(null),
                context(blue, types.event(types.unrelatedSameShapeId))));
        assertTrue(processor.matches(
                workflow(types.pattern(types.expectedId)),
                context(blue, types.untypedEvent())));
        assertFalse(processor.matches(
                workflow(types.pattern(types.expectedId)),
                context(blue, null)));
        assertTrue(processor.matches(
                workflow(new Node().properties("kind", new Node().value("accepted"))),
                context(blue, types.event(types.unrelatedSameShapeId))));
        assertFalse(processor.matches(
                workflow(new Node().properties("kind", new Node().value("other"))),
                context(blue, types.event(types.unrelatedSameShapeId))));
    }

    @Test
    void sequentialAndChatOperationsShareDeclaredTypeEventFiltering() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.configuredBlue();
        Node event = operationRequest(new Node());
        Node structurallyCompatibleUnrelatedPattern = types.pattern(types.operationLookalikeId);
        SequentialWorkflowOperation sequential = operation(structurallyCompatibleUnrelatedPattern);
        ChatWorkflowOperation chat = chatOperation(structurallyCompatibleUnrelatedPattern);
        HandlerMatchContext context = context(blue, event);

        assertFalse(new SequentialWorkflowOperationProcessor().matches(sequential, context));
        assertFalse(new ChatWorkflowOperationProcessor().matches(chat, context));

        sequential.setEvent(new Node().type(reference(Request.blueId())));
        chat.setEvent(new Node().type(reference(Request.blueId())));
        assertTrue(new SequentialWorkflowOperationProcessor().matches(sequential, context));
        assertTrue(new ChatWorkflowOperationProcessor().matches(chat, context));
    }

    @Test
    void requestPayloadMatchingRetainsGenericStructuralTypeFallback() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.configuredBlue();
        SequentialWorkflowOperation sequential = operation(null);
        sequential.request(types.pattern(types.expectedId));
        ChatWorkflowOperation chat = chatOperation(null);
        chat.request(types.pattern(types.expectedId));
        HandlerMatchContext context = context(
                blue,
                operationRequest(types.event(types.unrelatedSameShapeId)));

        assertTrue(new SequentialWorkflowOperationProcessor().matches(sequential, context));
        assertTrue(new ChatWorkflowOperationProcessor().matches(chat, context));
    }

    @Test
    void pureReferenceResultsAgreeAcrossColdAndWarmContexts() {
        TypeFixture types = TypeFixture.create();
        Blue blue = types.configuredBlue();
        SequentialWorkflow workflow = workflow(types.pattern(types.expectedId));
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();
        Node event = types.event(types.childId);

        assertTrue(processor.matches(workflow, context(blue, event)));
        assertTrue(processor.matches(workflow, context(blue, event.clone())));
        assertTrue(processor.matches(workflow, context(blue, types.event(types.childId))));
    }

    private static SequentialWorkflow workflow(Node pattern) {
        SequentialWorkflow workflow = new SequentialWorkflow();
        workflow.setEvent(pattern);
        return workflow;
    }

    private static SequentialWorkflowOperation operation(Node eventPattern) {
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey(OPERATION);
        operation.setEvent(eventPattern);
        return operation;
    }

    private static ChatWorkflowOperation chatOperation(Node eventPattern) {
        ChatWorkflowOperation operation = new ChatWorkflowOperation();
        operation.setKey(OPERATION);
        operation.setEvent(eventPattern);
        return operation;
    }

    private static Node operationRequest(Node request) {
        return new Node()
                .type(reference(OperationRequest.blueId()))
                .properties("operation", new Node().value(OPERATION))
                .properties("channel", new Node().value(CHANNEL))
                .properties("request", request);
    }

    private static HandlerMatchContext context(Blue blue, Node event) {
        return HandlerMatchContextFactory.create(blue, OPERATION, CHANNEL, event);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class TypeFixture {
        private final String expectedId;
        private final String childId;
        private final String unrelatedSameShapeId;
        private final String unrelatedDifferentShapeId;
        private final String operationLookalikeId;
        private final Map<String, Node> definitions;

        private TypeFixture(String expectedId,
                            String childId,
                            String unrelatedSameShapeId,
                            String unrelatedDifferentShapeId,
                            String operationLookalikeId,
                            Map<String, Node> definitions) {
            this.expectedId = expectedId;
            this.childId = childId;
            this.unrelatedSameShapeId = unrelatedSameShapeId;
            this.unrelatedDifferentShapeId = unrelatedDifferentShapeId;
            this.operationLookalikeId = operationLookalikeId;
            this.definitions = definitions;
        }

        private static TypeFixture create() {
            Node expected = sameShapeDefinition("Expected Event");
            String expectedId = BlueIdCalculator.calculateBlueId(expected);
            Node child = sameShapeDefinition("Child Event").type(reference(expectedId));
            Node unrelatedSameShape = sameShapeDefinition("Unrelated Same Shape Event");
            Node unrelatedDifferentShape = new Node()
                    .name("Unrelated Different Shape Event")
                    .properties("different", requiredText());
            Node operationLookalike = new Node()
                    .name("Unrelated Operation Lookalike")
                    .properties("operation", requiredText())
                    .properties("channel", requiredText());
            String childId = BlueIdCalculator.calculateBlueId(child);
            String unrelatedSameShapeId = BlueIdCalculator.calculateBlueId(unrelatedSameShape);
            String unrelatedDifferentShapeId = BlueIdCalculator.calculateBlueId(unrelatedDifferentShape);
            String operationLookalikeId = BlueIdCalculator.calculateBlueId(operationLookalike);
            Map<String, Node> definitions = new LinkedHashMap<String, Node>();
            definitions.put(expectedId, expected);
            definitions.put(childId, child);
            definitions.put(unrelatedSameShapeId, unrelatedSameShape);
            definitions.put(unrelatedDifferentShapeId, unrelatedDifferentShape);
            definitions.put(operationLookalikeId, operationLookalike);
            return new TypeFixture(
                    expectedId,
                    childId,
                    unrelatedSameShapeId,
                    unrelatedDifferentShapeId,
                    operationLookalikeId,
                    definitions);
        }

        private Blue configuredBlue() {
            Blue blue = BlueRepository.latest().configure(new Blue());
            NodeProvider repositoryProvider = blue.getNodeProvider();
            blue.nodeProvider(new SequentialNodeProvider(
                    NodeProviderWrapper.unverified(new MapProvider(definitions)),
                    repositoryProvider));
            return blue;
        }

        private static Node sameShapeDefinition(String name) {
            return new Node().name(name).properties("kind", requiredText());
        }

        private static Node requiredText() {
            return new Node()
                    .type(reference(TEXT_TYPE_BLUE_ID))
                    .schema(new Schema().required(true));
        }

        private Node event(String typeBlueId) {
            return new Node()
                    .type(reference(typeBlueId))
                    .properties("kind", new Node().value("accepted"));
        }

        private Node eventWithoutKind(String typeBlueId) {
            return new Node().type(reference(typeBlueId));
        }

        private Node untypedEvent() {
            return new Node().properties("kind", new Node().value("accepted"));
        }

        private Node pattern(String typeBlueId) {
            return new Node().type(reference(typeBlueId));
        }

        private Node differentEvent() {
            return new Node()
                    .type(reference(unrelatedDifferentShapeId))
                    .properties("different", new Node().value("value"));
        }
    }

    private static final class MapProvider implements NodeProvider {
        private final Map<String, Node> definitions;

        private MapProvider(Map<String, Node> definitions) {
            this.definitions = definitions;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : null;
        }
    }
}
