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
    private final CoordinationSemanticTypeIdentities identities;
    private final TimelineExternalSubscriptionFunctions<TimelineChannel>
            subscriptionFunctions;

    /** Creates the published Coordination 1.0 Timeline processor. */
    public TimelineChannelProcessor() {
        this(CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    /** Creates one processor bound to an already validated identity profile. */
    public TimelineChannelProcessor(
            CoordinationSemanticTypeIdentities identities) {
        this.identities = java.util.Objects.requireNonNull(
                identities, "identities");
        this.subscriptionFunctions =
                TimelineExternalSubscriptionFunctions.with(identities);
    }

    CoordinationSemanticTypeIdentities semanticTypeIdentities() {
        return identities;
    }

    @Override
    public Class<TimelineChannel> contractType() {
        return TimelineChannel.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<TimelineChannel>
    externalSubscriptionFunctions() {
        return subscriptionFunctions;
    }

    @Override
    public ChannelEvaluation evaluate(TimelineChannel contract, ChannelEvaluationContext context) {
        return TimelineProviderSupport.evaluateTimelineEntry(
                contract, context, identities);
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
