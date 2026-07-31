package blue.coordination.processor;

import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.repo.coordination.TimelineChannel;

import java.util.Objects;

/**
 * Generic runtime adapter that gives one explicitly registered
 * {@link TimelineChannel} subtype the standard Timeline semantics.
 *
 * <p>The Java class selects only the object model used for exact runtime
 * dispatch. Language still verifies the subtype's Blue type evidence and
 * performs semantic subtype matching. Constructing this adapter therefore
 * never creates an identity alias or turns Java inheritance into Blue type
 * evidence.</p>
 *
 * @param <T> exact generated or host-provided Timeline Channel subtype
 */
final class TimelineChannelSubtypeProcessor<
        T extends TimelineChannel>
        implements ChannelProcessor<T> {
    private final Class<T> contractType;

    /**
     * Creates Timeline semantics for one exact subtype registration.
     *
     * @param contractType exact subtype model class
     */
    TimelineChannelSubtypeProcessor(
            Class<T> contractType) {
        this.contractType =
                Objects.requireNonNull(
                        contractType, "contractType");
        if (!TimelineChannel.class
                .isAssignableFrom(
                        contractType)) {
            throw new IllegalArgumentException(
                    "contractType must be a Timeline Channel "
                            + "subtype");
        }
        if (TimelineChannel.class.equals(
                contractType)) {
            throw new IllegalArgumentException(
                    "Use TimelineChannelProcessor for the base "
                            + "Timeline Channel type");
        }
    }

    @Override
    public Class<T> contractType() {
        return contractType;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<T>
    externalSubscriptionFunctions() {
        return TimelineExternalSubscriptionFunctions
                .forSubtype();
    }

    @Override
    public ChannelEvaluation evaluate(
            T contract,
            ChannelEvaluationContext context) {
        return TimelineProviderSupport
                .evaluateTimelineEntry(
                        contract, context);
    }

    @Override
    public String eventId(
            T contract,
            ChannelEvaluationContext context) {
        return TimelineProviderSupport.eventId(
                context.event());
    }

    @Override
    public boolean isNewerEvent(
            T contract,
            ChannelCheckpointContext context) {
        return TimelineProviderSupport
                .isNewerTimelineSubject(
                        context,
                        TimelineExternalSubscriptionFunctions
                                .TIMELINE_ORDER_SUBJECT_VERSION);
    }
}
