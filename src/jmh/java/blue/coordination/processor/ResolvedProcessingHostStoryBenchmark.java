package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.PrincipalActor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One host session over a large document: resolve and initialize once, then
 * resolve and process five timeline entries through three BEX workflows.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ResolvedProcessingHostStoryBenchmark {
    private static final int PAYLOAD_FIELDS = 32;
    private static final int EVENTS = 5;
    private static final int WORKFLOWS = 3;
    private static final int COMPUTE_STEPS_PER_WORKFLOW = 2;
    private static final int EXPECTED_COUNTER = EVENTS * COMPUTE_STEPS_PER_WORKFLOW;

    private Blue blue;
    private Node sourceDocument;
    private Node[] events;
    private DocumentProcessingResult lastResult;
    private int sourceJsonBytes;

    @Setup(Level.Trial)
    public void setUp() {
        BlueRepository repository = BlueRepository.latest();
        blue = repository.configure(new Blue());
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder().build());

        sourceDocument = preprocess(repository, document());
        sourceJsonBytes = blue.nodeToJson(sourceDocument).getBytes(StandardCharsets.UTF_8).length;
        events = new Node[EVENTS];
        for (int index = 0; index < EVENTS; index++) {
            events[index] = timelineEntry(blue, repository, index + 1);
        }

    }

    @Benchmark
    public DocumentProcessingResult resolveInitializeAndProcessFiveEvents() {
        lastResult = runHostStory();
        return lastResult;
    }

    @TearDown(Level.Iteration)
    public void verifyIteration() {
        assertExpectedResult(lastResult);
    }

    @TearDown(Level.Trial)
    public void reportFixture() {
        System.out.println("Resolved processing host fixture: sourceJsonBytes=" + sourceJsonBytes
                + ", payloadFields=" + PAYLOAD_FIELDS
                + ", events=" + EVENTS
                + ", workflowsPerEvent=" + WORKFLOWS
                + ", computeStepsPerWorkflow=" + COMPUTE_STEPS_PER_WORKFLOW);
    }

    private DocumentProcessingResult runHostStory() {
        long phaseStarted = System.nanoTime();
        ResolvedSnapshot selected = blue.resolveToSnapshot(sourceDocument.clone());
        trace("initial resolve", phaseStarted, selected.resolvedRoot());
        phaseStarted = System.nanoTime();
        DocumentProcessingResult result = blue.initializeDocument(selected);
        requireSuccess(result, "initialization");
        trace("initial process", phaseStarted, result.document());

        phaseStarted = System.nanoTime();
        Node epoch = storedEpoch(result, "initialization");
        trace("epoch 0 store", phaseStarted, epoch);
        for (int index = 0; index < EVENTS; index++) {
            phaseStarted = System.nanoTime();
            ResolvedSnapshot resolvedEpoch = blue.resolveToSnapshot(epoch.clone());
            trace("epoch " + index + " resolve", phaseStarted, resolvedEpoch.resolvedRoot());
            phaseStarted = System.nanoTime();
            result = blue.processDocument(resolvedEpoch, events[index].clone());
            requireSuccess(result, "event " + (index + 1));
            trace("event " + (index + 1) + " process", phaseStarted, result.document());
            phaseStarted = System.nanoTime();
            epoch = storedEpoch(result, "event " + (index + 1));
            trace("epoch " + (index + 1) + " store", phaseStarted, epoch);
        }
        return result;
    }

    private void trace(String phase, long started, Node node) {
        if (!Boolean.getBoolean("blue.benchmark.trace")) {
            return;
        }
        int bytes = node != null
                ? blue.nodeToJson(node).getBytes(StandardCharsets.UTF_8).length
                : 0;
        double millis = (System.nanoTime() - started) / 1_000_000.0d;
        System.out.println(phase + ": ms=" + millis + ", jsonBytes=" + bytes);
    }

    private void assertExpectedResult(DocumentProcessingResult result) {
        requireSuccess(result, "verification");
        Node resolved = result.resolvedDocument();
        for (int workflow = 1; workflow <= WORKFLOWS; workflow++) {
            Integer actual = resolved.getAsInteger("/workflow" + workflow + "Counter");
            if (!Integer.valueOf(EXPECTED_COUNTER).equals(actual)) {
                throw new IllegalStateException("workflow " + workflow + " executed incorrectly: " + actual);
            }
        }
    }

    private static Node storedEpoch(DocumentProcessingResult result, String phase) {
        Node canonical = result.canonicalDocument();
        if (canonical == null) {
            throw new IllegalStateException(phase + " did not produce a canonical epoch");
        }
        return canonical;
    }

    private static void requireSuccess(DocumentProcessingResult result, String phase) {
        if (result == null || result.status() != ProcessorStatus.SUCCESS) {
            throw new IllegalStateException(phase + " failed: "
                    + (result != null ? result.failureReason() : "missing result"));
        }
    }

    private Node preprocess(BlueRepository repository, Node document) {
        document.blue(repository.typeAliasBlue());
        Node aliasesResolved = new RepositoryTypeAliasPreprocessor(repository).preprocess(document);
        return blue.preprocess(aliasesResolved);
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
                .type("Coordination/Timeline Channel")
                .properties("timeline", new Node()
                        .type("Coordination/Timeline")
                        .properties("providerId", new Node().value("test-provider"))
                        .properties("timelineId", new Node().value("owner")))
                .properties("actor", new Node()
                        .type("MyOS/Principal Actor")
                        .properties("accountId", new Node().value("owner")));
    }

    private static Node workflow(String counterPath) {
        return new Node()
                .type("Coordination/Sequential Workflow")
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
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$appendChange", new Node()
                                .properties("op", new Node().value("replace"))
                                .properties("path", new Node().value(counterPath))
                                .properties("val", incrementedValue)),
                        new Node().properties("$return", new Node()
                                .properties("changeset", new Node()
                                        .properties("$changeset", new Node().value(true))))));
    }

    private static Node timelineEntry(Blue blue, BlueRepository repository, int entryNumber) {
        TimelineEntry entry = new TimelineEntry()
                .timeline(new Timeline().timelineId("owner"))
                .actor(new PrincipalActor().accountId("owner"))
                .timestamp(BigInteger.valueOf(7_000_000L + entryNumber));
        Node message = new Node()
                .type(ChatMessage.qualifiedName())
                .properties("message", new Node().value("entry-" + entryNumber));
        Node event = blue.objectToNode(entry)
                .properties("timestamp", new Node().value(7_000_000L + entryNumber))
                .properties("message", message)
                .blue(repository.typeAliasBlue());
        return blue.preprocess(event).blue(null);
    }
}
