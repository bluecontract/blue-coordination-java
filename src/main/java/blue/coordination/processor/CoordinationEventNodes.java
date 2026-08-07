package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.GasChargeContext;
import blue.language.processor.HandlerMatchContext;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.mapping.BlueMapper;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.TimelineEntry;
import blue.repo.coordination.Actor;
import blue.repo.coordination.Timeline;
import java.math.BigInteger;

/**
 * Read-only adapters for the Coordination event headers used by channel
 * routing and handler matching.
 *
 * <p>The context-aware methods are the production path when a header may
 * contain references: they materialize only the fields needed for the routing
 * decision and delegate type/pattern evidence to the owning Contracts
 * context. The context-free methods are intentionally limited to already
 * available nodes and never create processing state or checkpoints.</p>
 */
final class CoordinationEventNodes {
    private static final String TIMELINE_FIELD = "timeline";
    private static final String PREVIOUS_ENTRY_FIELD = "prevEntry";
    private static final String TIMESTAMP_FIELD = "timestamp";
    private static final String ACTOR_FIELD = "actor";
    private static final String SOURCE_FIELD = "source";
    private static final String ON_BEHALF_OF_FIELD = "onBehalfOf";
    private static final String MESSAGE_FIELD = "message";
    private static final String OPERATION_FIELD = "operation";
    private static final String CHANNEL_FIELD = "channel";
    private static final String REQUEST_FIELD = "request";

    private static final BlueMapper REPOSITORY_MAPPER =
            BlueMapper.builder()
                    .scanPackage("blue.repo")
                    .build();
    private static final ContractMatchingService INLINE_MATCHER =
            new ContractMatchingService();

    private CoordinationEventNodes() {
    }

