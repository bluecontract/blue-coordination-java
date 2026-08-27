package blue.coordination.internal;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** Immutable AVL map with an exact logarithmic minimum-entry read. */
final class PersistentMinimumMap<K, V> {
    private final Comparator<? super K> order;
    private final Node<K, V> root;

    private PersistentMinimumMap(
            Comparator<? super K> order, Node<K, V> root) {
        this.order = Objects.requireNonNull(order, "order");
        this.root = root;
    }

    static <K, V> PersistentMinimumMap<K, V> empty(
            Comparator<? super K> order) {
        return new PersistentMinimumMap<>(order, null);
    }

    ReadResult<V> read(K key) {
        K selected = Objects.requireNonNull(key, "key");
        int comparisons = 0;
        Node<K, V> node = root;
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

    MinimumResult<K, V> minimum() {
        int rows = 0;
        Node<K, V> node = root;
        if (node == null) {
            return new MinimumResult<>(null, 0);
        }
        while (node.left != null) {
            rows = Math.addExact(rows, 1);
            node = node.left;
        }
        rows = Math.addExact(rows, 1);
        return new MinimumResult<>(
                new AbstractMap.SimpleImmutableEntry<>(node.key, node.value),
                rows);
    }

    /** Returns the least entry whose key is strictly greater than {@code key}. */
    MinimumResult<K, V> higherThan(K key) {
        K selected = Objects.requireNonNull(key, "key");
        Node<K, V> node = root;
        Node<K, V> candidate = null;
        int rows = 0;
        while (node != null) {
            rows = Math.addExact(rows, 1);
            int comparison = order.compare(selected, node.key);
            if (comparison < 0) {
                candidate = node;
                node = node.left;
            } else {
                node = node.right;
            }
        }
        return new MinimumResult<>(
                candidate == null ? null
                        : new AbstractMap.SimpleImmutableEntry<>(
                                candidate.key, candidate.value),
                rows);
    }

    Mutation<K, V> put(K key, V value) {
        Counter work = new Counter();
        Node<K, V> changed = put(
                root,
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value"),
                work);
        return new Mutation<>(
                new PersistentMinimumMap<>(order, changed),
                true,
                work.comparisons,
                work.copiedNodes);
    }

    Mutation<K, V> remove(K key) {
        Counter work = new Counter();
        Change removed = new Change();
        Node<K, V> changed = remove(
                root, Objects.requireNonNull(key, "key"), removed, work);
        return new Mutation<>(
                removed.value ? new PersistentMinimumMap<>(order, changed)
                        : this,
                removed.value,
                work.comparisons,
                work.copiedNodes);
    }

    int size() {
        return Node.size(root);
    }

    void assertStructurallyValid() {
        Validation<K> validation = validate(root);
        if (validation.size != size()) {
            throw new IllegalStateException(
                    "Persistent minimum-map size metadata is inconsistent");
        }
    }

    private Node<K, V> put(
            Node<K, V> node, K key, V value, Counter work) {
        if (node == null) {
            return copied(key, value, null, null, work);
        }
        work.compared();
        int comparison = order.compare(key, node.key);
        if (comparison == 0) {
            return copied(key, value, node.left, node.right, work);
        }
        Node<K, V> changed = comparison < 0
                ? copied(
                        node.key,
                        node.value,
                        put(node.left, key, value, work),
                        node.right,
                        work)
                : copied(
                        node.key,
                        node.value,
                        node.left,
                        put(node.right, key, value, work),
                        work);
        return balance(changed, work);
    }

    private Node<K, V> remove(
            Node<K, V> node, K key, Change removed, Counter work) {
        if (node == null) {
            return null;
        }
        work.compared();
        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            Node<K, V> left = remove(node.left, key, removed, work);
            return removed.value
                    ? balance(copied(
                            node.key,
                            node.value,
                            left,
                            node.right,
                            work), work)
                    : node;
        }
        if (comparison > 0) {
            Node<K, V> right = remove(node.right, key, removed, work);
            return removed.value
                    ? balance(copied(
                            node.key,
                            node.value,
                            node.left,
                            right,
                            work), work)
                    : node;
        }
        removed.value = true;
        if (node.left == null) {
            return node.right;
        }
        if (node.right == null) {
            return node.left;
        }
        Node<K, V> successor = minimum(node.right);
        Node<K, V> right = removeMinimum(node.right, work);
        return balance(copied(
                successor.key,
                successor.value,
                node.left,
                right,
                work), work);
    }

    private Node<K, V> removeMinimum(Node<K, V> node, Counter work) {
        if (node.left == null) {
            return node.right;
        }
        return balance(copied(
                node.key,
                node.value,
                removeMinimum(node.left, work),
                node.right,
                work), work);
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        Node<K, V> selected = node;
        while (selected.left != null) {
            selected = selected.left;
        }
        return selected;
    }

