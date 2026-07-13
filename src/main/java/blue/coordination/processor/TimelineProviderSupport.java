package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.TimelineChannel;

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
        return ChannelEvaluation.match(eventNode);
    }

    static boolean matchesTimelineAndActor(TimelineChannel contract,
                                           CoordinationEventNodes.TimelineEntryView entry) {
        return contract != null
                && entry != null
                && BlueSemanticIdentity.equals(contract.getTimeline(), entry.timeline())
                && BlueSemanticIdentity.equals(contract.getActor(), entry.actor());
    }

    public static boolean matchesEventFilter(TimelineChannel contract, Node eventNode) {
        Node definition = contract.getDefinition();
        return definition == null || CoordinationEventNodes.matchesPattern(eventNode, definition);
    }

    public static String eventId(Node eventNode) {
        return eventNode != null ? BlueIdCalculator.calculateBlueId(eventNode.clone().blue(null)) : null;
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
        return current.sequence().compareTo(previous.sequence()) > 0;
    }
}
