package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.GasChargeContext;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.TimelineChannel;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable subscription behavior for the union of every same-scope Timeline
 * Channel subtype.
 *
 * <p>Membership comes from Language's verified effective-channel catalog.
 * The first accepting member owns the derived order subject, while the
 * aggregate still represents one logical source delivery.</p>
 */
final class AllTimelinesExternalSubscriptionFunctions
        implements ExternalChannelSubscriptionFunctions<
        AllTimelinesChannel> {

    static final AllTimelinesExternalSubscriptionFunctions INSTANCE =
            new AllTimelinesExternalSubscriptionFunctions(
                    CoordinationCurrentRepositoryIdentities.current()
                            .timelineChannelBlueId(),
                    CoordinationSemanticTypeIdentities.publishedDefaults());
    static final String ALL_TIMELINES_KEY =
            "blue.coordination/1.0/all-timelines";
    static final String ORDER_SUBJECT_VERSION =
            "blue.coordination/1.0/all-timelines-order-subject-v3";

    private final String timelineChannelTypeBlueId;
    private final CoordinationSemanticTypeIdentities identities;
    private final TimelineExternalSubscriptionFunctions<TimelineChannel>
            timelineFunctions;

    AllTimelinesExternalSubscriptionFunctions(
            String timelineChannelTypeBlueId,
            CoordinationSemanticTypeIdentities identities) {
        this.timelineChannelTypeBlueId = Objects.requireNonNull(
                timelineChannelTypeBlueId,
                "timelineChannelTypeBlueId");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.timelineFunctions =
                TimelineExternalSubscriptionFunctions.with(
                        this.identities);
    }

    @Override
    public List<String> channelKeys(
            AllTimelinesChannel immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        return CoordinationRuntimeGas.inComponent(
                context.runtimeWorkSession(),
                () -> {
                    /*
                     * Enumerating the exact Timeline type family records the
                     * membership dependency, including an empty family. Event
                     * evaluation can select any member, so promote every
                     * Timeline member header now as well.
                     */
                    List<ExternalChannelMemberSnapshot> members =
                            members(context);
                    chargeMemberVisits(
                            context,
                            members.size(),
                            "project All Timelines member headers");
                    for (ExternalChannelMemberSnapshot member
                            : members) {
                        member.checkpointDomainBlueId();
                    }
                    return Collections.singletonList(
                            ALL_TIMELINES_KEY);
                });
    }

    @Override
    public List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return !timelineFunctions.eventKeys(
                exactEvent, context).isEmpty()
                ? Collections.singletonList(ALL_TIMELINES_KEY)
                : Collections.<String>emptyList();
    }

    @Override
    public boolean accepts(
            AllTimelinesChannel immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return winning(exactEvent, context) != null;
    }

    @Override
    public Node payload(
            AllTimelinesChannel immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .payload(exactEvent, context, identities);
    }

    @Override
    public Node checkpointSubject(
            AllTimelinesChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        TimelineMemberSubscriptions.WinningMember winner =
                requireWinner(exactEvent, context);
        return TimelineProviderSupport.memberTimelineOrderSubject(
                ORDER_SUBJECT_VERSION,
                winner.member(),
                winner.evaluation().checkpointSubject());
    }

    @Override
    public String handlerChannelKey(
            AllTimelinesChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .handlerChannelKey(
                        immutableContractSnapshot,
                        exactEvent,
                        exactPayload,
                        context,
                        identities);
    }

    @Override
    public String logicalDeliveryKey(
            AllTimelinesChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .logicalDeliveryKey(
                        immutableContractSnapshot,
                        exactEvent,
                        exactPayload,
                        context,
                        identities);
    }

    @Override
    public String checkpointDomainDiscriminator(
            AllTimelinesChannel immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        OperationRequestRoutingFunctions
                .declareTargetChannelCatalog(
                        context);
        return "coordination.all-timelines:"
                + "timeline-type-family-v2"
                + semanticProfileSuffix()
                + "|subject="
                + ORDER_SUBJECT_VERSION;
    }

    private String semanticProfileSuffix() {
        return identities.custom()
                ? "|semantic-profile=" + identities.profileIdentity()
                : "";
    }

    private TimelineMemberSubscriptions.WinningMember winning(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return CoordinationRuntimeGas.inComponent(
                context.runtimeWorkSession(),
                () -> {
                    List<ExternalChannelMemberSnapshot> members =
                            members(context);
                    return TimelineMemberSubscriptions.winning(
                            members,
                            exactEvent,
                            () -> chargeMemberVisits(
                                    context,
                                    1,
                                    "evaluate All Timelines member"));
                });
    }

    private TimelineMemberSubscriptions.WinningMember requireWinner(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        TimelineMemberSubscriptions.WinningMember winner =
                winning(exactEvent, context);
        if (winner == null) {
            throw new IllegalStateException(
                    "All Timelines payload requires an accepting member");
        }
        return winner;
    }

    private List<ExternalChannelMemberSnapshot> members(
            ExternalChannelFunctionContext context) {
        return TimelineMemberSubscriptions.shallowAllTimelineMembers(
                context, timelineChannelTypeBlueId);
    }

    private static void chargeMemberVisits(
            ExternalChannelFunctionContext context,
            int quantity,
            String reason) {
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "allTimelinesMemberVisited",
                quantity,
                GasChargeContext.of(
                        context.scopePath(),
                        context.channelKey(),
                        null,
                        reason));
    }
}
