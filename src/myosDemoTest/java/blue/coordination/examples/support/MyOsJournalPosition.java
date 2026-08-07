package blue.coordination.examples.support;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Monotonic host-journal position, independent of document identity. */
public record MyOsJournalPosition(
        long sequence,
        String entryBlueId,
        ExternalOrderKey orderKey)
        implements Comparable<MyOsJournalPosition> {

    public MyOsJournalPosition {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (Objects.requireNonNull(entryBlueId, "entryBlueId").isBlank()) {
            throw new IllegalArgumentException("entryBlueId must not be blank");
        }
        Objects.requireNonNull(orderKey, "orderKey");
    }

    @Override
    public int compareTo(MyOsJournalPosition other) {
        return Long.compare(sequence, Objects.requireNonNull(other).sequence);
    }
}
