package blue.coordination.processor.compute;

import blue.bex.api.BexDocumentView;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestProcessorOptions;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.CoordinationTestRuntime;
import blue.coordination.processor.ExternalBlockerProbeAssertions;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.ProcessingEventIdentityEvidence;
import blue.coordination.processor.bex.ProcessingEventIdentityObserver;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for Coordination's immutable root Processing Event BEX binding.
 */
class ProcessingEventBindingTest {
    private static final int ROOT_TIMESTAMP = 7_000_001;
    private static final String IMPLICIT_SOURCE =
            "implicitInitializationSource";
    private static final String IMPLICIT_SUBSCRIPTION =
            "implicit-initialization";
    private static final String IMPLICIT_CHECKPOINT_DOMAIN =
            "coordination-implicit-initialization";

    @Test
    void shouldReadCompleteProcessingEventFromDirectCompute() {
        // given
        Fixture fixture = fixture();
        Node initialized = fixture.initialize(operationDocument(
                captureStep("/observation", directObservation())));

        // when
        DocumentProcessingResult result = fixture.process(initialized,
                fixture.operationEvent(ROOT_TIMESTAMP, "run", "ownerChannel",
                        new Node().properties("requestSentinel", scalar("direct-request"))));

        // then
        assertSuccess(result);
        Node resolved = fixture.runtime.resolveToSnapshot(
                result.document()).resolvedRoot();
        assertEquals("object", resolved.get("/observation/rootKind"));
        assertEquals("owner", resolved.get("/observation/rootTimeline"));
        assertEquals("owner", resolved.get("/observation/rootActor"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), resolved.get("/observation/currentTimestamp"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), resolved.get("/observation/rootTimestamp"));
        assertEquals("direct-request", resolved.get("/observation/rootRequestSentinel"));
    }

