package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.model.ChannelContract;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CompositeTimelineChannelProcessor implements ChannelProcessor<CompositeTimelineChannel> {
    @Override
    public Class<CompositeTimelineChannel> contractType() {
        return CompositeTimelineChannel.class;
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
            Node deliveryEvent = childEvaluation.event() != null
                    ? childEvaluation.event()
                    : context.event();
            if (deliveryEvent == null) {
                continue;
            }
            MatchingChild candidate = new MatchingChild(key,
                    order(child),
                    deliveryEvent,
                    childEvaluation.eventId());
            if (matching == null || candidate.precedes(matching)) {
                matching = candidate;
            }
        }
        if (matching == null) {
            return ChannelEvaluation.noMatch();
        }
        return ChannelEvaluation.match(withCompositeMetadata(matching.event, matching.channelKey),
                matching.eventId);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ChannelEvaluation evaluateChild(ChannelProcessor processor,
                                            ChannelContract child,
                                            ChannelEvaluationContext context) {
        return processor.evaluate(child, context);
    }

    @Override
    public boolean isNewerEvent(CompositeTimelineChannel contract, ChannelCheckpointContext context) {
        return TimelineProviderSupport.isNewerOrDifferentTimelineEvent(context);
    }

    private Node withCompositeMetadata(Node event, String childKey) {
        Node copy = event.clone();
        Node meta = property(copy, "meta");
        if (meta == null) {
            meta = new Node();
            copy.properties("meta", meta);
        }
        meta.properties("compositeSourceChannelKey", new Node().value(childKey));
        return copy;
    }

    private Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
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
        private final Node event;
        private final String eventId;

        private MatchingChild(String channelKey, int order, Node event, String eventId) {
            this.channelKey = channelKey;
            this.order = order;
            this.event = event;
            this.eventId = eventId;
        }

        private boolean precedes(MatchingChild other) {
            int orderComparison = Integer.compare(order, other.order);
            return orderComparison < 0
                    || (orderComparison == 0 && channelKey.compareTo(other.channelKey) < 0);
        }
    }
}
