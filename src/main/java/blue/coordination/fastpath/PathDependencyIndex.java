package blue.coordination.fastpath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable pointer trie for deterministic changed-path invalidation.
 * Query cost is proportional to changed path depth plus the actually affected
 * subtree, rather than every active subscription occurrence.
 */
public final class PathDependencyIndex {
    private final TrieNode root;
    private final int pathCount;

    private PathDependencyIndex(TrieNode root, int pathCount) {
        this.root = root;
        this.pathCount = pathCount;
    }

    public static PathDependencyIndex from(
            Collection<AdmittedOccurrence> occurrences) {
        MutableNode root = new MutableNode();
        int paths = 0;
        for (AdmittedOccurrence occurrence : Objects.requireNonNull(
                occurrences, "occurrences")) {
            for (String path : occurrence.dependencyPaths()) {
                MutableNode cursor = root;
                for (String segment : segments(path)) {
                    MutableNode child = cursor.children.get(segment);
                    if (child == null) {
                        child = new MutableNode();
                        cursor.children.put(segment, child);
                    }
                    cursor = child;
                }
                if (cursor.directKeys.add(occurrence.publicKey())) paths++;
            }
        }
        return new PathDependencyIndex(freeze(root), paths);
    }

    public int pathCount() { return pathCount; }

    /**
     * Returns occurrences whose dependency path is an ancestor or descendant
     * of at least one exact changed path.
     */
    public Set<String> affected(Collection<String> changedPaths) {
        Set<String> result = new LinkedHashSet<String>();
        for (String changed : Objects.requireNonNull(changedPaths, "changedPaths")) {
            String exact = AdmittedOccurrence.canonicalScope(changed);
            TrieNode cursor = root;
            result.addAll(cursor.directKeys);
            boolean found = true;
            for (String segment : segments(exact)) {
                cursor = cursor.children.get(segment);
                if (cursor == null) {
                    found = false;
                    break;
                }
                result.addAll(cursor.directKeys);
            }
            if (found) collectDescendants(cursor, result);
        }
        List<String> ordered = new ArrayList<String>(result);
        Collections.sort(ordered, AdmittedOccurrence::codePointCompare);
        return Collections.unmodifiableSet(new LinkedHashSet<String>(ordered));
    }

    private static void collectDescendants(TrieNode start, Set<String> result) {
        Deque<TrieNode> pending = new ArrayDeque<TrieNode>();
        pending.add(start);
        while (!pending.isEmpty()) {
            TrieNode current = pending.removeFirst();
            result.addAll(current.directKeys);
            pending.addAll(current.children.values());
        }
    }

    private static TrieNode freeze(MutableNode source) {
        List<String> names = new ArrayList<String>(source.children.keySet());
        Collections.sort(names, AdmittedOccurrence::codePointCompare);
        Map<String, TrieNode> children = new LinkedHashMap<String, TrieNode>();
        for (String name : names) children.put(name, freeze(source.children.get(name)));
        List<String> direct = new ArrayList<String>(source.directKeys);
        Collections.sort(direct, AdmittedOccurrence::codePointCompare);
        return new TrieNode(
                Collections.unmodifiableMap(children),
                Collections.unmodifiableSet(new LinkedHashSet<String>(direct)));
    }

    private static List<String> segments(String pointer) {
        if ("/".equals(pointer)) return Collections.emptyList();
        String[] raw = pointer.substring(1).split("/", -1);
        List<String> result = new ArrayList<String>(raw.length);
        for (String segment : raw) result.add(segment);
        return result;
    }

    private static final class MutableNode {
        private final Map<String, MutableNode> children =
                new LinkedHashMap<String, MutableNode>();
        private final Set<String> directKeys = new LinkedHashSet<String>();
    }

    private static final class TrieNode {
        private final Map<String, TrieNode> children;
        private final Set<String> directKeys;

        private TrieNode(Map<String, TrieNode> children, Set<String> directKeys) {
            this.children = children;
            this.directKeys = directKeys;
        }
    }
}
