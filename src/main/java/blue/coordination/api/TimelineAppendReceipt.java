package blue.coordination.api;

import java.util.Objects;

/** Result of admitting one exact Timeline Entry to the environment journal. */
public record TimelineAppendReceipt(
        TimelineEntry entry,
        boolean stored,
        int journalEntryCount,
        long elapsedNanos) {
    /** Validates the immutable append evidence. */
    public TimelineAppendReceipt {
        entry = Objects.requireNonNull(entry, "entry");
        if (journalEntryCount < 1) {
            throw new IllegalArgumentException(
                    "journalEntryCount must be positive");
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "elapsedNanos must be non-negative");
        }
    }
}
