package blue.coordination.examples.support;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Immutable resolved prototype for one Timeline/actor/operation shape. */
final class MyOsPreparedEntryTemplate {

    private final MyOsEntryTemplateKey key;
    private final FrozenNode exactPrototype;
    private final MyOsTimelineBinding binding;
    private final MyOsAppendTemplateMetrics metrics;

    MyOsPreparedEntryTemplate(
            MyOsEntryTemplateKey key,
            Node exactPrototype,
            MyOsTimelineBinding binding,
            MyOsAppendTemplateMetrics metrics) {
        this.key = Objects.requireNonNull(key, "key");
        this.exactPrototype = FrozenNode.fromNode(
                Objects.requireNonNull(exactPrototype, "exactPrototype"));
        this.binding = Objects.requireNonNull(binding, "binding");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    long approximateRetainedWeightBytes() {
        return exactPrototype.approximateRetainedWeightBytes();
    }

    PendingTimelineAppend instantiate(
            MyOsDemoTimeline owner,
            MyOsDemoRuntime runtime,
            MyOsDemoOperation operation,
            long timestampMicros,
            String previousEntryBlueId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(operation, "operation");
        if (key.hasPreviousEntry() != (previousEntryBlueId != null)) {
            throw new IllegalArgumentException(
                    "Prepared entry previous-link shape differs");
        }
        Node exact = exactPrototype.toNode();
        metrics.materialized();
        NodePathEditor.put(
                exact,
                "/timestamp",
                new Node().value(timestampMicros));
        metrics.leafPatched();
        if (previousEntryBlueId != null) {
            NodePathEditor.put(
                    exact,
                    "/prevEntry",
                    new Node().blueId(previousEntryBlueId));
            metrics.leafPatched();
        }
        metrics.rootBlueIdCalculated();
        String blueId = runtime.directBlueId(exact);
        ExternalOrderKey orderKey = ExternalOrderKey.of(List.<Object>of(
                BigInteger.valueOf(timestampMicros),
                key.timelineId(),
                blueId));
        return new PendingTimelineAppend(
                owner,
                new MyOsDemoEntry(
                        exact,
                        blueId,
                        orderKey,
                        binding,
                        key.timelineId(),
                        owner.actor().actorId(),
                        operation.sourceChannel(),
                        operation.operation(),
                        operation.handlerChannel(),
                        timestampMicros),
                previousEntryBlueId);
    }
}
