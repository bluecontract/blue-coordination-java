package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;
import java.util.Optional;

/** One whole exact Timeline Entry retained once in the journal. */
public record TimelineEntry(
        ExactValue exactEvent,
        Optional<ExactValue> request,
        ExternalOrderKey journalOrderKey,
        ExternalOrderKey sourceOrderKey,
        Timeline timeline,
        String operation,
        String channel,
        long timestampMicros,
        long globalSequence,
        long timelineSequence) {
    /** Validates exact values and deterministic journal/source coordinates. */
    public TimelineEntry {
        exactEvent = Objects.requireNonNull(exactEvent, "exactEvent");
        request = Objects.requireNonNull(request, "request");
        journalOrderKey = Objects.requireNonNull(journalOrderKey, "journalOrderKey");
        sourceOrderKey = Objects.requireNonNull(sourceOrderKey, "sourceOrderKey");
        timeline = Objects.requireNonNull(timeline, "timeline");
        operation = requireText(operation, "operation");
        channel = requireText(channel, "channel");
        if (timestampMicros <= 0L) {
            throw new IllegalArgumentException("timestampMicros must be positive");
        }
        if (globalSequence <= 0L || timelineSequence <= 0L) {
            throw new IllegalArgumentException(
                    "journal sequences must be positive");
        }
    }

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
        return request.orElseThrow();
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
