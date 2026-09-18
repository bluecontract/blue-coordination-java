package blue.coordination.sdk;

import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.language.processor.NoncommittingExecutionException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Five selected point-backed SDK maps; engine restoration/publication remains the caller's responsibility. */
final class SdkRuntimePointMaps implements AutoCloseable {
    private static final String FORMAT = "blue-coordination/sdk-runtime-point-maps/1";
    enum Kind { TIMELINES, INTENTS, RESULTS, ENTRIES, SOURCE_RESULTS }
    /** Descriptor pins and point-row limits are distinct; neither measures Java heap. */
    record Limits(InsertionOrderedStorage.Limits maps, SdkPointStorage.Limits points, int codecBytes) {
        Limits {
            Objects.requireNonNull(maps); Objects.requireNonNull(points);
            if (codecBytes <= 0) throw new IllegalArgumentException("Invalid SDK map codec bound");
            // Each of five maps can pin at most this many complete bounded rows.
            Math.multiplyExact(5L * maps.pinnedEntries(), points.maximumRowBytes());
        }
    }
    record Snapshot(Map<Kind, InsertionOrderedStorage.Snapshot> roots) {
        Snapshot {
            var owned = new EnumMap<Kind, InsertionOrderedStorage.Snapshot>(Kind.class);
            owned.putAll(Objects.requireNonNull(roots));
            if (!owned.keySet().equals(Set.of(Kind.values())) || owned.containsValue(null))
                throw invalid("SDK map snapshot is missing a selected family");
            roots = Collections.unmodifiableMap(owned);
        }
    }
    private record Row<K, V>(K key, SdkPointStorage.Descriptor descriptor, V value) { }

    private final SdkCoordinationRuntime owner;
    private final Limits limits;
    private final SdkStorageCodec codec;
    private final SdkPointStorage points;
    private final SdkPointStorage.Scope pointScope;
    private final String configurationBinding;
    private final Map<Kind, NativeMap<?, ?>> all = new EnumMap<>(Kind.class);
    private NativeMap<String, TimelineHandle> timelines;
    private NativeMap<String, SdkCoordinationRuntime.EntryIntent> intents;
    private NativeMap<String, EntryResult> results;
    private NativeMap<String, SdkCoordinationRuntime.CoreEntryRef> entries;
    private NativeMap<SourceHistoryPrerequisite, DrainResult> sourceResults;
    private volatile boolean closed;

    static SdkRuntimePointMaps empty(SdkCoordinationRuntime owner, CoordinationImmutableObjectStore objects, Limits limits) {
        return physical(() -> new SdkRuntimePointMaps(owner, objects, limits, null));
    }
    static SdkRuntimePointMaps open(SdkCoordinationRuntime owner, CoordinationImmutableObjectStore objects,
            Limits limits, Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return physical(() -> new SdkRuntimePointMaps(owner, objects, limits, snapshot));
    }

    /** Explicit initial resident conversion; cold open never calls this or enumerates runtime maps. */
    static SdkRuntimePointMaps retain(SdkCoordinationRuntime owner, CoordinationImmutableObjectStore objects, Limits limits) {
        var retained = empty(owner, objects, limits);
        try {
            var source = owner.storedMaps();
            retained.timelines.putAll(source.timelines());
            retained.entries.putAll(source.entries()); // intents require exact core-entry membership
            retained.intents.putAll(source.intents());
            retained.results.putAll(source.results());
            retained.sourceResults.putAll(source.sourceResults());
            return retained;
        } catch (RuntimeException | Error failure) { retained.close(); throw failure; }
    }