    @Test
    void shouldDistinguishTriggeredEventFromProcessingEvent() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> contracts = operationContracts();
        contracts.put("run", operationWorkflow(triggerChat("triggered-message")));
        contracts.put("triggered", new Node().type("Triggered Event Channel"));
        contracts.put("observeTriggered", workflow("triggered",
                new Node().type(ChatMessage.qualifiedName())
                        .properties("message", scalar("triggered-message")),
                captureStep("/observation", routedObservation("/message"))));
        Node initialized = fixture.initialize(document(contracts));

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        fixture.operationEvent(
                                ROOT_TIMESTAMP,
                                "run",
                                "ownerChannel",
                                scalar("request")));

        // then
        assertSuccess(result);
        assertEquals("triggered-message", result.document().get("/observation/currentSentinel"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/observation/rootTimestamp"));
        assertEquals("request", result.document().get("/observation/rootRequest"));
        assertEquals("undefined", result.document().get("/observation/currentTimestampKind"),
                "$event must remain the triggered Message, not the root Timeline Entry");
    }

    @Test
    void shouldKeepOriginalProcessingEventAcrossMultipleHops() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> contracts = operationContracts();
        contracts.put("run", operationWorkflow(triggerChat("first-hop")));
        contracts.put("triggered", new Node().type("Triggered Event Channel"));
        contracts.put("firstHop", workflow("triggered", chatMatcher("first-hop"), triggerChat("second-hop")));
        contracts.put("secondHop", workflow("triggered", chatMatcher("second-hop"),
                captureStep("/observation", routedObservation("/message"))));
        Node initialized = fixture.initialize(document(contracts));

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        fixture.operationEvent(
                                ROOT_TIMESTAMP,
                                "run",
                                "ownerChannel",
                                scalar("request")));

        // then
        assertSuccess(result);
        assertEquals("second-hop", result.document().get("/observation/currentSentinel"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/observation/rootTimestamp"));
    }

    @Test
    void shouldObserveStableIdentityAcrossWorkflowAndBexBoundaries() {
        // given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        Fixture fixture = fixture(evidence);
        Map<String, Node> contracts = operationContracts();
        contracts.put(
                "run",
                operationWorkflow(
                        triggerChat("first-hop")));
        contracts.put(
                "triggered",
                new Node().type(
                        "Triggered Event Channel"));
        contracts.put(
                "observe",
                workflow(
                        "triggered",
                        chatMatcher("first-hop"),
                        captureStep(
                                "/observation",
                                binding(
                                        "processingEvent"
                                                + "/timestamp"))));
        Node initialized =
                fixture.initialize(
                        document(contracts));
        Node rootEvent =
                fixture.operationEvent(
                        ROOT_TIMESTAMP,
                        "run",
                        "ownerChannel",
                        scalar("request"));
        String expectedEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        rootEvent);

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        rootEvent);
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // then
        assertSuccess(result);
        assertTrue(snapshot.observed());
        assertTrue(snapshot.stable());
        assertNotNull(snapshot.admittedBlueId());
        assertEquals(
                expectedEventBlueId,
                snapshot.admittedBlueId());
        assertEquals(2L, snapshot.workflowObservations());
        assertEquals(1L, snapshot.bexBindingObservations());
    }

    @Test
    void shouldReadProcessingEventDuringImplicitInitialization() {
        // given
        Fixture fixture = fixture();
        Node rootEvent = new Node()
                .properties("kind", scalar("implicit-root"))
                .properties("nested", new Node().properties("answer", scalar(42)));

        // when
        DocumentProcessingResult result = fixture.processUninitialized(
                lifecycleDocument(binding("processingEvent")), rootEvent);

        // then
        assertSuccess(result);
        assertEquals("implicit-root", result.document().get("/observation/kind"));
        assertEquals(BigInteger.valueOf(42), result.document().get("/observation/nested/answer"));
    }

    @Test
    void shouldReadUndefinedDuringExplicitInitialization() {
        // given
        Fixture fixture = fixture();
        Node fallback = operation("$coalesce", new Node().items(
                binding("processingEvent"), scalar("undefined")));

        // when
        DocumentProcessingResult result = fixture.initializeResult(lifecycleDocument(fallback));

        // then
        assertSuccess(result);
        assertEquals("undefined", result.document().get("/observation"));
        assertEquals(0L, fixture.metrics.processEventSnapshotAttempts());
    }

    @Test
    void shouldReadRootProcessingEventFromEmbeddedScope() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> childContracts = operationContracts();
        childContracts.put("run", operationWorkflow(
                captureStep("/observation", routedObservation("/message/request"))));
        Node child = document(childContracts);
        Map<String, Node> rootContracts = new LinkedHashMap<String, Node>();
        rootContracts.put("embedded", new Node().type("Process Embedded")
                .properties("paths", new Node().items(scalar("/child"))));
        Node root = document(rootContracts).properties("child", child);
        Node initialized = fixture.initialize(root);

        // when
        DocumentProcessingResult result = fixture.process(initialized,
                fixture.operationEvent(ROOT_TIMESTAMP, "run", "ownerChannel", scalar("child-request")));

        // then
        assertSuccess(result);
        assertEquals("child-request", result.document().get("/child/observation/currentSentinel"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/child/observation/rootTimestamp"));
    }

    @Test
    void shouldReadRootProcessingEventFromBridgeHandler() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> childContracts = operationContracts();
        childContracts.put("run", operationWorkflow(triggerChat("from-child")));
        Node child = document(childContracts);
        Map<String, Node> rootContracts = new LinkedHashMap<String, Node>();
        rootContracts.put("embedded", new Node().type("Process Embedded")
                .properties("paths", new Node().items(scalar("/child"))));
        rootContracts.put("childBridge", new Node().type("Embedded Node Channel")
                .properties("sourcePath", scalar("/child")));
        rootContracts.put("observeBridge", workflow("childBridge", chatMatcher("from-child"),
                captureStep("/observation", routedObservation("/message"))));
        Node initialized = fixture.initialize(document(rootContracts).properties("child", child));

        // when
        ProcessingDebugResult debug =
                fixture.processWithTrace(
                        initialized,
                        fixture.operationEvent(
                                ROOT_TIMESTAMP,
                                "run",
                                "ownerChannel",
                                scalar("request")));
        DocumentProcessingResult result =
                debug.processResult();

        // then
        boolean childHandlerExecuted = false;
        boolean childEmissionQueued = false;
        boolean bridgeHandlerExecuted = false;
        for (ProcessingTraceRecord record :
                debug.trace().records()) {
            if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .HANDLER_EXECUTION) {
                childHandlerExecuted |= "/child".equals(
                        record.scopePath())
                        && "run".equals(
                        record.contractKey());
                bridgeHandlerExecuted |= "/".equals(
                        record.scopePath())
                        && "observeBridge".equals(
                        record.contractKey());
            }
            if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .EVENT_ENQUEUED
                    && record.node() != null) {
                childEmissionQueued |= "from-child"
                        .equals(
                                valueAt(
                                        record.node(),
                                        "/message"));
            }
        }
        boolean bridgeObserved =
                "from-child".equals(
                        valueAt(
                                result.document(),
                                "/observation/currentSentinel"));
        ExternalBlockerProbeAssertions.classify(
                "embedded-node-channel-bridge",
                "Language Embedded Node Channel bridge defect:",
                result.status() == ProcessorStatus.SUCCESS
                        && childHandlerExecuted
                        && childEmissionQueued
                        && !bridgeHandlerExecuted
                        && !bridgeObserved,
                result.status() == ProcessorStatus.SUCCESS
                        && childHandlerExecuted
                        && childEmissionQueued
                        && bridgeHandlerExecuted
                        && bridgeObserved,
                ExternalBlockerProbeAssertions
                        .resultTuple(result)
                        + ", childHandlerExecuted="
                        + childHandlerExecuted
                        + ", childEmissionQueued="
                        + childEmissionQueued
                        + ", bridgeHandlerExecuted="
                        + bridgeHandlerExecuted
                        + ", bridgeObserved="
                        + bridgeObserved);
        assertSuccess(result);
        assertEquals("from-child", result.document().get("/observation/currentSentinel"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/observation/rootTimestamp"));
    }

    @Test
    void shouldSupportNonTimelineScalarListAndObjectEvents() {
        // given
        Node[] events = {
                scalar("scalar-root"),
                new Node().items(scalar("first"), scalar(2), scalar(true)),
                new Node().properties("kind", scalar("object-root"))
        };
        Fixture[] fixtures = {
                fixture(),
                fixture(),
                fixture()
        };

        // when
        List<DocumentProcessingResult> results =
                new ArrayList<DocumentProcessingResult>();
        for (int index = 0;
             index < events.length;
             index++) {
            results.add(
                    fixtures[index]
                            .processUninitialized(
                                    lifecycleDocument(
                                            binding(
                                                    "processingEvent")),
                                    events[index]));
        }

        // then
        for (int index = 0;
             index < events.length;
             index++) {
            DocumentProcessingResult result =
                    results.get(index);
            assertSuccess(result);
            assertNodeShapeEquals(
                    events[index],
                    result.document()
                            .getProperties()
                            .get("observation"));
        }
    }

    @Test
    void shouldPreservePureReferenceProcessingEventIdentity() {
        // given
        Fixture fixture = fixture();
        Node reference = new Node().blueId(ChatMessage.blueId());

        // when
        DocumentProcessingResult result = fixture.processUninitialized(
                lifecycleDocument(binding("processingEvent")), reference);

        // then
        assertSuccess(result);
        Node observed = result.document().getAsNode("/observation");
        assertTrue(observed.isReferenceOnly());
        assertEquals(ChatMessage.blueId(), observed.getBlueId());
    }

    @Test
    void shouldNotLeakProcessingEventAcrossSeparateRuns() {
        // given
        Fixture fixture = fixture();
        Node initialized = fixture.initialize(operationDocument(
                captureStep("/observation", binding("processingEvent/timestamp"))));

        // when
        DocumentProcessingResult first = fixture.process(initialized,
                fixture.operationEvent(101, "run", "ownerChannel", scalar("first")));
        DocumentProcessingResult second = fixture.process(first.document(),
                fixture.operationEvent(202, "run", "ownerChannel", scalar("second")));

        // then
        assertSuccess(first);
        assertSuccess(second);
        assertEquals(BigInteger.valueOf(101), first.document().get("/observation"));
        assertEquals(BigInteger.valueOf(202), second.document().get("/observation"));
        assertEquals(2L, fixture.metrics.processEventSnapshotAttempts());
        assertEquals(2L, fixture.metrics.processEventSnapshotBuilds());
    }

    @Test
    void shouldAvoidSnapshotsForWideAndDeepUnusedEvents() {
        // given
        Fixture fixture = fixture();
        Node wideEvent = wideEvent();
        Node deepEvent = deepEvent();

        // when
        DocumentProcessingResult wide = fixture.processUninitialized(
                lifecycleDocument(scalar("unused")), wideEvent);
        DocumentProcessingResult deep = fixture.processUninitialized(
                lifecycleDocument(scalar("unused")), deepEvent);

        // then
        assertSuccess(wide);
        assertSuccess(deep);
        assertEquals(
                "unused",
                wide.document().get("/observation"));
        assertEquals(
                "unused",
                deep.document().get("/observation"));
        assertEquals(0L, fixture.metrics.processEventSnapshotAttempts());
        assertEquals(0L, fixture.metrics.processEventSnapshotBuilds());
        assertEquals(0L, fixture.metrics.processEventSnapshotFailures());
        assertEquals(0L, fixture.metrics.processEventSnapshotConstructionNanos());
    }

    @Test
    void shouldBuildOneSnapshotOnFirstBindingRead() {
        // given
        Fixture fixture = fixture();
        Node event = wideEvent();

        // when
        DocumentProcessingResult result = fixture.processUninitialized(
                lifecycleDocument(binding("processingEvent")), event);

        // then
        assertSuccess(result);
        assertNodeShapeEquals(
                event,
                result.document()
                        .getProperties()
                        .get("observation"));
        assertEquals(1L, fixture.metrics.processEventSnapshotAttempts());
        assertEquals(1L, fixture.metrics.processEventSnapshotBuilds());
        assertEquals(0L, fixture.metrics.processEventSnapshotFailures());
        assertTrue(fixture.metrics.processEventSnapshotConstructionNanos() >= 0L);
    }

    @Test
    void shouldBuildOneSnapshotForManyReadsInOneRun() {
        // given
        Fixture fixture = fixture();
        Node initialized = fixture.initialize(operationDocument(
                captureStep("/observation", binding("processingEvent/timestamp")),
                captureStep("/secondObservation", binding("processingEvent/message/request")),
                captureStep("/thirdObservation", routedObservation("/message/request"))));

        // when
        DocumentProcessingResult result = fixture.process(initialized,
                fixture.operationEvent(ROOT_TIMESTAMP, "run", "ownerChannel", scalar("request")));

        // then
        assertSuccess(result);
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/observation"));
        assertEquals("request", result.document().get("/secondObservation"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/thirdObservation/rootTimestamp"));
        assertEquals(1L, fixture.metrics.processEventSnapshotAttempts());
        assertEquals(1L, fixture.metrics.processEventSnapshotBuilds());
    }

    @Test
    void shouldNotChargeMoreGasForProcessingEventBinding() {
        // given
        Fixture currentEventFixture = fixture();
        Fixture processingEventFixture = fixture();
        Node currentDocument = currentEventFixture.initialize(directTimelineDocument(binding("event/timestamp")));
        Node processingDocument = processingEventFixture.initialize(
                directTimelineDocument(binding("processingEvent/timestamp")));
        Node currentEvent = currentEventFixture.timelineEvent(ROOT_TIMESTAMP, scalar("same"));
        Node processingEvent = processingEventFixture.timelineEvent(ROOT_TIMESTAMP, scalar("same"));

        // when
        DocumentProcessingResult currentResult = currentEventFixture.process(currentDocument, currentEvent);
        DocumentProcessingResult processingResult = processingEventFixture.process(processingDocument, processingEvent);

        // then
        assertSuccess(currentResult);
        assertSuccess(processingResult);
        assertTrue(
                processingResult.totalGas()
                        <= currentResult.totalGas(),
                "the exact processing-event binding may reuse admitted "
                        + "identity but must not cost more than the current event");
        assertEquals(0L, currentEventFixture.metrics.processEventSnapshotAttempts());
        assertEquals(1L, processingEventFixture.metrics.processEventSnapshotAttempts());
    }

    @Test
    void shouldAddZeroGasForUnusedEagerProcessingEventBinding() {
        // given
        BexEngine engine = BexEngine.builder().build();
        BexProgramSource source = BexProgramSource.expression(FrozenNode.fromResolvedNode(scalar("result")));
        BexExecutionContext withoutBinding = bareBexContext().build();
        BexExecutionContext withUnusedBinding = bareBexContext()
                .processingEvent(BexValues.scalar("unused"))
                .build();

        // when
        BexExecutionResult withoutResult = engine.compileAndExecute(source, withoutBinding);
        BexExecutionResult withResult = engine.compileAndExecute(source, withUnusedBinding);

        // then
        assertEquals(withoutResult.gasUsed(), withResult.gasUsed());
    }

    @Test
    void shouldFanOutLanguageObservationsWithoutMixingWorkflowMetrics() {
        // given
        BexProcessingMetrics processorMetrics = new BexProcessingMetrics();
        BexProcessingMetrics workflowMetrics = new BexProcessingMetrics();
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime runtime =
                CoordinationTestResources.configuredBlue(repository);
        runtime.configure(
                CoordinationProcessorOptions.builder()
                        .processingMetrics(workflowMetrics)
                        .build(),
                processorMetrics);
        configureImplicitInitializationSource(
                runtime);
        Node document = withImplicitInitializationSource(
                lifecycleDocument(
                        binding("processingEvent")))
                .blue(repository.importsDirective());
        Node event =
                new Node().properties(
                        "kind", scalar("root"));
        Node prepared =
                runtime.preprocess(document);
        String originalEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        event);
        List<String> expectedExactBlueIds =
                ExternalBlockerProbeAssertions
                        .expectedExactBlueIds(
                                prepared, event);

        // when
        ProcessingDebugResult debug;
        try {
            debug = runtime.processor()
                    .processDocumentWithTrace(
                            prepared, event);
        } catch (RuntimeException failure) {
            ExternalBlockerProbeAssertions
                    .classifyImplicitInitializationFailure(
                            failure,
                            expectedExactBlueIds,
                            "independent metrics sinks");
            throw failure;
        }
        DocumentProcessingResult result =
                debug.processResult();

        // then
        ExternalBlockerProbeAssertions
                .requireImplicitInitializationSuccess(
                        debug,
                        IMPLICIT_SOURCE,
                        originalEventBlueId,
                        "independent metrics sinks");
        assertSuccess(result);
        assertEquals(1L, processorMetrics.processEventSnapshotAttempts());
        assertEquals(1L, workflowMetrics.processEventSnapshotAttempts());
        assertEquals(0L, processorMetrics.computeStepsExecuted());
        assertEquals(1L, workflowMetrics.computeStepsExecuted());
    }

    private static BexExecutionContext.Builder bareBexContext() {
        return BexExecutionContext.builder()
                .document(EmptyDocumentView.INSTANCE)
                .gasLimit(10_000L);
    }

    private static Node directTimelineDocument(Node returnValue) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel("owner"));
        contracts.put("observe", workflow("ownerChannel", null, computeReturnStep(returnValue)));
        return document(contracts);
    }

    private static Node operationDocument(Node... steps) {
        Map<String, Node> contracts = operationContracts();
        contracts.put("run", operationWorkflow(steps));
        return document(contracts);
    }

    private static Map<String, Node> operationContracts() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", TestTimelineProvider.channel("owner"));
        return contracts;
    }

    private static Node operationWorkflow(Node... steps) {
        return new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", scalar("ownerChannel"))
                .properties("steps", new Node().items(steps));
    }

    private static Node workflow(String channel, Node event, Node... steps) {
        Node workflow = new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", scalar(channel))
                .properties("steps", new Node().items(steps));
        if (event != null) {
            workflow.properties("event", event);
        }
        return workflow;
    }

    private static Node lifecycleDocument(Node observation) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("lifecycle", new Node().type("Lifecycle Event Channel"));
        contracts.put("observeInitialization", workflow("lifecycle",
                null,
                captureStep("/observation", observation)));
        return document(contracts);
    }

    private static Node document(Map<String, Node> contracts) {
        return new Node()
                .name("Processing Event Binding Test")
                .properties("observation", scalar("unset"))
                .properties("secondObservation", scalar("unset"))
                .properties("thirdObservation", scalar("unset"))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node captureStep(String path, Node value) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        operation("$appendChange", new Node()
                                .properties("op", scalar("replace"))
                                .properties("path", scalar(path))
                                .properties("val", value)),
                        operation("$return", new Node()
                                .properties("changeset", operation("$changeset", scalar(true))))));
    }

    private static Node computeReturnStep(Node value) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(operation("$return", value)));
    }

    private static Node triggerChat(String message) {
        return new Node()
                .type("Coordination/Trigger Event")
                .properties("event", new Node()
                        .type(ChatMessage.qualifiedName())
                        .properties("message", scalar(message)));
    }

    private static Node chatMatcher(String message) {
        return new Node()
                .type(ChatMessage.qualifiedName())
                .properties("message", scalar(message));
    }

    private static Node directObservation() {
        return new Node()
                .properties("rootKind", operation("$kind", binding("processingEvent")))
                .properties("rootTimeline", binding("processingEvent/timeline/timelineId"))
                .properties("rootActor", binding("processingEvent/actor/accountId"))
                .properties("currentTimestamp", event("/timestamp"))
                .properties("rootTimestamp", binding("processingEvent/timestamp"))
                .properties("rootRequestSentinel",
                        binding("processingEvent/message/request/requestSentinel"));
    }

    private static Node routedObservation(String currentPath) {
        return new Node()
                .properties("currentSentinel", event(currentPath))
                .properties("currentTimestampKind", operation("$kind", binding("event/timestamp")))
                .properties("rootTimestamp", binding("processingEvent/timestamp"))
                .properties("rootRequest", binding("processingEvent/message/request"));
    }

    private static Node binding(String path) {
        return operation("$binding", scalar(path));
    }

    private static Node event(String path) {
        return operation("$event", scalar(path));
    }

    private static Node operation(String name, Node argument) {
        return new Node().properties(name, argument);
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static Node withImplicitInitializationSource(
            Node document) {
        Node prepared = document.clone();
        Node contracts = prepared.getContracts();
        if (contracts == null) {
            contracts = new Node();
            prepared.properties("contracts", contracts);
        }
        contracts.properties(
                IMPLICIT_SOURCE,
                new Node()
                        .type(new Node().blueId(
                                RuntimeBlueIds
                                        .SCRIPTED_EXTERNAL_CHANNEL))
                        .properties(
                                "subscriptionKey",
                                scalar(
                                        IMPLICIT_SUBSCRIPTION))
                        .properties(
                                "checkpointDomain",
                                scalar(
                                        IMPLICIT_CHECKPOINT_DOMAIN)));
        return prepared;
    }

    private static void configureImplicitInitializationSource(
            CoordinationTestRuntime runtime) {
        runtime.registerExternalContractType(
                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                BlueRuntimeTypeRegistry.getDefault()
                        .node(RuntimeTypeKey
                                .SCRIPTED_EXTERNAL_CHANNEL),
                new ImplicitInitializationChannelProcessor());
    }

    private static Object valueAt(
            Node node,
            String path) {
        try {
            return node != null
                    ? node.get(path)
                    : null;
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static Node wideEvent() {
        Node event = new Node().properties("kind", scalar("wide"));
        for (int index = 0; index < 256; index++) {
            event.properties("field" + index, scalar(index));
        }
        return event;
    }

    private static Node deepEvent() {
        Node event = new Node().properties("kind", scalar("deep"));
        Node cursor = event;
        for (int index = 0; index < 128; index++) {
            Node child = new Node();
            cursor.properties("next", child);
            cursor = child;
        }
        cursor.properties("leaf", scalar("end"));
        return event;
    }

    private static void assertNodeShapeEquals(Node expected, Node actual) {
        assertNotNull(actual);
        assertScalarEquals(expected.getValue(), actual.getValue());
        if (expected.getItems() == null) {
            assertNull(actual.getItems());
        } else {
            assertNotNull(actual.getItems());
            assertEquals(expected.getItems().size(), actual.getItems().size());
            for (int index = 0; index < expected.getItems().size(); index++) {
                assertNodeShapeEquals(expected.getItems().get(index), actual.getItems().get(index));
            }
        }
        if (expected.getProperties() == null) {
            assertNull(actual.getProperties());
        } else {
            assertNotNull(actual.getProperties());
            assertEquals(expected.getProperties().keySet(), actual.getProperties().keySet());
            for (String key : expected.getProperties().keySet()) {
                assertNodeShapeEquals(expected.getProperties().get(key), actual.getProperties().get(key));
            }
        }
    }

    private static void assertScalarEquals(Object expected, Object actual) {
        if (expected instanceof Number && actual instanceof Number) {
            assertEquals(new BigDecimal(expected.toString()), new BigDecimal(actual.toString()));
            return;
        }
        assertEquals(expected, actual);
    }

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result));
    }

    private static Fixture fixture() {
        return fixture(null);
    }

    private static Fixture fixture(
            ProcessingEventIdentityObserver
                    processingEventIdentityObserver) {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime runtime =
                CoordinationTestResources.configuredBlue(repository);
        runtime.configure(
                CoordinationTestProcessorOptions
                        .withProcessingEventIdentityEvidence(
                                metrics,
                                processingEventIdentityObserver));
        configureImplicitInitializationSource(
                runtime);
        return new Fixture(repository, runtime, metrics);
    }

    @TypeBlueId(RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL)
    public static final class ImplicitInitializationChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;

        public ImplicitInitializationChannel() {
        }

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }
    }

    private static final class
            ImplicitInitializationChannelProcessor
            implements ChannelProcessor<ImplicitInitializationChannel> {
        private final ExternalChannelSubscriptionFunctions<
                ImplicitInitializationChannel> subscriptions =
                new ExternalChannelSubscriptionFunctions<
                        ImplicitInitializationChannel>() {
                    @Override
                    public List<String> channelKeys(
                            ImplicitInitializationChannel contract) {
                        return Collections.singletonList(
                                contract.getSubscriptionKey());
                    }

                    @Override
                    public List<String> eventKeys(
                            Node event) {
                        return Collections.singletonList(
                                IMPLICIT_SUBSCRIPTION);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            ImplicitInitializationChannel contract) {
                        return contract
                                .getCheckpointDomain();
                    }
                };

        @Override
        public Class<ImplicitInitializationChannel> contractType() {
            return ImplicitInitializationChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                ImplicitInitializationChannel> externalSubscriptionFunctions() {
            return subscriptions;
        }

        @Override
        public ChannelEvaluation evaluate(
                ImplicitInitializationChannel contract,
                ChannelEvaluationContext context) {
            return ChannelEvaluation.match(
                    context.event(),
                    null);
        }
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final CoordinationTestRuntime runtime;
        private final BexProcessingMetrics metrics;

        Fixture(
                BlueRepository repository,
                CoordinationTestRuntime runtime,
                BexProcessingMetrics metrics) {
            this.repository = repository;
            this.runtime = runtime;
            this.metrics = metrics;
        }

        Node initialize(Node document) {
            return initializeResult(document).document();
        }

        DocumentProcessingResult initializeResult(Node document) {
            document.blue(repository.importsDirective());
            return runtime.initializeDocument(
                    runtime.preprocess(document));
        }

        DocumentProcessingResult process(Node document, Node event) {
            return runtime.processDocument(document, event);
        }

        ProcessingDebugResult processWithTrace(
                Node document,
                Node event) {
            return runtime.processor()
                    .processDocumentWithTrace(
                            document, event);
        }

        DocumentProcessingResult processUninitialized(Node document, Node event) {
            Node prepared =
                    withImplicitInitializationSource(
                            document);
            prepared.blue(
                    repository.importsDirective());
            Node preprocessed =
                    runtime.preprocess(prepared);
            String originalEventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            event);
            List<String> expectedExactBlueIds =
                    ExternalBlockerProbeAssertions
                            .expectedExactBlueIds(
                                    preprocessed,
                                    event);
            ProcessingDebugResult debug;
            try {
                debug = runtime.processor()
                        .processDocumentWithTrace(
                                preprocessed,
                                event);
            } catch (RuntimeException failure) {
                ExternalBlockerProbeAssertions
                        .classifyImplicitInitializationFailure(
                                failure,
                                expectedExactBlueIds,
                                "processing-event binding");
                throw failure;
            }
            ExternalBlockerProbeAssertions
                    .requireImplicitInitializationSuccess(
                            debug,
                            IMPLICIT_SOURCE,
                            originalEventBlueId,
                            "processing-event binding");
            return debug.processResult();
        }

        Node operationEvent(int timestamp,
                            String operation,
                            String channel,
                            Node request) {
            return TestTimelineProvider.timelineEntry(runtime,
                    repository,
                    "owner",
                    "owner",
                    BigInteger.valueOf(timestamp),
                    CoordinationTestResources.operationRequest(operation, channel, request));
        }

        Node timelineEvent(int timestamp, Node message) {
            return TestTimelineProvider.timelineEntry(runtime,
                    repository,
                    "owner",
                    "owner",
                    BigInteger.valueOf(timestamp),
                    message);
        }
    }

    private enum EmptyDocumentView implements BexDocumentView {
        INSTANCE;

        @Override
        public String resolvePointer(String pointer) {
            return pointer;
        }

        @Override
        public BexValue canonicalAt(String pointer) {
            return BexValues.undefined();
        }

        @Override
        public BexValue resolvedAt(String pointer) {
            return BexValues.undefined();
        }

        @Override
        public String currentScopePath() {
            return "/";
        }
    }
}
