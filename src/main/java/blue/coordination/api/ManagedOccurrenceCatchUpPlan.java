package blue.coordination.api;

import blue.language.identity.BlueIds;
import blue.language.model.wire.JsonPointer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable audit snapshot of one occurrence-specific managed catch-up plan. */
public final class ManagedOccurrenceCatchUpPlan {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-catch-up-plan/1.0";
    private static final String SNAPSHOT_IDENTITY_DOMAIN =
            "blue-coordination-managed-catch-up-plan-snapshot/1.0";

    private final String planIdentity;
    private final String snapshotIdentity;
    private final String barrierIdentity;
    private final DocumentId consumerDocumentId;
    private final String targetOccurrenceIdentity;
    private final String targetPath;
    private final long activationGeneration;
    private final DocumentId sourceDocumentId;
    private final long admittedSourceEpoch;
    private final String admittedSourceBlueId;
    private final long nextSourceEpoch;
    private final long requiredThroughSourceEpoch;
    private final String causedByIdentity;
    private final ManagedCatchUpStatus status;
    private final String waitingCode;
    private final String waitingMessage;

    /** Creates and verifies one exact immutable progress snapshot. */
    public ManagedOccurrenceCatchUpPlan(
            String planIdentity,
            String snapshotIdentity,
            String barrierIdentity,
            DocumentId consumerDocumentId,
            String targetOccurrenceIdentity,
            String targetPath,
            long activationGeneration,
            DocumentId sourceDocumentId,
            long admittedSourceEpoch,
            String admittedSourceBlueId,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            String causedByIdentity,
            ManagedCatchUpStatus status,
            String waitingCode,
            String waitingMessage) {
        this.barrierIdentity = ManagedIdentity.requireSha256(
                barrierIdentity, "barrierIdentity");
        this.consumerDocumentId = Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId");
        this.targetOccurrenceIdentity = ManagedIdentity.requireSha256(
                targetOccurrenceIdentity, "targetOccurrenceIdentity");
        this.targetPath = requireOccurrencePath(targetPath);
        this.activationGeneration = requirePositiveSafeInteger(
                activationGeneration, "activationGeneration");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.admittedSourceEpoch = ManagedIdentity.requireSafeEpoch(
                admittedSourceEpoch, "admittedSourceEpoch");
        this.admittedSourceBlueId = BlueIds.requireBlueIdOrCyclicMember(
                admittedSourceBlueId, "/admittedSourceBlueId");
        this.nextSourceEpoch = ManagedIdentity.requireSafeInteger(
                nextSourceEpoch, "nextSourceEpoch");
        this.requiredThroughSourceEpoch = ManagedIdentity.requireSafeInteger(
                requiredThroughSourceEpoch, "requiredThroughSourceEpoch");
        this.causedByIdentity = ManagedIdentity.requireSha256(
                causedByIdentity, "causedByIdentity");
        this.status = Objects.requireNonNull(status, "status");
        this.waitingCode = nullablePortableText(waitingCode, "waitingCode");
        this.waitingMessage = nullablePortableText(
                waitingMessage, "waitingMessage");
        requireProgress();
        this.planIdentity = ManagedIdentity.verify(
                planIdentity,
                IDENTITY_DOMAIN,
                definitionIdentityValue(),
                "planIdentity");
        this.snapshotIdentity = ManagedIdentity.verify(
                snapshotIdentity,
                SNAPSHOT_IDENTITY_DOMAIN,
                snapshotIdentityValue(),
                "snapshotIdentity");
    }

    /** Derives stable plan and exact progress-snapshot identities. */
    public static ManagedOccurrenceCatchUpPlan identified(
            String barrierIdentity,
            DocumentId consumerDocumentId,
            String targetOccurrenceIdentity,
            String targetPath,
            long activationGeneration,
            DocumentId sourceDocumentId,
            long admittedSourceEpoch,
            String admittedSourceBlueId,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            String causedByIdentity,
            ManagedCatchUpStatus status,
            String waitingCode,
            String waitingMessage) {
        Map<String, Object> definition = definitionIdentityValue(
                barrierIdentity,
                consumerDocumentId,
                targetOccurrenceIdentity,
                targetPath,
                activationGeneration,
                sourceDocumentId,
                admittedSourceEpoch,
                admittedSourceBlueId,
                causedByIdentity);
        String planIdentity = ManagedIdentity.identify(
                IDENTITY_DOMAIN, definition);
        Map<String, Object> snapshot = snapshotIdentityValue(
                planIdentity,
                barrierIdentity,
                nextSourceEpoch,
                requiredThroughSourceEpoch,
                status,
                waitingCode,
                waitingMessage);
        return new ManagedOccurrenceCatchUpPlan(
                planIdentity,
                ManagedIdentity.identify(SNAPSHOT_IDENTITY_DOMAIN, snapshot),
                barrierIdentity,
                consumerDocumentId,
                targetOccurrenceIdentity,
                targetPath,
                activationGeneration,
                sourceDocumentId,
                admittedSourceEpoch,
                admittedSourceBlueId,
                nextSourceEpoch,
                requiredThroughSourceEpoch,
                causedByIdentity,
                status,
                waitingCode,
                waitingMessage);
    }

