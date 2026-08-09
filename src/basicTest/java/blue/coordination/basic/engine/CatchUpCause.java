package blue.coordination.basic.engine;

import java.util.Objects;

/** Exact attachment transition that made historical child work newly relevant. */
public record CatchUpCause(
        DocumentId parentDocumentId,
        String attachmentEntryBlueId,
        String occurrencePath,
        long attachmentTimestampMicros) {
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

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
