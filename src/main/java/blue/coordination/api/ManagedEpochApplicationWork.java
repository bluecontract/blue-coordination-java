package blue.coordination.api;

import blue.language.identity.BlueIds;
import blue.language.model.wire.JsonPointer;

import java.util.Map;
import java.util.Objects;

/** One canonical work item applying one source epoch through one occurrence. */
public final class ManagedEpochApplicationWork {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-epoch-application-work/1.0";

    private final String workIdentity;
    private final String planIdentity;
    private final String barrierIdentity;
    private final String sourceReceiptIdentity;
    private final DocumentId sourceDocumentId;
    private final long sourceEpoch;
    private final DocumentId consumerDocumentId;
    private final String targetOccurrenceIdentity;
    private final String targetPath;
    private final long activationGeneration;
    private final long expectedConsumerCommittedEpoch;
    private final String expectedConsumerCommittedBlueId;
    private final long expectedGraphGeneration;

    /** Creates and verifies one exact immutable application work item. */
    public ManagedEpochApplicationWork(
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
        this.planIdentity = ManagedIdentity.requireSha256(
                planIdentity, "planIdentity");
        this.barrierIdentity = ManagedIdentity.requireSha256(
                barrierIdentity, "barrierIdentity");
        this.sourceReceiptIdentity = ManagedIdentity.requireSha256(
                sourceReceiptIdentity, "sourceReceiptIdentity");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.sourceEpoch = ManagedIdentity.requireSafeInteger(
                sourceEpoch, "sourceEpoch");
        this.consumerDocumentId = Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId");
        this.targetOccurrenceIdentity = ManagedIdentity.requireSha256(
                targetOccurrenceIdentity, "targetOccurrenceIdentity");
        this.targetPath = requireOccurrencePath(targetPath);
        this.activationGeneration = requirePositiveSafeInteger(
                activationGeneration, "activationGeneration");
        this.expectedConsumerCommittedEpoch = ManagedIdentity.requireSafeInteger(
                expectedConsumerCommittedEpoch,
                "expectedConsumerCommittedEpoch");
        this.expectedConsumerCommittedBlueId =
                BlueIds.requireBlueIdOrCyclicMember(
                        expectedConsumerCommittedBlueId,
                        "/expectedConsumerCommittedBlueId");
        this.expectedGraphGeneration = ManagedIdentity.requireSafeInteger(
                expectedGraphGeneration, "expectedGraphGeneration");
        this.workIdentity = ManagedIdentity.verify(
                workIdentity,
                IDENTITY_DOMAIN,
                identityValue(),
                "workIdentity");
    }

    /** Derives the exact canonical work identity. */
    public static ManagedEpochApplicationWork identified(
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
        Map<String, Object> value = identityValue(
                planIdentity,
                barrierIdentity,
                sourceReceiptIdentity,
                sourceDocumentId,
                sourceEpoch,
                consumerDocumentId,
                targetOccurrenceIdentity,
                targetPath,
                activationGeneration,
                expectedConsumerCommittedEpoch,
                expectedConsumerCommittedBlueId,
                expectedGraphGeneration);
        return new ManagedEpochApplicationWork(
                ManagedIdentity.identify(IDENTITY_DOMAIN, value),
                planIdentity,
                barrierIdentity,
                sourceReceiptIdentity,
                sourceDocumentId,
                sourceEpoch,
                consumerDocumentId,
                targetOccurrenceIdentity,
                targetPath,
                activationGeneration,
                expectedConsumerCommittedEpoch,
                expectedConsumerCommittedBlueId,
                expectedGraphGeneration);
    }

    public String workIdentity() { return workIdentity; }

    public String planIdentity() { return planIdentity; }

    public String barrierIdentity() { return barrierIdentity; }

    public String sourceReceiptIdentity() { return sourceReceiptIdentity; }

    public DocumentId sourceDocumentId() { return sourceDocumentId; }

    public long sourceEpoch() { return sourceEpoch; }

    public DocumentId consumerDocumentId() { return consumerDocumentId; }

    public String targetOccurrenceIdentity() {
        return targetOccurrenceIdentity;
    }

    public String targetPath() { return targetPath; }

    public long activationGeneration() { return activationGeneration; }

    public long expectedConsumerCommittedEpoch() {
        return expectedConsumerCommittedEpoch;
    }

    public String expectedConsumerCommittedBlueId() {
        return expectedConsumerCommittedBlueId;
    }

    public long expectedGraphGeneration() { return expectedGraphGeneration; }

    private Map<String, Object> identityValue() {
        return identityValue(
                planIdentity,
                barrierIdentity,
                sourceReceiptIdentity,
                sourceDocumentId,
                sourceEpoch,
                consumerDocumentId,
                targetOccurrenceIdentity,
                targetPath,
                activationGeneration,
                expectedConsumerCommittedEpoch,
                expectedConsumerCommittedBlueId,
                expectedGraphGeneration);
    }

    private static Map<String, Object> identityValue(
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
        return ManagedIdentity.fields(
                "planIdentity", planIdentity,
                "barrierIdentity", barrierIdentity,
                "sourceReceiptIdentity", sourceReceiptIdentity,
                "sourceDocumentId", sourceDocumentId.value(),
                "sourceEpoch", sourceEpoch,
                "consumerDocumentId", consumerDocumentId.value(),
                "targetOccurrenceIdentity", targetOccurrenceIdentity,
                "targetPath", requireOccurrencePath(targetPath),
                "activationGeneration", activationGeneration,
                "expectedConsumerCommittedEpoch",
                expectedConsumerCommittedEpoch,
                "expectedConsumerCommittedBlueId",
                expectedConsumerCommittedBlueId,
                "expectedGraphGeneration", expectedGraphGeneration);
    }

    private static String requireOccurrencePath(String value) {
        String canonical = JsonPointer.canonicalize(
                ManagedIdentity.requireText(value, "targetPath"));
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "targetPath must be a non-root JSON Pointer");
        }
        return canonical;
    }

    private static long requirePositiveSafeInteger(long value, String label) {
        ManagedIdentity.requireSafeInteger(value, label);
        if (value == 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }
}
