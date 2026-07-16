package blue.coordination.processor;

import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.coordination.processor.workflow.StepExecutionContext;
import blue.coordination.processor.workflow.WorkflowStepExecutor;
import blue.coordination.processor.workflow.WorkflowStepResult;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelDelivery;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessingMetricsSink;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.Authority;
import blue.repo.coordination.Compute;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TimelineChannel;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationRequestRoutingIntegrationTest {
    private static final String ALICE_CHANNEL = "aliceChannel";
    private static final String BOB_CHANNEL = "bobChannel";
    private static final String ALICE_TIMELINE = "alice-timeline";
    private static final String ALICE_ACTOR = "alice-account";

    @Test
    void crossChannelRequestRunsTargetOperationAndKeepsSourceCheckpoint() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)));

        assertSuccess(result);
        assertEquals(BigInteger.ONE, result.document().get("/counter"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
        assertNull(checkpoint(result.document(), BOB_CHANNEL));
    }

    @Test
    void sourceActorMismatchRejectsBeforeRouting() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);
        Node event = timelineEntry(fixture,
                ALICE_TIMELINE,
                "intruder",
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)));

        DocumentProcessingResult result = fixture.blue.processDocument(initialized, event);

        assertSuccess(result);
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void sourceTimelineMismatchRejectsBeforeRouting() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);
        Node event = timelineEntry(fixture,
                "different-timeline",
                ALICE_ACTOR,
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)));

        DocumentProcessingResult result = fixture.blue.processDocument(initialized, event);

        assertSuccess(result);
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void sourceEventFilterRejectsBeforeRouting() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.get(ALICE_CHANNEL).properties("definition", new Node()
                .properties("source", new Node()
                        .properties("kind", new Node().value("allowed"))));
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);
        Node event = timelineEntry(fixture,
                ALICE_TIMELINE,
                ALICE_ACTOR,
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)))
                .properties("source", new Node().properties("kind", new Node().value("denied")));

        DocumentProcessingResult result = fixture.blue.processDocument(initialized, event);

        assertSuccess(result);
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void routedHandlerSeesFullRootAttributionWithoutTargetActorSubstitution() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("capture", captureEventOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);
        Node message = request("capture", BOB_CHANNEL, new Node().value(7))
                .properties("document", new Node()
                        .blueId("2vz831ZwzhpUefTb5XkodBRANKpFMbj1F4CN33kf38Hw"))
                .properties("requireExactDocumentVersion", new Node().value(true))
                .properties("specializedField", new Node().value("preserved"));
        Node event = timelineEntry(fixture, ALICE_TIMELINE, ALICE_ACTOR, 1, message)
                .properties("source", new Node().properties("kind", new Node().value("verified-api")))
                .properties("onBehalfOf", new Node()
                        .type(new Node().blueId(Authority.blueId()))
                        .properties("label", new Node().value("mandate-owner")));

        DocumentProcessingResult result = fixture.blue.processDocument(initialized, event);

        assertSuccess(result);
        assertEquals(ALICE_TIMELINE, result.document().get("/captured/timeline/timelineId"));
        assertEquals(ALICE_ACTOR, result.document().get("/captured/actor/accountId"));
        assertEquals("verified-api", result.document().get("/captured/source/kind"));
        assertEquals("mandate-owner", result.document().get("/captured/onBehalfOf/label"));
        assertEquals("preserved", result.document().get("/captured/message/specializedField"));
        assertEquals(BOB_CHANNEL, result.document().get("/captured/message/channel"));
        assertEquals(Boolean.TRUE,
                result.document().get("/captured/message/requireExactDocumentVersion"));
        assertEquals("2vz831ZwzhpUefTb5XkodBRANKpFMbj1F4CN33kf38Hw",
                result.document().getAsNode("/captured/message/document").getBlueId());
    }

    @Test
    void unknownRequestTargetKeepsOrdinaryDeliveryAndCheckpoint() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("ordinaryObserver", ordinaryObserver(ALICE_CHANNEL));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("increment", "missingChannel", new Node().value(7)));

        assertSuccess(result);
        assertEquals(BigInteger.ONE, result.document().get("/ordinaryCount"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void nonChannelRequestTargetKeepsOrdinaryDeliveryAndCheckpoint() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("ordinaryObserver", ordinaryObserver(ALICE_CHANNEL));
        contracts.put("notAChannel", new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value(ALICE_CHANNEL))
                .properties("steps", new Node().items()));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("increment", "notAChannel", new Node().value(7)));

        assertSuccess(result);
        assertEquals(BigInteger.ONE, result.document().get("/ordinaryCount"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void malformedRoutingFieldsStayOrdinaryAndAdvanceCheckpoint() {
        Node[] malformedRequests = new Node[] {
                requestWithOptionalRoute(null, BOB_CHANNEL),
                requestWithOptionalRoute(" \t", BOB_CHANNEL),
                requestWithOptionalRoute("increment", null),
                requestWithOptionalRoute("increment", " \n")
        };

        for (Node malformedRequest : malformedRequests) {
            Fixture fixture = fixture();
            Map<String, Node> contracts = baseContracts();
            contracts.put("ordinaryObserver", ordinaryObserver(ALICE_CHANNEL));
            Node initialized = initialize(fixture, contracts);
            DocumentProcessingResult result = process(fixture,
                    initialized,
                    1,
                    malformedRequest);

            assertSuccess(result);
            assertEquals(BigInteger.ONE, result.document().get("/ordinaryCount"));
            assertEquals(BigInteger.ONE,
                    checkpoint(result.document(), ALICE_CHANNEL).get("/sequence"));
        }
    }

    @Test
    void unknownOperationRunsNoHandlerButAdvancesSourceCheckpoint() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("ordinaryObserver", ordinaryObserver(ALICE_CHANNEL));
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("missingOperation", BOB_CHANNEL, new Node().value(7)));

        assertSuccess(result);
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertEquals(BigInteger.ZERO, result.document().get("/ordinaryCount"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void targetOperationRequestPatternRemainsMandatory() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("increment", BOB_CHANNEL, new Node().value("7")));

        assertSuccess(result);
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertEquals(BigInteger.ONE, checkpoint(result.document(), ALICE_CHANNEL).get("/sequence"));
    }

    @Test
    void targetOperationEventPatternRemainsMandatory() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("increment", incrementOperation(BOB_CHANNEL)
                .properties("event", new Node()
                        .properties("source", new Node()
                                .properties("kind", new Node().value("allowed")))));
        Node initialized = initialize(fixture, contracts);
        Node event = timelineEntry(fixture,
                ALICE_TIMELINE,
                ALICE_ACTOR,
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)))
                .properties("source", new Node().properties("kind", new Node().value("denied")));

        DocumentProcessingResult result = fixture.blue.processDocument(initialized, event);

        assertSuccess(result);
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertEquals(BigInteger.ONE, checkpoint(result.document(), ALICE_CHANNEL).get("/sequence"));
    }

    @Test
    void firstCompositeSourceSuppliesPayloadWhileDuplicateSourcesCheckpoint() {
        RecordingMetrics metrics = new RecordingMetrics();
        Fixture fixture = fixture(metrics, null);
        Map<String, Node> contracts = baseContracts();
        contracts.get(ALICE_CHANNEL).properties("order", new Node().value(0));
        contracts.put("aliceComposite", composite(-10, ALICE_CHANNEL));
        contracts.put("recordWinner", recordMetadataOperation(
                BOB_CHANNEL, "compositeSourceChannelKey"));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("recordWinner", BOB_CHANNEL, new Node().value(7)));

        assertSuccess(result);
        assertEquals(ALICE_CHANNEL, result.document().get("/winner"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
        assertNotNull(checkpoint(result.document(), "aliceComposite"));
        assertNull(checkpoint(result.document(), "aliceComposite::" + ALICE_CHANNEL));
        assertEquals(1, metrics.routedDeliveries);
        assertEquals(1, metrics.deduplicatedDeliveries);
        assertEquals(1, metrics.handlersExecuted);
    }

    @Test
    void firstAllTimelinesSourceSuppliesPayloadWhileDuplicateSourcesCheckpoint() {
        RecordingMetrics metrics = new RecordingMetrics();
        Fixture fixture = fixture(metrics, null);
        Map<String, Node> contracts = baseContracts();
        contracts.get(ALICE_CHANNEL).properties("order", new Node().value(0));
        contracts.put("all", new Node()
                .type("Coordination/All Timelines Channel")
                .properties("order", new Node().value(-10)));
        contracts.put("recordWinner", recordMetadataOperation(
                BOB_CHANNEL, "allTimelinesSourceChannelKey"));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("recordWinner", BOB_CHANNEL, new Node().value(7)));

        assertSuccess(result);
        assertEquals(ALICE_CHANNEL, result.document().get("/winner"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
        assertNotNull(checkpoint(result.document(), "all"));
        assertNull(checkpoint(result.document(), "all::" + ALICE_CHANNEL));
        assertEquals(1, metrics.routedDeliveries);
        assertEquals(1, metrics.deduplicatedDeliveries);
        assertEquals(1, metrics.handlersExecuted);
    }

    @Test
    void severalMatchingDirectSourcesInvokeTargetOnceAndPersistOwnCheckpoints() {
        RecordingMetrics metrics = new RecordingMetrics();
        Fixture fixture = fixture(metrics, null);
        Map<String, Node> contracts = baseContracts();
        contracts.put("aliceMirror", timelineChannel(ALICE_TIMELINE, ALICE_ACTOR));
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)));

        assertEquals(BigInteger.ONE, result.document().get("/counter"));
        assertNotNull(checkpoint(result.document(), ALICE_CHANNEL));
        assertNotNull(checkpoint(result.document(), "aliceMirror"));
        assertEquals(1, metrics.routedDeliveries);
        assertEquals(1, metrics.deduplicatedDeliveries);
        assertEquals(1, metrics.handlersExecuted);
    }

    @Test
    void staleSourceDoesNotPiggybackOnSuccessfulRoute() {
        Fixture fixture = fixture();
        fixture.blue.registerContractProcessor(TimelineChannel.blueId(),
                new SelectiveFreshnessTimelineProcessor());
        Map<String, Node> contracts = baseContracts();
        contracts.put("freshSource", timelineChannel(ALICE_TIMELINE, ALICE_ACTOR));
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult backfill = process(fixture,
                initialized,
                5,
                request("increment", BOB_CHANNEL, new Node().value(7)));

        assertSuccess(backfill);
        assertEquals(BigInteger.ONE, backfill.document().get("/counter"));
        assertNull(checkpoint(backfill.document(), ALICE_CHANNEL));
        assertEquals(BigInteger.valueOf(5),
                checkpoint(backfill.document(), "freshSource").get("/sequence"));
    }

    @Test
    void targetHandlerFailurePersistsNoSourceCheckpoint() {
        Fixture fixture = fixture();
        Map<String, Node> contracts = baseContracts();
        contracts.put("fail", operation(BOB_CHANNEL,
                new Node().type("Integer"),
                failStep("target handler failed")));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("fail", BOB_CHANNEL, new Node().value(7)));

        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTrue(result.failureReason().contains("target handler failed"), result.failureReason());
        assertNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void targetGracefulTerminationPersistsNoSourceCheckpoint() {
        SequentialWorkflowRunner runner = new SequentialWorkflowRunner(
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        new GracefulTerminationExecutor()));
        Fixture fixture = fixture(null, runner);
        Map<String, Node> contracts = baseContracts();
        contracts.put("finish", operation(BOB_CHANNEL,
                new Node().type("Integer"),
                new Node().type("Coordination/Compute")));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = process(fixture,
                initialized,
                1,
                request("finish", BOB_CHANNEL, new Node().value(7)));

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    @Test
    void replayAfterCommittedSourceCheckpointsRunsNothing() {
        RecordingMetrics metrics = new RecordingMetrics();
        Fixture fixture = fixture(metrics, null);
        Map<String, Node> contracts = baseContracts();
        contracts.put("aliceMirror", timelineChannel(ALICE_TIMELINE, ALICE_ACTOR));
        contracts.put("increment", incrementOperation(BOB_CHANNEL));
        Node initialized = initialize(fixture, contracts);
        Node event = timelineEntry(fixture,
                ALICE_TIMELINE,
                ALICE_ACTOR,
                1,
                request("increment", BOB_CHANNEL, new Node().value(7)));

        DocumentProcessingResult first = fixture.blue.processDocument(initialized, event);
        int handlersAfterFirst = metrics.handlersExecuted;
        DocumentProcessingResult replay = fixture.blue.processDocument(first.document(), event);

        assertEquals(BigInteger.ONE, replay.document().get("/counter"));
        assertEquals(handlersAfterFirst, metrics.handlersExecuted);
        assertTrue(replay.totalGas() < first.totalGas());
    }

    @Test
    void invalidTrustedRouteMetadataRemainsCoreFatal() {
        Fixture fixture = fixture();
        fixture.blue.registerContractProcessor(TimelineChannel.blueId(),
                new InvalidRouteTimelineProcessor());
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(ALICE_CHANNEL, timelineChannel(ALICE_TIMELINE, ALICE_ACTOR));
        Node initialized = initialize(fixture, contracts);

        DocumentProcessingResult result = fixture.blue.processDocument(initialized,
                timelineEntry(fixture,
                        ALICE_TIMELINE,
                        ALICE_ACTOR,
                        1,
                        TestTimelineProvider.chatMessage("route")));

        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTrue(result.failureReason().contains("same-scope Channel"), result.failureReason());
        assertNull(checkpoint(result.document(), ALICE_CHANNEL));
    }

    private static Map<String, Node> baseContracts() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(ALICE_CHANNEL, timelineChannel(ALICE_TIMELINE, ALICE_ACTOR));
        contracts.put(BOB_CHANNEL, timelineChannel("bob-timeline", "bob-account"));
        return contracts;
    }

    private static Node timelineChannel(String timelineId, String actorId) {
        return TestTimelineProvider.channel(timelineId, actorId);
    }

    private static Node composite(int order, String... childKeys) {
        Node[] keys = new Node[childKeys.length];
        for (int i = 0; i < childKeys.length; i++) {
            keys[i] = new Node().value(childKeys[i]);
        }
        return new Node()
                .type("Coordination/Composite Timeline Channel")
                .properties("order", new Node().value(order))
                .properties("channels", new Node().items(keys));
    }

    private static Node incrementOperation(String channel) {
        return operation(channel,
                new Node().type("Integer"),
                replaceStep("/counter", bexAdd(
                        bexDocument("/counter"),
                        new Node().value(1))));
    }

    private static Node captureEventOperation(String channel) {
        return operation(channel,
                new Node().type("Integer"),
                replaceStep("/captured", bexBinding("event")));
    }

    private static Node recordMetadataOperation(String channel, String metadataKey) {
        return operation(channel,
                new Node().type("Integer"),
                replaceStep("/winner", bexBinding("event/meta/" + metadataKey)));
    }

    private static Node operation(String channel, Node requestPattern, Node... steps) {
        return new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value(channel))
                .properties("request", requestPattern)
                .properties("steps", new Node().items(steps));
    }

    private static Node ordinaryObserver(String channel) {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(
                        replaceStep("/ordinaryCount", bexAdd(
                                bexDocument("/ordinaryCount"),
                                new Node().value(1)))));
    }

    private static Node replaceStep(String path, Node value) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$appendChange", new Node()
                                .properties("op", new Node().value("replace"))
                                .properties("path", new Node().value(path))
                                .properties("val", value)),
                        new Node().properties("$return", new Node().value(true))));
    }

    private static Node failStep(String reason) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$fail", new Node().value(reason))));
    }

    private static Node bexAdd(Node... values) {
        return new Node().properties("$add", new Node().items(values));
    }

    private static Node bexDocument(String path) {
        return new Node().properties("$document", new Node().value(path));
    }

    private static Node bexBinding(String path) {
        return new Node().properties("$binding", new Node().value(path));
    }

    private static Node request(String operation, String channel, Node payload) {
        Node request = new Node()
                .type(OperationRequest.qualifiedName())
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value(channel));
        if (payload != null) {
            request.properties("request", payload);
        }
        return request;
    }

    private static Node requestWithOptionalRoute(String operation, String channel) {
        Node request = new Node().type(OperationRequest.qualifiedName());
        if (operation != null) {
            request.properties("operation", new Node().value(operation));
        }
        if (channel != null) {
            request.properties("channel", new Node().value(channel));
        }
        return request;
    }

    private static Node initialize(Fixture fixture, Map<String, Node> contracts) {
        Node document = new Node()
                .blue(fixture.repository.typeAliasBlue())
                .name("Operation Request Routing Test")
                .properties("counter", new Node().value(0))
                .properties("ordinaryCount", new Node().value(0))
                .properties("winner", new Node().value("none"))
                .properties("captured", new Node())
                .properties("contracts", new Node().properties(contracts));
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(
                fixture.blue.preprocess(document));
        assertSuccess(initialized);
        return initialized.document();
    }

    private static DocumentProcessingResult process(Fixture fixture,
                                                    Node document,
                                                    long sequence,
                                                    Node message) {
        return fixture.blue.processDocument(document,
                timelineEntry(fixture,
                        ALICE_TIMELINE,
                        ALICE_ACTOR,
                        sequence,
                        message));
    }

    private static Node timelineEntry(Fixture fixture,
                                      String timeline,
                                      String actor,
                                      long sequence,
                                      Node message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                timeline,
                actor,
                BigInteger.valueOf(sequence),
                BigInteger.valueOf(1_000L + sequence),
                message);
    }

    private static Node checkpoint(Node document, String key) {
        try {
            return document.getAsNode("/contracts/checkpoint/lastEvents/" + key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Fixture fixture() {
        return fixture(null, null);
    }

    private static Fixture fixture(RecordingMetrics metrics, SequentialWorkflowRunner runner) {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        if (runner != null) {
            blue.registerContractProcessor(new SequentialWorkflowOperationProcessor(runner));
        }
        if (metrics != null) {
            blue.getDocumentProcessor().processingMetricsSink(metrics);
        }
        return new Fixture(repository, blue);
    }

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(BlueRepository repository, Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        private int routedDeliveries;
        private int deduplicatedDeliveries;
        private int handlersExecuted;

        @Override
        public void incrementRoutedChannelDeliveries() {
            routedDeliveries++;
        }

        @Override
        public void incrementDeduplicatedChannelDeliveries() {
            deduplicatedDeliveries++;
        }

        @Override
        public void incrementHandlersExecuted() {
            handlersExecuted++;
        }
    }

    private static final class GracefulTerminationExecutor
            implements WorkflowStepExecutor<Compute> {
        @Override
        public boolean supports(SequentialWorkflowStep step) {
            return step instanceof Compute;
        }

        @Override
        public WorkflowStepResult execute(Compute step, StepExecutionContext context) {
            context.processorContext().terminateGracefully("operation complete");
            return WorkflowStepResult.none();
        }
    }

    private static final class InvalidRouteTimelineProcessor
            implements ChannelProcessor<TimelineChannel> {
        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(TimelineChannel contract,
                                          ChannelEvaluationContext context) {
            return ChannelEvaluation.matchDeliveries(Collections.singletonList(
                    ChannelDelivery.of(context.event(),
                            null,
                            null,
                            null,
                            "missing",
                            "invalid-route")));
        }
    }

    private static final class SelectiveFreshnessTimelineProcessor
            implements ChannelProcessor<TimelineChannel> {
        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(TimelineChannel contract,
                                          ChannelEvaluationContext context) {
            return TimelineProviderSupport.evaluateTimelineEntry(contract, context);
        }

        @Override
        public String eventId(TimelineChannel contract, ChannelEvaluationContext context) {
            return TimelineProviderSupport.eventId(context.event());
        }

        @Override
        public boolean isNewerEvent(TimelineChannel contract, ChannelCheckpointContext context) {
            return !ALICE_CHANNEL.equals(context.channelKey())
                    && TimelineProviderSupport.isNewerOrSameTimelineEvent(context);
        }
    }
}
