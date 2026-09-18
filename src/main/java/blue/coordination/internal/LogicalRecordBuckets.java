package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;

/** Flattened secondary indexes: bucket updates never write a shared bucket descriptor. */
final class LogicalRecordBuckets<O, K, V> implements LogicalRecordMap.Source<O, PersistentOrderedMap<K, V>> {
    private final Comparator<? super O> outerOrder;
    private final Comparator<? super K> innerOrder;
    private final LogicalRecordContext context;
    private final Family family;
    private final Bytes scope;
    private final OrderedRecordKey<O> outerKeys;
    private final OrderedRecordKey<K> innerKeys;
    private final PersistentMapCodec<V> values;
    private final PersistentMapStorage.Limits limits;

    LogicalRecordBuckets(Comparator<? super O> outerOrder, Comparator<? super K> innerOrder,
            LogicalRecordContext context, Family family, Bytes scope, OrderedRecordKey<O> outerKeys,
            OrderedRecordKey<K> innerKeys, PersistentMapCodec<V> values, PersistentMapStorage.Limits limits) {
        this.outerOrder = outerOrder; this.innerOrder = innerOrder; this.context = context;
        this.family = family; this.scope = scope; this.outerKeys = outerKeys;
        this.innerKeys = innerKeys; this.values = values; this.limits = limits;
    }

    PersistentOrderedMap<O, PersistentOrderedMap<K, V>> open() {
        return PersistentOrderedMap.logical(outerOrder, LogicalRecordMap.virtual(outerOrder, context, this,
                this::select, bucket -> !bucket.isEmpty()));
    }

    /** Empty buckets are valid virtual views; get itself makes no membership assertion. */
    @Override public PersistentOrderedMap<K, V> get(O owner) {
        context.checkOpen(); byte[] prefix = prefix(owner);
        OrderedRecordKey<K> prefixed = new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/bucket/1/" + innerKeys.identity(); }
            public byte[] encode(K key) {
                byte[] inner = innerKeys.encode(key), combined = Arrays.copyOf(prefix, prefix.length + inner.length);
                System.arraycopy(inner, 0, combined, prefix.length, inner.length); return combined;
            }
            public K decode(byte[] bytes) {
                if (bytes.length < prefix.length || !Arrays.equals(prefix, Arrays.copyOf(bytes, prefix.length)))
                    throw new IllegalArgumentException("Secondary record is outside its bucket");
                return innerKeys.decode(Arrays.copyOfRange(bytes, prefix.length, bytes.length));
            }
        };
        return PersistentOrderedMap.logical(innerOrder, LogicalRecordMap.open(innerOrder, context, family, scope,
                prefixed, values, limits.keyBytes(), limits.valueBytes(), new Bytes(prefix), end(prefix)));
    }

    @Override public boolean contains(O owner) { return !get(owner).isEmpty(); }

    @Override public Map.Entry<O, PersistentOrderedMap<K, V>> first(O lower, boolean exclusive, O upper) {
        Bytes from = lower == null ? null : exclusive ? end(prefix(lower)) : new Bytes(prefix(lower));
        Bytes to = upper == null ? null : new Bytes(prefix(upper));
        if (from != null && to != null && from.compareTo(to) >= 0) return null;
        return context.first(new Range(family, scope, from, to)).map(row -> {
            O owner = owner(row.key().key()); return Map.entry(owner, get(owner));
        }).orElse(null);
    }

    @Override public List<Map.Entry<O, PersistentOrderedMap<K, V>>> entries() {
        var owners = new TreeSet<O>(outerOrder);
        for (var row : context.query(new Range(family, scope, null, null))) owners.add(owner(row.key().key()));
        return owners.stream().map(key -> Map.entry(key, get(key))).toList();
    }

    private void select(O owner, PersistentOrderedMap<K, V> selected) {
        if (selected == null) { get(owner).emptyCopy().selectLogicalRecords(); return; }
        if (selected.isLogical()) {
            // Prove the selected view is this exact owner's map, including an empty tentative view.
            if (!selected.logicalBindingIs(context, family, scope, new Bytes(prefix(owner))))
                throw new IllegalArgumentException("Secondary bucket belongs to another logical owner");
            selected.selectLogicalRecords(); return;
        }
        // Explicit resident import/replacement is allowed; ordinary cold updates retain their logical view.
        var replacement = get(owner).emptyCopy();
        for (var row : selected.entries()) replacement = replacement.put(row.getKey(), row.getValue()).map();
        replacement.selectLogicalRecords();
    }

    private byte[] prefix(O owner) {
        byte[] bytes = outerKeys.encode(Objects.requireNonNull(owner));
        if (outerOrder.compare(owner, outerKeys.decode(bytes.clone())) != 0)
            throw new IllegalArgumentException("Invalid secondary owner encoding");
        byte[] prefix = OrderedRecordKey.tuple(bytes);
        if (prefix.length > limits.keyBytes()) throw new IllegalArgumentException("Secondary owner exceeds key bound");
        return prefix;
    }

    private O owner(Bytes key) {
        byte[] bytes = key.copy(); int boundary = -1;
        for (int i = 0; i + 1 < bytes.length; i++) if (bytes[i] == 0) {
            if (bytes[++i] == 0) { boundary = i + 1; break; }
            if (Byte.toUnsignedInt(bytes[i]) != 255) throw new IllegalArgumentException("Invalid secondary owner escape");
        }
        if (boundary < 0) throw new IllegalArgumentException("Unterminated secondary owner");
        byte[] encoded = Arrays.copyOf(bytes, boundary);
        O owner = outerKeys.decode(OrderedRecordKey.split(encoded, 1)[0]);
        if (!Arrays.equals(prefix(owner), encoded)) throw new IllegalArgumentException("Noncanonical secondary owner");
        return owner;
    }

    private static Bytes end(byte[] prefix) {
        // Tuple prefixes always end with a zero terminator; its successor bounds exactly that owner.
        byte[] end = prefix.clone(); end[end.length - 1] = 1; return new Bytes(end);
    }
}
