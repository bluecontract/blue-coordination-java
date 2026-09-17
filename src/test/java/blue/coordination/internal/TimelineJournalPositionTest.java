package blue.coordination.internal;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineJournalStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TimelineJournalPositionTest {
    private static final Timeline A = new Timeline("a", "alice");
    private static final Timeline B = new Timeline("b", "bob");
    private static final Operation OP = Operation.withoutRequest("tick", "owner");

    @Test void positionMatchesTheOldScanWithOutOfOrderCrossTimelineImportsAndRollback() {
        try (var f = new Fixture()) {
            var empty = f.journal.position("a");
            assertTrue(empty.head().isEmpty());
            assertEquals(0, empty.maximumTimestampMicros());
            assertEquals(0, empty.journalRevision());
            var a = f.journal.append(A, OP, 900);
            var b = f.journal.append(B, OP, 100); // Last appended is not maximum timestamp.
            for (String id : List.of("a", "b", "not-yet-used")) {
                var position = f.journal.position(id);
                var old = f.journal.entries(id);
                assertEquals(old.isEmpty() ? null : old.get(old.size() - 1), position.head().orElse(null));
                assertEquals(f.journal.entries().stream().mapToLong(e -> e.timestampMicros()).max().orElse(0),
                        position.maximumTimestampMicros());
                assertEquals(2, position.journalRevision());
            }
            var frozen = f.journal.position("b");
            var mark = f.journal.mark();
            f.journal.append(A, OP, 1000);
            assertEquals(900, frozen.maximumTimestampMicros(), "Detached observation cannot drift");
            assertEquals(b, frozen.head().orElseThrow());
            f.journal.rollbackTo(mark);
            assertEquals(900, f.journal.position("b").maximumTimestampMicros());
            assertEquals(4, f.journal.position("a").journalRevision());
            f.journal.appendExact(A, a.exactEvent());
            assertEquals(4, f.journal.position("a").journalRevision(), "Duplicate is not another append");
        }
    }

    @Test void selectedReadsStayConstantAsTheUnrelatedJournalGrows() {
        try (var f = new Fixture()) {
            f.journal.append(A, OP, 1);
            int previous = 0;
            for (int size : List.of(10, 100, 1000, 10000)) {
                for (int n = previous + 1; n <= size; n++) f.journal.append(B, OP, n + 1L);
                previous = size;
                f.store.reset();
                long start = System.nanoTime();
                var position = f.journal.position("a");
                long indexedNanos = System.nanoTime() - start;
                int indexedReads = f.store.rowReads;
                assertEquals(size + 1L, position.maximumTimestampMicros());
                assertEquals(1, f.store.opens, "Both coordinates must come from the same pinned view");
                assertEquals(1, f.store.closes);
                assertTrue(indexedReads <= 16, "No history-length multiplier: " + indexedReads);
                f.store.reset();
                start = System.nanoTime();
                f.journal.entries("a");
                long maximum = f.journal.entries().stream().mapToLong(e -> e.timestampMicros()).max().orElse(0);
                long scannedNanos = System.nanoTime() - start;
                assertEquals(position.maximumTimestampMicros(), maximum);
                assertTrue(f.store.rowReads > size * 4);
                System.out.printf("JOURNAL_POSITION rows=%d indexedReads=%d scannedReads=%d indexedNanos=%d scannedNanos=%d%n",
                        size + 1, indexedReads, f.store.rowReads, indexedNanos, scannedNanos);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EngineMetrics metrics = new EngineMetrics();
        final WholeObjectStore objects = new WholeObjectStore(metrics);
        final BlueRuntime runtime = BlueRuntime.create(objects);
        final CountingStore store = new CountingStore();
        final DefaultTimelineJournal journal = new DefaultTimelineJournal(
                new WholeRequestEntryFactory(runtime, objects, metrics, ignored -> "MyOS/Principal Actor", true), metrics, store);
        public void close() { runtime.close(); }
    }
    private static final class CountingStore implements TimelineJournalStore {
        final TimelineJournalStore delegate = new InMemoryTimelineJournalStore();
        int opens, closes, rowReads;
        void reset() { opens = closes = rowReads = 0; }
        public void apply(State expected, Mutation mutation) { delegate.apply(expected, mutation); }
        public ReadView openRead() {
            opens++;
            ReadView view = delegate.openRead();
            return (ReadView) Proxy.newProxyInstance(ReadView.class.getClassLoader(), new Class<?>[]{ReadView.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) closes++;
                        else if (!method.getName().equals("state") && method.getDeclaringClass() != Object.class) rowReads++;
                        try { return method.invoke(view, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
