package blue.coordination.internal;

import java.util.*;

/** Mutable point membership view; iteration deliberately observes complete membership. */
final class LogicalRecordSet<K> extends AbstractSet<K> {
    private final Map<K, Boolean> rows;
    LogicalRecordSet(Map<K, Boolean> rows) { this.rows = Objects.requireNonNull(rows); }
    @Override public boolean contains(Object key) { return Boolean.TRUE.equals(rows.get(key)); }
    @Override public boolean add(K key) { return rows.putIfAbsent(Objects.requireNonNull(key), true) == null; }
    @Override public boolean remove(Object key) { return rows.remove(key) != null; }
    @Override public int size() { return rows.size(); }
    @Override public Iterator<K> iterator() { return rows.keySet().iterator(); }
    @Override public void clear() { rows.clear(); }
}
