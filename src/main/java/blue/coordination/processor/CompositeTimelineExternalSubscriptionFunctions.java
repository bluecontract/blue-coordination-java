package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.GasChargeContext;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;

import java.util.List;
import java.util.Objects;

/**
 * Immutable subscription behavior for an explicitly declared union of
 * Timeline Channel members.
 *
 * <p>The member list is resolved through Language's effective-channel
 * catalog, so base Timeline Channels and verified subtypes share the same
 * matching, checkpoint, and logical-delivery rules.</p>
 */
final class CompositeTimelineExternalSubscriptionFunctions
        implements ExternalChannelSubscriptionFunctions<
        CompositeTimelineChannel> {

    static final CompositeTimelineExternalSubscriptionFunctions INSTANCE =
            new CompositeTimelineExternalSubscriptionFunctions(
                    CoordinationCurrentRepositoryIdentities.current()
                            .timelineChannelBlueId(),
                    CoordinationSemanticTypeIdentities.publishedDefaults());
    static final String ORDER_SUBJECT_VERSION =
            "blue.coordination/1.0/composite-timeline-order-subject-v3";

    private final String timelineChannelTypeBlueId;
    private final CoordinationSemanticTypeIdentities identities;
    private final TimelineExternalSubscriptionFunctions<TimelineChannel>
            timelineFunctions;

    CompositeTimelineExternalSubscriptionFunctions(
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
            CompositeTimelineChannel immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        return CoordinationRuntimeGas.inComponent(
                context.runtimeWorkSession(),
                () -> {
                    List<ExternalChannelMemberSnapshot> members =
                            members(
                                    immutableContractSnapshot,
                                    context);
                    chargeMemberVisits(
                            context,
                            members.size(),
                            "project Composite Timeline member keys");
                    return TimelineMemberSubscriptions
                            .unionChannelKeys(members);
                });
    }

    @Override
    public List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return timelineFunctions.eventKeys(exactEvent, context);
    }

    @Override
    public boolean accepts(
            CompositeTimelineChannel immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return winning(immutableContractSnapshot,
                exactEvent, context) != null;
    }

    @Override
    public Node payload(
            CompositeTimelineChannel immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .payload(exactEvent, context, identities);
    }

    @Override
    public Node checkpointSubject(
            CompositeTimelineChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        TimelineMemberSubscriptions.WinningMember winner =
                requireWinner(immutableContractSnapshot,
                        exactEvent, context);
        return TimelineProviderSupport.memberTimelineOrderSubject(
                ORDER_SUBJECT_VERSION,
                winner.member(),
                winner.evaluation().checkpointSubject());
    }

    @Override
    public String handlerChannelKey(
            CompositeTimelineChannel immutableContractSnapshot,
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
            CompositeTimelineChannel immutableContractSnapshot,
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
            CompositeTimelineChannel immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        OperationRequestRoutingFunctions
                .declareTargetChannelCatalog(
                        context);
        return "coordination.composite-timeline:"
                + "direct-timeline-members-v2"
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
            CompositeTimelineChannel contract,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return CoordinationRuntimeGas.inComponent(
                context.runtimeWorkSession(),
                () -> {
                    List<ExternalChannelMemberSnapshot> members =
                            members(contract, context);
                    return TimelineMemberSubscriptions.winning(
                            members,
                            exactEvent,
                            () -> chargeMemberVisits(
                                    context,
                                    1,
                                    "evaluate Composite Timeline member"));
                });
    }

    private TimelineMemberSubscriptions.WinningMember requireWinner(
            CompositeTimelineChannel contract,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        TimelineMemberSubscriptions.WinningMember winner =
                winning(contract, exactEvent, context);
        if (winner == null) {
            throw new IllegalStateException(
                    "Composite Timeline payload requires an accepting "
                            + "member");
        }
        return winner;
    }

    private List<ExternalChannelMemberSnapshot> members(
            CompositeTimelineChannel contract,
            ExternalChannelFunctionContext context) {
        return TimelineMemberSubscriptions.shallowCompositeMembers(
                contract, context, timelineChannelTypeBlueId);
    }

    private static void chargeMemberVisits(
            ExternalChannelFunctionContext context,
            int quantity,
            String reason) {
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "compositeMemberVisited",
                quantity,
                GasChargeContext.of(
                        context.scopePath(),
                        context.channelKey(),
                        null,
                        reason));
    }
}
