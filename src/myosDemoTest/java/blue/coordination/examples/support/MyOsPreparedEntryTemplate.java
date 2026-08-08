package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationEventShapeInstance;
import blue.coordination.engine.api.CoordinationEventShapePatch;
import blue.coordination.engine.api.CoordinationEventShapeTemplate;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable resolved prototype and fragment topology for one entry shape. */
final class MyOsPreparedEntryTemplate {

    private final MyOsEntryTemplateKey key;
    private final CoordinationEventShapeTemplate eventShape;
    private final MyOsTimelineBinding binding;
    private final MyOsAppendTemplateMetrics metrics;

    MyOsPreparedEntryTemplate(
            MyOsEntryTemplateKey key,
            CoordinationEventShapeTemplate eventShape,
            MyOsTimelineBinding binding,
            MyOsAppendTemplateMetrics metrics) {
        this.key = Objects.requireNonNull(key, "key");
        this.eventShape = Objects.requireNonNull(eventShape, "eventShape");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    long approximateRetainedWeightBytes() {
        return eventShape.approximateRetainedWeightBytes();
    }

    Node sentinelPrototypeForAudit() {
        return eventShape.sentinelPrototypeForAudit();
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
        List<CoordinationEventShapePatch> patches =
                new ArrayList<CoordinationEventShapePatch>(2);
        patches.add(CoordinationEventShapePatch.scalar(
                "/timestamp", timestampMicros));
        if (previousEntryBlueId != null) {
            patches.add(CoordinationEventShapePatch.reference(
                    "/prevEntry", previousEntryBlueId));
        }
        CoordinationEventShapeInstance preparedEvent =
                runtime.instantiateEntryShape(eventShape, patches);
        metrics.materialized();
        for (int index = 0; index < patches.size(); index++) {
            metrics.leafPatched();
        }
        metrics.rootBlueIdCalculated();
        String blueId = preparedEvent.eventBlueId();
        ExternalOrderKey orderKey = ExternalOrderKey.of(List.<Object>of(
                BigInteger.valueOf(timestampMicros),
                key.timelineId(),
                blueId));
        return new PendingTimelineAppend(
                owner,
                new MyOsDemoEntry(
                        preparedEvent.frozenExactEvent(),
                        blueId,
                        orderKey,
                        binding,
                        key.timelineId(),
                        owner.actor().actorId(),
                        operation.sourceChannel(),
                        operation.operation(),
                        operation.handlerChannel(),
                        timestampMicros),
                preparedEvent,
                previousEntryBlueId);
    }
}
