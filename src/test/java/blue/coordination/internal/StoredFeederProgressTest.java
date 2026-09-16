package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.internal.ContractsRootFeederWindow.*;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import blue.language.processor.closure.ExactNodeDemand;
import java.util.*;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.StoredRouteIndexesTest.Bytes;
import static org.junit.jupiter.api.Assertions.*;

final class StoredFeederProgressTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSIONS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final StoredInsertionOrderedMap.Limits MAPS = new StoredInsertionOrderedMap.Limits(
            new PersistentMapStorage.Limits(40 * 1024 * 1024, 16384, MAX, 4096, 8), MAX, 128L * 1024 * 1024, 100);
    private static final String RESOURCE = "4N8X8mM4K6cYz9V1j8Qv5A6C4a2Qj6C7v1G5d8E3r2P1";

    @Test void closedProducerColdPendingKeepsExactBarrierWhileAnotherLaneAdvances() {
        var bytes = new Bytes(); StoredFeederProgress.Snapshot snapshot; StorageState control; AttemptTicket blocked;
        List<byte[]> demands;
        try (var f = ContractsRootFeederWindowTest.fixture(); var binding = new Binding(bytes)) {
            var window = new ContractsRootFeederWindow(); var selected = window.select(f.adapter().capture(f.eventOne()));
            blocked = selected.get(0); var completed = selected.get(1);
            var first = ExactNodeDemand.derived(RESOURCE, ContractsClosureAdapter.closureId(blocked.members().get(0)), "/first");
            var second = ExactNodeDemand.derived(RESOURCE, ContractsClosureAdapter.closureId(blocked.members().get(0)), "/second");
            // Preserve order and multiplicity, not just resource identifiers.
            window.recordNeedsResources(blocked, blocked.members(), List.of(first, second, first));
            window.recordTerminal(completed, completed.members(), true, true);
            var state = window.durableState(); control = state.storageState(false);
            assertThrows(NoncommittingExecutionException.class, state::storageState);
            demands = window.requiredResourcesByLane().get(blocked.lane()).stream().map(execution()::encodeResourceDemand).toList();
            try (var stored = binding.storage.empty(binding.views)) {
                stored.retain(state.storedMaps()); snapshot = stored.snapshot();
                assertSame(state.storedMaps().pending().get(blocked.lane()), stored.maps().pending().get(blocked.lane()));
            }
        }
        var coldBytes = bytes.fresh();
        try (var binding = new Binding(coldBytes); var cold = binding.storage.open(snapshot, binding.views);
                var fresh = ContractsRootFeederWindowTest.fixture()) {
            assertEquals(0, coldBytes.writes);
            var restored = DurableState.fromStorage(control, cold.maps());
            assertSame(cold.maps().pending(), restored.storedMaps().pending());
            assertEquals(control, restored.storageState(false));
            assertThrows(NoncommittingExecutionException.class, restored::storageState);
            var row = cold.maps().pending().get(blocked.lane()); assertEquals(blocked, row.ticket());
            assertSame(row, cold.maps().pending().get(blocked.lane()));
            for (int i = 0; i < demands.size(); i++) assertArrayEquals(demands.get(i), execution().encodeResourceDemand(row.resourceDemands().get(i)));
            var window = new ContractsRootFeederWindow(restored);
            var next = window.select(fresh.adapter().capture(fresh.eventTwo()));
            assertEquals(List.of(List.of(DocumentId.of("b"))), next.stream().map(AttemptTicket::members).toList());
            var retry = window.select(fresh.adapter().capture(fresh.eventOne())); assertEquals(List.of(blocked), retry);
            assertEquals(0, coldBytes.writes, "open, exact reads and selection cannot write");
            window.recordTerminal(retry.get(0), retry.get(0).members(), false, false);
            assertTrue(cold.maps().pending().isEmpty()); assertNotNull(restored.storageState());
            // Original pinned roots still recover the pre-terminal barrier.
            try (var old = binding.storage.open(snapshot, binding.views)) { assertEquals(blocked, old.maps().pending().get(blocked.lane()).ticket()); }
        }
    }

    @Test void actualBirthRejectionColdOpenRetainsOriginalAttemptAuthority() throws Exception {
        var bytes = new Bytes(); StoredFeederProgress.Snapshot snapshot; String key, viewAddress; byte[] packet, demand;
        try (var f = new RootedDeclaredBirthRejectionTest.Scenario(); var binding = new Binding(bytes)) {
            var outcome = f.adapter.executeAndPublish(f.batch, f.invocation); var rejection = outcome.rejectedBirth();
            assertNotNull(rejection); key = rejection.terminalKey();
            viewAddress = binding.sessions.retainView(f.engine.documents().require(f.host.id()).rootedView());
            packet = new PublicationReceiptStorageCodec(MAX, 256).encodeRejection(rejection, binding.sessions::retainView);
            demand = execution().encodeResourceDemand(rejection.storedState().attempt().resourceDemands().get(0));
            var state = new DurableState(); state.rejectBirth(rejection, f.engine.documents());
            var ticket = new ContractsRootFeederWindow().select(f.batch).get(0);
            state.storedMaps().pending().put(ticket.lane(), new PendingProgress(ticket, outcome.attempt().resourceDemands()));
            assertThrows(NoncommittingExecutionException.class, state::storageState);
            try (var stored = binding.storage.empty(binding.views)) {
                stored.retain(state.storedMaps()); snapshot = stored.snapshot();
                assertSame(rejection, stored.maps().rejected().get(key));
                assertSame(rejection, stored.maps().rejected().putIfAbsent(key, rejection));
            }
        }
        var coldBytes = bytes.fresh();
        try (var binding = new Binding(coldBytes)) {
          try (var cold = binding.storage.open(snapshot, binding.views)) {
            var rejection = cold.maps().rejected().get(key); assertSame(rejection, cold.maps().rejected().get(key));
            var state = rejection.storedState(); rejection.requireSameObligation(state.selected());
            assertArrayEquals(packet, new PublicationReceiptStorageCodec(MAX, 256).encodeRejection(rejection, binding.views::addressOf));
            assertSame(state.selected().managedDraftPlan(), state.executed().managedDraftPlan());
            assertSame(state.attempt().resourceDemands().get(0), state.issues().get(0).demand());
            assertArrayEquals(demand, execution().encodeResourceDemand(cold.maps().pending().values().iterator().next().resourceDemands().get(0)));
            assertEquals(0, coldBytes.writes, "no provider, PROCESS, re-admission or physical prewrite during row decode");
            var restored = DurableState.fromStorage(new StorageState(List.of(), Map.of()), cold.maps());
            assertSame(rejection, restored.rejectedBirth(state.selected()));
            assertSame(rejection, restored.copy().rejectedBirth(state.selected()));
            assertThrows(NoncommittingExecutionException.class, restored::storageState);
            int writes = coldBytes.writes;
            assertThrows(NoncommittingExecutionException.class, () -> cold.maps().rejected().put("another-key", rejection));
            assertEquals(writes, coldBytes.writes);
          }
          assertNotNull(binding.views.view(viewAddress), "feeder close does not close the caller's shared views");
        }
    }

    @Test void pendingRowsKeepInsertionOrderAndFailuresCannotPublishPartialRetain() {
        var bytes = new Bytes();
        try (var f = ContractsRootFeederWindowTest.fixture(); var binding = new Binding(bytes);
                var stored = binding.storage.empty(binding.views)) {
            var selected = new ContractsRootFeederWindow().select(f.adapter().capture(f.eventOne()));
            var a = pending(selected.get(0)); var b = pending(selected.get(1));
            var map = stored.maps().pending(); map.put(b.ticket().lane(), b); map.put(a.ticket().lane(), a);
            assertEquals(List.of(b.ticket().lane(), a.ticket().lane()), new ArrayList<>(map.keySet()));
            assertSame(b, map.putIfAbsent(b.ticket().lane(), b));
            assertSame(b, map.remove(b.ticket().lane())); map.put(b.ticket().lane(), b);
            assertEquals(List.of(a.ticket().lane(), b.ticket().lane()), new ArrayList<>(map.keySet()));
            var before = stored.snapshot(); int writes = bytes.writes;
            assertThrows(NoncommittingExecutionException.class, () -> map.put(b.ticket().lane(), a));
            assertEquals(writes, bytes.writes); same(before, stored.snapshot());
            bytes.failWriteAt = bytes.writes + 1;
            assertThrows(NoncommittingExecutionException.class, () -> map.put(a.ticket().lane(), pending(a.ticket())));
            same(before, stored.snapshot()); assertSame(a, map.get(a.ticket().lane()));
            bytes.failWriteAt = -1;
            try (var partial = binding.storage.empty(binding.views)) {
                // The first map succeeds, then a bad second-map key fails. No partial snapshot may escape.
                var invalid = new LinkedHashMap<String, RootedDeclaredBirthRejection>(); invalid.put("bad", null);
                assertThrows(NullPointerException.class, () -> partial.retain(new DurableState.StoredMaps(Map.of(a.ticket().lane(), a), invalid)));
                assertThrows(NoncommittingExecutionException.class, partial::snapshot);
            }
            assertNotNull(binding.views, "closing the feeder does not own the shared session scope");
        }
    }

    @Test void openingDoesNotReadUnrelatedRowsAndRejectsSwappedRootsOrSelectedCorruption() {
        var bytes = new Bytes(); StoredFeederProgress.Snapshot snapshot; LaneId lane;
        try (var f = ContractsRootFeederWindowTest.fixture(); var binding = new Binding(bytes);
                var stored = binding.storage.empty(binding.views)) {
            var a = pending(new ContractsRootFeederWindow().select(f.adapter().capture(f.eventOne())).get(0)); lane = a.ticket().lane();
            stored.maps().pending().put(lane, a); snapshot = stored.snapshot();
        }
        var corrupt = bytes.fresh();
        // Damage the selected row payload only; both pinned directory roots remain valid.
        // Identify the wrapper via its length-delimited format prefix without decoding its value.
        byte[] prefix = SessionStorageWire.encode(4096, w -> w.text("blue-coordination/insertion-map/1"));
        var payload = corrupt.values.entrySet().stream().filter(row -> Arrays.equals(prefix, Arrays.copyOf(row.getValue(), prefix.length))).findFirst().orElseThrow();
        payload.getValue()[payload.getValue().length - 1] ^= 1;
        try (var binding = new Binding(corrupt); var cold = binding.storage.open(snapshot, binding.views)) {
            assertEquals(1, cold.maps().pending().size()); assertTrue(cold.maps().rejected().isEmpty());
            assertNull(cold.maps().rejected().get("unrelated"));
            assertThrows(NoncommittingExecutionException.class, () -> cold.maps().pending().get(lane));
            assertEquals(0, corrupt.writes);
            var swapped = new StoredFeederProgress.Snapshot(Map.of(StoredFeederProgress.Kind.PENDING, snapshot.roots().get(StoredFeederProgress.Kind.REJECTED),
                    StoredFeederProgress.Kind.REJECTED, snapshot.roots().get(StoredFeederProgress.Kind.PENDING)));
            assertThrows(NoncommittingExecutionException.class, () -> binding.storage.open(swapped, binding.views));
            assertThrows(IllegalArgumentException.class, () -> new StoredFeederProgress.Snapshot(Map.of()));
        }
    }

    private static PendingProgress pending(AttemptTicket ticket) {
        return new PendingProgress(ticket, List.of(ExactNodeDemand.derived(RESOURCE, ContractsClosureAdapter.closureId(ticket.members().get(0)), "/needed")));
    }
    private static ClosureExecutionEvidenceStorageCodec execution() { return new ClosureExecutionEvidenceStorageCodec(MAX, 256); }
    private static void same(StoredFeederProgress.Snapshot before, StoredFeederProgress.Snapshot after) {
        for (var kind : StoredFeederProgress.Kind.values()) {
            var a = before.roots().get(kind); var b = after.roots().get(kind);
            assertArrayEquals(a.keys(), b.keys()); assertArrayEquals(a.order(), b.order()); assertEquals(a.nextSequence(), b.nextSequence());
        }
    }
    private static final class Binding implements AutoCloseable {
        final DocumentSessionStorage sessions; final DocumentSessionStorage.OpenScope views; final StoredFeederProgress storage;
        Binding(Bytes bytes) {
            sessions = new DocumentSessionStorage(bytes, SESSIONS); views = sessions.openScope(); storage = new StoredFeederProgress(bytes, MAPS, sessions, 256);
        }
        public void close() { views.close(); }
    }
}