    private SdkRuntimePointMaps(SdkCoordinationRuntime owner, CoordinationImmutableObjectStore objects,
            Limits limits, Snapshot snapshot) {
        this.owner = Objects.requireNonNull(owner); this.limits = Objects.requireNonNull(limits);
        this.codec = new SdkStorageCodec(new Object(), limits.codecBytes());
        configurationBinding = digest(codec.encode(owner.storageConfiguration()));
        points = new SdkPointStorage(objects, limits.points(), owner.storageConfiguration());
        pointScope = owner.openPointStorage(points, new SdkPointStorage.References() {
            @Override public SdkPointStorage.Descriptor timeline(String id) { return descriptor(timelines, id); }
            @Override public SdkPointStorage.Descriptor coreEntry(String id) { return descriptor(entries, id); }
        });
        try {
            timelines = create(objects, snapshot, Kind.TIMELINES, String.class, points::retainTimeline, pointScope::timeline);
            entries = create(objects, snapshot, Kind.ENTRIES, String.class,
                    (key, value) -> points.retainCoreEntry(key, SdkStorageCodec.CoreEntrySnapshot.from(value.entry())),
                    (key, descriptor) -> new SdkCoordinationRuntime.CoreEntryRef(pointScope.coreEntry(key, descriptor)));
            intents = create(objects, snapshot, Kind.INTENTS, String.class, points::retainIntent, pointScope::intent);
            results = create(objects, snapshot, Kind.RESULTS, String.class, points::retainEntryResult, pointScope::entryResult);
            sourceResults = create(objects, snapshot, Kind.SOURCE_RESULTS, SourceHistoryPrerequisite.class,
                    points::retainSourceResult, pointScope::sourceResult);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    synchronized SdkCoordinationRuntime.StoredMaps maps() {
        guard(); return new SdkCoordinationRuntime.StoredMaps(timelines, intents, results, entries, sourceResults);
    }
    synchronized Snapshot snapshot() {
        guard(); var roots = new EnumMap<Kind, InsertionOrderedStorage.Snapshot>(Kind.class);
        all.forEach((kind, map) -> roots.put(kind, map.backing.snapshot())); return new Snapshot(roots);
    }

    private <K, V> NativeMap<K, V> create(CoordinationImmutableObjectStore objects, Snapshot snapshot, Kind kind,
            Class<K> keyType, BiFunction<K, V, SdkPointStorage.Descriptor> retain,
            BiFunction<K, SdkPointStorage.Descriptor, V> read) {
        var keys = new InsertionOrderedStorage.Codec<K>() {
            @Override public String identity() { return FORMAT + "/key/" + keyType.getSimpleName() + "/" + SdkStorageCodec.FORMAT; }
            @Override public byte[] encode(K key) { return codec.encode(key); }
            @Override public K decode(byte[] bytes) { return codec.decode(bytes, keyType); }
        };
        // All fields, including cutoff/diagnostic/physical coordinates, participate;
        // a claimed selectionIdentity alone is not the key's equality contract.
        Comparator<K> order = (left, right) -> Arrays.compareUnsigned(keys.encode(left), keys.encode(right));
        var rows = new InsertionOrderedStorage.Codec<Row<K, V>>() {
            @Override public String identity() { return FORMAT + "/" + kind + "/" + configurationBinding; }
            @Override public byte[] encode(Row<K, V> row) {
                return codec.encode(List.of(FORMAT, kind.name(), row.key(), row.descriptor().bytes()));
            }
            @Override public Row<K, V> decode(byte[] bytes) {
                var fields = codec.decode(bytes, List.class);
                require(fields.size() == 4 && FORMAT.equals(fields.get(0)) && kind.name().equals(fields.get(1)), "Foreign SDK map row kind");
                K key = keyType.cast(fields.get(2));
                var descriptor = points.descriptor((byte[]) fields.get(3));
                return new Row<>(key, descriptor, read.apply(key, descriptor));
            }
        };
        var storage = new InsertionOrderedStorage<>(objects, limits.maps(), FORMAT + "/" + kind + "/" + configurationBinding,
                FORMAT + "/complete-canonical-key-order", order, keys, rows);
        var map = new NativeMap<>(kind, snapshot == null ? storage.empty() : storage.open(snapshot.roots().get(kind)), retain, read);
        all.put(kind, map); return map;
    }

    private <K, V> SdkPointStorage.Descriptor descriptor(NativeMap<K, V> map, K key) {
        guard(); require(map != null, "SDK point dependency map is not initialized");
        var row = map.row(key); return row == null ? null : row.descriptor();
    }

    private final class NativeMap<K, V> extends AbstractMap<K, V> implements AutoCloseable {
        private final Kind kind;
        private final InsertionOrderedStorage.Scope<K, Row<K, V>> backing;
        private final BiFunction<K, V, SdkPointStorage.Descriptor> retain;
        private final BiFunction<K, SdkPointStorage.Descriptor, V> read;
        private NativeMap(Kind kind, InsertionOrderedStorage.Scope<K, Row<K, V>> backing,
                BiFunction<K, V, SdkPointStorage.Descriptor> retain, BiFunction<K, SdkPointStorage.Descriptor, V> read) {
            this.kind = kind; this.backing = backing; this.retain = retain; this.read = read;
        }
        @Override public int size() { guard(); return backing.size(); }
        @Override public boolean containsKey(Object key) { guard(); return backing.containsKey(key); }
        private Row<K, V> row(Object key) {
            guard(); var row = backing.get(key);
            require(row == null || row.key().equals(key), "SDK map row differs from selected key"); return row;
        }
        @Override public V get(Object key) { var row = row(key); return row == null ? null : row.value(); }
        @Override public V put(K key, V value) {
            guard(); Objects.requireNonNull(value);
            return physical(() -> {
                row(key); // authenticate any selected previous value before changing either root
                var descriptor = retain.apply(key, value);
                V verified = read.apply(key, descriptor);
                validateOriginalOwner(kind, value, verified);
                var prior = backing.put(key, new Row<>(key, descriptor, value));
                return prior == null ? null : prior.value();
            });
        }
        @Override public V putIfAbsent(K key, V value) { V old = get(key); return old == null ? put(key, value) : old; }
        @Override public V remove(Object key) { row(key); var old = backing.remove(key); return old == null ? null : old.value(); }
        @Override public boolean remove(Object key, Object value) {
            var old = row(key);
            return old != null && old.value().equals(value) && backing.remove(key, old);
        }
        @Override public void clear() { guard(); backing.clear(); }
        // All five views belong to one point owner. Closing any installed view
        // releases that owned group once, even if SDK.close has already marked
        // its owner closed. Ordinary clear/read operations remain guarded.
        @Override public void close() { SdkRuntimePointMaps.this.close(); }
        @Override public Set<K> keySet() {
            guard(); return new AbstractSet<>() {
                @Override public int size() { return NativeMap.this.size(); }
                @Override public boolean contains(Object key) { return NativeMap.this.containsKey(key); }
                @Override public void clear() { NativeMap.this.clear(); }
                @Override public Iterator<K> iterator() {
                    guard(); var iterator = backing.keySet().iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { guard(); return iterator.hasNext(); }
                        @Override public K next() { guard(); return iterator.next(); }
                        @Override public void remove() { guard(); iterator.remove(); }
                    };
                }
            };
        }
        @Override public Set<Entry<K, V>> entrySet() {
            guard(); return new AbstractSet<>() {
                @Override public int size() { return NativeMap.this.size(); }
                @Override public void clear() { NativeMap.this.clear(); }
                @Override public Iterator<Entry<K, V>> iterator() {
                    guard(); var iterator = backing.entrySet().iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { guard(); return iterator.hasNext(); }
                        @Override public Entry<K, V> next() {
                            guard(); var selected = iterator.next();
                            require(selected.getKey().equals(selected.getValue().key()), "SDK iterated row differs from selected key");
                            return new SimpleEntry<>(selected.getKey(), selected.getValue().value()) {
                                @Override public V setValue(V value) {
                                    V previous = NativeMap.this.put(getKey(), value); super.setValue(value); return previous;
                                }
                            };
                        }
                        @Override public void remove() { guard(); iterator.remove(); }
                    };
                }
            };
        }
    }

