package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only observation of one previously emitted source prerequisite's logical authority.
 * This value is not a source publication receipt or permission to execute a stale selection.
 *
 * @param status whether the unchanged requester still needs source work, is satisfied, or is stale
 * @param pending fresh exact one-step descriptor, present only for {@link Status#PENDING}
 */
public record SourceHistoryPrerequisiteObservation(Status status, Optional<SourceHistoryPrerequisite> pending) {
    /** Validates the closed observation shape. */
    public SourceHistoryPrerequisiteObservation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(pending, "pending");
        if ((status == Status.PENDING) != pending.isPresent())
            throw new IllegalArgumentException("Only a pending source observation carries a selection");
    }

    /** Closed outcomes; an absent or consumed requesting authority is never satisfaction. */
    public enum Status {
        /** The current descriptor names one source action or an explicit WAIT. */
        PENDING,
        /** The still-current requester has complete required source history below its frozen cutoff. */
        SATISFIED,
        /** Genuine requesting correlation is absent, changed, or already terminal. */
        STALE
    }
}
