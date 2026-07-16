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
import blue.repo.coordination.StatusFailed;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateTerminated;
import blue.repo.mandate.StatusTerminated;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MandateTerminationWorkflowTest {
    private static final int TERMINATION_TIMESTAMP = 7_000_001;

    @Test
    void generatedMandateTerminationAppliesTimestampAndTerminatesExactlyOnce() {
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument(false));
        assertEquals(1L, fixture.metrics.handlersExecuted());
        long handlersBeforeTermination = fixture.metrics.handlersExecuted();

        DocumentProcessingResult result = fixture.process(initialized.snapshot(),
                fixture.terminateMandateEvent(TERMINATION_TIMESTAMP));

        assertSuccess(result);
        assertEquals(StatusTerminated.blueId(),
                result.canonicalDocument().getAsText("/status/type/blueId"));
        assertEquals(BigInteger.valueOf(TERMINATION_TIMESTAMP), result.document().get("/terminatedAt"));
        assertEquals("graceful", result.document().get("/contracts/terminated/cause"));
        assertEquals("Mandate terminated", result.document().get("/contracts/terminated/reason"));

        List<Node> domainEvents = eventsOfType(result, MandateTerminated.blueId());
        assertEquals(1, domainEvents.size());
        assertEquals("requested by guarantor", domainEvents.get(0).get("/reason"));
        assertEquals(1, eventsOfType(result, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED).size());
        assertTrue(indexOfType(result, MandateTerminated.blueId())
                < indexOfType(result, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));

        assertEquals(1L, fixture.metrics.successfulComputeTerminationRequests());
        assertEquals(0L, fixture.metrics.declarativeTerminationSteps());
        assertEquals(0L, fixture.metrics.computeResultValidationFailures());
        assertEquals(2L, fixture.metrics.handlersExecuted() - handlersBeforeTermination);

        long handlersBeforeDuplicate = fixture.metrics.handlersExecuted();
        DocumentProcessingResult duplicate = fixture.process(result.snapshot(),
                fixture.terminateMandateEvent(TERMINATION_TIMESTAMP));
        assertSuccess(duplicate);
        assertTrue(eventsOfType(duplicate, MandateTerminated.blueId()).isEmpty());
        assertTrue(eventsOfType(duplicate, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED).isEmpty());
        assertEquals(StatusTerminated.blueId(),
                duplicate.canonicalDocument().getAsText("/status/type/blueId"));
        assertEquals(BigInteger.valueOf(TERMINATION_TIMESTAMP), duplicate.document().get("/terminatedAt"));
        assertEquals(handlersBeforeDuplicate, fixture.metrics.handlersExecuted());
        assertEquals(1L, fixture.metrics.successfulComputeTerminationRequests());
    }

    @Test
    void failedMandateTerminatesWithoutReplacingFailureStateOrTimestamp() {
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument(true));
        assertEquals(StatusFailed.blueId(), initialized.canonicalDocument().getAsText("/status/type/blueId"));
        assertNull(initialized.document().getAsNode("/terminatedAt").getValue());

        DocumentProcessingResult result = fixture.process(initialized.snapshot(),
                fixture.terminateMandateEvent(TERMINATION_TIMESTAMP));

        assertSuccess(result);
        assertEquals(StatusFailed.blueId(), result.canonicalDocument().getAsText("/status/type/blueId"));
        assertNull(result.document().getAsNode("/terminatedAt").getValue());
        assertEquals("graceful", result.document().get("/contracts/terminated/cause"));
        assertEquals("Mandate terminated", result.document().get("/contracts/terminated/reason"));
        assertEquals(1, eventsOfType(result, MandateTerminated.blueId()).size());
        assertEquals(1, eventsOfType(result, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED).size());
        assertEquals(1L, fixture.metrics.successfulComputeTerminationRequests());
    }

    private static Node mandateDocument(boolean invalidInitializationEntry) {
        Node document = new Node()
                .name("Generated Mandate Termination Acceptance")
                .type(Mandate.qualifiedName())
                .properties("activateOnAuthorityConfirmation", new Node().value(false))
                .properties("contracts", new Node()
                        .properties("mandateGuarantorChannel", TestTimelineProvider.channel("guarantor"))
                        .properties("authorityHolderChannel", TestTimelineProvider.channel("holder"))
                        .properties("authorizedActorChannel", TestTimelineProvider.channel("authorized")));
        if (invalidInitializationEntry) {
            document.properties("validation", new Node()
                    .properties("function", new Node()
                            .properties("entry", new Node().value("missing"))
                            .properties("functions", new Node()
                                    .properties("other", new Node()
                                            .properties("expr", new Node().value(true))))));
        }
        return document;
    }

    private static List<Node> eventsOfType(DocumentProcessingResult result, String blueId) {
        List<Node> events = new ArrayList<Node>();
        for (Node event : result.triggeredEvents()) {
            if (event.getType() != null && blueId.equals(event.getType().getBlueId())) {
                events.add(event);
            }
        }
        return events;
    }

    private static int indexOfType(DocumentProcessingResult result, String blueId) {
        for (int i = 0; i < result.triggeredEvents().size(); i++) {
            Node event = result.triggeredEvents().get(i);
            if (event.getType() != null && blueId.equals(event.getType().getBlueId())) {
                return i;
            }
        }
        return -1;
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
            DocumentProcessingResult result = blue.initializeDocument(snapshot);
            assertSuccess(result);
            return result;
        }

        private DocumentProcessingResult process(ResolvedSnapshot snapshot, Node event) {
            return blue.processDocument(snapshot, event);
        }

        private Node terminateMandateEvent(int timestamp) {
            Node request = new Node().properties("reason", new Node().value("requested by guarantor"));
            return TestTimelineProvider.timelineEntry(blue,
                    repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.valueOf(timestamp),
                    CoordinationTestResources.operationRequest(
                            "terminateMandate",
                            "mandateTerminationChannel",
                            request));
        }
    }
}
