package blue.coordination.processor.support;

/**
 * Frozen Coordination 1.0 portable {@code PROCESS} limits.
 *
 * <p>The values are mirrored from the bundled
 * {@code coordination-gas-1.0.yaml}. Processing-time limits and counters are
 * enforced through the processor-owned Language runtime work session;
 * preparation-only splitter and Mandate quotas are declared separately by
 * the public host-quota facade.</p>
 */
public final class CoordinationRuntimeLimitsSupport {
    public static final int MAX_COMPOSITE_MEMBERS = 1024;
    public static final int MAX_ALL_TIMELINES_MEMBERS = 4096;
    public static final int MAX_WORKFLOW_STEPS = 4096;
    public static final int MAX_OPERATION_CANDIDATES_PER_CHANNEL = 4096;
    public static final long MAX_COORDINATION_RUNTIME_GAS_PER_PROCESS =
            100_000L;

    private CoordinationRuntimeLimitsSupport() {
    }
}
