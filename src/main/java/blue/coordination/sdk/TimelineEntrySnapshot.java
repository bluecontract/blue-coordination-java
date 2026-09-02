package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;

/** Immutable read-only audit view of one whole retained Timeline Entry. */
public record TimelineEntrySnapshot(
        ExactBlueValue exact,
        Optional<ExactBlueValue> request,
        TimelineHandle timeline,
        Optional<String> previousEntryBlueId,
        String operation,
        String channel,
        long timestampMicros,
        long globalSequence,
        long timelineSequence) {
    /** Validates exact entry evidence while retaining an immutable predecessor. */
    public TimelineEntrySnapshot {
        exact = Objects.requireNonNull(exact, "exact");
        request = Objects.requireNonNull(request, "request");
        timeline = Objects.requireNonNull(timeline, "timeline");
        previousEntryBlueId = Objects.requireNonNull(
                previousEntryBlueId, "previousEntryBlueId");
        operation = SdkPreconditions.requireText(operation, "operation");
        channel = SdkPreconditions.requireText(channel, "channel");
        if (timestampMicros <= 0L || globalSequence <= 0L
                || timelineSequence <= 0L) {
            throw new IllegalArgumentException(
                    "Timeline Entry coordinates must be positive");
        }
    }

    /** Exact content identity of {@link #exact()}. */
    public String blueId() {
        return exact.blueId();
    }
}
