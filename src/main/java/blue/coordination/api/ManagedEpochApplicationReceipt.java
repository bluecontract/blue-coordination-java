package blue.coordination.api;

import blue.language.identity.BlueIds;
import blue.language.processor.closure.ManagedRepresentationCursor;
import java.util.Optional;
import java.util.LinkedHashMap;

import java.util.Map;
import java.util.Objects;

/** Immutable response-loss-safe receipt for one committed catch-up step. */
public final class ManagedEpochApplicationReceipt {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-epoch-application-receipt/1.0";

    private static final String REPRESENTATION_DOMAIN =
            "blue-coordination-managed-representation-application-receipt/1.0";
    private static final String SUCCESSOR_DOMAIN =
            "blue-coordination-managed-epoch-with-representation-successor-receipt/1.0";
    private final String representationCauseIdentity;
    private final String successorRepresentationCauseIdentity;
    private final ManagedRepresentationCursor resultingRepresentationCursor;
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
        this(applicationReceiptIdentity,
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
                resultingSourceCursor, null, null, null);
    }

    private ManagedEpochApplicationReceipt(
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
            String representationCauseIdentity, ManagedRepresentationCursor resultingRepresentationCursor,
            String successorRepresentationCauseIdentity) {
        this.representationCauseIdentity = representationCauseIdentity == null ? null
                : ManagedIdentity.requireSha256(representationCauseIdentity, "representationCauseIdentity");
        this.successorRepresentationCauseIdentity = successorRepresentationCauseIdentity == null ? null
                : ManagedIdentity.requireSha256(successorRepresentationCauseIdentity, "successorRepresentationCauseIdentity");
        this.resultingRepresentationCursor = resultingRepresentationCursor;
        if (representationCauseIdentity != null && successorRepresentationCauseIdentity != null) {
            throw new IllegalArgumentException("A numbered receipt cannot claim an applied representation cause");
        }
        if (successorRepresentationCauseIdentity != null && (resultingRepresentationCursor == null
                || !resultingRepresentationCursor.anchorReceiptIdentity().equals(sourceReceiptIdentity)
                || !resultingRepresentationCursor.positionIdentity().equals(sourceReceiptIdentity)
                || resultingRepresentationCursor.nextRevisionReceiptIdentity() != null)) {
            throw new IllegalArgumentException("A numbered successor receipt must stop at its immutable anchor");
        }
        if (representationCauseIdentity == null && successorRepresentationCauseIdentity == null && resultingRepresentationCursor != null) {
            throw new IllegalArgumentException("A positional cursor requires a representation application");
        }
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
                representationCauseIdentity != null ? REPRESENTATION_DOMAIN : successorRepresentationCauseIdentity != null ? SUCCESSOR_DOMAIN : IDENTITY_DOMAIN,
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

    /** Records exactly one applied representation position under its own receipt identity domain. */
    public static ManagedEpochApplicationReceipt identifiedRepresentation(
            ManagedEpochApplicationReceipt coordinates, ManagedEpochApplicationWork work,
            ManagedRepresentationCursor resultingCursor) {
        var cause = work.representationCause().orElseThrow();
        if (!coordinates.workIdentity().equals(work.workIdentity())) throw new IllegalArgumentException("Representation receipt belongs to different work");
        ManagedRepresentationCursor expected = cause.terminalPositionReached() ? null : new ManagedRepresentationCursor(
                cause.transition().anchorReceiptIdentity(), cause.transition().positionIdentity(),
                cause.targetPositionIdentity(), cause.nextRevisionReceiptIdentity());
        if (!Objects.equals(expected, resultingCursor)) throw new IllegalArgumentException("Representation application did not commit exactly its next position");
        Map<String, Object> value = new LinkedHashMap<>(coordinates.identityValue());
        value.put("representationCauseIdentity", cause.causeIdentity());
        value.put("resultingRepresentationCursor", resultingCursor == null ? null : resultingCursor.identityValue());
        return new ManagedEpochApplicationReceipt(ManagedIdentity.identify(REPRESENTATION_DOMAIN, value),
                coordinates.workIdentity(),
                coordinates.planIdentity(),
                coordinates.sourceReceiptIdentity(),
                coordinates.contractsInvocationIdentity(),
                coordinates.contractsResultIdentity(),
                coordinates.commitCompanionIdentity(),
                coordinates.consumerDocumentId(),
                coordinates.consumerRevisionEpoch(),
                coordinates.consumerRevisionReceiptIdentity(),
                coordinates.consumerCommittedBlueId(),
                coordinates.resultingSourceCursor(), cause.causeIdentity(), resultingCursor, null);
    }

    /** Records the numbered application at its anchor, before any successor position is executed. */
    public static ManagedEpochApplicationReceipt identifiedWithSuccessorRepresentationCause(
            ManagedEpochApplicationReceipt coordinates, ManagedEpochApplicationWork work,
            ManagedRepresentationCursor resultingCursor) {
        var successor = work.successorRepresentationCause().orElseThrow();
        if (!coordinates.workIdentity().equals(work.workIdentity())
                || coordinates.representationCauseIdentity().isPresent()
                || coordinates.successorRepresentationCauseIdentity().isPresent()
                || coordinates.resultingSourceCursor() != Math.addExact(work.sourceEpoch(), 1L)) {
            throw new IllegalArgumentException("Numbered successor receipt does not belong to its exact +1 work");
        }
        var expected = new ManagedRepresentationCursor(work.sourceReceiptIdentity(), work.sourceReceiptIdentity(),
                successor.targetPositionIdentity(), null);
        if (!expected.equals(resultingCursor)) {
            throw new IllegalArgumentException("Numbered application must retain the anchor, not consume its successor");
        }
        Map<String, Object> value = new LinkedHashMap<>(coordinates.identityValue());
        value.put("successorRepresentationCauseIdentity", successor.causeIdentity());
        value.put("resultingRepresentationCursor", resultingCursor.identityValue());
        return new ManagedEpochApplicationReceipt(ManagedIdentity.identify(SUCCESSOR_DOMAIN, value),
                coordinates.workIdentity(), coordinates.planIdentity(), coordinates.sourceReceiptIdentity(),
                coordinates.contractsInvocationIdentity(), coordinates.contractsResultIdentity(),
                coordinates.commitCompanionIdentity(), coordinates.consumerDocumentId(),
                coordinates.consumerRevisionEpoch(), coordinates.consumerRevisionReceiptIdentity(),
                coordinates.consumerCommittedBlueId(), coordinates.resultingSourceCursor(), null, resultingCursor,
                successor.causeIdentity());
    }

    /** Authenticated future step captured by this numbered application; not an applied source event. */
    public Optional<String> successorRepresentationCauseIdentity() {
        return Optional.ofNullable(successorRepresentationCauseIdentity);
    }

    public Optional<String> representationCauseIdentity() { return Optional.ofNullable(representationCauseIdentity); }
    public Optional<ManagedRepresentationCursor> resultingRepresentationCursor() { return Optional.ofNullable(resultingRepresentationCursor); }

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
        Map<String, Object> value = new LinkedHashMap<>(identityValue(
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
                resultingSourceCursor));
        if (representationCauseIdentity != null) {
            value.put("representationCauseIdentity", representationCauseIdentity);
            value.put("resultingRepresentationCursor", resultingRepresentationCursor == null ? null : resultingRepresentationCursor.identityValue());
        }
        if (successorRepresentationCauseIdentity != null) {
            value.put("successorRepresentationCauseIdentity", successorRepresentationCauseIdentity);
            value.put("resultingRepresentationCursor", resultingRepresentationCursor.identityValue());
        }
        return value;
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
