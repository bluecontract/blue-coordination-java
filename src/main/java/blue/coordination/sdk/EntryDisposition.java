package blue.coordination.sdk;

/** Stable terminal disposition of one appended SDK entry. */
public enum EntryDisposition {
    APPLIED,
    NO_MATCH,
    STALE,
    MIXED,
    REJECTED,
    NEEDS_RESOURCES,
    GAS_LIMIT_EXCEEDED,
    PORTABLE_LIMIT_EXCEEDED,
    BLOCKED
}
