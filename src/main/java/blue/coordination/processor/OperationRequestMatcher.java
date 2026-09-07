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
 * An authored {@code request} is an additional payload pattern. Absence means
 * that no payload constraint was declared; an exact empty object is a present
 * pattern and therefore requires a present request. Object cardinality, when
 * relevant, remains an explicit schema constraint such as {@code minFields}.
 * All provider evidence and event matching remain owned by the supplied
 * Contracts context.</p>
 */
final class OperationRequestMatcher {
    private final CoordinationSemanticTypeIdentities identities;

    OperationRequestMatcher() {
        this(CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    OperationRequestMatcher(
            CoordinationSemanticTypeIdentities identities) {
        this.identities = java.util.Objects.requireNonNull(
                identities, "identities");
    }

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
        if (isInheritedRequestMetadataOnly(requestPattern)) {
            requestPattern = null;
        }
        boolean requestMatches =
                CoordinationEventNodes.matchesOperationRequest(
                context.occurrenceEvent(),
                operationKey,
                channelKey,
                requestPattern,
                context,
                identities);
        return requestMatches;
    }

    /**
     * Resolution materializes the base Operation's descriptive request field
     * even when the concrete contract omitted a request constraint. An exact
     * empty-object contribution is different: Language retains its explicit
     * empty properties map, so only a node with no payload marker at all is
     * inherited metadata rather than an authored request pattern.
     */
    private static boolean isInheritedRequestMetadataOnly(Node pattern) {
        return pattern != null
                && pattern.getType() == null
                && pattern.getItemType() == null
                && pattern.getKeyType() == null
                && pattern.getValueType() == null
                && pattern.getValue() == null
                && pattern.getItems() == null
                && pattern.getProperties() == null
                && pattern.getContracts() == null
                && pattern.getBlueId() == null
                && pattern.getSchema() == null
                && pattern.getMergePolicy() == null
                && pattern.getPreviousBlueId() == null
                && pattern.getPosition() == null
                && pattern.getBlue() == null;
    }

    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().isEmpty() ? null : value;
    }
}
