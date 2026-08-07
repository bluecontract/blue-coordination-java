package blue.coordination.engine.fastpath;

import java.util.Objects;

/**
 * Typed orchestration skeleton for one warm Root transition. The integration
 * adapter supplies immutable semantic operations; this class enforces a
 * single invocation and records every phase without replaying PROCESS.
 */
public final class WarmProcessKernel<P, O, S, A, C> {
    private final FastPathMetrics metrics;

    public WarmProcessKernel(FastPathMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public C execute(P plan, Steps<P, O, S, A, C> steps) {
        P checkedPlan = Objects.requireNonNull(plan, "plan");
        Steps<P, O, S, A, C> checked = Objects.requireNonNull(steps, "steps");
        PreparedProcessInput input = metrics.measure(
                FastPathMetrics.Phase.BUNDLE_BIND,
                () -> checked.bind(checkedPlan));
        O output = metrics.measure(
                FastPathMetrics.Phase.CONTRACTS_PROCESS,
                () -> checked.process(checkedPlan, input));
        O resolved = metrics.measure(
                FastPathMetrics.Phase.RETAINED_RESOLUTION,
                () -> checked.resolveRetained(checkedPlan, output));
        S subscriptions = metrics.measure(
                FastPathMetrics.Phase.PROJECTION,
                () -> checked.project(checkedPlan, resolved));
        A transition = metrics.measure(
                FastPathMetrics.Phase.TRANSITION,
                () -> checked.transition(
                        checkedPlan, resolved, subscriptions));
        return metrics.measure(
                FastPathMetrics.Phase.COMMIT,
                () -> checked.commit(
                        checkedPlan, resolved, subscriptions, transition));
    }

    public interface Steps<P, O, S, A, C> {
        PreparedProcessInput bind(P plan);
        O process(P plan, PreparedProcessInput input);
        O resolveRetained(P plan, O output);
        S project(P plan, O output);
        A transition(P plan, O output, S subscriptions);
        C commit(P plan, O output, S subscriptions, A transition);
    }
}
