package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Compute;
import blue.repo.coordination.PrincipalActor;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One host session over a large document: resolve and initialize once, then
     * resolve and process five timeline entries through three BEX workflows.
 *
 * <p>The measured story uses Contracts' public whole-current-Root deriver;
 * Coordination does not substitute benchmark-owned delivery evidence.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ResolvedProcessingHostStoryBenchmark {
    private static final int PAYLOAD_FIELDS = 32;
    private static final int EVENTS = 5;
    private static final int WORKFLOWS = 3;
    private static final int COMPUTE_STEPS_PER_WORKFLOW = 2;

    private CoordinationBenchmarkRuntime runtime;
    private Node sourceDocument;
    private Node[] events;
    private ExternalDeliveryPlanDeriver publicBoundary;
    private String lastDiagnostic;
    private int sourceJsonBytes;

    @Setup(Level.Trial)
    public void setUp() {
        runtime = CoordinationBenchmarkRuntime.create();

        sourceDocument = document();
        sourceJsonBytes = runtime.nodeToJson(sourceDocument)
                .getBytes(StandardCharsets.UTF_8).length;
        events = new Node[EVENTS];
        for (int index = 0; index < EVENTS; index++) {
            events[index] = timelineEntry(index + 1);
        }
        Node planningRoot = runtime.preprocess(sourceDocument.clone());
        ExternalOrderKey order = eventOrder(events[0]);
        SubscriptionDelta initial = runtime.contracts()
                .subscriptionSurfaceProjection()
                .projectInitial(
                        planningRoot,
                        0L,
                        ExternalOrderKey.of(Collections.emptyList()));
        publicBoundary = CoordinationDeliveryPlanning
                .currentRootCompatibilityDeriver(
                        runtime.contracts(),
                        0L,
                        order,
                        initial.added());
    }

    @Benchmark
    public String resolveInitializeAndProcessFiveEvents() {
        lastDiagnostic = runHostStory();
        return lastDiagnostic;
    }

    @TearDown(Level.Iteration)
    public void verifyIteration() {
        if (lastDiagnostic == null
                || !lastDiagnostic.startsWith("deliveries=")) {
            throw new IllegalStateException(
                    "Host-story benchmark did not use the public delivery "
                            + "plan: " + lastDiagnostic);
        }
    }

    @TearDown(Level.Trial)
    public void reportFixture() {
        System.out.println("Resolved processing host fixture: sourceJsonBytes=" + sourceJsonBytes
                + ", payloadFields=" + PAYLOAD_FIELDS
                + ", events=" + EVENTS
                + ", workflowsPerEvent=" + WORKFLOWS
                + ", computeStepsPerWorkflow=" + COMPUTE_STEPS_PER_WORKFLOW);
        runtime.close();
    }

    private String runHostStory() {
        long phaseStarted = System.nanoTime();
        Node exactRoot = runtime.preprocess(sourceDocument.clone());
        trace("source preprocess", phaseStarted, exactRoot);
        int deliveries = publicBoundary
                .derive(exactRoot, events[0].clone())
                .deliveries().size();
        return "deliveries=" + deliveries;
    }

    private void trace(String phase, long started, Node node) {
        if (!Boolean.getBoolean("blue.benchmark.trace")) {
            return;
        }
        int bytes = node != null
                ? runtime.nodeToJson(node)
                        .getBytes(StandardCharsets.UTF_8).length
                : 0;
        double millis = (System.nanoTime() - started) / 1_000_000.0d;
        System.out.println(phase + ": ms=" + millis + ", jsonBytes=" + bytes);
    }

    private static Node document() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", timelineChannel());
        for (int workflow = 1; workflow <= WORKFLOWS; workflow++) {
            String counterPath = "/workflow" + workflow + "Counter";
            contracts.put("workflow" + workflow, workflow(counterPath));
        }

        Node payload = new Node();
        String value = payloadValue();
        for (int index = 0; index < PAYLOAD_FIELDS; index++) {
            payload.properties("field" + index, new Node().value(value));
        }

        Node document = new Node()
                .name("Resolved Processing Host Story")
                .properties("payload", payload)
                .properties("contracts", new Node().properties(contracts));
        for (int workflow = 1; workflow <= WORKFLOWS; workflow++) {
            document.properties("workflow" + workflow + "Counter", new Node().value(0));
        }
        return document;
    }

    private static String payloadValue() {
        String unit = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        StringBuilder result = new StringBuilder(4_096);
        for (int index = 0; index < 64; index++) {
            result.append(unit);
        }
        return result.toString();
    }

    private static Node timelineChannel() {
        return new Node()
                .type(typeReference(TimelineChannel.blueId()))
                .properties("timeline", new Node()
                        .type(typeReference(Timeline.blueId()))
                        .properties("providerId", new Node().value("test-provider"))
                        .properties("timelineId", new Node().value("owner")))
                .properties("actor", new Node()
                        .type(typeReference(PrincipalActor.blueId())));
    }

    private static Node workflow(String counterPath) {
        return new Node()
                .type(typeReference(SequentialWorkflow.blueId()))
                .properties("channel", new Node().value("ownerChannel"))
                .properties("steps", new Node().items(
                        incrementStep(counterPath),
                        incrementStep(counterPath)));
    }

    private static Node incrementStep(String counterPath) {
        Node incrementedValue = new Node().properties("$add", new Node().items(
                new Node().properties("$document", new Node().value(counterPath)),
                new Node().value(1)));
        return new Node()
                .type(typeReference(Compute.blueId()))
                .properties("do", new Node().items(
                        new Node().properties("$appendChange", new Node()
                                .properties("op", new Node().value("replace"))
                                .properties("path", new Node().value(counterPath))
                                .properties("val", incrementedValue)),
                        new Node().properties("$return", new Node()
                                .properties("changeset", new Node()
                                        .properties("$changeset", new Node().value(true))))));
    }

    private Node timelineEntry(int entryNumber) {
        Node message = new Node()
                .type(typeReference(ChatMessage.blueId()))
                .properties("message", new Node().value("entry-" + entryNumber));
        Node event = new Node()
                .type(typeReference(TimelineEntry.blueId()))
                .properties("timeline", new Node()
                        .type(typeReference(Timeline.blueId()))
                        .properties("timelineId", new Node().value("owner")))
                .properties("actor", new Node()
                        .type(typeReference(PrincipalActor.blueId())))
                .properties("timestamp", new Node().value(7_000_000L + entryNumber))
                .properties("message", message);
        return runtime.preprocess(event).blue(null);
    }

    private static ExternalOrderKey eventOrder(Node event) {
        return ExternalOrderKey.of(Collections.<Object>singletonList(
                DirectBlueIdCalculator.calculateBlueId(event)));
    }

    private static Node typeReference(String blueId) {
        return new Node().blueId(blueId);
    }
}
