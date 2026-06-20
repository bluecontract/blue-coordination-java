package blue.coordination.processor;

import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.repo.coordination.Operation;

public final class OperationProcessor implements HandlerProcessor<Operation> {
    @Override
    public Class<Operation> contractType() {
        return Operation.class;
    }

    @Override
    public String deriveChannel(Operation contract, HandlerRegistrationContext context) {
        return contract != null ? contract.getChannel() : null;
    }

    @Override
    public boolean matches(Operation contract, HandlerMatchContext context) {
        return false;
    }

    @Override
    public void execute(Operation contract, ProcessorExecutionContext context) {
        throw new IllegalStateException("Operation contracts declare operation shape but are not executable");
    }
}
