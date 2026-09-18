package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * One owned mutable map over independently pinned immutable indexes. This is
 * not a publication port. Keys are immutable, non-null, and ordered consistently
 * with equals; values are non-null. Closed codecs must encode without writes.
 * Returned values stay identity-pinned until replacement, removal, or close.
 */
final class StoredInsertionOrderedMap<K, V> extends AbstractMap<K, V> implements AutoCloseable {
    private static final String FORMAT = "blue-coordination/insertion-map/1";

    record Limits(PersistentMapStorage.Limits indexes, int maximumRecordBytes,
            long maximumPinnedBytes, int maximumPinnedEntries) {
        Limits {
            Objects.requireNonNull(indexes, "indexes");
            if (maximumRecordBytes <= 0 || maximumPinnedBytes < 0 || maximumPinnedEntries < 0)
                throw new IllegalArgumentException("Invalid insertion-map physical limits");
        }
    }

    /** The selected owner must authenticate/fence both roots and this counter together. */
    record Snapshot(byte[] keys, byte[] order, long nextSequence) {
        Snapshot {
            keys = Objects.requireNonNull(keys, "keys").clone();
            order = Objects.requireNonNull(order, "order").clone();
            if (nextSequence < 0) throw new IllegalArgumentException("Negative insertion sequence");
        }
        @Override public byte[] keys() { return keys.clone(); }
        @Override public byte[] order() { return order.clone(); }
    }

    /** Configuration/closed codecs, not a Java type registry or public authority factory. */
    static final class Storage<K, V> {
        private final CoordinationImmutableObjectStore objects;
        private final Limits limits;
        private final PersistentMapCodec<K> keyCodec;
        private final PersistentMapCodec<V> valueCodec;
        private final String binding;
        private final StoreIndexCodecs.Binding<K, Reference> keys;
        private final StoreIndexCodecs.Binding<Long, K> order;

        Storage(CoordinationImmutableObjectStore objects, Limits limits, String family,
                String orderingIdentity, Comparator<? super K> comparator,
                PersistentMapCodec<K> keyCodec, PersistentMapCodec<V> valueCodec) {
            this.objects = Objects.requireNonNull(objects);
            this.limits = Objects.requireNonNull(limits);
            this.keyCodec = Objects.requireNonNull(keyCodec);
            this.valueCodec = Objects.requireNonNull(valueCodec);
            Objects.requireNonNull(comparator);
            this.binding = digest(SessionStorageWire.encode(limits.indexes().descriptorBytes(), w -> {
                w.text(FORMAT); w.text(Objects.requireNonNull(family)); w.text(Objects.requireNonNull(orderingIdentity));
                w.text(keyCodec.identity()); w.text(valueCodec.identity());
            }));
            var codecs = new StoreIndexCodecs(objects, limits.indexes());
            var references = codecs.codec("insertion-map/reference/" + binding, (Writer w, Reference reference) -> {
                w.longValue(reference.sequence()); w.text(reference.address()); w.integer(reference.bytes());
            }, r -> {
                long sequence = r.longValue(); String address = r.text(r.remaining()); int bytes = r.integer();
                require(sequence >= 0 && address.matches("[0-9a-f]{64}")
                        && bytes > 0 && bytes <= limits.maximumRecordBytes(), "Invalid insertion-map reference");
                return new Reference(sequence, address, bytes);
            });
            var sequences = codecs.codec("insertion-map/sequence", Writer::longValue, r -> {
                long value = r.longValue(); require(value >= 0, "Negative insertion-map sequence"); return value;
            });
            keys = codecs.binding("insertion-map/keys/" + binding, comparator, keyCodec, references);
            order = codecs.binding("insertion-map/order/" + binding, Long::compare, sequences, keyCodec);
        }

        StoredInsertionOrderedMap<K, V> empty() {
            return physical(() -> new StoredInsertionOrderedMap<>(this, keys.open(null), order.open(null), 0));
        }

        StoredInsertionOrderedMap<K, V> open(Snapshot snapshot) {
            return physical(() -> {
                Objects.requireNonNull(snapshot, "snapshot");
                var selectedKeys = keys.open(snapshot.keys());
                var selectedOrder = order.open(snapshot.order());
                require(selectedKeys.size() == selectedOrder.size(), "Insertion-map root counts differ");
                var last = selectedOrder.maximum().entry();
                require(last == null || last.getKey() < snapshot.nextSequence(), "Insertion-map counter precedes retained order");
                return new StoredInsertionOrderedMap<>(this, selectedKeys, selectedOrder, snapshot.nextSequence());
            });
        }

        private byte[] keyBytes(K key) {
            byte[] bytes = Objects.requireNonNull(keyCodec.encode(key), "encoded key");
            require(bytes.length <= limits.indexes().keyBytes(), "Insertion-map key exceeds byte bound");
            return bytes;
        }

