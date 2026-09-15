package blue.coordination.sdk;

import blue.coordination.api.ProcessingAvailability;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-root scheduling must not change a root's ordered history or consume another command's work. */
final class RootedSlicedSelectionTest {
    @Test void aManagedSliceYieldsToAnIndependentJournalAdmissionWithoutLosingTheNextHistoricalStep() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String original = f.retain(source);
            for (long t : List.of(10L, 11L)) {
                var entry = f.append(source, "rcp2/source", "tick", t, "{}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, entry).entry(entry).disposition());
            }
            var unrelated = f.startYaml(RootedSdkFixture.resource("source.yaml")
                    .replace("RCP2 Source", "RCP2 Unrelated").replace("rcp2/source", "rcp2/unrelated"), "rcp2/unrelated");
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child: {blueId: " + original + "}");
            assertEquals(EntryDisposition.APPLIED,
                    f.blue.advanced().drainJournalThrough(attach, DrainBudget.unlimited()).entry(attach).disposition());
            var first = f.blue.advanced().auditNextProcessingSelection();
            assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, first.kind());
            assertEquals(1, verifiedHistoricalStep(f, parent, first).managedEpochApplications().size());
            assertEquals(1L, ((Number) f.blue.advanced().auditDocument(parent.id()).current().copyNode().get("/seen")).longValue());
            var before = List.of(f.history(source), f.history(parent));

            assertEquals(ProcessingSelection.Kind.JOURNAL,
                    f.blue.advanced().auditNextProcessingSelection(ProcessingAvailability.of(true)).kind());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(ProcessingSelection.Kind.JOURNAL,
                    f.blue.advanced().auditNextProcessingSelection(ProcessingAvailability.of(true)).kind());
            var next = f.append(unrelated, "rcp2/unrelated", "tick", 30, "{}");
            // when
            var journal = f.blue.advanced().drainJournalThrough(next, DrainBudget.unlimited());
            // then
            assertEquals(EntryDisposition.APPLIED, journal.entry(next).disposition());
            assertTrue(journal.managedEpochApplications().isEmpty());
            assertEquals(1L, unrelated.snapshot().longAt("/counter"));
            assertEquals(before, List.of(f.history(source), f.history(parent)));

