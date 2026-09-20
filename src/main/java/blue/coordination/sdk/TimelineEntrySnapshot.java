package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;

/** Immutable read-only audit view of one whole retained Timeline Entry. */
public record TimelineEntrySnapshot(
        ExactBlueValue exact,
        Optional<OperationDetails> operationDetails,
        TimelineHandle timeline,
        Optional<String> previousEntryBlueId,
        long timestampMicros,
        long globalSequence,
        long timelineSequence) {
    /** Validates exact entry evidence while retaining an immutable predecessor. */
    public TimelineEntrySnapshot {
        exact = Objects.requireNonNull(exact, "exact");
        operationDetails = Objects.requireNonNull(operationDetails, "operationDetails");
        timeline = Objects.requireNonNull(timeline, "timeline");
        previousEntryBlueId = Objects.requireNonNull(
                previousEntryBlueId, "previousEntryBlueId");
        if (timestampMicros <= 0L || globalSequence <= 0L
                || timelineSequence <= 0L) {
            throw new IllegalArgumentException(
                    "Timeline Entry coordinates must be positive");
        }
    }

    /** Optional operation specialization of the unchanged exact event. */
    public record OperationDetails(String operation, String channel, Optional<ExactBlueValue> request) {
        /** Validates operation-specific routing metadata. */
        public OperationDetails {
            operation = SdkPreconditions.requireText(operation, "operation");
            channel = SdkPreconditions.requireText(channel, "channel");
            request = Objects.requireNonNull(request, "request");
        }
    }

    /** Operation-only constructor retained from the preceding RC. */
    public TimelineEntrySnapshot(ExactBlueValue exact, Optional<ExactBlueValue> request,
            TimelineHandle timeline, Optional<String> previousEntryBlueId, String operation, String channel,
            long timestampMicros, long globalSequence, long timelineSequence) {
        this(exact, Optional.of(new OperationDetails(operation, channel, request)), timeline,
                previousEntryBlueId, timestampMicros, globalSequence, timelineSequence);
    }

    /**
     * Returns the operation name for an operation entry.
     * @throws java.util.NoSuchElementException for a general entry
     */
    public String operation() { return operationDetails.orElseThrow().operation(); }

    /**
     * Returns the target channel for an operation entry.
     * @throws java.util.NoSuchElementException for a general entry
     */
    public String channel() { return operationDetails.orElseThrow().channel(); }

    /** Operation request, empty for general entries and operations without a request. */
    public Optional<ExactBlueValue> request() { return operationDetails.flatMap(OperationDetails::request); }

    /** Accepted-base constructor; preserves request presence and its possibly collapsed envelope value. */
    public TimelineEntrySnapshot(
            ExactBlueValue exact, TimelineHandle timeline,
            Optional<String> previousEntryBlueId, String operation, String channel,
            long timestampMicros, long globalSequence, long timelineSequence) {
        this(exact, requestIn(exact), timeline, previousEntryBlueId, operation, channel,
                timestampMicros, globalSequence, timelineSequence);
    }

    private static Optional<ExactBlueValue> requestIn(ExactBlueValue exact) {
        return Optional.ofNullable(blue.language.model.NodePathEditor.getOrNull(
                Objects.requireNonNull(exact, "exact").copyNode(), "/message/request"))
                .map(value -> ExactBlueValue.wrap(blue.coordination.api.ExactValue.verified(value)));
    }

    /** Exact content identity of {@link #exact()}. */
    public String blueId() {
        return exact.blueId();
    }
}
