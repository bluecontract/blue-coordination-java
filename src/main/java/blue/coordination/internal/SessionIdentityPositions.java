package blue.coordination.internal;

import java.util.ArrayList;
import java.util.List;

/** Derived live-object membership only; never serialized or accepted from a host. */
final class SessionIdentityPositions<T> {
    private PersistentOrderedMap<Integer, List<Entry<T>>> buckets = PersistentOrderedMap.empty(Integer::compare);
    SessionIdentityPositions<T> copy() {
        var copy = new SessionIdentityPositions<T>(); copy.buckets = buckets; return copy;
    }
    Long get(T value) {
        var rows = buckets.get(System.identityHashCode(value));
        if (rows != null) for (var row : rows) if (row.value == value) return row.position;
        return null;
    }
    void putIfAbsent(T value, long position) {
        if (get(value) != null) return;
        int key = System.identityHashCode(value);
        var prior = buckets.get(key);
        var rows = new ArrayList<Entry<T>>(prior == null ? List.of() : prior);
        rows.add(new Entry<>(value, position)); buckets = buckets.put(key, List.copyOf(rows)).map();
    }
    private record Entry<T>(T value, long position) { }
}
