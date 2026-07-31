package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.StatusPending;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateAuthorityConfirmed;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance coverage for the generated Mandate programs that consume processingEvent.
 */
class MandateProcessingEventBindingTest {
    private static final int PROCESSING_EVENT_TIMESTAMP = 7_000_001;

    @Test
    void shouldUseRootProcessingEventTimestampForMandateConfirmation() {
        // Given
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument());

        // When
        DocumentProcessingResult result = fixture.process(
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        fixture.blue, initialized),
                fixture.confirmAuthorityEvent(PROCESSING_EVENT_TIMESTAMP));

        // Then
        assertEquals(StatusPending.blueId(),
                initialized.document().getAsText("/status/type/blueId"));
        assertSuccess(result);
        // Declared-type event matching owns final lifecycle state; this case isolates processingEvent.
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
        // Given
        Fixture fixture = fixture();
        Node processEvent = new Node().properties(
                "kind", scalar("missing-timestamp"));

        // When
        DocumentProcessingResult result = fixture.process(
                fixture.preprocess(timestampGuardDocument(fixture.repository)),
                processEvent);

        // Then
        assertGuardReturnsUndefined(fixture, result);
    }

    @Test
    void shouldReturnUndefinedForNonIntegerMandateTimestamp() {
        // Given
        Fixture fixture = fixture();
        Node processEvent = new Node().properties(
                "timestamp", scalar("7000001"));

        // When
        DocumentProcessingResult result = fixture.process(
                fixture.preprocess(timestampGuardDocument(fixture.repository)),
                processEvent);

        // Then
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

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static Fixture fixture() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
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

        Fixture(BlueRepository repository, Blue blue, BexProcessingMetrics metrics) {
            this.repository = repository;
            this.blue = blue;
            this.metrics = metrics;
        }

        DocumentProcessingResult initialize(Node document) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                    CoordinationTestResources
                            .preprocessWithFixedRepository(
                                    blue,
                                    repository,
                                    document));
            DocumentProcessingResult result = blue.initializeDocument(snapshot);
            assertSuccess(result);
            return result;
        }

        DocumentProcessingResult process(Node document, Node event) {
            return blue.processDocument(document, event);
        }

        DocumentProcessingResult process(ResolvedSnapshot snapshot, Node event) {
            return blue.processDocument(snapshot, event);
        }

        Node confirmAuthorityEvent(int timestamp) {
            return TestTimelineProvider.timelineEntry(blue,
                    repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.valueOf(timestamp),
                    CoordinationTestResources.operationRequest(
                            "confirmMandateAuthority",
                            "mandateGuarantorChannel",
                            new Node()));
        }

        Node preprocess(Node document) {
            return CoordinationTestResources
                    .preprocessWithFixedRepository(
                            blue,
                            repository,
                            document);
        }
    }
}
