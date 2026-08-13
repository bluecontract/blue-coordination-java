package blue.coordination.processor.workflow;

/**
 * Immutable result of one workflow step, including whether it produced a
 * value, already handled a changeset, or terminated the workflow.
 */
public final class WorkflowStepResult {
    private static final WorkflowStepResult NONE = new WorkflowStepResult(false, null, false, false);
    private static final WorkflowStepResult TERMINAL = new WorkflowStepResult(false, null, false, true);

    private final boolean hasValue;
    private final Object value;
    private final boolean changesetHandled;
    private final boolean terminal;

    private WorkflowStepResult(boolean hasValue,
                               Object value,
                               boolean changesetHandled,
                               boolean terminal) {
        this.hasValue = hasValue;
        this.value = value;
        this.changesetHandled = changesetHandled;
        this.terminal = terminal;
    }

    public static WorkflowStepResult none() {
        return NONE;
    }

    public static WorkflowStepResult value(Object value) {
        return value(value, false);
    }

    public static WorkflowStepResult value(Object value, boolean changesetHandled) {
        return new WorkflowStepResult(true, value, changesetHandled, false);
    }

    public static WorkflowStepResult terminal() {
        return TERMINAL;
    }

    public static WorkflowStepResult terminalValue(Object value) {
        return terminalValue(value, false);
    }

    public static WorkflowStepResult terminalValue(Object value, boolean changesetHandled) {
        return new WorkflowStepResult(true, value, changesetHandled, true);
    }

    public boolean hasValue() {
        return hasValue;
    }

    public Object value() {
        return value;
    }

    public boolean changesetHandled() {
        return changesetHandled;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
