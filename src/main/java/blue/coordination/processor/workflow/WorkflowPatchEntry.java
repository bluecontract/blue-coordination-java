package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

/**
 * Immutable workflow patch value retained between sequential step
 * executions.
 *
 * <p>Patch values are canonicalized to frozen snapshots at construction so a
 * later step cannot observe caller mutation.</p>
 */
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
        return FrozenNode.fromNode(
                FrozenNodeUtil.authoredOverlay(value));
    }

}
