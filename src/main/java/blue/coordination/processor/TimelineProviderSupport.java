package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelDelivery;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.TimelineChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TimelineProviderSupport {
    private TimelineProviderSupport() {
    }

    public static ChannelEvaluation evaluateTimelineEntry(TimelineChannel contract, ChannelEvaluationContext context) {
        Node eventNode = context.event();
        CoordinationEventNodes.TimelineEntryView entry = CoordinationEventNodes.timelineEntry(eventNode);
        if (entry == null) {
            return ChannelEvaluation.noMatch();
        }
        if (!matchesTimelineAndActor(contract, entry) || !matchesEventFilter(contract, eventNode)) {
            return ChannelEvaluation.noMatch();
        }
        return acceptedTimelineEntry(eventNode, context);
    }

    private static ChannelEvaluation acceptedTimelineEntry(Node eventNode,
                                                            ChannelEvaluationContext context) {
        CoordinationEventNodes.OperationRequestView request =
                CoordinationEventNodes.operationRequest(eventNode);
        if (request == null || !request.routable() || context.channel(request.channel()) == null) {
            return ChannelEvaluation.match(eventNode);
        }
        ChannelDelivery delivery = ChannelDelivery.of(eventNode,
                null,
                null,
                null,
                request.channel(),
                OperationRequest.blueId() + ":" + request.operation());
        return ChannelEvaluation.matchDeliveries(Collections.singletonList(delivery));
    }

    static boolean matchesTimelineAndActor(TimelineChannel contract,
                                           CoordinationEventNodes.TimelineEntryView entry) {
        return contract != null
                && entry != null
                && CoordinationEventNodes.matchesGeneratedBinding(
                        entry.timeline(), contract.getTimeline())
                && CoordinationEventNodes.matchesGeneratedBinding(
                        entry.actor(), contract.getActor());
    }

    public static boolean matchesEventFilter(TimelineChannel contract, Node eventNode) {
        Node definition = contract.getDefinition();
        return definition == null || CoordinationEventNodes.matchesPattern(eventNode, definition);
    }

    static ChannelEvaluation preserveUnionDelivery(ChannelEvaluation childEvaluation,
                                                    Node fallbackEvent,
                                                    String metadataKey,
                                                    String sourceChannelKey) {
        List<ChannelDelivery> childDeliveries = childEvaluation.deliveries();
        if (!childDeliveries.isEmpty()) {
            List<ChannelDelivery> unionDeliveries =
                    new ArrayList<ChannelDelivery>(childDeliveries.size());
            for (ChannelDelivery childDelivery : childDeliveries) {
                unionDeliveries.add(ChannelDelivery.of(
                        withSourceMetadata(childDelivery.event(), metadataKey, sourceChannelKey),
                        childDelivery.eventId(),
                        null,
                        childDelivery.shouldProcess(),
                        childDelivery.handlerChannelKey(),
                        childDelivery.logicalDeliveryKey()));
            }
            return ChannelEvaluation.matchDeliveries(unionDeliveries);
        }
        Node deliveryEvent = childEvaluation.event() != null
                ? childEvaluation.event()
                : fallbackEvent;
        return deliveryEvent != null
                ? ChannelEvaluation.match(
                        withSourceMetadata(deliveryEvent, metadataKey, sourceChannelKey),
                        childEvaluation.eventId())
                : ChannelEvaluation.noMatch();
    }

    public static String eventId(Node eventNode) {
        return eventNode != null ? BlueIdCalculator.calculateBlueId(eventNode.clone().blue(null)) : null;
    }

    private static Node withSourceMetadata(Node event,
                                           String metadataKey,
                                           String sourceChannelKey) {
        Node copy = event.clone();
        Node meta = property(copy, "meta");
        if (meta == null) {
            meta = new Node();
            copy.properties("meta", meta);
        }
        meta.properties(metadataKey, new Node().value(sourceChannelKey));
        return copy;
    }

    public static boolean isNewerOrSameTimelineEvent(ChannelCheckpointContext context) {
        return isNewerTimelineEvent(context, false);
    }

    public static boolean isNewerOrDifferentTimelineEvent(ChannelCheckpointContext context) {
        return isNewerTimelineEvent(context, true);
    }

    public static Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    public static String textProperty(Node node, String key) {
        Node property = property(node, key);
        Object value = property != null ? property.getValue() : null;
        return value instanceof String ? (String) value : null;
    }

    private static boolean isNewerTimelineEvent(ChannelCheckpointContext context,
                                                boolean acceptDifferentTimeline) {
        CoordinationEventNodes.TimelineEntryView current =
                CoordinationEventNodes.timelineEntry(context.event());
        if (current == null) {
            return false;
        }
        Node previousEvent = context.lastEvent();
        if (previousEvent == null) {
            return true;
        }
        if (context.eventSignature() != null
                && context.eventSignature().equals(context.lastEventSignature())) {
            return false;
        }
        CoordinationEventNodes.TimelineEntryView previous =
                CoordinationEventNodes.timelineEntry(previousEvent);
        if (previous == null) {
            return false;
        }
        if (!BlueSemanticIdentity.equals(current.timeline(), previous.timeline())) {
            return acceptDifferentTimeline;
        }
        return current.timestamp().compareTo(previous.timestamp()) > 0;
    }
}
