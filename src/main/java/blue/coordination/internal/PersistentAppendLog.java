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
    private final LogicalAppendLog<T> logical;
    private final PersistentAppendLogStorage<T> storage;
    private final PersistentAppendLogStorage<T> readStorage;
    private final PersistentAppendLogStorage.Handle handle;

    private PersistentAppendLog(
            PersistentAppendLog<T> previous,
            List<T> chunk,
            int size) {
        this.previous = previous;
        this.logical = previous == null ? null : previous.logical;
        this.chunk = List.copyOf(Objects.requireNonNull(chunk, "chunk"));
        this.size = size;
        this.storage = null;
        this.readStorage = previous == null ? null : previous.readStorage;
        this.handle = null;
    }

    private PersistentAppendLog(PersistentAppendLogStorage<T> storage, PersistentAppendLogStorage.Handle handle) {
        this.logical = null;
        this.previous = null; this.chunk = List.of(); this.storage = Objects.requireNonNull(storage); this.handle = handle;
        this.readStorage = storage;
        this.size = handle == null ? 0 : handle.size();
    }

    private PersistentAppendLog(LogicalAppendLog<T> logical) {
        this.logical = Objects.requireNonNull(logical); previous = null; chunk = List.of(); size = 0;
        storage = null; readStorage = null; handle = null;
    }
    static <T> PersistentAppendLog<T> logical(LogicalAppendLog<T> logical) { return new PersistentAppendLog<>(logical); }
    void selectLogicalRecords() {
        if (logical == null) throw new IllegalStateException("Not a logical evidence log");
        logical.select(logicalAppended());
    }

    private List<T> logicalAppended() {
        var chunks = new ArrayDeque<List<T>>(); var cursor = this;
        while (cursor.previous != null) { chunks.addFirst(cursor.chunk); cursor = cursor.previous; }
        var appended = new ArrayList<T>(); chunks.forEach(appended::addAll); return List.copyOf(appended);
    }

    static <T> PersistentAppendLog<T> fromStorage(PersistentAppendLogStorage<T> storage, PersistentAppendLogStorage.Handle handle) {
        return new PersistentAppendLog<>(storage, handle);
    }

    /** Explicit resident-chain retention; stored roots require the same complete backing store. */
    PersistentAppendLog<T> storedCopy(PersistentAppendLogStorage<T> target) {
        if (logical != null) throw new IllegalStateException("Logical evidence cannot become a descriptor log");
        if (storage != null) return target.open(storedRootDescriptor());
        Deque<List<T>> chunks = new ArrayDeque<>();
        PersistentAppendLog<T> cursor = this;
        while (cursor != null && cursor.storage == null && !cursor.isEmpty()) {
            if (!cursor.chunk.isEmpty()) chunks.addFirst(cursor.chunk);
            cursor = cursor.previous;
        }
        PersistentAppendLog<T> result = target.open(cursor != null && cursor.storage != null ? cursor.storedRootDescriptor() : null);
        for (List<T> values : chunks) result = result.appendAll(values);
        return result;
    }

    /** Keeps subsequent exact rows in memory until explicit final staging; no stored prefix is opened. */
    PersistentAppendLog<T> workingCopy() {
        if (logical != null) logical.checkOpen();
        return storage == null ? this : new PersistentAppendLog<>(this, List.of(), size);
    }

    byte[] storedRootDescriptor() {
        if (storage == null) throw new IllegalStateException("Not a storage-backed append log");
        return storage.descriptor(handle);
    }

    private List<T> chunk() { return logical != null && previous == null ? logical.values() : storage == null ? chunk : storage.chunk(handle); }
    private PersistentAppendLog<T> previous() {
        return storage == null ? previous : isEmpty() ? null : fromStorage(storage, storage.previous(handle));
    }

    @SuppressWarnings("unchecked")
    static <T> PersistentAppendLog<T> empty() {
        return (PersistentAppendLog<T>) EMPTY;
    }

    static <T> PersistentAppendLog<T> of(Collection<T> values) {
        return PersistentAppendLog.<T>empty().appendAll(values);
    }

    PersistentAppendLog<T> appendAll(Collection<T> values) {
        if (logical != null) logical.checkOpen();
        ArrayList<T> appended = new ArrayList<>();
        for (T value : Objects.requireNonNull(values, "values")) {
            appended.add(Objects.requireNonNull(value, "value"));
        }
        if (appended.isEmpty()) {
            return this;
        }
        if (storage != null) return fromStorage(storage, storage.append(handle, appended));
        return new PersistentAppendLog<>(
                this,
                appended,
                Math.addExact(size, appended.size()));
    }

    int size() {
        return logical == null ? size : Math.addExact(size, logical.size());
    }

    boolean isEmpty() {
        if (logical != null) logical.checkOpen();
        return size == 0 && (logical == null || logical.isEmpty());
    }

    List<T> values() {
        if (logical != null) return logical.valuesWith(logicalAppended());
        return readStorage == null ? readValues() : readStorage.scoped(this::readValues);
    }

    private List<T> readValues() {
        if (isEmpty()) {
            return List.of();
        }
        Deque<List<T>> chunks = new ArrayDeque<>();
        PersistentAppendLog<T> cursor = this;
        while (cursor != null && !cursor.isEmpty()) {
            chunks.addFirst(cursor.chunk());
            cursor = cursor.previous();
        }
        ArrayList<T> result = new ArrayList<>(readStorage == null ? size : Math.min(size, 64));
        chunks.forEach(result::addAll);
        return List.copyOf(result);
    }

    boolean extendsLog(PersistentAppendLog<T> prefix) {
        if (logical != null) logical.checkOpen();
        if (Objects.requireNonNull(prefix).logical != null) prefix.logical.checkOpen();
        return readStorage == null ? readExtends(prefix) : readStorage.scoped(() -> readExtends(prefix));
    }

    private boolean readExtends(PersistentAppendLog<T> prefix) {
        PersistentAppendLog<T> selected = Objects.requireNonNull(
                prefix, "prefix");
        PersistentAppendLog<T> cursor = this;
        while (cursor != null && cursor.size >= selected.size) {
            if (cursor == selected || cursor.logical == null && selected.logical == null && cursor.isEmpty() && selected.isEmpty()
                    || cursor.storage != null && selected.storage != null && cursor.storage.sameBinding(selected.storage)
                        && Objects.equals(cursor.handle, selected.handle)) {
                return true;
            }
            cursor = cursor.previous();
        }
        return false;
    }
}
