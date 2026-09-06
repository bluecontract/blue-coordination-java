package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.snapshot.FrozenNode;
import blue.language.processor.closure.ManagedOccurrenceBinding;

import java.util.List;
import java.util.Objects;

/** Exact same-lineage reference substitutions, with all other fields preserved. */
final class ManagedSourceReferenceRewrite {
    private ManagedSourceReferenceRewrite() { }

    static boolean verifies(Node before, Node after,
            List<ManagedOccurrenceBinding> prior,
            List<ManagedOccurrenceBinding> next) {
        if (prior.isEmpty() || prior.size() != next.size()) return false;
        Node normalizedBefore = before.clone();
        Node normalizedAfter = after.clone();
        for (ManagedOccurrenceBinding row : prior) {
            ManagedOccurrenceBinding successor = next.stream()
                    .filter(candidate -> candidate.occurrenceIdentity()
                            .equals(row.occurrenceIdentity()))
                    .findFirst().orElse(null);
            if (successor == null
                    || !successor.sourceDocumentId().equals(row.sourceDocumentId())
                    || !successor.bindingPolicyIdentity().equals(row.bindingPolicyIdentity())
                    || !successor.sourcePath().equals(row.sourcePath())
                    || !successor.targetDocumentId().equals(row.targetDocumentId())
                    || successor.activationGeneration() != row.activationGeneration()
                    || successor.active() != row.active()
                    || !Objects.equals(successor.pendingHistoricalEpoch(),
                            row.pendingHistoricalEpoch())) return false;
            Node previousValue = NodePathEditor.getOrNull(before, row.sourcePath());
            Node nextValue = NodePathEditor.getOrNull(after, row.sourcePath());
            if (!establishes(previousValue, row.expectedTargetBlueId())
                    || !establishes(nextValue, successor.expectedTargetBlueId())) return false;
            // Both exact values have been checked against the complete binding
            // rows. Normalize only that authenticated substitution to the same
            // predecessor reference; never ignore arbitrary child content.
            Node predecessorReference = new Node().blueId(row.expectedTargetBlueId());
            NodePathEditor.put(normalizedBefore, row.sourcePath(), predecessorReference.clone());
            NodePathEditor.put(normalizedAfter, row.sourcePath(), predecessorReference.clone());
        }
        return exact(normalizedBefore).equals(exact(normalizedAfter));
    }

    private static boolean establishes(Node value, String expected) {
        return value != null && exact(value).equals(expected);
    }

    private static String exact(Node value) {
        return FrozenNode.fromNode(NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(value)).blueId();
    }
}
