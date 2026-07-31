package blue.coordination.processor;

import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.repo.coordination.TimelineChannel;

/**
 * Implements fixed-repository Timeline Channel subscription, acceptance, and
 * per-source checkpoint semantics.
 */
public final class TimelineChannelProcessor implements ChannelProcessor<TimelineChannel> {
    @Override
    public Class<TimelineChannel> contractType() {
        return TimelineChannel.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<TimelineChannel>
    externalSubscriptionFunctions() {
        return TimelineExternalSubscriptionFunctions.INSTANCE;
    }

    @Override
    public ChannelEvaluation evaluate(TimelineChannel contract, ChannelEvaluationContext context) {
        return TimelineProviderSupport.evaluateTimelineEntry(contract, context);
    }

    @Override
    public String eventId(TimelineChannel contract, ChannelEvaluationContext context) {
        return TimelineProviderSupport.eventId(context.event());
    }

    @Override
    public boolean isNewerEvent(TimelineChannel contract,
                                ChannelCheckpointContext context) {
        return TimelineProviderSupport.isNewerTimelineSubject(
                context,
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION);
    }
}
