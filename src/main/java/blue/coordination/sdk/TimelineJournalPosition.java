package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;

/**
 * One coherent read of a Timeline head and the journal-wide maximum timestamp
 * (zero for an empty journal). Does not reserve an append or establish provider
 * completeness. Independent Timeline append order need not be timestamp order.
 */
public record TimelineJournalPosition(String timelineId, Optional<TimelineEntrySnapshot> head,
        long maximumTimestampMicros, long journalRevision) {
    public TimelineJournalPosition {
        timelineId = SdkPreconditions.requireText(timelineId, "timelineId");
        Objects.requireNonNull(head, "head");
        if (maximumTimestampMicros < 0 || journalRevision < 0)
            throw new IllegalArgumentException("Invalid journal position");
        if (head.isPresent() && (!head.orElseThrow().timeline().id().equals(timelineId)
                || head.orElseThrow().timestampMicros() > maximumTimestampMicros))
            throw new IllegalArgumentException("Timeline head differs from the journal position");
    }
}
