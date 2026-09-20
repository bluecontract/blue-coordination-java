package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.Timeline;

import blue.coordination.api.Operation;

import blue.coordination.api.ExactValue;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds one optional whole request object and one whole Timeline Entry object.
 * A present request is retained behind one pure reference; an absent request
 * contributes no {@code request} member at all.
 *
 * <p>Static event shape is resolved once per timeline/actor/operation/channel
 * shape. Later entries use {@link FrozenNode} structural sharing to replace
 * only timestamp, predecessor, and request reference. This is an exact
 * Language-supported canonical edit, not an approximate hand-built event.</p>
 */
final class WholeRequestEntryFactory {
    static final String REQUEST_SOURCES_PARSED =
            "append.requestSourcesParsed";

    private static final Set<String> PRESERVED_EVENT_PATHS =
            Set.of("/message/document", "/message/request");

    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final EngineMetrics metrics;
    private final Map<EventShapeKey, FrozenNode> eventTemplates =
            new LinkedHashMap<>();
    private final Function<String, String> actorType;
    private final boolean exactTimelineOrder;

    public WholeRequestEntryFactory(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EngineMetrics metrics) {
        this(runtime, objects, metrics,
                ignored -> "MyOS/Principal Actor");
    }

    public WholeRequestEntryFactory(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EngineMetrics metrics,
            Function<String, String> actorType) {
        this(runtime, objects, metrics, actorType, false);
    }