    public String planIdentity() { return planIdentity; }

    public String snapshotIdentity() { return snapshotIdentity; }

    public String barrierIdentity() { return barrierIdentity; }

    public DocumentId consumerDocumentId() { return consumerDocumentId; }

    public String targetOccurrenceIdentity() {
        return targetOccurrenceIdentity;
    }

    public String targetPath() { return targetPath; }

    public long activationGeneration() { return activationGeneration; }

    public DocumentId sourceDocumentId() { return sourceDocumentId; }

    public long admittedSourceEpoch() { return admittedSourceEpoch; }

    public String admittedSourceBlueId() { return admittedSourceBlueId; }

    public long nextSourceEpoch() { return nextSourceEpoch; }

    public long requiredThroughSourceEpoch() {
        return requiredThroughSourceEpoch;
    }

    public String causedByIdentity() { return causedByIdentity; }

    public ManagedCatchUpStatus status() { return status; }

    public Optional<String> waitingCode() {
        return Optional.ofNullable(waitingCode);
    }

    public Optional<String> waitingMessage() {
        return Optional.ofNullable(waitingMessage);
    }

    private void requireProgress() {
        if (requiredThroughSourceEpoch < admittedSourceEpoch) {
            throw new IllegalArgumentException(
                    "required source epoch precedes admitted source state");
        }
        if (nextSourceEpoch <= admittedSourceEpoch
                || nextSourceEpoch > requiredThroughSourceEpoch + 1L) {
            throw new IllegalArgumentException(
                    "next source epoch is outside the admitted frontier");
        }
        boolean waiting = status == ManagedCatchUpStatus.WAITING_FOR_HISTORY
                || status == ManagedCatchUpStatus.BLOCKED;
        if (waiting != (waitingCode != null)) {
            throw new IllegalArgumentException(
                    "waiting and blocked plans require one exact waiting code");
        }
        if (waitingMessage != null && waitingCode == null) {
            throw new IllegalArgumentException(
                    "waitingMessage requires waitingCode");
        }
        if (status == ManagedCatchUpStatus.COMPLETE
                && nextSourceEpoch != requiredThroughSourceEpoch + 1L) {
            throw new IllegalArgumentException(
                    "A complete plan must be through its required frontier");
        }
    }

    private Map<String, Object> definitionIdentityValue() {
        return definitionIdentityValue(
                barrierIdentity,
                consumerDocumentId,
                targetOccurrenceIdentity,
                targetPath,
                activationGeneration,
                sourceDocumentId,
                admittedSourceEpoch,
                admittedSourceBlueId,
                causedByIdentity);
    }

    private static Map<String, Object> definitionIdentityValue(
            String barrierIdentity,
            DocumentId consumerDocumentId,
            String targetOccurrenceIdentity,
            String targetPath,
            long activationGeneration,
            DocumentId sourceDocumentId,
            long admittedSourceEpoch,
            String admittedSourceBlueId,
            String causedByIdentity) {
        return ManagedIdentity.fields(
                "barrierIdentity", barrierIdentity,
                "consumerDocumentId", consumerDocumentId.value(),
                "targetOccurrenceIdentity", targetOccurrenceIdentity,
                "targetPath", requireOccurrencePath(targetPath),
                "activationGeneration", activationGeneration,
                "sourceDocumentId", sourceDocumentId.value(),
                "admittedSourceEpoch", admittedSourceEpoch,
                "admittedSourceBlueId", admittedSourceBlueId,
                "causedByIdentity", causedByIdentity);
    }

    private Map<String, Object> snapshotIdentityValue() {
        return snapshotIdentityValue(
                planIdentity,
                barrierIdentity,
                nextSourceEpoch,
                requiredThroughSourceEpoch,
                status,
                waitingCode,
                waitingMessage);
    }

    private static Map<String, Object> snapshotIdentityValue(
            String planIdentity,
            String barrierIdentity,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            ManagedCatchUpStatus status,
            String waitingCode,
            String waitingMessage) {
        return ManagedIdentity.fields(
                "planIdentity", planIdentity,
                "barrierIdentity", barrierIdentity,
                "nextSourceEpoch", nextSourceEpoch,
                "requiredThroughSourceEpoch", requiredThroughSourceEpoch,
                "status", status.name(),
                "waitingCode", waitingCode,
                "waitingMessage", waitingMessage);
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

    private static String nullablePortableText(String value, String label) {
        return value == null ? null : ManagedIdentity.requireText(value, label);
    }
}
