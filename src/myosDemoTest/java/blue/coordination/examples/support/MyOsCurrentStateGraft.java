package blue.coordination.examples.support;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Applies explicit managed-child current states without scanning references. */
public final class MyOsCurrentStateGraft {

    public record Replacement(String relativePath, Node exactCurrentChild) {
        public Replacement {
            relativePath = JsonPointer.canonicalize(
                    Objects.requireNonNull(relativePath, "relativePath"));
            if (relativePath.isEmpty()) {
                throw new IllegalArgumentException("Cannot replace Root");
            }
            exactCurrentChild = Objects.requireNonNull(
                    exactCurrentChild, "exactCurrentChild").clone();
        }

        @Override
        public Node exactCurrentChild() {
            return exactCurrentChild.clone();
        }
    }

    public Node apply(Node exactParent, List<Replacement> replacements) {
        Node result = Objects.requireNonNull(exactParent, "exactParent").clone();
        List<Replacement> ordered = new ArrayList<>(
                Objects.requireNonNull(replacements, "replacements"));
        ordered.sort(Comparator
                .comparingInt((Replacement replacement) ->
                        JsonPointer.split(replacement.relativePath()).size())
                .thenComparing(Replacement::relativePath,
                        blue.language.processor.ExternalOrderKey
                                ::compareTextCodePoints));
        for (Replacement replacement : ordered) {
            if (NodePathEditor.getOrNull(
                    result, replacement.relativePath()) == null) {
                throw new IllegalArgumentException(
                        "Declared embedded path is absent: "
                                + replacement.relativePath());
            }
            NodePathEditor.put(result, replacement.relativePath(),
                    replacement.exactCurrentChild());
        }
        return result;
    }
}
