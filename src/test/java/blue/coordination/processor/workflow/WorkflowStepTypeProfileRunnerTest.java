package blue.coordination.processor.workflow;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.BlueContracts;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.UpdateDocument;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowStepTypeProfileRunnerTest {

    private static final String CHANNEL_BLUE_ID =
            "G9oHk82BLN4Q8CojGKADv7yrvjA9HkC3q1hUks9yUoM1";
    private static final Node CUSTOM_UPDATE_TYPE = new Node()
            .name("Coordination Test/Custom Exact Update Document")
            .type(new Node().blueId(SequentialWorkflowStep.blueId()));
    private static final String CUSTOM_UPDATE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CUSTOM_UPDATE_TYPE);

    @Test
    void shouldDispatchCustomExactStepIdentityWhenFrozenTypeIsPureReference() {
        // given
        Node step = updateStep(new Node().blueId(CUSTOM_UPDATE_BLUE_ID));
        FrozenNode frozenStep = FrozenNode.fromResolvedNode(step);

        // when
        SequentialWorkflowStep dispatchedStep = profile().materialize(
                new SequentialWorkflowStep(), frozenStep);
        DocumentProcessingResult result;
        try (TestRuntime runtime = TestRuntime.open()) {
            result = runtime.process(step);
        }

        // then
        assertEquals(CUSTOM_UPDATE_BLUE_ID,
                frozenStep.getType().getReferenceBlueId());
        assertEquals(UpdateDocument.class, dispatchedStep.getClass());
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(BigInteger.valueOf(7L), result.document().get("/counter"));
    }

    @Test
    void shouldDispatchCustomExactStepIdentityWhenFrozenTypeIsMaterialized() {
        // given
        Node step = updateStep(CUSTOM_UPDATE_TYPE.clone());
        FrozenNode frozenStep = FrozenNode.fromResolvedNode(step);

        // when
        SequentialWorkflowStep dispatchedStep = profile().materialize(
                new SequentialWorkflowStep(), frozenStep);
        DocumentProcessingResult result;
        try (TestRuntime runtime = TestRuntime.open()) {
            result = runtime.process(step);
        }

        // then
        assertNull(frozenStep.getType().getReferenceBlueId());
        assertEquals(CUSTOM_UPDATE_BLUE_ID, frozenStep.getType().blueId());
        assertEquals(UpdateDocument.class, dispatchedStep.getClass());
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(BigInteger.valueOf(7L), result.document().get("/counter"));
    }

    private static Node updateStep(Node exactType) {
        return new Node()
                .type(exactType)
                .properties("changeset", new Node().items(new Node()
                        .properties("op", new Node().value("replace"))
                        .properties("path", new Node().value("/counter"))
                        .properties("val", new Node().value(7))));
    }

    private static Node document(Node step) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("channel", typed(CHANNEL_BLUE_ID));
        contracts.put("workflow", typed(SequentialWorkflow.blueId())
                .properties("channel", new Node().value("channel"))
                .properties("steps", new Node().items(step)));
        return new Node()
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static WorkflowStepTypeProfile profile() {
        return WorkflowStepTypeProfile.builder()
                .updateDocument(CUSTOM_UPDATE_BLUE_ID)
                .build();
    }

    private static ExternalDeliveryPlan deliveryPlan(Node root, Node event) {
        Node channel = root.getContracts().getProperties().get("channel");
        String contributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(channel);
        String checkpointDomainBlueId = CheckpointDomain.derive(
                CHANNEL_BLUE_ID,
                Collections.singletonList(contributionBlueId),
                "workflow-step-type-profile-test");
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder("/", "channel")
                        .sourceContribution(contributionBlueId)
                        .effectiveTypeBlueId(CHANNEL_BLUE_ID)
                        .subscriptionKey("channel")
                        .checkpointDomainBlueId(checkpointDomainBlueId)
                        .checkpointSubjectBlueId(
                                DirectBlueIdCalculator.calculateBlueId(event))
                        .build();
        SubscriptionDelta.Entry activeInterval =
                new SubscriptionDelta.Entry(
                        "/",
                        "channel",
                        CHANNEL_BLUE_ID,
                        Collections.singletonList(contributionBlueId),
                        0,
                        Collections.singletonList("channel"),
                        checkpointDomainBlueId,
                        0L,
                        null,
                        null);
        return ExternalDeliveryPlan.builder()
                .revisions(0L, 0L)
                .eventOrderKey(ExternalOrderKey.of(Collections.singletonList(
                        DirectBlueIdCalculator.calculateBlueId(event))))
                .delivery(delivery)
                .activeSubscriptionInterval(activeInterval)
                .exactRuntimeState()
                .build();
    }

    @TypeBlueId(CHANNEL_BLUE_ID)
    public static final class TestChannel extends ChannelContract {
    }

    private static final class TestChannelProcessor
            implements ChannelProcessor<TestChannel> {
        @Override
        public Class<TestChannel> contractType() {
            return TestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<TestChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<TestChannel>() {
                @Override
                public List<String> channelKeys(
                        TestChannel immutableContractSnapshot) {
                    return Collections.singletonList("channel");
                }

                @Override
                public String checkpointDomainDiscriminator(
                        TestChannel immutableContractSnapshot) {
                    return "workflow-step-type-profile-test";
                }
            };
        }

        @Override
        public boolean matches(
                TestChannel contract,
                ChannelEvaluationContext context) {
            return context.event() != null;
        }

        @Override
        public String eventId(
                TestChannel contract,
                ChannelEvaluationContext context) {
            return "run";
        }
    }

    private static final class TestRuntime implements AutoCloseable {
        private final BlueLanguage language;
        private final BlueContracts contracts;
        private final SequentialWorkflowRunner runner;
        private final DocumentProcessor processor;

        private TestRuntime() {
            NodeProvider customTypeProvider = blueId ->
                    CUSTOM_UPDATE_BLUE_ID.equals(blueId)
                            ? Collections.singletonList(
                                    CUSTOM_UPDATE_TYPE.clone())
                            : null;
            language = BlueLanguage.builder()
                    .nodeProvider(customTypeProvider)
                    .build();
            contracts = BlueContracts.builder(language.processing()).build();
            runner = SequentialWorkflowRunner.withLanguage(
                    language,
                    100_000L,
                    null,
                    null,
                    profile());
            DocumentProcessor.Builder builder = DocumentProcessor.builder()
                    .matchingService(new ContractMatchingService(
                            contracts.runtimeAccess().languageRuntime()))
                    .deliveryPlanDeriver(
                            WorkflowStepTypeProfileRunnerTest::deliveryPlan);
            CoordinationProcessors.configure(builder,
                    CoordinationProcessorOptions.builder()
                            .sequentialWorkflowRunner(runner)
                            .build());
            processor = builder
                    .registerContractProcessor(new TestChannelProcessor())
                    .build();
        }

        private static TestRuntime open() {
            return new TestRuntime();
        }

        private DocumentProcessingResult process(Node step) {
            DocumentProcessingResult initialized =
                    processor.initializeDocument(document(step));
            assertEquals(ProcessorStatus.SUCCESS, initialized.status(),
                    ProcessingResultTestSupport.diagnosticMessage(initialized));
            return processor.processDocument(
                    initialized.document(),
                    new Node()
                            .properties("id", new Node().value("run"))
                            .properties("subscriptionKey",
                                    new Node().value("channel")));
        }

        @Override
        public void close() {
            processor.close();
            runner.close();
            contracts.close();
            language.close();
        }
    }
}
