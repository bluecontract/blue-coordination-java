package blue.coordination.examples.support;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** One exact immutable Timeline Entry and its feeder order evidence. */
public record MyOsDemoEntry(
        FrozenNode frozenExactEntry,
        String blueId,
        ExternalOrderKey orderKey,
        MyOsTimelineBinding binding,
        String timelineId,
        String actorId,
        String sourceChannel,
        String operation,
        String handlerChannel,
        long timestampMicros) {

    public MyOsDemoEntry {
        frozenExactEntry = Objects.requireNonNull(
                frozenExactEntry, "frozenExactEntry");
        Objects.requireNonNull(blueId, "blueId");
        Objects.requireNonNull(orderKey, "orderKey");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(timelineId, "timelineId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(sourceChannel, "sourceChannel");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(handlerChannel, "handlerChannel");
    }

    /** Compatibility constructor for tests and non-shape callers. */
    public MyOsDemoEntry(
            Node exactEntry,
            String blueId,
            ExternalOrderKey orderKey,
            MyOsTimelineBinding binding,
            String timelineId,
            String actorId,
            String sourceChannel,
            String operation,
            String handlerChannel,
            long timestampMicros) {
        this(
                FrozenNode.fromNode(Objects.requireNonNull(
                        exactEntry, "exactEntry")),
                blueId,
                orderKey,
                binding,
                timelineId,
                actorId,
                sourceChannel,
                operation,
                handlerChannel,
                timestampMicros);
    }

    /** Returns a caller-owned mutable materialization. */
    public Node exactEntry() {
        return frozenExactEntry.toNode();
    }
}
