package blue.coordination.processor;

import java.util.List;

/** Public current-profile projection used by environment-owned route indexes. */
public final class CoordinationTimelineRouteProjection {

    private CoordinationTimelineRouteProjection() {
    }

    /**
     * Returns the exact representation-independent Timeline/actor
     * subscription key produced by the current generated identity profile.
     */
    public static String exactSubscriptionKey(
            String timelineId,
            String actorId) {
        return TimelineSubscriptionProjection.exactScalarPairKey(
                timelineId,
                actorId,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    /** Returns every current event-side key from most to least selective. */
    public static List<String> exactEventSubscriptionKeys(
            String timelineId,
            String actorId) {
        return TimelineSubscriptionProjection.exactScalarEventKeys(
                timelineId,
                actorId,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }
}
