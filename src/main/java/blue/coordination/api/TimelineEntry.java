package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;
import java.util.Optional;

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
        long timelineSequence,
        EnvironmentFrontier appendFrontier,
        boolean processorManaged,
        DocumentId internalTarget,
        TimelineEntry.CatchUpCause catchUpCause) {
    /** Validates exact values, order keys, frontier, and internal targeting. */
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

    /** Returns the exact content identity of the retained event. */
    public String blueId() {
        return exactEvent.blueId();
    }

    /** Returns an internal target only for processor-managed transitions. */
    public Optional<DocumentId> target() {
        return Optional.ofNullable(internalTarget);
    }

    /** Returns attachment evidence when this is a catch-up entry. */
    public Optional<TimelineEntry.CatchUpCause> cause() {
        return Optional.ofNullable(catchUpCause);
    }

    /** Returns an immutable copy enriched with attachment cause evidence. */
    public TimelineEntry withCatchUpCause(TimelineEntry.CatchUpCause cause) {
        return new TimelineEntry(
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

    /** Exact attachment transition that made historical work relevant. */
    public record CatchUpCause(
            DocumentId parentDocumentId,
            String attachmentEntryBlueId,
            String occurrencePath,
            long attachmentTimestampMicros) {
        /** Validates stable parent, entry, occurrence, and time evidence. */
        public CatchUpCause {
            parentDocumentId = Objects.requireNonNull(
                    parentDocumentId, "parentDocumentId");
            attachmentEntryBlueId = requireText(
                    attachmentEntryBlueId, "attachmentEntryBlueId");
            occurrencePath = requireText(occurrencePath, "occurrencePath");
            if (attachmentTimestampMicros <= 0L) {
                throw new IllegalArgumentException(
                        "attachmentTimestampMicros must be positive");
            }
        }
    }
}
