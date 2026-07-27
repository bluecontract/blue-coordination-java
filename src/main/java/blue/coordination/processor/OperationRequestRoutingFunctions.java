package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelMemberSnapshot;
import blue.language.processor.model.ChannelContract;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared immutable routing projection for Coordination Operation Requests.
 */
final class OperationRequestRoutingFunctions {
    private static final String LOGICAL_DELIVERY_PREFIX =
            "blue.coordination/1.0/operation-request:";

    private OperationRequestRoutingFunctions() {
    }

    static void declareTargetChannelFamilies(
            ChannelContract immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        for (String typeBlueId : targetTypeFamilies(
                immutableContractSnapshot)) {
            context.membersByEffectiveType(typeBlueId);
        }
    }

    static String handlerChannelKey(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        Route route = route(
                immutableContractSnapshot,
                exactEvent,
                context);
        return route != null
                ? route.channel
                : context.channelKey();
    }

    static Node payload(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return CoordinationEventNodes
                .operationRequestRoutingPayload(
                        exactEvent, context);
    }

    static String logicalDeliveryKey(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        Route route = route(
                immutableContractSnapshot,
                exactEvent,
                context);
        if (route == null) {
            return context.channelKey();
        }
        Node identity = new Node()
                .properties(
                        "operation",
                        new Node().value(
                                route.operation))
                .properties(
                        "channel",
                        new Node().value(
                                route.channel));
        return LOGICAL_DELIVERY_PREFIX
                + BlueIdCalculator.calculateBlueId(identity);
    }

    private static Route route(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        CoordinationEventNodes.OperationRequestView request =
                CoordinationEventNodes.operationRequest(
                        exactEvent, context);
        if (request == null
                || !request.routable()
                || !isChannelTarget(
                immutableContractSnapshot,
                request.channel(),
                context)) {
            return null;
        }
        return new Route(
                request.operation(),
                request.channel());
    }

    private static boolean isChannelTarget(
            ChannelContract immutableContractSnapshot,
            String targetKey,
            ExternalChannelFunctionContext context) {
        if (context.channelKey().equals(targetKey)) {
            return true;
        }
        for (String typeBlueId : targetTypeFamilies(
                immutableContractSnapshot)) {
            List<ExternalChannelMemberSnapshot> members =
                    context.membersByEffectiveType(
                            typeBlueId);
            for (ExternalChannelMemberSnapshot member : members) {
                if (member.channelKey().equals(targetKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> targetTypeFamilies(
            ChannelContract immutableContractSnapshot) {
        Set<String> typeBlueIds =
                new LinkedHashSet<String>();
        if (immutableContractSnapshot != null
                && immutableContractSnapshot.getTypeBlueId() != null
                && !immutableContractSnapshot
                .getTypeBlueId().isEmpty()) {
            typeBlueIds.add(
                    immutableContractSnapshot
                            .getTypeBlueId());
        }
        typeBlueIds.add(TimelineChannel.blueId());
        typeBlueIds.add(CompositeTimelineChannel.blueId());
        typeBlueIds.add(AllTimelinesChannel.blueId());
        return typeBlueIds;
    }

    private static final class Route {
        private final String operation;
        private final String channel;

        private Route(
                String operation,
                String channel) {
            this.operation = operation;
            this.channel = channel;
        }
    }
}
