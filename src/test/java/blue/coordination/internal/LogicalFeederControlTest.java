package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.internal.ContractsRootFeederWindow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Feeder storage-state controls, not application publication qualification. */
final class LogicalFeederControlTest {
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final StoredInsertionOrderedMap.Limits LIMITS = new StoredInsertionOrderedMap.Limits(
            new PersistentMapStorage.Limits(65536, 8192, 32768, 8192, 64), 65536, 8 * 1024 * 1024, 128);

    @Test void independentlySelectedCohortsPublishTerminalProgressInBothOrdersAndStayTerminalCold() {
        // given
        for (boolean reverse : List.of(false, true)) {
            var store = new LogicalRecordMapTest.Store();
            try (var fixture = ContractsRootFeederWindowTest.fixture()) {
                var original = fixture.adapter().capture(fixture.eventOne());
                try (var initial = store.attempt()) {
                    var instances = new LogicalDocumentInstances(initial, LIMITS.maximumRecordBytes());
                    new ContractsRootFeederWindow().select(original).stream()
                            .flatMap(ticket -> ticket.lane().roots().stream()).distinct()
                            .forEach(instances::requireOrCreateInitial);
                    assertTrue(store.publish(initial.prepare("initial-instances", List.of(), EVIDENCE)));
                }
                var a = store.attempt(); var b = store.attempt();
                var ca = new LogicalPointStorage(a); var cb = new LogicalPointStorage(b);
                var left = selected(original, 0); var right = selected(original, 1);
                var wa = window(ca); var wb = window(cb);
                // when
                var ta = wa.select(left).get(0); var tb = wb.select(right).get(0);
                wa.recordTerminal(ta, ta.members(), true, true); wb.recordTerminal(tb, tb.members(), false, false);
                ca.stage(); cb.stage();
                var pa = a.prepare("left", List.of(), EVIDENCE); var pb = b.prepare("right", List.of(), EVIDENCE);
                // then
                assertTrue(pa.queries().isEmpty()); assertTrue(pb.queries().isEmpty());
                assertEquals(Set.of(Family.FEEDER_TERMINAL, Family.FEEDER_FRONTIER),
                        pa.mutations().stream().map(m -> m.key().family()).collect(java.util.stream.Collectors.toSet()));
                assertTrue(store.publish(reverse ? pb : pa)); assertTrue(store.publish(reverse ? pa : pb));
                var cold = window(new LogicalPointStorage(store.attempt()));
                assertTrue(cold.select(left).isEmpty()); assertTrue(cold.select(right).isEmpty());
                assertTrue(cold.isTerminal(original));
                assertEquals(2, cold.durableState().storageState(false).terminalProgress().size());
            }
        }
    }

    @Test void concurrentProgressOnTheSameLaneConflictsAndColdFrontierRejectsAnEarlierEvent() {
        // given
        var store = new LogicalRecordMapTest.Store(); var a = store.attempt(); var b = store.attempt();
        var ca = new LogicalPointStorage(a); var cb = new LogicalPointStorage(b);
        try (var fixture = ContractsRootFeederWindowTest.fixture()) {
            var first = selected(fixture.adapter().capture(fixture.eventOne()), 0);
            var later = selected(fixture.adapter().capture(fixture.eventTwo()), 0);
            var wa = window(ca); var wb = window(cb);
            // when
            var ta = wa.select(first).get(0); var tb = wb.select(later).get(0);
            wa.recordTerminal(ta, ta.members(), true, true); wb.recordTerminal(tb, tb.members(), true, true);
            ca.stage(); cb.stage();
            var pa = a.prepare("first", List.of(), EVIDENCE); var pb = b.prepare("later", List.of(), EVIDENCE);
            // then
            assertTrue(store.publish(pb)); assertFalse(store.publish(pa));
            assertThrows(IllegalStateException.class, () -> window(new LogicalPointStorage(store.attempt())).select(first));
        }
    }

    @Test void openingControlHasNoReadsAndRetiresWithItsAttempt() {
        // given
        var store = new LogicalRecordMapTest.Store(); var a = store.attempt(); var ca = new LogicalPointStorage(a);
        var window = window(ca);
        // when
        ca.stage(); var empty = a.prepare("empty", List.of(), EVIDENCE);
        // then
        assertTrue(empty.points().isEmpty()); assertTrue(empty.queries().isEmpty()); assertTrue(empty.mutations().isEmpty());
        assertThrows(IllegalStateException.class, () -> window.durableState().storageState(false));
    }

    private static ContractsClosureAdapter.FrozenBatch selected(ContractsClosureAdapter.FrozenBatch original, int position) {
        return new ContractsClosureAdapter.FrozenBatch(original.entry(), original.routeGeneration(), List.of(original.invocations().get(position)));
    }
    private static ContractsRootFeederWindow window(LogicalPointStorage storage) {
        return new ContractsRootFeederWindow(LogicalFeederControl.open(storage, DurableState.StoredMaps.empty(), LIMITS));
    }
}
