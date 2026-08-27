package blue.coordination.internal;

import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;

import java.util.Objects;

/**
 * Typed fail-closed result from opening one immutable managed source epoch.
 *
 * <p>This exception is raised only before Contracts processing begins. It
 * carries the exact durable work item so the store can atomically remove that
 * due row and publish the corresponding same-cursor plan/barrier state.</p>
 */
final class ManagedEpochEvidenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    static final String SOURCE_EPOCH_MISSING =
            "MANAGED_EPOCH_RECEIPT_MISSING";
    static final String TRANSITION_RECEIPT_MISSING =
            "MANAGED_TRANSITION_RECEIPT_MISSING";
    static final String SOURCE_DOCUMENT_MISMATCH =
            "MANAGED_EPOCH_SOURCE_DOCUMENT_MISMATCH";
    static final String SOURCE_EPOCH_MISMATCH =
            "MANAGED_EPOCH_NUMBER_MISMATCH";
    static final String BEFORE_BLUE_ID_MISMATCH =
            "MANAGED_EPOCH_BEFORE_BLUE_ID_MISMATCH";
    static final String AFTER_BLUE_ID_MISMATCH =
            "MANAGED_EPOCH_AFTER_BLUE_ID_MISMATCH";
    static final String ORIGINAL_CAUSE_MISMATCH =
            "MANAGED_EPOCH_ORIGINAL_CAUSE_MISMATCH";
    static final String EVENT_OCCURRENCE_MISMATCH =
            "MANAGED_EPOCH_EVENT_OCCURRENCE_MISMATCH";
    static final String PROCESSING_GAS_MISMATCH =
            "MANAGED_EPOCH_PROCESSING_GAS_MISMATCH";
    static final String RECEIPT_IDENTITY_MISMATCH =
            "MANAGED_EPOCH_RECEIPT_IDENTITY_MISMATCH";
    static final String CYCLIC_PROOF_MISSING =
            "MANAGED_EPOCH_CYCLIC_PROOF_MISSING";
    static final String CYCLIC_PROOF_UNAVAILABLE =
            "MANAGED_EPOCH_CYCLIC_PROOF_UNAVAILABLE";
    static final String CYCLIC_PROOF_INVALID =
            "MANAGED_EPOCH_CYCLIC_PROOF_INVALID";

    private final ManagedEpochApplicationWork work;
    private final ManagedCatchUpStatus planStatus;
    private final String code;

    private ManagedEpochEvidenceException(
            ManagedEpochApplicationWork work,
            ManagedCatchUpStatus planStatus,
            String code,
            String message) {
        super(Objects.requireNonNull(message, "message"));
        this.work = Objects.requireNonNull(work, "work");
        this.planStatus = Objects.requireNonNull(planStatus, "planStatus");
        if (planStatus != ManagedCatchUpStatus.WAITING_FOR_HISTORY
                && planStatus != ManagedCatchUpStatus.BLOCKED) {
            throw new IllegalArgumentException(
                    "Immutable-evidence failure must wait or block");
        }
        this.code = requireCode(code);
    }

    static ManagedEpochEvidenceException waiting(
            ManagedEpochApplicationWork work,
            String code,
            String message) {
        return new ManagedEpochEvidenceException(
                work, ManagedCatchUpStatus.WAITING_FOR_HISTORY, code, message);
    }

    static ManagedEpochEvidenceException blocked(
            ManagedEpochApplicationWork work,
            String code,
            String message) {
        return new ManagedEpochEvidenceException(
                work, ManagedCatchUpStatus.BLOCKED, code, message);
    }

    ManagedEpochApplicationWork work() {
        return work;
    }

    ManagedCatchUpStatus planStatus() {
        return planStatus;
    }

    String code() {
        return code;
    }

    String message() {
        return getMessage();
    }

    private static String requireCode(String value) {
        String selected = Objects.requireNonNull(value, "code");
        if (!selected.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Immutable-evidence code must use portable upper snake case");
        }
        return selected;
    }
}
