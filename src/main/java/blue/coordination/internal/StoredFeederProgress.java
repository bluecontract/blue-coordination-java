package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.internal.ContractsRootFeederWindow.AttemptTicket;
import blue.coordination.internal.ContractsRootFeederWindow.DurableState.StoredMaps;
import blue.coordination.internal.ContractsRootFeederWindow.LaneId;
import blue.coordination.internal.ContractsRootFeederWindow.PendingProgress;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Exact suspended feeder rows. The caller pins/publishes both map roots with the feeder control state. */
final class StoredFeederProgress {
    private static final String FORMAT = "blue-coordination/feeder-progress/1";
    enum Kind { PENDING, REJECTED }

    record Snapshot(Map<Kind, StoredInsertionOrderedMap.Snapshot> roots) {
        Snapshot {
            roots = Map.copyOf(roots);
            if (roots.size() != Kind.values().length) throw new IllegalArgumentException("Incomplete feeder map roots");
            for (var kind : Kind.values()) Objects.requireNonNull(roots.get(kind), "Missing feeder root " + kind);
        }
    }

    private final CoordinationImmutableObjectStore objects;
    private final StoredInsertionOrderedMap.Limits limits;
    private final DocumentSessionStorage sessions;
    private final ClosureExecutionEvidenceStorageCodec execution;
    private final PublicationReceiptStorageCodec publications;

    StoredFeederProgress(CoordinationImmutableObjectStore objects, StoredInsertionOrderedMap.Limits limits,
            DocumentSessionStorage sessions, int maximumDepth) {
        this(objects, limits, sessions, maximumDepth, null);
    }

