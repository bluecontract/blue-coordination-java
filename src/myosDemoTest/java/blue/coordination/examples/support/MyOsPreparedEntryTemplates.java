package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationEventShapeTemplate;
import blue.coordination.fastpath.BoundedSingleFlightCache;
import blue.coordination.fastpath.CacheMetrics;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Objects;

/** JVM-shared bounded cache over the immutable current MyOS kernel. */
final class MyOsPreparedEntryTemplates {

    static final String CANONICAL_ENVIRONMENT_IDENTITY =
            "blue-coordination/myos-demo-entry-template/4.0";
    private static final long PROTOTYPE_TIMESTAMP_MICROS = 0L;
    private static final String PROTOTYPE_PREVIOUS_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(new Node().properties(
                    "kind", new Node().value(
                            "blue-coordination/event-shape-sentinel"),
                    "version", new Node().value(1L)));

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
            String previousEntryBlueId) {
        return requireShape(
                runtime,
                timeline,
                operation,
                previousEntryBlueId != null);
    }

    /**
     * The cache compiler deliberately accepts only shape facts. In
     * particular, no exact previous-entry identity can enter or be captured
     * by its single-flight loader.
     */
    private static MyOsPreparedEntryTemplate requireShape(
            MyOsDemoRuntime runtime,
            MyOsDemoTimeline timeline,
            MyOsDemoOperation operation,
            boolean hasPreviousEntry) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(operation, "operation");
        MyOsEntryTemplateKey key = MyOsEntryTemplateKey.of(
                CANONICAL_ENVIRONMENT_IDENTITY,
                runtime.eventAdmissionDomainIdentity(),
                timeline.timelineId(),
                timeline.actor(),
                operation,
                hasPreviousEntry);
        final boolean[] compiled = {false};
        MyOsPreparedEntryTemplate result = CACHE.getOrCompute(
                key,
                ignored -> {
                    compiled[0] = true;
                    String yaml = timeline.eventYaml(
                            operation,
                            PROTOTYPE_TIMESTAMP_MICROS,
                            null);
                    ResolvedSnapshot snapshot =
                            runtime.resolvedExactEvent(yaml);
                    Node exact = snapshot.canonicalRoot();
                    if (hasPreviousEntry) {
                        NodePathEditor.put(
                                exact,
                                "/prevEntry",
                                new Node().blueId(
                                        PROTOTYPE_PREVIOUS_BLUE_ID));
                    }
                    MyOsTimelineBinding binding = new MyOsTimelineBinding(
                            requiredResolvedBlueId(
                                    snapshot, "/timeline"),
                            requiredResolvedBlueId(snapshot, "/actor"));
                    CoordinationEventShapeTemplate eventShape =
                            runtime.compileEntryShape(
                                    "myos-entry/" + key,
                                    exact,
                                    key.hasPreviousEntry()
                                            ? List.of(
                                                    "/timestamp",
                                                    "/prevEntry")
                                            : List.of("/timestamp"));
                    METRICS.compiled();
                    return new MyOsPreparedEntryTemplate(
                            key, eventShape, binding, METRICS);
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
        return CACHE.retainedSize();
    }

    static CacheMetrics cacheMetrics() {
        return CACHE.metrics();
    }

    static long prototypeTimestampMicrosForAudit() {
        return PROTOTYPE_TIMESTAMP_MICROS;
    }

    static String prototypePreviousBlueIdForAudit() {
        return PROTOTYPE_PREVIOUS_BLUE_ID;
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
