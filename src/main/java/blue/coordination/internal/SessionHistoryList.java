package blue.coordination.internal;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** One mutable append cursor over an immutable, point-addressable history root. */
final class SessionHistoryList<E> extends AbstractList<E> {
    private PersistentOrderedMap<Long, E> root;

    private SessionHistoryList(PersistentOrderedMap<Long, E> root) {
        this.root = Objects.requireNonNull(root, "root");
    }
    static <E> SessionHistoryList<E> empty() { return fromRoot(PersistentOrderedMap.empty(Long::compare)); }
    static <E> SessionHistoryList<E> fromRoot(PersistentOrderedMap<Long, E> root) { return new SessionHistoryList<>(root); }
    static <E> SessionHistoryList<E> copyOf(List<E> source) {
        if (source instanceof SessionHistoryList<E> indexed) return indexed.copy();
        var result = SessionHistoryList.<E>empty(); result.addAll(source); return result;
    }
    PersistentOrderedMap<Long, E> root() { return root; }
    SessionHistoryList<E> copy() { return fromRoot(root); }
    @Override public int size() { return root.size(); }
    @Override public E get(int index) {
        Objects.checkIndex(index, size());
        return Objects.requireNonNull(root.get((long) index), "Missing selected session history position " + index);
    }
    @Override public boolean add(E value) {
        int next = size();
        Math.addExact(next, 1);
        if (root.containsKeyWithoutValue((long) next)) throw new IllegalStateException("History append would replace an existing position");
        root = root.put((long) next, Objects.requireNonNull(value, "value")).map();
        modCount++;
        return true;
    }
    @Override public Iterator<E> iterator() {
        int expectedSize = size();
        var rows = root.range(null, null);
        return new Iterator<>() {
            private long expected;
            @Override public boolean hasNext() {
                boolean present = rows.hasNext();
                if (!present && expected != expectedSize) throw new IllegalStateException("Incomplete session history enumeration");
                return present;
            }
            @Override public E next() {
                var row = rows.next();
                if (row.getKey() != expected++) throw new IllegalStateException("Noncontiguous session history enumeration");
                return row.getValue();
            }
        };
    }
    /** A detached range, never a live mutable-parent sublist. */
    @Override public List<E> subList(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, size());
        var rows = root.range((long) fromIndex, (long) toIndex);
        var selected = new ArrayList<E>();
        long expected = fromIndex;
        while (rows.hasNext()) {
            var row = rows.next();
            if (row.getKey() != expected++) throw new IllegalStateException("Noncontiguous selected history range");
            selected.add(row.getValue());
        }
        if (expected != toIndex) throw new IllegalStateException("Missing selected history range");
        return List.copyOf(selected);
    }
}
