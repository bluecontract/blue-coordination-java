package blue.coordination.sdk;

import java.util.Objects;

/** One exact external broadcast entry before append. */
public final class EventCall {
    private final SdkCoordinationRuntime runtime;
    private final TimelineHandle timeline;
    private ExactBlueValue event;
    private boolean consumed;

    EventCall(SdkCoordinationRuntime runtime, TimelineHandle timeline) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    /** Selects a complete exact Timeline Entry envelope. */
    public EventCall exact(ExactBlueValue exactEvent) {
        requireMutable();
        event = Objects.requireNonNull(exactEvent, "exactEvent");
        return this;
    }

    /** Appends without processing. */
    public EntryHandle submit() {
        requireReady();
        consumed = true;
        return runtime.submitEvent(this);
    }

    /** Appends and drains canonically through this entry. */
    public EntryResult execute() {
        requireReady();
        consumed = true;
        return runtime.executeEvent(this);
    }

    TimelineHandle timeline() { return timeline; }

    ExactBlueValue event() { return event; }

    private void requireReady() {
        requireMutable();
        Objects.requireNonNull(event, "exact event");
    }

    private void requireMutable() {
        if (consumed) {
            throw new IllegalStateException(
                    "An event call can be submitted only once");
        }
    }
}
