package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.HandlerMatchContext;
import blue.repo.BlueRepository;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.TimelineEntry;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

final class CoordinationEventNodes {
    private static final BlueRepository REPOSITORY = BlueRepository.latest();
    private static final ThreadLocal<Blue> BINDING_CONVERTER = new ThreadLocal<Blue>() {
        @Override
        protected Blue initialValue() {
            return new Blue()
                    .nodeProvider(REPOSITORY.nodeProvider())
                    .typeClassResolver(REPOSITORY.typeClassResolver());
        }
    };
    private static final ThreadLocal<Blue> FINAL_BINDING_CONVERTER =
            new ThreadLocal<Blue>() {
        @Override
        protected Blue initialValue() {
            return new Blue();
        }
    };
    private static final ThreadLocal<Blue> LEGACY_TYPE_MATCHER =
            new ThreadLocal<Blue>() {
        @Override
        protected Blue initialValue() {
            return new Blue()
                    .nodeProvider(REPOSITORY.nodeProvider())
                    .typeClassResolver(REPOSITORY.typeClassResolver());
        }
    };
    private static final Node TIMELINE_ENTRY_TYPE = new Node()
            .type(new Node().blueId(TimelineEntry.blueId()));
    private static final Node OPERATION_REQUEST_TYPE = new Node()
            .type(new Node().blueId(OperationRequest.blueId()));

    private CoordinationEventNodes() {
    }

    static TimelineEntryView timelineEntry(Node node) {
        if (!isTimelineEntry(node)) {
            return null;
        }
        return timelineEntryHeader(node);
    }

    static TimelineEntryView timelineEntry(
            Node node,
            ExternalChannelFunctionContext context) {
        Node projected = projectTimelineEntry(
                node, context);
        if (!isTimelineEntry(projected, context)) {
            return null;
        }
        return timelineEntryHeader(projected);
    }

    static TimelineEntryView timelineEntryHeader(
            Node node,
            ExternalChannelFunctionContext context) {
        return timelineEntryHeader(
                projectTimelineEntry(node, context));
    }

    static TimelineEntryView timelineEntryHeader(Node node) {
        Node timeline = property(node, "timeline");
        Node actor = property(node, "actor");
        BigInteger timestamp = timestamp(node);
        Node message = property(node, "message");
        if (timeline == null
                || actor == null
                || timestamp == null
                || message == null) {
            return null;
        }
        return new TimelineEntryView(timeline, actor, timestamp);
    }

    static boolean isTimelineEntry(Node node) {
        if (node == null || node.getType() == null) {
            return false;
        }
        Node type = node.getType();
        if (TimelineEntry.blueId().equals(type.getBlueId())) {
            return true;
        }
        try {
            if (BlueSemanticIdentity.equals(
                    type,
                    new Node().blueId(TimelineEntry.blueId()))) {
                return true;
            }
            return LEGACY_TYPE_MATCHER.get().nodeMatchesType(
                    new Node().type(type.clone()),
                    TIMELINE_ENTRY_TYPE);
        } catch (RuntimeException invalidTypeEvidence) {
            return false;
        }
    }

    static boolean isTimelineEntry(
            Node node,
            ExternalChannelFunctionContext context) {
        if (node == null || node.getType() == null) {
            return false;
        }
        if (TimelineEntry.blueId().equals(
                node.getType().getBlueId())) {
            return true;
        }
        return context.matchesPattern(
                node,
                new Node().type(
                        new Node().blueId(
                                TimelineEntry.blueId())));
    }

    static BigInteger timestamp(Node node) {
        return integerProperty(node, "timestamp");
    }

    static boolean matchesGeneratedBinding(Node candidate, Object configuredBinding) {
        if (configuredBinding == null || candidate == null) {
            return false;
        }
        Node pattern = BINDING_CONVERTER.get().objectToNode(configuredBinding);
        return candidate.isReferenceOnly()
                ? BlueSemanticIdentity.equals(candidate, pattern)
                : matchesPattern(candidate, pattern);
    }

    static boolean matchesGeneratedBinding(
            Node candidate,
            Object configuredBinding,
            ExternalChannelFunctionContext context) {
        return configuredBinding != null
                && candidate != null
                && context.matchesPattern(
                candidate,
                FINAL_BINDING_CONVERTER.get().objectToNode(
                        configuredBinding));
    }

