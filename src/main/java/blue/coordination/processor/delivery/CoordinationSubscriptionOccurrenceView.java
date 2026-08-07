package blue.coordination.processor.delivery;

import blue.language.processor.SubscriptionDelta;

import java.util.List;

/**
 * Delivery-facing immutable view of one retained subscription occurrence.
 *
 * <p>The persistence value implements this role in the public facade package;
 * the delivery engine owns only the semantic fields it consumes.</p>
 */
public interface CoordinationSubscriptionOccurrenceView {
    String occurrenceKey();

    String scopePath();

    String channelKey();

    List<String> sourceContributionNodeBlueIds();

    String effectiveTypeBlueId();

    String headerIdentityBlueId();

    SubscriptionDelta.Entry toSubscriptionDeltaEntry();
}
