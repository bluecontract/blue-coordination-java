package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Locale;

final class WorkflowPatchEntry {
    private final String op;
    private final String path;
    private final FrozenNode val;

    WorkflowPatchEntry(String op, String path, Node val) {
        this.op = op;
        this.path = path;
        this.val = isRemove(op) || val == null ? null : FrozenNode.fromNode(val);
    }

    WorkflowPatchEntry(String op, String path, FrozenNode val) {
        this.op = op;
        this.path = path;
        this.val = isRemove(op) ? null : canonicalSnapshot(val);
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

    private static boolean isRemove(String op) {
        return op != null && "remove".equals(op.trim().toLowerCase(Locale.ROOT));
    }
}
