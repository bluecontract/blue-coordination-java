package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.MarkerContract;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.TimelineChannel;
import java.util.Map;

public final class AllTimelinesChannelProcessor implements ChannelProcessor<AllTimelinesChannel> {
    @Override
    public Class<AllTimelinesChannel> contractType() {
        return AllTimelinesChannel.class;
    }

    @Override
    public ChannelEvaluation evaluate(AllTimelinesChannel contract, ChannelEvaluationContext context) {
        Node event = context.event();
        if (!CoordinationEventNodes.isTimelineEntry(event)) {
            return ChannelEvaluation.noMatch();
        }
        MatchingTimeline matching = matchingTimeline(context);
        if (matching == null) {
            return ChannelEvaluation.noMatch();
        }
        Node deliveryEvent = matching.evaluation.event() != null
                ? matching.evaluation.event()
                : event;
        return ChannelEvaluation.match(withAllTimelinesMetadata(deliveryEvent, matching.channelKey),
                matching.evaluation.eventId());
    }

    private MatchingTimeline matchingTimeline(ChannelEvaluationContext context) {
        for (Map.Entry<String, ChannelContract> entry : context.channels().entrySet()) {
            String key = entry.getKey();
            ChannelContract channel = entry.getValue();
            if (key == null || key.equals(context.bindingKey()) || !(channel instanceof TimelineChannel)) {
                continue;
            }
            ChannelProcessor<? extends ChannelContract> processor = context.channelProcessor(key);
            if (processor == null) {
                throw new IllegalStateException("No processor registered for All Timelines Channel child '"
                        + key + "'");
            }
            ChannelEvaluation evaluation = evaluateChild(processor, channel, context.forBindingKey(key));
            if (evaluation != null && evaluation.matches() && childCheckpointAllows(key, context)) {
                return new MatchingTimeline(key, evaluation);
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ChannelEvaluation evaluateChild(ChannelProcessor processor,
                                            ChannelContract child,
                                            ChannelEvaluationContext context) {
        return processor.evaluate(child, context);
    }

    private boolean childCheckpointAllows(String channelKey,
                                          ChannelEvaluationContext context) {
        ChannelEventCheckpoint checkpoint = checkpoint(context);
        Node lastEvent = checkpoint != null ? checkpoint.lastEvent(channelKey) : null;
        return lastEvent == null || !TimelineProviderSupport.isOlderSameTimelineEvent(context.event(), lastEvent);
    }

    private ChannelEventCheckpoint checkpoint(ChannelEvaluationContext context) {
        MarkerContract marker = context.markers().get("checkpoint");
        return marker instanceof ChannelEventCheckpoint ? (ChannelEventCheckpoint) marker : null;
    }

    @Override
    public boolean isNewerEvent(AllTimelinesChannel contract, ChannelCheckpointContext context) {
        return TimelineProviderSupport.isNewerOrDifferentTimelineEvent(context);
    }

    private Node withAllTimelinesMetadata(Node event, String sourceChannelKey) {
        Node copy = event.clone();
        Node meta = TimelineProviderSupport.property(copy, "meta");
        if (meta == null) {
            meta = new Node();
            copy.properties("meta", meta);
        }
        meta.properties("allTimelinesSourceChannelKey", new Node().value(sourceChannelKey));
        return copy;
    }

    private static final class MatchingTimeline {
        private final String channelKey;
        private final ChannelEvaluation evaluation;

        private MatchingTimeline(String channelKey, ChannelEvaluation evaluation) {
            this.channelKey = channelKey;
            this.evaluation = evaluation;
        }
    }
}
