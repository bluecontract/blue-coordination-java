package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecordStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Binding protocol controls; these do not stand in for the connected runtime lifecycle proof. */
final class LogicalDocumentInstancesTest {
    private static final DocumentId A = DocumentId.of("semantic-a"), B = DocumentId.of("semantic-b");
    private static final DocumentInstanceRef A1 = new DocumentInstanceRef(A, "a-1");
    private static final DocumentInstanceRef A2 = new DocumentInstanceRef(A, "a-2");
    private static final DocumentInstanceRef B1 = new DocumentInstanceRef(B, "b-1");
    private static final Bytes EVIDENCE = bytes("binding-protocol-control");

    @Test void initialExecutionIsStablePerDocumentAndCannotResurrectATombstone() {
        // given
        var store = new Records();
        var references = new ArrayList<DocumentInstanceRef>();
        // when
        store.commit("initial", instances -> {
            references.add(instances.requireOrCreateInitial(A));
            references.add(instances.requireOrCreateInitial(B));
            // then
            assertNotEquals(references.get(0).instanceId(), references.get(1).instanceId());
            assertEquals(references.get(0), instances.requireOrCreateInitial(A));
        });
        store.commit("cold", instances -> assertEquals(references.get(0), instances.requireOrCreateInitial(A)));
        store.commit("retire", instances -> instances.retire(instances.requireActive(references.get(0))));
        store.commit("after", instances -> {
            assertThrows(IllegalStateException.class, () -> instances.requireOrCreateInitial(A));
            assertEquals(references.get(1), instances.requireOrCreateInitial(B));
        });
    }

