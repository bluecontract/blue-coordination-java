package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Observer-instance lookup for feeder consumption/suspension. Initial execution
 * preserves existing record keys; replacements get distinct native keys. The
 * key inventory contains canonical keys once, never every retired incarnation.
 */
final class LogicalInstanceProgress<K, V> implements LogicalRecordMap.Source<K, V> {
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final OrderedRecordKey<K> keys;
    private final Comparator<K> order;
    private final Function<K, List<DocumentId>> observers;
    private final LogicalRecordMap<K, V> initial;
    private final LogicalRecordMap<Selected<K>, V> replacements;
    private final LogicalRecordMap<K, Boolean> inventory;
    private final Map<K, List<DocumentInstanceRef>> selected = new HashMap<>();

    private record Selected<K>(K key, List<DocumentInstanceRef> instances) { }

    LogicalInstanceProgress(LogicalRecordContext context, Family family, Bytes scope,
            OrderedRecordKey<K> keys, PersistentMapCodec<V> values, int keyBytes, int valueBytes,
            Function<K, List<DocumentId>> observers) {
        if (!Set.of(Family.FEEDER_PENDING, Family.FEEDER_TERMINAL, Family.FEEDER_FRONTIER).contains(family))
            throw new IllegalArgumentException("Not a feeder instance-progress family");
        this.context = context; this.instances = context.instances(valueBytes); this.keys = keys; this.observers = observers;
        order = (a, b) -> Arrays.compareUnsigned(keys.encode(a), keys.encode(b));
        initial = LogicalRecordMap.open(order, context, family, scope, keys, values, keyBytes, valueBytes);
        OrderedRecordKey<Selected<K>> selectedKeys = selectedKeys(keyBytes);
        replacements = LogicalRecordMap.open((a, b) -> Arrays.compareUnsigned(selectedKeys.encode(a), selectedKeys.encode(b)),
                context, family, new Bytes(OrderedRecordKey.tuple(scope.copy(), OrderedRecordKey.text().encode("instances/1"))),
                selectedKeys, values, keyBytes, valueBytes);
        var membership = new PersistentMapCodec<Boolean>() {
            public String identity() { return "blue-coordination/instance-progress-membership/1"; }
            public byte[] encode(Boolean value) { require(Boolean.TRUE.equals(value), "False progress membership"); return new byte[] {1}; }
            public Boolean decode(byte[] bytes) { require(Arrays.equals(bytes, new byte[] {1}), "Invalid progress membership"); return true; }
        };
        inventory = LogicalRecordMap.open(order, context, Family.INSTANCE_PROGRESS,
                new Bytes(OrderedRecordKey.tuple(OrderedRecordKey.text().encode(family.name()), scope.copy())),
                keys, membership, keyBytes, valueBytes);
    }

    LogicalRecordMap<K, V> open() {
        return LogicalRecordMap.virtual(order, context, this, this::select, value -> true);
    }

    void preflightSelectedInstances() {
        selected.values().forEach(refs -> refs.forEach(instances::requireActive));
    }

    private List<DocumentId> documents(K key) {
        var docs = List.copyOf(observers.apply(key));
        require(!docs.isEmpty(), "Instance progress requires exact observers");
        var ordered = docs.stream().sorted(EmbeddingBinding.DOCUMENT_ORDER).distinct().toList();
        require(docs.size() == ordered.size(), "Repeated instance progress observer");
        return ordered;
    }

    private List<DocumentInstanceRef> references(K key) {
        var refs = selected.computeIfAbsent(key, ignored -> documents(key).stream().map(instances::requireOrCreateInitial).toList());
        // A map already selected for old work cannot retarget within the same attempt.
        refs.forEach(instances::requireActive);
        return refs;
    }

    private static boolean initial(List<DocumentInstanceRef> refs) {
        return refs.stream().allMatch(ref -> ref.equals(LogicalDocumentInstances.initialReference(ref.documentId())));
    }

    @Override public V get(K key) {
        var refs = references(key);
        return initial(refs) ? initial.get(key) : replacements.get(new Selected<>(key, refs));
    }
    @Override public boolean contains(K key) { return get(key) != null; }

    /** Explicit current-key inventory; ordinary root processing uses exact points. */
    @Override public List<Map.Entry<K, V>> entries() {
        var all = new TreeSet<K>(order);
        initial.entries().forEach(row -> all.add(row.getKey())); inventory.entries().forEach(row -> all.add(row.getKey()));
        var result = new ArrayList<Map.Entry<K, V>>();
        for (K key : all) {
            if (documents(key).stream().map(instances::select)
                    .anyMatch(binding -> binding.instance().isEmpty() && binding.generation() != 0)) continue;
            V value = get(key); if (value != null) result.add(Map.entry(key, value));
        }
        return List.copyOf(result);
    }
    @Override public Map.Entry<K, V> first(K lower, boolean exclusive, K upper) {
        return entries().stream().filter(row -> (lower == null || order.compare(row.getKey(), lower) > (exclusive ? 0 : -1))
                && (upper == null || order.compare(row.getKey(), upper) < 0)).findFirst().orElse(null);
    }

    private void select(K key, V value) {
        var refs = references(key);
        if (initial(refs)) {
            (value == null ? initial.remove(key) : initial.put(key, value)).select();
        } else {
            var selectedKey = new Selected<>(key, refs);
            (value == null ? replacements.remove(selectedKey) : replacements.put(selectedKey, value)).select();
            if (value != null) inventory.put(key, true).select();
        }
    }

    private OrderedRecordKey<Selected<K>> selectedKeys(int maximumBytes) {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/instance-progress-key/1/" + keys.identity(); }
            public byte[] encode(Selected<K> selected) {
                return SessionStorageWire.encode(maximumBytes, writer -> {
                    writer.text(identity()); writer.bytes(keys.encode(selected.key())); writer.integer(selected.instances().size());
                    for (var ref : selected.instances()) { writer.text(ref.documentId().value()); writer.text(ref.instanceId()); }
                });
            }
            public Selected<K> decode(byte[] encoded) {
                return SessionStorageWire.decode(encoded, maximumBytes, reader -> {
                    require(identity().equals(reader.text(reader.remaining())), "Unknown instance progress key format");
                    K key = keys.decode(reader.bytes(reader.remaining()));
                    var refs = new ArrayList<DocumentInstanceRef>();
                    for (int n = reader.count(Integer.MAX_VALUE, 8); n > 0; n--)
                        refs.add(new DocumentInstanceRef(DocumentId.of(reader.text(reader.remaining())), reader.text(reader.remaining())));
                    require(documents(key).equals(refs.stream().map(DocumentInstanceRef::documentId).toList()),
                            "Instance progress key has another observer set");
                    return new Selected<>(key, List.copyOf(refs));
                });
            }
        };
    }
}
