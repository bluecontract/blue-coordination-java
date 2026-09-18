package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import java.util.function.Function;

/** Owner-ordered evidence rows; independent owners never share an append counter. */
final class LogicalAppendLog<T> {
    private record Position(String owner, long sequence) { }
    private final LogicalRecordContext context;
    private final PersistentOrderedMap<Position, T> rows;
    private final PersistentOrderedMap<String, Long> next;
    private final Function<T, String> owner;

    LogicalAppendLog(LogicalRecordContext context, CoordinationImmutableObjectStore objects,
            PersistentMapStorage.Limits limits, Family family, PersistentMapCodec<T> values, Function<T, String> owner) {
        if (family != Family.RUNTIME_OUTBOX && family != Family.CHECKPOINT)
            throw new IllegalArgumentException("Not a runtime evidence log family");
        this.context = context; this.owner = owner;
        var codecs = new StoreIndexCodecs(objects, limits);
        var position = new OrderedRecordKey<Position>() {
            public String identity() { return "blue-coordination/ordered-key/owner-log-position/1"; }
            public byte[] encode(Position position) {
                if (position.sequence() < 0) throw new IllegalArgumentException("Negative log position");
                return OrderedRecordKey.tuple(OrderedRecordKey.text().encode(position.owner()), OrderedRecordKey.signedLong().encode(position.sequence()));
            }
            public Position decode(byte[] bytes) {
                var parts = OrderedRecordKey.split(bytes, 2);
                var result = new Position(OrderedRecordKey.text().decode(parts[0]), OrderedRecordKey.signedLong().decode(parts[1]));
                if (result.sequence() < 0) throw new IllegalArgumentException("Negative log position"); return result;
            }
        };
        var counts = codecs.codec("owner-log/next", (SessionStorageWire.Writer w, Long value) -> w.longValue(value), r -> {
            long value = r.longValue(); SessionStorageWire.require(value >= 0, "Negative log counter"); return value;
        });
        rows = PersistentOrderedMap.logical(Comparator.comparing(Position::owner, EmbeddingBinding.TEXT_ORDER)
                .thenComparingLong(Position::sequence), context, family, scope("items"), position, values, limits.keyBytes(), limits.valueBytes());
        next = codecs.binding("owner-log/next", EmbeddingBinding.TEXT_ORDER, codecs.text, counts)
                .openLogical(context, family, scope("next"), OrderedRecordKey.text());
    }
    private static Bytes scope(String part) { return new Bytes(OrderedRecordKey.text().encode("runtime/1/log/" + part)); }
    void checkOpen() { context.checkOpen(); }
    boolean isEmpty() { return rows.isEmpty(); }
    int size() { return rows.size(); }
    List<T> values() {
        return context.protect(() -> {
            var result = new ArrayList<T>(); var observedCounts = new TreeMap<String, Long>(EmbeddingBinding.TEXT_ORDER);
            String previous = null; long sequence = 0;
            for (var row : rows.entries()) {
                var key = row.getKey();
                if (!key.owner().equals(previous)) {
                    if (previous != null) observedCounts.put(previous, sequence);
                    previous = key.owner(); sequence = 0;
                }
                if (key.sequence() != sequence++ || !key.owner().equals(owner.apply(row.getValue())))
                    throw new IllegalStateException("Evidence log owner or position differs");
                result.add(row.getValue());
            }
            if (previous != null) observedCounts.put(previous, sequence);
            var retainedCounts = new TreeMap<String, Long>(EmbeddingBinding.TEXT_ORDER);
            for (var row : next.entries()) retainedCounts.put(row.getKey(), row.getValue());
            if (!observedCounts.equals(retainedCounts)) throw new IllegalStateException("Evidence log owner/tail coverage differs");
            return List.copyOf(result);
        });
    }
    List<T> valuesWith(List<T> appended) {
        var grouped = new TreeMap<String, List<T>>(EmbeddingBinding.TEXT_ORDER);
        for (var value : values()) grouped.computeIfAbsent(owner.apply(value), ignored -> new ArrayList<>()).add(value);
        for (var value : appended) grouped.computeIfAbsent(owner.apply(value), ignored -> new ArrayList<>()).add(value);
        return grouped.values().stream().flatMap(List::stream).toList();
    }
    void select(List<T> appended) {
        context.protect(() -> {
            var selectedRows = rows; var selectedNext = next;
            for (var value : appended) {
                String selectedOwner = Objects.requireNonNull(owner.apply(value));
                Long position = selectedNext.get(selectedOwner); long sequence = position == null ? 0 : position;
                var key = new Position(selectedOwner, sequence);
                if (selectedRows.containsKey(key)) throw new IllegalStateException("Evidence append position is already retained");
                selectedRows = selectedRows.put(key, value).map(); selectedNext = selectedNext.put(selectedOwner, Math.addExact(sequence, 1)).map();
            }
            selectedRows.selectLogicalRecords(); selectedNext.selectLogicalRecords(); return true;
        });
    }
}