    private static void validateOriginalOwner(Kind kind, Object original, Object verified) {
        if (kind == Kind.TIMELINES) require(((TimelineHandle) original).owner() == ((TimelineHandle) verified).owner(), "Foreign SDK Timeline owner");
        else if (kind == Kind.RESULTS) require(((EntryResult) original).entry().owner() == ((EntryResult) verified).entry().owner(), "Foreign SDK result owner");
        else if (kind == Kind.SOURCE_RESULTS) {
            var a = ((DrainResult) original).entries(); var b = ((DrainResult) verified).entries();
            require(a.size() == b.size(), "SDK source result cardinality differs");
            for (int i = 0; i < a.size(); i++) require(a.get(i).entry().owner() == b.get(i).entry().owner(), "Foreign SDK source result owner");
        }
    }
    @Override public synchronized void close() {
        if (!closed) { closed = true; all.values().forEach(map -> map.backing.close()); pointScope.close(); }
    }
    private void guard() {
        require(!closed, "SDK point map scope is closed");
        // Mandatory even on a pinned warm hit, which does not call pointScope.
        physical(owner::engine);
    }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static CoordinationObjectStorageException invalid(String message) { return new CoordinationObjectStorageException(message); }
    private static <T> T physical(Supplier<T> operation) {
        try { return operation.get(); }
        catch (NoncommittingExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CoordinationObjectStorageException("Invalid SDK point-map storage", failure); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
