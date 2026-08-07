package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.model.ChannelContract;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluates the deterministic union of the Timeline Channels explicitly
 * listed by a Composite Timeline Channel.
 *
 * <p>Duplicate member keys are evaluated once. Concrete member processors
 * retain authority over Timeline acceptance, while this processor coalesces
 * successful members into one logical external delivery.</p>
 */
public final class CompositeTimelineChannelProcessor implements ChannelProcessor<CompositeTimelineChannel> {
    private final ExternalChannelSubscriptionFunctions<
            CompositeTimelineChannel> subscriptionFunctions;

    public CompositeTimelineChannelProcessor() {
        this.subscriptionFunctions =
                CompositeTimelineExternalSubscriptionFunctions.INSTANCE;
    }

    CompositeTimelineChannelProcessor(
            String timelineChannelTypeBlueId,
            CoordinationSemanticTypeIdentities identities) {
        this.subscriptionFunctions =
                new CompositeTimelineExternalSubscriptionFunctions(
                        timelineChannelTypeBlueId,
                        identities);
    }

    @Override
    public Class<CompositeTimelineChannel> contractType() {
        return CompositeTimelineChannel.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<
            CompositeTimelineChannel> externalSubscriptionFunctions() {
        return subscriptionFunctions;
    }

    @Override
    public ChannelEvaluation evaluate(CompositeTimelineChannel contract, ChannelEvaluationContext context) {
        List<String> channels = contract.getChannels();
        if (channels == null || channels.isEmpty()) {
            return ChannelEvaluation.noMatch();
        }
        MatchingChild matching = null;
        Set<String> evaluatedKeys = new HashSet<String>();
        for (String childKey : channels) {
            String key = trimToNull(childKey);
            if (key == null || !evaluatedKeys.add(key)) {
                continue;
            }
            if (key.equals(context.bindingKey())) {
                throw new IllegalStateException("Composite Timeline Channel '" + context.bindingKey()
                        + "' cannot include itself");
            }
            ChannelContract child = context.channel(key);
            if (child == null) {
                throw new IllegalStateException("Composite Timeline Channel '" + context.bindingKey()
                        + "' references missing child channel '" + key + "'");
            }
            if (!(child instanceof TimelineChannel)) {
                throw new IllegalStateException("Composite Timeline Channel '" + context.bindingKey()
                        + "' child '" + key + "' must be a Timeline Channel");
            }
            ChannelProcessor<? extends ChannelContract> processor = context.channelProcessor(key);
            if (processor == null) {
                throw new IllegalStateException("No processor registered for Composite Timeline Channel child '"
                        + key + "'");
            }
            ChannelEvaluation childEvaluation = evaluateChild(processor, child, context.forBindingKey(key));
            if (childEvaluation == null || !childEvaluation.matches()) {
                continue;
            }
            MatchingChild candidate = new MatchingChild(key,
                    order(child),
                    childEvaluation);
            if (matching == null || candidate.precedes(matching)) {
                matching = candidate;
            }
        }
        if (matching == null) {
            return ChannelEvaluation.noMatch();
        }
        return TimelineProviderSupport.preserveUnionPayload(
                matching.evaluation, context.event());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ChannelEvaluation evaluateChild(ChannelProcessor processor,
                                            ChannelContract child,
                                            ChannelEvaluationContext context) {
        return processor.evaluate(child, context);
    }

    @Override
    public boolean isNewerEvent(CompositeTimelineChannel contract,
                                ChannelCheckpointContext context) {
        return TimelineProviderSupport.isNewerTimelineSubject(
                context,
                CompositeTimelineExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int order(ChannelContract contract) {
        return contract.getOrder() != null ? contract.getOrder() : 0;
    }

    private static final class MatchingChild {
        private final String channelKey;
        private final int order;
        private final ChannelEvaluation evaluation;

        private MatchingChild(String channelKey, int order, ChannelEvaluation evaluation) {
            this.channelKey = channelKey;
            this.order = order;
            this.evaluation = evaluation;
        }

        private boolean precedes(MatchingChild other) {
            int orderComparison = Integer.compare(order, other.order);
            return orderComparison < 0
                    || (orderComparison == 0 && channelKey.compareTo(other.channelKey) < 0);
        }
    }
}
