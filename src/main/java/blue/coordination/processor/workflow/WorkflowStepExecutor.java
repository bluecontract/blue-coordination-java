package blue.coordination.processor.workflow;

import blue.repo.coordination.SequentialWorkflowStep;

/**
 * Strategy for one supported fixed-repository Sequential Workflow step type.
 *
 * @param <T> concrete generated step model
 */
public interface WorkflowStepExecutor<T extends SequentialWorkflowStep> {
    boolean supports(SequentialWorkflowStep step);

    WorkflowStepResult execute(T step, StepExecutionContext context);
}