        private byte[] record(K key, V value) {
            byte[] keyBytes = keyBytes(key);
            // This hook is mutation-only. It may retain closed immutable
            // dependencies, but encode/decode/canonical checks may not do so.
            V prepared = Objects.requireNonNull(valueCodec.prepareForStorage(value), "prepared value");
            byte[] valueBytes = Objects.requireNonNull(valueCodec.encode(prepared), "encoded value");
            return SessionStorageWire.encode(limits.maximumRecordBytes(), w -> {
                w.text(FORMAT); w.text(binding); w.bytes(keyBytes); w.bytes(valueBytes);
            });
        }

        private V read(K key, Reference reference) {
            byte[] bytes = objects.get(reference.address(), reference.bytes())
                    .orElseThrow(() -> new blue.coordination.api.storage.CoordinationObjectStorageException("Missing insertion-map payload"));
            require(bytes.length == reference.bytes() && digest(bytes).equals(reference.address()), "Insertion-map payload integrity failure");
            return SessionStorageWire.decode(bytes, limits.maximumRecordBytes(), r -> {
                require(FORMAT.equals(r.text(r.remaining())) && binding.equals(r.text(r.remaining())), "Foreign insertion-map payload binding");
                require(Arrays.equals(keyBytes(key), r.bytes(limits.indexes().keyBytes())), "Insertion-map payload has foreign key");
                byte[] valueBytes = r.bytes(limits.maximumRecordBytes());
                V value = Objects.requireNonNull(valueCodec.decode(valueBytes), "decoded value");
                require(Arrays.equals(valueBytes, valueCodec.encode(value)), "Noncanonical insertion-map payload");
                return value;
            });
        }
    }

    private record Reference(long sequence, String address, int bytes) {}
    private record Pinned<V>(Reference reference, V value) {}
    private final Storage<K, V> storage;
    private PersistentOrderedMap<K, Reference> keys;
    private PersistentOrderedMap<Long, K> order;
    private final Map<Long, Pinned<V>> pinned = new HashMap<>();
    private long nextSequence;
    private long pinnedBytes;
    private long structuralVersion;
    private boolean closed;

    private StoredInsertionOrderedMap(Storage<K, V> storage, PersistentOrderedMap<K, Reference> keys,
            PersistentOrderedMap<Long, K> order, long nextSequence) {
        this.storage = storage; this.keys = keys; this.order = order; this.nextSequence = nextSequence;
    }

    synchronized Snapshot snapshot() {
        ensureOpen(); return new Snapshot(keys.storedRootDescriptor(), order.storedRootDescriptor(), nextSequence);
    }

    @Override public synchronized int size() { ensureOpen(); return keys.size(); }
    @Override public synchronized boolean isEmpty() { return size() == 0; }

    @Override public synchronized boolean containsKey(Object key) {
        ensureOpen(); return physical(() -> reference(castKey(key)) != null);
    }

    @Override public synchronized V get(Object key) {
        ensureOpen(); return physical(() -> {
            K selected = castKey(key); var reference = reference(selected);
            return reference == null ? null : value(selected, reference);
        });
    }

    @Override public synchronized V put(K key, V value) {
        ensureOpen(); Objects.requireNonNull(key, "key"); Objects.requireNonNull(value, "value");
        return physical(() -> {
            var prior = reference(key);
            V previous = prior == null ? null : value(key, prior);
            long sequence = prior == null ? nextSequence : prior.sequence();
            long preparedNext = prior == null ? Math.addExact(nextSequence, 1) : nextSequence;
            byte[] bytes = storage.record(key, value);
            var next = new Reference(sequence, digest(bytes), bytes.length);
            requireCapacity(next, prior);
            byte[] retained = storage.objects.putIfAbsent(next.address(), bytes.clone());
            require(Arrays.equals(bytes, retained), "Insertion-map retention returned different bytes");
            var preparedKeys = keys.put(key, next).map();
            var preparedOrder = prior == null ? order.put(sequence, key).map() : order;
            // All IO has succeeded before the owned mutable state changes.
            keys = preparedKeys; order = preparedOrder; nextSequence = preparedNext;
            if (prior == null) structuralVersion++;
            pin(next, value);
            return previous;
        });
    }

    @Override public synchronized V putIfAbsent(K key, V value) {
        V previous = get(key); return previous == null ? put(key, value) : previous;
    }

    @Override public synchronized V remove(Object key) {
        ensureOpen(); return physical(() -> {
            K selected = castKey(key); var prior = reference(selected);
            if (prior == null) return null;
            V previous = value(selected, prior);
            var preparedKeys = keys.remove(selected).map();
            var preparedOrder = order.remove(prior.sequence()).map();
            keys = preparedKeys; order = preparedOrder; structuralVersion++;
            var removed = pinned.remove(prior.sequence());
            if (removed != null) pinnedBytes -= removed.reference().bytes();
            return previous;
        });
    }

    @Override public synchronized boolean remove(Object key, Object value) {
        V current = get(key);
        if (current == null || !current.equals(value)) return false;
        remove(key); return true;
    }

    @Override public synchronized void clear() {
        ensureOpen();
        if (!keys.isEmpty()) structuralVersion++;
        keys = keys.emptyCopy(); order = order.emptyCopy(); pinned.clear(); pinnedBytes = 0;
        // Sequence is a physical insertion coordinate; retaining it prevents
        // stale references from aliasing newly inserted rows in this scope.
    }

