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
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.StatusPending;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateAuthorityConfirmed;
import blue.repo.mandate.StatusActive;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance coverage for the published RC5 Mandate programs that consume processingEvent.
 */
class MandateProcessingEventBindingTest {
    private static final int ROOT_TIMESTAMP = 7_000_001;

    @Test
    void mandateAuthorityConfirmationUsesRootTimestamp7000001() {
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument(fixture.repository));
        assertEquals(StatusPending.blueId(), initialized.document().getAsText("/status/type/blueId"));

        DocumentProcessingResult result = fixture.process(initialized.snapshot(),
                fixture.confirmAuthorityEvent(ROOT_TIMESTAMP));

        assertSuccess(result);
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/authorityConfirmedAt"));
        assertTrue(result.triggeredEvents().stream().anyMatch(event -> event.getType() != null
                && MandateAuthorityConfirmed.blueId().equals(event.getType().getBlueId())));
    }

    @Test
    void mandateAutomaticActivationKeepsRootTimestamp7000001() {
        Fixture fixture = fixture();
        DocumentProcessingResult initialized = fixture.initialize(mandateDocument(fixture.repository));

        DocumentProcessingResult result = fixture.process(initialized.snapshot(),
                fixture.confirmAuthorityEvent(ROOT_TIMESTAMP));

        assertSuccess(result);
        assertEquals(StatusActive.blueId(), result.document().getAsText("/status/type/blueId"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/authorityConfirmedAt"));
        assertEquals(BigInteger.valueOf(ROOT_TIMESTAMP), result.document().get("/activatedAt"));
    }

    @Test
    void mandateTimestampFunctionReturnsUndefinedWhenTimestampMissing() {
        assertGuardReturnsUndefined(new Node().properties("kind", scalar("missing-timestamp")));
    }

    @Test
    void mandateTimestampFunctionReturnsUndefinedForNonIntegerTimestamp() {
        assertGuardReturnsUndefined(new Node().properties("timestamp", scalar("7000001")));
    }

    private static void assertGuardReturnsUndefined(Node processEvent) {
        Fixture fixture = fixture();
        DocumentProcessingResult result = fixture.process(
                fixture.preprocess(timestampGuardDocument(fixture.repository)), processEvent);

        assertSuccess(result);
        assertEquals("undefined", result.document().get("/observation"));
        assertEquals(1L, fixture.metrics.processEventSnapshotAttempts());
        assertEquals(1L, fixture.metrics.processEventSnapshotBuilds());
    }

    private static Node mandateDocument(BlueRepository repository) {
        Node generatedDefinition = repository.nodeByBlueId(Mandate.blueId())
                .orElseThrow(() -> new IllegalStateException("Published Mandate definition is unavailable"));
        Node mandate = generatedDefinition.clone()
                .name("Processing Event Mandate Acceptance");
        // Keep the generated lifecycle field declarations as the fixture's type boundary so
        // status replacements use the same generalization rules as a concrete Mandate subtype.
        Node lifecycleType = new Node()
                .name("Mandate Lifecycle Acceptance Type")
                .properties("status", generatedDefinition.getAsNode("/status").clone())
                .properties("activateOnAuthorityConfirmation",
                        generatedDefinition.getAsNode("/activateOnAuthorityConfirmation").clone())
                .properties("authorityConfirmedAt",
                        generatedDefinition.getAsNode("/authorityConfirmedAt").clone())
                .properties("activatedAt", generatedDefinition.getAsNode("/activatedAt").clone())
                .properties("terminatedAt", generatedDefinition.getAsNode("/terminatedAt").clone());
        mandate.type(lifecycleType);
        Map<String, Node> contracts = mandate.getAsNode("/contracts").getProperties();
        contracts.put("mandateGuarantorChannel", TestTimelineProvider.channel("guarantor"));
        contracts.put("authorityHolderChannel", TestTimelineProvider.channel("holder"));
        contracts.put("authorizedActorChannel", TestTimelineProvider.channel("authorized"));
        // Task 9 owns the termination workflow and its Terminate Processing executor.
        contracts.remove("mandateTerminationChannel");
        contracts.remove("mandateTerminatedChannel");
        contracts.remove("terminateMandate");
        contracts.remove("applyMandateTermination");
        return mandate;
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
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
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
            document.blue(repository.typeAliasBlue());
            Node aliasesResolved = new RepositoryTypeAliasPreprocessor(repository).preprocess(document);
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(aliasesResolved);
            if (snapshot.canonicalAt("/contracts/mandateLifecycleDefinition") == null) {
                throw new IllegalStateException("Mandate lifecycle definition missing from canonical fixture: "
                        + blue.nodeToSimpleYaml(snapshot.canonicalRoot()));
            }
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
                    BigInteger.ONE,
                    BigInteger.valueOf(timestamp),
                    CoordinationTestResources.operationRequest(
                            "confirmMandateAuthority",
                            "mandateGuarantorChannel",
                            new Node()));
        }

        Node preprocess(Node document) {
            document.blue(repository.typeAliasBlue());
            return blue.preprocess(document);
        }
    }
}
