package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.snapshot.FrozenNode;
import blue.language.processor.closure.ManagedOccurrenceBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact same-lineage reference substitutions, with all other fields preserved. */
final class ManagedSourceReferenceRewrite {
    private ManagedSourceReferenceRewrite() { }

    static boolean verifies(Node before, Node after,
            List<ManagedOccurrenceBinding> prior,
            List<ManagedOccurrenceBinding> next) {
        return verifies(before, after, prior, next, Map.of(), Map.of());
    }

    static boolean verifies(Node before, Node after,
            List<ManagedOccurrenceBinding> prior,
            List<ManagedOccurrenceBinding> next,
            Map<String, Node> beforeTargets, Map<String, Node> afterTargets) {
        if (prior.isEmpty() || prior.size() != next.size()) return false;
        var unmatched = new HashMap<String, ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding row : next) {
            if (unmatched.put(row.occurrenceIdentity(), row) != null) return false;
        }
        Node normalizedBefore = before.clone();
        Node normalizedAfter = after.clone();
        for (ManagedOccurrenceBinding row : prior) {
            // Consume each successor once: duplicate prior rows cannot hide
            // missing or additional evidence in equal-sized inventories.
            ManagedOccurrenceBinding successor = unmatched.remove(row.occurrenceIdentity());
            if (successor == null
                    || !successor.sourceDocumentId().equals(row.sourceDocumentId())
                    || !successor.bindingPolicyIdentity().equals(row.bindingPolicyIdentity())
                    || !successor.sourcePath().equals(row.sourcePath())
                    || !successor.targetDocumentId().equals(row.targetDocumentId())
                    || successor.activationGeneration() != row.activationGeneration()
                    || successor.active() != row.active()
                    || !Objects.equals(successor.pendingHistoricalEpoch(),
                            row.pendingHistoricalEpoch())
                    || !Objects.equals(successor.pendingRepresentationCursor(),
                            row.pendingRepresentationCursor())) return false;
            if (!row.active() && row.pendingHistoricalEpoch() == null) {
                // Retired paths are absent from the source document. Their retained
                // binding rows still follow finalizer-owned target identities.
                // Authenticate both endpoints against the complete committed member
                // values, and leave every byte at the retired source path untouched.
                if (!establishes(beforeTargets.get(row.targetDocumentId().value()), row.expectedTargetBlueId())
                        || !establishes(afterTargets.get(row.targetDocumentId().value()), successor.expectedTargetBlueId())) return false;
                continue;
            }
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
