package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A detached path imports a foreign authored source without inheriting the retired cursor. */
final class RootedDetachedAuthoredRetargetTest {
    private static final String PATH = "/orders/same";

    @Test
    void authoredReferenceImportsInitializationAndOrderedForeignEvents() throws Exception {
        // given
        boolean inline = false;
        // when
        long gas = scenario(inline, 2, 100_000L, true);
        // then
        assertTrue(gas > 0L);
    }

    @Test
    void authoredInlineImportsInitializationAndOrderedForeignEvents() throws Exception {
        // given
        boolean inline = true;
        // when
        long gas = scenario(inline, 2, 100_000L, true);
        // then
        assertTrue(gas > 0L);
    }

    @Test
    void authoredSelectionStillImportsEpochZeroWhenForeignSourceHasNoLaterHistory() throws Exception {
        // given
        int advances = 0;
        // when
        long referenceGas = scenario(false, advances, 100_000L, true);
        long inlineGas = scenario(true, advances, 100_000L, true);
        // then
        assertTrue(referenceGas > 0L);
        assertTrue(inlineGas > 0L);
    }

    @Test
    void detachedForeignSelectionPreservesItsMeasuredGasBoundaryAndFailedReplay() throws Exception {
        // given
        long required = scenario(false, 2, 100_000L, true);
        assertTrue(required > 1L);
        // when
        long failedGas = scenario(false, 2, required - 1L, false);
        // then
        assertTrue(failedGas <= required - 1L);
        assertEquals(required, scenario(false, 2, required, true));
    }

