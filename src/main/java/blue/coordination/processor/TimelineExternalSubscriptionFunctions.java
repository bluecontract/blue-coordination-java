package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;

import java.util.Collections;
import java.util.List;

/**
 * Immutable Contracts 1.0 subscription functions for a Timeline Channel.
 *
 * <p>The explicit Timeline Entry key is a conservative finite preselection
 * key. Complete immutable Timeline and Actor matching is authoritative and
 * uses the processor-owned verified pattern matcher so inline and pure
 * reference representations are equivalent.</p>
 */
final class TimelineExternalSubscriptionFunctions
        implements ExternalChannelSubscriptionFunctions<TimelineChannel> {

    static final TimelineExternalSubscriptionFunctions INSTANCE =
            new TimelineExternalSubscriptionFunctions();

    static final String TIMELINE_ENTRY_KEY =
            "blue.coordination/1.0/timeline-entry";
    static final String TIMELINE_ORDER_SUBJECT_VERSION =
            "blue.coordination/1.0/timeline-order-subject";

    private TimelineExternalSubscriptionFunctions() {
    }

    @Override
    public List<String> channelKeys(TimelineChannel immutableContractSnapshot) {
        if (immutableContractSnapshot == null
                || immutableContractSnapshot.getTimeline() == null
                || immutableContractSnapshot.getActor() == null) {
            throw new IllegalArgumentException(
                    "Timeline Channel requires immutable timeline and actor headers");
        }
        return Collections.singletonList(TIMELINE_ENTRY_KEY);
    }

    @Override
    public List<String> channelKeys(
            TimelineChannel immutableContractSnapshot,
            ExternalChannelFunctionContext context) {
        List<String> keys =
                channelKeys(immutableContractSnapshot);
        OperationRequestRoutingFunctions
                .declareTargetChannelFamilies(
                        immutableContractSnapshot,
                        context);
        return keys;
    }

    @Override
    public List<String> eventKeys(Node exactEvent) {
        return CoordinationEventNodes.isTimelineEntry(exactEvent)
                ? Collections.singletonList(TIMELINE_ENTRY_KEY)
                : Collections.<String>emptyList();
    }

    @Override
    public List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return CoordinationEventNodes.isTimelineEntry(
                exactEvent, context)
                ? Collections.singletonList(TIMELINE_ENTRY_KEY)
                : Collections.<String>emptyList();
    }

    @Override
    public boolean accepts(TimelineChannel immutableContractSnapshot,
                           Node exactEvent) {
        if (!eventKeys(exactEvent).contains(TIMELINE_ENTRY_KEY)) {
            return false;
        }
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(exactEvent);
        return TimelineProviderSupport.matchesTimelineAndActor(
                immutableContractSnapshot, entry);
    }

    @Override
    public boolean accepts(
            TimelineChannel immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntry(
                        exactEvent, context);
        return immutableContractSnapshot != null
                && entry != null
                && CoordinationEventNodes.matchesGeneratedBinding(
                entry.timeline(),
                immutableContractSnapshot.getTimeline(),
                context)
                && CoordinationEventNodes.matchesGeneratedBinding(
                entry.actor(),
                immutableContractSnapshot.getActor(),
                context);
    }

    @Override
    public Node payload(
            TimelineChannel immutableContractSnapshot,
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .payload(exactEvent, context);
    }

    @Override
    public Node checkpointSubject(TimelineChannel immutableContractSnapshot,
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
                CoordinationEventNodes.timelineEntry(exactEvent));
    }

    @Override
    public Node checkpointSubject(
            TimelineChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        CoordinationEventNodes.TimelineEntryView entry =
                CoordinationEventNodes.timelineEntryHeader(
                        exactEvent, context);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "Timeline checkpoint subject requires an accepted "
                            + "Timeline Entry");
        }
        return TimelineProviderSupport.timelineOrderSubject(entry);
    }

    @Override
    public String handlerChannelKey(
            TimelineChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .handlerChannelKey(
                        immutableContractSnapshot,
                        exactEvent,
                        context);
    }

    @Override
    public String logicalDeliveryKey(
            TimelineChannel immutableContractSnapshot,
            Node exactEvent,
            Node exactPayload,
            ExternalChannelFunctionContext context) {
        return OperationRequestRoutingFunctions
                .logicalDeliveryKey(
                        immutableContractSnapshot,
                        exactEvent,
                        context);
    }

    @Override
    public String checkpointDomainDiscriminator(
            TimelineChannel immutableContractSnapshot) {
        channelKeys(immutableContractSnapshot);
        return "coordination.timeline-entry:"
                + TimelineEntry.blueId()
                + "|subject="
                + TIMELINE_ORDER_SUBJECT_VERSION;
    }
}
