package blue.coordination.engine.api;

/** Host intent when an exact document is admitted or attached. */
public enum RegistrationMode {
    OPEN_OR_CREATE,
    CREATE_ONLY,
    ATTACH_EXISTING,
    FORK_FROM_EXACT_STATE
}
