package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.Timeline;

import blue.coordination.api.Operation;

import blue.coordination.api.ExactValue;

import blue.coordination.api.EnvironmentFrontier;

import blue.coordination.api.DocumentId;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds one whole request object and one whole Timeline Entry object.
 * The entry contains one pure reference to the request; neither value is split.
 *
 * <p>Static event shape is resolved once per timeline/actor/operation/channel
 * shape. Later entries use {@link FrozenNode} structural sharing to replace
 * only timestamp, predecessor, and request reference. This is an exact
 * Language-supported canonical edit, not an approximate hand-built event.</p>
 */
final class WholeRequestEntryFactory {
    private static final Set<String> PRESERVED_EVENT_PATHS =
            Set.of("/message/request");

    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final EngineMetrics metrics;
    private final Map<EventShapeKey, FrozenNode> eventTemplates =
            new LinkedHashMap<>();

    public WholeRequestEntryFactory(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EngineMetrics metrics) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public TimelineEntry create(
            Timeline timeline,
            String previousEntryBlueId,
            Operation operation,
            long timestampMicros,
            long globalSequence,
            long timelineSequence,
            EnvironmentFrontier appendFrontier,
            boolean processorManaged,
            DocumentId target,
            TimelineEntry.CatchUpCause cause,
            ExternalOrderKey sourceOrderOverride) {
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(operation, "operation");
        if (timestampMicros <= 0L) {
            throw new IllegalArgumentException("timestampMicros must be positive");
        }

        ExactValue request = metrics.timed(
                "append.request.retainWhole",
                () -> exactRequest(operation));
        ExactValue event = metrics.timed(
                "append.event.buildRetainWhole",
                () -> exactEvent(
                        timeline,
                        previousEntryBlueId,
                        operation,
                        timestampMicros,
                        request));
        ExternalOrderKey journalOrderKey = ExternalOrderKey.of(List.of(
                BigInteger.valueOf(timestampMicros),
                timeline.timelineId(),
                event.blueId()));
        ExternalOrderKey sourceOrderKey = sourceOrderOverride == null
                ? journalOrderKey
                : sourceOrderOverride;
        metrics.increment("append.entriesBuilt");
        return new TimelineEntry(
                event,
                request,
                journalOrderKey,
                sourceOrderKey,
                timeline,
                operation.operation(),
                operation.channel(),
                timestampMicros,
                globalSequence,
                timelineSequence,
                appendFrontier,
                processorManaged,
                target,
                cause);
    }

    public ExactValue parseExactRequest(String requestYaml) {
        return exactRequest(Operation.yaml(
                "requestOnly", "requestOnly", requestYaml));
    }

    private ExactValue exactRequest(Operation operation) {
        if (operation.exactRequest().isPresent()) {
            metrics.increment("append.exactRequestsReused");
            return objects.put(
                    operation.exactRequest().orElseThrow(),
                    "timeline-request");
        }
        Node source = runtime.parseSourceYaml(
                operation.requestYaml().orElseThrow());
        Node preprocessed = runtime.preprocess(source);
        ResolvedSnapshot snapshot = runtime.cache(
                runtime.resolveToSnapshot(preprocessed));
        return objects.put(snapshot, "timeline-request");
    }

    private ExactValue exactEvent(
            Timeline timeline,
            String previousEntryBlueId,
            Operation operation,
            long timestampMicros,
            ExactValue request) {
        EventShapeKey key = new EventShapeKey(
                timeline.timelineId(),
                timeline.actorId(),
                operation.operation(),
                operation.channel(),
                previousEntryBlueId != null);
        FrozenNode template;
        synchronized (eventTemplates) {
            template = eventTemplates.get(key);
            if (template == null) {
                template = compileTemplate(
                        timeline,
                        previousEntryBlueId,
                        operation,
                        timestampMicros,
                        request);
                eventTemplates.put(key, template);
                metrics.increment("append.eventTemplatesCompiled");
            } else {
                metrics.increment("append.eventTemplateHits");
            }
        }

        FrozenNode message = requireChild(template, "message")
                .withProperty("request", reference(request.blueId()));
        FrozenNode event = template
                .withProperty("timestamp", scalar(timestampMicros))
                .withProperty("message", message)
                .withProperty(
                        "prevEntry",
                        previousEntryBlueId == null
                                ? null
                                : reference(previousEntryBlueId));
        FrozenNode requestNode = event.at("/message/request");
        if (requestNode == null
                || !request.blueId().equals(requestNode.getReferenceBlueId())) {
            throw new IllegalStateException(
                    "Timeline Entry request must remain one whole-object reference");
        }
        return objects.put(event, "timeline-entry");
    }

    private FrozenNode compileTemplate(
            Timeline timeline,
            String previousEntryBlueId,
            Operation operation,
            long timestampMicros,
            ExactValue request) {
        Node timelineNode = new Node()
                .type("MyOS/MyOS Timeline")
                .properties("timelineId", scalarNode(timeline.timelineId()));
        Node actorNode = new Node()
                .type("MyOS/Principal Actor")
                .properties("accountId", scalarNode(timeline.actorId()));
        Node messageNode = new Node()
                .type("Coordination/Operation Request")
                .properties(new LinkedHashMap<>(Map.of(
                        "operation", scalarNode(operation.operation()),
                        "channel", scalarNode(operation.channel()),
                        "request", request.referenceNode())));
        Map<String, Node> properties = new LinkedHashMap<>();
        properties.put("timeline", timelineNode);
        if (previousEntryBlueId != null) {
            properties.put("prevEntry", new Node().blueId(previousEntryBlueId));
        }
        properties.put("timestamp", new Node().value(timestampMicros));
        properties.put("actor", actorNode);
        properties.put("message", messageNode);
        Node source = new Node()
                .type("Coordination/Timeline Entry")
                .properties(properties);
        Node preprocessed = runtime.preprocess(source);
        ResolvedSnapshot snapshot = runtime.resolveToSnapshotPreservingPaths(
                preprocessed, PRESERVED_EVENT_PATHS);
        return snapshot.frozenCanonicalRoot();
    }

    private static FrozenNode requireChild(FrozenNode parent, String key) {
        FrozenNode child = parent.property(key);
        if (child == null) {
            throw new IllegalStateException("Event template has no " + key);
        }
        return child;
    }

    private static FrozenNode reference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(
                Objects.requireNonNull(blueId, "blueId")));
    }

    private static FrozenNode scalar(Object value) {
        return FrozenNode.fromNode(new Node().value(
                Objects.requireNonNull(value, "value")));
    }

    private static Node scalarNode(String value) {
        return new Node().value(Objects.requireNonNull(value, "value"))
                .inlineValue(true);
    }

    private record EventShapeKey(
            String timelineId,
            String actorId,
            String operation,
            String channel,
            boolean hasPreviousEntry) {
        private EventShapeKey {
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
