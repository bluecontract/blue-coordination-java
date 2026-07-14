package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerMatchContext;
import blue.repo.coordination.SequentialWorkflowOperation;

final class OperationRequestMatcher {

    boolean matches(SequentialWorkflowOperation contract, HandlerMatchContext context) {
        if (contract == null || context == null) {
            return false;
        }
        if (contract.getEvent() != null && !context.matchesEventPattern(contract.getEvent())) {
            return false;
        }
        CoordinationEventNodes.OperationRequestView request =
                CoordinationEventNodes.operationRequest(context.event());
        if (request == null || !request.routable()) {
            return false;
        }
        String operationKey = nonBlank(contract.getKey());
        if (operationKey == null || !operationKey.equals(request.operation())) {
            return false;
        }
        if (!request.channel().equals(context.channelKey())) {
            return false;
        }
        return requestMatches(contract.getRequest(), request, context);
    }

    private boolean requestMatches(Node requestPattern,
                                   CoordinationEventNodes.OperationRequestView request,
                                   HandlerMatchContext context) {
        if (requestPattern == null) {
            return true;
        }
        if (isEmptyRequestPattern(requestPattern)) {
            return true;
        }
        if (request.request() == null) {
            return false;
        }
        return context.matchesEventPattern(request.patternFor(requestPattern));
    }

    private boolean isEmptyRequestPattern(Node requestPattern) {
        return requestPattern.getType() == null
                && requestPattern.getItemType() == null
                && requestPattern.getKeyType() == null
                && requestPattern.getValueType() == null
                && requestPattern.getValue() == null
                && requestPattern.getItems() == null
                && (requestPattern.getProperties() == null || requestPattern.getProperties().isEmpty())
                && requestPattern.getBlueId() == null
                && requestPattern.getSchema() == null;
    }

    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().isEmpty() ? null : value;
    }
}
