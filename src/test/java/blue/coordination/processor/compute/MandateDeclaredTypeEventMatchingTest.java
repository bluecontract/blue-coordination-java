package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.RepositoryTypeAliasPreprocessor;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateActivated;
import blue.repo.mandate.MandateAuthorityConfirmed;
import blue.repo.mandate.MandateTerminated;
import blue.repo.mandate.StatusActive;
import blue.repo.mandate.StatusAuthorityConfirmed;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MandateDeclaredTypeEventMatchingTest {
    private static final int EVENT_TIMESTAMP = 7_000_001;

    @Test
    void initializationExecutesExactlyOnceAndActivationSelectsOnlyItsHandler() {
        Fixture fixture = fixture();

        DocumentProcessingResult initialized = fixture.initialize(mandateDocument(true, false));

        assertSuccess(initialized);
        assertEquals(1L, fixture.metrics.handlersExecuted());
        assertEquals(1L, fixture.metrics.workflowStepsExecuted());

        long handlersBeforeConfirmation = fixture.metrics.handlersExecuted();
        DocumentProcessingResult activated = fixture.process(
                initialized.snapshot(),
                fixture.confirmAuthorityEvent());

        assertSuccess(activated);
        assertEquals(StatusActive.blueId(),
                activated.canonicalDocument().getAsText("/status/type/blueId"));
        assertEquals(BigInteger.valueOf(EVENT_TIMESTAMP), activated.document().get("/activatedAt"));
        assertEquals(2L, fixture.metrics.handlersExecuted() - handlersBeforeConfirmation);
        assertEquals(0L, fixture.metrics.successfulComputeTerminationRequests());
        assertEquals(1, eventsOfType(activated, MandateAuthorityConfirmed.blueId()));
        assertEquals(1, eventsOfType(activated, MandateActivated.blueId()));
        assertEquals(0, eventsOfType(activated, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
    }

    @Test
    void fatalLifecycleDeliveryDoesNotReselectInitialization() {
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument(false, true));
        DocumentProcessingResult confirmed = fixture.process(
                initialized.snapshot(),
                fixture.confirmAuthorityEvent());
        assertSuccess(confirmed);
        assertEquals(StatusAuthorityConfirmed.blueId(),
                confirmed.canonicalDocument().getAsText("/status/type/blueId"));
        long handlersBeforeFatal = fixture.metrics.handlersExecuted();

        DocumentProcessingResult fatal = fixture.process(
                confirmed.snapshot(),
                fixture.fatalProbeEvent());

        assertEquals(ProcessorStatus.RUNTIME_FATAL, fatal.status(), fatal.failureReason());
        assertTrue(fatal.failureReason().contains("Unsupported sequential workflow step"),
                fatal.failureReason());
        assertEquals(StatusAuthorityConfirmed.blueId(),
                fatal.canonicalDocument().getAsText("/status/type/blueId"));
        assertEquals(1L, fixture.metrics.handlersExecuted() - handlersBeforeFatal);
        assertEquals(1, eventsOfType(fatal, RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR));
    }

    private static Node mandateDocument(boolean activateOnConfirmation, boolean fatalProbe) {
        Node contracts = new Node()
                .properties("mandateGuarantorChannel", TestTimelineProvider.channel("guarantor"))
                .properties("authorityHolderChannel", TestTimelineProvider.channel("holder"))
                .properties("authorizedActorChannel", TestTimelineProvider.channel("authorized"));
        if (fatalProbe) {
            contracts.properties("fatalProbe", new Node()
                    .type("Coordination/Sequential Workflow")
                    .properties("channel", new Node().value("mandateGuarantorChannel"))
                    .properties("event", new Node()
                            .properties("message", new Node().type(ChatMessage.qualifiedName())))
                    .properties("steps", new Node().items(
                            new Node().type("Coordination/Sequential Workflow Step"))));
        }
        return new Node()
                .name("Mandate Declared-Type Event Matching Acceptance")
                .type(Mandate.qualifiedName())
                .properties("activateOnAuthorityConfirmation", new Node().value(activateOnConfirmation))
                .properties("contracts", contracts);
    }

    private static int eventsOfType(DocumentProcessingResult result, String blueId) {
        int count = 0;
        for (Node event : result.triggeredEvents()) {
            if (event.getType() != null && blueId.equals(event.getType().getBlueId())) {
                count++;
            }
        }
        return count;
    }

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
    }

    private static Fixture fixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        return new Fixture(repository, blue, metrics);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;
        private final BexProcessingMetrics metrics;

        private Fixture(BlueRepository repository, Blue blue, BexProcessingMetrics metrics) {
            this.repository = repository;
            this.blue = blue;
            this.metrics = metrics;
        }

        private DocumentProcessingResult initialize(Node document) {
            document.blue(repository.typeAliasBlue());
            Node aliasesResolved = new RepositoryTypeAliasPreprocessor(repository).preprocess(document);
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(blue.preprocess(aliasesResolved));
            assertMaterializedDeclaredType(snapshot,
                    "/contracts/initializeMandate/event/type",
                    RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED);
            assertMaterializedDeclaredType(snapshot,
                    "/contracts/applyMandateActivation/event/type",
                    MandateActivated.blueId());
            assertMaterializedDeclaredType(snapshot,
                    "/contracts/applyMandateTermination/event/type",
                    MandateTerminated.blueId());
            return blue.initializeDocument(snapshot);
        }

        private static void assertMaterializedDeclaredType(ResolvedSnapshot snapshot,
                                                           String path,
                                                           String expectedBlueId) {
            Node type = snapshot.resolvedRoot().getAsNode(path);
            assertNotNull(type, path);
            assertEquals(expectedBlueId, type.getBlueId(), path);
            assertFalse(type.isReferenceOnly(), path);
        }

        private DocumentProcessingResult process(ResolvedSnapshot snapshot, Node event) {
            return blue.processDocument(snapshot, event);
        }

        private Node confirmAuthorityEvent() {
            return TestTimelineProvider.timelineEntry(
                    blue,
                    repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.ONE,
                    BigInteger.valueOf(EVENT_TIMESTAMP),
                    CoordinationTestResources.operationRequest(
                            "confirmMandateAuthority",
                            "mandateGuarantorChannel",
                            new Node()));
        }

        private Node fatalProbeEvent() {
            return TestTimelineProvider.timelineEntry(
                    blue,
                    repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.valueOf(2L),
                    BigInteger.valueOf(EVENT_TIMESTAMP + 1L),
                    TestTimelineProvider.chatMessage("trigger fatal probe"));
        }
    }
}
