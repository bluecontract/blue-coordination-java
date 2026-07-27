package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

final class WorkflowPatchEntry {
    private final String op;
    private final String path;
    private final FrozenNode val;

    WorkflowPatchEntry(String op, String path, Node val) {
        this.op = op;
        this.path = path;
        this.val = val != null ? FrozenNode.fromNode(val) : null;
    }

    WorkflowPatchEntry(String op, String path, FrozenNode val) {
        this.op = op;
        this.path = path;
        this.val = canonicalSnapshot(val);
    }

    String op() {
        return op;
    }

    String path() {
        return path;
    }

    FrozenNode val() {
        return val;
    }

    private static FrozenNode canonicalSnapshot(FrozenNode value) {
        if (value == null || value.isStrictCanonical()) {
            return value;
        }
        return FrozenNode.fromNode(value.toNode());
    }

}