    static OperationRequestView operationRequest(Node event) {
        if (matchesOperationRequestType(event)) {
            return OperationRequestView.from(event);
        }
        if (!isTimelineEntry(event)) {
            return null;
        }
        Node message = property(event, "message");
        return matchesOperationRequestType(message)
                ? OperationRequestView.from(message)
                : null;
    }

    static OperationRequestView operationRequest(
            Node event,
            ExternalChannelFunctionContext context) {
        if (event == null || context == null) {
            return null;
        }
        Node projectedEvent =
                materializeIfReference(event, context);
        if (declaresExactType(
                projectedEvent,
                TimelineEntry.blueId())) {
            Node message = materializeIfReference(
                    property(projectedEvent, "message"),
                    context);
            return matchesOperationRequestType(
                    message, context)
                    ? OperationRequestView.from(
                    message, context)
                    : null;
        }
        if (matchesOperationRequestType(
                projectedEvent, context)) {
            return OperationRequestView.from(
                    projectedEvent, context);
        }
        if (!isTimelineEntry(
                projectedEvent, context)) {
            return null;
        }
        Node message = materializeIfReference(
                property(projectedEvent, "message"),
                context);
        return matchesOperationRequestType(
                message, context)
                ? OperationRequestView.from(
                message, context)
                : null;
    }

    static Node operationRequestRoutingPayload(
            Node event,
            ExternalChannelFunctionContext context) {
        Node projectedEvent =
                materializeIfReference(event, context);
        if (projectedEvent == null) {
            return null;
        }
        if (declaresExactType(
                projectedEvent,
                TimelineEntry.blueId())) {
            return projectTimelineOperationRequestPayload(
                    projectedEvent, context);
        }
        if (matchesOperationRequestType(
                projectedEvent, context)) {
            return projectOperationRequestFields(
                    projectedEvent, context);
        }
        if (!isTimelineEntry(
                projectedEvent, context)) {
            return projectedEvent.clone();
        }
        return projectTimelineOperationRequestPayload(
                projectedEvent, context);
    }

    private static Node projectTimelineOperationRequestPayload(
            Node projectedEvent,
            ExternalChannelFunctionContext context) {
        Node suppliedMessage =
                property(projectedEvent, "message");
        Node projectedMessage =
                materializeIfReference(
                        suppliedMessage, context);
        if (!matchesOperationRequestType(
                projectedMessage, context)) {
            return projectedEvent.clone();
        }
        Node payload = projectedEvent.clone();
        payload.getProperties().put(
                "message",
                projectOperationRequestFields(
                        projectedMessage, context));
        return payload;
    }

