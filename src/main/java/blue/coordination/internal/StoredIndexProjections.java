package blue.coordination.internal;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import static blue.coordination.internal.SessionStorageWire.*;

/** Bounded private registry of selected nested maps; never a catalog or decoded-history cache. */
final class StoredIndexProjections implements AutoCloseable {
    private record Key(String family, Object owner) { }
    private record Entry<K, V>(byte[] descriptor, Object logicalIdentity, PersistentOrderedMap.ValueProjection<K, V, V> projection,
            BiFunction<K, V, V> writer) { }
    private final Map<Key, Entry<?, ?>> entries = new HashMap<>();
    private final Map<Object, Entry<?, ?>> tokens = new IdentityHashMap<>();
    private final int maximumMaps;
    private boolean closed;
    StoredIndexProjections(int maximumMaps) {
        if (maximumMaps < 1) throw new IllegalArgumentException("Selected index-map bound must be positive");
        this.maximumMaps = maximumMaps;
    }
    <K, V> PersistentOrderedMap<K, V> open(String family, Object owner, PersistentOrderedMap<K, V> source,
            BiFunction<K, V, V> read, BiFunction<K, V, V> write) {
        return open(family, owner, source, read, write, null);
    }
    @SuppressWarnings("unchecked")
    synchronized <K, V> PersistentOrderedMap<K, V> open(String family, Object owner, PersistentOrderedMap<K, V> source,
            BiFunction<K, V, V> read, BiFunction<K, V, V> write, Consumer<K> absent) {
        require(!closed, "Selected index scope is closed");
        var key = new Key(Objects.requireNonNull(family), Objects.requireNonNull(owner));
        byte[] descriptor = source.isLogical() ? null : source.storedRootDescriptor();
        Object logicalIdentity = source.isLogical() ? source.logicalSnapshotIdentity() : null;
        var previous = (Entry<K, V>) entries.get(key);
        if (previous != null) {
            require(Arrays.equals(previous.descriptor(), descriptor) && Objects.equals(previous.logicalIdentity(), logicalIdentity), "Selected nested index changed within one pinned scope");
            return previous.projection().open();
        }
        require(entries.size() < maximumMaps, "Selected index-map scope bound exceeded");
        var projection = source.projectValues((k, v) -> {
            require(!closed, "Selected index scope is closed"); return read.apply(k, v);
        }, absent == null ? null : k -> { require(!closed, "Selected index scope is closed"); absent.accept(k); });
        var entry = new Entry<>(descriptor, logicalIdentity, projection, write);
        entries.put(key, entry); tokens.put(projection, entry); return projection.open();
    }
    @SuppressWarnings("unchecked")
    synchronized <K, V> PersistentOrderedMap<K, V> stage(PersistentOrderedMap<K, V> map) {
        require(!closed, "Selected index scope is closed");
        if (map.valueProjectionIdentity() == null) return map; // Actual new resident bucket or unchanged physical map.
        var entry = (Entry<K, V>) tokens.get(map.valueProjectionIdentity());
        require(entry != null, "Index map belongs to another selected scope");
        return entry.projection().stage(map, entry.writer());
    }
    @Override public synchronized void close() { closed = true; entries.clear(); tokens.clear(); }
}
