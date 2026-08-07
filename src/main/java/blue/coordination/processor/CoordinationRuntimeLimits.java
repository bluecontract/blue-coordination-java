package blue.coordination.processor;

import blue.coordination.processor.support.CoordinationRuntimeLimitsSupport;

/**
 * Frozen Coordination 1.0 portable {@code PROCESS} limits.
 *
 * <p>This public compatibility facade delegates to the package-neutral
 * runtime catalog used by workflow execution. Keeping the catalog below both
 * packages prevents the API facade and workflow implementation from forming
 * a package cycle.</p>
 */
public final class CoordinationRuntimeLimits {
    public static final int MAX_COMPOSITE_MEMBERS =
            CoordinationRuntimeLimitsSupport.MAX_COMPOSITE_MEMBERS;
    public static final int MAX_ALL_TIMELINES_MEMBERS =
            CoordinationRuntimeLimitsSupport.MAX_ALL_TIMELINES_MEMBERS;
    public static final int MAX_WORKFLOW_STEPS =
            CoordinationRuntimeLimitsSupport.MAX_WORKFLOW_STEPS;
    public static final int MAX_OPERATION_CANDIDATES_PER_CHANNEL =
            CoordinationRuntimeLimitsSupport
                    .MAX_OPERATION_CANDIDATES_PER_CHANNEL;
    public static final long MAX_COORDINATION_RUNTIME_GAS_PER_PROCESS =
            CoordinationRuntimeLimitsSupport
                    .MAX_COORDINATION_RUNTIME_GAS_PER_PROCESS;

    private CoordinationRuntimeLimits() {
    }
}
