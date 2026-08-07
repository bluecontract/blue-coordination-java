package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorStatus;

import java.util.Objects;

/** Complete immutable semantic and physical projection of one PROCESS call. */
public final class CoordinationTransition {

    private final CoordinationProcessingPlan plan;
    private final PlatformProcessingResult platformResult;
    private final CoordinationFragmentTransition fragmentTransition;
    private final CoordinationSubscriptionUpdate subscriptionUpdate;
    private final CoordinationAtomicCommitPlan commitPlan;
    private final LocalityDiagnostics locality;

    public CoordinationTransition(
            CoordinationProcessingPlan plan,
            PlatformProcessingResult platformResult,
            CoordinationFragmentTransition fragmentTransition,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            CoordinationAtomicCommitPlan commitPlan,
            LocalityDiagnostics locality) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.platformResult = Objects.requireNonNull(
                platformResult, "platformResult");
        this.fragmentTransition = Objects.requireNonNull(
                fragmentTransition, "fragmentTransition");
        this.subscriptionUpdate = Objects.requireNonNull(
                subscriptionUpdate, "subscriptionUpdate");
        this.commitPlan = Objects.requireNonNull(commitPlan, "commitPlan");
        this.locality = Objects.requireNonNull(locality, "locality");
        if (platformResult.processResult() != commitPlan.processResult()
                || platformResult.commitCompanion()
                        != commitPlan.commitCompanion()
                || fragmentTransition != commitPlan.fragmentTransition()
                || subscriptionUpdate != commitPlan.subscriptionUpdate()) {
            throw new IllegalArgumentException(
                    "Transition commit plan must retain the exact platform, "
                            + "fragment, and subscription results");
        }
    }

    public CoordinationProcessingPlan plan() { return plan; }
    public PlatformProcessingResult platformResult() { return platformResult; }
    public CoordinationFragmentTransition fragmentTransition() {
        return fragmentTransition;
    }
    public CoordinationSubscriptionUpdate subscriptionUpdate() {
        return subscriptionUpdate;
    }
    public CoordinationAtomicCommitPlan commitPlan() { return commitPlan; }
    public LocalityDiagnostics locality() { return locality; }
    public ProcessorStatus status() {
        return platformResult.processResult().status();
    }
    public String beforeRootBlueId() {
        return plan.session().currentRootBlueId();
    }
    public String afterRootBlueId() {
        return commitPlan.resultingRootBlueId();
    }
    public long beforeEpoch() { return plan.session().currentEpoch(); }
    public long afterEpoch() { return commitPlan.resultingEpoch(); }
    public boolean commitEligible() {
        // DocumentProcessingResult represents only completed PROCESS
        // statuses. Non-success results commit revision-bound progress rather
        // than a new Root/outbox.
        return true;
    }
}
