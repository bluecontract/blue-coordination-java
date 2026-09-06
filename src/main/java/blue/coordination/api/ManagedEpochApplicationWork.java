package blue.coordination.api;

import blue.language.identity.BlueIds;
import blue.language.processor.closure.ManagedRepresentationCause;
import java.util.Optional;
import java.util.LinkedHashMap;
import blue.language.model.wire.JsonPointer;

import java.util.Map;
import java.util.Objects;

/** One canonical managed-history item; representation steps have a separate identity domain. */
public final class ManagedEpochApplicationWork {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-epoch-application-work/1.0";

    private static final String REPRESENTATION_DOMAIN =
            "blue-coordination-managed-representation-application-work/1.0";
    private final ManagedRepresentationCause representationCause;
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
        this(workIdentity,
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
                expectedGraphGeneration, null);
    }

    private ManagedEpochApplicationWork(
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
            ManagedRepresentationCause representationCause) {
        this.representationCause = representationCause;
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
        if (representationCause != null && (representationCause.fromEpoch() != sourceEpoch
                || !representationCause.childDocumentId().value().equals(sourceDocumentId.value())
                || !representationCause.targetOccurrenceIdentity().equals(targetOccurrenceIdentity)
                || !representationCause.transition().anchorReceiptIdentity().equals(sourceReceiptIdentity))) {
            throw new IllegalArgumentException("Representation work must bind its exact source anchor and occurrence");
        }
        this.workIdentity = ManagedIdentity.verify(
                workIdentity,
                representationCause == null ? IDENTITY_DOMAIN : REPRESENTATION_DOMAIN,
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

    /** Constructs one separately identified same-epoch step, never an ordinary epoch work item. */
    public static ManagedEpochApplicationWork identifiedRepresentation(
            ManagedEpochApplicationWork coordinates, ManagedRepresentationCause cause) {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(cause, "cause");
        if (coordinates.isRepresentationApplication()) throw new IllegalArgumentException("Coordinates already carry representation work");
        Map<String, Object> value = new LinkedHashMap<>(coordinates.identityValue());
        value.put("representationCauseIdentity", cause.causeIdentity());
        return new ManagedEpochApplicationWork(ManagedIdentity.identify(REPRESENTATION_DOMAIN, value),
                coordinates.planIdentity(),
                coordinates.barrierIdentity(),
                coordinates.sourceReceiptIdentity(),
                coordinates.sourceDocumentId(),
                coordinates.sourceEpoch(),
                coordinates.consumerDocumentId(),
                coordinates.targetOccurrenceIdentity(),
                coordinates.targetPath(),
                coordinates.activationGeneration(),
                coordinates.expectedConsumerCommittedEpoch(),
                coordinates.expectedConsumerCommittedBlueId(),
                coordinates.expectedGraphGeneration(), cause);
    }

    public Optional<ManagedRepresentationCause> representationCause() { return Optional.ofNullable(representationCause); }
    public boolean isRepresentationApplication() { return representationCause != null; }
    /** The numbered receipt cursor owned by this step, which representation work does not advance. */
    public long expectedNextSourceEpoch() { return isRepresentationApplication() ? Math.addExact(sourceEpoch, 1L) : sourceEpoch; }

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
        Map<String, Object> value = new LinkedHashMap<>(identityValue(
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
                expectedGraphGeneration));
        if (representationCause != null) value.put("representationCauseIdentity", representationCause.causeIdentity());
        return value;
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
