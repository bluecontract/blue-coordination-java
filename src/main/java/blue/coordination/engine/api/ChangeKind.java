package blue.coordination.engine.api;

/** Identity-derived state of one scope occurrence across a transition. */
public enum ChangeKind {
    ADDED,
    CHANGED,
    REMOVED,
    UNCHANGED
}
