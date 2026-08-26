package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/**
 * Immutable admission evidence for one managed occurrence in a closure.
 *
 * <p>This is read-only metadata about evidence already accepted by the
 * Coordination compiler. It does not let a host author or override graph
 * state.</p>
 */
public record ClosureOccurrenceSnapshot(
        DocumentId sourceDocumentId,
        String sourcePath,
        long activationGeneration,
        DocumentId targetDocumentId,
        String expectedTargetBlueId,
        boolean active) {

    public ClosureOccurrenceSnapshot {
        sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        sourcePath = SdkPreconditions.requireText(sourcePath, "sourcePath");
        if (!sourcePath.startsWith("/") || "/".equals(sourcePath)) {
            throw new IllegalArgumentException(
                    "sourcePath must be an absolute embedded path");
        }
        if (activationGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "activationGeneration must be positive");
        }
        targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        expectedTargetBlueId = SdkPreconditions.requireText(
                expectedTargetBlueId, "expectedTargetBlueId");
    }
}
