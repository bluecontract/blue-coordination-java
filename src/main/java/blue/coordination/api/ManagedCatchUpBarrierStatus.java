package blue.coordination.api;

/** Aggregate readiness state for plans introduced by one exact cause. */
public enum ManagedCatchUpBarrierStatus {
    /** At least one member plan has work that may become runnable. */
    OPEN,
    /** At least one member is waiting for an immutable source receipt. */
    WAITING_FOR_HISTORY,
    /** One member failed closed. */
    BLOCKED,
    /** Every required member completed or was deterministically cancelled. */
    COMPLETE
}
