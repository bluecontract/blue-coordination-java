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

    /** Processes one earliest pending LIVE or retained input in the selected root's exact view. */
    public DrainResult processNext(DocumentHandle root) {
        return runtime.processNextRoot(Objects.requireNonNull(root, "root"));
    }

    /**
     * Executes the supplied accepted input and materializes its current result,
     * without selecting later work. Requires a caller-established ordered window.
     * No input is appended by this call. Host preparation/publication is separate.
     * @param root authoritative root in this runtime
     * @param input exact already accepted input in this runtime
     * @return current-stage evidence without a quiescence/command-completion claim
     */
    public ProcessingStageResult processStage(DocumentHandle root, EntryHandle input) {
        return runtime.processRootStage(Objects.requireNonNull(root, "root"), Objects.requireNonNull(input, "input"));
    }

    /**
     * Selects and executes one earliest LIVE, retained-local or managed stage.
     * Materializes and retains its result before returning, with no readiness
     * lookahead. A throwing invocation requires discarding its mutable owner.
     * @param root authoritative root in this runtime
     * @return current-stage evidence, still subject to host publication
     */
    public ProcessingStageResult processNextStage(DocumentHandle root) {
        return runtime.processNextRootStage(Objects.requireNonNull(root, "root"));
    }

    /** Freezes one root's next stage; acquire context entry owners before executing the returned token. */
    public SelectedProcessingStage selectNextStage(DocumentHandle root) {
        return runtime.selectStage(Objects.requireNonNull(root), null);
    }

    /** Freezes the supplied accepted input without appending or looking ahead. */
    public SelectedProcessingStage selectStage(DocumentHandle root, EntryHandle input) {
        return runtime.selectStage(Objects.requireNonNull(root), Objects.requireNonNull(input));
    }

    /**
     * Freezes the next causal root stage through an accepted input's full external order,
     * including earlier local/managed prerequisites. A NO_WORK result refers to this
     * cutoff only; later accepted input remains for its own continuation.
     * @param root authoritative processing root
     * @param inclusiveEntry original accepted command cutoff
     * @return one frozen stage, without processing or future-readiness lookahead
     */
    public SelectedProcessingStage selectNextStageThrough(DocumentHandle root, EntryHandle inclusiveEntry) {
        return runtime.selectStageThrough(Objects.requireNonNull(root), Objects.requireNonNull(inclusiveEntry));
    }
    /**
     * Restores the cutoff by its retained exact entry identity in a cold owner.
     * This is a point lookup, never submission or authorship.
     * @param root authoritative processing root
     * @param inclusiveEntryBlueId already accepted cutoff entry
     * @return one frozen stage under the original cutoff
     */
    public SelectedProcessingStage selectNextStageThrough(DocumentHandle root, String inclusiveEntryBlueId) {
        return runtime.selectStageThrough(Objects.requireNonNull(root), runtime.lightweightHandle(
                SdkPreconditions.requireText(inclusiveEntryBlueId, "inclusiveEntryBlueId")));
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