    @Test void replacementFencesOldPacketsWithoutConflictingWithIndependentBInEitherOrder() {
        for (boolean reverse : List.of(false, true)) {
            // given
            // Two instances share one address and one initialization packet.
            var store = new Records();
            store.commit("initial", instances -> { instances.start(A1); instances.start(B1); });
            Publication oldA = store.prepare("old-a", instances -> instances.requireActive(A1));
            Publication independentB = store.prepare("b-only", instances -> instances.requireActive(B1));
            Publication retireA = store.prepare("retire-a", instances -> instances.retire(instances.requireActive(A1)));
            // when
            assertTrue(store.publish(reverse ? independentB : retireA));
            assertTrue(store.publish(reverse ? retireA : independentB));
            store.commit("replacement", instances -> instances.start(A2));
            // then
            assertFalse(store.publish(oldA));
            store.commit("inspect", instances -> {
                assertEquals(3, instances.requireActive(A2).generation());
                assertEquals(1, instances.requireActive(B1).generation());
                assertThrows(IllegalStateException.class, () -> instances.requireActive(A1));
            });
            assertTrue(independentB.queries().isEmpty());
            assertEquals(Set.of(Family.INSTANCE_BINDING, Family.INSTANCE_IDENTITY), independentB.points().stream()
                    .map(point -> point.key().family()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(2, independentB.points().size(), "B selection must not read A or an account generation");
        }
    }

    @Test void absentSelectionRetainsTombstoneGenerationAndRejectsAbaPublication() {
        // given
        var store = new Records();
        Publication neverPresent = store.prepare("absent-before", instances -> {
            var selection = instances.select(A);
            assertEquals(0, selection.generation()); assertTrue(selection.instance().isEmpty());
        });
        // when
        store.commit("start", instances -> instances.start(A1));
        store.commit("retire", instances -> instances.retire(instances.requireActive(A1)));
        // then
        assertFalse(store.publish(neverPresent));
        store.commit("absent-after", instances -> {
            var selection = instances.select(A);
            assertEquals(2, selection.generation()); assertTrue(selection.instance().isEmpty());
            assertThrows(IllegalStateException.class, () -> instances.start(A1));
            assertThrows(IllegalStateException.class, () -> instances.start(new DocumentInstanceRef(B, A1.instanceId())));
        });
    }

    @Test void oldRetainedAssociationRemainsExactAndDoesNotObserveActiveBinding() {
        // given
        var store = new Records(); store.commit("start", instances -> instances.start(A1));
        Publication oldHistory = store.prepare("retained-reader", instances -> instances.requireRetained(A1));
        // when
        store.commit("retire", instances -> instances.retire(instances.requireActive(A1)));
        store.commit("replacement", instances -> instances.start(A2));
        // then
        assertTrue(store.publish(oldHistory), "Historical association does not follow today's head");
        assertEquals(1, oldHistory.points().size());
        assertEquals(Family.INSTANCE_IDENTITY, oldHistory.points().get(0).key().family());
        store.commit("wrong-association", instances -> assertThrows(RuntimeException.class,
                () -> instances.requireRetained(new DocumentInstanceRef(B, A1.instanceId()))));
    }

    @Test void replacementInsideOnePacketHasTheActualProjectedBindingRevision() {
        // given
        var store = new Records(); store.commit("start", instances -> instances.start(A1));
        // when
        // Multiple pending writes still produce one physical revision increment.
        store.commit("replace", instances -> {
            instances.retire(instances.requireActive(A1));
            assertEquals(2, instances.start(A2).generation());
        });
        // then
        store.commit("inspect", instances -> {
            assertEquals(2, instances.requireActive(A2).generation()); instances.requireRetained(A1);
        });
    }

    @Test void duplicateStartsAndStaleRetirementSelectionsCannotMutateTheBinding() {
        // given
        var store = new Records(); store.commit("start", instances -> instances.start(A1));
        var original = new LogicalDocumentInstances.Binding(A, 1, Optional.of(A1));
        // when
        store.commit("duplicate", instances -> {
            // then
            assertThrows(IllegalStateException.class, () -> instances.start(A1));
            assertThrows(IllegalStateException.class, () -> instances.start(A2));
            assertThrows(IllegalStateException.class, () -> instances.retire(
                    new LogicalDocumentInstances.Binding(A, 2, Optional.of(A1))));
            assertEquals(original, instances.requireActive(A1));
        });
        store.commit("retire", instances -> instances.retire(original));
        store.commit("stale", instances -> assertThrows(IllegalStateException.class, () -> instances.retire(original)));
    }

    @Test void neverReuseAlsoRejectsAnIllegallyDeletedIdentityAndMalformedReferences() {
        // given
        var store = new Records(); store.commit("start", instances -> instances.start(A1));
        store.commit("retire", instances -> instances.retire(instances.requireActive(A1)));
        var identity = store.data.keySet().stream().filter(key -> key.family() == Family.INSTANCE_IDENTITY).findFirst().orElseThrow();
        // when
        store.data.put(identity, new Value(2, null)); // adversarial storage corruption, not a supported writer
        // then
        store.commit("reuse", instances -> assertThrows(IllegalStateException.class, () -> instances.start(A1)));
        assertThrows(NullPointerException.class, () -> new DocumentInstanceRef(null, "one"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentInstanceRef(A, " "));
        assertThrows(IllegalArgumentException.class, () -> new DocumentInstanceRef(A, "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentInstanceRef(A, "\uDC00"));
        assertEquals("instance-😀", new DocumentInstanceRef(A, "instance-😀").instanceId());
    }

    private static Bytes bytes(String text) { return new Bytes(text.getBytes(StandardCharsets.UTF_8)); }

    /** Snapshot/conditional storage double: validates the detached protocol, with no runtime mocks. */
    private static final class Records {
        private final Address address = new Address("account", "alice");
        private final Map<Key, Value> data = new TreeMap<>();
        Publication prepare(String id, Consumer<LogicalDocumentInstances> action) {
            var pinned = new TreeMap<>(data);
            var scope = new CoordinationRecordStore.ReadScope() {
                private boolean closed;
                private void check() { if (closed) throw new IllegalStateException("Closed snapshot"); }
                public Address address() { check(); return address; }
                public Value read(Key key) { check(); return pinned.getOrDefault(key, Value.absent()); }
                public List<Row> query(Range range) {
                    check(); return pinned.entrySet().stream().filter(row -> range.contains(row.getKey()) && row.getValue().present())
                            .map(row -> new Row(row.getKey(), row.getValue())).toList();
                }
                public void close() { closed = true; }
            };
            try (var attempt = new CoordinationRecordAttempt(scope)) {
                action.accept(new LogicalDocumentInstances(attempt, 4096));
                return attempt.prepare(id, List.of(), EVIDENCE);
            }
        }
        void commit(String id, Consumer<LogicalDocumentInstances> action) { assertTrue(publish(prepare(id, action))); }
        boolean publish(Publication packet) {
            assertEquals(address, packet.address());
            assertTrue(packet.queries().isEmpty());
            if (packet.points().stream().anyMatch(point -> !point.expected().equals(data.getOrDefault(point.key(), Value.absent())))) return false;
            for (var mutation : packet.mutations()) data.put(mutation.key(),
                    new Value(data.getOrDefault(mutation.key(), Value.absent()).revision() + 1, mutation.content()));
            return true;
        }
    }
}
