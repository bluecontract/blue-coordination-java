package blue.coordination.sdk;

import java.util.Objects;

/** Registers authenticated append-only Timelines in one environment. */
public final class TimelineCatalog {
    private final SdkCoordinationRuntime runtime;

    TimelineCatalog(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Registers a local Timeline whose stable id and actor account are equal. */
    public TimelineHandle local(String accountId) {
        return runtime.registerTimeline(accountId, accountId);
    }

    /** Registers an explicitly named Timeline for an actor account. */
    public TimelineHandle register(String timelineId, String accountId) {
        return runtime.registerTimeline(timelineId, accountId);
    }

    /** Registers a Timeline whose SDK-authored entries use the chosen actor. */
    public TimelineHandle register(
            String timelineId,
            String accountId,
            TimelineActorKind actorKind) {
        return runtime.registerTimeline(timelineId, accountId, actorKind);
    }
}
