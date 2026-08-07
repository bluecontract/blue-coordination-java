package blue.coordination.examples.support;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** One exact immutable Timeline Entry and its feeder order evidence. */
public record MyOsDemoEntry(
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

    public MyOsDemoEntry {
        exactEntry = Objects.requireNonNull(exactEntry, "exactEntry").clone();
        Objects.requireNonNull(blueId, "blueId");
        Objects.requireNonNull(orderKey, "orderKey");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(timelineId, "timelineId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(sourceChannel, "sourceChannel");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(handlerChannel, "handlerChannel");
    }

    @Override
    public Node exactEntry() {
        return exactEntry.clone();
    }
}
