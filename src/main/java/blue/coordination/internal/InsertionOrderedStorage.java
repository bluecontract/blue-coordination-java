package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/**
 * Internal cross-package bridge for closed SDK storage records. This transports
 * selected physical state, not semantic authority or a host publication API.
 * Record/key codecs must be bounded, deterministic, and library-owned. Returned
 * records remain identity-pinned; scope limits fail noncommitting, not by eviction.
 *
 * @param <K> immutable non-null key, ordered consistently with equality
 * @param <V> immutable non-null closed record
 */
public final class InsertionOrderedStorage<K, V> {
    /** Closed deterministic transport; encoding/decoding must never prewrite. */
    public interface Codec<T> {
        /** Exact version-bound physical identity. @return codec identity */
        String identity();
        /** Mutation-only dependency retention. @param value original value @return prepared equivalent */
        default T prepare(T value) { return value; }
        /** Pure bounded encoding. @param value value @return owned bytes */
        byte[] encode(T value);
        /** Pure bounded decoding. @param bytes exact bytes @return immutable/detached value */
        T decode(byte[] bytes);
    }

    /** Explicit physical bounds; encoded pin charge is not measured JVM heap. */
    public record Limits(int nodeBytes, int keyBytes, int indexValueBytes, int descriptorBytes,
            int cachedNodes, int recordBytes, long pinnedBytes, int pinnedEntries) { }

    /** Independently selected map roots and counter; caller supplies publication fencing. */
    public record Snapshot(byte[] keys, byte[] order, long nextSequence) {
        /** Defensively owns descriptor bytes. */
        public Snapshot { keys = Objects.requireNonNull(keys).clone(); order = Objects.requireNonNull(order).clone(); }
        @Override public byte[] keys() { return keys.clone(); }
        @Override public byte[] order() { return order.clone(); }
    }

    private final StoredInsertionOrderedMap.Storage<K, V> storage;

    /**
     * Binds one closed physical family.
     * @param objects immutable object storage
     * @param limits explicit operational bounds
     * @param family closed family identity
     * @param orderingIdentity exact key ordering identity
     * @param order key comparator consistent with equality
     * @param keys closed key codec
     * @param values closed record codec
     */
    public InsertionOrderedStorage(CoordinationImmutableObjectStore objects, Limits limits, String family,
            String orderingIdentity, Comparator<? super K> order, Codec<K> keys, Codec<V> values) {
        Objects.requireNonNull(limits);
        storage = new StoredInsertionOrderedMap.Storage<>(objects, new StoredInsertionOrderedMap.Limits(
                new PersistentMapStorage.Limits(limits.nodeBytes(), limits.keyBytes(), limits.indexValueBytes(),
                        limits.descriptorBytes(), limits.cachedNodes()), limits.recordBytes(), limits.pinnedBytes(), limits.pinnedEntries()),
                family, orderingIdentity, order, adapt(keys), adapt(values));
    }

    /** New empty owned scope. @return map scope */
    public Scope<K, V> empty() { return new Scope<>(storage.empty()); }
    /** Opens selected roots without reading record bodies. @param snapshot selected snapshot @return owned scope */
    public Scope<K, V> open(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        return new Scope<>(storage.open(new StoredInsertionOrderedMap.Snapshot(snapshot.keys(), snapshot.order(), snapshot.nextSequence())));
    }

    private static <T> PersistentMapCodec<T> adapt(Codec<T> codec) {
        Objects.requireNonNull(codec);
        return new PersistentMapCodec<>() {
            @Override public String identity() { return codec.identity(); }
            @Override public T prepareForStorage(T value) { return codec.prepare(value); }
            @Override public byte[] encode(T value) { return codec.encode(value); }
            @Override public T decode(byte[] bytes) { return codec.decode(bytes); }
        };
    }

    /** Ordinary owned Map semantics, not a concurrent-map/publication protocol. */
    public static final class Scope<K, V> extends AbstractMap<K, V> implements AutoCloseable {
        private final StoredInsertionOrderedMap<K, V> map;
        private Scope(StoredInsertionOrderedMap<K, V> map) { this.map = map; }
        /** Captures both roots and insertion counter. @return exact selected snapshot */
        public Snapshot snapshot() {
            var snapshot = map.snapshot(); return new Snapshot(snapshot.keys(), snapshot.order(), snapshot.nextSequence());
        }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public V get(Object key) { return map.get(key); }
        @Override public V put(K key, V value) { return map.put(key, value); }
        @Override public V putIfAbsent(K key, V value) { return map.putIfAbsent(key, value); }
        @Override public V remove(Object key) { return map.remove(key); }
        @Override public boolean remove(Object key, Object value) { return map.remove(key, value); }
        @Override public void clear() { map.clear(); }
        @Override public Set<K> keySet() { return map.keySet(); }
        @Override public Set<Entry<K, V>> entrySet() { return map.entrySet(); }
        @Override public void close() { map.close(); }
    }
}
