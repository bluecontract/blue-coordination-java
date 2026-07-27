package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;

/** Buffers an application-caused successful current-scope termination request. */
public final class TerminateProcessingStepExecutor implements WorkflowStepExecutor<TerminateProcessing> {
    private final BexProcessingMetrics metrics;

    public TerminateProcessingStepExecutor() {
        this(null);
    }

    public TerminateProcessingStepExecutor(BexProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean supports(SequentialWorkflowStep step) {
        return step instanceof TerminateProcessing;
    }

    @Override
    public WorkflowStepResult execute(TerminateProcessing step, StepExecutionContext context) {
        FrozenNode rawStep = context.stepFrozenNode();
        String cause;
        String reason;
        try {
            cause = FrozenNodeUtil.textProperty(rawStep, "cause");
            reason = FrozenNodeUtil.textProperty(rawStep, "reason");
        } catch (IllegalArgumentException exception) {
            context.processorContext().throwFatal(
                    "Terminate Processing cause and reason must be Text");
            return WorkflowStepResult.none();
        }
        if (cause == null || cause.isEmpty()) {
            context.processorContext().throwFatal(
                    "Terminate Processing cause must be non-empty Text");
            return WorkflowStepResult.none();
        }
        context.processorContext().terminate(cause, reason);
        if (metrics != null) {
            metrics.incrementDeclarativeTerminationSteps();
        }
        return WorkflowStepResult.terminal();
    }
}
