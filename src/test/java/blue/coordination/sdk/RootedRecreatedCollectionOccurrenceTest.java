package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.api.ContractsExecutionPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A retired collection key reuses its reserved generation but starts at the saved exact source. */
final class RootedRecreatedCollectionOccurrenceTest {
    @Test void readdingSavedInitializedSourceTraversesHistoryForTheNewGeneration() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.start("historical-a.yaml", "rcp2/a", Map.of());
            String saved = source.snapshot().blueId();
            var parent = fixture.startYaml(consumer(), "rcp2/b");
            assertEquals("{}", parent.snapshot().valueAt("/orders").json().replaceAll("\\s", ""));
            var original = apply(fixture, parent, fixture.append(parent, "rcp2/b", "attach", 100, reference(saved)));
            assertTrue(original.active());
            var firstTick = fixture.append(source, "rcp2/a", "tick", 200, "{}");
            apply(fixture, source, firstTick);
            fixture.retain(source);
            apply(fixture, parent, firstTick);
            assertEquals(1L, parent.snapshot().longAt("/log/0"));
            var retired = apply(fixture, parent, fixture.append(parent, "rcp2/b", "remove", 300, "{}"));
            assertFalse(retired.active());
            assertNull(retired.pendingHistoricalEpoch());
            assertEquals(original.activationGeneration() + 1L, retired.activationGeneration());
            assertEquals(source.snapshot().blueId(), retired.expectedTargetBlueId());
            var sourceHistory = fixture.history(source);
            String sourceHead = source.snapshot().blueId();
            var readd = fixture.append(parent, "rcp2/b", "attach", 400, reference(saved));
            var readded = blue.processing().processNext(parent).entry(readd);
            assertEquals(EntryDisposition.APPLIED, readded.disposition(), readded.diagnostic().toString());
            String publication = readded.closures().get(0).closureId();
            var frozenInput = blue.advanced().closureInvocation(publication).orElseThrow();
            var originalRow = frozenInput.snapshot().occurrences().stream()
                    .filter(row -> row.occurrenceIdentity().equals(retired.occurrenceIdentity())).findFirst().orElseThrow();
            assertEquals(retired.bindingIdentity(), originalRow.bindingIdentity());
            assertNull(originalRow.pendingHistoricalEpoch());
            assertFalse(originalRow.active());
            var pending = blue.advanced().closureExecution(publication).orElseThrow().occurrenceBindings().stream()
                    .filter(row -> row.occurrenceIdentity().equals(retired.occurrenceIdentity())).findFirst().orElseThrow();
            assertFalse(pending.active());
            assertEquals(Long.valueOf(0), pending.pendingHistoricalEpoch());
            assertEquals(saved, pending.expectedTargetBlueId());
            assertEquals(retired.activationGeneration(), pending.activationGeneration());
            assertEquals(retired.occurrenceIdentity(), pending.occurrenceIdentity());
            assertNotEquals(original.occurrenceIdentity(), pending.occurrenceIdentity());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            var pendingAudit = blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            assertFalse(pendingAudit.active());
            assertEquals(pending.activationGeneration(), pendingAudit.activationGeneration());
            assertEquals(source.id(), pendingAudit.targetDocumentId());
            var caughtUp = blue.processing().processNext(parent);
            assertEquals(1, caughtUp.managedEpochApplications().size(), caughtUp.managedEpochApplicationAttempts().toString());
            var work = caughtUp.managedEpochApplicationAttempts().get(0).work();
            assertEquals(source.id(), work.sourceDocumentId());
            assertEquals(1L, work.sourceEpoch());
            assertEquals(pending.occurrenceIdentity(), work.targetOccurrenceIdentity());
            var live = blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            assertTrue(live.active());
            assertEquals(pending.activationGeneration(), live.activationGeneration());
            assertEquals(1L, parent.snapshot().longAt("/log/1"));
            assertEquals(sourceHead, source.snapshot().blueId());
            assertEquals(sourceHistory, fixture.history(source));
            var secondTick = fixture.append(source, "rcp2/a", "tick", 500, "{}");
            apply(fixture, source, secondTick);
            apply(fixture, parent, secondTick);
            assertEquals(2L, parent.snapshot().longAt("/log/2"));
            assertEquals(3, parent.snapshot().valueAt("/log").copyNode().getItems().size());
            var histories = List.of(fixture.history(parent), fixture.history(source));
            var heads = List.of(parent.snapshot().blueId(), source.snapshot().blueId());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertTrue(blue.processing().processNext(parent).quiescent());
            assertEquals(heads, List.of(parent.snapshot().blueId(), source.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(parent), fixture.history(source)));
        }
    }

    @Test void retiredReservationCannotReaddAnotherLineage() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.start("historical-a.yaml", "rcp2/a", Map.of());
            var foreign = fixture.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Different source").replace("rcp2/a", "rcp2/foreign"), "rcp2/foreign");
            var parent = fixture.startYaml(consumer(), "rcp2/b");
            apply(fixture, parent, fixture.append(parent, "rcp2/b", "attach", 100, reference(source.snapshot().blueId())));
            apply(fixture, parent, fixture.append(parent, "rcp2/b", "remove", 200, "{}"));
            var retired = blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            var histories = List.of(fixture.history(parent), fixture.history(source), fixture.history(foreign));
            var heads = List.of(parent.snapshot().blueId(), source.snapshot().blueId(), foreign.snapshot().blueId());
            var entry = fixture.append(parent, "rcp2/b", "attach", 300, reference(foreign.snapshot().blueId()));
            var result = blue.processing().processNext(parent);
            assertNotEquals(EntryDisposition.APPLIED, result.entry(entry).disposition());
            assertEquals(retired, blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow());
            assertEquals(heads, List.of(parent.snapshot().blueId(), source.snapshot().blueId(), foreign.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(parent), fixture.history(source), fixture.history(foreign)));
        }
    }

    @Test void lateGasFailureRollsBackTheHistoricalSelectionAndPreservesItOnRestart() throws Exception {
        long required = readdAtGas(100_000L, true);
        assertTrue(required > 1L);
        readdAtGas(required - 1L, false);
        assertEquals(required, readdAtGas(required, true));
    }

    private static long readdAtGas(long budget, boolean succeeds) throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.start("historical-a.yaml", "rcp2/a", Map.of());
            String saved = source.snapshot().blueId();
            var parent = fixture.startYaml(consumer(), "rcp2/b");
            apply(fixture, parent, fixture.append(parent, "rcp2/b", "attach", 100, reference(saved)));
            var tick = fixture.append(source, "rcp2/a", "tick", 200, "{}");
            apply(fixture, source, tick);
            fixture.retain(source);
            apply(fixture, parent, tick);
            var reservation = apply(fixture, parent, fixture.append(parent, "rcp2/b", "remove", 300, "{}"));
            var before = List.of(parent.snapshot().blueId(), source.snapshot().blueId());
            var histories = List.of(fixture.history(parent), fixture.history(source));
            var entry = fixture.append(parent, "rcp2/b", "attach", 400, reference(saved));
            var result = blue.advanced().process(parent, entry,
                    ContractsExecutionPolicy.exactSharedGas(budget, "recreated-occurrence-gas")).entry(entry);
            assertEquals(succeeds ? EntryDisposition.APPLIED : EntryDisposition.GAS_LIMIT_EXCEEDED,
                    result.disposition(), result.diagnostic().toString());
            var execution = blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
            if (!succeeds) {
                assertTrue(execution.rollbackToInput());
                assertNull(execution.commitCompanion());
                assertTrue(execution.checkpointWrites().isEmpty());
                assertTrue(execution.managedTransitionReceipts().isEmpty());
                assertTrue(result.publicEvents().isEmpty());
                assertNotNull(execution.rejectedCharge());
                assertEquals(before, List.of(parent.snapshot().blueId(), source.snapshot().blueId()));
                assertEquals(histories, List.of(fixture.history(parent), fixture.history(source)));
                var row = execution.occurrenceBindings().stream()
                        .filter(r -> r.occurrenceIdentity().equals(reservation.occurrenceIdentity())).findFirst().orElseThrow();
                assertEquals(reservation.bindingIdentity(), row.bindingIdentity());
                assertNull(row.pendingHistoricalEpoch());
                CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                assertEquals(before, List.of(parent.snapshot().blueId(), source.snapshot().blueId()));
                assertEquals(histories, List.of(fixture.history(parent), fixture.history(source)));
            }
            return execution.totalGas();
        }
    }

    private static String reference(String id) { return "child:\n  blueId: " + id; }

    private static blue.language.processor.closure.ManagedOccurrenceBinding apply(
            RootedSdkFixture fixture, DocumentHandle root, EntryHandle entry) {
        var result = fixture.blue.processing().processNext(root).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
        return result.closures().stream().flatMap(c -> fixture.blue.advanced().closureExecution(c.closureId())
                .orElseThrow().occurrenceBindings().stream())
                .filter(row -> row.sourceDocumentId().value().equals(root.id().value())
                        && row.sourcePath().equals("/orders/same"))
                .findFirst().orElse(null);
    }

    private static String consumer() throws Exception {
        return RootedSdkFixture.resource("historical-b.yaml")
                .replace("name: RCP2 Historical B", "name: RCP2 Recreated Collection\norders: {}")
                .replace("    paths:\n    - /child", "    collectionPaths:\n    - /orders")
                .replace("/child", "/orders/same")
                // The operation request remains a single exact child value.
                .replace("event/message/request/orders/same", "event/message/request/child") + """
                  remove:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: remove
                          path: /orders/same
                      - $return: true
                """;
    }
}
