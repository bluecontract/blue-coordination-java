package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.internal.PersistentOrderedMap.TreeNode;
import java.util.function.Supplier;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** Immutable AVL map with an exact logarithmic minimum-entry read. */
final class PersistentMinimumMap<K, V> {
    private final Comparator<? super K> order;
    private final TreeNode<K, V> root;
    private final PersistentMapStorage<K, V> storage;
    private final java.util.function.BiConsumer<K, V> retainedReadCheck;

    private PersistentMinimumMap(
            Comparator<? super K> order, TreeNode<K, V> root) {
        this(order, root, null);
    }

    private PersistentMinimumMap(Comparator<? super K> order, TreeNode<K, V> root, PersistentMapStorage<K, V> storage) {
        this(order, root, storage, null);
    }

    private PersistentMinimumMap(Comparator<? super K> order, TreeNode<K, V> root, PersistentMapStorage<K, V> storage,
            java.util.function.BiConsumer<K, V> retainedReadCheck) {
        this.order = Objects.requireNonNull(order, "order");
        this.root = root;
        this.storage = storage;
        this.retainedReadCheck = retainedReadCheck;
    }

    /** Private selected-read validation only; copied rows and mutation accounting remain unchanged. */
    PersistentMinimumMap<K, V> withRetainedReadCheck(java.util.function.BiConsumer<K, V> check) {
        if (storage == null || retainedReadCheck != null) throw new IllegalStateException("Expected one original physical minimum map");
        return new PersistentMinimumMap<>(order, root, storage, Objects.requireNonNull(check));
    }

    private V checkedValue(TreeNode<K, V> node) {
        V value = node.value(); if (retainedReadCheck != null) retainedReadCheck.accept(node.key(), value); return value;
    }

    static <K, V> PersistentMinimumMap<K, V> stored(Comparator<? super K> order, String orderingIdentity,
            PersistentMapCodec<K> keys, PersistentMapCodec<V> values, CoordinationImmutableObjectStore objects,
            PersistentMapStorage.Limits limits, byte[] descriptor) {
        var storage = new PersistentMapStorage<>(order, orderingIdentity, keys, values, objects, limits);
        return new PersistentMinimumMap<>(order, storage.open(descriptor), storage);
    }

    byte[] storedRootDescriptor() {
        if (storage == null) throw new IllegalStateException("Not a storage-backed minimum map");
        return storage.descriptor(root);
    }

    /** Explicit selected partition conversion; not an eager cold-runtime constructor. */
    PersistentMinimumMap<K, V> storedCopy(String orderingIdentity, PersistentMapCodec<K> keys, PersistentMapCodec<V> values,
            CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        if (storage != null) return stored(order, orderingIdentity, keys, values, objects, limits, storedRootDescriptor());
        var target = new PersistentMapStorage<>(order, orderingIdentity, keys, values, objects, limits);
        return target.scoped(() -> new PersistentMinimumMap<>(order, retainShape(root, target), target));
    }

    private static <K, V> TreeNode<K, V> retainShape(TreeNode<K, V> node, PersistentMapStorage<K, V> target) {
        if (node == null) return null;
        var left = retainShape(node.left(), target); var right = retainShape(node.right(), target);
        return target.create(node.key(), node.value(), left, right);
    }

    private <T> T scoped(Supplier<T> action) { return storage == null ? action.get() : storage.scoped(action); }

    static <K, V> PersistentMinimumMap<K, V> empty(
            Comparator<? super K> order) {
        return new PersistentMinimumMap<>(order, null);
    }

    ReadResult<V> read(K key) { return scoped(() -> readInScope(key)); }

    private ReadResult<V> readInScope(K key) {
        K selected = Objects.requireNonNull(key, "key");
        int comparisons = 0;
        TreeNode<K, V> node = root;
        while (node != null) {
            comparisons = Math.addExact(comparisons, 1);
            int comparison = order.compare(selected, node.key());
            if (comparison == 0) {
                return new ReadResult<>(checkedValue(node), comparisons);
            }
            node = comparison < 0 ? node.left() : node.right();
        }
        return new ReadResult<>(null, comparisons);
    }

