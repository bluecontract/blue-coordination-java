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
    /** Host-supplied temporal policy evidence is missing or inconsistent. */
    INVALID_ACTIVATION_EVIDENCE,
    /** Frozen subscription evidence is inconsistent with the committed state. */
    INVALID_SUBSCRIPTION_EVIDENCE,
    /** A Process Embedded topology change would publish a cycle. */
    PROCESS_EMBEDDED_CYCLE,
    /** No managed document matches the supplied entry when one is required. */
    ROUTE_NOT_FOUND,
    /** Exact Timeline Entry validation or publication failed. */
    INVALID_TIMELINE_ENTRY,
    /** Language, Contracts, or BEX rejected frozen semantic processing. */
    FROZEN_PROCESSING_FAILED,
    /** The in-memory atomic publication boundary could not commit. */
    ATOMIC_COMMIT_FAILED,
    /** A parent attempted to mutate state owned by a managed child. */
    MANAGED_CHILD_OWNERSHIP_VIOLATION
}
