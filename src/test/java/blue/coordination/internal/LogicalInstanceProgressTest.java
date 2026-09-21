package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native progress-key controls; ledger transitions here do not substitute for runtime retirement eligibility. */
final class LogicalInstanceProgressTest {
    private static final DocumentId A = DocumentId.of("a"), B = DocumentId.of("b");
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final StoredInsertionOrderedMap.Limits LIMITS = new StoredInsertionOrderedMap.Limits(
            new PersistentMapStorage.Limits(8192, 4096, 4096, 1024, 32), 4096, 65536, 100);

    @Test void replacementDoesNotReuseOldTerminalFrontierOrSuspensionAndBRemainsIntact() {
        // given
        var store = seeded();
        var oldRows = new TreeMap<>(store.data);
        // when
        commit(store, "replace", tx -> {
            tx.instances.retire(tx.instances.requireActive(LogicalDocumentInstances.initialReference(A)));
            tx.instances.start(new DocumentInstanceRef(A, "a-2"));
        });
        // then
        commit(store, "new-observer", tx -> {
            for (var progress : List.of(tx.terminal, tx.frontier, tx.pending)) {
                assertNull(progress.get(A), "Same semantic lane cannot inherit the retired observer's progress");
                assertEquals("b-original", progress.get(B));
                assertEquals(Set.of(B), progress.keySet());
                progress.put(A, "a-replayed-same-entry");
            }
        });
        commit(store, "cold", tx -> {
            for (var progress : List.of(tx.terminal, tx.frontier, tx.pending)) {
                assertEquals("a-replayed-same-entry", progress.get(A));
                assertEquals("b-original", progress.get(B));
                assertEquals(Set.of(A, B), progress.keySet());
            }
        });
        oldRows.forEach((key, value) -> {
            if (Set.of(Family.FEEDER_TERMINAL, Family.FEEDER_FRONTIER, Family.FEEDER_PENDING).contains(key.family()))
                assertEquals(value, store.data.get(key), "Original progress bytes/revisions remain retained");
        });
    }

    @Test void independentBProgressPublishesAcrossAReplacementInBothOrders() {
        // given
        for (boolean reverse : List.of(false, true)) {
            var store = seeded();
            var b = prepare(store, "b", tx -> {
                assertEquals("b-original", tx.frontier.get(B)); tx.frontier.put(B, "b-next");
            });
            var oldA = prepare(store, "old-a", tx -> tx.frontier.put(A, "old-a-next"));
            // when
            var replace = prepare(store, "replace", tx -> {
                tx.instances.retire(tx.instances.requireActive(LogicalDocumentInstances.initialReference(A)));
                tx.instances.start(new DocumentInstanceRef(A, "a-2"));
            });
            // then
            assertTrue(b.queries().isEmpty(), "B's point progress must not acquire a catalog predicate");
            assertTrue(store.publish(reverse ? b : replace));
            assertTrue(store.publish(reverse ? replace : b));
            assertFalse(store.publish(oldA));
            commit(store, "cold", tx -> {
                assertEquals("b-next", tx.frontier.get(B)); assertNull(tx.frontier.get(A));
            });
        }
    }

    @Test void anAlreadySelectedProgressMapCannotRetargetInsideOneAttempt() {
        // given
        var store = seeded();
        try (var tx = new Transaction(store.attempt())) {
            assertEquals("a-original", tx.terminal.get(A));
            // when
            tx.instances.retire(tx.instances.requireActive(LogicalDocumentInstances.initialReference(A)));
            tx.instances.start(new DocumentInstanceRef(A, "a-2"));
            tx.terminal.put(A, "old-work");
            // then
            assertThrows(IllegalStateException.class, tx.logical::stage);
            assertThrows(IllegalStateException.class, () -> tx.attempt.prepare("retargeted", List.of(), EVIDENCE));
        }
    }

    @Test void readOnlySelectedProgressCannotSurviveAnInAttemptInstanceChange() {
        // given
        var store = seeded();
        try (var tx = new Transaction(store.attempt())) {
            assertEquals("a-original", tx.frontier.get(A));
            // when
            tx.instances.retire(tx.instances.requireActive(LogicalDocumentInstances.initialReference(A)));
            tx.instances.start(new DocumentInstanceRef(A, "a-2"));
            // then
            assertThrows(IllegalStateException.class, tx.logical::stage);
            assertThrows(IllegalStateException.class, () -> tx.attempt.prepare("read-retargeted", List.of(), EVIDENCE));
        }
    }

    @Test void currentProgressInventoryProtectsAnEmptyReplacementPointAgainstInsertion() {
        // given
        var store = seeded();
        commit(store, "replace", tx -> {
            tx.instances.retire(tx.instances.requireActive(LogicalDocumentInstances.initialReference(A)));
            tx.instances.start(new DocumentInstanceRef(A, "a-2"));
        });
        var reader = prepare(store, "inventory", tx -> assertEquals(Set.of(B), tx.pending.keySet()));
        // when
        commit(store, "new-pending", tx -> tx.pending.put(A, "new-a-wait"));
        // then
        assertFalse(store.publish(reader));
        commit(store, "retire", tx -> tx.instances.retire(tx.instances.requireActive(new DocumentInstanceRef(A, "a-2"))));
        commit(store, "retired-inventory", tx -> assertEquals(Set.of(B), tx.pending.keySet()));
    }

    private static LogicalRecordMapTest.Store seeded() {
        var store = new LogicalRecordMapTest.Store();
        commit(store, "initial", tx -> {
            for (var progress : List.of(tx.terminal, tx.frontier, tx.pending)) {
                progress.put(A, "a-original"); progress.put(B, "b-original");
            }
        });
        return store;
    }
    private static void commit(LogicalRecordMapTest.Store store, String id, Consumer<Transaction> action) {
        assertTrue(store.publish(prepare(store, id, action)));
    }
    private static Publication prepare(LogicalRecordMapTest.Store store, String id, Consumer<Transaction> action) {
        try (var tx = new Transaction(store.attempt())) {
            action.accept(tx); tx.logical.stage(); return tx.attempt.prepare(id, List.of(), EVIDENCE);
        }
    }
    private static final class Transaction implements AutoCloseable {
        final CoordinationRecordAttempt attempt;
        final LogicalPointStorage logical;
        final LogicalDocumentInstances instances;
        final LogicalPointStorage.Scope<DocumentId, String> terminal, frontier, pending;
        Transaction(CoordinationRecordAttempt attempt) {
            this.attempt = attempt; logical = new LogicalPointStorage(attempt); instances = new LogicalDocumentInstances(attempt, 4096);
            terminal = progress(Family.FEEDER_TERMINAL); frontier = progress(Family.FEEDER_FRONTIER); pending = progress(Family.FEEDER_PENDING);
        }
        private LogicalPointStorage.Scope<DocumentId, String> progress(Family family) {
            return logical.openInstanceProgress(family, "fixture/lane", OrderedRecordKey.document(),
                    OrderedRecordKey.text(), LIMITS, List::of);
        }
        public void close() { attempt.close(); }
    }
}
