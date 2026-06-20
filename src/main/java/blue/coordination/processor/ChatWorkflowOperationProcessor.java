package blue.coordination.processor;

import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.SequentialWorkflow;

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
    public String deriveChannel(ChatWorkflowOperation contract, HandlerRegistrationContext context) {
        String channel = trimToNull(contract.getChannel());
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
