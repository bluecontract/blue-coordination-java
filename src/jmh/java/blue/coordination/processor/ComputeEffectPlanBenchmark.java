package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.Compute;
import blue.repo.coordination.Event;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.PrincipalActor;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Measures the Coordination boundary for each active Compute-effect combination. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ComputeEffectPlanBenchmark {
    @Param({"changeset", "events", "changesetEvents", "changesetEventsTermination"})
    public String effects;

    private Blue blue;
    private BlueRepository repository;
    private ResolvedSnapshot initializedSnapshot;
    private Node event;
    private DocumentProcessingResult lastResult;

    @Setup(Level.Trial)
    public void setUp() {
        repository = BlueRepository.latest();
        blue = new Blue()
                .nodeProvider(repository.nodeProvider())
                .typeClassResolver(repository.typeClassResolver());
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder().build());
        CoordinationDeliveryPlanning.currentRootCompatibility(blue);

        Node source = sourceDocument(effects)
                .blue(repository.typeAliasBlue());
        ResolvedSnapshot selected =
                blue.resolveToSnapshot(blue.preprocess(source));
        DocumentProcessingResult initialized = blue.initializeDocument(selected);
        requireSuccess(initialized);
        initializedSnapshot = blue.resolveToSnapshot(initialized.document());
        event = operationEvent();
    }

    @Benchmark
    public DocumentProcessingResult processComputeEffects() {
        lastResult = blue.processDocument(initializedSnapshot, event);
        return lastResult;
    }

    @TearDown(Level.Iteration)
    public void verify() {
        requireSuccess(lastResult);
        boolean changeset = effects.contains("changeset");
        boolean events = effects.contains("Events") || "events".equals(effects);
        boolean termination = effects.contains("Termination");
        Object expectedStatus = changeset ? "changed" : "idle";
        if (!expectedStatus.equals(lastResult.document().get("/status"))) {
            throw new IllegalStateException("Compute changeset was not applied");
        }
        Object cause = valueAt(lastResult.document(), "/contracts/terminated/cause");
        if (termination != "benchmark-complete".equals(cause)) {
            throw new IllegalStateException("Unexpected termination result: " + cause);
        }
        int expectedTriggeredEvents = (events ? 1 : 0) + (termination ? 1 : 0);
        if (lastResult.events().size() != expectedTriggeredEvents) {
            throw new IllegalStateException("Unexpected triggered event count: "
                    + lastResult.events().size());
        }
        int benchmarkEvents = 0;
        for (Node emitted : lastResult.events()) {
            Node type = emitted.getType();
            boolean expectedType = type != null
                    && (Event.qualifiedName().equals(type.getValue())
                    || Event.blueId().equals(type.getBlueId()));
            if (expectedType && "benchmark".equals(valueAt(emitted, "/kind"))) {
                benchmarkEvents++;
            }
        }
        if (benchmarkEvents != (events ? 1 : 0)) {
            throw new IllegalStateException("Unexpected benchmark event count: " + benchmarkEvents);
        }
    }

    private static Node sourceDocument(String effects) {
        boolean changeset = effects.contains("changeset");
        boolean events = effects.contains("Events") || "events".equals(effects);
        boolean termination = effects.contains("Termination");
        Node result = new Node();
        List<Node> statements = new ArrayList<Node>();
        if (changeset) {
            statements.add(new Node().properties("$appendChange", new Node()
                    .properties("op", new Node().value("replace"))
                    .properties("path", new Node().value("/status"))
                    .properties("val", new Node().value("changed"))));
            result.properties("changeset", new Node().properties("$changeset", new Node().value(true)));
        }
        if (events) {
            statements.add(new Node().properties("$appendEvent", new Node()
                    .type(typeReference(Event.blueId()))
                    .properties("kind", new Node().value("benchmark"))));
            result.properties("events", new Node().properties("$events", new Node().value(true)));
        }
        if (termination) {
            result.properties("termination", new Node()
                    .properties("cause", new Node().value("benchmark-complete"))
                    .properties("reason", new Node().value("benchmark-complete")));
        }
        statements.add(new Node().properties("$return", result));
        Node program = new Node().items(statements);

        Node channel = new Node()
                .type(typeReference(TimelineChannel.blueId()))
                .properties("timeline", new Node()
                        .type(typeReference(Timeline.blueId()))
                        .properties("providerId", new Node().value("test-provider"))
                        .properties("timelineId", new Node().value("owner")))
                .properties("actor", new Node()
                        .type(typeReference(PrincipalActor.blueId())));
        Node operation = new Node()
                .type(typeReference(
                        SequentialWorkflowOperation.blueId()))
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().type("Text"))
                .properties("steps", new Node().items(new Node()
                        .type(typeReference(Compute.blueId()))
                        .properties("do", program)));
        return new Node()
                .name("Compute Effect Plan Benchmark")
                .properties("status", new Node().value("idle"))
                .properties("contracts", new Node()
                        .properties("ownerChannel", channel)
                        .properties("run", operation));
    }

    private static Object valueAt(Node document, String path) {
        try {
            return document.get(path);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Node operationEvent() {
        TimelineEntry entry = new TimelineEntry()
                .timeline(new Timeline().timelineId("owner"))
                .actor(new PrincipalActor())
                .timestamp(BigInteger.ONE);
        Node request = new Node()
                .type(typeReference(OperationRequest.blueId()))
                .properties("operation", new Node().value("run"))
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().value("request"));
        Node source = blue.objectToNode(entry)
                .properties("timestamp", new Node().value(BigInteger.ONE))
                .properties("message", request)
                .blue(repository.typeAliasBlue());
        return blue.preprocess(source).blue(null);
    }

    private static Node typeReference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static void requireSuccess(DocumentProcessingResult result) {
        if (result == null || result.status() != ProcessorStatus.SUCCESS) {
            throw new IllegalStateException(result != null && result.diagnostic() != null
                    ? result.diagnostic().message()
                    : "missing result");
        }
    }
}
