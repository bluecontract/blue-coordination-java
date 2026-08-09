package blue.coordination.basic.engine;

/** Readiness of one managed document session. */
public enum SessionStatus {
    PENDING_INITIALIZATION,
    CATCHING_UP,
    READY,
    BLOCKED,
    TERMINATED
}
