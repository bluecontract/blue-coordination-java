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
        Optional<ManagedEpochApplicationWork.Position> resultingRepresentationCursor) {

    /** Compatibility constructor for ordinary numbered epoch application receipts. */
    public ManagedEpochApplicationReceipt(String applicationReceiptIdentity, String workIdentity, String planIdentity, String sourceReceiptIdentity,
            String contractsInvocationIdentity, String contractsResultIdentity, String commitCompanionIdentity,
            DocumentId consumerDocumentId, long consumerRevisionEpoch, String consumerRevisionReceiptIdentity,
            String consumerCommittedBlueId, long resultingSourceCursor) {
        this(applicationReceiptIdentity, workIdentity, planIdentity, sourceReceiptIdentity, contractsInvocationIdentity, contractsResultIdentity, commitCompanionIdentity, consumerDocumentId, consumerRevisionEpoch, consumerRevisionReceiptIdentity, consumerCommittedBlueId, resultingSourceCursor, Optional.empty(), Optional.empty());
    }

    /** Validates the complete immutable SDK projection. */
    public ManagedEpochApplicationReceipt {
        representationCauseIdentity = Objects.requireNonNull(representationCauseIdentity);
        resultingRepresentationCursor = Objects.requireNonNull(resultingRepresentationCursor);
        if (representationCauseIdentity.isEmpty() && resultingRepresentationCursor.isPresent()) {
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
