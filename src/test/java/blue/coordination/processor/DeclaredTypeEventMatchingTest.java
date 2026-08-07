package blue.coordination.processor;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerMatchContextFactory;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Request;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclaredTypeEventMatchingTest {
    private static final String CHANNEL = "operations";
    private static final String OPERATION = "run";

    @Test
    void shouldAcceptExactAndChildDeclaredTypesButRejectUnrelatedTypedShapes() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflow workflow = workflow(types.pattern(types.expectedId));
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();

        // when
        boolean exactMatches = processor.matches(
                workflow, context(blue, types.event(types.expectedId)));
        boolean childMatches = processor.matches(
                workflow, context(blue, types.event(types.childId)));
        boolean unrelatedSameShapeMatches = processor.matches(
                workflow, context(blue, types.event(types.unrelatedSameShapeId)));
        boolean differentShapeMatches = processor.matches(
                workflow, context(blue, types.differentEvent()));

        // then
        assertTrue(exactMatches);
        assertTrue(childMatches);
        assertFalse(unrelatedSameShapeMatches);
        assertFalse(differentShapeMatches);
    }

    @Test
    void shouldMatchDeclaredTypeLineageAcrossPureAndMaterializedRepresentations() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();

        // when
        List<Boolean> exactResults = representationMatrix(
                processor, blue, types, types.expectedId, types.expectedId);
        List<Boolean> childResults = representationMatrix(
                processor, blue, types, types.childId, types.expectedId);
        List<Boolean> grandchildResults = representationMatrix(
                processor, blue, types, types.grandchildId, types.expectedId);
        List<Boolean> siblingResults = representationMatrix(
                processor, blue, types, types.siblingId, types.childId);
        List<Boolean> unrelatedResults = representationMatrix(
                processor, blue, types, types.unrelatedSameShapeId, types.expectedId);

        // then
        List<Boolean> allMatch = Collections.nCopies(4, Boolean.TRUE);
        List<Boolean> noneMatch = Collections.nCopies(4, Boolean.FALSE);
        assertEquals(allMatch, exactResults);
        assertEquals(allMatch, childResults);
        assertEquals(allMatch, grandchildResults);
        assertEquals(noneMatch, siblingResults);
        assertEquals(noneMatch, unrelatedResults);
    }

    @Test
    void shouldEnforceAdditionalConstraintsForCompatibleDeclaredTypes() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();
        Node requiredKind = new Node().schema(new Schema().required(true));
        SequentialWorkflow requiredKindWorkflow = workflow(
                types.pattern(types.expectedId).properties("kind", requiredKind));
        SequentialWorkflow requiredValueWorkflow = workflow(
                types.pattern(types.expectedId)
                        .properties("kind", new Node().value("required-value")));
        SequentialWorkflow minimumLengthWorkflow = workflow(
                types.pattern(types.expectedId)
                        .properties("kind", new Node().schema(new Schema().minLength(12))));

        // when
        boolean missingRequiredKindMatches = processor.matches(
                requiredKindWorkflow,
                context(blue, types.eventWithoutKind(types.childId)));
        boolean wrongValueMatches = processor.matches(
                requiredValueWorkflow,
                context(blue, types.event(types.childId)));
        boolean tooShortMatches = processor.matches(
                minimumLengthWorkflow,
                context(blue, types.event(types.childId)));

        // then
        assertFalse(missingRequiredKindMatches);
        assertFalse(wrongValueMatches);
        assertFalse(tooShortMatches);
    }

    @Test
    void shouldRetainStructuralMatchingForUntypedAndTypeFreePatterns() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();

        // when
        boolean typeFreePatternMatches = processor.matches(
                workflow(null),
                context(blue, types.event(types.unrelatedSameShapeId)));
        boolean untypedEventMatches = processor.matches(
                workflow(types.pattern(types.expectedId)),
                context(blue, types.untypedEvent()));
        boolean nullEventMatches = processor.matches(
                workflow(types.pattern(types.expectedId)),
                context(blue, null));
        boolean matchingStructureMatches = processor.matches(
                workflow(new Node().properties("kind", new Node().value("accepted"))),
                context(blue, types.event(types.unrelatedSameShapeId)));
        boolean differentStructureMatches = processor.matches(
                workflow(new Node().properties("kind", new Node().value("other"))),
                context(blue, types.event(types.unrelatedSameShapeId)));

        // then
        assertTrue(typeFreePatternMatches);
        assertTrue(untypedEventMatches);
        assertFalse(nullEventMatches);
        assertTrue(matchingStructureMatches);
        assertFalse(differentStructureMatches);
    }

    @Test
    void shouldRetainStructuralMatchingForAnonymousExpectedTypes() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();
        Node anonymousExpectedType = TypeFixture.sameShapeDefinition("Anonymous Expected Event");

        // when
        boolean matches = processor.matches(
                workflow(new Node().type(anonymousExpectedType)),
                context(blue, types.event(types.unrelatedSameShapeId)));

        // then
        assertTrue(matches);
    }

    @Test
    void shouldRetainStructuralMatchingForAnonymousActualTypes() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();
        Node anonymouslyTypedEvent = new Node()
                .type(TypeFixture.sameShapeDefinition("Anonymous Actual Event"))
                .properties("kind", new Node().value("accepted"));
        SequentialWorkflow identityBearingPattern = workflow(types.pattern(types.expectedId));
        HandlerMatchContext anonymousActualContext = context(blue, anonymouslyTypedEvent);

        // when
        boolean structuralResult = anonymousActualContext.matchesEventPattern(
                identityBearingPattern.getEvent());
        boolean processorResult = processor.matches(
                identityBearingPattern, anonymousActualContext);

        // then
        assertTrue(structuralResult);
        assertEquals(structuralResult, processorResult);
    }

    @Test
    void shouldApplyDeclaredTypeFilteringToSequentialAndChatOperations() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        Node event = operationRequest(new Node());
        Node structurallyCompatibleUnrelatedPattern = types.pattern(types.operationLookalikeId);
        SequentialWorkflowOperation sequential = operation(structurallyCompatibleUnrelatedPattern);
        ChatWorkflowOperation chat = chatOperation(structurallyCompatibleUnrelatedPattern);
        HandlerMatchContext matchContext = context(blue, event);
        SequentialWorkflowOperationProcessor sequentialProcessor =
                new SequentialWorkflowOperationProcessor();
        ChatWorkflowOperationProcessor chatProcessor = new ChatWorkflowOperationProcessor();

        // when
        boolean sequentialMatchesUnrelatedType = sequentialProcessor.matches(
                sequential, matchContext);
        boolean chatMatchesUnrelatedType = chatProcessor.matches(chat, matchContext);
        sequential.setEvent(new Node().type(reference(Request.blueId())));
        chat.setEvent(new Node().type(reference(Request.blueId())));
        boolean sequentialMatchesRequestType = sequentialProcessor.matches(
                sequential, matchContext);
        boolean chatMatchesRequestType = chatProcessor.matches(chat, matchContext);

        // then
        assertFalse(sequentialMatchesUnrelatedType);
        assertFalse(chatMatchesUnrelatedType);
        assertTrue(sequentialMatchesRequestType);
        assertTrue(chatMatchesRequestType);
    }

    @Test
    void shouldRetainGenericStructuralFallbackForRequestPayloadMatching() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflowOperation sequential = operation(null);
        sequential.request(types.pattern(types.expectedId));
        ChatWorkflowOperation chat = chatOperation(null);
        chat.request(types.pattern(types.expectedId));
        HandlerMatchContext pureContext = context(
                blue,
                operationRequest(types.event(types.unrelatedSameShapeId)));
        HandlerMatchContext materializedContext = context(
                blue,
                operationRequest(types.materializedEvent(blue, types.unrelatedSameShapeId)));
        SequentialWorkflowOperationProcessor sequentialProcessor =
                new SequentialWorkflowOperationProcessor();
        ChatWorkflowOperationProcessor chatProcessor = new ChatWorkflowOperationProcessor();

        // when
        boolean sequentialMatchesPure = sequentialProcessor.matches(sequential, pureContext);
        boolean chatMatchesPure = chatProcessor.matches(chat, pureContext);
        boolean sequentialMatchesMaterialized = sequentialProcessor.matches(
                sequential, materializedContext);
        boolean chatMatchesMaterialized = chatProcessor.matches(chat, materializedContext);

        // then
        assertTrue(sequentialMatchesPure);
        assertTrue(chatMatchesPure);
        assertTrue(sequentialMatchesMaterialized);
        assertTrue(chatMatchesMaterialized);
    }

    @Test
    void shouldReturnSamePureReferenceResultAcrossColdAndWarmContexts() {
        // given
        TypeFixture types = TypeFixture.create();
        CoordinationTestRuntime blue = types.configuredBlue();
        SequentialWorkflow workflow = workflow(types.pattern(types.expectedId));
        SequentialWorkflowProcessor processor = new SequentialWorkflowProcessor();
        Node event = types.event(types.childId);

        // when
        boolean coldResult = processor.matches(workflow, context(blue, event));
        boolean clonedWarmResult = processor.matches(workflow, context(blue, event.clone()));
        boolean recreatedWarmResult = processor.matches(
                workflow, context(blue, types.event(types.childId)));

        // then
        assertTrue(coldResult);
        assertTrue(clonedWarmResult);
        assertTrue(recreatedWarmResult);
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

    private static HandlerMatchContext context(
            CoordinationTestRuntime blue,
            Node event) {
        return HandlerMatchContextFactory.create(blue, OPERATION, CHANNEL, event);
    }

    private static List<Boolean> representationMatrix(SequentialWorkflowProcessor processor,
                                                      CoordinationTestRuntime blue,
                                                      TypeFixture types,
                                                      String actualTypeId,
                                                      String expectedTypeId) {
        Node pureEvent = types.event(actualTypeId);
        Node materializedEvent = types.materializedEvent(blue, actualTypeId);
        Node pureExpected = reference(expectedTypeId);
        Node materializedExpected = types.materializedType(blue, expectedTypeId);

        return Arrays.asList(
                processor.matches(
                        workflow(new Node().type(pureExpected)), context(blue, pureEvent)),
                processor.matches(
                        workflow(new Node().type(materializedExpected)), context(blue, pureEvent)),
                processor.matches(
                        workflow(new Node().type(pureExpected)), context(blue, materializedEvent)),
                processor.matches(
                        workflow(new Node().type(materializedExpected)),
                        context(blue, materializedEvent)));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class TypeFixture {
        private final String expectedId;
        private final String childId;
        private final String grandchildId;
        private final String siblingId;
        private final String unrelatedSameShapeId;
        private final String unrelatedDifferentShapeId;
        private final String operationLookalikeId;
        private final Map<String, Node> definitions;

        private TypeFixture(String expectedId,
                            String childId,
                            String grandchildId,
                            String siblingId,
                            String unrelatedSameShapeId,
                            String unrelatedDifferentShapeId,
                            String operationLookalikeId,
                            Map<String, Node> definitions) {
            this.expectedId = expectedId;
            this.childId = childId;
            this.grandchildId = grandchildId;
            this.siblingId = siblingId;
            this.unrelatedSameShapeId = unrelatedSameShapeId;
            this.unrelatedDifferentShapeId = unrelatedDifferentShapeId;
            this.operationLookalikeId = operationLookalikeId;
            this.definitions = definitions;
        }

        private static TypeFixture create() {
            Node expected = sameShapeDefinition("Expected Event");
            String expectedId = DirectBlueIdCalculator.calculateBlueId(expected);
            Node child = sameShapeDefinition("Child Event").type(reference(expectedId));
            String childId = DirectBlueIdCalculator.calculateBlueId(child);
            Node grandchild = sameShapeDefinition("Grandchild Event").type(reference(childId));
            Node common = sameShapeDefinition("Common Event");
            String commonId = DirectBlueIdCalculator.calculateBlueId(common);
            Node sibling = sameShapeDefinition("Sibling Event").type(reference(commonId));
            Node unrelatedSameShape = sameShapeDefinition("Unrelated Same Shape Event");
            Node unrelatedDifferentShape = new Node()
                    .name("Unrelated Different Shape Event")
                    .properties("different", requiredText());
            Node operationLookalike = new Node()
                    .name("Unrelated Operation Lookalike")
                    .properties("operation", requiredText())
                    .properties("channel", requiredText());
            String grandchildId = DirectBlueIdCalculator.calculateBlueId(grandchild);
            String siblingId = DirectBlueIdCalculator.calculateBlueId(sibling);
            String unrelatedSameShapeId = DirectBlueIdCalculator.calculateBlueId(unrelatedSameShape);
            String unrelatedDifferentShapeId = DirectBlueIdCalculator.calculateBlueId(unrelatedDifferentShape);
            String operationLookalikeId = DirectBlueIdCalculator.calculateBlueId(operationLookalike);
            Map<String, Node> definitions = new LinkedHashMap<String, Node>();
            definitions.put(expectedId, expected);
            definitions.put(childId, child);
            definitions.put(grandchildId, grandchild);
            definitions.put(commonId, common);
            definitions.put(siblingId, sibling);
            definitions.put(unrelatedSameShapeId, unrelatedSameShape);
            definitions.put(unrelatedDifferentShapeId, unrelatedDifferentShape);
            definitions.put(operationLookalikeId, operationLookalike);
            return new TypeFixture(
                    expectedId,
                    childId,
                    grandchildId,
                    siblingId,
                    unrelatedSameShapeId,
                    unrelatedDifferentShapeId,
                    operationLookalikeId,
                    definitions);
        }

        private CoordinationTestRuntime configuredBlue() {
            BlueRepository repository = BlueRepository.current();
            CoordinationTestRuntime blue =
                    CoordinationTestResources.configuredBlue(repository);
            blue.addNodeProvider(new MapProvider(definitions));
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

        private Node materializedEvent(
                CoordinationTestRuntime blue,
                String typeBlueId) {
            return blue.resolveToSnapshot(event(typeBlueId)).resolvedRoot();
        }

        private Node materializedType(
                CoordinationTestRuntime blue,
                String typeBlueId) {
            return materializedEvent(blue, typeBlueId).getType();
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
