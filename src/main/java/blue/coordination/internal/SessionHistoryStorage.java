package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.Comparator;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Library-owned bounded index nodes; the enclosing session binds all lane roots together. */
final class SessionHistoryStorage {
    private static final String PREFIX = "blue-coordination/session-history/1/";
    private final CoordinationImmutableObjectStore objects;
    private final PersistentMapStorage.Limits limits;
    final PersistentMapCodec<Long> ordinals;
    final PersistentMapCodec<String> strings;
    final PersistentMapCodec<Boolean> membership;
    final PersistentMapCodec<ManagedLineageIndex.RetainedState> retainedStates;

    SessionHistoryStorage(CoordinationImmutableObjectStore objects, int maximumRecordBytes) {
        this.objects = objects;
        // History rows contain metadata or payload addresses, not complete revisions/results.
        int nodeBytes = Math.min(maximumRecordBytes, 256 * 1024);
        limits = new PersistentMapStorage.Limits(nodeBytes, Math.min(nodeBytes, 64 * 1024),
                Math.min(nodeBytes, 128 * 1024), Math.min(nodeBytes, 16 * 1024), 32);
        ordinals = codec("ordinal", Writer::longValue, in -> {
            long value = in.longValue(); require(value >= 0, "Negative history ordinal"); return value;
        });
        strings = codec("text", Writer::text, SessionRecordCodec::text);
        membership = codec("membership", (out, value) -> {
            require(Boolean.TRUE.equals(value), "History membership must be true"); out.bool(true);
        }, in -> { require(in.bool(), "History membership must be true"); return true; });
        retainedStates = codec("retained-state", (out, row) -> {
            out.text(row.documentId().value()); out.longValue(row.epoch()); out.text(row.blueId());
        }, in -> new ManagedLineageIndex.RetainedState(
                blue.coordination.api.DocumentId.of(SessionRecordCodec.text(in)), in.longValue(), SessionRecordCodec.text(in)));
    }

    <T> PersistentMapCodec<T> codec(String name, BiConsumer<Writer, T> write, Function<Reader, T> read) {
        return new PersistentMapCodec<>() {
            @Override public String identity() { return PREFIX + name; }
            @Override public byte[] encode(T value) { return SessionStorageWire.encode(limits.valueBytes(), out -> write.accept(out, value)); }
            @Override public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, limits.valueBytes(), read); }
        };
    }

    <K, V> PersistentOrderedMap<K, V> retain(String lane, PersistentOrderedMap<K, V> map,
            PersistentMapCodec<K> keys, PersistentMapCodec<V> values) {
        return map.storedCopy(PREFIX + lane, keys, values, objects, limits);
    }

    <K, V> PersistentOrderedMap<K, V> open(Reader in, String lane, Comparator<K> order,
            PersistentMapCodec<K> keys, PersistentMapCodec<V> values) {
        return PersistentOrderedMap.stored(order, PREFIX + lane, keys, values, objects, limits,
                in.bytes(limits.descriptorBytes()));
    }

    static void root(Writer out, PersistentOrderedMap<?, ?> map) { out.bytes(map.storedRootDescriptor()); }
}
