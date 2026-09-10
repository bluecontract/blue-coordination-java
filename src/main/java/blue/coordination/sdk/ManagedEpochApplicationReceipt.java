package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;
import java.util.Optional;

/** Immutable SDK receipt for one committed managed epoch application. */
public record ManagedEpochApplicationReceipt(
        String applicationReceiptIdentity,
        String workIdentity,
        String planIdentity,
        String sourceReceiptIdentity,
        String contractsInvocationIdentity,
        String contractsResultIdentity,
        String commitCompanionIdentity,
        DocumentId consumerDocumentId,
        long consumerRevisionEpoch,
        String consumerRevisionReceiptIdentity,
        String consumerCommittedBlueId,
        long resultingSourceCursor,
        Optional<String> representationCauseIdentity,
        Optional<ManagedEpochApplicationWork.Position> resultingRepresentationCursor,
        Optional<String> successorRepresentationCauseIdentity) {

    /** Compatibility constructor for ordinary numbered epoch application receipts. */
    public ManagedEpochApplicationReceipt(String applicationReceiptIdentity, String workIdentity, String planIdentity, String sourceReceiptIdentity,
            String contractsInvocationIdentity, String contractsResultIdentity, String commitCompanionIdentity,
            DocumentId consumerDocumentId, long consumerRevisionEpoch, String consumerRevisionReceiptIdentity,
            String consumerCommittedBlueId, long resultingSourceCursor) {
        this(applicationReceiptIdentity, workIdentity, planIdentity, sourceReceiptIdentity, contractsInvocationIdentity, contractsResultIdentity, commitCompanionIdentity, consumerDocumentId, consumerRevisionEpoch, consumerRevisionReceiptIdentity, consumerCommittedBlueId, resultingSourceCursor, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Compatibility constructor retaining the existing applied-position receipt. */
    public ManagedEpochApplicationReceipt(String applicationReceiptIdentity, String workIdentity, String planIdentity, String sourceReceiptIdentity,
            String contractsInvocationIdentity, String contractsResultIdentity, String commitCompanionIdentity,
            DocumentId consumerDocumentId, long consumerRevisionEpoch, String consumerRevisionReceiptIdentity,
            String consumerCommittedBlueId, long resultingSourceCursor, Optional<String> representationCauseIdentity,
            Optional<ManagedEpochApplicationWork.Position> resultingRepresentationCursor) {
        this(applicationReceiptIdentity, workIdentity, planIdentity, sourceReceiptIdentity, contractsInvocationIdentity,
                contractsResultIdentity, commitCompanionIdentity, consumerDocumentId, consumerRevisionEpoch,
                consumerRevisionReceiptIdentity, consumerCommittedBlueId, resultingSourceCursor,
                representationCauseIdentity, resultingRepresentationCursor, Optional.empty());
    }

    /** Validates the complete immutable SDK projection. */
    public ManagedEpochApplicationReceipt {
        representationCauseIdentity = Objects.requireNonNull(representationCauseIdentity);
        resultingRepresentationCursor = Objects.requireNonNull(resultingRepresentationCursor);
        successorRepresentationCauseIdentity = Objects.requireNonNull(successorRepresentationCauseIdentity);
        successorRepresentationCauseIdentity.ifPresent(value -> text(value, "successorRepresentationCauseIdentity"));
        if (successorRepresentationCauseIdentity.isPresent()) {
            var cursor = resultingRepresentationCursor.orElseThrow(() ->
                    new IllegalArgumentException("Numbered successor receipt omits its anchor cursor"));
            if (representationCauseIdentity.isPresent() || !cursor.anchorReceiptIdentity().equals(sourceReceiptIdentity)
                    || !cursor.positionIdentity().equals(sourceReceiptIdentity)
                    || cursor.targetPositionIdentity().equals(sourceReceiptIdentity) || cursor.nextRevisionReceiptIdentity().isPresent()) {
                throw new IllegalArgumentException("Numbered successor receipt cannot claim an applied representation position");
            }
        }
        if (representationCauseIdentity.isEmpty() && successorRepresentationCauseIdentity.isEmpty() && resultingRepresentationCursor.isPresent()) {
            throw new IllegalArgumentException("Representation cursor requires its application cause");
        }
        applicationReceiptIdentity = text(
                applicationReceiptIdentity,
                "applicationReceiptIdentity");
        workIdentity = text(workIdentity, "workIdentity");
        planIdentity = text(planIdentity, "planIdentity");
        sourceReceiptIdentity = text(
                sourceReceiptIdentity, "sourceReceiptIdentity");
        contractsInvocationIdentity = text(
                contractsInvocationIdentity,
                "contractsInvocationIdentity");
        contractsResultIdentity = text(
                contractsResultIdentity, "contractsResultIdentity");
        commitCompanionIdentity = text(
                commitCompanionIdentity, "commitCompanionIdentity");
        consumerDocumentId = Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId");
        consumerRevisionReceiptIdentity = text(
                consumerRevisionReceiptIdentity,
                "consumerRevisionReceiptIdentity");
        consumerCommittedBlueId = text(
                consumerCommittedBlueId, "consumerCommittedBlueId");
        SdkPreconditions.requireNonNegative(
                consumerRevisionEpoch, "consumerRevisionEpoch");
        SdkPreconditions.requireNonNegative(
                resultingSourceCursor, "resultingSourceCursor");
    }

    private static String text(String value, String label) {
        return SdkPreconditions.requireText(value, label);
    }
}
