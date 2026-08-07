package blue.coordination.engine.api;

/** Exhaustive atomic session-store commit conclusion. */
public enum CommitStatus {
    COMMITTED,
    ALREADY_COMMITTED,
    CONFLICT
}
