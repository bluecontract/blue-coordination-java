package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.GasChargeContext;
import blue.repo.coordination.TimelineChannel;

import java.util.List;

/**
 * Immutable Contracts 1.0 subscription functions for a Timeline Channel.
 *
 * <p>The explicit Timeline Entry key is a conservative finite preselection
 * key. Complete immutable Timeline and Actor matching is authoritative and
 * uses the processor-owned verified pattern matcher so inline and pure
 * reference representations are equivalent.</p>
 */
final class TimelineExternalSubscriptionFunctions<T extends TimelineChannel>
        implements ExternalChannelSubscriptionFunctions<T> {

    static final TimelineExternalSubscriptionFunctions<TimelineChannel>
            INSTANCE =
            new TimelineExternalSubscriptionFunctions<TimelineChannel>(
                    CoordinationSemanticTypeIdentities.publishedDefaults());

    static final String TIMELINE_ENTRY_KEY =
            TimelineSubscriptionProjection.BROAD_KEY;
    static final String TIMELINE_ORDER_SUBJECT_VERSION =
            "blue.coordination/1.0/timeline-order-subject-v3";

    private final CoordinationSemanticTypeIdentities identities;

    private TimelineExternalSubscriptionFunctions(
            CoordinationSemanticTypeIdentities identities) {
        this.identities = java.util.Objects.requireNonNull(
                identities, "identities");
    }

    static TimelineExternalSubscriptionFunctions<TimelineChannel> with(
            CoordinationSemanticTypeIdentities identities) {
        if (identities == CoordinationSemanticTypeIdentities
                .publishedDefaults()) {
            return INSTANCE;
        }
        return new TimelineExternalSubscriptionFunctions<TimelineChannel>(
                identities);
    }

    @SuppressWarnings("unchecked")
    static <S extends TimelineChannel>
    TimelineExternalSubscriptionFunctions<S> forSubtype() {
        return (TimelineExternalSubscriptionFunctions<S>)
                (TimelineExternalSubscriptionFunctions<?>)
                        INSTANCE;
    }

    @Override
    public List<String> channelKeys(T immutableContractSnapshot) {
        /*
         * Context-free, out-of-band callers have no verified header
         * materializer. Keep that surface sound; PROCESS always uses the
         * context-aware selective projection below.
         */
        TimelineSubscriptionProjection.channelKeys(
                immutableContractSnapshot,
                identities);
        return java.util.Collections.singletonList(
                TimelineSubscriptionProjection.BROAD_KEY);
    }

    @Override
    public List<String> channelKeys(
            T immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        return TimelineSubscriptionProjection.channelKeys(
                immutableContractSnapshot,
                identities);
    }

    @Override
    public List<String> eventKeys(Node exactEvent) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(
                        exactEvent, identities);
        if (entry == null) {
            return java.util.Collections.emptyList();
        }
        /*
         * The context-free surface cannot safely materialize header
         * references. Keep it sound with the bounded broad key; the processor
         * path below supplies the selective verified projection.
         */
        return java.util.Collections.singletonList(
                TimelineSubscriptionProjection.BROAD_KEY);
    }

    @Override
    public List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return TimelineSubscriptionProjection.eventKeys(
                exactEvent,
                context,
                identities);
    }

    @Override
    public boolean accepts(T immutableContractSnapshot,
                           Node exactEvent) {
        if (CoordinationEventNodes.timelineEntry(
                exactEvent, identities)
                == null) {
            return false;
        }
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(
                        exactEvent, identities);
        return TimelineProviderSupport.matchesTimelineAndActor(
                immutableContractSnapshot, entry, identities);
    }

    @Override
    public boolean accepts(
            T immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(
                        exactEvent, context, identities);
        if (immutableContractSnapshot == null
                || entry == null) {
            return false;
        }
        chargeBindingComparison(
                context,
                "compare Timeline binding");
        boolean timelineMatches =
                CoordinationEventNodes.matchesGeneratedBinding(
                entry.timeline(),
                immutableContractSnapshot.getTimeline(),
                context,
                identities);
        if (!timelineMatches) {
            return false;
        }
        chargeBindingComparison(
                context,
                "compare Actor binding");
        return CoordinationEventNodes.matchesGeneratedBinding(
                entry.actor(),
                immutableContractSnapshot.getActor(),
                context,
                identities);
    }

    @Override
    public Node payload(
            T immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .payload(exactEvent, context, identities);
    }

    @Override
    public Node checkpointSubject(T immutableContractSnapshot,
                                  Node exactEvent,
                                  Node exactPayload) {
        if (!accepts(
                immutableContractSnapshot,
                exactEvent)) {
            throw new IllegalArgumentException(
                    "Timeline checkpoint subject requires an accepted "
                            + "Timeline Entry");
        }
        return TimelineProviderSupport.timelineOrderSubject(
                CoordinationEventNodes.timelineEntry(
                        exactEvent, identities));
    }

    @Override
    public Node checkpointSubject(
            T immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntryHeader(
                        exactEvent, context, identities);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "Timeline checkpoint subject requires an accepted "
                            + "Timeline Entry");
        }
        return TimelineProviderSupport.timelineOrderSubject(entry);
    }

    @Override
    public String handlerChannelKey(
            T immutableContractSnapshot,
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
            T immutableContractSnapshot,
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
            T immutableContractSnapshot) {
        channelKeys(immutableContractSnapshot);
        return "coordination.timeline-entry:"
                + identities.timelineEntryBlueId()
                + "|semantic-profile="
                + identities.profileIdentity()
                + "|projection="
                + TimelineSubscriptionProjection.VERSION
                + "|subject="
                + TIMELINE_ORDER_SUBJECT_VERSION;
    }

    @Override
    public String checkpointDomainDiscriminator(
            T immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        OperationRequestRoutingFunctions
                .declareTargetChannelCatalog(
                        context);
        return checkpointDomainDiscriminator(
                immutableContractSnapshot);
    }

    private static void chargeBindingComparison(
            ExternalChannelFunctionContext context,
            String reason) {
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "timelineBindingCompared",
                1L,
                GasChargeContext.of(
                        context.scopePath(),
                        context.channelKey(),
                        null,
                        reason));
    }
}
