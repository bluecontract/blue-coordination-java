package blue.coordination.examples.support;

import blue.coordination.engine.memory.BoundedSingleFlightCache;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** JVM-shared bounded cache over the immutable current MyOS kernel. */
final class MyOsPreparedEntryTemplates {

    static final String CANONICAL_ENVIRONMENT_IDENTITY =
            "blue-coordination/myos-demo-entry-template/3.0";

    private static final int MAXIMUM_TEMPLATES = 256;
    private static final long MAXIMUM_TEMPLATE_WEIGHT_BYTES =
            64L * 1024L * 1024L;
    private static final BoundedSingleFlightCache<
            MyOsEntryTemplateKey,
            MyOsPreparedEntryTemplate> CACHE =
            new BoundedSingleFlightCache<>(
                    MAXIMUM_TEMPLATES,
                    MAXIMUM_TEMPLATE_WEIGHT_BYTES,
                    MyOsPreparedEntryTemplate
                            ::approximateRetainedWeightBytes);
    private static final MyOsAppendTemplateMetrics METRICS =
            new MyOsAppendTemplateMetrics();

    private MyOsPreparedEntryTemplates() {
    }

    static MyOsPreparedEntryTemplate require(
            MyOsDemoRuntime runtime,
            MyOsDemoTimeline timeline,
            MyOsDemoOperation operation,
            long prototypeTimestampMicros,
            String prototypePreviousBlueId) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(operation, "operation");
        MyOsEntryTemplateKey key = MyOsEntryTemplateKey.of(
                CANONICAL_ENVIRONMENT_IDENTITY,
                timeline.timelineId(),
                timeline.actor(),
                operation,
                prototypePreviousBlueId != null);
        final boolean[] compiled = {false};
        MyOsPreparedEntryTemplate result = CACHE.compute(
                key,
                ignored -> {
                    compiled[0] = true;
                    String yaml = timeline.eventYaml(
                            operation,
                            prototypeTimestampMicros,
                            prototypePreviousBlueId);
                    ResolvedSnapshot snapshot =
                            runtime.resolvedExactEvent(yaml);
                    Node exact = snapshot.canonicalRoot();
                    MyOsTimelineBinding binding = new MyOsTimelineBinding(
                            requiredResolvedBlueId(
                                    snapshot, "/timeline"),
                            requiredResolvedBlueId(snapshot, "/actor"));
                    METRICS.compiled();
                    return new MyOsPreparedEntryTemplate(
                            key, exact, binding, METRICS);
                });
        if (compiled[0]) {
            METRICS.miss();
        } else {
            METRICS.hit();
        }
        return result;
    }

    static MyOsAppendTemplateMetrics.Snapshot metrics() {
        return METRICS.snapshot();
    }

    static int size() {
        return CACHE.size();
    }

    static BoundedSingleFlightCache.Snapshot cacheMetrics() {
        return CACHE.metrics();
    }

    private static String requiredResolvedBlueId(
            ResolvedSnapshot snapshot,
            String path) {
        FrozenNode selected = Objects.requireNonNull(
                snapshot, "snapshot").resolvedAt(path);
        if (selected == null) {
            throw new IllegalArgumentException(
                    "Exact value is absent at " + path);
        }
        return selected.blueId();
    }
}
