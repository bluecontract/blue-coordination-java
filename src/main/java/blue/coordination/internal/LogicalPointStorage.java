package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;

/** Library cross-package bridge for point maps sharing one logical attempt. */
public final class LogicalPointStorage {
    private final LogicalRecordContext context;
    private final List<Scope<?, ?>> scopes = new ArrayList<>();
    private final List<Runnable> selections = new ArrayList<>();
    void selectBeforeFlush(Runnable selection) { context.checkOpen(); selections.add(Objects.requireNonNull(selection)); }

    /** Binds the caller-owned attempt; creating a binding performs no reads. @param attempt owned attempt */
    public LogicalPointStorage(CoordinationRecordAttempt attempt) { context = new LogicalRecordContext(attempt); }
    LogicalRecordContext context() { return context; }

    /**
     * Tracks immutable bytes consumed or retained by this owner as required packet dependencies.
     * @param objects host-owned immutable store
     * @return an attempt-confined view retaining the existing controlled-writer capability
     */
    public CoordinationImmutableObjectStore trackArtifacts(CoordinationImmutableObjectStore objects) {
        context.checkOpen(); Objects.requireNonNull(objects);
        CoordinationImmutableObjectStore tracked = new CoordinationImmutableObjectStore() {
            private byte[] checked(String address, byte[] bytes) {
                var digest = CoordinationRecords.sha256(new Bytes(bytes));
                if (!digest.hex().equals(address)) throw new IllegalArgumentException("Immutable object address differs from bytes");
                context.requireArtifact(new Artifact(digest, bytes.length)); return bytes;
            }
            public byte[] putIfAbsent(String address, byte[] bytes) {
                return context.protect(() -> {
                    byte[] expected = Objects.requireNonNull(bytes).clone();
                    var retained = Objects.requireNonNull(objects.putIfAbsent(address, expected.clone()));
                    if (!Arrays.equals(expected, retained)) throw new IllegalArgumentException("Immutable retention acknowledged different bytes");
                    return checked(address, retained).clone();
                });
            }
            public Optional<byte[]> get(String address, int maximumBytes) {
                return context.protect(() -> Objects.requireNonNull(objects.get(address, maximumBytes)).map(bytes -> {
                    if (bytes.length > maximumBytes) throw new IllegalArgumentException("Immutable object exceeds selected read capacity");
                    return checked(address, bytes).clone();
                }));
            }
        };
        return RootedEngineStorage.isControlledNamespace(objects) ? RootedEngineStorage.controlledNamespace(tracked) : tracked;
    }

    /**
     * Opens a closed family in canonical encoded-key order. Enumeration is an explicit complete predicate;
     * there is no shared insertion counter. Callers must not use this map for insertion-order scheduling.
     * @param family logical family @param scope stable versioned scope @param keys closed key codec
     * @param values closed value codec @param limits point and pin bounds @return owned mutable view
     * @param <K> key type @param <V> value type
     */
    public <K, V> Scope<K, V> open(Family family, String scope, InsertionOrderedStorage.Codec<K> keys,
            InsertionOrderedStorage.Codec<V> values, InsertionOrderedStorage.Limits limits) {
        return context.protect(() -> {
            var ordered = new OrderedRecordKey<K>() {
                public String identity() { return keys.identity(); }
                public byte[] encode(K key) { return keys.encode(key); }
                public K decode(byte[] bytes) { return keys.decode(bytes); }
            };
            var codec = new PersistentMapCodec<V>() {
                public String identity() { return values.identity(); }
                public V prepareForStorage(V value) { return values.prepare(value); }
                public byte[] encode(V value) { return values.encode(value); }
                public V decode(byte[] bytes) { return values.decode(bytes); }
            };
            var map = LogicalRecordMap.open((K a, K b) -> Arrays.compareUnsigned(keys.encode(a), keys.encode(b)),
                    context, family, new Bytes(OrderedRecordKey.text().encode(scope)), ordered, codec,
                    limits.keyBytes(), limits.recordBytes());
            var selected = new Scope<>(map, values, limits); scopes.add(selected); return selected;
        });
    }

