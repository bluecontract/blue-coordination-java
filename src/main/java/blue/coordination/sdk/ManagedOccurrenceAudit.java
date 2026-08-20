package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/** Immutable diagnostic view of one retained managed occurrence lineage. */
public record ManagedOccurrenceAudit(
        DocumentId targetDocumentId,
        long activationGeneration,
        boolean active) {
    public ManagedOccurrenceAudit {
        targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        if (activationGeneration < 1L) {
            throw new IllegalArgumentException(
                    "activationGeneration must be positive");
        }
    }
}
