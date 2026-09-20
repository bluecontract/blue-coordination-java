package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalAppendLogTest {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(65536, 4096, 32768, 2048, 8);
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});

    @Test void independentOwnersAppendInEitherOrderWithoutReadingTheHistoricalPrefix() {
        // given
        var records = new LogicalRecordMapTest.Store(); var packets = new ArrayList<Publication>();
        // when
        for (String owner : List.of("a", "b")) {
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt); var base = open(context);
                var next = base.appendAll(List.of(owner + "/first", owner + "/second"));
                assertTrue(next.extendsLog(base)); assertFalse(base.extendsLog(next));
                base.appendAll(List.of(owner + "/discarded"));
                next.selectLogicalRecords(); context.flush(); packets.add(attempt.prepare(owner, List.of(), EVIDENCE));
            }
        }
        // then
        assertTrue(packets.stream().allMatch(packet -> packet.queries().isEmpty()));
        for (boolean reverse : List.of(false, true)) {
            var target = new LogicalRecordMapTest.Store();
            assertTrue(target.publish(packets.get(reverse ? 1 : 0))); assertTrue(target.publish(packets.get(reverse ? 0 : 1)));
            try (var attempt = target.attempt()) {
                var context = new LogicalRecordContext(attempt); var cold = open(context);
                assertEquals(List.of("a/first", "a/second", "b/first", "b/second"), cold.values());
                assertEquals(4, cold.size()); assertFalse(cold.isEmpty());
                var updated = cold.appendAll(List.of("a/third"));
                assertEquals(List.of("a/first", "a/second", "b/first", "b/second"), cold.values());
                assertEquals(List.of("a/first", "a/second", "a/third", "b/first", "b/second"), updated.values());
                assertTrue(updated.extendsLog(cold)); updated.selectLogicalRecords(); context.flush();
                assertTrue(target.publish(attempt.prepare("third", List.of(), EVIDENCE)));
            }
            try (var attempt = target.attempt()) {
                assertEquals(List.of("a/first", "a/second", "a/third", "b/first", "b/second"), open(new LogicalRecordContext(attempt)).values());
            }
        }
    }

    @Test void sameOwnerAppendConflictsAndCompleteMembershipObservationDetectsInsertion() {
        // given
        var records = new LogicalRecordMapTest.Store(); var packets = new ArrayList<Publication>();
        // when
        for (String value : List.of("a/first", "a/second")) {
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt); open(context).appendAll(List.of(value)).selectLogicalRecords();
                context.flush(); packets.add(attempt.prepare(value, List.of(), EVIDENCE));
            }
        }
        Publication empty;
        try (var attempt = records.attempt()) {
            assertTrue(open(new LogicalRecordContext(attempt)).values().isEmpty()); empty = attempt.prepare("empty", List.of(), EVIDENCE);
        }
        // then
        assertTrue(records.publish(packets.get(0))); assertFalse(records.publish(packets.get(1))); assertFalse(records.publish(empty));
    }

    @Test void closedAttemptRetiresEvenCachedTentativeLogOperations() {
        // given
        var attempt = new LogicalRecordMapTest.Store().attempt(); var base = open(new LogicalRecordContext(attempt));
        var tentative = base.appendAll(List.of("a/one"));
        // when
        attempt.close();
        // then
        assertThrows(IllegalStateException.class, () -> tentative.appendAll(List.of("a/two")));
        assertThrows(IllegalStateException.class, tentative::isEmpty);
        assertThrows(IllegalStateException.class, tentative::values);
        assertThrows(IllegalStateException.class, () -> tentative.extendsLog(base));
        assertThrows(IllegalStateException.class, tentative::selectLogicalRecords);
    }

    private static PersistentAppendLog<String> open(LogicalRecordContext context) {
        return PersistentAppendLog.logical(new LogicalAppendLog<>(context, new DocumentSessionStorageTest.Bytes(), LIMITS,
                Family.RUNTIME_OUTBOX, OrderedRecordKey.text(), value -> value.substring(0, value.indexOf('/'))));
    }
}
