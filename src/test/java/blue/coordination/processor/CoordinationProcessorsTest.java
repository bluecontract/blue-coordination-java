package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
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
import blue.repo.myos.MyOSTimelineChannel;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationProcessorsTest {

    @Test
    void shouldRegisterCoordinationProcessorsWithBlue() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        DocumentProcessor processor =
                fixture.blue.getDocumentProcessor();

        // Then
        assertCoordinationProcessorsRegistered(processor);
    }

    @Test
    void shouldRegisterCoordinationProcessorsWithBuilder() {
        // Given
        DocumentProcessor.Builder builder =
                DocumentProcessor.builder();

        // When
        DocumentProcessor processor =
                CoordinationProcessors.configure(builder).build();

        // Then
        assertCoordinationProcessorsRegistered(processor);
    }

    @Test
    void shouldPreserveHostResolverWhenConfiguringBuilder() {
        // Given
        TypeClassResolver hostResolver =
                new TypeClassResolver()
                        .registerAnnotatedClass(
                                HostTimelineChannel.class);
        DocumentProcessor.Builder builder =
                DocumentProcessor.builder()
                        .withContractTypeResolver(hostResolver);

        // When
        DocumentProcessor processor =
                CoordinationProcessors.configure(builder).build();

        // Then
        assertSame(
                hostResolver,
                processor.getContractTypeResolver());
        assertEquals(
                HostTimelineChannel.class,
                hostResolver.resolveClass(
                        HOST_TIMELINE_CHANNEL_BLUE_ID));
        assertEquals(
                TimelineChannel.class,
                hostResolver.resolveClass(
                        TimelineChannel.blueId()));
    }

    @Test
    void shouldLeaveTimelineSubtypesUnregisteredByDefault() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        ContractProcessorRegistry registry =
                fixture.blue.getDocumentProcessor()
                        .getContractRegistry();

        // Then
        assertFalse(
                registry.lookupChannel(
                        MyOSTimelineChannel.blueId())
                        .isPresent());
        assertTrue(
                CoordinationRuntimeRegistrations
                        .timelineSubtypeBlueIds(
                                fixture.blue
                                        .getDocumentProcessor())
                        .isEmpty());
    }

    @Test
    void shouldRegisterAnyAnnotatedTimelineSubtypeExplicitlyWithBlue() {
        // Given
        Fixture fixture = configuredFixture();
        String before =
                CoordinationRuntimeRegistrations.identity(
                        fixture.blue
                                .getDocumentProcessor());

        // When
        Blue registered =
                CoordinationProcessors
                        .registerTimelineSubtype(
                                fixture.blue,
                                HostTimelineChannel.class);
        String after =
                CoordinationRuntimeRegistrations.identity(
                        fixture.blue
                                .getDocumentProcessor());

        // Then
        assertSame(fixture.blue, registered);
        assertTrue(
                fixture.blue.getDocumentProcessor()
                        .getContractRegistry()
                        .lookupChannel(
                                HOST_TIMELINE_CHANNEL_BLUE_ID)
                        .isPresent());
        assertEquals(
                Collections.singletonList(
                        HOST_TIMELINE_CHANNEL_BLUE_ID),
                CoordinationRuntimeRegistrations
                        .timelineSubtypeBlueIds(
                                fixture.blue
                                        .getDocumentProcessor()));
        assertNotEquals(before, after);
    }

    @Test
    void shouldRegisterGeneratedTimelineSubtypeExplicitlyWithBuilder() {
        // Given
        DocumentProcessor.Builder builder =
                CoordinationProcessors.configure(
                        DocumentProcessor.builder());

        // When
        DocumentProcessor processor =
                CoordinationProcessors
                        .registerTimelineSubtype(
                                builder,
                                MyOSTimelineChannel.class)
                        .build();

        // Then
        assertTrue(
                processor.getContractRegistry()
                        .lookupChannel(
                                MyOSTimelineChannel.blueId())
                        .isPresent());
        assertEquals(
                Collections.singletonList(
                        MyOSTimelineChannel.blueId()),
                CoordinationRuntimeRegistrations
                        .timelineSubtypeBlueIds(
                                processor));
    }

    @Test
    void shouldUseHostDeliveryPlanningSelectedAfterRegistration() {
        // Given
        BlueRepository repository = BlueRepository.latest();
        Blue blue = repository.configure(new Blue());
        CoordinationProcessors.registerWith(blue);
        DocumentProcessor processor =
                blue.getDocumentProcessor();
        ExternalDeliveryPlanDeriver compatibility =
                CoordinationDeliveryPlanning
                        .currentRootCompatibilityDeriver(
                                processor);
        AtomicBoolean invoked =
                new AtomicBoolean();
        processor.externalDeliveryPlanDeriver(
                (root, event) -> {
                    invoked.set(true);
                    return compatibility.derive(root, event);
                });
        Node initialized =
                blue.initializeDocument(
                        blue.preprocess(
                                counterDocument(
                                        repository,
                                        "ownerChannel")))
                        .document();

        // When
        DocumentProcessingResult processed =
                blue.processDocument(
                        initialized,
                        TestTimelineProvider.timelineEntry(
                                blue,
                                repository,
                                "owner",
                                1,
                                CoordinationTestResources
                                        .operationRequest(
                                                "increment",
                                                "ownerChannel",
                                                new Node()
                                                        .value(1))));

        // Then
        assertTrue(invoked.get());
        assertFalse(
                ProcessingResultTestSupport
                        .isCapabilityFailure(processed),
                ProcessingResultTestSupport
                        .diagnosticMessage(processed));
    }

    @Test
    void shouldFailClosedWhenRegistrationHasNoHostDeliveryPlan() {
        // Given
        BlueRepository repository = BlueRepository.latest();
        Blue blue = repository.configure(new Blue());
        CoordinationProcessors.registerWith(blue);
        Node initialized =
                blue.initializeDocument(
                        blue.preprocess(
                                counterDocument(
                                        repository,
                                        "ownerChannel")))
                        .document();

        // When
        ExecutionEvidenceUnavailableException failure =
                assertThrows(
                        ExecutionEvidenceUnavailableException.class,
                        () -> blue.processDocument(
                                initialized,
                                TestTimelineProvider.timelineEntry(
                                        blue,
                                        repository,
                                        "owner",
                                        1,
                                        CoordinationTestResources
                                                .operationRequest(
                                                        "increment",
                                                        "ownerChannel",
                                                        new Node()
                                                                .value(1)))));

        // Then
        assertTrue(
                failure.getMessage()
                        .contains(
                                "Exact external delivery "
                                        + "subscription and "
                                        + "activation state is "
                                        + "unavailable"));
    }

    @Test
    void shouldEnableDeterministicCurrentRootPlanningExplicitly() {
        // Given
        BlueRepository repository = BlueRepository.latest();
        Blue blue = repository.configure(new Blue());
        CoordinationProcessors.registerWith(blue);
        CoordinationDeliveryPlanning
                .currentRootCompatibility(
                        blue.getDocumentProcessor());
        Node initialized =
                blue.initializeDocument(
                        blue.preprocess(
                                counterDocument(
                                        repository,
                                        "ownerChannel")))
                        .document();
        Node event =
                TestTimelineProvider.timelineEntry(
                        blue,
                        repository,
                        "owner",
                        1,
                        CoordinationTestResources
                                .operationRequest(
                                        "increment",
                                        "ownerChannel",
                                        new Node().value(1)));

        // When
        DocumentProcessingResult first =
                blue.processDocument(
                        initialized.clone(),
                        event.clone());
        DocumentProcessingResult second =
                blue.processDocument(
                        initialized.clone(),
                        event.clone());

        // Then
        assertFalse(
                ProcessingResultTestSupport
                        .isCapabilityFailure(first),
                ProcessingResultTestSupport
                        .diagnosticMessage(first));
        assertEquals(
                blue.calculateBlueId(
                        first.document()),
                blue.calculateBlueId(
                        second.document()));
    }

    @Test
    void shouldDeclareOnlyStepsAsDeferredExecutableBody() {
        // Given
        java.util.List<String> expected =
                Collections.singletonList("steps");

        // When
        java.util.List<String> workflowFields =
                new SequentialWorkflowProcessor()
                        .executableBodyFields();
        java.util.List<String> operationFields =
                new SequentialWorkflowOperationProcessor()
                        .executableBodyFields();
        java.util.List<String> chatFields =
                new ChatWorkflowOperationProcessor()
                        .executableBodyFields();

        // Then
        assertEquals(expected, workflowFields);
        assertEquals(expected, operationFields);
        assertEquals(expected, chatFields);
    }

    @Test
    void shouldInstallOptionsMetricsAsBlueLanguageSink() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        Blue blue = CoordinationTestResources.configuredBlue(BlueRepository.latest());

        // When
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());

        // Then
        assertSame(metrics, blue.getDocumentProcessor().processingMetricsSink());
    }

    @Test
    void shouldPreserveAndFanOutToIndependentLanguageSink() {
        // Given
        BexProcessingMetrics existing = new BexProcessingMetrics();
        BexProcessingMetrics coordination = new BexProcessingMetrics();
        Blue blue = CoordinationTestResources.configuredBlue(BlueRepository.latest());
        blue.getDocumentProcessor().processingMetricsSink(existing);

        // When
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(coordination)
                .build());
        blue.getDocumentProcessor().processingMetricsSink().incrementPatchSequencesPrepared();
        blue.getDocumentProcessor().processingMetricsSink().addPatchesPrepared(3L);

        // Then
        assertEquals(1L, existing.preparedPatchSequences());
        assertEquals(3L, existing.preparedPatches());
        assertEquals(1L, coordination.preparedPatchSequences());
        assertEquals(3L, coordination.preparedPatches());
    }

    @Test
    void shouldFanOutGenericLanguageMetricsToBothSinks() {
        // Given
        BexProcessingMetrics existing = new BexProcessingMetrics();
        BexProcessingMetrics coordination = new BexProcessingMetrics();
        Blue blue = CoordinationTestResources.configuredBlue(BlueRepository.latest());
        blue.getDocumentProcessor().processingMetricsSink(existing);

        // When
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

        // Then
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
    void shouldInstallOptionsMetricsAsBuilderLanguageSink() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        DocumentProcessor processor = CoordinationProcessors.configure(
                DocumentProcessor.builder(),
                CoordinationProcessorOptions.builder().processingMetrics(metrics).build())
                .build();

        // Then
        assertSame(metrics, processor.processingMetricsSink());
    }

    @Test
    void shouldRecordRealLanguageMultiPatchSequenceMetrics() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        Fixture fixture = configuredFixture(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
        Node preprocessed = fixture.blue.preprocess(multiPatchCounterDocument(fixture.repository));
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(preprocessed);
        BexProcessingMetrics.Snapshot before = metrics.snapshot();

        // When
        DocumentProcessingResult processed = fixture.blue.processDocument(
                ProcessingResultTestSupport.snapshot(
                        fixture.blue,
                        initialized),
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        "owner",
                        1,
                        CoordinationTestResources.operationRequest(
                                "increment", "ownerChannel", new Node().value(7))));
        BexProcessingMetrics.Snapshot after = metrics.snapshot();

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(processed), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(processed));
        assertEquals(BigInteger.valueOf(3), processed.document().get("/counter"));
        assertEquals(1L, after.preparedPatchSequences - before.preparedPatchSequences);
        assertEquals(3L, after.preparedPatches - before.preparedPatches);
        assertEquals(1L, after.languageSequenceTransactions - before.languageSequenceTransactions);
        assertEquals(0L, after.languageSingletonTransactions - before.languageSingletonTransactions);
        assertEquals(0L, after.languageSuffixRebases - before.languageSuffixRebases);
        assertEquals(0L, after.languageFallbackPatches - before.languageFallbackPatches);
        assertEquals(3L, after.languageIntermediateSnapshotAdvances
                - before.languageIntermediateSnapshotAdvances);
        assertEquals(0L, after.languageFinalSnapshotPromotions
                - before.languageFinalSnapshotPromotions);
    }

    @Test
    void shouldLoadRealRepositoryCoordinationContracts() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = counterDocument(fixture.repository, "ownerChannel");

        // When
        Node preprocessed = fixture.blue.preprocess(document.clone());
        Map<String, Node> contracts = contracts(preprocessed);
        Object convertedOperation = fixture.blue.nodeToObject(
                contracts.get("increment"), Object.class);
        Object convertedHandler = fixture.blue.nodeToObject(
                contracts.get("increment"), Object.class);

        // Then
        assertEquals(TimelineChannel.blueId(), contracts.get("ownerChannel").getType().getBlueId());
        assertEquals(SequentialWorkflowOperation.blueId(),
                contracts.get("increment").getType().getBlueId());

        assertTrue(convertedOperation instanceof SequentialWorkflowOperation);
        assertEquals("ownerChannel", ((SequentialWorkflowOperation) convertedOperation).getChannel());

        assertTrue(convertedHandler instanceof SequentialWorkflowOperation);
        SequentialWorkflowOperation handler = (SequentialWorkflowOperation) convertedHandler;
        assertEquals("ownerChannel", handler.getChannel());
        assertNotNull(handler.getRequest());
        assertNotNull(handler.getSteps());
        assertTrue(handler.getSteps().isEmpty());
    }

    @Test
    void shouldInitializeRealRepositoryCoordinationDocument() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = counterDocument(fixture.repository, "ownerChannel");
        Node preprocessed = fixture.blue.preprocess(document.clone());

        // When
        DocumentProcessingResult result = fixture.blue.initializeDocument(preprocessed);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(fixture.blue.isInitialized(result.document()));
        assertEquals(BigInteger.ZERO, result.document().getProperties().get("counter").getValue());
        assertFalse(contracts(result.document()).containsKey("checkpoint"));
    }

    @Test
    void shouldNotRunSequentialWorkflowOperationWithMissingChannel() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = counterDocument(fixture.repository, "missingChannel");
        Node preprocessed = fixture.blue.preprocess(document.clone());

        // When
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(preprocessed);
        DocumentProcessingResult processed = fixture.blue.processDocument(initialized.document(),
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        "owner",
                        1,
                        CoordinationTestResources.operationRequest(
                                "increment", "missingChannel", new Node().value(7))));

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(processed), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(processed));
        assertEquals(BigInteger.ZERO, processed.document().getProperties().get("counter").getValue());
    }

    @Test
    void shouldProvideProcessorModelBaseTypesForGeneratedContracts() {
        // Given
        Class<?> channelBase = ChannelContract.class;
        Class<?> handlerBase = HandlerContract.class;

        // When
        boolean allTimelinesIsChannel =
                channelBase.isAssignableFrom(
                        AllTimelinesChannel.class);
        boolean timelineIsChannel =
                channelBase.isAssignableFrom(
                        TimelineChannel.class);
        boolean compositeIsChannel =
                channelBase.isAssignableFrom(
                        CompositeTimelineChannel.class);
        boolean chatIsHandler =
                handlerBase.isAssignableFrom(
                        ChatWorkflowOperation.class);
        boolean workflowIsHandler =
                handlerBase.isAssignableFrom(
                        SequentialWorkflow.class);
        boolean operationIsHandler =
                handlerBase.isAssignableFrom(
                        Operation.class);
        boolean workflowOperationIsHandler =
                handlerBase.isAssignableFrom(
                        SequentialWorkflowOperation.class);

        // Then
        assertTrue(allTimelinesIsChannel);
        assertTrue(timelineIsChannel);
        assertTrue(compositeIsChannel);
        assertTrue(chatIsHandler);
        assertTrue(workflowIsHandler);
        assertTrue(operationIsHandler);
        assertTrue(workflowOperationIsHandler);
    }

    @Test
    void shouldResolveGeneratedCoordinationTypesToRepositoryClasses() {
        // Given
        TypeClassResolver resolver = BlueRepository.latest().typeClassResolver();

        // When
        Class<?> resolvedTimeline =
                resolver.resolveClass(TimelineChannel.blueId());

        // Then
        assertEquals(AllTimelinesChannel.class, resolver.resolveClass(AllTimelinesChannel.blueId()));
        assertEquals(TimelineChannel.class, resolvedTimeline);
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
        assertFalse(
                registry.lookupChannel(
                        MyOSTimelineChannel.blueId())
                        .isPresent());
        assertFalse(registry.lookupMarker(Operation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(ChatWorkflowOperation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(Operation.blueId()).isPresent());
        assertTrue(registry.lookupHandler(SequentialWorkflow.blueId()).isPresent());
        assertTrue(registry.lookupHandler(SequentialWorkflowOperation.blueId()).isPresent());
    }

    private static final String
            HOST_TIMELINE_CHANNEL_BLUE_ID =
            "3AqDqXSY5KaqBHnQqpqVf2Lw1EjTqRaP"
                    + "PvZ7M5sT7X7u";

    @TypeBlueId(HOST_TIMELINE_CHANNEL_BLUE_ID)
    private static final class HostTimelineChannel
            extends TimelineChannel {
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
