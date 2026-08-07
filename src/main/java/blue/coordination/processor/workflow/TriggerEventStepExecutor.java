package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.coordination.processor.support.CoordinationProcessHeaderSupport;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TriggerEvent;

/**
 * Normalizes a fixed Trigger Event step and delegates event emission to the
 * parent Contracts processing boundary.
 */
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
                context.throwFatal("Trigger Event step payload is invalid");
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
                    context.throwFatal(
                            "Trigger Event step must declare event payload");
                    return WorkflowStepResult.none();
                } else {
                    FrozenNode rawEvent =
                            rawStep.getProperties().get("event");
                    if (rawEvent == null) {
                        context.throwFatal(
                                "Trigger Event step must declare event payload");
                        return WorkflowStepResult.none();
                    }
                    event = exactEvent(
                            rawEvent, step, context);
                }
            } else if (step.getEvent() != null) {
                event = step.getEvent().clone();
            } else {
                context.throwFatal("Trigger Event step must declare event payload");
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

    private Node exactEvent(
            FrozenNode rawEvent,
            TriggerEvent step,
            StepExecutionContext context) {
        Node authored =
                FrozenNodeUtil.authoredOverlay(
                        rawEvent);
        Node resolved =
                step.getEvent();
        if (!authored.isReferenceOnly()
                || resolved == null
                || resolved.isReferenceOnly()) {
            return authored;
        }
        Node exactResolved =
                CoordinationProcessHeaderSupport
                        .canonicalExactCopy(resolved);
        String calculated =
                DirectBlueIdCalculator.calculateBlueId(
                        exactResolved);
        if (!authored.getBlueId().equals(calculated)) {
            context.throwFatal(
                    "Trigger Event selected payload identity changed");
            return authored;
        }
        return exactResolved;
    }

}
