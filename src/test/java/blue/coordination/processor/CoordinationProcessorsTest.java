package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void registerWithBlueInstallsOptionsMetricsAsLanguageSink() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        Blue blue = CoordinationTestResources.configuredBlue(BlueRepository.latest());

        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());

        assertSame(metrics, blue.getDocumentProcessor().processingMetricsSink());
    }

    @Test
    void registerWithBluePreservesAndFansOutToIndependentLanguageSink() {
        BexProcessingMetrics existing = new BexProcessingMetrics();
        BexProcessingMetrics coordination = new BexProcessingMetrics();
        Blue blue = CoordinationTestResources.configuredBlue(BlueRepository.latest());
        blue.getDocumentProcessor().processingMetricsSink(existing);

        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(coordination)
                .build());
        blue.getDocumentProcessor().processingMetricsSink().incrementPatchSequencesPrepared();
        blue.getDocumentProcessor().processingMetricsSink().addPatchesPrepared(3L);

        assertEquals(1L, existing.preparedPatchSequences());
        assertEquals(3L, existing.preparedPatches());
        assertEquals(1L, coordination.preparedPatchSequences());
        assertEquals(3L, coordination.preparedPatches());
    }

    @Test
    void registerWithBlueFansOutGenericLanguageMetricsToBothSinks() {
        BexProcessingMetrics existing = new BexProcessingMetrics();
        BexProcessingMetrics coordination = new BexProcessingMetrics();
        Blue blue = CoordinationTestResources.configuredBlue(BlueRepository.latest());
        blue.getDocumentProcessor().processingMetricsSink(existing);

        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(coordination)
                .build());
        blue.getDocumentProcessor().processingMetricsSink()
                .incrementFullSnapshotFallback("compositeTest");
        blue.getDocumentProcessor().processingMetricsSink()
                .incrementNodeCloneCalls("compositeTest");
        blue.getDocumentProcessor().processingMetricsSink()
                .setCacheEntries("compositeTest", 4L);
        blue.getDocumentProcessor().processingMetricsSink()
                .recordCacheHighWaterBytes("compositeTest", 12L);

        assertEquals(existing.languageCounters(), coordination.languageCounters());
        assertEquals(existing.languageGauges(), coordination.languageGauges());
        assertEquals(existing.languageHighWaterMarks(), coordination.languageHighWaterMarks());
        assertEquals(1L, existing.languageCounters().get("fullSnapshotFallbacks"));
        assertEquals(1L, existing.languageCounters()
                .get("fullSnapshotFallbackReason.compositeTest"));
        assertEquals(1L, existing.languageCounters()
                .get("nodeCloneCallsByPurpose.compositeTest"));
        assertEquals(4L, existing.languageGauges().get("cache.compositeTest.entries"));
        assertEquals(12L, existing.languageHighWaterMarks()
                .get("cache.compositeTest.highWaterBytes"));
    }

    @Test
    void configureBuilderInstallsOptionsMetricsAsLanguageSink() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessor processor = CoordinationProcessors.configure(
                DocumentProcessor.builder(),
                CoordinationProcessorOptions.builder().processingMetrics(metrics).build())
                .build();

        assertSame(metrics, processor.processingMetricsSink());
    }

    @Test
    void optionsMetricsReceiveRealLanguageMultiPatchSequenceCallbacks() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        Fixture fixture = configuredFixture(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
        Node preprocessed = fixture.blue.preprocess(multiPatchCounterDocument(fixture.repository));
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(preprocessed);
        BexProcessingMetrics.Snapshot before = metrics.snapshot();

        DocumentProcessingResult processed = fixture.blue.processDocument(initialized.document(),
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        "owner",
                        1,
                        CoordinationTestResources.operationRequest(
                                "increment", "ownerChannel", new Node().value(7))));
        BexProcessingMetrics.Snapshot after = metrics.snapshot();

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals(BigInteger.valueOf(3), processed.document().get("/counter"));
        assertEquals(1L, after.preparedPatchSequences - before.preparedPatchSequences);
        assertEquals(3L, after.preparedPatches - before.preparedPatches);
        assertEquals(1L, after.languageSequenceTransactions - before.languageSequenceTransactions);
        assertEquals(0L, after.languageSingletonTransactions - before.languageSingletonTransactions);
        assertEquals(0L, after.languageSuffixRebases - before.languageSuffixRebases);
        assertEquals(0L, after.languageFallbackPatches - before.languageFallbackPatches);
        assertEquals(2L, after.languageIntermediateSnapshotAdvances
                - before.languageIntermediateSnapshotAdvances);
        assertEquals(1L, after.languageFinalSnapshotPromotions
                - before.languageFinalSnapshotPromotions);
    }

    @Test
    void realRepositoryCoordinationContractsLoadAndInitialize() {
        Fixture fixture = configuredFixture();
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
        Node document = counterDocument(fixture.repository, "missingChannel");
        Node preprocessed = fixture.blue.preprocess(document.clone());

        DocumentProcessingResult initialized = fixture.blue.initializeDocument(preprocessed);
        DocumentProcessingResult processed = fixture.blue.processDocument(initialized.document(),
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        "owner",
                        1,
                        CoordinationTestResources.operationRequest(
                                "increment", "missingChannel", new Node().value(7))));

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
        assertTrue(registry.lookupChannel(TimelineChannel.blueId()).isPresent());
        assertTrue(registry.lookupChannel(CompositeTimelineChannel.blueId()).isPresent());
        assertFalse(registry.lookupMarker(Operation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(ChatWorkflowOperation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(Operation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(SequentialWorkflow.blueId()).isPresent());
        assertTrue(registry.lookupHandler(SequentialWorkflowOperation.blueId()).isPresent());
    }

    private static Fixture configuredFixture() {
        return configuredFixture(null);
    }

    private static Fixture configuredFixture(CoordinationProcessorOptions options) {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue, options);
        return new Fixture(repository, blue);
    }

    private static Node multiPatchCounterDocument(BlueRepository repository) {
        Node update = new Node()
                .type("Coordination/Update Document")
                .properties("changeset", new Node().items(
                        replacePatch("/counter", 1),
                        replacePatch("/counter", 2),
                        replacePatch("/counter", 3)));
        Map<String, Node> contracts = new LinkedHashMap<>();
        contracts.put("ownerChannel", TestTimelineProvider.channel("owner"));
        contracts.put("increment", new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().type("Integer"))
                .properties("steps", new Node().items(update)));
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("MultiPatchCounter")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node replacePatch(String path, int value) {
        return new Node()
                .properties("op", new Node().value("replace"))
                .properties("path", new Node().value(path))
                .properties("val", new Node().value(value));
    }

    private static Node counterDocument(BlueRepository repository, String operationChannel) {
        Map<String, Node> contracts = new LinkedHashMap<>();
        contracts.put("ownerChannel", TestTimelineProvider.channel("owner"));
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
