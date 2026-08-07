package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationAtomicCommitPlan;

import java.util.Objects;

/**
 * Fully validated mutation handed to one authoritative compare-and-publish
 * call. All expensive identity/content work happens before the CAS lock.
 */
public final class PreparedAtomicCommit {
    private final CoordinationAtomicCommitPlan plan;
    private final FastFragmentDelta fragments;
    private final PreparedRootExecutionContext resultingContext;

    public PreparedAtomicCommit(
            CoordinationAtomicCommitPlan plan,
            FastFragmentDelta fragments,
            PreparedRootExecutionContext resultingContext) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.fragments = Objects.requireNonNull(fragments, "fragments");
        this.resultingContext = Objects.requireNonNull(
                resultingContext, "resultingContext");
        if (!plan.sessionId().value().equals(resultingContext.sessionId())
                || plan.resultingEpoch() != resultingContext.epoch()
                || !plan.resultingRootBlueId().equals(
                        resultingContext.rootBlueId())
                || !plan.fragmentTransition().resultingInventory()
                        .inventoryIdentity().equals(
                                fragments.inventory().inventoryIdentity())
                || !fragments.inventory().inventoryIdentity().equals(
                        resultingContext.inventoryIdentity())) {
            throw new IllegalArgumentException(
                    "Prepared context does not bind commit result");
        }
    }

    public CoordinationAtomicCommitPlan plan() { return plan; }
    public FastFragmentDelta fragments() { return fragments; }
    public PreparedRootExecutionContext resultingContext() {
        return resultingContext;
    }
}
