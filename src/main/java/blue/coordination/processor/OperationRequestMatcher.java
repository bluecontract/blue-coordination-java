package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.GasChargeContext;
import blue.language.processor.HandlerMatchContext;
import blue.repo.coordination.SequentialWorkflowOperation;

/**
 * Matches one Sequential Workflow Operation against a direct or
 * Timeline-wrapped Operation Request.
 *
 * <p>The operation key and selected channel are immutable dispatch headers.
 * An authored {@code request} is an additional payload pattern; an empty Node
 * intentionally means that no payload constraint was declared. All provider
 * evidence and event matching remain owned by the supplied Contracts
 * context.</p>
 */
final class OperationRequestMatcher {

    boolean matches(SequentialWorkflowOperation contract, HandlerMatchContext context) {
        if (contract == null || context == null) {
            return false;
        }
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "operationCandidateTested",
                1L,
                GasChargeContext.of(
                        context.scopePath(),
                        context.handlerKey(),
                        null,
                        "test Operation candidate"));
        boolean eventMatches =
                SequentialWorkflowEventMatcher.matches(
                        contract.getEvent(), context);
        if (!eventMatches) {
            return false;
        }
        String operationKey = nonBlank(contract.getKey());
        String channelKey = nonBlank(context.channelKey());
        if (operationKey == null || channelKey == null) {
            return false;
        }
        Node requestPattern = contract.getRequest();
        boolean requestMatches =
                CoordinationEventNodes.matchesOperationRequest(
                context.occurrenceEvent(),
                operationKey,
                channelKey,
                requestPattern == null
                        || isEmptyRequestPattern(requestPattern)
                        ? null
                        : requestPattern,
                context);
        return requestMatches;
    }

    private boolean isEmptyRequestPattern(Node requestPattern) {
        /*
         * Repository resolution contributes descriptive metadata from
         * Operation.request even when the document authored request: {}.
         * Name and description are documentation, not payload constraints.
         */
        return requestPattern.getType() == null
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
