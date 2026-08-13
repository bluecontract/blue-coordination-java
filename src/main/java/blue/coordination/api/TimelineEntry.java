package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** One whole exact Timeline Entry retained once in the journal. */
public record TimelineEntry(
        ExactValue exactEvent,
        ExactValue exactRequest,
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
        exactRequest = Objects.requireNonNull(exactRequest, "exactRequest");
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
