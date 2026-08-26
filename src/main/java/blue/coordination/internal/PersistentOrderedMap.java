package blue.coordination.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable ordered map backed by a persistent AVL tree.
 *
 * <p>A mutation copies only its search path and any nodes required by AVL
 * rotations. Untouched subtrees remain shared by identity. Reported work is
 * structural work performed by that one operation: comparisons are comparator
 * invocations along the search path and copied nodes are actual tree-node
 * allocations.</p>
 */
final class PersistentOrderedMap<K, V> {
    private final Comparator<? super K> order;
    private final TreeNode<K, V> root;

    private PersistentOrderedMap(
            Comparator<? super K> order,
            TreeNode<K, V> root) {
        this.order = Objects.requireNonNull(order, "order");
        this.root = root;
    }

    static <K, V> PersistentOrderedMap<K, V> empty(
            Comparator<? super K> order) {
        return new PersistentOrderedMap<>(order, null);
    }

    ReadResult<V> read(K key) {
        K selected = Objects.requireNonNull(key, "key");
        int comparisons = 0;
        TreeNode<K, V> node = root;
        while (node != null) {
            comparisons = Math.addExact(comparisons, 1);
            int comparison = order.compare(selected, node.key);
            if (comparison == 0) {
                return new ReadResult<>(node.value, comparisons);
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return new ReadResult<>(null, comparisons);
    }

    V get(K key) {
        return read(key).value();
    }

    boolean containsKey(K key) {
        return read(key).found();
    }

    int lookupSteps(K key) {
        return read(key).comparisons();
    }

    Mutation<K, V> put(K key, V value) {
        K selectedKey = Objects.requireNonNull(key, "key");
        V selectedValue = Objects.requireNonNull(value, "value");
        WorkCounter work = new WorkCounter();
        TreeNode<K, V> changed = put(
                root, selectedKey, selectedValue, work);
        return new Mutation<>(
                new PersistentOrderedMap<>(order, changed),
                true,
                work.metrics());
    }

    Mutation<K, V> remove(K key) {
        K selected = Objects.requireNonNull(key, "key");
        WorkCounter work = new WorkCounter();
        ChangeFlag removed = new ChangeFlag();
        TreeNode<K, V> changed = remove(
                root, selected, removed, work);
        return removed.value
                ? new Mutation<>(
                        new PersistentOrderedMap<>(order, changed),
                        true,
                        work.metrics())
                : new Mutation<>(this, false, work.metrics());
    }

    int size() {
        return TreeNode.size(root);
    }

    boolean isEmpty() {
        return root == null;
    }

    List<K> keys() {
        ArrayList<K> values = new ArrayList<>(size());
        collectKeys(root, values);
        return List.copyOf(values);
    }

    List<V> values() {
        ArrayList<V> values = new ArrayList<>(size());
        collectValues(root, values);
        return List.copyOf(values);
    }

    List<Map.Entry<K, V>> entries() {
        ArrayList<Map.Entry<K, V>> values = new ArrayList<>(size());
        collectEntries(root, values);
        return List.copyOf(values);
    }

    /** Exhaustive invariant check intended for focused structure tests. */
    void assertStructurallyValid() {
        Validation<K> validation = validate(root);
        if (validation.size() != size()) {
            throw new IllegalStateException(
                    "Persistent-map size metadata is inconsistent");
        }
    }

    /** Package-private test seam; ordinary callers should not inspect shape. */
    int heightForTesting() {
        return TreeNode.height(root);
    }

    /** Package-private test seam for proving no-op identity preservation. */
    Object rootIdentityForTesting() {
        return root;
    }

    /** Package-private test seam for proving persistent subtree sharing. */
    int sharedNodeCountForTesting(PersistentOrderedMap<?, ?> other) {
        PersistentOrderedMap<?, ?> selected = Objects.requireNonNull(
                other, "other");
        IdentityHashMap<TreeNode<?, ?>, Boolean> identities =
                new IdentityHashMap<>();
        collectNodeIdentities(root, identities);
        return countSharedNodeIdentities(selected.root, identities);
    }

    private TreeNode<K, V> put(
            TreeNode<K, V> node,
            K key,
            V value,
            WorkCounter work) {
        if (node == null) {
            return copiedNode(key, value, null, null, work);
        }
        work.compared();
        int comparison = order.compare(key, node.key);
        if (comparison == 0) {
            return copiedNode(
                    key, value, node.left, node.right, work);
        }
        TreeNode<K, V> changed = comparison < 0
                ? copiedNode(
                        node.key,
                        node.value,
                        put(node.left, key, value, work),
                        node.right,
                        work)
                : copiedNode(
                        node.key,
                        node.value,
                        node.left,
                        put(node.right, key, value, work),
                        work);
        return balance(changed, work);
    }

    private TreeNode<K, V> remove(
            TreeNode<K, V> node,
            K key,
            ChangeFlag removed,
            WorkCounter work) {
        if (node == null) {
            return null;
        }
        work.compared();
        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            TreeNode<K, V> changedLeft = remove(
                    node.left, key, removed, work);
            if (!removed.value) {
                return node;
            }
            return balance(copiedNode(
                    node.key,
                    node.value,
                    changedLeft,
                    node.right,
                    work), work);
        }
        if (comparison > 0) {
            TreeNode<K, V> changedRight = remove(
                    node.right, key, removed, work);
            if (!removed.value) {
                return node;
            }
            return balance(copiedNode(
                    node.key,
                    node.value,
                    node.left,
                    changedRight,
                    work), work);
        }
        removed.value = true;
        if (node.left == null) {
            return node.right;
        }
        if (node.right == null) {
            return node.left;
        }
        TreeNode<K, V> successor = minimum(node.right);
        TreeNode<K, V> changedRight = removeMinimum(
                node.right, work);
        return balance(copiedNode(
                successor.key,
                successor.value,
                node.left,
                changedRight,
                work), work);
    }

    private TreeNode<K, V> removeMinimum(
            TreeNode<K, V> node,
            WorkCounter work) {
        if (node.left == null) {
            return node.right;
        }
        return balance(copiedNode(
                node.key,
                node.value,
                removeMinimum(node.left, work),
                node.right,
                work), work);
    }

    private TreeNode<K, V> balance(
            TreeNode<K, V> node,
            WorkCounter work) {
        int balance = TreeNode.height(node.left)
                - TreeNode.height(node.right);
        if (balance > 1) {
            if (TreeNode.height(node.left.left)
                    < TreeNode.height(node.left.right)) {
                node = copiedNode(
                        node.key,
                        node.value,
                        rotateLeft(node.left, work),
                        node.right,
                        work);
            }
            return rotateRight(node, work);
        }
        if (balance < -1) {
            if (TreeNode.height(node.right.right)
                    < TreeNode.height(node.right.left)) {
                node = copiedNode(
                        node.key,
                        node.value,
                        node.left,
                        rotateRight(node.right, work),
                        work);
            }
            return rotateLeft(node, work);
        }
        return node;
    }

    private TreeNode<K, V> rotateLeft(
            TreeNode<K, V> node,
            WorkCounter work) {
        TreeNode<K, V> promoted = node.right;
        TreeNode<K, V> changedLeft = copiedNode(
                node.key,
                node.value,
                node.left,
                promoted.left,
                work);
        return copiedNode(
                promoted.key,
                promoted.value,
                changedLeft,
                promoted.right,
                work);
    }

    private TreeNode<K, V> rotateRight(
            TreeNode<K, V> node,
            WorkCounter work) {
        TreeNode<K, V> promoted = node.left;
        TreeNode<K, V> changedRight = copiedNode(
                node.key,
                node.value,
                promoted.right,
                node.right,
                work);
        return copiedNode(
                promoted.key,
                promoted.value,
                promoted.left,
                changedRight,
                work);
    }

    private static <K, V> TreeNode<K, V> minimum(
            TreeNode<K, V> node) {
        TreeNode<K, V> selected = node;
        while (selected.left != null) {
            selected = selected.left;
        }
        return selected;
    }

    private static <K, V> TreeNode<K, V> copiedNode(
            K key,
            V value,
            TreeNode<K, V> left,
            TreeNode<K, V> right,
            WorkCounter work) {
        work.copiedNode();
        return new TreeNode<>(key, value, left, right);
    }

    private static <K, V> void collectKeys(
            TreeNode<K, V> node,
            List<K> destination) {
        if (node == null) {
            return;
        }
        collectKeys(node.left, destination);
        destination.add(node.key);
        collectKeys(node.right, destination);
    }

    private static <K, V> void collectValues(
            TreeNode<K, V> node,
            List<V> destination) {
        if (node == null) {
            return;
        }
        collectValues(node.left, destination);
        destination.add(node.value);
        collectValues(node.right, destination);
    }

    private static <K, V> void collectEntries(
            TreeNode<K, V> node,
            List<Map.Entry<K, V>> destination) {
        if (node == null) {
            return;
        }
        collectEntries(node.left, destination);
        destination.add(Map.entry(node.key, node.value));
        collectEntries(node.right, destination);
    }

    private static void collectNodeIdentities(
            TreeNode<?, ?> node,
            IdentityHashMap<TreeNode<?, ?>, Boolean> destination) {
        if (node == null) {
            return;
        }
        destination.put(node, Boolean.TRUE);
        collectNodeIdentities(node.left, destination);
        collectNodeIdentities(node.right, destination);
    }

    private static int countSharedNodeIdentities(
            TreeNode<?, ?> node,
            IdentityHashMap<TreeNode<?, ?>, Boolean> candidates) {
        if (node == null) {
            return 0;
        }
        int here = candidates.containsKey(node) ? 1 : 0;
        return Math.addExact(here, Math.addExact(
                countSharedNodeIdentities(node.left, candidates),
                countSharedNodeIdentities(node.right, candidates)));
    }

    private Validation<K> validate(TreeNode<K, V> node) {
        if (node == null) {
            return Validation.empty();
        }
        Validation<K> left = validate(node.left);
        Validation<K> right = validate(node.right);
        if (left.maximum() != null
                && order.compare(left.maximum(), node.key) >= 0) {
            throw new IllegalStateException(
                    "Persistent-map left subtree is out of order");
        }
        if (right.minimum() != null
                && order.compare(node.key, right.minimum()) >= 0) {
            throw new IllegalStateException(
                    "Persistent-map right subtree is out of order");
        }
        int expectedHeight = Math.addExact(
                Math.max(left.height(), right.height()), 1);
        int expectedSize = Math.addExact(
                Math.addExact(left.size(), right.size()), 1);
        if (node.height != expectedHeight || node.size != expectedSize
                || Math.abs(left.height() - right.height()) > 1) {
            throw new IllegalStateException(
                    "Persistent-map AVL metadata is inconsistent");
        }
        return new Validation<>(
                left.minimum() == null ? node.key : left.minimum(),
                right.maximum() == null ? node.key : right.maximum(),
                expectedHeight,
                expectedSize);
    }

    record ReadResult<V>(V value, int comparisons) {
        ReadResult {
            if (comparisons < 0) {
                throw new IllegalArgumentException(
                        "comparisons must be non-negative");
            }
        }

        boolean found() {
            return value != null;
        }
    }

    record MutationMetrics(int comparisons, int copiedNodes) {
        MutationMetrics {
            if (comparisons < 0 || copiedNodes < 0) {
                throw new IllegalArgumentException(
                        "mutation metrics must be non-negative");
            }
        }
    }

    record Mutation<K, V>(
            PersistentOrderedMap<K, V> map,
            boolean changed,
            MutationMetrics metrics) {
        Mutation {
            map = Objects.requireNonNull(map, "map");
            metrics = Objects.requireNonNull(metrics, "metrics");
        }

        int comparisons() {
            return metrics.comparisons();
        }

        int copiedNodes() {
            return metrics.copiedNodes();
        }
    }

    private record Validation<K>(
            K minimum,
            K maximum,
            int height,
            int size) {
        private Validation {
            if (height < 0 || size < 0) {
                throw new IllegalArgumentException(
                        "validation dimensions must be non-negative");
            }
        }

        private static <K> Validation<K> empty() {
            return new Validation<>(null, null, 0, 0);
        }
    }

    private static final class TreeNode<K, V> {
        private final K key;
        private final V value;
        private final TreeNode<K, V> left;
        private final TreeNode<K, V> right;
        private final int height;
        private final int size;

        private TreeNode(
                K key,
                V value,
                TreeNode<K, V> left,
                TreeNode<K, V> right) {
            this.key = Objects.requireNonNull(key, "key");
            this.value = Objects.requireNonNull(value, "value");
            this.left = left;
            this.right = right;
            this.height = Math.addExact(
                    Math.max(height(left), height(right)), 1);
            this.size = Math.addExact(
                    Math.addExact(size(left), size(right)), 1);
        }

        private static int height(TreeNode<?, ?> node) {
            return node == null ? 0 : node.height;
        }

        private static int size(TreeNode<?, ?> node) {
            return node == null ? 0 : node.size;
        }
    }

    private static final class ChangeFlag {
        private boolean value;
    }

    private static final class WorkCounter {
        private int comparisons;
        private int copiedNodes;

        private void compared() {
            comparisons = Math.addExact(comparisons, 1);
        }

        private void copiedNode() {
            copiedNodes = Math.addExact(copiedNodes, 1);
        }

        private MutationMetrics metrics() {
            return new MutationMetrics(comparisons, copiedNodes);
        }
    }
}
