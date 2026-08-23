package blue.coordination.sdk;

/** Immutable accepted Timeline and actor address for one compiled route. */
public record TimelineSourceSnapshot(
        String timelineId,
        String actorId) {
    /** Validates the complete external source address. */
    public TimelineSourceSnapshot {
        timelineId = SdkPreconditions.requireText(timelineId, "timelineId");
        actorId = SdkPreconditions.requireText(actorId, "actorId");
    }
}
