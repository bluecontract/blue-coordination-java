package blue.coordination.processor;

import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.repo.coordination.SequentialWorkflow;
import java.util.Collections;
import java.util.List;

/**
 * Executes a fixed Sequential Workflow handler against the current immutable
 * contract snapshot and shared processing invocation.
 */
public final class SequentialWorkflowProcessor implements HandlerProcessor<SequentialWorkflow> {
    private final SequentialWorkflowRunner runner;
    private final CoordinationSemanticTypeIdentities identities;

    public SequentialWorkflowProcessor() {
        this(new SequentialWorkflowRunner(),
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    public SequentialWorkflowProcessor(SequentialWorkflowRunner runner) {
        this(runner,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    public SequentialWorkflowProcessor(
            SequentialWorkflowRunner runner,
            CoordinationSemanticTypeIdentities identities) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        this.runner = runner;
        this.identities = java.util.Objects.requireNonNull(
                identities, "identities");
    }

    @Override
    public Class<SequentialWorkflow> contractType() {
        return SequentialWorkflow.class;
    }

    @Override
    public List<String> executableBodyFields() {
        return Collections.singletonList("steps");
    }

    @Override
    public String deriveChannel(SequentialWorkflow contract, HandlerRegistrationContext context) {
        return HandlerChannelResolver.resolve(
                contract != null
                        ? contract.getChannel()
                        : null,
                context);
    }

    @Override
    public boolean matches(SequentialWorkflow contract, HandlerMatchContext context) {
        return !CoordinationEventNodes
                .isRoutableOperationRequestForChannel(
                        context.occurrenceEvent(),
                        context.channelKey(),
                        context,
                        identities)
                && SequentialWorkflowEventMatcher.matches(
                        contract.getEvent(), context);
    }

    @Override
    public void execute(SequentialWorkflow contract, ProcessorExecutionContext context) {
        runner.execute(contract, context);
    }
}
