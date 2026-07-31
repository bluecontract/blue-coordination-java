package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
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
        String reason;
        try {
            FrozenNode reasonNode =
                    FrozenNodeUtil.property(rawStep, "reason");
            if (reasonNode != null
                    && FrozenNodeUtil.rawScalar(reasonNode) == null) {
                throw new IllegalArgumentException(
                        "Expected Text scalar");
            }
            reason = FrozenNodeUtil.text(reasonNode);
        } catch (IllegalArgumentException exception) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    "Terminate Processing reason must be Text",
                    exception);
        }
        if (FrozenNodeUtil.property(rawStep, "cause") != null) {
            context.throwFatal(
                    "Terminate Processing does not accept an authored cause");
            return WorkflowStepResult.none();
        }
        context.processorContext().terminate(
                TerminateProcessing.blueId(),
                reason == null || reason.isEmpty()
                        ? null
                        : reason);
        if (metrics != null) {
            metrics.incrementDeclarativeTerminationSteps();
        }
        return WorkflowStepResult.terminal();
    }
}
