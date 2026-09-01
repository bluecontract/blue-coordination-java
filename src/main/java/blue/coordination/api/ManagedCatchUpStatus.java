package blue.coordination.api;

/** Closed progress vocabulary for one occurrence-specific catch-up plan. */
public enum ManagedCatchUpStatus {
    /** Historical work has been admitted but not selected. */
    PENDING,
    /** At least one source epoch has been selected or applied. */
    RUNNING,
    /** The exact next immutable source receipt is not yet available. */
    WAITING_FOR_HISTORY,
    /** Invalid evidence or a permanent processing failure stopped the plan. */
    BLOCKED,
    /** Every required source epoch was applied through the occurrence. */
    COMPLETE,
    /** The owning occurrence generation retired before completion. */
    CANCELLED_OCCURRENCE_RETIRED;

    /** Returns whether no further application may occur in this plan. */
    public boolean terminal() {
        return this == BLOCKED || this == COMPLETE
                || this == CANCELLED_OCCURRENCE_RETIRED;
    }
}
