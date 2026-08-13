package blue.coordination.processor;

import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.SequentialWorkflow;
import java.util.Collections;
import java.util.List;

/**
 * Routes a fixed-repository Chat Workflow Operation and executes its ordered
 * workflow steps after the owning Operation Request matches.
 */
public final class ChatWorkflowOperationProcessor implements HandlerProcessor<ChatWorkflowOperation> {
    private final SequentialWorkflowRunner runner;
    private final OperationRequestMatcher matcher = new OperationRequestMatcher();

    public ChatWorkflowOperationProcessor() {
        this(new SequentialWorkflowRunner());
    }

    public ChatWorkflowOperationProcessor(SequentialWorkflowRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        this.runner = runner;
    }

    @Override
    public Class<ChatWorkflowOperation> contractType() {
        return ChatWorkflowOperation.class;
    }

    @Override
    public List<String> executableBodyFields() {
        return Collections.singletonList("steps");
    }

    @Override
    public String deriveChannel(ChatWorkflowOperation contract, HandlerRegistrationContext context) {
        String channel = HandlerChannelResolver.resolve(
                contract != null
                        ? contract.getChannel()
                        : null,
                context);
        if (channel != null && !context.hasContract(channel)) {
            throw new IllegalStateException("Chat workflow operation '" + context.handlerKey()
                    + "' references unknown channel '" + channel + "'");
        }
        return channel;
    }

    @Override
    public boolean matches(ChatWorkflowOperation contract, HandlerMatchContext context) {
        return matcher.matches(contract, context);
    }

    @Override
    public void execute(ChatWorkflowOperation contract, ProcessorExecutionContext context) {
        runner.execute(new SequentialWorkflow().steps(contract.getSteps()), context);
    }

}
