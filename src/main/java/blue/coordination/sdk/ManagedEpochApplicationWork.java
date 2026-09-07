package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;
import java.util.Optional;

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
        long expectedGraphGeneration,
        Optional<RepresentationStep> representationStep) {

    /** Compatibility constructor for ordinary numbered epoch work. */
    public ManagedEpochApplicationWork(String workIdentity, String planIdentity, String barrierIdentity, String sourceReceiptIdentity,
            DocumentId sourceDocumentId, long sourceEpoch, DocumentId consumerDocumentId,
            String targetOccurrenceIdentity, String targetPath, long activationGeneration,
            long expectedConsumerCommittedEpoch, String expectedConsumerCommittedBlueId, long expectedGraphGeneration) {
        this(workIdentity, planIdentity, barrierIdentity, sourceReceiptIdentity, sourceDocumentId, sourceEpoch, consumerDocumentId, targetOccurrenceIdentity, targetPath, activationGeneration, expectedConsumerCommittedEpoch, expectedConsumerCommittedBlueId, expectedGraphGeneration, Optional.empty());
    }

    /** Exact historical position; the epoch alone cannot identify this progress. */
    public record Position(String anchorReceiptIdentity, String positionIdentity,
            String targetPositionIdentity, Optional<String> nextRevisionReceiptIdentity) {
        public Position {
            anchorReceiptIdentity = text(anchorReceiptIdentity, "anchorReceiptIdentity");
            positionIdentity = text(positionIdentity, "positionIdentity");
            targetPositionIdentity = text(targetPositionIdentity, "targetPositionIdentity");
            nextRevisionReceiptIdentity = Objects.requireNonNull(nextRevisionReceiptIdentity);
            nextRevisionReceiptIdentity.ifPresent(value -> text(value, "nextRevisionReceiptIdentity"));
        }
    }

    /** One explicitly identified same-epoch application with a captured completion target. */
    public record RepresentationStep(String causeIdentity, String beforeBlueId, String afterBlueId,
            Position before, Position after, boolean terminal) {
        public RepresentationStep {
            causeIdentity = text(causeIdentity, "causeIdentity");
            beforeBlueId = text(beforeBlueId, "beforeBlueId");
            afterBlueId = text(afterBlueId, "afterBlueId");
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (!before.anchorReceiptIdentity().equals(after.anchorReceiptIdentity())
                    || !before.targetPositionIdentity().equals(after.targetPositionIdentity())
                    || !before.nextRevisionReceiptIdentity().equals(after.nextRevisionReceiptIdentity())
                    || before.positionIdentity().equals(after.positionIdentity())
                    || terminal != (after.nextRevisionReceiptIdentity().isEmpty()
                        && after.positionIdentity().equals(after.targetPositionIdentity()))) {
                throw new IllegalArgumentException("Representation work must retain its anchor and captured target");
            }
        }
    }

    /** Validates the complete immutable SDK projection. */
    public ManagedEpochApplicationWork {
        representationStep = Objects.requireNonNull(representationStep, "representationStep");
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
