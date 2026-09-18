package blue.coordination.sdk;

import blue.coordination.api.ProcessingStageContext;
import blue.coordination.internal.DefaultCoordinationEngine;

/** One scope-bound selection. Acquire its entry owners before execute; discard the scope on failure. */
public final class SelectedProcessingStage {
    final SdkCoordinationRuntime runtime;
    final DefaultCoordinationEngine.SelectedRootStage selected;
    SelectedProcessingStage(SdkCoordinationRuntime runtime, DefaultCoordinationEngine.SelectedRootStage selected) {
        this.runtime = runtime; this.selected = selected;
    }
    /** Exact accepted causes, topology bindings and complete known publication owners. */
    public ProcessingStageContext context() { return selected.context(); }
    /** Executes this frozen selection exactly once, without selecting subsequent work. */
    public ProcessingStageResult execute() { return runtime.executeSelectedStage(this); }
}
