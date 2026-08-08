package blue.coordination.fastpath;

import blue.language.model.wire.JsonPointer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable persistent pointer trie for exact changed-path invalidation.
 *
 * <p>The index is shared by the durable subscription snapshot and the compact
 * admitted planning projection.  A dependency matches when it is an ancestor
 * or descendant of an exact changed path. Updates copy only the pointer spine
 * and the persistent key-set branch being changed; unrelated pointer branches
 * and key buckets retain object identity.</p>
 */
public final class PathDependencyIndex {
    private static final TrieNode EMPTY_NODE = new TrieNode(null, null);
    private static final PathDependencyIndex EMPTY =
            new PathDependencyIndex(EMPTY_NODE, 0);

    private final TrieNode root;
    private final int pathCount;

    private PathDependencyIndex(TrieNode root, int pathCount) {
        this.root = Objects.requireNonNull(root, "root");
        if (pathCount < 0) {
            throw new IllegalArgumentException("pathCount must be non-negative");
        }
        this.pathCount = pathCount;
    }

    /** Returns the shared empty immutable index. */
    public static PathDependencyIndex empty() {
        return EMPTY;
    }

    /** Builds an admitted-occurrence index without exposing a second trie. */
    public static PathDependencyIndex fromOccurrences(
            Collection<AdmittedOccurrence> occurrences) {
        PathDependencyIndex result = empty();
        for (AdmittedOccurrence occurrence : Objects.requireNonNull(
                occurrences, "occurrences")) {
            AdmittedOccurrence exact = Objects.requireNonNull(
                    occurrence, "occurrence");
            result = result.updated(
                    exact.publicKey(),
                    Collections.<String>emptySet(),
                    exact.dependencyPaths());
        }
        return result;
    }

    /** Retains the original public construction surface. */
    public static PathDependencyIndex from(
            Collection<AdmittedOccurrence> occurrences) {
        return fromOccurrences(occurrences);
    }

    /**
     * Builds an index from exact public-key to dependency-pointer bindings.
     * The supplied map is consumed only during construction and is not retained.
     */
    public static PathDependencyIndex fromDependencies(
            Map<String, ? extends Collection<String>> dependenciesByKey) {
        PathDependencyIndex result = empty();
        for (Map.Entry<String, ? extends Collection<String>> entry
                : Objects.requireNonNull(
                        dependenciesByKey, "dependenciesByKey").entrySet()) {
            result = result.updated(
                    entry.getKey(),
                    Collections.<String>emptySet(),
                    entry.getValue());
        }
        return result;
    }

    public int pathCount() {
        return pathCount;
    }

    /**
     * Returns a new index after replacing one key's exact dependency paths.
     * Supplying equal old/new path sets is allocation-free.
     */
    public PathDependencyIndex updated(
            String publicKey,
            Collection<String> previousPaths,
            Collection<String> resultingPaths) {
        String key = AdmittedOccurrence.text(publicKey, "publicKey");
        Set<String> previous = canonicalPaths(previousPaths, "previousPaths");
        Set<String> resulting = canonicalPaths(resultingPaths, "resultingPaths");
        if (previous.equals(resulting)) return this;

        TrieNode changed = root;
        int nextCount = pathCount;
        for (String path : previous) {
            if (resulting.contains(path)) continue;
            Update update = change(changed, segments(path), 0, key, false);
            if (!update.changed) {
                throw new IllegalArgumentException(
                        "previous dependency binding is absent: "
                                + key + " at " + path);
            }
            changed = update.node;
            nextCount--;
        }
        for (String path : resulting) {
            if (previous.contains(path)) continue;
            Update update = change(changed, segments(path), 0, key, true);
            if (!update.changed) {
                throw new IllegalArgumentException(
                        "resulting dependency binding already exists: "
                                + key + " at " + path);
            }
            changed = update.node;
            nextCount++;
        }
        return nextCount == 0 ? empty()
                : new PathDependencyIndex(changed, nextCount);
    }