    private Reference reference(K key) {
        var reference = keys.get(key);
        if (reference != null) require(reference.sequence() < nextSequence
                && key.equals(order.get(reference.sequence())), "Insertion-map key/order binding differs");
        return reference;
    }

    private V value(K key, Reference reference) {
        var hit = pinned.get(reference.sequence());
        if (hit != null) {
            require(hit.reference().equals(reference), "Insertion-map pinned reference differs");
            return hit.value();
        }
        requireCapacity(reference, null);
        V value = storage.read(key, reference);
        pin(reference, value); return value;
    }

    private void requireCapacity(Reference next, Reference replaced) {
        long oldBytes = replaced == null || !pinned.containsKey(replaced.sequence()) ? 0 : replaced.bytes();
        int entries = pinned.size() - (oldBytes == 0 ? 0 : 1);
        require(entries < storage.limits.maximumPinnedEntries()
                && next.bytes() <= storage.limits.maximumPinnedBytes() - (pinnedBytes - oldBytes),
                "Insertion-map selected scope pin budget exceeded");
    }

    private void pin(Reference reference, V value) {
        var prior = pinned.put(reference.sequence(), new Pinned<>(reference, value));
        if (prior != null) pinnedBytes -= prior.reference().bytes();
        pinnedBytes += reference.bytes();
    }

    @Override public Set<K> keySet() {
        synchronized (this) { ensureOpen(); }
        return new AbstractSet<>() {
            @Override public int size() { return StoredInsertionOrderedMap.this.size(); }
            @Override public boolean contains(Object key) { return containsKey(key); }
            @Override public void clear() { StoredInsertionOrderedMap.this.clear(); }
            @Override public Iterator<K> iterator() { return new OrderedIterator<>() {
                @Override K selected(K key, Reference reference) { return key; }
            }; }
        };
    }

    @Override public Set<Entry<K, V>> entrySet() {
        synchronized (this) { ensureOpen(); }
        return new AbstractSet<>() {
            @Override public int size() { return StoredInsertionOrderedMap.this.size(); }
            @Override public void clear() { StoredInsertionOrderedMap.this.clear(); }
            @Override public Iterator<Entry<K, V>> iterator() { return new OrderedIterator<>() {
                @Override Entry<K, V> selected(K key, Reference reference) {
                    return new SimpleEntry<>(key, value(key, reference)) {
                        @Override public V setValue(V replacement) {
                            synchronized (StoredInsertionOrderedMap.this) {
                                ensureOpen(); var current = physical(() -> reference(key));
                                if (current == null || current.sequence() != reference.sequence()) throw new ConcurrentModificationException();
                                V previous = put(key, replacement); super.setValue(replacement); return previous;
                            }
                        }
                    };
                }
            }; }
        };
    }

    private abstract class OrderedIterator<T> implements Iterator<T> {
        private long expectedVersion;
        private Long after;
        private K removable;
        private Map.Entry<Long, K> next;
        private boolean selectedNext;
        OrderedIterator() { synchronized (StoredInsertionOrderedMap.this) { ensureOpen(); expectedVersion = structuralVersion; } }
        abstract T selected(K key, Reference reference);
        private void check() {
            ensureOpen(); if (expectedVersion != structuralVersion) throw new ConcurrentModificationException();
        }
        private void findNext() {
            if (!selectedNext) {
                next = after == null ? order.minimum().entry() : order.higherThan(after).entry();
                selectedNext = true;
            }
        }
        @Override public boolean hasNext() {
            synchronized (StoredInsertionOrderedMap.this) { check(); physical(() -> { findNext(); return true; }); return next != null; }
        }
        @Override public T next() {
            synchronized (StoredInsertionOrderedMap.this) {
                check(); physical(() -> { findNext(); return true; });
                if (next == null) throw new NoSuchElementException();
                return physical(() -> {
                    var reference = reference(next.getValue());
                    require(reference != null && reference.sequence() == next.getKey(), "Insertion-map order/key binding differs");
                    T result = selected(next.getValue(), reference);
                    after = next.getKey(); removable = next.getValue(); selectedNext = false; next = null;
                    return result;
                });
            }
        }
        @Override public void remove() {
            synchronized (StoredInsertionOrderedMap.this) {
                check(); if (removable == null) throw new IllegalStateException();
                StoredInsertionOrderedMap.this.remove(removable); removable = null; expectedVersion = structuralVersion;
            }
        }
    }

    synchronized int pinnedEntries() { ensureOpen(); return pinned.size(); }
    synchronized long pinnedBytes() { ensureOpen(); return pinnedBytes; }
    @Override public synchronized void close() {
        closed = true; pinned.clear(); pinnedBytes = 0; keys = null; order = null;
    }
    private void ensureOpen() { require(!closed, "Insertion-map owner scope is closed"); }
    @SuppressWarnings("unchecked") private K castKey(Object key) { return (K) Objects.requireNonNull(key, "key"); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