    static boolean matchesOperationRequest(
            Node event,
            String operation,
            String channel,
            Node request,
            HandlerMatchContext context) {
        if (event == null
                || operation == null
                || channel == null
                || context == null) {
            return false;
        }
        Node requestPattern = new Node()
                .type(new Node().blueId(
                        OperationRequest.blueId()))
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value(channel));
        if (request != null) {
            Node presencePattern = requestPattern.clone()
                    .properties("request", new Node()
                            .schema(new Schema().required(true)));
            if (!matchesDirectOrTimelineOperationRequest(
                    presencePattern, context)) {
                return false;
            }
            requestPattern.properties("request", request.clone());
        }
        return matchesDirectOrTimelineOperationRequest(
                requestPattern, context);
    }

    static boolean isRoutableOperationRequestForChannel(
            Node event,
            String channel,
            HandlerMatchContext context) {
        if (event == null
                || channel == null
                || context == null) {
            return false;
        }
        OperationRequestView direct =
                operationRequest(event);
        if (direct != null && direct.routable()) {
            return channel.equals(
                    direct.channel());
        }
        if (direct != null
                && !hasReferencedRoutingFields(event)) {
            return false;
        }
        Node requestPattern = new Node()
                .type(new Node().blueId(
                        OperationRequest.blueId()))
                .properties(
                        "operation",
                        new Node().schema(
                                new Schema()
                                        .required(true)
                                        .minLength(1)))
                .properties(
                        "channel",
                        new Node().value(channel));
        return matchesDirectOrTimelineOperationRequest(
                requestPattern, context);
    }

    private static boolean hasReferencedRoutingFields(
            Node event) {
        Node request = event;
        if (isTimelineEntry(event)) {
            request = property(event, "message");
        }
        if (request == null) {
            return false;
        }
        if (request.isReferenceOnly()) {
            return true;
        }
        Node operation = property(
                request, "operation");
        Node channel = property(
                request, "channel");
        return operation != null
                && operation.isReferenceOnly()
                || channel != null
                && channel.isReferenceOnly();
    }

    private static boolean matchesDirectOrTimelineOperationRequest(
            Node requestPattern,
            HandlerMatchContext context) {
        if (context.matchesEventPattern(requestPattern)) {
            return true;
        }
        return context.matchesEventPattern(new Node()
                .type(new Node().blueId(
                        TimelineEntry.blueId()))
                .properties("message", requestPattern));
    }

    static boolean matchesPattern(Node node, Node pattern) {
        if (pattern == null) {
            return true;
        }
        if (node == null) {
            return false;
        }
        if (pattern.isReferenceOnly()) {
            return pattern.getBlueId().equals(node.getBlueId());
        }
        if (!typeMatches(node.getType(), pattern.getType())) {
            return false;
        }
        if (!valueMatches(node.getValue(), pattern.getValue())) {
            return false;
        }
        if (!itemsMatch(node.getItems(), pattern.getItems())) {
            return false;
        }
        return propertiesMatch(node.getProperties(), pattern.getProperties());
    }

    private static Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    private static boolean matchesOperationRequestType(Node node) {
        if (node == null || node.getType() == null) {
            return false;
        }
        Node exactType = node.getType();
        if (OperationRequest.blueId().equals(exactType.getBlueId())) {
            return true;
        }
        try {
            if (BlueSemanticIdentity.equals(
                    exactType,
                    new Node().blueId(
                            OperationRequest.blueId()))) {
                return true;
            }
            return LEGACY_TYPE_MATCHER.get().nodeMatchesType(
                    new Node().type(exactType.clone()),
                    OPERATION_REQUEST_TYPE);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean matchesOperationRequestType(
            Node node,
            ExternalChannelFunctionContext context) {
        if (node == null || node.getType() == null) {
            return false;
        }
        if (OperationRequest.blueId().equals(
                node.getType().getBlueId())) {
            return true;
        }
        return context.matchesPattern(
                node,
                new Node().type(
                        new Node().blueId(
                                OperationRequest.blueId())));
    }

    private static boolean declaresExactType(
            Node node,
            String typeBlueId) {
        return node != null
                && node.getType() != null
                && typeBlueId.equals(
                node.getType().getBlueId());
    }

    private static Node materializeIfReference(
            Node node,
            ExternalChannelFunctionContext context) {
        return node != null && node.isReferenceOnly()
                ? context.materializeExactReference(node)
                : node;
    }

    private static Node projectTimelineEntry(
            Node node,
            ExternalChannelFunctionContext context) {
        Node projected =
                materializeIfReference(node, context);
        if (projected == null
                || projected.getProperties() == null) {
            return projected;
        }
        String[] fragmentFields = new String[] {
                "timeline",
                "actor",
                "timestamp",
                "message"
        };
        Node mutable = projected;
        boolean cloned = false;
        for (String field : fragmentFields) {
            Node value = property(projected, field);
            if (value == null || !value.isReferenceOnly()) {
                continue;
            }
            if (!cloned) {
                mutable = projected.clone();
                cloned = true;
            }
            mutable.getProperties().put(
                    field,
                    context.materializeExactReference(value));
        }
        return mutable;
    }

    private static Node projectOperationRequestFields(
            Node request,
            ExternalChannelFunctionContext context) {
        Node projected = request.clone();
        String[] routingFields =
                new String[] {
                        "operation",
                        "channel"
                };
        for (String field : routingFields) {
            Node supplied = property(
                    request, field);
            if (supplied != null
                    && supplied.isReferenceOnly()) {
                projected.getProperties().put(
                        field,
                        context.materializeExactReference(
                                supplied));
            }
        }
        return projected;
    }

    private static String nonBlankTextProperty(Node node, String key) {
        Node property = property(node, key);
        Object value = property != null ? property.getValue() : null;
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static String nonBlankTextProperty(
            Node node,
            String key,
            ExternalChannelFunctionContext context) {
        Node exactProperty = materializeIfReference(
                property(node, key), context);
        Object value =
                exactProperty != null
                        ? exactProperty.getValue()
                        : null;
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static BigInteger integerProperty(Node node, String key) {
        Node property = property(node, key);
        Object value = property != null ? property.getValue() : null;
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return null;
    }

    private static boolean typeMatches(Node nodeType, Node patternType) {
        if (patternType == null) {
            return true;
        }
        if (nodeType == null) {
            return false;
        }
        try {
            return BlueSemanticIdentity.equals(
                    nodeType, patternType);
        } catch (RuntimeException invalidTypeEvidence) {
            return false;
        }
    }

    private static boolean valueMatches(Object actual, Object expected) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        if (actual instanceof Number && expected instanceof Number) {
            return number(actual).compareTo(number(expected)) == 0;
        }
        return expected.equals(actual);
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return new BigDecimal(value.toString());
    }

    private static boolean itemsMatch(List<Node> actual, List<Node> expected) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            Node expectedItem = expected.get(i);
            if (i < actual.size()) {
                if (!matchesPattern(actual.get(i), expectedItem)) {
                    return false;
                }
            } else if (requiresPresence(expectedItem)) {
                return false;
            }
        }
        return true;
    }

    private static boolean propertiesMatch(Map<String, Node> actual, Map<String, Node> expected) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (Map.Entry<String, Node> entry : expected.entrySet()) {
            Node actualProperty = actual.get(entry.getKey());
            Node expectedProperty = entry.getValue();
            if (actualProperty != null) {
                if (!matchesPattern(actualProperty, expectedProperty)) {
                    return false;
                }
            } else if (requiresPresence(expectedProperty)) {
                return false;
            }
        }
        return true;
    }

    private static boolean requiresPresence(Node pattern) {
        if (pattern == null) {
            return false;
        }
        if (pattern.getName() != null
                || pattern.getDescription() != null
                || pattern.getType() != null
                || pattern.getItemType() != null
                || pattern.getKeyType() != null
                || pattern.getValueType() != null
                || pattern.getValue() != null
                || pattern.getContracts() != null
                || pattern.getBlueId() != null
                || pattern.getSchema() != null
                || pattern.getMergePolicy() != null
                || pattern.getPreviousBlueId() != null
                || pattern.getPosition() != null
                || pattern.getBlue() != null
                || pattern.getItems() != null) {
            return true;
        }
        if (pattern.getProperties() != null) {
            for (Node property : pattern.getProperties().values()) {
                if (requiresPresence(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Node repositoryType(String qualifiedName) {
        return REPOSITORY.nodeByName(qualifiedName)
                .orElseThrow(() -> new IllegalStateException(
                        "Published repository is missing " + qualifiedName));
    }

    static final class TimelineEntryView {
        private final Node timeline;
        private final Node actor;
        private final BigInteger timestamp;

        private TimelineEntryView(Node timeline,
                                  Node actor,
                                  BigInteger timestamp) {
            this.timeline = timeline;
            this.actor = actor;
            this.timestamp = timestamp;
        }

        Node timeline() {
            return timeline;
        }

        Node actor() {
            return actor;
        }

        BigInteger timestamp() {
            return timestamp;
        }
    }

    static final class OperationRequestView {
        private final String operation;
        private final String channel;

        private OperationRequestView(String operation,
                                     String channel) {
            this.operation = operation;
            this.channel = channel;
        }

        private static OperationRequestView from(Node requestNode) {
            return new OperationRequestView(
                    nonBlankTextProperty(requestNode, "operation"),
                    nonBlankTextProperty(requestNode, "channel"));
        }

        private static OperationRequestView from(
                Node requestNode,
                ExternalChannelFunctionContext context) {
            return new OperationRequestView(
                    nonBlankTextProperty(
                            requestNode,
                            "operation",
                            context),
                    nonBlankTextProperty(
                            requestNode,
                            "channel",
                            context));
        }

        boolean routable() {
            return operation != null && channel != null;
        }

        String operation() {
            return operation;
        }

        String channel() {
            return channel;
        }

    }
}
