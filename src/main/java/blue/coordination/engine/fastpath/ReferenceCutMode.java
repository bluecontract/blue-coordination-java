package blue.coordination.engine.fastpath;

/**
 * Controls identity-equivalent sparse-Root execution at the frozen Contracts
 * boundary. No mode weakens provider checks or semantic verification.
 */
public enum ReferenceCutMode {
    /** Preserve the historical complete-Root representation. */
    DISABLED,

    /** Compile, identity-verify, cache, and execute the sparse representation. */
    VERIFIED,

    /**
     * Execute the sparse representation and require a caller-supplied
     * differential oracle to compare it with the complete representation.
     * Intended for tests and controlled performance qualification only.
     */
    SHADOW_DIFFERENTIAL
}
