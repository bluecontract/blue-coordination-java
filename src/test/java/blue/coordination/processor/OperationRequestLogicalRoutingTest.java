package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.CoordinationRoutingHarness;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerMatchContextFactory;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessingMetricsSink;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.repo.BlueRepository;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperationRequestLogicalRoutingTest {
    private static final Node CHANNEL_TYPE =
            new Node().name(
                    "Coordination Logical Routing Test Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final Node TARGET_CHANNEL_TYPE =
            new Node()
                    .name("Coordination Logical Routing Processor-Managed Target")
                    .type(reference(RuntimeBlueIds.CHANNEL));
    private static final String TARGET_CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(TARGET_CHANNEL_TYPE);
    private static final Node OPERATION_TYPE =
            new Node().name(
                    "Coordination Logical Routing Test Operation");
    private static final String OPERATION_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(OPERATION_TYPE);
    private static final Node OBSERVER_TYPE =
            new Node().name(
                    "Coordination Logical Routing Test Observer");
    private static final String OBSERVER_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(OBSERVER_TYPE);

    @Test
    void shouldEnsureThatTwoSourcesRouteOnceSuppressOrdinaryHandlersAndOwnCheckpoints() {
        // Given
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(document());

        // When
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request("increment", "target"));

        // Then
        assertSuccess(result);
        assertEquals(
                1,
                fixture.operations.executions,
                "full-bundle routing="
                        + fixture.preparedRouting
                        + ", Phase-B routing="
                        + fixture.channels.lastHandlerChannel
                        + ", total handlers="
                        + fixture.metrics.handlersExecuted);
        assertEquals(1, fixture.metrics.handlersExecuted);
        assertTrue(hasCheckpoint(
                result.document(), "source-a"));
        assertTrue(hasCheckpoint(
                result.document(), "source-b"));
        assertFalse(hasCheckpoint(
                result.document(), "target"));
    }

    @Test
    void shouldEnsureThatMalformedUnknownAndNonChannelTargetsKeepIndependentOrdinaryDelivery() {
        // Given
        Node[] events = new Node[] {
                request(null, "target"),
                request("increment", null),
                request("increment", "missing"),
                request("increment", "observer-a")
        };
        // When
        for (Node event : events) {
            Fixture fixture = new Fixture(null);
            Node initialized =
                    fixture.initialize(document());

            DocumentProcessingResult result =
                    fixture.process(
                            initialized, event);

            // Then
            assertSuccess(result);
            assertEquals(0, fixture.operations.executions);
            assertEquals(2, fixture.metrics.handlersExecuted);
            assertTrue(hasCheckpoint(
                    result.document(), "source-a"));
            assertTrue(hasCheckpoint(
                    result.document(), "source-b"));
            assertFalse(hasCheckpoint(
                    result.document(), "target"));
        }
    }

    @Test
    void shouldEnsureThatValidTargetWithUnknownOperationSuppressesOrdinaryWorkflow() {
        // Given
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(document());

        // When
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request("missing-operation", "target"));

        // Then
        assertSuccess(result);
        assertEquals(0, fixture.operations.executions);
        assertEquals(0, fixture.metrics.handlersExecuted);
        assertTrue(hasCheckpoint(
                result.document(), "source-a"));
        assertTrue(hasCheckpoint(
                result.document(), "source-b"));
        assertFalse(hasCheckpoint(
                result.document(), "target"));
    }

    @Test
    void shouldEnsureThatFragmentedTimelineAndOperationRequestProjectWithoutLosingRoute() {
        // Given
        FragmentedEvent fragments =
                fragmentedTimelineRequest(
                        "increment", "target");
        Fixture fixture =
                new Fixture(fragments.provider);
        Node initialized = fixture.initialize(document());

        // When
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        fragments.event);

        // Then
        assertSuccess(result);
        assertEquals(1, fixture.operations.executions);
        assertEquals(1, fixture.metrics.handlersExecuted);
        assertNotNull(fixture.operations.lastEvent);
        assertTrue(hasCheckpoint(
                result.document(), "source-a"));
        assertTrue(hasCheckpoint(
                result.document(), "source-b"));
        assertFalse(hasCheckpoint(
                result.document(), "target"));
    }

    @Test
    void shouldEnsureThatMissingRequiredFragmentFailsInsteadOfFallingBackToOrdinaryDelivery() {
        // Given
        String missingMessageBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node()
                                .type(new Node().blueId(
                                        OperationRequest.blueId()))
                                .properties(
                                        "operation",
                                        new Node().value(
                                                "increment"))
                                .properties(
                                        "channel",
                                        new Node().value(
                                                "target")));
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(document());
        Node event = timelineShell(
                new Node().blueId(
                        missingMessageBlueId));

        boolean failed = false;
        // When
        try {
            fixture.process(initialized, event);
        } catch (RuntimeException expected) {
            failed = true;
        }

        // Then
        assertTrue(failed);
        assertEquals(0, fixture.operations.executions);
        assertEquals(0, fixture.metrics.handlersExecuted);
        assertFalse(hasCheckpoint(
                initialized, "source-a"));
        assertFalse(hasCheckpoint(
                initialized, "source-b"));
    }

    @Test
    void shouldEnsureThatFragmentedWhitespaceOperationKeepsOrdinarySourceDelivery() {
        // Given
        FragmentedEvent fragments =
                fragmentedTimelineRequest(
                        " \t", "source-a");
        Fixture fixture =
                new Fixture(fragments.provider);
        Node initialized =
                fixture.initialize(document());

        // When
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        fragments.event);

        // Then
        assertSuccess(result);
        assertEquals(0, fixture.operations.executions);
        assertEquals(
                2,
                fixture.metrics.handlersExecuted,
                "Language handler-match reference materialization defect: "
                        + "the exact fragmented whitespace value must remain "
                        + "ordinary non-routable payload");
        assertTrue(hasCheckpoint(
                result.document(), "source-a"));
        assertTrue(hasCheckpoint(
                result.document(), "source-b"));
        assertFalse(hasCheckpoint(
                result.document(), "target"));
    }

    @Test
    void shouldEnsureThatProductionOrdinaryWorkflowSuppressesOnlyEffectiveRoutableTarget() {
        // Given
        SequentialWorkflow workflow =
                new SequentialWorkflow();
        SequentialWorkflowProcessor processor =
                new SequentialWorkflowProcessor();
        // When
        Node routed =
                request("increment", "target");

        // Then
        assertFalse(processor.matches(
                workflow,
                HandlerMatchContextFactory.create(
                        new Blue(),
                        "observer-target",
                        "target",
                        routed)));
        assertTrue(processor.matches(
                workflow,
                HandlerMatchContextFactory.create(
                        new Blue(),
                        "observer-source",
                        "source-a",
                        routed)));
        assertTrue(processor.matches(
                workflow,
                HandlerMatchContextFactory.create(
                        new Blue(),
                        "observer-source",
                        "source-a",
                        request(" \t", "source-a"))));
    }

    @Test
    void shouldRouteToAnInheritedEffectiveTargetByItsExactRawKey() {
        // Given
        String targetKey = "inherited-target";
        Node inheritedTarget = targetChannel(2);
        Node scopeType = new Node().contracts(
                new Node().properties(
                        targetKey,
                        inheritedTarget));
        String scopeTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        scopeType);
        NodeProvider inheritedProvider = blueId ->
                scopeTypeBlueId.equals(blueId)
                        ? Collections.singletonList(
                        scopeType.clone())
                        : null;
        Fixture fixture =
                new Fixture(inheritedProvider);
        Node authored =
                documentWithTargetKey(
                        targetKey, false)
                        .type(reference(
                                scopeTypeBlueId));
        Node initialized =
                fixture.initialize(authored);

        // When
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request(
                                "increment",
                                targetKey));

        // Then
        assertSuccess(result);
        assertEquals(1, fixture.operations.executions);
        assertEquals(
                targetKey,
                fixture.channels.lastHandlerChannel);
        assertTrue(hasCheckpoint(
                result.document(), "source-a"));
        assertTrue(hasCheckpoint(
                result.document(), "source-b"));
        assertFalse(hasCheckpoint(
                result.document(), targetKey));
    }

    @Test
    void shouldTreatSlashAndTildeInTargetKeyAsRawCharacters() {
        // Given
        String targetKey = "target/branch~leaf";
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(
                documentWithTargetKey(
                        targetKey, true));

        // When
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request(
                                "increment",
                                targetKey));

        // Then
        assertSuccess(result);
        assertEquals(1, fixture.operations.executions);
        assertEquals(
                targetKey,
                fixture.channels.lastHandlerChannel);
        assertTrue(hasCheckpoint(
                result.document(), "source-a"));
        assertTrue(hasCheckpoint(
                result.document(), "source-b"));
        assertFalse(hasCheckpoint(
                result.document(), targetKey));
    }

    private static Node document() {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "source-a",
                channel(0, "topic"));
        contracts.put(
                "source-b",
                channel(1, "topic"));
        contracts.put(
                "target",
                new Node()
                        .type(reference(
                                TARGET_CHANNEL_TYPE_BLUE_ID))
                        .properties(
                                "order",
                                new Node().value(2)));
        contracts.put(
                "increment",
                new Node()
                        .type(reference(
                                OPERATION_TYPE_BLUE_ID))
                        .properties(
                                "channel",
                                new Node().value(
                                        "target")));
        contracts.put(
                "observer-a",
                observer("source-a"));
        contracts.put(
                "observer-b",
                observer("source-b"));
        contracts.put(
                "observer-target",
                observer("target"));
        return new Node()
                .name("Coordination Logical Routing Test")
                .contracts(new Node()
                        .properties(contracts));
    }

    private static Node documentWithTargetKey(
            String targetKey,
            boolean declareTargetLocally) {
        Node authored = document();
        Map<String, Node> contracts =
                authored.getContracts()
                        .getProperties();
        Node target = contracts.remove("target");
        if (declareTargetLocally) {
            contracts.put(targetKey, target);
        }
        contracts.get("increment")
                .properties(
                        "channel",
                        new Node().value(
                                targetKey));
        contracts.get("observer-target")
                .properties(
                        "channel",
                        new Node().value(
                                targetKey));
        return authored;
    }

    private static Node targetChannel(int order) {
        return new Node()
                .type(reference(
                        TARGET_CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(order));
    }

    private static Node channel(
            int order,
            String subscriptionKey) {
        return new Node()
                .type(reference(
                        CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey));
    }

    private static Node observer(String channel) {
        return new Node()
                .type(reference(
                        OBSERVER_TYPE_BLUE_ID))
                .properties(
                        "channel",
                        new Node().value(channel))
                .properties(
                        "steps",
                        new Node().items());
    }

    private static Node request(
            String operation,
            String channel) {
        Node request = new Node()
                .type(reference(
                        OperationRequest.blueId()))
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"));
        if (operation != null) {
            request.properties(
                    "operation",
                    new Node().value(operation))
                    .properties(
                            "testOperation",
                            new Node().value(operation));
        }
        if (channel != null) {
            request.properties(
                    "channel",
                    new Node().value(channel));
        }
        return request;
    }

    private static FragmentedEvent fragmentedTimelineRequest(
            String operation,
            String channel) {
        final Map<String, Node> exact =
                new LinkedHashMap<String, Node>();
        String subscriptionKey = addFragment(
                exact,
                new Node().value("topic"));
        String timeline = addFragment(
                exact,
                new Node().value("timeline"));
        String actor = addFragment(
                exact,
                new Node().value("actor"));
        String timestamp = addFragment(
                exact,
                new Node().value(
                        BigInteger.TEN));
        String operationValue = addFragment(
                exact,
                new Node().value(operation));
        String channelValue = addFragment(
                exact,
                new Node().value(channel));
        String specialized = addFragment(
                exact,
                new Node().value("retained"));
        Node message = new Node()
                .type(reference(
                        OperationRequest.blueId()))
                .properties(
                        "operation",
                        reference(operationValue))
                .properties(
                        "channel",
                        reference(channelValue))
                .properties(
                        "specializedField",
                        reference(specialized));
        String messageBlueId =
                addFragment(exact, message);
        String attribution = addFragment(
                exact,
                new Node().value("preserved"));
        Node event = new Node()
                .type(reference(
                        TimelineEntry.blueId()))
                .properties(
                        "testOperation",
                        new Node().value(operation))
                .properties(
                        "subscriptionKey",
                        reference(subscriptionKey))
                .properties(
                        "timeline",
                        reference(timeline))
                .properties(
                        "actor",
                        reference(actor))
                .properties(
                        "timestamp",
                        reference(timestamp))
                .properties(
                        "message",
                        reference(messageBlueId))
                .properties(
                        "onBehalfOf",
                        reference(attribution));
        NodeProvider provider = blueId -> {
            Node content = exact.get(blueId);
            return content != null
                    ? Collections.singletonList(
                            content.clone())
                    : null;
        };
        return new FragmentedEvent(
                event, provider);
    }

    private static String addFragment(
            Map<String, Node> exact,
            Node content) {
        String blueId =
                BlueIdCalculator.calculateBlueId(
                        content);
        exact.put(blueId, content.clone());
        return blueId;
    }

    private static Node timelineShell(Node message) {
        return new Node()
                .type(reference(
                        TimelineEntry.blueId()))
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "timeline",
                        new Node().value(
                                "timeline"))
                .properties(
                        "actor",
                        new Node().value("actor"))
                .properties(
                        "timestamp",
                        new Node().value(
                                BigInteger.TEN))
                .properties(
                        "message",
                        message)
                .properties(
                        "onBehalfOf",
                        new Node().value(
                                "preserved"));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static boolean hasCheckpoint(
            Node document,
            String channelKey) {
        Node contracts =
                document != null
                        ? document.getContracts()
                        : null;
        Node checkpoint =
                property(contracts, "checkpoint");
        Node entries =
                property(checkpoint, "entries");
        return property(entries, channelKey) != null;
    }

    private static Node property(
            Node node,
            String key) {
        return node != null
                && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static void assertSuccess(
            DocumentProcessingResult result) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport
                        .diagnosticMessage(result));
    }

    private static final class FragmentedEvent {
        private final Node event;
        private final NodeProvider provider;

        private FragmentedEvent(
                Node event,
                NodeProvider provider) {
            this.event = event;
            this.provider = provider;
        }
    }

    public static final class RoutingTestChannel
            extends ChannelContract {
        private String subscriptionKey;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey =
                    subscriptionKey;
        }
    }

    public static final class RoutingTestOperation
            extends SequentialWorkflowOperation {
    }

    public static final class RoutingTargetChannel
            extends EmbeddedNodeChannel {
    }

    private static final class RoutingTargetChannelProcessor
            implements ChannelProcessor<RoutingTargetChannel> {
        @Override
        public Class<RoutingTargetChannel> contractType() {
            return RoutingTargetChannel.class;
        }
    }

    private static final class RoutingChannelProcessor
            implements ChannelProcessor<
            RoutingTestChannel> {
        private String lastHandlerChannel;
        private final ExternalChannelSubscriptionFunctions<
                RoutingTestChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        RoutingTestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            RoutingTestChannel contract,
                            ExternalChannelFunctionContext context) {
                        return Collections.singletonList(
                                contract
                                        .getSubscriptionKey());
                    }

                    @Override
                    public boolean accepts(
                            RoutingTestChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        if (!ExternalChannelSubscriptionFunctions
                                .super.accepts(
                                        contract,
                                        exactEvent,
                                        context)) {
                            return false;
                        }
                        return !declaresTimelineEntry(
                                exactEvent)
                                || CoordinationEventNodes
                                .timelineEntry(
                                        exactEvent,
                                        context) != null;
                    }

                    @Override
                    public Node checkpointSubject(
                            RoutingTestChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        CoordinationEventNodes
                                .TimelineEntryView entry =
                                declaresTimelineEntry(
                                        exactEvent)
                                        ? CoordinationEventNodes
                                        .timelineEntry(
                                                exactEvent,
                                                context)
                                        : null;
                        return entry != null
                                ? new Node().value(
                                        entry.timestamp())
                                : ExternalChannelSubscriptionFunctions
                                .super.checkpointSubject(
                                        contract,
                                        exactEvent,
                                        exactPayload,
                                        context);
                    }

                    @Override
                    public Node payload(
                            RoutingTestChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return OperationRequestRoutingFunctions
                                .payload(
                                        exactEvent,
                                        context);
                    }

                    @Override
                    public String handlerChannelKey(
                            RoutingTestChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        lastHandlerChannel =
                                OperationRequestRoutingFunctions
                                .handlerChannelKey(
                                        contract,
                                        exactEvent,
                                        exactPayload,
                                        context);
                        return lastHandlerChannel;
                    }

                    @Override
                    public String logicalDeliveryKey(
                            RoutingTestChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return OperationRequestRoutingFunctions
                                .logicalDeliveryKey(
                                        contract,
                                        exactEvent,
                                        exactPayload,
                                        context);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            RoutingTestChannel contract) {
                        return "coordination-logical-routing-test";
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            RoutingTestChannel contract,
                            ExternalChannelFunctionContext context) {
                        OperationRequestRoutingFunctions
                                .declareTargetChannelCatalog(
                                        context);
                        return checkpointDomainDiscriminator(
                                contract);
                    }
                };

        private boolean declaresTimelineEntry(
                Node event) {
            return event != null
                    && event.getType() != null
                    && TimelineEntry.blueId().equals(
                    event.getType().getBlueId());
        }

        @Override
        public Class<RoutingTestChannel> contractType() {
            return RoutingTestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                RoutingTestChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class RoutingOperationProcessor
            implements HandlerProcessor<
            RoutingTestOperation> {
        private int executions;
        private Node lastEvent;

        @Override
        public Class<RoutingTestOperation>
        contractType() {
            return RoutingTestOperation.class;
        }

        @Override
        public boolean matches(
                RoutingTestOperation contract,
                HandlerMatchContext context) {
            Node operationNode = property(
                    context.event(), "testOperation");
            Object operation = operationNode != null
                    ? operationNode.getValue()
                    : null;
            return contract != null
                    && contract.getKey() != null
                    && contract.getKey().equals(
                    operation);
        }

        @Override
        public void execute(
                RoutingTestOperation contract,
                ProcessorExecutionContext context) {
            executions++;
            lastEvent = context.event().clone();
        }
    }

    private static final class Fixture {
        private final Blue language;
        private final DocumentProcessor processor;
        private final RoutingChannelProcessor channels =
                new RoutingChannelProcessor();
        private final RoutingOperationProcessor operations =
                new RoutingOperationProcessor();
        private final RoutingTargetChannelProcessor targets =
                new RoutingTargetChannelProcessor();
        private final RecordingMetrics metrics =
                new RecordingMetrics();
        private List<String> preparedRouting;

        private Fixture(
                NodeProvider provider) {
            NodeProvider fixtureProvider = blueId -> {
                if (TARGET_CHANNEL_TYPE_BLUE_ID.equals(blueId)) {
                    return Collections.singletonList(
                            TARGET_CHANNEL_TYPE.clone());
                }
                return provider != null
                        ? provider.fetchByBlueId(blueId)
                        : null;
            };
            language = BlueRepository.latest()
                    .configure(new Blue());
            NodeProvider repositoryProvider =
                    language.getNodeProvider();
            language.nodeProvider(
                    new SequentialNodeProvider(
                            fixtureProvider,
                            repositoryProvider));
            SequentialWorkflowProcessor workflows =
                    new SequentialWorkflowProcessor();
            language.registerExternalContractType(
                    CHANNEL_TYPE_BLUE_ID,
                    CHANNEL_TYPE,
                    channels);
            language.registerExternalContractType(
                    OPERATION_TYPE_BLUE_ID,
                    OPERATION_TYPE,
                    operations);
            language.registerExternalContractType(
                    OBSERVER_TYPE_BLUE_ID,
                    OBSERVER_TYPE,
                    workflows);
            language.registerContractProcessor(
                    TARGET_CHANNEL_TYPE_BLUE_ID,
                    targets);
            processor = DocumentProcessor.builder()
                    .registerContractProcessor(
                            CHANNEL_TYPE_BLUE_ID,
                            CHANNEL_TYPE,
                            channels)
                    .registerContractProcessor(
                            OPERATION_TYPE_BLUE_ID,
                            OPERATION_TYPE,
                            operations)
                    .registerContractProcessor(
                            OBSERVER_TYPE_BLUE_ID,
                            OBSERVER_TYPE,
                            workflows)
                    .registerContractProcessor(
                            TARGET_CHANNEL_TYPE_BLUE_ID,
                            TARGET_CHANNEL_TYPE,
                            targets)
                    .withMatchingService(
                            new ContractMatchingService(
                                    language))
                    .withSnapshotManager(
                            CoordinationRoutingHarness
                                    .snapshotManager(
                                            language))
                    .withProcessingMetricsSink(
                            metrics)
                    .withExternalDeliveryEvidenceVerifier(
                            (root, event, evidence) -> {
                                // Exact binding is revalidated by evidence.
                            })
                    .build();
        }

        private Node initialize(Node document) {
            DocumentProcessingResult result =
                    processor.initializeDocument(document);
            assertSuccess(result);
            return result.document();
        }

        private DocumentProcessingResult process(
                Node document,
                Node event) {
            preparedRouting =
                    CoordinationRoutingHarness
                            .routingProjection(
                                    processor,
                                    document,
                                    event,
                                    "source-b");
            return CoordinationRoutingHarness
                    .process(
                            processor,
                            document,
                            event,
                            "source-a",
                            "source-b");
        }
    }

    private static final class RecordingMetrics
            implements ProcessingMetricsSink {
        private int handlersExecuted;

        @Override
        public void incrementHandlersExecuted() {
            handlersExecuted++;
        }
    }
}
