package blue.coordination.api;

/** Stable machine-readable failures from the supported in-memory engine. */
public enum CoordinationErrorCode {
    /** A stable document identity is already managed with different content. */
    DUPLICATE_DOCUMENT,
    /** The requested document identity is not managed. */
    DOCUMENT_NOT_FOUND,
    /** A transition requires a document whose catch-up is incomplete. */
    DOCUMENT_NOT_READY,
    /** Authored or referenced content violates exact identity rules. */
    INVALID_DOCUMENT_IDENTITY,
    /** The requested temporal activation mode is not implemented. */
    UNSUPPORTED_ACTIVATION_MODE,
    /** A transition would change parent route membership dynamically. */
    UNSUPPORTED_DYNAMIC_MEMBERSHIP,
    /** Process Embedded collections are outside this release's scope. */
    UNSUPPORTED_EMBEDDED_COLLECTION,
    /** No autonomous Root matches the supplied entry when one is required. */
    ROUTE_NOT_FOUND,
    /** Exact Timeline Entry validation or publication failed. */
    INVALID_TIMELINE_ENTRY,
    /** Language, Contracts, or BEX rejected frozen semantic processing. */
    FROZEN_PROCESSING_FAILED,
    /** The in-memory atomic publication boundary could not commit. */
    ATOMIC_COMMIT_FAILED,
    /** A parent attempted to mutate state owned by an autonomous child. */
    AUTONOMOUS_OWNERSHIP_VIOLATION
}
