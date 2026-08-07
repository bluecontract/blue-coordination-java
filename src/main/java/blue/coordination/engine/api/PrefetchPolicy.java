package blue.coordination.engine.api;

/** Physical loading policy; it never changes PROCESS semantics or gas. */
public enum PrefetchPolicy {
    MINIMUM_BYTES,
    BALANCED,
    MINIMUM_ROUND_TRIPS
}
