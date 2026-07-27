package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.TimelineChannel;
import java.math.BigInteger;

public final class TimelineProviderSupport {
    private TimelineProviderSupport() {
    }

    public static ChannelEvaluation evaluateTimelineEntry(TimelineChannel contract, ChannelEvaluationContext context) {
        Node eventNode = context.event();
        CoordinationEventNodes.TimelineEntryView entry = CoordinationEventNodes.timelineEntry(eventNode);
        if (entry == null) {
            return ChannelEvaluation.noMatch();
        }
        if (!TimelineExternalSubscriptionFunctions.INSTANCE
                .accepts(contract, eventNode)) {
            return ChannelEvaluation.noMatch();
        }
        return ChannelEvaluation.match(eventNode, eventId(eventNode));
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

    static ChannelEvaluation preserveUnionPayload(ChannelEvaluation childEvaluation,
                                                  Node fallbackEvent) {
        Node deliveryEvent = childEvaluation.event() != null
                ? childEvaluation.event()
                : fallbackEvent;
        return deliveryEvent != null
                ? ChannelEvaluation.match(deliveryEvent, childEvaluation.eventId())
                : ChannelEvaluation.noMatch();
    }

    public static String eventId(Node eventNode) {
        return eventNode != null ? BlueIdCalculator.calculateBlueId(eventNode.clone().blue(null)) : null;
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

    static Node timelineOrderSubject(
            CoordinationEventNodes.TimelineEntryView entry) {
        if (entry == null) {
            throw new IllegalArgumentException(
                    "Timeline order subject requires a Timeline Entry");
        }
        return new Node()
                .properties("semantics",
                        new Node().value(
                                TimelineExternalSubscriptionFunctions
                                        .TIMELINE_ORDER_SUBJECT_VERSION))
                .properties("timestamp",
                        new Node().value(entry.timestamp()));
    }

    static Node memberTimelineOrderSubject(
            String semantics,
            ExternalChannelMemberSnapshot member,
            Node exactMemberSubject) {
        TimelineOrder memberOrder = timelineOrder(
                exactMemberSubject,
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION);
        if (memberOrder == null) {
            throw new IllegalArgumentException(
                    "Timeline member order subject requires the selected "
                            + "member's exact Timeline subject");
        }
        return new Node()
                .properties("semantics", new Node().value(semantics))
                .properties("timestamp",
                        new Node().value(memberOrder.timestamp))
                .properties("memberKey",
                        new Node().value(member.channelKey()))
                .properties("memberDomain",
                        new Node().value(
                                member.checkpointDomainBlueId()));
    }

    static boolean isNewerTimelineSubject(
            ChannelCheckpointContext context,
            String expectedSemantics) {
        TimelineOrder current = timelineOrder(
                context.currentSubject(), expectedSemantics);
        if (current == null) {
            throw new IllegalArgumentException(
                    "Current Timeline checkpoint has no exact order "
                            + "subject");
        }
        if (context.eventSignature() != null
                && context.eventSignature().equals(
                context.lastEventSignature())) {
            return false;
        }
        Node previousSubject = context.lastEvent();
        if (previousSubject == null) {
            return true;
        }
        TimelineOrder previous =
                timelineOrder(previousSubject, expectedSemantics);
        if (previous == null) {
            throw new IllegalArgumentException(
                    "Stored Timeline checkpoint subject is malformed");
        }
        if (current.memberKey == null) {
            return current.timestamp.compareTo(
                    previous.timestamp) > 0;
        }
        boolean sameMember =
                current.memberKey.equals(previous.memberKey)
                        && current.memberDomain.equals(
                        previous.memberDomain);
        if (!sameMember) {
            /*
             * Composite and All Timelines preserve the established Timeline
             * policy: each selected semantic member is an independent source.
             * The generic feeder owns cross-source canonical ordering; this
             * checkpoint only rejects replays/non-increasing timestamps from
             * the same frozen member lineage.
             */
            return true;
        }
        return current.timestamp.compareTo(
                previous.timestamp) > 0;
    }

    private static TimelineOrder timelineOrder(Node node,
                                               String expectedSemantics) {
        String semantics = textProperty(node, "semantics");
        if (!expectedSemantics.equals(semantics)) {
            return null;
        }
        Node timestampNode = property(node, "timestamp");
        Object rawTimestamp =
                timestampNode != null
                        ? timestampNode.getValue()
                        : null;
        BigInteger timestamp = integer(rawTimestamp);
        String memberKey = textProperty(node, "memberKey");
        String memberDomain = textProperty(node, "memberDomain");
        boolean direct = TimelineExternalSubscriptionFunctions
                .TIMELINE_ORDER_SUBJECT_VERSION.equals(
                        expectedSemantics);
        if (timestamp == null
                || direct && (memberKey != null || memberDomain != null)
                || !direct && (memberKey == null
                || memberKey.isEmpty()
                || memberDomain == null
                || memberDomain.isEmpty())) {
            return null;
        }
        return new TimelineOrder(
                timestamp, memberKey, memberDomain);
    }

    private static BigInteger integer(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(
                    ((Number) value).longValue());
        }
        return null;
    }

    private static final class TimelineOrder {
        private final BigInteger timestamp;
        private final String memberKey;
        private final String memberDomain;

        private TimelineOrder(BigInteger timestamp,
                              String memberKey,
                              String memberDomain) {
            this.timestamp = timestamp;
            this.memberKey = memberKey;
            this.memberDomain = memberDomain;
        }
    }

}
