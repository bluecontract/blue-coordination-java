package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerMatchContext;
import blue.language.snapshot.FrozenNode;

/** Shared event-filter semantics for Coordination sequential workflow handlers. */
final class SequentialWorkflowEventMatcher {
    private SequentialWorkflowEventMatcher() {
    }

    static boolean matches(Node pattern, HandlerMatchContext context) {
        if (pattern == null
                || isEmptyPattern(pattern)) {
            return true;
        }
        Node expectedType = pattern.getType();
        FrozenNode event =
                context.occurrenceEventFrozen();
        if (expectedType != null
                && expectedType.getBlueId() != null
                && event != null
                && event.getType() != null
                && event.getType().getReferenceBlueId() != null
                && !context.eventDeclaredTypeIsSameOrDescendantOf(expectedType)) {
            return false;
        }
        return context.matchesEventPattern(pattern);
    }

    private static boolean isEmptyPattern(Node pattern) {
        return pattern.getType() == null
                && pattern.getItemType() == null
                && pattern.getKeyType() == null
                && pattern.getValueType() == null
                && pattern.getValue() == null
                && pattern.getItems() == null
                && (pattern.getProperties() == null
                || pattern.getProperties().isEmpty())
                && pattern.getContracts() == null
                && pattern.getBlueId() == null
                && pattern.getSchema() == null
                && pattern.getMergePolicy() == null
                && pattern.getPreviousBlueId() == null
                && pattern.getPosition() == null
                && pattern.getBlue() == null;
    }
}
