package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Actual evidence from one source action; it never reports the waiting parent as applied.
 * @param selection exact independently selected prerequisite
 * @param admission real source admission result, when that phase ran
 * @param processing real one-step source processing result, for LIVE or retained work
 * @param replayed whether this response repeats an already returned committing result
 */
public record SourceHistoryPrerequisiteResult(SourceHistoryPrerequisite selection,
        Optional<ContractsClosureAdmissionReceipt> admission, Optional<ProcessingDrainReceipt> processing,
        boolean replayed) {
    /** Requires exactly one actual result and forbids executing a wait. */
    public SourceHistoryPrerequisiteResult {
        Objects.requireNonNull(selection); Objects.requireNonNull(admission); Objects.requireNonNull(processing);
        if (selection.kind() == SourceHistoryPrerequisite.Kind.WAIT || admission.isPresent() == processing.isPresent()
                || (selection.kind() == SourceHistoryPrerequisite.Kind.ADMISSION) != admission.isPresent()) {
            throw new IllegalArgumentException("Source prerequisite result does not match its single phase");
        }
    }
}
