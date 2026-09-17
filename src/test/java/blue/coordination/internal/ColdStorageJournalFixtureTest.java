package blue.coordination.internal;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.sdk.BlueCoordination;
import blue.language.processor.NoncommittingExecutionException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ColdStorageJournalFixtureTest {
    private static final Timeline A = new Timeline("a", "alice");
    private static final Operation OPERATION = Operation.withoutRequest("touch", "owner");

    @Test void actualEngineRowsColdOpenFromOwnedBytesWithoutBodyReadsOrReappend() {
        // given
        ColdStorageJournalFixture.Snapshot snapshot; TimelineEntry original;
        try (var blue = BlueCoordination.inMemory()) {
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var timeline = engine.registerTimeline(A.timelineId(), A.actorId());
            original = engine.appendAt(timeline, OPERATION, 10);
            snapshot = ColdStorageJournalFixture.retain(engine);
        }
        // when
        var store = ColdStorageJournalFixture.open(snapshot);
        // then
        assertEquals(0, store.bodyReads()); assertEquals(0, store.mutations());
        try (var view = store.openRead()) {
            assertEquals(new TimelineJournalStore.State(1, 1, 1, TimelineJournalStore.Availability.available()), view.state());
            assertEquals(0, store.bodyReads());
            var restored = view.byBlueId(original.blueId()).orElseThrow();
            assertNotSame(original, restored); assertArrayEquals(bytes(original), bytes(restored));
            assertEquals(1, store.bodyReads());
        }
        var copy = snapshot.bytes(); copy.values().forEach(row -> Arrays.fill(row, (byte) 0));
        try (var view = ColdStorageJournalFixture.open(snapshot).openRead()) {
            assertArrayEquals(bytes(original), bytes(view.atAppendPosition(0).orElseThrow()));
        }
    }

    @Test void actualAppendRollbackAvailabilityAndDuplicateSurviveAnotherColdCopy() {
        // given
        var store = ColdStorageJournalFixture.empty(); TimelineEntry first;
        try (var context = new Context(store)) {
            first = context.journal.append(A, OPERATION, 10); var mark = context.journal.mark();
            context.journal.append(A, OPERATION, 20); context.journal.rollbackTo(mark);
            // when
            context.journal.makeHistoricalUnavailable("retained maintenance");
            // then
            assertEquals(3, context.journal.revision());
        }
        var copied = ColdStorageJournalFixture.open(ColdStorageJournalFixture.retain(store));
        try (var view = copied.openRead()) {
            assertEquals(3, view.state().revision()); assertEquals(1, view.state().globalSequence());
            assertEquals("retained maintenance", view.state().availability().diagnostic());
        }
        try (var context = new Context(copied)) {
            context.journal.makeHistoricalAvailable();
            assertArrayEquals(bytes(first), bytes(context.journal.appendExact(A, first.exactEvent())));
            assertEquals(3, context.journal.revision(), "duplicate does not publish an append");
            var second = context.journal.append(A, OPERATION, 30);
            assertEquals(2, second.globalSequence()); assertEquals(2, second.timelineSequence());
            assertEquals(4, context.journal.revision());
        }
        assertEquals(2, copied.mutations(), "availability plus one real append; no duplicate mutation");
        try (var view = ColdStorageJournalFixture.open(copied.snapshot()).openRead()) {
            assertEquals(2, view.state().entryCount()); assertEquals(4, view.state().revision());
            assertEquals(30, view.timelineHead("a").orElseThrow().timestampMicros());
        }
    }

    @Test void physicalMissingCorruptionAndStateConflictsFailWithoutPartialMutation() {
        // given
        var store = ColdStorageJournalFixture.empty(); TimelineEntry row;
        try (var context = new Context(store)) { row = context.journal.append(A, OPERATION, 10); }
        for (boolean missing : List.of(true, false)) {
            var bytes = new LinkedHashMap<>(store.snapshot().bytes());
            var key = bytes.keySet().stream().filter(name -> name.startsWith("body/")).findFirst().orElseThrow();
            if (missing) bytes.remove(key); else bytes.get(key)[0] ^= 1;
            // when
            var cold = ColdStorageJournalFixture.open(new ColdStorageJournalFixture.Snapshot(bytes));
            // then
            assertEquals(0, cold.bodyReads());
            try (var view = cold.openRead()) {
                assertThrows(NoncommittingExecutionException.class, () -> view.byBlueId(row.blueId()));
            }
        }
        var original = store.snapshot();
        assertThrows(NoncommittingExecutionException.class, () -> store.apply(TimelineJournalStore.State.empty(),
                new TimelineJournalStore.SetAvailability(TimelineJournalStore.State.empty())));
        try (var view = store.openRead()) {
            assertThrows(NoncommittingExecutionException.class, () -> store.apply(view.state(), new TimelineJournalStore.SetAvailability(view.state())));
        }
        assertEquals(original.bytes().keySet(), store.snapshot().bytes().keySet());
        original.bytes().forEach((key, bytes) -> assertArrayEquals(bytes, store.snapshot().bytes().get(key)));
        assertEquals(1, store.mutations());
    }

    private static byte[] bytes(TimelineEntry entry) {
        return SessionStorageWire.encode(16 * 1024 * 1024, out -> new SessionRecordCodec(16 * 1024 * 1024, 256).entry(out, entry));
    }
    private static final class Context implements AutoCloseable {
        private final EngineMetrics metrics = new EngineMetrics();
        private final WholeObjectStore objects = new WholeObjectStore(metrics);
        private final BlueRuntime runtime = BlueRuntime.create(objects);
        private final TimelineJournal journal;
        private Context(TimelineJournalStore store) {
            var factory = new WholeRequestEntryFactory(runtime, objects, metrics, ignored -> "MyOS/Principal Actor", true);
            journal = new DefaultTimelineJournal(factory, metrics, store);
        }
        @Override public void close() { runtime.close(); }
    }
}
