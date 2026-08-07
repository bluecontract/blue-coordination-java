package blue.coordination.engine.api;

/** Exhaustive removal conclusion for one revision-bound request. */
public enum DocumentRemovalStatus {
    REMOVED,
    ALREADY_REMOVED,
    NOT_FOUND,
    CONFLICT
}
