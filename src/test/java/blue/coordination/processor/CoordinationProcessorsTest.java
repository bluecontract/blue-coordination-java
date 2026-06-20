package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.TypeClassResolver;
import blue.repo.BlueRepository;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Operation;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.UpdateDocument;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationProcessorsTest {

    @Test
    void registerWithBlueRegistersCoordinationProcessors() {
        Fixture fixture = configuredFixture();

        assertCoordinationProcessorsRegistered(fixture.blue.getDocumentProcessor());
    }

    @Test
    void configureBuilderRegistersCoordinationProcessors() {
        DocumentProcessor processor =
                CoordinationProcessors.configure(DocumentProcessor.builder()).build();

        assertCoordinationProcessorsRegistered(processor);
    }

    @Test
    void realRepositoryCoordinationContractsLoadAndInitialize() {
        Fixture fixture = configuredFixture();
        TestTimelineProvider.registerWith(fixture.blue);
        Node document = counterDocument(fixture.repository, "ownerChannel");
        Node preprocessed = fixture.blue.preprocess(document.clone());
        Map<String, Node> contracts = contracts(preprocessed);

        assertEquals(TimelineChannel.blueId(), contracts.get("ownerChannel").getType().getBlueId());
        assertEquals(SequentialWorkflowOperation.blueId(),
                contracts.get("increment").getType().getBlueId());

        Object convertedOperation = fixture.blue.nodeToObject(contracts.get("increment"), Object.class);
        assertTrue(convertedOperation instanceof SequentialWorkflowOperation);
        assertEquals("ownerChannel", ((SequentialWorkflowOperation) convertedOperation).getChannel());

        Object convertedHandler = fixture.blue.nodeToObject(contracts.get("increment"), Object.class);
        assertTrue(convertedHandler instanceof SequentialWorkflowOperation);
        SequentialWorkflowOperation handler = (SequentialWorkflowOperation) convertedHandler;
        assertEquals("ownerChannel", handler.getChannel());
        assertNotNull(handler.getRequest());
        assertNotNull(handler.getSteps());
        assertTrue(handler.getSteps().isEmpty());

        DocumentProcessingResult result = fixture.blue.initializeDocument(preprocessed);

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertTrue(fixture.blue.isInitialized(result.document()));
        assertEquals(BigInteger.ZERO, result.document().getProperties().get("counter").getValue());
        assertFalse(contracts(result.document()).containsKey("checkpoint"));
    }

    @Test
    void sequentialWorkflowOperationWithMissingChannelDoesNotRun() {
        Fixture fixture = configuredFixture();
        TestTimelineProvider.registerWith(fixture.blue);
        Node document = counterDocument(fixture.repository, "missingChannel");
        Node preprocessed = fixture.blue.preprocess(document.clone());

        DocumentProcessingResult initialized = fixture.blue.initializeDocument(preprocessed);
        DocumentProcessingResult processed = fixture.blue.processDocument(initialized.document(),
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        "owner",
                        1,
                        CoordinationTestResources.operationRequest("increment", new Node().value(7))));

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals(BigInteger.ZERO, processed.document().getProperties().get("counter").getValue());
    }

    @Test
    void generatedRepositoryContractsProvideProcessorModelBaseTypes() {
        assertTrue(ChannelContract.class.isAssignableFrom(AllTimelinesChannel.class));
        assertTrue(ChannelContract.class.isAssignableFrom(TimelineChannel.class));
        assertTrue(ChannelContract.class.isAssignableFrom(CompositeTimelineChannel.class));
        assertTrue(HandlerContract.class.isAssignableFrom(ChatWorkflowOperation.class));
        assertTrue(HandlerContract.class.isAssignableFrom(SequentialWorkflow.class));
        assertTrue(HandlerContract.class.isAssignableFrom(Operation.class));
        assertTrue(HandlerContract.class.isAssignableFrom(SequentialWorkflowOperation.class));
    }

    @Test
    void generatedCoordinationTypesResolveToRepositoryClasses() {
        TypeClassResolver resolver = BlueRepository.v1_3_0().typeClassResolver();

        assertEquals(AllTimelinesChannel.class, resolver.resolveClass(AllTimelinesChannel.blueId()));
        assertEquals(TimelineChannel.class, resolver.resolveClass(TimelineChannel.blueId()));
        assertEquals(CompositeTimelineChannel.class,
                resolver.resolveClass(CompositeTimelineChannel.blueId()));
        assertEquals(ChatWorkflowOperation.class, resolver.resolveClass(ChatWorkflowOperation.blueId()));
        assertEquals(Operation.class, resolver.resolveClass(Operation.blueId()));
        assertEquals(SequentialWorkflow.class, resolver.resolveClass(SequentialWorkflow.blueId()));
        assertEquals(SequentialWorkflowOperation.class,
                resolver.resolveClass(SequentialWorkflowOperation.blueId()));
        assertEquals(UpdateDocument.class, resolver.resolveClass(UpdateDocument.blueId()));
        assertEquals(ChatMessage.class, resolver.resolveClass(ChatMessage.blueId()));
        assertEquals(OperationRequest.class, resolver.resolveClass(OperationRequest.blueId()));
    }

    private static void assertCoordinationProcessorsRegistered(DocumentProcessor processor) {
        ContractProcessorRegistry registry = processor.getContractRegistry();

        assertTrue(registry.lookupChannel(AllTimelinesChannel.blueId()).isPresent());
        assertFalse(registry.lookupChannel(TimelineChannel.blueId()).isPresent());
        assertTrue(registry.lookupChannel(CompositeTimelineChannel.blueId()).isPresent());
        assertFalse(registry.lookupMarker(Operation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(ChatWorkflowOperation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(Operation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(SequentialWorkflow.blueId()).isPresent());
        assertTrue(registry.lookupHandler(SequentialWorkflowOperation.blueId()).isPresent());
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.v1_3_0();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static Node counterDocument(BlueRepository repository, String operationChannel) {
        Map<String, Node> contracts = new LinkedHashMap<>();
        contracts.put("ownerChannel", new Node()
                .type("Coordination/Timeline Channel")
                .properties("timelineId", new Node().value("owner")));
        contracts.put("increment", new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value(operationChannel))
                .properties("request", new Node().type("Integer"))
                .properties("steps", new Node().items(Collections.<Node>emptyList())));

        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Counter")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Map<String, Node> contracts(Node document) {
        return document.getContracts().getProperties();
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
