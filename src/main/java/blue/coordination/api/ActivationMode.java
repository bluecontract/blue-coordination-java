package blue.coordination.api;

/** Temporal semantics for a newly discovered Process Embedded occurrence. */
public enum ActivationMode {
    /** A new subprocess born at attachment time; no earlier history exists. */
    BIRTH_AT_ATTACHMENT,

    /** An existing process whose complete source history must be caught up. */
    IMPORT_FULL_HISTORY,

    /** Existing process starting after an explicitly persisted frontier. */
    IMPORT_FROM_FRONTIER,

    /** Ordinary immutable evidence; no initialization, replay, or live link. */
    PASSIVE_SNAPSHOT
}
