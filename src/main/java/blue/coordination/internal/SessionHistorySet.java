package blue.coordination.internal;

import java.util.AbstractSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;

/** Insertion-ordered set with constant-time detached cursors over persistent roots. */
final class SessionHistorySet<K> extends AbstractSet<K> {
    private final SessionHistoryMap<K, Boolean> map;
    private SessionHistorySet(SessionHistoryMap<K, Boolean> map) { this.map = Objects.requireNonNull(map); }
    static <K> SessionHistorySet<K> empty(Comparator<? super K> comparator) {
        return fromMap(SessionHistoryMap.empty(comparator));
    }
    static <K> SessionHistorySet<K> fromMap(SessionHistoryMap<K, Boolean> map) { return new SessionHistorySet<>(map); }
    SessionHistoryMap<K, Boolean> map() { return map; }
    SessionHistorySet<K> copy() { return fromMap(map.copy()); }
    @Override public int size() { return map.size(); }
    @Override public boolean contains(Object key) { return Boolean.TRUE.equals(map.get(key)); }
    @Override public boolean add(K key) { return map.putIfAbsent(key, Boolean.TRUE) == null; }
    @Override public Iterator<K> iterator() { return map.keySet().iterator(); }
}
