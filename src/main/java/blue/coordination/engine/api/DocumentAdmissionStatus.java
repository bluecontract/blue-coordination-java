package blue.coordination.engine.api;

/** Exhaustive admission conclusion for one session registration. */
public enum DocumentAdmissionStatus {
    CREATED,
    ATTACHED_CURRENT,
    ATTACHED_TO_CURRENT,
    CONFLICT,
    FORK_REQUIRED,
    VERIFIED_LINEAGE_REQUIRED
}
