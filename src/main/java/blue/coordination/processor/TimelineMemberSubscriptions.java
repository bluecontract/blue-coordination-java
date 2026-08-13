package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelMemberEvaluation;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.repo.coordination.CompositeTimelineChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared, bounded member-catalog operations for Composite and All Timelines
 * subscription functions.
 *
 * <p>This helper works only with immutable Language snapshots. It never opens
 * executable handler bodies and preserves the catalog's canonical member
 * order when selecting one logical winner.</p>
 */
final class TimelineMemberSubscriptions {
    private TimelineMemberSubscriptions() {
    }

    static List<ExternalChannelMemberSnapshot> shallowCompositeMembers(
            CompositeTimelineChannel contract,
            ExternalChannelFunctionContext context,
            String timelineChannelTypeBlueId) {
        if (contract == null
                || contract.getChannels() == null
                || contract.getChannels().isEmpty()) {
            throw new IllegalArgumentException(
                    "Composite Timeline Channel requires at least one member");
        }
        Set<String> uniqueKeys = new LinkedHashSet<String>();
        for (String key : contract.getChannels()) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Composite Timeline Channel member key must be "
                                + "non-empty Text");
            }
            if (!uniqueKeys.add(key)) {
                continue;
            }
            if (uniqueKeys.size()
                    > CoordinationRuntimeLimits
                    .MAX_COMPOSITE_MEMBERS) {
                throw new IllegalArgumentException(
                        "Composite Timeline Channel exceeds "
                                + "maxCompositeMembers="
                                + CoordinationRuntimeLimits
                                .MAX_COMPOSITE_MEMBERS);
            }
        }

        /*
         * The assignable selector is deliberately shallow. Unlike
         * context.member(key), it does not resolve a selected member's
         * subscription header. Filtering these immutable identity headers
         * keeps the catalog work at Language's generic boundary; the caller's
         * compositeMemberVisited charge therefore precedes channelKeys(),
         * checkpointDomainBlueId(), or evaluate(), whichever first resolves
         * the selected peer.
         */
        Set<String> unresolvedKeys =
                new LinkedHashSet<String>(uniqueKeys);
        List<ExternalChannelMemberSnapshot> members =
                new ArrayList<ExternalChannelMemberSnapshot>();
        for (ExternalChannelMemberSnapshot candidate
                : context.membersAssignableToType(
                timelineChannelTypeBlueId)) {
            if (unresolvedKeys.remove(
                    candidate.channelKey())) {
                /*
                 * Language guarantees this selector's canonical member
                 * order, so filtering preserves the prior
                 * (order, key, effectiveTypeBlueId) winner order without
                 * touching a lazy peer header.
                 */
                members.add(candidate);
            }
        }
        if (!unresolvedKeys.isEmpty()) {
            String key = unresolvedKeys.iterator().next();
            throw new IllegalArgumentException(
                    "Composite Timeline Channel member '" + key
                            + "' must have Timeline Channel "
                            + "runtime semantics");
        }
        return Collections.unmodifiableList(members);
    }

    static List<ExternalChannelMemberSnapshot> shallowAllTimelineMembers(
            ExternalChannelFunctionContext context,
            String timelineChannelTypeBlueId) {
        /*
         * This generic subtype-family query is Language-owned catalog work
         * and returns identity-only snapshots. No selected Timeline peer is
         * resolved until a caller accesses a derived header or evaluate().
         */
        List<ExternalChannelMemberSnapshot> members =
                context.membersAssignableToType(
                timelineChannelTypeBlueId);
        if (members.size()
                > CoordinationRuntimeLimits
                .MAX_ALL_TIMELINES_MEMBERS) {
            throw new IllegalArgumentException(
                    "All Timelines Channel exceeds "
                            + "maxAllTimelinesMembers="
                            + CoordinationRuntimeLimits
                            .MAX_ALL_TIMELINES_MEMBERS);
        }
        return members;
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
            Node exactEvent,
            Runnable beforeMemberVisit) {
        for (ExternalChannelMemberSnapshot member : members) {
            beforeMemberVisit.run();
            ExternalChannelMemberEvaluation evaluation =
                    member.evaluate(exactEvent);
            if (evaluation.accepts()) {
                return new WinningMember(member, evaluation);
            }
        }
        return null;
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

}