    private Node<K, V> balance(Node<K, V> node, Counter work) {
        int balance = Node.height(node.left) - Node.height(node.right);
        if (balance > 1) {
            if (Node.height(node.left.left)
                    < Node.height(node.left.right)) {
                Node<K, V> left = rotateLeft(node.left, work);
                return rotateRight(copied(
                        node.key,
                        node.value,
                        left,
                        node.right,
                        work), work);
            }
            return rotateRight(node, work);
        }
        if (balance < -1) {
            if (Node.height(node.right.right)
                    < Node.height(node.right.left)) {
                Node<K, V> right = rotateRight(node.right, work);
                return rotateLeft(copied(
                        node.key,
                        node.value,
                        node.left,
                        right,
                        work), work);
            }
            return rotateLeft(node, work);
        }
        return node;
    }

    private Node<K, V> rotateLeft(Node<K, V> node, Counter work) {
        Node<K, V> pivot = node.right;
        Node<K, V> left = copied(
                node.key,
                node.value,
                node.left,
                pivot.left,
                work);
        return copied(pivot.key, pivot.value, left, pivot.right, work);
    }

    private Node<K, V> rotateRight(Node<K, V> node, Counter work) {
        Node<K, V> pivot = node.left;
        Node<K, V> right = copied(
                node.key,
                node.value,
                pivot.right,
                node.right,
                work);
        return copied(pivot.key, pivot.value, pivot.left, right, work);
    }

    private static <K, V> Node<K, V> copied(
            K key,
            V value,
            Node<K, V> left,
            Node<K, V> right,
            Counter work) {
        work.copied();
        return new Node<>(key, value, left, right);
    }

    private Validation<K> validate(Node<K, V> node) {
        if (node == null) {
            return Validation.empty();
        }
        Validation<K> left = validate(node.left);
        Validation<K> right = validate(node.right);
        if (left.maximum != null
                && order.compare(left.maximum, node.key) >= 0) {
            throw new IllegalStateException(
                    "Persistent minimum-map left order is invalid");
        }
        if (right.minimum != null
                && order.compare(node.key, right.minimum) >= 0) {
            throw new IllegalStateException(
                    "Persistent minimum-map right order is invalid");
        }
        int height = Math.addExact(Math.max(left.height, right.height), 1);
        int size = Math.addExact(Math.addExact(left.size, right.size), 1);
        if (node.height != height
                || node.size != size
                || Math.abs(left.height - right.height) > 1) {
            throw new IllegalStateException(
                    "Persistent minimum-map AVL metadata is invalid");
        }
        return new Validation<>(
                left.minimum == null ? node.key : left.minimum,
                right.maximum == null ? node.key : right.maximum,
                height,
                size);
    }

    record ReadResult<V>(V value, int comparisons) {
        boolean found() {
            return value != null;
        }
    }

    record MinimumResult<K, V>(Map.Entry<K, V> entry, int rowsRead) {
        boolean found() {
            return entry != null;
        }
    }

    record Mutation<K, V>(
            PersistentMinimumMap<K, V> map,
            boolean changed,
            int comparisons,
            int copiedNodes) {
        Mutation {
            map = Objects.requireNonNull(map, "map");
            if (comparisons < 0 || copiedNodes < 0) {
                throw new IllegalArgumentException(
                        "Mutation work must be non-negative");
            }
        }
    }

    private record Validation<K>(
            K minimum, K maximum, int height, int size) {
        private static <K> Validation<K> empty() {
            return new Validation<>(null, null, 0, 0);
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private final V value;
        private final Node<K, V> left;
        private final Node<K, V> right;
        private final int height;
        private final int size;

        private Node(
                K key,
                V value,
                Node<K, V> left,
                Node<K, V> right) {
            this.key = Objects.requireNonNull(key, "key");
            this.value = Objects.requireNonNull(value, "value");
            this.left = left;
            this.right = right;
            this.height = Math.addExact(
                    Math.max(height(left), height(right)), 1);
            this.size = Math.addExact(
                    Math.addExact(size(left), size(right)), 1);
        }

        private static int height(Node<?, ?> node) {
            return node == null ? 0 : node.height;
        }

        private static int size(Node<?, ?> node) {
            return node == null ? 0 : node.size;
        }
    }

    private static final class Counter {
        private int comparisons;
        private int copiedNodes;

        private void compared() {
            comparisons = Math.addExact(comparisons, 1);
        }

        private void copied() {
            copiedNodes = Math.addExact(copiedNodes, 1);
        }
    }

    private static final class Change {
        private boolean value;
    }
}