            var second = f.blue.advanced().auditNextProcessingSelection();
            assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, second.kind());
            assertNotEquals(first.managedEpochApplicationWork().orElseThrow().workIdentity(),
                    second.managedEpochApplicationWork().orElseThrow().workIdentity());
            assertEquals(1, verifiedHistoricalStep(f, parent, second).managedEpochApplications().size());
            assertEquals(2L, parent.snapshot().longAt("/seen"));
            assertEquals(before.get(0), f.history(source));
        }
    }

    @Test void aFailedHistoricalRootCannotBlockIndependentInputsOrSkipItsOwnPendingHistory() throws Exception {
        // given
        try (var f = new RootedSdkFixture(ContractsExecutionPolicy.exactSharedGas(3_000L, "rooted-fairness"))) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String original = f.retain(source);
            var unrelated = f.startYaml(RootedSdkFixture.resource("source.yaml")
                    .replace("RCP2 Source", "RCP2 Unrelated").replace("rcp2/source", "rcp2/unrelated"), "rcp2/unrelated");
            String parentYaml = RootedSdkFixture.resource("parent.yaml");
            int observe = parentYaml.indexOf("  observe:");
            int steps = parentYaml.indexOf("    steps:\n", observe) + "    steps:\n".length();
            var parent = f.startYaml(parentYaml.substring(0, steps) + parentYaml.substring(steps).repeat(60), "rcp2/parent");
            var tick = f.append(source, "rcp2/source", "tick", 10, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.advanced().process(source, tick,
                    ContractsExecutionPolicy.releaseDefault()).entry(tick).disposition());
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child: {blueId: " + original + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.advanced().process(parent, attach,
                    ContractsExecutionPolicy.releaseDefault()).entry(attach).disposition());
            var before = List.of(f.history(source), f.history(parent));
            var first = f.blue.advanced().auditNextProcessingSelection();
            // when
            var failed = verifiedHistoricalStep(f, parent, first);
            // then
            assertTrue(failed.managedEpochApplications().isEmpty());
            assertEquals("GAS_LIMIT_EXCEEDED", failed.managedEpochApplicationAttempts().get(0).attempt().processResult().status().name());
            assertEquals(before, List.of(f.history(source), f.history(parent)));

            // An input to this same parent remains behind its failed retained step.
            var laterParent = f.append(parent, "rcp2/parent", "attach", 30, "child: {blueId: " + original + "}");
            assertEquals(first.managedEpochApplicationWork(), f.blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork());
            for (int retry = 0; retry < 2; retry++) {
                // Audits do not consume the fair turn. Restart retains the previous failure's isolation.
                for (int audit = 0; audit < 3; audit++) assertEquals(ProcessingSelection.Kind.JOURNAL,
                        f.blue.advanced().auditNextProcessingSelection(ProcessingAvailability.of(true)).kind());
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                var independent = f.append(unrelated, "rcp2/unrelated", "tick", 40 + retry, "{}");
                var result = f.blue.advanced().drainJournalThrough(independent, DrainBudget.unlimited());
                assertEquals(EntryDisposition.APPLIED, result.entry(independent).disposition());
                assertTrue(result.managedEpochApplications().isEmpty());
                assertFalse(result.entries().stream().anyMatch(entry -> entry.entry().equals(laterParent)));
                assertEquals(before, List.of(f.history(source), f.history(parent)));
                var next = f.blue.advanced().auditNextProcessingSelection();
                assertEquals(first.managedEpochApplicationWork(), next.managedEpochApplicationWork());
                var repeated = verifiedHistoricalStep(f, parent, next);
                assertEquals(failed.managedEpochApplicationAttempts().get(0).attempt().processResult().gasTraceIdentity(),
                        repeated.managedEpochApplicationAttempts().get(0).attempt().processResult().gasTraceIdentity());
                assertEquals(before, List.of(f.history(source), f.history(parent)));
            }
            assertEquals(2L, unrelated.snapshot().longAt("/counter"));
        }
    }

    @Test void historicalRoundsRotateBetweenOwnersWithoutReorderingEitherHistory() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String original = f.retain(source);
            var p = f.start("parent.yaml", "rcp2/parent", Map.of());
            var q = f.startYaml(RootedSdkFixture.resource("parent.yaml").replace("RCP2 Parent", "RCP2 Other Parent")
                    .replace("rcp2/parent", "rcp2/other-parent"), "rcp2/other-parent");
            for (long t : List.of(10L, 11L, 12L)) {
                var tick = f.append(source, "rcp2/source", "tick", t, "{}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, tick).entry(tick).disposition());
            }
            var sourceHistory = f.history(source);
            var attachP = f.append(p, "rcp2/parent", "attach", 20, "child: {blueId: " + original + "}");
            var attachQ = f.append(q, "rcp2/other-parent", "attach", 30, "child: {blueId: " + original + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(p, attachP).entry(attachP).disposition());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(q, attachQ).entry(attachQ).disposition());
            // when
            for (long epoch = 1; epoch <= 3; epoch++) {
                var owners = new java.util.HashSet<blue.coordination.api.DocumentId>();
                for (int turn = 0; turn < 2; turn++) {
                    var next = f.blue.advanced().auditNextProcessingSelection();
                    var work = next.managedEpochApplicationWork().orElseThrow();
                    assertEquals(epoch, work.sourceEpoch());
                    assertTrue(owners.add(work.consumerDocumentId()), "Each owner receives one step per round");
                    assertEquals(work, f.blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow());
                    verifiedHistoricalStep(f, work.consumerDocumentId().equals(p.id()) ? p : q, next);
                }
                assertEquals(java.util.Set.of(p.id(), q.id()), owners);
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                assertEquals(sourceHistory, f.history(source));
            }
            // then
            for (var parent : List.of(p, q)) {
                assertEquals(3L, parent.snapshot().longAt("/seen"));
                for (int i = 0; i < 3; i++) assertEquals(i + 1L, parent.snapshot().longAt("/log/" + i));
            }
        }
    }

    private static DrainResult verifiedHistoricalStep(RootedSdkFixture f, DocumentHandle parent, ProcessingSelection selection) {
        assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selection.kind());
        var input = f.control.captureRegisteredOwnedHistory(parent.id());
        var selectedWork = selection.managedEpochApplicationWork().orElseThrow();
        var sourceReceipt = f.blue.advanced().auditManagedEpoch(selectedWork.sourceDocumentId(), selectedWork.sourceEpoch()).orElseThrow();
        assertEquals(selectedWork.sourceReceiptIdentity(), sourceReceipt.receiptIdentity());
        var reference = RootedCalculationFixture.materializedReference(input,
                List.of(f.blue.values().yaml("{}").unwrap(), sourceReceipt.afterDocument().unwrap()));
        var actual = f.blue.processing().drainManagedEpochApplication(
                selection.managedEpochApplicationWork().orElseThrow().workIdentity());
        var result = actual.managedEpochApplicationAttempts().get(0).attempt().processResult();
        assertEquals(reference.status().name(), result.status().name());
        assertEquals(reference.totalGas(), result.totalGas());
        assertEquals(reference.gasTraceIdentity(), result.gasTraceIdentity());
        assertEquals(reference.outputClosureIdentity(), result.outputClosureIdentity());
        return actual;
    }
}
