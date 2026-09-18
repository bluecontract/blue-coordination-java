package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;

/** One runtime attempt; map branches collect writes only when explicitly selected. */
final class LogicalRecordContext {
    private final CoordinationRecordAttempt attempt;
    private final Map<Key, Mutation> selected = new TreeMap<>();
    private boolean flushed;

    LogicalRecordContext(CoordinationRecordAttempt attempt) { this.attempt = Objects.requireNonNull(attempt); }
    Value read(Key key) { open(); return attempt.read(key); }
    List<Row> query(Range range) { open(); return attempt.query(range); }
    Optional<Row> first(Range range) { open(); return attempt.first(range); }

    void select(Key key, Bytes content) {
        open();
        var mutation = new Mutation(key, content);
        var prior = selected.putIfAbsent(key, mutation);
        if (prior != null && !prior.equals(mutation))
            throw new IllegalStateException("Two final runtime maps disagree on one logical record");
    }

    /** All selected values/dependencies must be encoded before any write reaches the attempt. */
    void flush() {
        open();
        try {
            // Capture every original first. A failed read retires the whole selection.
            for (var mutation : selected.values()) attempt.read(mutation.key());
            flushed = true;
            for (var mutation : selected.values()) {
                if (mutation.content() == null) attempt.delete(mutation.key());
                else attempt.put(mutation.key(), mutation.content());
            }
        } catch (RuntimeException | Error failure) {
            try { attempt.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    <T> T protect(java.util.function.Supplier<T> operation) {
        open();
        try { return operation.get(); }
        catch (RuntimeException | Error failure) {
            try { attempt.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void open() {
        if (flushed) throw new IllegalStateException("Logical runtime selection is already flushed");
        attempt.address(); // Also enforce attempt lifetime and thread ownership on cached/overlay access.
    }
    void checkOpen() { open(); }
}
