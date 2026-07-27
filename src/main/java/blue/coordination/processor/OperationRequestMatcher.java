package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerMatchContext;
import blue.repo.coordination.SequentialWorkflowOperation;

final class OperationRequestMatcher {

    boolean matches(SequentialWorkflowOperation contract, HandlerMatchContext context) {
        if (contract == null || context == null) {
            return false;
        }
        if (!SequentialWorkflowEventMatcher.matches(contract.getEvent(), context)) {
            return false;
        }
        String operationKey = nonBlank(contract.getKey());
        String channelKey = nonBlank(context.channelKey());
        if (operationKey == null || channelKey == null) {
            return false;
        }
        Node requestPattern = contract.getRequest();
        return CoordinationEventNodes.matchesOperationRequest(
                context.event(),
                operationKey,
                channelKey,
                requestPattern == null
                        || isEmptyRequestPattern(requestPattern)
                        ? null
                        : requestPattern,
                context);
    }

    private boolean isEmptyRequestPattern(Node requestPattern) {
        return requestPattern.getName() == null
                && requestPattern.getDescription() == null
                && requestPattern.getType() == null
                && requestPattern.getItemType() == null
                && requestPattern.getKeyType() == null
                && requestPattern.getValueType() == null
                && requestPattern.getValue() == null
                && requestPattern.getItems() == null
                && (requestPattern.getProperties() == null || requestPattern.getProperties().isEmpty())
                && requestPattern.getContracts() == null
                && requestPattern.getBlueId() == null
                && requestPattern.getSchema() == null
                && requestPattern.getMergePolicy() == null
                && requestPattern.getPreviousBlueId() == null
                && requestPattern.getPosition() == null
                && requestPattern.getBlue() == null;
    }

    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().isEmpty() ? null : value;
    }
}
