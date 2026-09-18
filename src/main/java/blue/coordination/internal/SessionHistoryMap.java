package blue.coordination.internal;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Structurally shared insertion-ordered indexes; iteration is explicitly exhaustive. */
final class SessionHistoryMap<K, V> extends AbstractMap<K, V> {
    private PersistentOrderedMap<K, V> values;
    private PersistentOrderedMap<Long, K> order;
    private SessionHistoryMap(PersistentOrderedMap<K, V> values, PersistentOrderedMap<Long, K> order) {
        this.values = Objects.requireNonNull(values); this.order = Objects.requireNonNull(order);
        if (values.size() != order.size()) throw new IllegalArgumentException("Session map root counts differ");
    }
    static <K, V> SessionHistoryMap<K, V> empty(Comparator<? super K> comparator) {
        return fromRoots(PersistentOrderedMap.empty(comparator), PersistentOrderedMap.empty(Long::compare));
    }
    static <K, V> SessionHistoryMap<K, V> fromRoots(PersistentOrderedMap<K, V> values, PersistentOrderedMap<Long, K> order) {
        return new SessionHistoryMap<>(values, order);
    }
    PersistentOrderedMap<K, V> valuesRoot() { return values; }
    PersistentOrderedMap<Long, K> orderRoot() { return order; }
    SessionHistoryMap<K, V> copy() { return fromRoots(values, order); }
    @Override public int size() { return values.size(); }
    @Override public V get(Object key) {
        if (key == null) return null;
        @SuppressWarnings("unchecked") K selected = (K) Objects.requireNonNull(key);
        return values.get(selected);
    }
    @Override public boolean containsKey(Object key) { return get(key) != null; }
    @Override public V put(K key, V value) {
        Objects.requireNonNull(key); Objects.requireNonNull(value);
        V prior = values.get(key);
        var updatedValues = values.put(key, value).map();
        var updatedOrder = order;
        if (prior == null) updatedOrder = order.put((long) size(), key).map();
        values = updatedValues; order = updatedOrder;
        return prior;
    }
    @Override public V putIfAbsent(K key, V value) {
        V prior = get(key);
        if (prior == null) put(key, value);
        return prior;
    }
    @Override public Set<Map.Entry<K, V>> entrySet() {
        var capturedValues = values; var capturedOrder = order;
        return new AbstractSet<>() {
            @Override public int size() { return capturedOrder.size(); }
            @Override public Iterator<Map.Entry<K, V>> iterator() {
                var positions = capturedOrder.range(null, null);
                return new Iterator<>() {
                    private long expected;
                    @Override public boolean hasNext() { return positions.hasNext(); }
                    @Override public Map.Entry<K, V> next() {
                        var position = positions.next();
                        if (position.getKey() != expected++) throw new IllegalStateException("Noncontiguous session-map insertion order");
                        K key = position.getValue();
                        return Map.entry(key, Objects.requireNonNull(capturedValues.get(key), "Missing selected session-map value"));
                    }
                };
            }
        };
    }
}
