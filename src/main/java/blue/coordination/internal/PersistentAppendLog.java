package blue.coordination.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Immutable append-only chunk chain with constant-time root publication. */
final class PersistentAppendLog<T> {
    private static final PersistentAppendLog<?> EMPTY =
            new PersistentAppendLog<>(null, List.of(), 0);

    private final PersistentAppendLog<T> previous;
    private final List<T> chunk;
    private final int size;

    private PersistentAppendLog(
            PersistentAppendLog<T> previous,
            List<T> chunk,
            int size) {
        this.previous = previous;
        this.chunk = List.copyOf(Objects.requireNonNull(chunk, "chunk"));
        this.size = size;
    }

    @SuppressWarnings("unchecked")
    static <T> PersistentAppendLog<T> empty() {
        return (PersistentAppendLog<T>) EMPTY;
    }

    static <T> PersistentAppendLog<T> of(Collection<T> values) {
        return PersistentAppendLog.<T>empty().appendAll(values);
    }

    PersistentAppendLog<T> appendAll(Collection<T> values) {
        ArrayList<T> appended = new ArrayList<>();
        for (T value : Objects.requireNonNull(values, "values")) {
            appended.add(Objects.requireNonNull(value, "value"));
        }
        if (appended.isEmpty()) {
            return this;
        }
        return new PersistentAppendLog<>(
                this,
                appended,
                Math.addExact(size, appended.size()));
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    List<T> values() {
        if (isEmpty()) {
            return List.of();
        }
        Deque<List<T>> chunks = new ArrayDeque<>();
        PersistentAppendLog<T> cursor = this;
        while (cursor != null && !cursor.chunk.isEmpty()) {
            chunks.addFirst(cursor.chunk);
            cursor = cursor.previous;
        }
        ArrayList<T> result = new ArrayList<>(size);
        chunks.forEach(result::addAll);
        return List.copyOf(result);
    }

    boolean extendsLog(PersistentAppendLog<T> prefix) {
        PersistentAppendLog<T> selected = Objects.requireNonNull(
                prefix, "prefix");
        PersistentAppendLog<T> cursor = this;
        while (cursor != null && cursor.size >= selected.size) {
            if (cursor == selected) {
                return true;
            }
            cursor = cursor.previous;
        }
        return false;
    }
}