    MinimumResult<K, V> minimum() { return scoped(this::minimumInScope); }

    private MinimumResult<K, V> minimumInScope() {
        int rows = 0;
        TreeNode<K, V> node = root;
        if (node == null) {
            return new MinimumResult<>(null, 0);
        }
        while (node.left() != null) {
            rows = Math.addExact(rows, 1);
            node = node.left();
        }
        rows = Math.addExact(rows, 1);
        return new MinimumResult<>(
                new AbstractMap.SimpleImmutableEntry<>(node.key(), checkedValue(node)),
                rows);
    }

    /** Returns the least entry whose key is strictly greater than {@code key}. */
    MinimumResult<K, V> higherThan(K key) { return scoped(() -> higherThanInScope(key)); }

    private MinimumResult<K, V> higherThanInScope(K key) {
        K selected = Objects.requireNonNull(key, "key");
        TreeNode<K, V> node = root;
        TreeNode<K, V> candidate = null;
        int rows = 0;
        while (node != null) {
            rows = Math.addExact(rows, 1);
            int comparison = order.compare(selected, node.key());
            if (comparison < 0) {
                candidate = node;
                node = node.left();
            } else {
                node = node.right();
            }
        }
        return new MinimumResult<>(
                candidate == null ? null
                        : new AbstractMap.SimpleImmutableEntry<>(
                                candidate.key(), checkedValue(candidate)),
                rows);
    }

    Mutation<K, V> put(K key, V value) { return scoped(() -> putInScope(key, value)); }

    private Mutation<K, V> putInScope(K key, V value) {
        Counter work = new Counter();
        TreeNode<K, V> changed = put(
                root,
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value"),
                work);
        return new Mutation<>(
                new PersistentMinimumMap<>(order, changed, storage, retainedReadCheck),
                true,
                work.comparisons,
                work.copiedNodes);
    }

    Mutation<K, V> remove(K key) { return scoped(() -> removeInScope(key)); }

    private Mutation<K, V> removeInScope(K key) {
        Counter work = new Counter();
        Change removed = new Change();
        TreeNode<K, V> changed = remove(
                root, Objects.requireNonNull(key, "key"), removed, work);
        return new Mutation<>(
                removed.value ? new PersistentMinimumMap<>(order, changed, storage, retainedReadCheck)
                        : this,
                removed.value,
                work.comparisons,
                work.copiedNodes);
    }

    int size() {
        return TreeNode.size(root);
    }

    void assertStructurallyValid() { scoped(() -> { validateInScope(); return null; }); }

    private void validateInScope() {
        Validation<K> validation = validate(root);
        if (validation.size != size()) {
            throw new IllegalStateException(
                    "Persistent minimum-map size metadata is inconsistent");
        }
    }

    private TreeNode<K, V> put(
            TreeNode<K, V> node, K key, V value, Counter work) {
        if (node == null) {
            return copied(key, value, null, null, work);
        }
        work.compared();
        int comparison = order.compare(key, node.key());
        if (comparison == 0) {
            return copied(key, value, node.left(), node.right(), work);
        }
        TreeNode<K, V> changed = comparison < 0
                ? copied(
                        node.key(),
                        node.value(),
                        put(node.left(), key, value, work),
                        node.right(),
                        work)
                : copied(
                        node.key(),
                        node.value(),
                        node.left(),
                        put(node.right(), key, value, work),
                        work);
        return balance(changed, work);
    }

    private TreeNode<K, V> remove(
            TreeNode<K, V> node, K key, Change removed, Counter work) {
        if (node == null) {
            return null;
        }
        work.compared();
        int comparison = order.compare(key, node.key());
        if (comparison < 0) {
            TreeNode<K, V> left = remove(node.left(), key, removed, work);
            return removed.value
                    ? balance(copied(
                            node.key(),
                            node.value(),
                            left,
                            node.right(),
                            work), work)
                    : node;
        }
        if (comparison > 0) {
            TreeNode<K, V> right = remove(node.right(), key, removed, work);
            return removed.value
                    ? balance(copied(
                            node.key(),
                            node.value(),
                            node.left(),
                            right,
                            work), work)
                    : node;
        }
        removed.value = true;
        if (node.left() == null) {
            return node.right();
        }
        if (node.right() == null) {
            return node.left();
        }
        TreeNode<K, V> successor = minimum(node.right());
        TreeNode<K, V> right = removeMinimum(node.right(), work);
        return balance(copied(
                successor.key(),
                successor.value(),
                node.left(),
                right,
                work), work);
    }

