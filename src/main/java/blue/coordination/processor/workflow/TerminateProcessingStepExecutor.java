package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;

/** Buffers a graceful current-scope termination request. */
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
        context.processorContext().terminateGracefully(step.getReason());
        if (metrics != null) {
            metrics.incrementDeclarativeTerminationSteps();
        }
        return WorkflowStepResult.terminal();
    }
}
