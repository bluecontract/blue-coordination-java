package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/** Immutable SDK view of one occurrence-specific managed epoch work item. */
public record ManagedEpochApplicationWork(
        String workIdentity,
        String planIdentity,
        String barrierIdentity,
        String sourceReceiptIdentity,
        DocumentId sourceDocumentId,
        long sourceEpoch,
        DocumentId consumerDocumentId,
        String targetOccurrenceIdentity,
        String targetPath,
        long activationGeneration,
        long expectedConsumerCommittedEpoch,
        String expectedConsumerCommittedBlueId,
        long expectedGraphGeneration) {

    /** Validates the complete immutable SDK projection. */
    public ManagedEpochApplicationWork {
        workIdentity = text(workIdentity, "workIdentity");
        planIdentity = text(planIdentity, "planIdentity");
        barrierIdentity = text(barrierIdentity, "barrierIdentity");
        sourceReceiptIdentity = text(
                sourceReceiptIdentity, "sourceReceiptIdentity");
        sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        consumerDocumentId = Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId");
        targetOccurrenceIdentity = text(
                targetOccurrenceIdentity, "targetOccurrenceIdentity");
        targetPath = SdkPreconditions.requireOccurrencePath(targetPath);
        expectedConsumerCommittedBlueId = text(
                expectedConsumerCommittedBlueId,
                "expectedConsumerCommittedBlueId");
        SdkPreconditions.requireNonNegative(sourceEpoch, "sourceEpoch");
        SdkPreconditions.requireNonNegative(
                expectedConsumerCommittedEpoch,
                "expectedConsumerCommittedEpoch");
        SdkPreconditions.requireNonNegative(
                expectedGraphGeneration, "expectedGraphGeneration");
        if (activationGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "activationGeneration must be positive");
        }
    }

    private static String text(String value, String label) {
        return SdkPreconditions.requireText(value, label);
    }
}
