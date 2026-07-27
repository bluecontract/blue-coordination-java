package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TriggerEvent;

public final class TriggerEventStepExecutor implements WorkflowStepExecutor<TriggerEvent> {
    private final BexProcessingMetrics metrics;

    public TriggerEventStepExecutor() {
        this(null);
    }

    public TriggerEventStepExecutor(BexProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean supports(SequentialWorkflowStep step) {
        return step instanceof TriggerEvent;
    }

    @Override
    public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
        long stepStart = System.nanoTime();
        try {
            if (step == null) {
                context.processorContext().throwFatal("Trigger Event step payload is invalid");
                return WorkflowStepResult.none();
            }
            if (metrics != null) {
                metrics.incrementTriggerEventStepsExecuted();
            }
            FrozenNode rawStep = context.stepFrozenNode();
            Node event;
            if (rawStep != null) {
                if (rawStep.getProperties() == null
                        || !rawStep.getProperties().containsKey("event")) {
                    context.processorContext().throwFatal(
                            "Trigger Event step must declare event payload");
                    return WorkflowStepResult.none();
                }
                FrozenNode rawEvent =
                        rawStep.getProperties().get("event");
                if (rawEvent == null) {
                    context.processorContext().throwFatal(
                            "Trigger Event step must declare event payload");
                    return WorkflowStepResult.none();
                }
                event = rawEvent.toNode();
            } else if (step.getEvent() != null) {
                event = step.getEvent().clone();
            } else {
                context.processorContext().throwFatal("Trigger Event step must declare event payload");
                return WorkflowStepResult.none();
            }
            long emitStart = System.nanoTime();
            context.processorContext().emitEvent(event);
            if (metrics != null) {
                metrics.addTriggerEmitEventNanos(System.nanoTime() - emitStart);
            }
            return WorkflowStepResult.none();
        } finally {
            if (metrics != null) {
                metrics.addTriggerStepNanos(System.nanoTime() - stepStart);
            }
        }
    }

}