    /**
     * Returns occurrences whose dependency path is an ancestor or descendant
     * of at least one exact changed path. Query work is proportional to changed
     * path depth plus the keys in matched dependency subtrees.
     */
    public Set<String> affected(Collection<String> changedPaths) {
        Set<String> result = new TreeSet<String>(
                AdmittedOccurrence::codePointCompare);
        for (String supplied : Objects.requireNonNull(
                changedPaths, "changedPaths")) {
            String changed = canonicalPath(supplied, "changedPath");
            TrieNode cursor = root;
            addKeys(cursor.directKeys, result);
            boolean found = true;
            for (String segment : segments(changed)) {
                cursor = get(cursor.children, segment);
                if (cursor == null) {
                    found = false;
                    break;
                }
                addKeys(cursor.directKeys, result);
            }
            if (found) collectDescendants(cursor, result);
        }
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(result));
    }

    private static Update change(
            TrieNode node,
            List<String> path,
            int offset,
            String publicKey,
            boolean add) {
        TrieNode current = node == null ? EMPTY_NODE : node;
        if (offset == path.size()) {
            boolean present = contains(current.directKeys, publicKey);
            if (present == add) return new Update(current, false);
            SetNode keys = add
                    ? put(current.directKeys, publicKey)
                    : remove(current.directKeys, publicKey);
            return new Update(new TrieNode(current.children, keys), true);
        }

        String segment = path.get(offset);
        TrieNode child = get(current.children, segment);
        Update childUpdate = change(
                child, path, offset + 1, publicKey, add);
        if (!childUpdate.changed) return new Update(current, false);
        MapNode<TrieNode> children = childUpdate.node.isEmpty()
                ? remove(current.children, segment)
                : put(current.children, segment, childUpdate.node);
        return new Update(
                new TrieNode(children, current.directKeys), true);
    }

    private static void collectDescendants(
            TrieNode start,
            Set<String> result) {
        Deque<TrieNode> pending = new ArrayDeque<TrieNode>();
        pending.add(start);
        while (!pending.isEmpty()) {
            TrieNode current = pending.removeFirst();
            addKeys(current.directKeys, result);
            addValues(current.children, pending);
        }
    }

    private static Set<String> canonicalPaths(
            Collection<String> supplied,
            String label) {
        List<String> ordered = new ArrayList<String>();
        for (String path : Objects.requireNonNull(supplied, label)) {
            ordered.add(canonicalPath(path, label + " value"));
        }
        Collections.sort(ordered, AdmittedOccurrence::codePointCompare);
        Set<String> unique = new LinkedHashSet<String>(ordered);
        if (unique.size() != ordered.size()) {
            throw new IllegalArgumentException(
                    label + " contains a duplicate dependency path");
        }
        return Collections.unmodifiableSet(unique);
    }

    private static String canonicalPath(String supplied, String label) {
        String path = AdmittedOccurrence.text(supplied, label);
        String exact = JsonPointer.canonicalize(path);
        if (!path.equals(exact)) {
            throw new IllegalArgumentException(
                    label + " must be canonical: " + path);
        }
        return exact;
    }

    private static List<String> segments(String pointer) {
        return JsonPointer.split(pointer);
    }

    /* Persistent deterministic treaps keep both path children and exact key
     * buckets copy-on-write without cloning a high-fanout Java Map/Set. */

    private static <V> V get(MapNode<V> node, String key) {
        MapNode<V> cursor = node;
        while (cursor != null) {
            int compared = AdmittedOccurrence.codePointCompare(key, cursor.key);
            if (compared == 0) return cursor.value;
            cursor = compared < 0 ? cursor.left : cursor.right;
        }
        return null;
    }

    private static <V> MapNode<V> put(
            MapNode<V> node,
            String key,
            V value) {
        if (node == null) return new MapNode<V>(key, value, null, null);
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared == 0) {
            return node.value == value
                    ? node
                    : new MapNode<V>(key, value, node.left, node.right);
        }
        if (compared < 0) {
            MapNode<V> left = put(node.left, key, value);
            MapNode<V> changed = new MapNode<V>(
                    node.key, node.value, left, node.right);
            return higherPriority(left, changed) ? rotateRight(changed) : changed;
        }
        MapNode<V> right = put(node.right, key, value);
        MapNode<V> changed = new MapNode<V>(
                node.key, node.value, node.left, right);
        return higherPriority(right, changed) ? rotateLeft(changed) : changed;
    }

    private static <V> MapNode<V> remove(MapNode<V> node, String key) {
        if (node == null) return null;
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared == 0) return merge(node.left, node.right);
        if (compared < 0) {
            MapNode<V> left = remove(node.left, key);
            return left == node.left
                    ? node
                    : new MapNode<V>(node.key, node.value, left, node.right);
        }
        MapNode<V> right = remove(node.right, key);
        return right == node.right
                ? node
                : new MapNode<V>(node.key, node.value, node.left, right);
    }

    private static <V> MapNode<V> merge(
            MapNode<V> left,
            MapNode<V> right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new MapNode<V>(
                    left.key,
                    left.value,
                    left.left,
                    merge(left.right, right));
        }
        return new MapNode<V>(
                right.key,
                right.value,
                merge(left, right.left),
                right.right);
    }

    private static <V> MapNode<V> rotateRight(MapNode<V> node) {
        MapNode<V> pivot = node.left;
        MapNode<V> right = new MapNode<V>(
                node.key, node.value, pivot.right, node.right);
        return new MapNode<V>(
                pivot.key, pivot.value, pivot.left, right);
    }

    private static <V> MapNode<V> rotateLeft(MapNode<V> node) {
        MapNode<V> pivot = node.right;
        MapNode<V> left = new MapNode<V>(
                node.key, node.value, node.left, pivot.left);
        return new MapNode<V>(
                pivot.key, pivot.value, left, pivot.right);
    }

    private static boolean higherPriority(
            MapNode<?> candidate,
            MapNode<?> current) {
        if (candidate == null) return false;
        int compared = Integer.compareUnsigned(
                candidate.priority, current.priority);
        return compared < 0
                || (compared == 0
                        && AdmittedOccurrence.codePointCompare(
                                candidate.key, current.key) < 0);
    }

    private static void addValues(
            MapNode<TrieNode> node,
            Deque<TrieNode> target) {
        if (node == null) return;
        addValues(node.left, target);
        target.addLast(node.value);
        addValues(node.right, target);
    }

    private static boolean contains(SetNode node, String key) {
        SetNode cursor = node;
        while (cursor != null) {
            int compared = AdmittedOccurrence.codePointCompare(key, cursor.key);
            if (compared == 0) return true;
            cursor = compared < 0 ? cursor.left : cursor.right;
        }
        return false;
    }

    private static SetNode put(SetNode node, String key) {
        if (node == null) return new SetNode(key, null, null);
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared == 0) return node;
        if (compared < 0) {
            SetNode left = put(node.left, key);
            SetNode changed = new SetNode(node.key, left, node.right);
            return higherPriority(left, changed) ? rotateRight(changed) : changed;
        }
        SetNode right = put(node.right, key);
        SetNode changed = new SetNode(node.key, node.left, right);
        return higherPriority(right, changed) ? rotateLeft(changed) : changed;
    }

    private static SetNode remove(SetNode node, String key) {
        if (node == null) return null;
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared == 0) return merge(node.left, node.right);
        if (compared < 0) {
            SetNode left = remove(node.left, key);
            return left == node.left ? node : new SetNode(node.key, left, node.right);
        }
        SetNode right = remove(node.right, key);
        return right == node.right ? node : new SetNode(node.key, node.left, right);
    }

    private static SetNode merge(SetNode left, SetNode right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new SetNode(left.key, left.left, merge(left.right, right));
        }
        return new SetNode(right.key, merge(left, right.left), right.right);
    }

    private static SetNode rotateRight(SetNode node) {
        SetNode pivot = node.left;
        return new SetNode(
                pivot.key,
                pivot.left,
                new SetNode(node.key, pivot.right, node.right));
    }

    private static SetNode rotateLeft(SetNode node) {
        SetNode pivot = node.right;
        return new SetNode(
                pivot.key,
                new SetNode(node.key, node.left, pivot.left),
                pivot.right);
    }

    private static boolean higherPriority(
            SetNode candidate,
            SetNode current) {
        if (candidate == null) return false;
        int compared = Integer.compareUnsigned(
                candidate.priority, current.priority);
        return compared < 0
                || (compared == 0
                        && AdmittedOccurrence.codePointCompare(
                                candidate.key, current.key) < 0);
    }

    private static void addKeys(SetNode node, Set<String> target) {
        if (node == null) return;
        addKeys(node.left, target);
        target.add(node.key);
        addKeys(node.right, target);
    }

    private static int priority(String value) {
        int hash = value.hashCode();
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        return hash ^ (hash >>> 16);
    }

    private static final class TrieNode {
        private final MapNode<TrieNode> children;
        private final SetNode directKeys;

        private TrieNode(
                MapNode<TrieNode> children,
                SetNode directKeys) {
            this.children = children;
            this.directKeys = directKeys;
        }

        private boolean isEmpty() {
            return children == null && directKeys == null;
        }
    }

    private static final class Update {
        private final TrieNode node;
        private final boolean changed;

        private Update(TrieNode node, boolean changed) {
            this.node = node;
            this.changed = changed;
        }
    }

    private static final class MapNode<V> {
        private final String key;
        private final V value;
        private final int priority;
        private final MapNode<V> left;
        private final MapNode<V> right;

        private MapNode(
                String key,
                V value,
                MapNode<V> left,
                MapNode<V> right) {
            this.key = key;
            this.value = value;
            this.priority = priority(key);
            this.left = left;
            this.right = right;
        }
    }

    private static final class SetNode {
        private final String key;
        private final int priority;
        private final SetNode left;
        private final SetNode right;

        private SetNode(String key, SetNode left, SetNode right) {
            this.key = key;
            this.priority = priority(key);
            this.left = left;
            this.right = right;
        }
    }
}
