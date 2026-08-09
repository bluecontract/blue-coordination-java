package blue.coordination.api;

/** Readiness of one managed document session. */
public enum SessionStatus {
    /** Authored state exists but initialization has not committed. */
    PENDING_INITIALIZATION,
    /** Historical child work is being integrated to a captured frontier. */
    CATCHING_UP,
    /** The document and active children are complete through their frontier. */
    READY,
    /** A required transition failed closed and needs explicit recovery. */
    BLOCKED,
    /** The document process has ended and accepts no further transitions. */
    TERMINATED
}