    StoredFeederProgress(CoordinationImmutableObjectStore objects, StoredInsertionOrderedMap.Limits limits,
            DocumentSessionStorage sessions, int maximumDepth, RootedStorageCache cache) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        this.sessions = Objects.requireNonNull(sessions);
        execution = new ClosureExecutionEvidenceStorageCodec(limits.maximumRecordBytes(), maximumDepth,
                new StoredClosureResultCodec(limits.maximumRecordBytes(), maximumDepth, cache).configured());
        publications = new PublicationReceiptStorageCodec(limits.maximumRecordBytes(), maximumDepth, cache);
    }

    Scope open(Snapshot snapshot, DocumentSessionStorage.OpenScope views) {
        Objects.requireNonNull(snapshot); return new Scope(snapshot, views);
    }
    Scope empty(DocumentSessionStorage.OpenScope views) { return new Scope(null, views); }

    final class Scope implements AutoCloseable {
        private final Map<Kind, StoredInsertionOrderedMap<?, ?>> owned = new EnumMap<>(Kind.class);
        private final StoredMaps maps;
        private boolean closed;

        private Scope(Snapshot selected, DocumentSessionStorage.OpenScope views) {
            Objects.requireNonNull(views, "shared views");
            try {
                PersistentMapCodec<LaneId> lanes = codec("lane", UnaryOperator.identity(), value -> encode(limits.indexes().keyBytes(), w -> lane(w, value)),
                        bytes -> decode(bytes, limits.indexes().keyBytes(), StoredFeederProgress::lane));
                PersistentMapCodec<PendingProgress> pending = codec("pending", UnaryOperator.identity(), StoredFeederProgress.this::encodePending,
                        StoredFeederProgress.this::decodePending);
                PersistentMapCodec<String> texts = codec("text", UnaryOperator.identity(), value -> encode(limits.indexes().keyBytes(), w -> w.text(value)),
                        bytes -> decode(bytes, limits.indexes().keyBytes(), SessionRecordCodec::text));
                PersistentMapCodec<RootedDeclaredBirthRejection> rejected = codec("rejected", value -> {
                    publications.encodeRejection(value, views::retainView); return value;
                }, value -> publications.encodeRejection(value, sessions::viewAddress), bytes -> publications.decodeRejection(bytes, views));
                var pendingMap = create(Kind.PENDING, selected, LANE_ORDER, lanes, pending);
                var rejectedMap = create(Kind.REJECTED, selected, EmbeddingBinding.TEXT_ORDER, texts, rejected);
                maps = new StoredMaps(new CheckedMap<>(pendingMap, value -> value.ticket().lane()),
                        new CheckedMap<>(rejectedMap, RootedDeclaredBirthRejection::terminalKey));
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        private <K, V> StoredInsertionOrderedMap<K, V> create(Kind kind, Snapshot selected,
                Comparator<K> order, PersistentMapCodec<K> keys, PersistentMapCodec<V> values) {
            var storage = new StoredInsertionOrderedMap.Storage<>(objects, limits, FORMAT + "/" + kind,
                    FORMAT + "/order/" + kind, order, keys, values);
            var map = selected == null ? storage.empty() : storage.open(selected.roots().get(kind));
            owned.put(kind, map); return map;
        }

        StoredMaps maps() { requireOpen(); return maps; }
        Snapshot snapshot() {
            requireOpen(); var roots = new EnumMap<Kind, StoredInsertionOrderedMap.Snapshot>(Kind.class);
            owned.forEach((kind, map) -> roots.put(kind, map.snapshot())); return new Snapshot(roots);
        }
        /** Explicit bootstrap/import only; a failed multi-map retain cannot expose partial roots. */
        void retain(StoredMaps original) {
            requireOpen(); require(maps.pending().isEmpty() && maps.rejected().isEmpty(), "Feeder retention needs empty maps");
            try { maps.pending().putAll(original.pending()); maps.rejected().putAll(original.rejected()); }
            catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        private void requireOpen() { require(!closed, "Closed feeder progress scope"); }
        @Override public void close() {
            if (closed) return; closed = true; owned.values().forEach(StoredInsertionOrderedMap::close);
        }

        /** Bind selected payloads to their actual index keys, not merely their individual valid bytes. */
        private final class CheckedMap<K, V> extends AbstractMap<K, V> {
            private final StoredInsertionOrderedMap<K, V> delegate;
            private final Function<V, K> keyOf;
            CheckedMap(StoredInsertionOrderedMap<K, V> delegate, Function<V, K> keyOf) {
                this.delegate = delegate; this.keyOf = keyOf;
            }
            private V checked(Object key, V value) {
                if (value != null) require(Objects.equals(key, keyOf.apply(value)), "Feeder row belongs to another key");
                return value;
            }
            @Override public int size() { requireOpen(); return delegate.size(); }
            @Override public boolean containsKey(Object key) { requireOpen(); return delegate.containsKey(key); }
            @Override public V get(Object key) { requireOpen(); return checked(key, delegate.get(key)); }
            @Override public V put(K key, V value) {
                requireOpen(); Objects.requireNonNull(value); checked(key, value);
                get(key); return delegate.put(key, value);
            }
            @Override public V remove(Object key) { requireOpen(); get(key); return delegate.remove(key); }
            @Override public Set<Entry<K, V>> entrySet() {
                requireOpen(); return new AbstractSet<>() {
                    public int size() { return CheckedMap.this.size(); }
                    public Iterator<Entry<K, V>> iterator() {
                        requireOpen(); var iterator = delegate.entrySet().iterator();
                        return new Iterator<>() {
                            public boolean hasNext() { requireOpen(); return iterator.hasNext(); }
                            public Entry<K, V> next() {
                                requireOpen(); var row = iterator.next(); checked(row.getKey(), row.getValue());
                                return new SimpleEntry<>(row) {
                                    @Override public V setValue(V value) {
                                        var old = CheckedMap.this.put(getKey(), value); super.setValue(value); return old;
                                    }
                                };
                            }
                            public void remove() { requireOpen(); iterator.remove(); }
                        };
                    }
                };
            }
        }
    }

    private byte[] encodePending(PendingProgress value) {
        return encode(limits.maximumRecordBytes(), w -> {
            w.text(FORMAT); var ticket = value.ticket(); w.text(ticket.entryBlueId()); order(w, ticket.sourceOrder());
            w.integer(ticket.cohortIndex()); lane(w, ticket.lane()); w.text(ticket.invocationIdentity());
            list(w, ticket.members(), (out, id) -> out.text(id.value()));
            list(w, value.resourceDemands(), (out, demand) -> out.bytes(execution.encodeResourceDemand(demand)));
        });
    }
    private PendingProgress decodePending(byte[] bytes) {
        return decode(bytes, limits.maximumRecordBytes(), r -> {
            require(FORMAT.equals(text(r)), "Unknown feeder pending format");
            var ticket = new AttemptTicket(text(r), order(r), r.integer(), lane(r), text(r),
                    list(r, in -> DocumentId.of(text(in))));
            return new PendingProgress(ticket, list(r, in -> execution.decodeResourceDemand(in.bytes(in.remaining()))));
        });
    }
    private static void lane(Writer out, LaneId value) {
        out.bool(value.publicLane()); list(out, value.roots(), (w, id) -> w.text(id.value()));
    }
    private static LaneId lane(Reader in) { return new LaneId(in.bool(), list(in, r -> DocumentId.of(text(r)))); }
    private static final Comparator<LaneId> LANE_ORDER = (left, right) -> {
        int order = Boolean.compare(left.publicLane(), right.publicLane()); if (order != 0) return order;
        for (int i = 0; i < Math.min(left.roots().size(), right.roots().size()); i++) {
            order = EmbeddingBinding.DOCUMENT_ORDER.compare(left.roots().get(i), right.roots().get(i));
            if (order != 0) return order;
        }
        return Integer.compare(left.roots().size(), right.roots().size());
    };
    private static <T> PersistentMapCodec<T> codec(String name, UnaryOperator<T> prepare,
            Function<T, byte[]> encoder, Function<byte[], T> decoder) {
        return new PersistentMapCodec<>() {
            public String identity() { return FORMAT + "/" + name; }
            public T prepareForStorage(T value) { return physical(() -> prepare.apply(value)); }
            public byte[] encode(T value) { return physical(() -> encoder.apply(value)); }
            public T decode(byte[] bytes) { return physical(() -> decoder.apply(bytes)); }
        };
    }
}
