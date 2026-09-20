package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;
import java.util.Optional;

/** One whole exact Timeline Entry retained once in the journal. */
public record TimelineEntry(
        ExactValue exactEvent,
        Optional<OperationDetails> operationDetails,
        ExternalOrderKey journalOrderKey,
        ExternalOrderKey sourceOrderKey,
        Timeline timeline,
        long timestampMicros,
        long globalSequence,
        long timelineSequence) {
    /** Validates exact values and deterministic journal/source coordinates. */
    public TimelineEntry {
        exactEvent = Objects.requireNonNull(exactEvent, "exactEvent");
        operationDetails = Objects.requireNonNull(operationDetails, "operationDetails");
        journalOrderKey = Objects.requireNonNull(journalOrderKey, "journalOrderKey");
        sourceOrderKey = Objects.requireNonNull(sourceOrderKey, "sourceOrderKey");
        timeline = Objects.requireNonNull(timeline, "timeline");
        if (timestampMicros <= 0L) {
            throw new IllegalArgumentException("timestampMicros must be positive");
        }
        if (globalSequence <= 0L || timelineSequence <= 0L) {
            throw new IllegalArgumentException(
                    "journal sequences must be positive");
        }
    }

    /** Operation-specific metadata; absence identifies a general entry. */
    public record OperationDetails(String operation, String channel, Optional<ExactValue> request) {
        /** Requires real routing fields and preserves absent versus empty request. */
        public OperationDetails {
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
            request = Objects.requireNonNull(request, "request");
        }
    }

    /** Retains the operation-only constructor from the preceding RC. */
    public TimelineEntry(ExactValue exactEvent, Optional<ExactValue> request,
            ExternalOrderKey journalOrderKey, ExternalOrderKey sourceOrderKey,
            Timeline timeline, String operation, String channel,
            long timestampMicros, long globalSequence, long timelineSequence) {
        this(exactEvent, Optional.of(new OperationDetails(operation, channel, request)),
                journalOrderKey, sourceOrderKey, timeline, timestampMicros, globalSequence, timelineSequence);
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

    /** Operation request, empty for a general entry or an operation without a request. */
    public Optional<ExactValue> request() { return operationDetails.flatMap(OperationDetails::request); }

    /** Retains the accepted-base constructor for entries with a present exact request. */
    public TimelineEntry(
            ExactValue exactEvent, ExactValue exactRequest,
            ExternalOrderKey journalOrderKey, ExternalOrderKey sourceOrderKey,
            Timeline timeline, String operation, String channel,
            long timestampMicros, long globalSequence, long timelineSequence) {
        this(exactEvent, Optional.of(Objects.requireNonNull(exactRequest, "exactRequest")),
                journalOrderKey, sourceOrderKey, timeline, operation, channel,
                timestampMicros, globalSequence, timelineSequence);
    }

    /**
     * Accepted-base accessor for a present request. Use {@link #request()} to
     * distinguish an absent request from semantic {@code {}}.
     *
     * @throws java.util.NoSuchElementException if this entry has no request
     */
    public ExactValue exactRequest() {
        return request().orElseThrow();
    }

    /** Returns the exact content identity of the retained event. */
    public String blueId() {
        return exactEvent.blueId();
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

}
