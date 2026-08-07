package blue.coordination.processor;

import blue.language.processor.CoordinationRoutingHarness;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessingMetricId;
import blue.language.processor.ProcessingObservation;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.SequentialNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.runtime.BlueLanguage;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.TimelineEntry;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.AfterAll;
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
    private static final NodeProvider REPOSITORY_PROVIDER =
            BlueRepository.current().nodeProvider();
    private static final List<Fixture> OPEN_FIXTURES =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Node CHANNEL_TYPE =
            new Node().name(
                    "Coordination Logical Routing Test Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final Node TARGET_CHANNEL_TYPE =
            new Node()
                    .name("Coordination Logical Routing Processor-Managed Target")
                    .type(reference(RuntimeBlueIds.CHANNEL));
    private static final String TARGET_CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(TARGET_CHANNEL_TYPE);
    private static final Node OPERATION_TYPE =
            new Node().name(
                    "Coordination Logical Routing Test Operation");
    private static final String OPERATION_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(OPERATION_TYPE);
    private static final Node OBSERVER_TYPE =
            new Node().name(
                    "Coordination Logical Routing Test Observer");
    private static final String OBSERVER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(OBSERVER_TYPE);

    @AfterAll
    static void shouldCloseOpenFixtures() {
        for (Fixture fixture : OPEN_FIXTURES) {
            fixture.close();
        }
        OPEN_FIXTURES.clear();
    }

    @Test
    void shouldTwoSourcesRouteOnceSuppressOrdinaryHandlersAndOwnCheckpoints() {
        // given
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(document());

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request("increment", "target"));

        // then
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
    void shouldKeepIndependentOrdinaryDeliveryForMalformedUnknownAndNonChannelTargets() {
        // given
        Node[] events = new Node[] {
                request(null, "target"),
                request("increment", null),
                request("increment", "missing"),
                request("increment", "observer-a")
        };
        // when
        for (Node event : events) {
            Fixture fixture = new Fixture(null);
            Node initialized =
                    fixture.initialize(document());

            DocumentProcessingResult result =
                    fixture.process(
                            initialized, event);

            // then
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
    void shouldSuppressOrdinaryWorkflowForValidTargetWithUnknownOperation() {
        // given
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(document());

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request("missing-operation", "target"));

        // then
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
    void shouldPreserveRouteForFragmentedTimelineAndOperationRequest() {
        // given
        FragmentedEvent fragments =
                fragmentedTimelineRequest(
                        "increment", "target");
        Fixture fixture =
                new Fixture(fragments.provider);
        Node initialized = fixture.initialize(document());

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        fragments.event);

        // then
        List<Node> exactOperationCandidates =
                fragments.provider.fetchByBlueId(
                        fragments.operationBlueId);
        boolean exactProviderEvidence =
                exactOperationCandidates != null
                        && exactOperationCandidates.size() == 1
                        && "increment".equals(
                        exactOperationCandidates.get(0)
                                .getValue())
                        && fragments.operationBlueId
                        .equals(
                                DirectBlueIdCalculator
                                        .calculateBlueId(
                                                exactOperationCandidates
                                                        .get(0)));
        boolean exactMatcherDefect =
                exactProviderEvidence
                        && result.status()
                        == ProcessorStatus.SUCCESS
                        && fixture.operations.executions == 0
                        && fixture.metrics.handlersExecuted == 2
                        && hasCheckpoint(
                        result.document(), "source-a")
                        && hasCheckpoint(
                        result.document(), "source-b")
                        && !hasCheckpoint(
                        result.document(), "target");
        ExternalBlockerProbeAssertions.classify(
                "handler-match-reference-materialization",
                "Language handler-match reference materialization defect:",
                exactMatcherDefect,
                result.status()
                        == ProcessorStatus.SUCCESS
                        && fixture.operations.executions == 1
                        && fixture.metrics.handlersExecuted == 1,
                "status=" + result.status()
                        + ", diagnostic="
                        + ProcessingResultTestSupport
                        .diagnosticMessage(result)
                        + ", operationBlueId="
                        + fragments.operationBlueId
                        + ", exactProviderEvidence="
                        + exactProviderEvidence
                        + ", operationExecutions="
                        + fixture.operations.executions
                        + ", handlerExecutions="
                        + fixture.metrics.handlersExecuted
                        + ", checkpoints="
                        + hasCheckpoint(
                        result.document(), "source-a")
                        + "/"
                        + hasCheckpoint(
                        result.document(), "source-b")
                        + "/"
                        + hasCheckpoint(
                        result.document(), "target"));
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
    void shouldFailForMissingFragmentInsteadOfFallingBackToOrdinaryDelivery() {
        // given
        String missingMessageBlueId =
                DirectBlueIdCalculator.calculateBlueId(
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
        // when
        try {
            fixture.process(initialized, event);
        } catch (RuntimeException expected) {
            failed = true;
        }

        // then
        assertTrue(failed);
        assertEquals(0, fixture.operations.executions);
        assertEquals(0, fixture.metrics.handlersExecuted);
        assertFalse(hasCheckpoint(
                initialized, "source-a"));
        assertFalse(hasCheckpoint(
                initialized, "source-b"));
    }

    @Test
    void shouldKeepOrdinarySourceDeliveryForFragmentedWhitespaceOperation() {
        // given
        FragmentedEvent fragments =
                fragmentedTimelineRequest(
                        " \t", "source-a");
        Fixture fixture =
                new Fixture(fragments.provider);
        Node initialized =
                fixture.initialize(document());

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        fragments.event);

        // then
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
    void shouldSuppressOnlyEffectiveRoutableTargetInProductionOrdinaryWorkflow() {
        // given
        Fixture routedFixture = new Fixture(null);
        Node routedDocument = routedFixture.initialize(document());
        Fixture whitespaceFixture = new Fixture(null);
        Node whitespaceDocument = whitespaceFixture.initialize(document());

        // when
        DocumentProcessingResult routed = routedFixture.process(
                routedDocument,
                request("increment", "target"));
        DocumentProcessingResult whitespace = whitespaceFixture.process(
                whitespaceDocument,
                request(" \t", "source-a"));

        // then
        assertSuccess(routed);
        assertEquals(1, routedFixture.operations.executions);
        assertEquals(1, routedFixture.metrics.handlersExecuted);
        assertSuccess(whitespace);
        assertEquals(0, whitespaceFixture.operations.executions);
        assertEquals(2, whitespaceFixture.metrics.handlersExecuted);
    }

    @Test
    void shouldRouteToAnInheritedEffectiveTargetByItsExactRawKey() {
        // given
        String targetKey = "inherited-target";
        Node inheritedTarget = targetChannel(2);
        Node scopeType = new Node().contracts(
                new Node().properties(
                        targetKey,
                        inheritedTarget));
        String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
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

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request(
                                "increment",
                                targetKey));

        // then
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
        // given
        String targetKey = "target/branch~leaf";
        Fixture fixture = new Fixture(null);
        Node initialized = fixture.initialize(
                documentWithTargetKey(
                        targetKey, true));

        // when
        DocumentProcessingResult result =
                fixture.process(
                        initialized,
                        request(
                                "increment",
                                targetKey));

        // then
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
                event,
                provider,
                operationValue);
    }

    private static String addFragment(
            Map<String, Node> exact,
            Node content) {
        String blueId =
                DirectBlueIdCalculator.calculateBlueId(
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
        private final String operationBlueId;

        private FragmentedEvent(
                Node event,
                NodeProvider provider,
                String operationBlueId) {
            this.event = event;
            this.provider = provider;
            this.operationBlueId =
                    operationBlueId;
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

    private static final class Fixture implements AutoCloseable {
        private final BlueLanguage language;
        private final BlueContracts contracts;
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
            NodeProvider repositoryProvider =
                    REPOSITORY_PROVIDER;
            SequentialWorkflowProcessor workflows =
                    new SequentialWorkflowProcessor();
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .registerDefaults()
                            .register(
                                    CHANNEL_TYPE_BLUE_ID,
                                    CHANNEL_TYPE,
                                    channels)
                            .register(
                                    OPERATION_TYPE_BLUE_ID,
                                    OPERATION_TYPE,
                                    operations)
                            .register(
                                    OBSERVER_TYPE_BLUE_ID,
                                    OBSERVER_TYPE,
                                    workflows)
                            .register(
                                    TARGET_CHANNEL_TYPE_BLUE_ID,
                                    TARGET_CHANNEL_TYPE,
                                    targets)
                            .build();
            NodeProvider nodeProvider =
                    new SequentialNodeProvider(
                            fixtureProvider,
                            BlueRuntimeTypeRegistry.getDefault()
                                    .asProcessorSnapshotProvider(),
                            registry.exactTypeProvider(),
                            repositoryProvider);
            language = BlueLanguage.builder()
                    .nodeProvider(nodeProvider)
                    .build();
            contracts = BlueContracts.builder(
                            language.processing())
                    .runtimeRegistry(registry)
                    .build();
            processor = DocumentProcessor.builder()
                    .runtimeAccess(contracts.runtimeAccess())
                    .runtimeRegistry(registry)
                    .runtimeRegistryIdentity(
                            "blue.coordination/test/operation-routing/1")
                    .observer(
                            metrics)
                    .evidenceVerifier(
                            (root, event, evidence) -> {
                                // Exact binding is revalidated by evidence.
                            })
                    .build();
            OPEN_FIXTURES.add(this);
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

        @Override
        public void close() {
            processor.close();
            contracts.close();
            language.close();
        }
    }

    private static final class RecordingMetrics
            implements ProcessingObserver {
        private int handlersExecuted;

        @Override
        public void record(ProcessingObservation observation) {
            if (observation.metricId()
                    == ProcessingMetricId.HANDLERS_EXECUTED) {
                handlersExecuted += Math.toIntExact(
                        observation.value());
            }
        }
    }
}
