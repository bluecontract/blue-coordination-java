package blue.coordination.processor;

import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import java.util.Collections;
import java.util.List;

/**
 * Executes a Sequential Workflow Operation selected through Operation Request
 * source-to-target routing.
 */
public final class SequentialWorkflowOperationProcessor implements HandlerProcessor<SequentialWorkflowOperation> {
    private final SequentialWorkflowRunner runner;
    private final OperationRequestMatcher matcher = new OperationRequestMatcher();

    public SequentialWorkflowOperationProcessor() {
        this(new SequentialWorkflowRunner());
    }

    public SequentialWorkflowOperationProcessor(SequentialWorkflowRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        this.runner = runner;
    }

    @Override
    public Class<SequentialWorkflowOperation> contractType() {
        return SequentialWorkflowOperation.class;
    }

    @Override
    public List<String> executableBodyFields() {
        return Collections.singletonList("steps");
    }

    @Override
    public String deriveChannel(SequentialWorkflowOperation contract, HandlerRegistrationContext context) {
        String channel = HandlerChannelResolver.resolve(
                contract != null
                        ? contract.getChannel()
                        : null,
                context);
        if (channel != null && !context.hasContract(channel)) {
            throw new IllegalStateException("Sequential workflow operation '" + context.handlerKey()
                    + "' references unknown channel '" + channel + "'");
        }
        return channel;
    }

    @Override
    public boolean matches(SequentialWorkflowOperation contract, HandlerMatchContext context) {
        return matcher.matches(contract, context);
    }

    @Override
    public void execute(SequentialWorkflowOperation contract, ProcessorExecutionContext context) {
        runner.execute(new SequentialWorkflow().steps(contract.getSteps()), context);
    }

}