    private static long scenario(boolean inline, int advances, long budget, boolean succeeds) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            String cYaml = RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Detached authored source C")
                    .replace("rcp2/a", "rcp2/c");
            var authoredC = f.blue.values().yaml(cYaml);
            f.exact.put(authoredC.blueId(), authoredC.json());
            var c = f.startYaml(cYaml, "rcp2/c");
            assertEquals(authoredC.blueId(), c.id().value());
            var parent = f.startYaml(RootedRetargetInputTest.consumer(), "rcp2/b");
            applied(f, parent, f.append(parent, "rcp2/b", "attach", 100,
                    reference(b.snapshot().blueId())));
            for (int tick = 0; tick < advances; tick++) {
                applied(f, c, f.append(c, "rcp2/c", "tick", 200 + tick, "{}"));
                f.retain(c);
            }
            var removal = applied(f, parent, f.append(parent, "rcp2/b", "remove", 300, "{}"));
            var reserved = row(execution(f, removal).occurrenceBindings(), parent);
            assertFalse(reserved.active());
            assertNull(reserved.pendingHistoricalEpoch());
            assertEquals(2L, reserved.activationGeneration());
            var heads = heads(parent, b, c);
            var histories = List.of(f.history(parent), f.history(b), f.history(c));
            var sourceReceipts = List.of(f.blue.advanced().auditManagedEpochs(b.id()),
                    f.blue.advanced().auditManagedEpochs(c.id()));
            var sourceEvidence = List.of(sourceEvidence(f, b), sourceEvidence(f, c));
            assertEquals(advances, sourceReceipts.get(1).stream()
                    .mapToInt(receipt -> receipt.emittedEvents().size()).sum());
            String request = inline ? "child:\n" + cYaml.indent(2) : reference(authoredC.blueId());
            var entry = f.append(parent, "rcp2/b", "attach", 400, request);
            var policy = ContractsExecutionPolicy.exactSharedGas(budget, "detached-authored-retarget-gas");
            var accepted = f.blue.advanced().process(parent, entry, policy).entry(entry);
            assertEquals(succeeds ? EntryDisposition.APPLIED : EntryDisposition.GAS_LIMIT_EXCEEDED,
                    accepted.disposition(), accepted.diagnostic().toString());
            var output = execution(f, accepted);
            var input = f.blue.advanced().closureInvocation(accepted.closures().get(0).closureId()).orElseThrow();
            var original = row(input.snapshot().occurrences(), parent);
            assertEquals(reserved.bindingIdentity(), original.bindingIdentity());
            assertEquals(b.id().value(), original.targetDocumentId().value());
            assertFalse(original.active());
            assertNull(original.pendingHistoricalEpoch());
            assertEquals(heads.subList(1, 3), heads(b, c));
            assertEquals(histories.subList(1, 3), List.of(f.history(b), f.history(c)));
            if (!succeeds) {
                assertTrue(output.rollbackToInput());
                assertNull(output.commitCompanion());
                assertTrue(output.checkpointWrites().isEmpty());
                assertTrue(output.managedTransitionReceipts().isEmpty());
                assertTrue(output.publicEvents().isEmpty());
                assertTrue(accepted.publicEvents().isEmpty());
                assertNotNull(output.rejectedCharge());
                assertEquals(reserved.bindingIdentity(), row(output.occurrenceBindings(), parent).bindingIdentity());
                assertEquals(heads, heads(parent, b, c));
                assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
                var replay = f.blue.advanced().process(parent, entry, policy).entry(entry);
                var replayOutput = execution(f, replay);
                assertEquals(accepted.disposition(), replay.disposition());
                assertEquals(accepted.stats(), replay.stats());
                assertEquals(accepted.diagnostic(), replay.diagnostic());
                assertEquals(output.invocationIdentity(), replayOutput.invocationIdentity());
                assertEquals(output.gasTraceIdentity(), replayOutput.gasTraceIdentity());
                assertEquals(output.rejectedCharge().rejectedChargeIdentity(),
                        replayOutput.rejectedCharge().rejectedChargeIdentity());
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                assertEquals(heads, heads(parent, b, c));
                assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
                assertEquals(reserved.activationGeneration(),
                        f.blue.advanced().auditManagedOccurrence(parent.id(), PATH).orElseThrow().activationGeneration());
            } else {
                assertFalse(output.rollbackToInput());
                assertNotNull(output.commitCompanion());
                var pending = row(output.occurrenceBindings(), parent);
                assertEquals(c.id().value(), pending.targetDocumentId().value());
                assertEquals(reserved.activationGeneration(), pending.activationGeneration());
                assertNotEquals(reserved.occurrenceIdentity(), pending.occurrenceIdentity());
                assertNotEquals(reserved.bindingIdentity(), pending.bindingIdentity());
                assertEquals(authoredC.blueId(), pending.expectedTargetBlueId());
                assertEquals(Long.valueOf(-1L), pending.pendingHistoricalEpoch());
                assertFalse(pending.active());
                var frozenC = input.snapshot().managedDocument(pending.targetDocumentId());
                assertEquals(heads.get(2), frozenC.blueId());
                assertEquals(advances, frozenC.epoch());
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                var epochs = new ArrayList<Long>();
                for (long epoch = 0; epoch <= advances; epoch++) {
                    var imported = f.blue.processing().processNext(parent);
                    assertFalse(imported.blocked(), imported.diagnostic().toString());
                    assertTrue(imported.entries().isEmpty());
                    assertEquals(1, imported.managedEpochApplications().size(),
                            imported.managedEpochApplicationAttempts().toString());
                    assertEquals(1, imported.managedEpochApplicationAttempts().size());
                    var attempt = imported.managedEpochApplicationAttempts().get(0);
                    assertTrue(attempt.published());
                    assertFalse(attempt.replayed());
                    assertEquals(c.id(), attempt.work().sourceDocumentId());
                    assertEquals(epoch, attempt.work().sourceEpoch());
                    assertEquals(pending.occurrenceIdentity(), attempt.work().targetOccurrenceIdentity());
                    epochs.add(attempt.work().sourceEpoch());
                    var committed = f.blue.advanced().auditDocument(parent.id());
                    assertEquals(epoch, assertInstanceOf(Number.class,
                            committed.valueAt("/seen").copyNode().getValue()).longValue());
                    assertEquals((int) epoch, committed.valueAt("/log").copyNode().getItems().size());
                    assertEquals(epoch == advances, imported.quiescent());
                    assertEquals(heads.subList(1, 3), heads(b, c));
                    assertEquals(sourceEvidence, List.of(sourceEvidence(f, b), sourceEvidence(f, c)));
                }
                assertEquals(java.util.stream.LongStream.rangeClosed(0, advances).boxed().toList(), epochs);
                assertTrue(parent.snapshot().ready());
                assertEquals(f.blue.advanced().auditDocument(parent.id()).blueId(), parent.snapshot().blueId());
                assertEquals(advances, parent.snapshot().longAt("/seen"));
                for (int tick = 1; tick <= advances; tick++)
                    assertEquals(tick, parent.snapshot().longAt("/log/" + (tick - 1)));
                var active = f.blue.advanced().auditManagedOccurrence(parent.id(), PATH).orElseThrow();
                assertTrue(active.active());
                assertEquals(c.id(), active.targetDocumentId());
                assertEquals(pending.activationGeneration(), active.activationGeneration());
                var finalHeads = heads(parent, b, c);
                var finalHistories = List.of(f.history(parent), f.history(b), f.history(c));
                var repeated = f.blue.advanced().process(parent, entry, policy).entry(entry);
                assertEquals(EntryDisposition.STALE, repeated.disposition());
                assertEquals("STALE_TARGET_DOCUMENT", repeated.diagnostic().code());
                assertEquals(output.invocationIdentity(), execution(f, accepted).invocationIdentity());
                assertEquals(output.gasTraceIdentity(), execution(f, accepted).gasTraceIdentity());
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                assertTrue(f.blue.processing().processNext(parent).quiescent());
                assertEquals(finalHeads, heads(parent, b, c));
                assertEquals(finalHistories, List.of(f.history(parent), f.history(b), f.history(c)));
            }
            assertEquals(sourceEvidence, List.of(sourceEvidence(f, b), sourceEvidence(f, c)));
            return output.totalGas();
        }
    }

    private static EntryResult applied(RootedSdkFixture f, DocumentHandle owner, EntryHandle entry) {
        var result = f.blue.processing().processNext(owner).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
        return result;
    }

    private static ClosureProcessResult execution(RootedSdkFixture f, EntryResult result) {
        return f.blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
    }

    private static ManagedOccurrenceBinding row(List<ManagedOccurrenceBinding> rows, DocumentHandle parent) {
        return rows.stream().filter(row -> row.sourceDocumentId().value().equals(parent.id().value())
                && row.sourcePath().equals(PATH)).findFirst().orElseThrow();
    }

    private static List<String> heads(DocumentHandle... documents) {
        return Arrays.stream(documents).map(document -> document.snapshot().blueId()).toList();
    }

    private static List<List<Object>> sourceEvidence(RootedSdkFixture f, DocumentHandle document) {
        return f.blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> Arrays.<Object>asList(
                receipt.receiptIdentity(), receipt.afterDocument().json(), receipt.emittedEvents().stream()
                        .map(event -> List.of(event.managedEventIdentity(), event.exactEvent().json())).toList())).toList();
    }

    private static String reference(String blueId) { return "child:\n  blueId: " + blueId; }
}
