package blue.coordination.sdk;

import java.util.Objects;

/** Starts explicit broadcast admission of exact external Timeline entries. */
public final class EventGateway {
    private final SdkCoordinationRuntime runtime;

    EventGateway(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public EventCall from(TimelineHandle timeline) {
        return new EventCall(
                runtime, Objects.requireNonNull(timeline, "timeline"));
    }
}