    static TimelineEntryView timelineEntry(Node node) {
        return timelineEntry(
                node,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static TimelineEntryView timelineEntry(
            Node node,
            CoordinationSemanticTypeIdentities identities) {
        if (!isTimelineEntry(node, identities)) {
            return null;
        }
        return timelineEntryHeader(node);
    }

    static TimelineEntryView timelineEntry(
            Node node,
            ExternalChannelFunctionContext context) {
        return timelineEntry(
                node,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static TimelineEntryView timelineEntry(
            Node node,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        Node projected = projectTimelineEntry(
                node, context);
        if (!isTimelineEntry(projected, context, identities)) {
            return null;
        }
        return timelineEntryHeader(node, projected);
    }

    static TimelineEntryView timelineEntryHeader(
            Node node,
            ExternalChannelFunctionContext context) {
        return timelineEntryHeader(
                node,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static TimelineEntryView timelineEntryHeader(
            Node node,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        Node projected = projectTimelineEntry(node, context);
        if (!isTimelineEntry(projected, context, identities)) {
            return null;
        }
        return timelineEntryHeader(
                node,
                projected);
    }

    static TimelineEntryView timelineEntryHeader(Node node) {
        return timelineEntryHeader(node, node);
    }

    private static TimelineEntryView timelineEntryHeader(
            Node exactEntry,
            Node projectedHeader) {
        Node timeline = property(projectedHeader, TIMELINE_FIELD);
        Node prevEntry = property(projectedHeader, PREVIOUS_ENTRY_FIELD);
        Node timestampNode = property(projectedHeader, TIMESTAMP_FIELD);
        Node actor = property(projectedHeader, ACTOR_FIELD);
        Node source = property(projectedHeader, SOURCE_FIELD);
        Node onBehalfOf = property(projectedHeader, ON_BEHALF_OF_FIELD);
        Node message = property(projectedHeader, MESSAGE_FIELD);
        BigInteger timestamp = timestamp(projectedHeader);
        if (timeline == null
                || actor == null
                || timestamp == null
                || message == null) {
            return null;
        }
        return new TimelineEntryView(
                exactEntry,
                timeline,
                prevEntry,
                timestampNode,
                timestamp,
                actor,
                source,
                onBehalfOf,
                message);
    }

    static boolean isTimelineEntry(Node node) {
        return isTimelineEntry(
                node,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean isTimelineEntry(
            Node node,
            CoordinationSemanticTypeIdentities identities) {
        if (node == null || node.getType() == null) {
            return false;
        }
        Node type = node.getType();
        String timelineEntryBlueId = identities.timelineEntryBlueId();
        if (timelineEntryBlueId.equals(type.getBlueId())) {
            return true;
        }
        try {
            if (BlueSemanticIdentity.equals(
                    type,
                    new Node().blueId(timelineEntryBlueId))) {
                return true;
            }
            return false;
        } catch (RuntimeException invalidTypeEvidence) {
            return false;
        }
    }

    static boolean isTimelineEntry(
            Node node,
            ExternalChannelFunctionContext context) {
        return isTimelineEntry(
                node,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean isTimelineEntry(
            Node node,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        if (node == null || node.getType() == null) {
            return false;
        }
        String timelineEntryBlueId = identities.timelineEntryBlueId();
        if (timelineEntryBlueId.equals(
                node.getType().getBlueId())) {
            return true;
        }
        return context.matchesPattern(
                node,
                new Node().type(
                        new Node().blueId(
                                timelineEntryBlueId)));
    }

    static BigInteger timestamp(Node node) {
        return integerProperty(node, TIMESTAMP_FIELD);
    }

    static boolean matchesGeneratedBinding(Node candidate, Object configuredBinding) {
        return matchesGeneratedBinding(
                candidate,
                configuredBinding,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean matchesGeneratedBinding(
            Node candidate,
            Object configuredBinding,
            CoordinationSemanticTypeIdentities identities) {
        if (configuredBinding == null || candidate == null) {
            return false;
        }
        Node pattern = REPOSITORY_MAPPER.toNode(configuredBinding);
        if (configuredBinding instanceof Timeline) {
            pattern.type(new Node().blueId(
                    identities.timelineBlueId()));
        } else if (configuredBinding instanceof Actor) {
            pattern.type(new Node().blueId(
                    identities.actorBlueId()));
        }
        if (candidate.isReferenceOnly()) {
            return BlueSemanticIdentity.equals(candidate, pattern);
        }
        return INLINE_MATCHER.matches(candidate, pattern);
    }

    static Node generatedBindingNode(Object configuredBinding) {
        if (configuredBinding == null) {
            return null;
        }
        return REPOSITORY_MAPPER.toNode(configuredBinding);
    }

    static Node materializeHeaderValue(
            Node value,
            ExternalChannelFunctionContext context) {
        return materializeIfReference(value, context);
    }

    static boolean matchesGeneratedBinding(
            Node candidate,
            Object configuredBinding,
            ExternalChannelFunctionContext context) {
        return matchesGeneratedBinding(
                candidate,
                configuredBinding,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean matchesGeneratedBinding(
            Node candidate,
            Object configuredBinding,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        if (configuredBinding == null || candidate == null) {
            return false;
        }
        Node pattern;
        pattern = REPOSITORY_MAPPER.toNode(configuredBinding);
        if (configuredBinding instanceof Timeline) {
            pattern.type(new Node().blueId(
                    identities.timelineBlueId()));
        } else if (configuredBinding instanceof Actor) {
            pattern.type(new Node().blueId(
                    identities.actorBlueId()));
        }
        return context.matchesPattern(
                candidate,
                pattern);
    }

    static OperationRequestView operationRequest(Node event) {
        return operationRequest(
                event,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static OperationRequestView operationRequest(
            Node event,
            CoordinationSemanticTypeIdentities identities) {
        if (matchesOperationRequestType(event, identities)) {
            return OperationRequestView.from(event);
        }
        if (!isTimelineEntry(event, identities)) {
            return null;
        }
        Node message = property(event, MESSAGE_FIELD);
        return matchesOperationRequestType(message, identities)
                ? OperationRequestView.from(message)
                : null;
    }

    static OperationRequestView operationRequest(
            Node event,
            ExternalChannelFunctionContext context) {
        return operationRequest(
                event,
                context,
                true,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static OperationRequestView operationRequest(
            Node event,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        return operationRequest(
                event,
                context,
                true,
                identities);
    }

    /**
     * Reads the routing view from the exact payload produced by
     * {@link #operationRequestRoutingPayload(Node,
     * ExternalChannelFunctionContext)}.
     *
     * <p>The payload projection already owns and charged the two semantic
     * routing-field reads. Language invokes payload, handler routing, and
     * logical-delivery routing as separate functions, so reparsing that exact
     * projection must not charge the same reads again.</p>
     */
    static OperationRequestView operationRequestFromRoutingPayload(
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return operationRequest(
                exactPayload,
                context,
                false,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static OperationRequestView operationRequestFromRoutingPayload(
            Node exactPayload,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        return operationRequest(
                exactPayload,
                context,
                false,
                identities);
    }

    private static OperationRequestView operationRequest(
            Node event,
            ExternalChannelFunctionContext context,
            boolean chargeRoutingFields,
            CoordinationSemanticTypeIdentities identities) {
        if (event == null || context == null) {
            return null;
        }
        Node projectedEvent =
                materializeIfReference(event, context);
        if (declaresExactType(
                projectedEvent,
                identities.timelineEntryBlueId())) {
            Node message = materializeIfReference(
                    property(projectedEvent, MESSAGE_FIELD),
                    context);
            return matchesOperationRequestType(
                    message, context, identities)
                    ? operationRequestView(
                            message,
                            context,
                            chargeRoutingFields)
                    : null;
        }
        if (matchesOperationRequestType(
                projectedEvent, context, identities)) {
            return operationRequestView(
                    projectedEvent,
                    context,
                    chargeRoutingFields);
        }
        if (!isTimelineEntry(
                projectedEvent, context, identities)) {
            return null;
        }
        Node message = materializeIfReference(
                property(projectedEvent, MESSAGE_FIELD),
                context);
        return matchesOperationRequestType(
                message, context, identities)
                ? operationRequestView(
                message,
                context,
                chargeRoutingFields)
                : null;
    }

    private static OperationRequestView operationRequestView(
            Node request,
            ExternalChannelFunctionContext context,
            boolean chargeRoutingFields) {
        return OperationRequestView.from(
                request,
                context,
                chargeRoutingFields);
    }

    static Node operationRequestRoutingPayload(
            Node event,
            ExternalChannelFunctionContext context) {
        return operationRequestRoutingPayload(
                event,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static Node operationRequestRoutingPayload(
            Node event,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        String originalEventBlueId =
                exactIdentity(event);
        Node projectedEvent =
                materializeIfReference(event, context);
        if (projectedEvent == null) {
            return null;
        }
        Node payload;
        if (declaresExactType(
                projectedEvent,
                identities.timelineEntryBlueId())) {
            payload = projectTimelineOperationRequestPayload(
                    projectedEvent, context, identities);
        } else if (matchesOperationRequestType(
                projectedEvent, context, identities)) {
            payload = projectOperationRequestFields(
                    projectedEvent, context);
        } else if (!isTimelineEntry(
                projectedEvent, context, identities)) {
            payload = projectedEvent.clone();
        } else {
            payload = projectTimelineOperationRequestPayload(
                    projectedEvent, context, identities);
        }
        /*
         * A reference-backed event can arrive as exact expanded content with
         * provider provenance on any node. Hosted runtime output must be
         * canonical exact content (or a pure reference), never that resolved
         * hybrid representation.
         */
        Node exactPayload =
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(payload);
        if (hasSemanticOutputBoundary(context)
                && originalEventBlueId.equals(
                DirectBlueIdCalculator.calculateBlueId(
                        exactPayload))) {
            /*
             * ChannelRunner carries the exact PROCESS event into the hosted
             * output boundary under this identity. Returning that identity
             * preserves the original Timeline Entry without recursively
             * reopening opaque descendants merely because routing consulted
             * a direct fragment.
             */
            return new Node().blueId(
                    originalEventBlueId);
        }
        return exactPayload;
    }

    private static Node projectTimelineOperationRequestPayload(
            Node projectedEvent,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        Node suppliedMessage =
                property(projectedEvent, MESSAGE_FIELD);
        Node projectedMessage =
                materializeIfReference(
                        suppliedMessage, context);
        if (!matchesOperationRequestType(
                projectedMessage, context, identities)) {
            return projectedEvent.clone();
        }
        Node payload = projectedEvent.clone();
        payload.getProperties().put(
                MESSAGE_FIELD,
                projectOperationRequestFields(
                        projectedMessage, context));
        return payload;
    }

    private static String exactIdentity(Node node) {
        Node exact =
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(
                                java.util.Objects.requireNonNull(
                                        node, "node"));
        if (exact.isReferenceOnly()) {
            return exact.getBlueId();
        }
        return DirectBlueIdCalculator.calculateBlueId(
                exact);
    }

    static boolean matchesOperationRequest(
            Node event,
            String operation,
            String channel,
            Node request,
            HandlerMatchContext context) {
        return matchesOperationRequest(
                event,
                operation,
                channel,
                request,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean matchesOperationRequest(
            Node event,
            String operation,
            String channel,
            Node request,
            HandlerMatchContext context,
            CoordinationSemanticTypeIdentities identities) {
        if (event == null
                || operation == null
                || channel == null
                || context == null) {
            return false;
        }
        Node requestPattern = new Node()
                .type(new Node().blueId(
                        identities.operationRequestBlueId()))
                .properties(OPERATION_FIELD, new Node().value(operation))
                .properties(CHANNEL_FIELD, new Node().value(channel));
        if (request != null) {
            Node presencePattern = requestPattern.clone()
                    .properties(REQUEST_FIELD, new Node()
                            .schema(new Schema().required(true)));
            if (!matchesDirectOrTimelineOperationRequest(
                    presencePattern, context, identities)) {
                return false;
            }
            requestPattern.properties(REQUEST_FIELD, request.clone());
        }
        return matchesDirectOrTimelineOperationRequest(
                requestPattern, context, identities);
    }

    static boolean isRoutableOperationRequestForChannel(
            Node event,
            String channel,
            HandlerMatchContext context) {
        return isRoutableOperationRequestForChannel(
                event,
                channel,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static boolean isRoutableOperationRequestForChannel(
            Node event,
            String channel,
            HandlerMatchContext context,
            CoordinationSemanticTypeIdentities identities) {
        if (event == null
                || channel == null
                || context == null) {
            return false;
        }
        OperationRequestView direct =
                operationRequest(event, identities);
        if (direct != null && direct.routable()) {
            return channel.equals(
                    direct.channel());
        }
        OperationRequestView exactReferenced =
                handlerOperationRequest(
                        event,
                        context,
                        identities);
        if (exactReferenced != null) {
            return exactReferenced.routable()
                    && channel.equals(
                            exactReferenced.channel());
        }
        if (direct != null
                && !hasReferencedRoutingFields(event, identities)) {
            return false;
        }
        /*
         * An inline event with no declared type cannot be an Operation
         * Request or Timeline Entry.  Asking the semantic matcher to test an
         * Operation Request pattern here would turn an ordinary untyped
         * workflow event into a demand for the repository's Operation
         * Request definition before the workflow is even entered.  Pure
         * references and declared (possibly derived) types still use the
         * verified matcher path below.
         */
        if (!event.isReferenceOnly()
                && event.getType() == null) {
            return false;
        }
        Node requestPattern = new Node()
                .type(new Node().blueId(
                        identities.operationRequestBlueId()))
                .properties(
                        OPERATION_FIELD,
                        new Node().schema(
                                new Schema()
                                        .required(true)
                                        .minLength(1)))
                .properties(
                        CHANNEL_FIELD,
                        new Node().value(channel));
        return matchesDirectOrTimelineOperationRequest(
                requestPattern, context, identities);
    }

    private static OperationRequestView handlerOperationRequest(
            Node event,
            HandlerMatchContext context,
            CoordinationSemanticTypeIdentities identities) {
        Node projectedEvent =
                materializeIfReference(
                        event,
                        context);
        if (projectedEvent == null) {
            return null;
        }
        Node request = projectedEvent;
        if (declaresExactType(
                projectedEvent,
                identities.timelineEntryBlueId())) {
            request = materializeIfReference(
                    property(
                            projectedEvent,
                            MESSAGE_FIELD),
                    context);
        }
        if (!declaresExactType(
                request,
                identities.operationRequestBlueId())) {
            return null;
        }
        Node projectedRequest =
                request.clone();
        if (projectedRequest.getProperties()
                != null) {
            String[] routingFields =
                    new String[] {
                            OPERATION_FIELD,
                            CHANNEL_FIELD
                    };
            for (String field : routingFields) {
                Node supplied =
                        property(
                                request,
                                field);
                if (supplied != null
                        && supplied.isReferenceOnly()) {
                    projectedRequest
                            .getProperties()
                            .put(
                                    field,
                                    context
                                            .materializeExactReference(
                                                    supplied));
                }
            }
        }
        return OperationRequestView.from(
                projectedRequest);
    }

    private static boolean hasReferencedRoutingFields(
            Node event,
            CoordinationSemanticTypeIdentities identities) {
        Node request = event;
        if (isTimelineEntry(event, identities)) {
            request = property(event, MESSAGE_FIELD);
        }
        if (request == null) {
            return false;
        }
        if (request.isReferenceOnly()) {
            return true;
        }
        Node operation = property(
                request, OPERATION_FIELD);
        Node channel = property(
                request, CHANNEL_FIELD);
        return operation != null
                && operation.isReferenceOnly()
                || channel != null
                && channel.isReferenceOnly();
    }

    private static boolean matchesDirectOrTimelineOperationRequest(
            Node requestPattern,
            HandlerMatchContext context,
            CoordinationSemanticTypeIdentities identities) {
        if (context.matchesEventPattern(requestPattern)) {
            return true;
        }
        return context.matchesEventPattern(new Node()
                .type(new Node().blueId(
                        identities.timelineEntryBlueId()))
                .properties(MESSAGE_FIELD, requestPattern));
    }

    private static Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    private static boolean matchesOperationRequestType(Node node) {
        return matchesOperationRequestType(
                node,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    private static boolean matchesOperationRequestType(
            Node node,
            CoordinationSemanticTypeIdentities identities) {
        if (node == null || node.getType() == null) {
            return false;
        }
        Node exactType = node.getType();
        String operationRequestBlueId =
                identities.operationRequestBlueId();
        if (operationRequestBlueId.equals(exactType.getBlueId())) {
            return true;
        }
        try {
            if (BlueSemanticIdentity.equals(
                    exactType,
                    new Node().blueId(
                            operationRequestBlueId))) {
                return true;
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean matchesOperationRequestType(
            Node node,
            ExternalChannelFunctionContext context) {
        return matchesOperationRequestType(
                node,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    private static boolean matchesOperationRequestType(
            Node node,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        if (node == null || node.getType() == null) {
            return false;
        }
        String operationRequestBlueId =
                identities.operationRequestBlueId();
        if (operationRequestBlueId.equals(
                node.getType().getBlueId())) {
            return true;
        }
        return context.matchesPattern(
                node,
                new Node().type(
                        new Node().blueId(
                                operationRequestBlueId)));
    }

    private static boolean declaresExactType(
            Node node,
            String typeBlueId) {
        return node != null
                && node.getType() != null
                && typeBlueId.equals(
                node.getType().getBlueId());
    }

    private static boolean hasSemanticOutputBoundary(
            ExternalChannelFunctionContext context) {
        try {
            context.runtimeWorkSession().semanticOutputBoundary();
            return true;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    private static Node materializeIfReference(
            Node node,
            ExternalChannelFunctionContext context) {
        return node != null && node.isReferenceOnly()
                ? context.materializeExactReference(node)
                : node;
    }

    private static Node materializeIfReference(
            Node node,
            HandlerMatchContext context) {
        return node != null && node.isReferenceOnly()
                ? context.materializeExactReference(node)
                : node;
    }

    private static Node projectTimelineEntry(
            Node node,
            ExternalChannelFunctionContext context) {
        Node projected =
                materializeIfReference(node, context);
        if (projected == null
                || projected.getProperties() == null) {
            return projected;
        }
        String[] scalarHeaderFields = new String[] {
                TIMESTAMP_FIELD
        };
        Node mutable = projected;
        boolean cloned = false;
        for (String field : scalarHeaderFields) {
            Node value = property(projected, field);
            if (value == null || !value.isReferenceOnly()) {
                continue;
            }
            if (!cloned) {
                mutable = projected.clone();
                cloned = true;
            }
            mutable.getProperties().put(
                    field,
                    context.materializeExactReference(value));
        }
        return mutable;
    }

    private static Node projectOperationRequestFields(
            Node request,
            ExternalChannelFunctionContext context) {
        Node projected = request.clone();
        String[] routingFields =
                new String[] {
                        OPERATION_FIELD,
                        CHANNEL_FIELD
                };
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "operationRequestFieldRead",
                routingFields.length,
                GasChargeContext.of(
                        context.scopePath(),
                        context.channelKey(),
                        null,
                        "read Operation Request routing payload fields"));
        for (String field : routingFields) {
            Node supplied = property(
                    request, field);
            if (supplied != null
                    && supplied.isReferenceOnly()) {
                projected.getProperties().put(
                        field,
                        context.materializeExactReference(
                                supplied));
            }
        }
        return projected;
    }

    private static String nonBlankTextProperty(Node node, String key) {
        Node property = property(node, key);
        Object value = property != null ? property.getValue() : null;
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static String nonBlankTextProperty(
            Node node,
            String key,
            ExternalChannelFunctionContext context) {
        Node exactProperty = materializeIfReference(
                property(node, key), context);
        Object value =
                exactProperty != null
                        ? exactProperty.getValue()
                        : null;
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static BigInteger integerProperty(Node node, String key) {
        Node property = property(node, key);
        Object value = property != null ? property.getValue() : null;
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return null;
    }

    static final class TimelineEntryView {
        private final Node exactEntry;
        private final Node timeline;
        private final Node prevEntry;
        private final Node timestampNode;
        private final Node actor;
        private final Node source;
        private final Node onBehalfOf;
        private final Node message;
        private final BigInteger timestamp;

        private TimelineEntryView(Node exactEntry,
                                  Node timeline,
                                  Node prevEntry,
                                  Node timestampNode,
                                  BigInteger timestamp,
                                  Node actor,
                                  Node source,
                                  Node onBehalfOf,
                                  Node message) {
            this.exactEntry = exactEntry.clone();
            this.timeline = timeline.clone();
            this.prevEntry = cloneOrNull(prevEntry);
            this.timestampNode = timestampNode.clone();
            this.timestamp = timestamp;
            this.actor = actor.clone();
            this.source = cloneOrNull(source);
            this.onBehalfOf = cloneOrNull(onBehalfOf);
            this.message = message.clone();
        }

        Node exactEntry() {
            return exactEntry.clone();
        }

        Node timeline() {
            return timeline.clone();
        }

        Node prevEntry() {
            return cloneOrNull(prevEntry);
        }

        Node timestampNode() {
            return timestampNode.clone();
        }

        Node actor() {
            return actor.clone();
        }

        Node source() {
            return cloneOrNull(source);
        }

        Node onBehalfOf() {
            return cloneOrNull(onBehalfOf);
        }

        Node message() {
            return message.clone();
        }

        BigInteger timestamp() {
            return timestamp;
        }

        String entryBlueId() {
            return TimelineProviderSupport.eventId(exactEntry);
        }

        private static Node cloneOrNull(Node node) {
            return node != null ? node.clone() : null;
        }
    }

    static final class OperationRequestView {
        private final String operation;
        private final String channel;

        private OperationRequestView(String operation,
                                     String channel) {
            this.operation = operation;
            this.channel = channel;
        }

        private static OperationRequestView from(Node requestNode) {
            return new OperationRequestView(
                    nonBlankTextProperty(requestNode, OPERATION_FIELD),
                    nonBlankTextProperty(requestNode, CHANNEL_FIELD));
        }

        private static OperationRequestView from(
                Node requestNode,
                ExternalChannelFunctionContext context) {
            return from(
                    requestNode,
                    context,
                    true);
        }

        private static OperationRequestView from(
                Node requestNode,
                ExternalChannelFunctionContext context,
                boolean chargeRoutingFields) {
            if (chargeRoutingFields) {
                CoordinationRuntimeGas.charge(
                        context.runtimeWorkSession(),
                        "operationRequestFieldRead",
                        2L,
                        GasChargeContext.of(
                                context.scopePath(),
                                context.channelKey(),
                                null,
                                "read Operation Request dispatch fields"));
            }
            return new OperationRequestView(
                    nonBlankTextProperty(
                            requestNode,
                            OPERATION_FIELD,
                            context),
                    nonBlankTextProperty(
                            requestNode,
                            CHANNEL_FIELD,
                            context));
        }

        boolean routable() {
            return operation != null && channel != null;
        }

        String operation() {
            return operation;
        }

        String channel() {
            return channel;
        }

    }
}
