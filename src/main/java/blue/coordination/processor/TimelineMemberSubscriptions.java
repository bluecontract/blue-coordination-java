package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelMemberEvaluation;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TimelineMemberSubscriptions {
    private TimelineMemberSubscriptions() {
    }

    static List<ExternalChannelMemberSnapshot> compositeMembers(
            CompositeTimelineChannel contract,
            ExternalChannelFunctionContext context) {
        if (contract == null
                || contract.getChannels() == null
                || contract.getChannels().isEmpty()) {
            throw new IllegalArgumentException(
                    "Composite Timeline Channel requires at least one member");
        }
        Set<String> uniqueKeys = new LinkedHashSet<String>();
        List<ExternalChannelMemberSnapshot> members =
                new ArrayList<ExternalChannelMemberSnapshot>();
        for (String key : contract.getChannels()) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Composite Timeline Channel member key must be "
                                + "non-empty Text");
            }
            if (!uniqueKeys.add(key)) {
                continue;
            }
            ExternalChannelMemberSnapshot member =
                    context.member(key);
            if (!TimelineChannel.blueId().equals(
                    member.effectiveTypeBlueId())) {
                throw new IllegalArgumentException(
                        "Composite Timeline Channel member '" + key
                                + "' must have exact Timeline Channel "
                                + "runtime semantics");
            }
            members.add(member);
        }
        Collections.sort(members, MEMBER_ORDER);
        return Collections.unmodifiableList(members);
    }

    static List<ExternalChannelMemberSnapshot> allTimelineMembers(
            ExternalChannelFunctionContext context) {
        return context.membersByEffectiveType(
                TimelineChannel.blueId());
    }

    static List<String> unionChannelKeys(
            List<ExternalChannelMemberSnapshot> members) {
        Set<String> keys = new LinkedHashSet<String>();
        for (ExternalChannelMemberSnapshot member : members) {
            keys.addAll(member.channelKeys());
        }
        return Collections.unmodifiableList(
                new ArrayList<String>(keys));
    }

    static WinningMember winning(
            List<ExternalChannelMemberSnapshot> members,
            Node exactEvent) {
        for (ExternalChannelMemberSnapshot member : members) {
            ExternalChannelMemberEvaluation evaluation =
                    member.evaluate(exactEvent);
            if (evaluation.accepts()) {
                return new WinningMember(member, evaluation);
            }
        }
        return null;
    }

    static List<String> timelineEventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return TimelineExternalSubscriptionFunctions.INSTANCE
                .eventKeys(exactEvent, context);
    }

    static final class WinningMember {
        private final ExternalChannelMemberSnapshot member;
        private final ExternalChannelMemberEvaluation evaluation;

        private WinningMember(
                ExternalChannelMemberSnapshot member,
                ExternalChannelMemberEvaluation evaluation) {
            this.member = member;
            this.evaluation = evaluation;
        }

        ExternalChannelMemberSnapshot member() {
            return member;
        }

        ExternalChannelMemberEvaluation evaluation() {
            return evaluation;
        }
    }

    private static final Comparator<ExternalChannelMemberSnapshot>
            MEMBER_ORDER =
            new Comparator<ExternalChannelMemberSnapshot>() {
                @Override
                public int compare(ExternalChannelMemberSnapshot left,
                                   ExternalChannelMemberSnapshot right) {
                    int order = Integer.compare(
                            left.order(), right.order());
                    if (order != 0) {
                        return order;
                    }
                    int key = ExternalOrderKey.compareTextCodePoints(
                            left.channelKey(), right.channelKey());
                    if (key != 0) {
                        return key;
                    }
                    return ExternalOrderKey.compareTextCodePoints(
                            left.effectiveTypeBlueId(),
                            right.effectiveTypeBlueId());
                }
            };
}
