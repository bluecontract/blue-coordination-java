package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

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
        long resultingSourceCursor) {

    /** Validates the complete immutable SDK projection. */
    public ManagedEpochApplicationReceipt {
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
