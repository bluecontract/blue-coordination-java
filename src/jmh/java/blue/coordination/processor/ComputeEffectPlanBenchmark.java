package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.identity.DirectBlueIdCalculator;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Measures the Coordination boundary for each active Compute-effect combination. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ComputeEffectPlanBenchmark {
    @Param({"changeset", "events", "changesetEvents", "changesetEventsTermination"})
    public String effects;

    private CoordinationBenchmarkRuntime runtime;
    private Node initializedRoot;
    private Node event;
    private ExternalDeliveryPlanDeriver publicBoundary;
    private int lastDeliveryCount = -1;

    @Setup(Level.Trial)
    public void setUp() {
        runtime = CoordinationBenchmarkRuntime.create();

        Node source = sourceDocument(effects);
        initializedRoot = runtime.preprocess(source);
        event = operationEvent();
        ExternalOrderKey order = eventOrder(event);
        SubscriptionDelta initial = runtime.contracts()
                .subscriptionSurfaceProjection()
                .projectInitial(
                        initializedRoot,
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
    public int processComputeEffects() {
        lastDeliveryCount = publicBoundary
                .derive(initializedRoot, event)
                .deliveries().size();
        return lastDeliveryCount;
    }

    @TearDown(Level.Iteration)
    public void verify() {
        if (lastDeliveryCount <= 0) {
            throw new IllegalStateException(
                    "Compute benchmark did not derive a selected delivery: "
                            + lastDeliveryCount);
        }
    }

    @TearDown(Level.Trial)
    public void closeRuntime() {
        runtime.close();
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

    private Node operationEvent() {
        Node request = new Node()
                .type(typeReference(OperationRequest.blueId()))
                .properties("operation", new Node().value("run"))
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().value("request"));
        Node source = new Node()
                .type(typeReference(TimelineEntry.blueId()))
                .properties("timeline", new Node()
                        .type(typeReference(Timeline.blueId()))
                        .properties("timelineId", new Node().value("owner")))
                .properties("actor", new Node()
                        .type(typeReference(PrincipalActor.blueId())))
                .properties("timestamp", new Node().value(BigInteger.ONE))
                .properties("message", request);
        return runtime.preprocess(source).blue(null);
    }

    private static ExternalOrderKey eventOrder(Node event) {
        return ExternalOrderKey.of(Collections.<Object>singletonList(
                DirectBlueIdCalculator.calculateBlueId(event)));
    }

    private static Node typeReference(String blueId) {
        return new Node().blueId(blueId);
    }

}
