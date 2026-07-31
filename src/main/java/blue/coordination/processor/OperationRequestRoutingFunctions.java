package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelLookupResult;
import blue.language.processor.ChannelMemberSnapshot;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.GasChargeContext;
import blue.language.processor.model.ChannelContract;
import blue.language.utils.BlueIdCalculator;

/**
 * Shared immutable routing projection for Coordination Operation Requests.
 */
final class OperationRequestRoutingFunctions {
    private static final String LOGICAL_DELIVERY_PREFIX =
            "blue.coordination/1.0/operation-request:";

    private OperationRequestRoutingFunctions() {
    }

    static void declareTargetChannelCatalog(
            ExternalChannelFunctionContext context) {
        context.dependOnSameScopeChannelCatalog();
    }

    static String handlerChannelKey(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        Route route = route(
                exactPayload,
                context,
                true);
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
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        Route route = route(
                exactPayload,
                context,
                false);
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
            Node exactPayload,
            ExternalChannelFunctionContext context,
            boolean chargeTargetLookup) {
        CoordinationEventNodes.OperationRequestView request =
                CoordinationEventNodes
                        .operationRequestFromRoutingPayload(
                                exactPayload,
                                context);
        if (request == null
                || !request.routable()) {
            return null;
        }
        if (chargeTargetLookup) {
            /*
             * Language invokes handler routing before logical-delivery
             * routing. Both functions validate the same immutable declared
             * catalog result; the handler function owns the single semantic
             * target-lookup charge for that accepted source.
             */
            CoordinationRuntimeGas.charge(
                    context.runtimeWorkSession(),
                    "operationTargetLookup",
                    1L,
                    GasChargeContext.of(
                            context.scopePath(),
                            context.channelKey(),
                            null,
                            "lookup Operation Request target Channel"));
        }
        ChannelLookupResult lookup =
                context.lookupChannel(
                        request.channel());
        if (!lookup.isChannel()) {
            return null;
        }
        ChannelMemberSnapshot target =
                lookup.channel().get();
        return new Route(
                request.operation(),
                target.channelKey());
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
