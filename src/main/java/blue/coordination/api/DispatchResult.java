package blue.coordination.api;

import java.util.List;
import java.util.Objects;

/** Immutable result of routing and publishing one exact Timeline Entry. */
public record DispatchResult(
        TimelineEntry entry,
        List<DocumentDispatchOutcome> outcomes,
        long elapsedNanos) {
    /** Defensively copies outcomes and validates the elapsed duration. */
    public DispatchResult {
        entry = Objects.requireNonNull(entry, "entry");
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "elapsedNanos must be non-negative");
        }
    }

    /** Returns the outcome when routing selected exactly one autonomous Root. */
    public DocumentDispatchOutcome onlyOutcome() {
        if (outcomes.size() != 1) {
            throw new CoordinationException(
                    CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                    "Expected one outcome but got " + outcomes.size());
        }
        return outcomes.get(0);
    }
}
