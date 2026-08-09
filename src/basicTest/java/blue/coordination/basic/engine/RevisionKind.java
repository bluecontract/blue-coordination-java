package blue.coordination.basic.engine;

/** Cause of an exact committed document revision. */
public enum RevisionKind {
    INITIALIZATION,
    TIMELINE_ENTRY,
    EMBEDDED_REVISION_APPLICATION,
    CATCH_UP_COMPLETED
}
