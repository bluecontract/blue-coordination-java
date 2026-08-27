package blue.coordination.sdk;

/** Deterministic SDK limits enforced between selected entries and commits. */
public record DrainBudget(
        long maxCommittedProcessTransitions,
        long maxSelectedEntries) {
    /** Validates strictly positive limits. */
    public DrainBudget {
        if (maxCommittedProcessTransitions <= 0L || maxSelectedEntries <= 0L) {
            throw new IllegalArgumentException("Drain limits must be positive");
        }
    }

    /** Returns an explicitly unbounded budget equivalent to {@code drain()}. */
    public static DrainBudget unlimited() {
        return new DrainBudget(Long.MAX_VALUE, Long.MAX_VALUE);
    }
}
