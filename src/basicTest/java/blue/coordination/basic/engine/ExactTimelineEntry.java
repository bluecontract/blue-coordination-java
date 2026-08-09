package blue.coordination.basic.engine;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;
import java.util.Optional;

/** One whole exact Timeline Entry retained once in the journal. */
public record ExactTimelineEntry(
        ExactNodeValue exactEvent,
        ExactNodeValue exactRequest,
        ExternalOrderKey journalOrderKey,
        ExternalOrderKey sourceOrderKey,
        Timeline timeline,
        String operation,
        String channel,
        long timestampMicros,
        long globalSequence,
        long timelineSequence,
        EnvironmentFrontier appendFrontier,
        boolean processorManaged,
        DocumentId internalTarget,
        CatchUpCause catchUpCause) {
    public ExactTimelineEntry {
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
        appendFrontier = Objects.requireNonNull(
                appendFrontier, "appendFrontier");
        if (!appendFrontier.includesEntry(
                timeline.timelineId(), globalSequence, timelineSequence)) {
            throw new IllegalArgumentException(
                    "append frontier must include its Timeline Entry");
        }
        if (!processorManaged && internalTarget != null) {
            throw new IllegalArgumentException(
                    "Only processor-managed entries may carry an internal target");
        }
    }

    public String blueId() {
        return exactEvent.blueId();
    }

    public Optional<DocumentId> target() {
        return Optional.ofNullable(internalTarget);
    }

    public Optional<CatchUpCause> cause() {
        return Optional.ofNullable(catchUpCause);
    }

    public ExactTimelineEntry withCatchUpCause(CatchUpCause cause) {
        return new ExactTimelineEntry(
                exactEvent,
                exactRequest,
                journalOrderKey,
                sourceOrderKey,
                timeline,
                operation,
                channel,
                timestampMicros,
                globalSequence,
                timelineSequence,
                appendFrontier,
                processorManaged,
                internalTarget,
                Objects.requireNonNull(cause, "cause"));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
