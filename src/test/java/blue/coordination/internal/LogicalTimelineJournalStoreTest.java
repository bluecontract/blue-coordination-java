package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalTimelineJournalStoreTest {
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final RootedEngineStorage.Limits LIMITS = new RootedEngineStorage.Limits(
            65536, 8192, 65536, 8192, 32, 4 * 1024 * 1024, 256, 32 * 1024 * 1024,
            128, 1024, 256, 32 * 1024 * 1024, 65536, 65536, 32);
    private static final Timeline A = new Timeline("a", "alice");
    private static final Timeline B = new Timeline("b", "bob");
    private static final Operation OP = Operation.withoutRequest("touch", "owner");

    @Test void acceptedRowsIndexesRollbackAndAvailabilitySurviveColdPublicationWithoutReplay() {
        // given
        var records = new LogicalRecordMapTest.Store(); var attempt = records.attempt();
        var logical = new LogicalPointStorage(attempt); TimelineEntry first; TimelineEntry second;
        try (var owner = new Owner(new LogicalTimelineJournalStore(logical, LIMITS))) {
            first = owner.journal.append(A, OP, 20); second = owner.journal.append(B, OP, 10);
            var mark = owner.journal.mark(); owner.journal.append(A, OP, 30); owner.journal.rollbackTo(mark);
            owner.journal.makeHistoricalUnavailable("paused");
            // when
            logical.stage(); assertTrue(records.publish(attempt.prepare("accepted", List.of(), EVIDENCE)));
        }
        try (var coldAttempt = records.attempt(); var cold = new Owner(new LogicalTimelineJournalStore(new LogicalPointStorage(coldAttempt), LIMITS))) {
            // then
            assertEquals(List.of(first.blueId(), second.blueId()), cold.journal.entries().stream().map(TimelineEntry::blueId).toList());
            assertEquals(List.of(first.blueId()), cold.journal.entries("a").stream().map(TimelineEntry::blueId).toList());
            assertEquals(second.blueId(), cold.journal.nextExternal(null, null).orElseThrow().blueId());
            assertEquals(first.sourceOrderKey(), cold.journal.latestExternalOrder());
            assertEquals(4, cold.journal.revision());
            assertEquals(first.blueId(), cold.journal.appendExact(A, first.exactEvent()).blueId());
            assertEquals(new HistoricalStep.Unavailable("paused"), cold.journal.nextHistoricalStep(null,
                    first.sourceOrderKey(), null, ignored -> true, 0, 0, () -> "surface"));
        }
    }

    @Test void discardedAcceptancePublishesNoJournalAndCompetingAppendConditionsConflict() {
        // given
        var records = new LogicalRecordMapTest.Store(); var discarded = records.attempt();
        try (var owner = new Owner(new LogicalTimelineJournalStore(new LogicalPointStorage(discarded), LIMITS))) {
            owner.journal.append(A, OP, 10); discarded.close();
        }
        var a = records.attempt(); var b = records.attempt();
        var ca = new LogicalPointStorage(a); var cb = new LogicalPointStorage(b);
        // when
        try (var left = new Owner(new LogicalTimelineJournalStore(ca, LIMITS));
             var right = new Owner(new LogicalTimelineJournalStore(cb, LIMITS))) {
            assertEquals(0, left.journal.size()); assertEquals(0, right.journal.size());
            left.journal.append(A, OP, 20); right.journal.append(B, OP, 30);
            ca.stage(); cb.stage();
        }
        var pa = a.prepare("a", List.of(), EVIDENCE); var pb = b.prepare("b", List.of(), EVIDENCE);
        // then
        assertTrue(records.publish(pa)); assertFalse(records.publish(pb));
        try (var attempt = records.attempt(); var cold = new Owner(new LogicalTimelineJournalStore(new LogicalPointStorage(attempt), LIMITS))) {
            assertEquals(1, cold.journal.size()); assertEquals("a", cold.journal.entries().get(0).timeline().timelineId());
        }
    }

    @Test void openingStoreIsLazyAndLiveViewsPreventMutationAndRetireTheAttemptOnMisuse() {
        // given
        var records = new LogicalRecordMapTest.Store(); var attempt = records.attempt();
        var logical = new LogicalPointStorage(attempt); var store = new LogicalTimelineJournalStore(logical, LIMITS);
        var view = store.openRead();
        // when
        assertThrows(RuntimeException.class, () -> store.apply(TimelineJournalStore.State.empty(),
                new TimelineJournalStore.SetAvailability(TimelineJournalStore.State.empty())));
        // then
        assertThrows(RuntimeException.class, view::state); view.close(); view.close();
        assertThrows(RuntimeException.class, () -> attempt.prepare("invalid", List.of(), EVIDENCE));
    }

    private static final class Owner implements AutoCloseable {
        private final EngineMetrics metrics = new EngineMetrics();
        private final WholeObjectStore objects = new WholeObjectStore(metrics);
        private final BlueRuntime runtime = BlueRuntime.create(objects);
        private final TimelineJournal journal;
        Owner(TimelineJournalStore store) { journal = new DefaultTimelineJournal(
                new WholeRequestEntryFactory(runtime, objects, metrics), metrics, store); }
        public void close() { runtime.close(); }
    }
}
