package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.HandlerMatchContext;
import blue.language.snapshot.FrozenNode;

/** Shared event-filter semantics for Coordination sequential workflow handlers. */
final class SequentialWorkflowEventMatcher {
    private SequentialWorkflowEventMatcher() {
    }

    static boolean matches(Node pattern, HandlerMatchContext context) {
        if (pattern == null) {
            return true;
        }
        Node expectedType = pattern.getType();
        FrozenNode event = context.eventFrozen();
        if (expectedType != null
                && event != null
                && event.getType() != null
                && !context.eventTypeIsSubtypeOf(expectedType)) {
            return false;
        }
        return context.matchesEventPattern(pattern);
    }
}
