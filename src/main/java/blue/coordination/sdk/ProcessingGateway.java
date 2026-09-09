package blue.coordination.sdk;

import java.util.Objects;

/** Explicit canonical processing boundary for submitted work. */
public final class ProcessingGateway {
    private final SdkCoordinationRuntime runtime;

    ProcessingGateway(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Processes one supplied exact input in the selected root's forward view.
     * Requires the rooted checkpoint profile and a caller-established ordered
     * input window. It leaves independent roots available for separate processing.
     */
    public DrainResult process(DocumentHandle root, EntryHandle input) {
        return runtime.processRootInput(Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(input, "input"));
    }

    /** Processes one root input with an explicit, immutable shared gas budget. */
    public DrainResult process(DocumentHandle root, EntryHandle input,
            blue.coordination.api.ContractsExecutionPolicy policy) {
        return runtime.processRootInput(Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(input, "input"), Objects.requireNonNull(policy, "policy"));
    }

    /** Processes one earliest pending LIVE or retained input in the selected root's exact view. */
    public DrainResult processNext(DocumentHandle root) {
        return runtime.processNextRoot(Objects.requireNonNull(root, "root"));
    }

    /** Drains all currently eligible work to a safe frontier. */
    public DrainResult drain() {
        return runtime.drain();
    }

    /** Drains eligible work without exceeding the supplied deterministic limits. */
    public DrainResult drain(DrainBudget budget) {
        return runtime.drain(Objects.requireNonNull(budget, "budget"));
    }

    /**
     * Drains at most one ordinary journal selection when it owns the next
     * fair turn. This call never falls through to managed-epoch work; the
     * supplied budget controls only the admitted commit bound.
     */
    public DrainResult drainJournal(DrainBudget budget) {
        return runtime.drainJournal(
                Objects.requireNonNull(budget, "budget"));
    }

    /**
     * Applies one exact managed work item after atomically revalidating that
     * it owns the next fair bounded turn.
     */
    public DrainResult drainManagedEpochApplication(
            String expectedWorkIdentity) {
        return runtime.drainManagedEpochApplication(
                SdkPreconditions.requireText(
                        expectedWorkIdentity, "expectedWorkIdentity"));
    }
}
