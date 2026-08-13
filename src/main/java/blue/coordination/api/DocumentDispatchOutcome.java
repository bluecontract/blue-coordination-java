package blue.coordination.api;

import java.util.Objects;

/** One managed document result selected by an external Timeline Entry. */
public record DocumentDispatchOutcome(
        DocumentId documentId,
        DocumentRevision revision,
        long totalNanos) {
    /** Validates the committed revision and non-negative duration. */
    public DocumentDispatchOutcome {
        documentId = Objects.requireNonNull(documentId, "documentId");
        revision = Objects.requireNonNull(revision, "revision");
        if (totalNanos < 0L) {
            throw new IllegalArgumentException(
                    "totalNanos must be non-negative");
        }
    }
}
