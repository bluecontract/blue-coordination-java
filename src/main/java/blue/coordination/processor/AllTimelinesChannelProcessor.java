package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.model.ChannelContract;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.TimelineChannel;
import java.util.Map;

/** Evaluates the deterministic union of every same-scope Timeline Channel. */
public final class AllTimelinesChannelProcessor implements ChannelProcessor<AllTimelinesChannel> {
    private final CoordinationSemanticTypeIdentities identities;
    private final ExternalChannelSubscriptionFunctions<
            AllTimelinesChannel> subscriptionFunctions;

    public AllTimelinesChannelProcessor() {
        this.identities = CoordinationSemanticTypeIdentities
                .publishedDefaults();
        this.subscriptionFunctions =
                AllTimelinesExternalSubscriptionFunctions.INSTANCE;
    }

    AllTimelinesChannelProcessor(
            String timelineChannelTypeBlueId,
            CoordinationSemanticTypeIdentities identities) {
        this.identities = java.util.Objects.requireNonNull(
                identities, "identities");
        this.subscriptionFunctions =
                new AllTimelinesExternalSubscriptionFunctions(
                        timelineChannelTypeBlueId,
                        identities);
    }

    @Override
    public Class<AllTimelinesChannel> contractType() {
        return AllTimelinesChannel.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<AllTimelinesChannel>
    externalSubscriptionFunctions() {
        return subscriptionFunctions;
    }

    @Override
    public ChannelEvaluation evaluate(AllTimelinesChannel contract, ChannelEvaluationContext context) {
        Node event = context.event();
        if (!CoordinationEventNodes.isTimelineEntry(event, identities)) {
            return ChannelEvaluation.noMatch();
        }
        MatchingTimeline matching = matchingTimeline(context);
        if (matching == null) {
            return ChannelEvaluation.noMatch();
        }
        return TimelineProviderSupport.preserveUnionPayload(
                matching.evaluation, event);
    }

    private MatchingTimeline matchingTimeline(ChannelEvaluationContext context) {
        MatchingTimeline matching = null;
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
            if (evaluation != null && evaluation.matches()) {
                MatchingTimeline candidate = new MatchingTimeline(key,
                        order(channel),
                        evaluation);
                if (matching == null || candidate.precedes(matching)) {
                    matching = candidate;
                }
            }
        }
        return matching;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ChannelEvaluation evaluateChild(ChannelProcessor processor,
                                            ChannelContract child,
                                            ChannelEvaluationContext context) {
        return processor.evaluate(child, context);
    }

    @Override
    public boolean isNewerEvent(AllTimelinesChannel contract,
                                ChannelCheckpointContext context) {
        return TimelineProviderSupport.isNewerTimelineSubject(
                context,
                AllTimelinesExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION);
    }

    private int order(ChannelContract contract) {
        return contract.getOrder() != null ? contract.getOrder() : 0;
    }

    static final class MatchingTimeline {
        private final String channelKey;
        private final int order;
        private final ChannelEvaluation evaluation;

        MatchingTimeline(String channelKey, int order, ChannelEvaluation evaluation) {
            this.channelKey = channelKey;
            this.order = order;
            this.evaluation = evaluation;
        }

        boolean precedes(MatchingTimeline other) {
            int orderComparison = Integer.compare(order, other.order);
            return orderComparison < 0
                    || (orderComparison == 0
                    && ExternalOrderKey.compareTextCodePoints(
                            channelKey, other.channelKey) < 0);
        }
    }
}
