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
    /** Exact content required by a declared static occurrence is unavailable. */
    NEEDS_RESOURCES,
    /** A cyclic exact member was supplied without its complete set proof. */
    MISSING_EXACT_VALUE_PROOF,
    /** A cyclic exact member body or supplied set proof failed verification. */
    INVALID_EXACT_VALUE_PROOF,
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
    /** A targeted bounded call disagreed with the retained fair selection. */
    PROCESSING_SELECTION_MISMATCH,
    /** Retained catch-up attempted to author an unsupported nested lineage. */
    UNSUPPORTED_NESTED_NEW_LINEAGE,
    /** The in-memory atomic publication boundary could not commit. */
    ATOMIC_COMMIT_FAILED,
    /** A parent attempted to mutate state owned by a managed child. */
    MANAGED_CHILD_OWNERSHIP_VIOLATION
}
