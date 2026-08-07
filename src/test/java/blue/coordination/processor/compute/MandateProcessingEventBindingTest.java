package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestRuntime;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.ExternalBlockerProbeAssertions;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.BlueRepository;
import blue.repo.coordination.StatusPending;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateAuthorityConfirmed;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance coverage for the generated Mandate programs that consume processingEvent.
 */
class MandateProcessingEventBindingTest {
    private static final int PROCESSING_EVENT_TIMESTAMP = 7_000_001;
    private static final String IMPLICIT_SOURCE =
            "implicitInitializationSource";
    private static final String IMPLICIT_SUBSCRIPTION =
            "implicit-initialization";
    private static final String IMPLICIT_CHECKPOINT_DOMAIN =
            "coordination-implicit-initialization";

    @Test
    void shouldUseRootProcessingEventTimestampForMandateConfirmation() {
        // given
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument());
        ResolvedSnapshot initializedSnapshot =
                fixture.runtime.resolveToSnapshot(
                        initialized.document());

        // when
        DocumentProcessingResult result = fixture.process(
                initializedSnapshot,
                fixture.confirmAuthorityEvent(PROCESSING_EVENT_TIMESTAMP));

        // then
        ExternalBlockerProbeAssertions
                .classifyMandateContractRefresh(
                        result,
                        initializedSnapshot.resolvedNodeAt(
                                "/contracts/"
                                        + "mandateGuarantorChannel"
                                        + "/type")
                                != null,
                        "Mandate processing-event confirmation");
        assertEquals(StatusPending.blueId(),
                initialized.document().getAsText("/status/type/blueId"));
        assertSuccess(result);
        // declared-type event matching owns final lifecycle state; this case isolates processingEvent.
        assertEquals(BigInteger.valueOf(PROCESSING_EVENT_TIMESTAMP),
                result.document().get("/authorityConfirmedAt"));
        assertTrue(result.events().stream().anyMatch(event -> event.getType() != null
                && MandateAuthorityConfirmed.blueId().equals(event.getType().getBlueId())));
        assertTrue(fixture.metrics.processEventSnapshotAttempts() > 0L);
        assertEquals(fixture.metrics.processEventSnapshotAttempts(),
                fixture.metrics.processEventSnapshotBuilds());
    }

    @Test
    void shouldReturnUndefinedWhenMandateTimestampIsMissing() {
        // given
        Fixture fixture = fixture();
        Node processEvent = new Node().properties(
                "kind", scalar("missing-timestamp"));

        // when
        DocumentProcessingResult result = fixture.processUninitialized(
                timestampGuardDocument(fixture.repository),
                processEvent);

        // then
        assertGuardReturnsUndefined(fixture, result);
    }

    @Test
    void shouldReturnUndefinedForNonIntegerMandateTimestamp() {
        // given
        Fixture fixture = fixture();
        Node processEvent = new Node().properties(
                "timestamp", scalar("7000001"));

        // when
        DocumentProcessingResult result = fixture.processUninitialized(
                timestampGuardDocument(fixture.repository),
                processEvent);

        // then
        assertGuardReturnsUndefined(fixture, result);
    }

    private static void assertGuardReturnsUndefined(
            Fixture fixture,
            DocumentProcessingResult result) {
        assertSuccess(result);
        assertEquals("undefined", result.document().get("/observation"));
        assertEquals(1L, fixture.metrics.processEventSnapshotAttempts());
        assertEquals(1L, fixture.metrics.processEventSnapshotBuilds());
    }

    private static Node mandateDocument() {
        return new Node()
                .name("Processing Event Mandate Acceptance")
                .type(Mandate.qualifiedName())
                .properties("activateOnAuthorityConfirmation", new Node().value(false))
                .properties("contracts", new Node()
                        .properties("mandateGuarantorChannel", TestTimelineProvider.channel("guarantor"))
                        .properties("authorityHolderChannel", TestTimelineProvider.channel("holder"))
                        .properties("authorizedActorChannel", TestTimelineProvider.channel("authorized")));
    }

    private static Node timestampGuardDocument(BlueRepository repository) {
        Node mandateDefinition = repository.nodeByBlueId(Mandate.blueId())
                .orElseThrow(() -> new IllegalStateException("Published Mandate definition is unavailable"));
        Node lifecycleDefinition = mandateDefinition
                .getAsNode("/contracts/mandateLifecycleDefinition")
                .clone();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("lifecycle", new Node().type("Lifecycle Event Channel"));
        contracts.put("mandateLifecycleDefinition", lifecycleDefinition);
        contracts.put("guard", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", scalar("lifecycle"))
                .properties("event", new Node().type("Document Processing Initiated"))
                .properties("steps", new Node().items(
                        new Node()
                                .name("Timestamp Guard")
                                .type("Coordination/Compute")
                                .properties("definition", scalar("mandateLifecycleDefinition"))
                                .properties("entry", scalar("processingEventTimestamp")),
                        captureStep("/observation", operation("$coalesce", new Node().items(
                                operation("$steps", scalar("Timestamp Guard")),
                                scalar("undefined")))))));
        return new Node()
                .name("Generated Mandate Timestamp Guard")
                .properties("observation", scalar("unset"))
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

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result));
    }

    private static Fixture fixture() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime runtime =
                CoordinationTestResources.configuredBlue(repository);
        runtime.configure(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
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

        DocumentProcessingResult initialize(Node document) {
            ResolvedSnapshot snapshot = runtime.resolveToSnapshot(
                    CoordinationTestResources
                            .preprocessWithFixedRepository(
                                    runtime,
                                    repository,
                                    document));
            DocumentProcessingResult result =
                    runtime.processor().initializeDocument(snapshot);
            assertSuccess(result);
            return result;
        }

        DocumentProcessingResult processUninitialized(
                Node document,
                Node event) {
            Node prepared =
                    CoordinationTestResources
                            .preprocessWithFixedRepository(
                                    runtime,
                                    repository,
                                    withImplicitInitializationSource(
                                            document));
            String originalEventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            event);
            List<String> expectedExactBlueIds =
                    ExternalBlockerProbeAssertions
                            .expectedExactBlueIds(
                                    prepared,
                                    event);
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
                                "Mandate timestamp guard");
                throw failure;
            }
            ExternalBlockerProbeAssertions
                    .requireImplicitInitializationSuccess(
                            debug,
                            IMPLICIT_SOURCE,
                            originalEventBlueId,
                            "Mandate timestamp guard");
            return debug.processResult();
        }

        DocumentProcessingResult process(ResolvedSnapshot snapshot, Node event) {
            return runtime.processor().processDocument(snapshot, event);
        }

        Node confirmAuthorityEvent(int timestamp) {
            return TestTimelineProvider.timelineEntry(runtime,
                    repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.valueOf(timestamp),
                    CoordinationTestResources.operationRequest(
                            "confirmMandateAuthority",
                            "mandateGuarantorChannel",
                            new Node()));
        }
    }
}
