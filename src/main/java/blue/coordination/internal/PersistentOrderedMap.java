package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

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
    private final PersistentMapStorage<K, V> storage;
    private final PersistentMapStorage<?, ?> readStorage;
    private final Object projectionOwner;
    private final LogicalRecordMap<K, V> records;

    private PersistentOrderedMap(
            Comparator<? super K> order,
            TreeNode<K, V> root) {
        this(order, root, null);
    }

    private PersistentOrderedMap(Comparator<? super K> order, TreeNode<K, V> root,
            PersistentMapStorage<K, V> storage) {
        this(order, root, storage, storage, null);
    }

    private PersistentOrderedMap(Comparator<? super K> order, TreeNode<K, V> root,
            PersistentMapStorage<K, V> storage, PersistentMapStorage<?, ?> readStorage, Object projectionOwner) {
        this(order, root, storage, readStorage, projectionOwner, null);
    }

    private PersistentOrderedMap(Comparator<? super K> order, TreeNode<K, V> root,
            PersistentMapStorage<K, V> storage, PersistentMapStorage<?, ?> readStorage, Object projectionOwner,
            LogicalRecordMap<K, V> records) {
        this.records = records;
        this.order = Objects.requireNonNull(order, "order");
        this.root = root;
        this.storage = storage;
        this.readStorage = readStorage;
        this.projectionOwner = projectionOwner;
    }

    static <K, V> PersistentOrderedMap<K, V> empty(
            Comparator<? super K> order) {
        return new PersistentOrderedMap<>(order, null);
    }

    static <K, V> PersistentOrderedMap<K, V> logical(Comparator<? super K> order, LogicalRecordContext context,
            blue.coordination.api.storage.CoordinationRecords.Family family,
            blue.coordination.api.storage.CoordinationRecords.Bytes scope, OrderedRecordKey<K> keys,
            PersistentMapCodec<V> values, int maximumKeyBytes, int maximumValueBytes) {
        return new PersistentOrderedMap<>(order, null, null, null, null,
                LogicalRecordMap.open(order, context, family, scope, keys, values, maximumKeyBytes, maximumValueBytes));
    }

    boolean isLogical() { return records != null; }
    void selectLogicalRecords() {
        if (records == null) throw new IllegalStateException("Not a logical-record map");
        records.select();
    }
    private PersistentOrderedMap<K, V> withRecords(LogicalRecordMap<K, V> changed) {
        return new PersistentOrderedMap<>(order, null, null, null, projectionOwner, changed);
    }

    /** Opens only an authenticated root, not its descendants. Null means empty. */
    static <K, V> PersistentOrderedMap<K, V> stored(
            Comparator<? super K> order, String orderingIdentity,
            PersistentMapCodec<K> keyCodec, PersistentMapCodec<V> valueCodec,
            CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits,
            byte[] rootDescriptor) {
        PersistentMapStorage<K, V> storage = new PersistentMapStorage<>(
                order, orderingIdentity, keyCodec, valueCodec, objects, limits);
        return new PersistentOrderedMap<>(order, storage.open(rootDescriptor), storage);
    }

    /** Physical root descriptor only; publishing/fencing it belongs to the owner. */
    byte[] storedRootDescriptor() {
        if (storage == null) throw new IllegalStateException("Not a storage-backed map");
        return storage.descriptor(root);
    }

    boolean isStored() { return storage != null; }

    /** An empty map retaining the same ordering and physical codec binding. */
    PersistentOrderedMap<K, V> emptyCopy() { if (records != null) return withRecords(records.empty()); return new PersistentOrderedMap<>(order, null, storage, readStorage, projectionOwner); }

    /** Attaches physical storage without changing the retained AVL tree shape. */
    PersistentOrderedMap<K, V> storedCopy(String orderingIdentity,
            PersistentMapCodec<K> keyCodec, PersistentMapCodec<V> valueCodec,
            CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        if (records != null) throw new IllegalStateException("Logical records cannot become a shared descriptor");
        if (storage != null) {
            return stored(order, orderingIdentity, keyCodec, valueCodec, objects, limits,
                    storedRootDescriptor());
        }
        PersistentMapStorage<K, V> target = new PersistentMapStorage<>(
                order, orderingIdentity, keyCodec, valueCodec, objects, limits);
        return target.scoped(() -> new PersistentOrderedMap<>(order, retainShape(root, target), target));
    }

    private static <K, V> TreeNode<K, V> retainShape(TreeNode<K, V> node,
            PersistentMapStorage<K, V> target) {
        if (node == null) return null;
        TreeNode<K, V> left = retainShape(node.left(), target);
        TreeNode<K, V> right = retainShape(node.right(), target);
        return target.create(node.key(), node.value(), left, right);
    }

    /** Explicit physical conversion of a selected tree; preserves ordering and AVL shape, not a point operation. */
    <T> PersistentOrderedMap<K, T> mapValuesPreservingShape(java.util.function.Function<? super V, ? extends T> mapping) {
        Objects.requireNonNull(mapping, "mapping");
        if (records != null) {
            PersistentOrderedMap<K, T> mapped = empty(order);
            for (var row : records.entries()) mapped = mapped.put(row.getKey(), mapping.apply(row.getValue())).map();
            return mapped;
        }
        return scoped(() -> new PersistentOrderedMap<>(order, mapValues(root, mapping)));
    }

    private static <K, V, T> TreeNode<K, T> mapValues(TreeNode<K, V> node,
            java.util.function.Function<? super V, ? extends T> mapping) {
        if (node == null) return null;
        return new MemoryNode<>(node.key(), Objects.requireNonNull(mapping.apply(node.value()), "mapped value"),
                mapValues(node.left(), mapping), mapValues(node.right(), mapping));
    }

    private <T> T scoped(Supplier<T> operation) {
        return readStorage == null ? operation.get() : readStorage.scoped(operation);
    }

    ReadResult<V> read(K key) {
        return records == null ? scoped(() -> readInScope(key)) : new ReadResult<>(records.get(key), 1);
    }

    private ReadResult<V> readInScope(K key) {
        K selected = Objects.requireNonNull(key, "key");
        int comparisons = 0;
        TreeNode<K, V> node = root;
        while (node != null) {
            comparisons = Math.addExact(comparisons, 1);
            int comparison = order.compare(selected, node.key());
            if (comparison == 0) {
                return new ReadResult<>(node.value(), comparisons);
            }
            node = comparison < 0 ? node.left() : node.right();
        }
        if (projectionOwner instanceof ValueProjection<?, ?, ?> projected) projected.absent(selected);
        return new ReadResult<>(null, comparisons);
    }

    V get(K key) {
        return read(key).value();
    }

    boolean containsKey(K key) {
        return read(key).found();
    }

    /** Authenticated key-path membership only; deliberately does not project the value. */
    boolean containsKeyWithoutValue(K key) {
        if (records != null) return records.contains(key);
        return scoped(() -> {
            K selected = Objects.requireNonNull(key, "key");
            TreeNode<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(selected, node.key());
                if (comparison == 0) return true;
                node = comparison < 0 ? node.left() : node.right();
            }
            if (projectionOwner instanceof ValueProjection<?, ?, ?> projected) projected.absent(selected);
            return false;
        });
    }

    int lookupSteps(K key) {
        return read(key).comparisons();
    }

    /** Exact minimum on one root path; physical checks do not add logical rows. */
    MinimumResult<K, V> minimum() {
        return records == null ? scoped(this::minimumInScope) : new MinimumResult<>(records.first(null, false, null), 1);
    }

    private MinimumResult<K, V> minimumInScope() {
        int rows = 0;
        TreeNode<K, V> node = root;
        if (node == null) return new MinimumResult<>(null, 0);
        TreeNode<K, V> left;
        while ((left = node.left()) != null) {
            rows = Math.addExact(rows, 1);
            node = left;
        }
        rows = Math.addExact(rows, 1);
        return new MinimumResult<>(Map.entry(node.key(), node.value()), rows);
    }

    /** Exact maximum on one root path, symmetrical to minimum(). */
    MinimumResult<K, V> maximum() {
        if (records != null) {
            var rows = records.entries();
            return new MinimumResult<>(rows.isEmpty() ? null : rows.get(rows.size() - 1), rows.size());
        }
        return scoped(this::maximumInScope);
    }

    private MinimumResult<K, V> maximumInScope() {
        int rows = 0;
        TreeNode<K, V> node = root;
        if (node == null) return new MinimumResult<>(null, 0);
        TreeNode<K, V> right;
        while ((right = node.right()) != null) {
            rows = Math.addExact(rows, 1);
            node = right;
        }
        return new MinimumResult<>(Map.entry(node.key(), node.value()), Math.addExact(rows, 1));
    }

    /** Returns the least entry strictly after the supplied key on one root path. */
    MinimumResult<K, V> higherThan(K key) {
        return records == null ? scoped(() -> higherThanInScope(key)) : new MinimumResult<>(records.first(key, true, null), 1);
    }

    private MinimumResult<K, V> higherThanInScope(K key) {
        K selected = Objects.requireNonNull(key, "key");
        TreeNode<K, V> node = root;
        TreeNode<K, V> candidate = null;
        int rows = 0;
        while (node != null) {
            rows = Math.addExact(rows, 1);
            if (order.compare(selected, node.key()) < 0) {
                candidate = node;
                node = node.left();
            } else {
                node = node.right();
            }
        }
        return new MinimumResult<>(candidate == null ? null
                : Map.entry(candidate.key(), candidate.value()), rows);
    }

    record MinimumResult<K, V>(Map.Entry<K, V> entry, int rowsRead) {
        boolean found() { return entry != null; }
    }

    Mutation<K, V> put(K key, V value) {
        if (records != null) return new Mutation<>(withRecords(records.put(key, value)), true, new MutationMetrics(1, 1));
        return scoped(() -> putInScope(key, value));
    }

    private Mutation<K, V> putInScope(K key, V value) {
        K selectedKey = Objects.requireNonNull(key, "key");
        V selectedValue = Objects.requireNonNull(value, "value");
        WorkCounter work = new WorkCounter();
        TreeNode<K, V> changed = put(
                root, selectedKey, selectedValue, work);
        return new Mutation<>(
                new PersistentOrderedMap<>(order, changed, storage, readStorage, projectionOwner),
                true,
                work.metrics());
    }

    Mutation<K, V> remove(K key) {
        if (records != null) return records.contains(key)
                ? new Mutation<>(withRecords(records.remove(key)), true, new MutationMetrics(1, 1))
                : new Mutation<>(this, false, new MutationMetrics(1, 0));
        return scoped(() -> removeInScope(key));
    }

    private Mutation<K, V> removeInScope(K key) {
        K selected = Objects.requireNonNull(key, "key");
        WorkCounter work = new WorkCounter();
        ChangeFlag removed = new ChangeFlag();
        TreeNode<K, V> changed = remove(
                root, selected, removed, work);
        return removed.value
                ? new Mutation<>(
                        new PersistentOrderedMap<>(order, changed, storage, readStorage, projectionOwner),
                        true,
                        work.metrics())
                : new Mutation<>(this, false, work.metrics());
    }

    int size() {
        return records == null ? TreeNode.size(root) : records.entries().size();
    }

    boolean isEmpty() {
        return records == null ? root == null : records.first(null, false, null) == null;
    }

    List<K> keys() {
        return records == null ? scoped(this::keysInScope) : records.entries().stream().map(Map.Entry::getKey).toList();
    }

    private List<K> keysInScope() {
        ArrayList<K> values = new ArrayList<>(initialListCapacity());
        collectKeys(root, values);
        return List.copyOf(values);
    }

    List<V> values() {
        return records == null ? scoped(this::valuesInScope) : records.entries().stream().map(Map.Entry::getValue).toList();
    }

    private List<V> valuesInScope() {
        ArrayList<V> values = new ArrayList<>(initialListCapacity());
        collectValues(root, values);
        return List.copyOf(values);
    }

    List<Map.Entry<K, V>> entries() {
        return records == null ? scoped(this::entriesInScope) : records.entries();
    }

    private List<Map.Entry<K, V>> entriesInScope() {
        ArrayList<Map.Entry<K, V>> values = new ArrayList<>(initialListCapacity());
        collectEntries(root, values);
        return List.copyOf(values);
    }

    private int initialListCapacity() {
        // A stored root's count must not allocate its unvisited descendants' output.
        // These helpers still return the complete traversal; this is not an output limit.
        return readStorage == null ? size() : Math.min(size(), 64);
    }

    /** Ordered lazy range: inclusive lower bound, exclusive upper bound; null is unbounded. */
    Iterator<Map.Entry<K, V>> range(K fromInclusive, K toExclusive) {
        if (records != null) return records.range(fromInclusive, toExclusive);
        if (fromInclusive != null && toExclusive != null
                && order.compare(fromInclusive, toExclusive) > 0) {
            throw new IllegalArgumentException("Range lower bound follows upper bound");
        }
        return new Iterator<>() {
            private final ArrayDeque<TreeNode<K, V>> path = new ArrayDeque<>();
            { scoped(() -> { descend(root, path); return null; }); }
            private void descend(TreeNode<K, V> node, ArrayDeque<TreeNode<K, V>> selectedPath) {
                while (node != null) {
                    if (fromInclusive != null && order.compare(node.key(), fromInclusive) < 0) {
                        node = node.right();
                    } else {
                        selectedPath.push(node);
                        node = node.left();
                    }
                }
            }
            @Override public boolean hasNext() {
                return scoped(() -> !path.isEmpty() && (toExclusive == null
                        || order.compare(path.peek().key(), toExclusive) < 0));
            }
            @Override public Map.Entry<K, V> next() {
                if (!hasNext()) throw new NoSuchElementException();
                return scoped(() -> {
                    ArrayDeque<TreeNode<K, V>> nextPath = new ArrayDeque<>(path);
                    TreeNode<K, V> node = nextPath.pop();
                    Map.Entry<K, V> entry = Map.entry(node.key(), node.value());
                    descend(node.right(), nextPath);
                    path.clear();
                    path.addAll(nextPath);
                    return entry;
                });
            }
        };
    }

    /** Exhaustive invariant check intended for focused structure tests. */
    void assertStructurallyValid() {
        if (records != null) { records.entries(); return; }
        scoped(() -> { validateInScope(); return null; });
    }

    private void validateInScope() {
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
        int comparison = order.compare(key, node.key());
        if (comparison == 0) {
            return copiedNode(
                    key, value, node.left(), node.right(), work);
        }
        TreeNode<K, V> changed = comparison < 0
                ? copiedNode(
                        node,
                        put(node.left(), key, value, work),
                        node.right(),
                        work)
                : copiedNode(
                        node,
                        node.left(),
                        put(node.right(), key, value, work),
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
        int comparison = order.compare(key, node.key());
        if (comparison < 0) {
            TreeNode<K, V> changedLeft = remove(
                    node.left(), key, removed, work);
            if (!removed.value) {
                return node;
            }
            return balance(copiedNode(
                    node,
                    changedLeft,
                    node.right(),
                    work), work);
        }
        if (comparison > 0) {
            TreeNode<K, V> changedRight = remove(
                    node.right(), key, removed, work);
            if (!removed.value) {
                return node;
            }
            return balance(copiedNode(
                    node,
                    node.left(),
                    changedRight,
                    work), work);
        }
        removed.value = true;
        if (node.left() == null) {
            return node.right();
        }
        if (node.right() == null) {
            return node.left();
        }
        TreeNode<K, V> successor = minimum(node.right());
        TreeNode<K, V> changedRight = removeMinimum(
                node.right(), work);
        return balance(copiedNode(
                successor,
                node.left(),
                changedRight,
                work), work);
    }

    private TreeNode<K, V> removeMinimum(
            TreeNode<K, V> node,
            WorkCounter work) {
        if (node.left() == null) {
            return node.right();
        }
        return balance(copiedNode(
                node,
                removeMinimum(node.left(), work),
                node.right(),
                work), work);
    }

    private TreeNode<K, V> balance(
            TreeNode<K, V> node,
            WorkCounter work) {
        int balance = TreeNode.height(node.left())
                - TreeNode.height(node.right());
        if (balance > 1) {
            if (TreeNode.height(node.left().left())
                    < TreeNode.height(node.left().right())) {
                node = copiedNode(
                        node,
                        rotateLeft(node.left(), work),
                        node.right(),
                        work);
            }
            return rotateRight(node, work);
        }
        if (balance < -1) {
            if (TreeNode.height(node.right().right())
                    < TreeNode.height(node.right().left())) {
                node = copiedNode(
                        node,
                        node.left(),
                        rotateRight(node.right(), work),
                        work);
            }
            return rotateLeft(node, work);
        }
        return node;
    }

    private TreeNode<K, V> rotateLeft(
            TreeNode<K, V> node,
            WorkCounter work) {
        TreeNode<K, V> promoted = node.right();
        TreeNode<K, V> changedLeft = copiedNode(
                node,
                node.left(),
                promoted.left(),
                work);
        return copiedNode(
                promoted,
                changedLeft,
                promoted.right(),
                work);
    }

    private TreeNode<K, V> rotateRight(
            TreeNode<K, V> node,
            WorkCounter work) {
        TreeNode<K, V> promoted = node.left();
        TreeNode<K, V> changedRight = copiedNode(
                node,
                promoted.right(),
                node.right(),
                work);
        return copiedNode(
                promoted,
                promoted.left(),
                changedRight,
                work);
    }

    private static <K, V> TreeNode<K, V> minimum(
            TreeNode<K, V> node) {
        TreeNode<K, V> selected = node;
        while (selected.left() != null) {
            selected = selected.left();
        }
        return selected;
    }

    private TreeNode<K, V> copiedNode(
            K key,
            V value,
            TreeNode<K, V> left,
            TreeNode<K, V> right,
            WorkCounter work) {
        work.copiedNode();
        return storage == null
                ? new MemoryNode<>(key, value, left, right)
                : storage.create(key, value, left, right);
    }

    private TreeNode<K, V> copiedNode(
            TreeNode<K, V> original,
            TreeNode<K, V> left,
            TreeNode<K, V> right,
            WorkCounter work) {
        work.copiedNode();
        if (projectionOwner != null) return new CopiedValueNode<>(original, left, right);
        return storage == null
                ? new MemoryNode<>(original.key(), original.value(), left, right)
                : storage.rebranch(original, left, right);
    }

    /** Lazy working values over one exact physical tree; only explicit staging writes complete values. */
    <T> ValueProjection<K, V, T> projectValues(java.util.function.BiFunction<K, V, T> mapping) {
        return projectValues(mapping, null);
    }

    <T> ValueProjection<K, V, T> projectValues(java.util.function.BiFunction<K, V, T> mapping,
            java.util.function.Consumer<K> absentFromOriginal) {
        if (storage == null && records == null) throw new IllegalStateException("Value projection requires a physical source tree");
        return new ValueProjection<>(this, mapping, absentFromOriginal);
    }

    Object valueProjectionIdentity() { return projectionOwner; }

    static final class ValueProjection<K, S, T> {
        private final PersistentOrderedMap<K, S> source;
        private final java.util.function.BiFunction<K, S, T> mapping;
        private final java.util.function.Consumer<K> absentFromOriginal;
        private ValueProjection(PersistentOrderedMap<K, S> source, java.util.function.BiFunction<K, S, T> mapping,
                java.util.function.Consumer<K> absentFromOriginal) {
            this.source = source; this.mapping = Objects.requireNonNull(mapping);
            this.absentFromOriginal = absentFromOriginal;
        }
        @SuppressWarnings("unchecked")
        private void absent(Object key) {
            // A deliberate removal from the working map is not corrupt retained absence.
            if (absentFromOriginal != null && !source.containsKey((K) key)) absentFromOriginal.accept((K) key);
        }
        PersistentOrderedMap<K, T> open() {
            if (source.records != null) return new PersistentOrderedMap<>(source.order, null, null, null, this,
                    source.records.project(mapping, absentFromOriginal));
            return new PersistentOrderedMap<>(source.order, wrap(source.root), null, source.storage, this);
        }
        private TreeNode<K, T> wrap(TreeNode<K, S> node) { return node == null ? null : new ProjectedNode<>(this, node); }

        /** Does not mutate the working tree or account physical staging as logical map work. */
        PersistentOrderedMap<K, S> stage(PersistentOrderedMap<K, T> working, java.util.function.BiFunction<K, T, S> retain) {
            if (working.projectionOwner != this) throw new IllegalArgumentException("Working values belong to another selected tree");
            Objects.requireNonNull(retain);
            if (source.records != null) return source.withRecords(source.records.stageProjection(working.records, retain));
            return source.scoped(() -> new PersistentOrderedMap<>(source.order, stageNode(working.root, retain), source.storage));
        }
        @SuppressWarnings("unchecked")
        private TreeNode<K, S> stageNode(TreeNode<K, T> node, java.util.function.BiFunction<K, T, S> retain) {
            if (node == null) return null;
            if (node instanceof ProjectedNode<?, ?, ?> projected) {
                if (projected.owner != this) throw new IllegalArgumentException("Mixed physical value projection");
                return (TreeNode<K, S>) projected.source;
            }
            TreeNode<K, S> left = stageNode(node.left(), retain), right = stageNode(node.right(), retain);
            TreeNode<K, T> row = node instanceof CopiedValueNode<K, T> copied ? copied.row : node;
            if (row instanceof ProjectedNode<?, ?, ?> projected) {
                if (projected.owner != this) throw new IllegalArgumentException("Mixed physical value row");
                return source.storage.rebranch((TreeNode<K, S>) projected.source, left, right);
            }
            return source.storage.create(node.key(), Objects.requireNonNull(retain.apply(node.key(), node.value())), left, right);
        }
    }

    private static final class ProjectedNode<K, S, T> extends TreeNode<K, T> {
        private final ValueProjection<K, S, T> owner;
        private final TreeNode<K, S> source;
        ProjectedNode(ValueProjection<K, S, T> owner, TreeNode<K, S> source) {
            super(source.height, source.size); this.owner = owner; this.source = source;
        }
        @Override K key() { return source.key(); }
        @Override T value() { return Objects.requireNonNull(owner.mapping.apply(source.key(), source.value())); }
        @Override TreeNode<K, T> left() { return owner.wrap(source.left()); }
        @Override TreeNode<K, T> right() { return owner.wrap(source.right()); }
    }

    /** Rebranches an unchanged lazy value without evaluating it, even through repeated rotations. */
    private static final class CopiedValueNode<K, V> extends TreeNode<K, V> {
        private final TreeNode<K, V> row;
        private final TreeNode<K, V> left, right;
        CopiedValueNode(TreeNode<K, V> row, TreeNode<K, V> left, TreeNode<K, V> right) {
            super(Math.addExact(Math.max(TreeNode.height(left), TreeNode.height(right)), 1),
                    Math.addExact(Math.addExact(TreeNode.size(left), TreeNode.size(right)), 1));
            this.row = row instanceof CopiedValueNode<K, V> copied ? copied.row : row;
            this.left = left; this.right = right;
        }
        @Override K key() { return row.key(); }
        @Override V value() { return row.value(); }
        @Override TreeNode<K, V> left() { return left; }
        @Override TreeNode<K, V> right() { return right; }
    }

    private static <K, V> void collectKeys(
            TreeNode<K, V> node,
            List<K> destination) {
        if (node == null) {
            return;
        }
        collectKeys(node.left(), destination);
        destination.add(node.key());
        collectKeys(node.right(), destination);
    }

    private static <K, V> void collectValues(
            TreeNode<K, V> node,
            List<V> destination) {
        if (node == null) {
            return;
        }
        collectValues(node.left(), destination);
        destination.add(node.value());
        collectValues(node.right(), destination);
    }

    private static <K, V> void collectEntries(
            TreeNode<K, V> node,
            List<Map.Entry<K, V>> destination) {
        if (node == null) {
            return;
        }
        collectEntries(node.left(), destination);
        destination.add(Map.entry(node.key(), node.value()));
        collectEntries(node.right(), destination);
    }

    private static void collectNodeIdentities(
            TreeNode<?, ?> node,
            IdentityHashMap<TreeNode<?, ?>, Boolean> destination) {
        if (node == null) {
            return;
        }
        destination.put(node, Boolean.TRUE);
        collectNodeIdentities(node.left(), destination);
        collectNodeIdentities(node.right(), destination);
    }

    private static int countSharedNodeIdentities(
            TreeNode<?, ?> node,
            IdentityHashMap<TreeNode<?, ?>, Boolean> candidates) {
        if (node == null) {
            return 0;
        }
        int here = candidates.containsKey(node) ? 1 : 0;
        return Math.addExact(here, Math.addExact(
                countSharedNodeIdentities(node.left(), candidates),
                countSharedNodeIdentities(node.right(), candidates)));
    }

    private Validation<K> validate(TreeNode<K, V> node) {
        if (node == null) {
            return Validation.empty();
        }
        Validation<K> left = validate(node.left());
        Validation<K> right = validate(node.right());
        if (left.maximum() != null
                && order.compare(left.maximum(), node.key()) >= 0) {
            throw new IllegalStateException(
                    "Persistent-map left subtree is out of order");
        }
        if (right.minimum() != null
                && order.compare(node.key(), right.minimum()) >= 0) {
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
                left.minimum() == null ? node.key() : left.minimum(),
                right.maximum() == null ? node.key() : right.maximum(),
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

    abstract static class TreeNode<K, V> {
        final int height;
        final int size;
        TreeNode(int height, int size) { this.height = height; this.size = size; }
        abstract K key();
        abstract V value();
        abstract TreeNode<K, V> left();
        abstract TreeNode<K, V> right();
        static int height(TreeNode<?, ?> node) { return node == null ? 0 : node.height; }
        static int size(TreeNode<?, ?> node) { return node == null ? 0 : node.size; }
    }

    private static final class MemoryNode<K, V> extends TreeNode<K, V> {
        private final K key;
        private final V value;
        private final TreeNode<K, V> left;
        private final TreeNode<K, V> right;
        private MemoryNode(K key, V value, TreeNode<K, V> left, TreeNode<K, V> right) {
            super(Math.addExact(Math.max(TreeNode.height(left), TreeNode.height(right)), 1),
                    Math.addExact(Math.addExact(TreeNode.size(left), TreeNode.size(right)), 1));
            this.key = Objects.requireNonNull(key, "key");
            this.value = Objects.requireNonNull(value, "value");
            this.left = left;
            this.right = right;
        }
        @Override K key() { return key; }
        @Override V value() { return value; }
        @Override TreeNode<K, V> left() { return left; }
        @Override TreeNode<K, V> right() { return right; }
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