    WholeRequestEntryFactory(BlueRuntime runtime, WholeObjectStore objects, EngineMetrics metrics,
            Function<String, String> actorType, boolean exactTimelineOrder) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.exactTimelineOrder = exactTimelineOrder;
    }

    public TimelineEntry create(
            Timeline timeline,
            String previousEntryBlueId,
            Operation operation,
            long timestampMicros,
            long globalSequence,
            long timelineSequence) {
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(operation, "operation");
        if (timestampMicros <= 0L) {
            throw new IllegalArgumentException("timestampMicros must be positive");
        }

        Optional<ExactValue> request = metrics.timed(
                "append.request.retainWhole",
                () -> exactRequest(operation));
        ExactValue event = metrics.timed(
                "append.event.buildRetainWhole",
                () -> exactEvent(
                        timeline,
                        previousEntryBlueId,
                        operation,
                        timestampMicros,
                        request,
                        "append"));
        ExternalOrderKey journalOrderKey = ExternalOrderKey.of(List.of(
                BigInteger.valueOf(timestampMicros),
                orderTimeline(event, timeline.timelineId()),
                event.blueId()));
        metrics.increment("append.entriesBuilt");
        return new TimelineEntry(
                event,
                request,
                journalOrderKey,
                journalOrderKey,
                timeline,
                operation.operation(),
                operation.channel(),
                timestampMicros,
                globalSequence,
                timelineSequence);
    }

    /** Retains one already exact external envelope without rebuilding it. */
    public TimelineEntry createExact(
            Timeline registeredTimeline,
            ExactValue exactEvent,
            long globalSequence,
            long timelineSequence) {
        Objects.requireNonNull(registeredTimeline, "registeredTimeline");
        ExactValue supplied = Objects.requireNonNull(
                exactEvent, "exactEvent");
        blue.coordination.processor.TimelineProviderSupport.validateExactEnvelope(supplied.copyNode());
        FrozenNode root = supplied.frozen();
        String timelineId = (String) root.at(
                "/timeline/timelineId").getValue();
        String actorId = (String) root.at("/actor/accountId").getValue();
        if (!registeredTimeline.equals(new Timeline(timelineId, actorId))) {
            throw new IllegalArgumentException(
                    "Timeline Entry does not match the registered Timeline");
        }
        long timestamp = new BigInteger(
                root.at("/timestamp").getValue().toString()).longValueExact();
        Optional<TimelineEntry.OperationDetails> details = operationDetails(root, true);
        ExactValue retainedEvent = objects.put(
                supplied, "timeline-entry");
        ExternalOrderKey order = ExternalOrderKey.of(List.of(
                BigInteger.valueOf(timestamp),
                orderTimeline(retainedEvent, timelineId),
                retainedEvent.blueId()));
        metrics.increment("append.entriesBuilt");
        return new TimelineEntry(retainedEvent, details, order, order, registeredTimeline,
                timestamp, globalSequence, timelineSequence);
    }

    private Optional<TimelineEntry.OperationDetails> operationDetails(FrozenNode root, boolean retain) {
        FrozenNode message = runtime.materializeExact(Objects.requireNonNull(root.property("message"), "message"));
        if (!runtime.isOperationMessage(message)) return Optional.empty();
        String operation = requiredRoutingText(message.property("operation"), "operation");
        String channel = requiredRoutingText(message.property("channel"), "channel");
        FrozenNode version = message.property("requireExactDocumentVersion");
        // Materialized optional fields can carry their Boolean schema without a value.
        boolean unsetVersion = version != null && version.getValue() == null
                && version.getType() != null
                && blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID.equals(version.getType().getReferenceBlueId())
                && version.getItems() == null && (version.getProperties() == null || version.getProperties().isEmpty());
        if (version != null && !unsetVersion && !(version.getValue() instanceof Boolean)) {
            throw new IllegalArgumentException("requireExactDocumentVersion must be Boolean");
        }
        if (version != null && Boolean.TRUE.equals(version.getValue()) && message.property("document") == null) {
            throw new IllegalArgumentException("Exact document version requires a document");
        }
        FrozenNode request = message.property("request");
        Optional<ExactValue> value = Optional.ofNullable(request).map(node -> retain
                ? retainExactRequest(node) : ExactValue.fromFrozen(runtime.materializeExact(node)));
        return Optional.of(new TimelineEntry.OperationDetails(operation, channel, value));
    }

    private static String requiredRoutingText(FrozenNode value, String field) {
        if (value == null || !(value.getValue() instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Operation Request requires nonblank Text " + field);
        }
        return text;
    }

    private String orderTimeline(ExactValue entry, String legacyTimelineName) {
        return exactTimelineOrder ? entry.canonicalAt("/timeline").blueId() : legacyTimelineName;
    }

    /** Verifies stored coordinates and derived metadata against authenticated exact message/type evidence. */
    void verifyStoredEntry(TimelineEntry entry) {
        ExactValue exact = entry.exactEvent();
        blue.coordination.processor.TimelineProviderSupport.validateExactEnvelope(exact.copyNode());
        FrozenNode root = exact.frozen();
        String timelineId = (String) root.at("/timeline/timelineId").getValue();
        String actorId = (String) root.at("/actor/accountId").getValue();
        long timestamp = new BigInteger(root.at("/timestamp").getValue().toString()).longValueExact();
        ExternalOrderKey order = ExternalOrderKey.of(List.of(
                BigInteger.valueOf(timestamp), orderTimeline(exact, timelineId), exact.blueId()));
        if (!new Timeline(timelineId, actorId).equals(entry.timeline())
                || timestamp != entry.timestampMicros()
                || !order.equals(entry.journalOrderKey())
                || !order.equals(entry.sourceOrderKey())) {
            throw new IllegalArgumentException("Stored Timeline metadata differs from exact event");
        }
        Optional<TimelineEntry.OperationDetails> actual = operationDetails(root, false);
        if (actual.isPresent() != entry.operationDetails().isPresent()) {
            throw new IllegalArgumentException("Stored entry kind differs from exact message type");
        }
        if (actual.isPresent()) {
            var expected = actual.orElseThrow();
            var stored = entry.operationDetails().orElseThrow();
            if (!expected.operation().equals(stored.operation()) || !expected.channel().equals(stored.channel())
                    || expected.request().isPresent() != stored.request().isPresent()
                    || expected.request().isPresent() && !expected.request().orElseThrow()
                            .sameExactValue(stored.request().orElseThrow())) {
                throw new IllegalArgumentException("Stored operation metadata differs from exact event");
            }
        }
    }

    /**
     * Retains one exact request body from an accepted external envelope.
     *
     * <p>A pure reference is presence evidence, not an absent request. Resolve
     * it through the same verified provider graph used by the frozen runtime;
     * unavailable evidence therefore remains incomplete instead of becoming
     * an unknown local object or an empty value.</p>
     */
    ExactValue retainExactRequest(FrozenNode requestNode) {
        if (!requestNode.isReferenceOnly()) {
            return objects.put(requestNode, "timeline-request");
        }
        String requestBlueId = requestNode.getReferenceBlueId();
        if (objects.contains(requestBlueId)) {
            ExactValue retained = objects.require(requestBlueId);
            if (!retained.frozen().isReferenceOnly()) {
                return retained;
            }
        }
        ResolvedSnapshot resolved = runtime.loadExactSnapshot(requestBlueId);
        ExactValue retained = objects.put(resolved, "timeline-request");
        if (!requestBlueId.equals(retained.blueId())) {
            throw new IllegalStateException(
                    "Resolved Timeline Entry request changed exact identity");
        }
        return retained;
    }

    public ExactValue parseExactRequest(String requestYaml) {
        return exactRequest(Operation.yaml(
                "requestOnly", "requestOnly", requestYaml)).orElseThrow();
    }

    ExactValue createProcessorOwnedEvent(
            Timeline timeline,
            String previousEntryBlueId,
            long timestampMicros,
            ExactValue request) {
        return exactEvent(
                timeline,
                previousEntryBlueId,
                Operation.exact(
                        EmbeddedEpochInput.INTERNAL_OPERATION,
                        EmbeddedEpochInput.INTERNAL_CHANNEL,
                        request),
                timestampMicros,
                Optional.of(request),
                "process.embeddedInput");
    }

    private Optional<ExactValue> exactRequest(Operation operation) {
        if (operation.exactRequest().isPresent()) {
            metrics.increment("append.exactRequestsReused");
            return Optional.of(objects.put(
                    operation.exactRequest().orElseThrow(),
                    "timeline-request"));
        }
        if (operation.requestYaml().isEmpty()) {
            metrics.increment("append.absentRequests");
            return Optional.empty();
        }
        metrics.increment(REQUEST_SOURCES_PARSED);
        Node source = runtime.parseSourceYaml(
                operation.requestYaml().orElseThrow());
        Node preprocessed = runtime.preprocess(source);
        ResolvedSnapshot snapshot = runtime.cache(
                runtime.resolveToSnapshot(preprocessed));
        return Optional.of(objects.put(snapshot, "timeline-request"));
    }

    private ExactValue exactEvent(
            Timeline timeline,
            String previousEntryBlueId,
            Operation operation,
            long timestampMicros,
            Optional<ExactValue> request,
            String metricPrefix) {
        EventShapeKey key = new EventShapeKey(
                timeline.timelineId(),
                timeline.actorId(),
                actorType.apply(timeline.timelineId()),
                operation.operation(),
                operation.channel(),
                previousEntryBlueId != null,
                request.isPresent(),
                operation.targetDocument().isPresent(),
                operation.requireExactDocumentVersion());
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
                metrics.increment(metricPrefix + ".eventTemplatesCompiled");
            } else {
                metrics.increment(metricPrefix + ".eventTemplateHits");
            }
        }

        FrozenNode message = requireChild(template, "message");
        if (request.isPresent()) {
            message = message.withProperty(
                    "request", reference(request.orElseThrow().blueId()));
        } else {
            message = message.withProperty("request", null);
        }
        if (operation.targetDocument().isPresent()) {
            ExactValue target = objects.put(
                    operation.targetDocument().orElseThrow(),
                    "operation-document-target");
            message = message.withProperty(
                    "document", reference(target.blueId()));
        }
        FrozenNode event = template
                .withProperty("timestamp", scalar(timestampMicros))
                .withProperty("message", message)
                .withProperty(
                        "prevEntry",
                        previousEntryBlueId == null
                                ? null
                                : reference(previousEntryBlueId));
        FrozenNode requestNode = event.at("/message/request");
        if (request.isPresent()
                ? requestNode == null
                        || !request.orElseThrow().blueId().equals(
                                requestNode.getReferenceBlueId())
                : requestNode != null) {
            throw new IllegalStateException(
                    "Timeline Entry request presence or whole-object reference changed");
        }
        return objects.put(event, "timeline-entry");
    }

    private FrozenNode compileTemplate(
            Timeline timeline,
            String previousEntryBlueId,
            Operation operation,
            long timestampMicros,
            Optional<ExactValue> request) {
        Node timelineNode = new Node()
                .type("MyOS/MyOS Timeline")
                .properties("timelineId", scalarNode(timeline.timelineId()));
        Node actorNode = new Node()
                .type(actorType.apply(timeline.timelineId()))
                .properties("accountId", scalarNode(timeline.actorId()));
        Node messageNode = new Node()
                .type("Coordination/Operation Request")
                .properties(new LinkedHashMap<>(Map.of(
                        "operation", scalarNode(operation.operation()),
                        "channel", scalarNode(operation.channel()))));
        request.ifPresent(exact -> messageNode.properties(
                "request", exact.referenceNode()));
        operation.targetDocument().ifPresent(target -> {
            messageNode.properties("document", target.referenceNode());
            if (operation.requireExactDocumentVersion()) {
                messageNode.properties(
                        "requireExactDocumentVersion",
                        new Node().value(true));
            }
        });
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
            String actorType,
            String operation,
            String channel,
            boolean hasPreviousEntry,
            boolean hasRequest,
            boolean hasDocumentTarget,
            boolean requireExactDocumentVersion) {
        private EventShapeKey {
            timelineId = requireText(timelineId, "timelineId");
            actorId = requireText(actorId, "actorId");
            actorType = requireText(actorType, "actorType");
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
