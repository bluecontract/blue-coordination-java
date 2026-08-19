package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelLookupResult;
import blue.language.processor.ChannelMemberSnapshot;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.GasChargeContext;
import blue.language.processor.model.ChannelContract;
import blue.language.identity.DirectBlueIdCalculator;

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
        return handlerChannelKey(
                immutableContractSnapshot,
                exactEvent,
                exactPayload,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static String handlerChannelKey(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        Route route = route(
                exactPayload,
                context,
                true,
                identities);
        return route != null
                ? route.channel
                : context.channelKey();
    }

    static Node payload(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return payload(
                exactEvent,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static Node payload(
            Node exactEvent,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        return CoordinationEventNodes
                .operationRequestRoutingPayload(
                        exactEvent, context, identities);
    }

    static String logicalDeliveryKey(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return logicalDeliveryKey(
                immutableContractSnapshot,
                exactEvent,
                exactPayload,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static String logicalDeliveryKey(
            ChannelContract immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        Route route = route(
                exactPayload,
                context,
                false,
                identities);
        if (route == null) {
            return context.channelKey();
        }
        return logicalDeliveryKey(route.operation, route.channel);
    }

    static String logicalDeliveryKey(
            String operation,
            String channel) {
        Node identity = new Node()
                .properties(
                        "operation",
                        new Node().value(requireText(
                                operation, "operation")))
                .properties(
                        "channel",
                        new Node().value(requireText(
                                channel, "channel")));
        return LOGICAL_DELIVERY_PREFIX
                + DirectBlueIdCalculator.calculateBlueId(identity);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private static Route route(
            Node exactPayload,
            ExternalChannelFunctionContext context,
            boolean chargeTargetLookup,
            CoordinationSemanticTypeIdentities identities) {
        CoordinationEventNodes.OperationRequestView request =
                CoordinationEventNodes
                        .operationRequestFromRoutingPayload(
                                exactPayload,
                                context,
                                identities);
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
