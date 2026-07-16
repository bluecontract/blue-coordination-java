package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSPrincipalActor;

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
        blue = repository.configure(new Blue());
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder().build());

        Node source = sourceDocument(effects).blue(repository.typeAliasBlue());
        Node aliasesResolved = new RepositoryTypeAliasPreprocessor(repository).preprocess(source);
        ResolvedSnapshot selected = blue.resolveToSnapshot(blue.preprocess(aliasesResolved));
        DocumentProcessingResult initialized = blue.initializeDocument(selected);
        requireSuccess(initialized);
        initializedSnapshot = initialized.snapshot();
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
        boolean termination = effects.contains("Termination");
        Object expectedStatus = changeset ? "changed" : "idle";
        if (!expectedStatus.equals(lastResult.document().get("/status"))) {
            throw new IllegalStateException("Compute changeset was not applied");
        }
        Object cause = valueAt(lastResult.document(), "/contracts/terminated/cause");
        if (termination != "graceful".equals(cause)) {
            throw new IllegalStateException("Unexpected termination result: " + cause);
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
                    .type("Coordination/Event")
                    .properties("kind", new Node().value("benchmark"))));
            result.properties("events", new Node().properties("$events", new Node().value(true)));
        }
        if (termination) {
            result.properties("termination", new Node()
                    .properties("reason", new Node().value("benchmark-complete")));
        }
        statements.add(new Node().properties("$return", result));
        Node program = new Node().items(statements);

        Node channel = new Node()
                .type("Coordination/Timeline Channel")
                .properties("timeline", new Node()
                        .type("Coordination/Timeline")
                        .properties("timelineId", new Node().value("owner")))
                .properties("actor", new Node()
                        .type("MyOS/MyOS Principal Actor")
                        .properties("accountId", new Node().value("owner")));
        Node operation = new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().type("Text"))
                .properties("steps", new Node().items(new Node()
                        .type("Coordination/Compute")
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
                .actor(new MyOSPrincipalActor().accountId("owner"))
                .timestamp(BigInteger.ONE);
        Node request = new Node()
                .type("Coordination/Operation Request")
                .properties("operation", new Node().value("run"))
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().value("request"));
        Node source = blue.objectToNode(entry)
                .properties("timestamp", new Node().value(BigInteger.ONE))
                .properties("message", request)
                .blue(repository.typeAliasBlue());
        return blue.preprocess(new RepositoryTypeAliasPreprocessor(repository).preprocess(source)).blue(null);
    }

    private static void requireSuccess(DocumentProcessingResult result) {
        if (result == null || result.status() != ProcessorStatus.SUCCESS) {
            throw new IllegalStateException(result != null ? result.failureReason() : "missing result");
        }
    }
}
