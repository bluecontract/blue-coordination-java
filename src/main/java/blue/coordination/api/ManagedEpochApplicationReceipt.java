package blue.coordination.api;

import blue.language.identity.BlueIds;

import java.util.Map;
import java.util.Objects;

/** Immutable response-loss-safe receipt for one committed catch-up step. */
public final class ManagedEpochApplicationReceipt {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-epoch-application-receipt/1.0";

    private final String applicationReceiptIdentity;
    private final String workIdentity;
    private final String planIdentity;
    private final String sourceReceiptIdentity;
    private final String contractsInvocationIdentity;
    private final String contractsResultIdentity;
    private final String commitCompanionIdentity;
    private final DocumentId consumerDocumentId;
    private final long consumerRevisionEpoch;
    private final String consumerRevisionReceiptIdentity;
    private final String consumerCommittedBlueId;
    private final long resultingSourceCursor;

    /** Creates and verifies one exact immutable application receipt. */
    public ManagedEpochApplicationReceipt(
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
        this.workIdentity = ManagedIdentity.requireSha256(
                workIdentity, "workIdentity");
        this.planIdentity = ManagedIdentity.requireSha256(
                planIdentity, "planIdentity");
        this.sourceReceiptIdentity = ManagedIdentity.requireSha256(
                sourceReceiptIdentity, "sourceReceiptIdentity");
        this.contractsInvocationIdentity = ManagedIdentity.requireSha256(
                contractsInvocationIdentity, "contractsInvocationIdentity");
        this.contractsResultIdentity = ManagedIdentity.requireSha256(
                contractsResultIdentity, "contractsResultIdentity");
        this.commitCompanionIdentity = ManagedIdentity.requireSha256(
                commitCompanionIdentity, "commitCompanionIdentity");
        this.consumerDocumentId = Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId");
        this.consumerRevisionEpoch = ManagedIdentity.requireSafeInteger(
                consumerRevisionEpoch, "consumerRevisionEpoch");
        this.consumerRevisionReceiptIdentity = ManagedIdentity.requireSha256(
                consumerRevisionReceiptIdentity,
                "consumerRevisionReceiptIdentity");
        this.consumerCommittedBlueId = BlueIds.requireBlueIdOrCyclicMember(
                consumerCommittedBlueId, "/consumerCommittedBlueId");
        this.resultingSourceCursor = ManagedIdentity.requireSafeInteger(
                resultingSourceCursor, "resultingSourceCursor");
        this.applicationReceiptIdentity = ManagedIdentity.verify(
                applicationReceiptIdentity,
                IDENTITY_DOMAIN,
                identityValue(),
                "applicationReceiptIdentity");
    }

    /** Derives the canonical identity for one committed application. */
    public static ManagedEpochApplicationReceipt identified(
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
        Map<String, Object> value = identityValue(
                workIdentity,
                planIdentity,
                sourceReceiptIdentity,
                contractsInvocationIdentity,
                contractsResultIdentity,
                commitCompanionIdentity,
                consumerDocumentId,
                consumerRevisionEpoch,
                consumerRevisionReceiptIdentity,
                consumerCommittedBlueId,
                resultingSourceCursor);
        return new ManagedEpochApplicationReceipt(
                ManagedIdentity.identify(IDENTITY_DOMAIN, value),
                workIdentity,
                planIdentity,
                sourceReceiptIdentity,
                contractsInvocationIdentity,
                contractsResultIdentity,
                commitCompanionIdentity,
                consumerDocumentId,
                consumerRevisionEpoch,
                consumerRevisionReceiptIdentity,
                consumerCommittedBlueId,
                resultingSourceCursor);
    }

    public String applicationReceiptIdentity() {
        return applicationReceiptIdentity;
    }

    public String workIdentity() { return workIdentity; }

    public String planIdentity() { return planIdentity; }

    public String sourceReceiptIdentity() { return sourceReceiptIdentity; }

    public String contractsInvocationIdentity() {
        return contractsInvocationIdentity;
    }

    public String contractsResultIdentity() { return contractsResultIdentity; }

    public String commitCompanionIdentity() { return commitCompanionIdentity; }

    public DocumentId consumerDocumentId() { return consumerDocumentId; }

    public long consumerRevisionEpoch() { return consumerRevisionEpoch; }

    public String consumerRevisionReceiptIdentity() {
        return consumerRevisionReceiptIdentity;
    }

    public String consumerCommittedBlueId() {
        return consumerCommittedBlueId;
    }

    public long resultingSourceCursor() { return resultingSourceCursor; }

    private Map<String, Object> identityValue() {
        return identityValue(
                workIdentity,
                planIdentity,
                sourceReceiptIdentity,
                contractsInvocationIdentity,
                contractsResultIdentity,
                commitCompanionIdentity,
                consumerDocumentId,
                consumerRevisionEpoch,
                consumerRevisionReceiptIdentity,
                consumerCommittedBlueId,
                resultingSourceCursor);
    }

    private static Map<String, Object> identityValue(
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
        return ManagedIdentity.fields(
                "workIdentity", workIdentity,
                "planIdentity", planIdentity,
                "sourceReceiptIdentity", sourceReceiptIdentity,
                "contractsInvocationIdentity", contractsInvocationIdentity,
                "contractsResultIdentity", contractsResultIdentity,
                "commitCompanionIdentity", commitCompanionIdentity,
                "consumerDocumentId", consumerDocumentId.value(),
                "consumerRevisionEpoch", consumerRevisionEpoch,
                "consumerRevisionReceiptIdentity",
                consumerRevisionReceiptIdentity,
                "consumerCommittedBlueId", consumerCommittedBlueId,
                "resultingSourceCursor", resultingSourceCursor);
    }
}