    private TreeNode<K, V> removeMinimum(TreeNode<K, V> node, Counter work) {
        if (node.left() == null) {
            return node.right();
        }
        return balance(copied(
                node.key(),
                node.value(),
                removeMinimum(node.left(), work),
                node.right(),
                work), work);
    }

    private static <K, V> TreeNode<K, V> minimum(TreeNode<K, V> node) {
        TreeNode<K, V> selected = node;
        while (selected.left() != null) {
            selected = selected.left();
        }
        return selected;
    }

    private TreeNode<K, V> balance(TreeNode<K, V> node, Counter work) {
        int balance = TreeNode.height(node.left()) - TreeNode.height(node.right());
        if (balance > 1) {
            if (TreeNode.height(node.left().left())
                    < TreeNode.height(node.left().right())) {
                TreeNode<K, V> left = rotateLeft(node.left(), work);
                return rotateRight(copied(
                        node.key(),
                        node.value(),
                        left,
                        node.right(),
                        work), work);
            }
            return rotateRight(node, work);
        }
        if (balance < -1) {
            if (TreeNode.height(node.right().right())
                    < TreeNode.height(node.right().left())) {
                TreeNode<K, V> right = rotateRight(node.right(), work);
                return rotateLeft(copied(
                        node.key(),
                        node.value(),
                        node.left(),
                        right,
                        work), work);
            }
            return rotateLeft(node, work);
        }
        return node;
    }

    private TreeNode<K, V> rotateLeft(TreeNode<K, V> node, Counter work) {
        TreeNode<K, V> pivot = node.right();
        TreeNode<K, V> left = copied(
                node.key(),
                node.value(),
                node.left(),
                pivot.left(),
                work);
        return copied(pivot.key(), pivot.value(), left, pivot.right(), work);
    }

    private TreeNode<K, V> rotateRight(TreeNode<K, V> node, Counter work) {
        TreeNode<K, V> pivot = node.left();
        TreeNode<K, V> right = copied(
                node.key(),
                node.value(),
                pivot.right(),
                node.right(),
                work);
        return copied(pivot.key(), pivot.value(), pivot.left(), right, work);
    }

    private TreeNode<K, V> copied(
            K key,
            V value,
            TreeNode<K, V> left,
            TreeNode<K, V> right,
            Counter work) {
        work.copied();
        return storage == null ? new MemoryNode<>(key, value, left, right) : storage.create(key, value, left, right);
    }

    private Validation<K> validate(TreeNode<K, V> node) {
        if (node == null) {
            return Validation.empty();
        }
        Validation<K> left = validate(node.left());
        Validation<K> right = validate(node.right());
        if (left.maximum != null
                && order.compare(left.maximum, node.key()) >= 0) {
            throw new IllegalStateException(
                    "Persistent minimum-map left order is invalid");
        }
        if (right.minimum != null
                && order.compare(node.key(), right.minimum) >= 0) {
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
                left.minimum == null ? node.key() : left.minimum,
                right.maximum == null ? node.key() : right.maximum,
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

    private static final class MemoryNode<K, V> extends TreeNode<K, V> {
        private final K key;
        private final V value;
        private final TreeNode<K, V> left, right;
        private MemoryNode(K key, V value, TreeNode<K, V> left, TreeNode<K, V> right) {
            super(Math.addExact(Math.max(TreeNode.height(left), TreeNode.height(right)), 1),
                    Math.addExact(Math.addExact(TreeNode.size(left), TreeNode.size(right)), 1));
            this.key = Objects.requireNonNull(key, "key"); this.value = Objects.requireNonNull(value, "value");
            this.left = left; this.right = right;
        }
        @Override K key() { return key; }
        @Override V value() { return value; }
        @Override TreeNode<K, V> left() { return left; }
        @Override TreeNode<K, V> right() { return right; }
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
