package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmbeddedTerminationWorkflowTest {
    @Test
    void terminateProcessingInEmbeddedWorkflowTerminatesOnlyEmbeddedScope() {
        Fixture fixture = fixture();
        Node initialized = fixture.initialize(documentWithEmbeddedTermination(false));

        DocumentProcessingResult childResult = fixture.process(initialized,
                fixture.operationEvent("child", 1, "runChild", "childChannel"));

        assertSuccess(childResult);
        assertEquals("changed-before-stop", childResult.document().get("/child/status"));
        assertEquals("graceful", childResult.document().get("/child/contracts/terminated/cause"));
        assertEquals("embedded-complete", childResult.document().get("/child/contracts/terminated/reason"));
        assertNull(nodeAt(childResult.document(), "/contracts/terminated"));
        assertEquals(1L, fixture.metrics.declarativeTerminationSteps());

        DocumentProcessingResult rootResult = fixture.process(childResult.document(),
                fixture.operationEvent("root", 1, "runRoot", "rootChannel"));

        assertSuccess(rootResult);
        assertEquals("root-still-active", rootResult.document().get("/rootStatus"));
        assertNull(nodeAt(rootResult.document(), "/contracts/terminated"));
        assertEquals("graceful", rootResult.document().get("/child/contracts/terminated/cause"));
    }

    @Test
    void computeAndDeclarativeTerminationProduceEquivalentEmbeddedEffects() {
        Fixture computeFixture = fixture();
        Fixture declarativeFixture = fixture();
        Node computeDocument = computeFixture.initialize(documentWithEmbeddedTermination(true));
        Node declarativeDocument = declarativeFixture.initialize(documentWithEmbeddedTermination(false));

        DocumentProcessingResult compute = computeFixture.process(computeDocument,
                computeFixture.operationEvent("child", 1, "runChild", "childChannel"));
        DocumentProcessingResult declarative = declarativeFixture.process(declarativeDocument,
                declarativeFixture.operationEvent("child", 1, "runChild", "childChannel"));

        assertSuccess(compute);
        assertSuccess(declarative);
        assertEquals(compute.document().get("/child/status"), declarative.document().get("/child/status"));
        assertEquals(compute.document().get("/child/contracts/terminated/cause"),
                declarative.document().get("/child/contracts/terminated/cause"));
        assertEquals(compute.document().get("/child/contracts/terminated/reason"),
                declarative.document().get("/child/contracts/terminated/reason"));
        assertNull(nodeAt(compute.document(), "/contracts/terminated"));
        assertNull(nodeAt(declarative.document(), "/contracts/terminated"));
        assertEquals(1L, computeFixture.metrics.successfulComputeTerminationRequests());
        assertEquals(1L, declarativeFixture.metrics.declarativeTerminationSteps());
    }

    private static Node documentWithEmbeddedTermination(boolean computeTermination) {
        Map<String, Node> rootContracts = new LinkedHashMap<String, Node>();
        rootContracts.put("rootChannel", TestTimelineProvider.channel("root"));
        rootContracts.put("embedded", new Node()
                .type("Process Embedded")
                .properties("paths", new Node().items(new Node().value("/child"))));
        rootContracts.put("runRoot", operation("rootChannel",
                updateStep("/rootStatus", "root-still-active")));

        Map<String, Node> childContracts = new LinkedHashMap<String, Node>();
        childContracts.put("childChannel", TestTimelineProvider.channel("child"));
        childContracts.put("runChild", operation("childChannel",
                updateStep("/status", "changed-before-stop"),
                computeTermination ? computeTerminateStep("embedded-complete")
                        : declarativeTerminateStep("embedded-complete"),
                updateStep("/status", "must-not-run")));

        return new Node()
                .name("Embedded Termination Test")
                .properties("rootStatus", new Node().value("idle"))
                .properties("contracts", new Node().properties(rootContracts))
                .properties("child", new Node()
                        .name("Embedded Child")
                        .properties("status", new Node().value("idle"))
                        .properties("contracts", new Node().properties(childContracts)));
    }

    private static Node operation(String channel, Node... steps) {
        return new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value(channel))
                .properties("request", new Node().type("Text"))
                .properties("steps", new Node().items(steps));
    }

    private static Node updateStep(String path, String value) {
        return new Node()
                .type("Coordination/Update Document")
                .properties("changeset", new Node().items(new Node()
                        .properties("op", new Node().value("replace"))
                        .properties("path", new Node().value(path))
                        .properties("val", new Node().value(value))));
    }

    private static Node declarativeTerminateStep(String reason) {
        return new Node()
                .type("Coordination/Terminate Processing")
                .properties("reason", new Node().value(reason));
    }

    private static Node computeTerminateStep(String reason) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(new Node()
                        .properties("$return", new Node()
                                .properties("termination", new Node()
                                        .properties("reason", new Node().value(reason))))));
    }

    private static Node nodeAt(Node node, String pointer) {
        try {
            Object value = node.get(pointer);
            return value instanceof Node ? (Node) value : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
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

        private Node initialize(Node document) {
            document.blue(repository.typeAliasBlue());
            return blue.initializeDocument(blue.preprocess(document)).document();
        }

        private DocumentProcessingResult process(Node document, Node event) {
            return blue.processDocument(document, event);
        }

        private Node operationEvent(String timelineId,
                                    int timestamp,
                                    String operation,
                                    String channel) {
            return CoordinationTestResources.operationRequestEvent(blue,
                    repository,
                    timelineId,
                    timestamp,
                    operation,
                    channel,
                    new Node().value("request"));
        }
    }
}
