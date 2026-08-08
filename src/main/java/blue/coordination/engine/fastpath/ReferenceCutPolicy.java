package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.language.model.wire.JsonPointer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fail-closed policy deciding which splitter-created edge bodies may be cut. */
public final class ReferenceCutPolicy {
    private final PathIntersectionIndex forcedExpandedPaths;
    private final PathIntersectionIndex forbiddenCutPaths;

    public ReferenceCutPolicy(
            Collection<String> forcedExpandedPaths,
            Collection<String> forbiddenCutPaths) {
        this.forcedExpandedPaths = new PathIntersectionIndex(
                forcedExpandedPaths);
        this.forbiddenCutPaths = new PathIntersectionIndex(
                forbiddenCutPaths);
    }

    public static ReferenceCutPolicy strictDefaults() {
        /* The concrete Root itself is already protected explicitly in
         * mayCut(). Mandatory platform paths are supplied by the engine's
         * active dependency closure. Treating them as globally forced
         * subtrees here would inline executable-body fragments that must
         * remain provider-resolved references. */
        return new ReferenceCutPolicy(
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
    }

    public boolean mayCut(
            FragmentEdgeRecord edge,
            ActivePathSet activePaths) {
        FragmentEdgeRecord checked = Objects.requireNonNull(edge, "edge");
        return mayCut(
                checked,
                activePaths,
                JsonPointer.canonicalize(checked.absolutePointer()));
    }

    boolean mayCut(
            FragmentEdgeRecord edge,
            ActivePathSet activePaths,
            String canonicalPath) {
        Objects.requireNonNull(edge, "edge");
        Objects.requireNonNull(activePaths, "activePaths");
        if (!edge.splitterCreated() || edge.originalPureReference()) {
            return false;
        }
        String path = JsonPointer.canonicalize(
                Objects.requireNonNull(canonicalPath, "canonicalPath"));
        if (JsonPointer.ROOT.equals(path)) return false;
        if (activePaths.enters(path)) return false;
        if (forcedExpandedPaths.intersects(path)) return false;
        if (forbiddenCutPaths.intersects(path)) return false;
        return true;
    }

    /** Immutable prefix index; intersection is O(pointer depth). */
    private static final class PathIntersectionIndex {
        private final TrieNode root = new TrieNode();

        private PathIntersectionIndex(Collection<String> supplied) {
            for (String path : Objects.requireNonNull(
                    supplied, "supplied")) {
                add(JsonPointer.canonicalize(
                        Objects.requireNonNull(path, "path")));
            }
        }

        private void add(String path) {
            TrieNode cursor = root;
            cursor.terminalsBelow++;
            for (String segment : JsonPointer.split(path)) {
                TrieNode next = cursor.children.get(segment);
                if (next == null) {
                    next = new TrieNode();
                    cursor.children.put(segment, next);
                }
                cursor = next;
                cursor.terminalsBelow++;
            }
            cursor.terminal = true;
        }

        private boolean intersects(String path) {
            TrieNode cursor = root;
            if (cursor.terminal) return true;
            List<String> segments = JsonPointer.split(path);
            for (String segment : segments) {
                cursor = cursor.children.get(segment);
                if (cursor == null) return false;
                if (cursor.terminal) return true;
            }
            return cursor.terminalsBelow > 0;
        }
    }

    private static final class TrieNode {
        private final Map<String, TrieNode> children =
                new HashMap<String, TrieNode>();
        private int terminalsBelow;
        private boolean terminal;
    }
}
