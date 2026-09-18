package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A read-only authoring observation from one pinned journal view. This is not a
 * Timeline completeness guarantee, a timestamp policy or an append reservation.
 * A later append must still pass the normal predecessor and publication fences.
 */
public record TimelineJournalPosition(String timelineId, Optional<TimelineEntry> head,
        long maximumTimestampMicros, long journalRevision) {
    public TimelineJournalPosition {
        Objects.requireNonNull(timelineId, "timelineId");
        Objects.requireNonNull(head, "head");
        if (timelineId.isBlank() || maximumTimestampMicros < 0 || journalRevision < 0)
            throw new IllegalArgumentException("Invalid journal position");
        if (head.isPresent() && (!head.orElseThrow().timeline().timelineId().equals(timelineId)
                || head.orElseThrow().timestampMicros() > maximumTimestampMicros))
            throw new IllegalArgumentException("Timeline head differs from the journal position");
    }
}
