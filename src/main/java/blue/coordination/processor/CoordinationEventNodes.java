package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.coordination.Actor;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.StatusCompleted;
import blue.repo.coordination.Timeline;
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
            return REPOSITORY.configure(new Blue());
        }
    };
    private static final Node TIMELINE_TYPE = repositoryType(Timeline.qualifiedName());
    private static final Node ACTOR_TYPE = repositoryType(Actor.qualifiedName());
    private static final Node OPERATION_REQUEST_TYPE = new Node()
            .type(new Node().blueId(OperationRequest.blueId()));

    private CoordinationEventNodes() {
    }

    static TimelineEntryView timelineEntry(Node node) {
        if (!isTimelineEntry(node)) {
            return null;
        }
        Node timeline = property(node, "timeline");
        Node actor = property(node, "actor");
        BigInteger sequence = integerProperty(node, "sequence");
        BigInteger timestamp = timestamp(node);
        Node message = property(node, "message");
        if (!BlueSemanticIdentity.matchesType(timeline, TIMELINE_TYPE)
                || !BlueSemanticIdentity.matchesType(actor, ACTOR_TYPE)
                || sequence == null
                || timestamp == null
                || message == null) {
            return null;
        }
        return new TimelineEntryView(timeline, actor, sequence);
    }

    static boolean isTimelineEntry(Node node) {
        return node != null
                && node.getType() != null
                && TimelineEntry.blueId().equals(node.getType().getBlueId());
    }

    static BigInteger timestamp(Node node) {
        return integerProperty(node, "timestamp");
    }

    static boolean matchesGeneratedBinding(Node candidate, Object configuredBinding) {
        return configuredBinding != null
                && matchesPattern(candidate, BINDING_CONVERTER.get().objectToNode(configuredBinding));
    }

    static OperationRequestView operationRequest(Node event) {
        if (matchesOperationRequestType(event)) {
            return OperationRequestView.from(event, false);
        }
        if (!isTimelineEntry(event)) {
            return null;
        }
        Node message = property(event, "message");
        return matchesOperationRequestType(message)
                ? OperationRequestView.from(message, true)
                : null;
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
        String typeBlueId = node.getType().getBlueId();
        if (OperationRequest.blueId().equals(typeBlueId)) {
            return true;
        }
        if (typeBlueId == null) {
            return false;
        }
        try {
            Node resolvedType = node.getType().isReferenceOnly()
                    ? REPOSITORY.nodeByBlueId(typeBlueId).orElse(null)
                    : node.getType();
            return resolvedType != null
                    && BINDING_CONVERTER.get().nodeMatchesType(
                            new Node().type(resolvedType.clone().blueId(null)),
                            OPERATION_REQUEST_TYPE);
        } catch (RuntimeException ignored) {
            return false;
        }
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
        String expected = typeIdentity(patternType);
        if (expected == null) {
            return true;
        }
        if (nodeType == null) {
            return true;
        }
        String actual = typeIdentity(nodeType);
        return expected.equals(actual);
    }

    private static String typeIdentity(Node type) {
        if (type == null) {
            return null;
        }
        if (type.getBlueId() != null) {
            return type.getBlueId();
        }
        Object value = type.getValue();
        if (value instanceof String) {
            String knownBlueId = knownCoordinationTypeBlueId((String) value);
            return knownBlueId != null ? knownBlueId : (String) value;
        }
        return null;
    }

    private static String knownCoordinationTypeBlueId(String qualifiedName) {
        if (TimelineEntry.qualifiedName().equals(qualifiedName)) {
            return TimelineEntry.blueId();
        }
        if (ChatMessage.qualifiedName().equals(qualifiedName)) {
            return ChatMessage.blueId();
        }
        if (OperationRequest.qualifiedName().equals(qualifiedName)) {
            return OperationRequest.blueId();
        }
        if (StatusCompleted.qualifiedName().equals(qualifiedName)) {
            return StatusCompleted.blueId();
        }
        return null;
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
        if (pattern.isReferenceOnly() || pattern.getValue() != null) {
            return true;
        }
        if (pattern.getItems() != null) {
            for (Node item : pattern.getItems()) {
                if (requiresPresence(item)) {
                    return true;
                }
            }
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
        private final BigInteger sequence;

        private TimelineEntryView(Node timeline,
                                  Node actor,
                                  BigInteger sequence) {
            this.timeline = timeline;
            this.actor = actor;
            this.sequence = sequence;
        }

        Node timeline() {
            return timeline;
        }

        Node actor() {
            return actor;
        }

        BigInteger sequence() {
            return sequence;
        }
    }

    static final class OperationRequestView {
        private final Node requestNode;
        private final boolean timelineMessage;
        private final String operation;
        private final String channel;

        private OperationRequestView(Node requestNode,
                                     boolean timelineMessage,
                                     String operation,
                                     String channel) {
            this.requestNode = requestNode;
            this.timelineMessage = timelineMessage;
            this.operation = operation;
            this.channel = channel;
        }

        private static OperationRequestView from(Node requestNode, boolean timelineMessage) {
            return new OperationRequestView(requestNode,
                    timelineMessage,
                    nonBlankTextProperty(requestNode, "operation"),
                    nonBlankTextProperty(requestNode, "channel"));
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

        Node request() {
            return property(requestNode, "request");
        }

        Node patternFor(Node requestPattern) {
            Node request = requestPattern.clone();
            if (!timelineMessage) {
                return new Node().properties("request", request);
            }
            return new Node().properties("message", new Node()
                    .properties("request", request));
        }
    }
}