    <K, V> Scope<K, V> open(Family family, String scope, PersistentMapCodec<K> keys,
            PersistentMapCodec<V> values, StoredInsertionOrderedMap.Limits limits) {
        var i = limits.indexes();
        return open(family, scope, adapt(keys), adapt(values), new InsertionOrderedStorage.Limits(
                i.nodeBytes(), i.keyBytes(), i.valueBytes(), i.descriptorBytes(), i.cachedNodes(),
                limits.maximumRecordBytes(), limits.maximumPinnedBytes(), limits.maximumPinnedEntries()));
    }
    private static <T> InsertionOrderedStorage.Codec<T> adapt(PersistentMapCodec<T> codec) {
        return new InsertionOrderedStorage.Codec<>() {
            public String identity() { return codec.identity(); }
            public T prepare(T value) { return codec.prepareForStorage(value); }
            public byte[] encode(T value) { return codec.encode(value); }
            public T decode(byte[] bytes) { return codec.decode(bytes); }
        };
    }

    /** Encodes every selected map, then flushes their mutations into the attempt; does not publish. */
    public void stage() {
        context.protect(() -> { selections.forEach(Runnable::run); scopes.forEach(scope -> { scope.guard(); scope.map.select(); }); return null; });
        context.flush();
    }

    /** Identity-pinned point map. Closing a view invalidates it without publishing. */
    public final class Scope<K, V> extends AbstractMap<K, V> implements AutoCloseable {
        private LogicalRecordMap<K, V> map;
        private final InsertionOrderedStorage.Codec<V> codec;
        private final InsertionOrderedStorage.Limits limits;
        private final Map<K, V> pins = new HashMap<>();
        private final Map<K, Integer> charges = new HashMap<>();
        private long bytes;
        private boolean closed;
        private java.util.function.BiConsumer<K, V> validator = (key, value) -> { };
        Scope<K, V> validateRows(java.util.function.BiConsumer<K, V> validator) {
            guard(); if (!pins.isEmpty()) throw new IllegalStateException("Validation must be bound before reading");
            this.validator = Objects.requireNonNull(validator); return this;
        }
        private Scope(LogicalRecordMap<K, V> map, InsertionOrderedStorage.Codec<V> codec,
                InsertionOrderedStorage.Limits limits) {
            this.map = map; this.codec = codec; this.limits = limits;
            if (limits.pinnedEntries() < 0 || limits.pinnedBytes() < 0) throw new IllegalArgumentException("Invalid logical pin capacity");
        }
        private void guard() { context.checkOpen(); if (closed) throw new IllegalStateException("Logical point view is closed"); }
        private V pin(K key, V value) {
            if (value != null) validator.accept(key, value);
            int charge = value == null ? 0 : codec.encode(value).length;
            long next = Math.addExact(bytes - charges.getOrDefault(key, 0), charge);
            if (charge > limits.recordBytes() || next > limits.pinnedBytes()
                    || !pins.containsKey(key) && pins.size() >= limits.pinnedEntries())
                throw new IllegalStateException("Logical point pin capacity exceeded");
            pins.put(key, value); charges.put(key, charge); bytes = next; return value;
        }
        @SuppressWarnings("unchecked")
        @Override public V get(Object key) {
            return context.protect(() -> { guard(); K typed = (K) Objects.requireNonNull(key);
                return pins.containsKey(typed) ? pins.get(typed) : pin(typed, map.get(typed)); });
        }
        @Override public boolean containsKey(Object key) { return get(key) != null; }
        @Override public V put(K key, V value) {
            return context.protect(() -> { guard(); Objects.requireNonNull(value); V prior = get(key);
                V prepared = Objects.requireNonNull(codec.prepare(value));
                pin(key, prepared); map = map.put(key, prepared); return prior; });
        }
        @SuppressWarnings("unchecked")
        @Override public V remove(Object key) {
            return context.protect(() -> { guard(); V prior = get(key); pin((K) key, null);
                map = map.remove((K) key); return prior; });
        }
        @Override public int size() { guard(); return map.entries().size(); }
        @Override public Set<Entry<K, V>> entrySet() {
            guard(); return new AbstractSet<>() {
                public int size() { return Scope.this.size(); }
                public Iterator<Entry<K, V>> iterator() {
                    guard(); var rows = map.entries().iterator();
                    return new Iterator<>() {
                        K last; boolean removable;
                        public boolean hasNext() { guard(); return rows.hasNext(); }
                        public Entry<K, V> next() {
                            guard(); var row = rows.next(); last = row.getKey(); removable = true;
                            return new SimpleEntry<>(last, Scope.this.get(last)) {
                                @Override public V setValue(V value) {
                                    V old = Scope.this.put(getKey(), value); super.setValue(value); return old;
                                }
                            };
                        }
                        public void remove() { guard(); if (!removable) throw new IllegalStateException();
                            Scope.this.remove(last); removable = false; }
                    };
                }
            };
        }
        @Override public void close() { closed = true; pins.clear(); charges.clear(); bytes = 0; }
    }
}
