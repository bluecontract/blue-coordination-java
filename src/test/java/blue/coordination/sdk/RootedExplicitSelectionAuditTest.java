package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** An explicit root has its own exact next work even while global fairness selects another root. */
final class RootedExplicitSelectionAuditTest {
    @Test void managedRootAuditDoesNotBorrowAnUnrelatedGlobalJournalTurn() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            String sourceYaml = RootedSdkFixture.resource("source.yaml");
            String authoredSource = f.blue.values().yaml(sourceYaml).blueId();
            var source = f.startYaml(sourceYaml, "rcp2/source");
            var independent = f.startYaml(sourceYaml.replace("RCP2 Source", "RCP2 Independent A")
                    .replace("rcp2/source", "rcp2/independent"), "rcp2/independent");
            var independentEntry = f.append(independent, "rcp2/independent", "tick", 5L, "{}");
            var sourceEntry = f.append(source, "rcp2/source", "tick", 10L, "{}");
            assertEquals(EntryDisposition.APPLIED,
                    f.blue.processing().processNext(source).entry(sourceEntry).disposition());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var attachment = f.append(parent, "rcp2/parent", "attach", 20L,
                    "child: {blueId: " + authoredSource + "}");
            assertEquals(EntryDisposition.APPLIED,
                    f.blue.processing().processNext(parent).entry(attachment).disposition());
            var initialization = f.blue.processing().processNext(parent);
            assertEquals(0L, initialization.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
            var expected = f.control.registeredOwnedHistory(parent.id());
            assertEquals(source.id(), expected.sourceDocumentId());
            assertEquals(1L, expected.sourceEpoch());
            var before = List.of(f.history(source), f.history(parent), f.history(independent));
            var selectedView = f.control.selectedView(parent.id()).closureIdentity();
            var journal = f.blue.advanced().auditTimelineEntries();
            assertEquals(independentEntry.blueId(), f.control.nextLiveInput(independent.id()).orElseThrow());
            var global = f.blue.advanced().auditNextProcessingSelection();
            assertEquals(ProcessingSelection.Kind.JOURNAL, global.kind());
            // when
            for (boolean restart : List.of(false, true)) {
                if (restart) CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                for (int audit = 0; audit < 3; audit++) {
                    var selected = f.blue.advanced().auditNextRootProcessingSelection(parent);
                    // then
                    assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selected.kind());
                    assertEquals(expected, selected.managedEpochApplicationWork().orElseThrow());
                    assertTrue(selected.rootedRetainedRoot().isEmpty());
                    assertEquals(global, f.blue.advanced().auditNextProcessingSelection());
                    assertEquals(before, List.of(f.history(source), f.history(parent), f.history(independent)));
                    assertEquals(selectedView, f.control.selectedView(parent.id()).closureIdentity());
                    assertEquals(journal, f.blue.advanced().auditTimelineEntries());
                }
            }
            var result = f.blue.processing().processNext(parent);
            assertTrue(result.entries().isEmpty());
            assertTrue(result.rootedRetainedApplications().isEmpty());
            assertEquals(1, result.managedEpochApplications().size());
            assertEquals(expected.workIdentity(), result.managedEpochApplicationAttempts().get(0).work().workIdentity());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(before.get(0), f.history(source));
            assertEquals(before.get(2), f.history(independent));
            assertEquals(ProcessingSelection.Kind.NONE,
                    f.blue.advanced().auditNextRootProcessingSelection(parent).kind());
            assertEquals(EntryDisposition.APPLIED,
                    f.blue.processing().processNext(independent).entry(independentEntry).disposition());
        }
    }

    @Test void liveAndEmptyRootAuditsAgreeWithActualRootProcessing() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var root = f.start("source.yaml", "rcp2/source", Map.of());
            assertEquals(ProcessingSelection.none(), f.blue.advanced().auditNextRootProcessingSelection(root));
            var entry = f.append(root, "rcp2/source", "tick", 10L, "{}");
            var before = f.history(root);
            var global = f.blue.advanced().auditNextProcessingSelection();
            // when
            for (int audit = 0; audit < 3; audit++) {
                // then
                assertEquals(ProcessingSelection.journal(), f.blue.advanced().auditNextRootProcessingSelection(root));
                assertEquals(before, f.history(root));
                assertEquals(global, f.blue.advanced().auditNextProcessingSelection());
            }
            var result = f.blue.processing().processNext(root);
            assertEquals(List.of(entry), result.entries().stream().map(EntryResult::entry).toList());
            assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition());
            assertEquals(ProcessingSelection.none(), f.blue.advanced().auditNextRootProcessingSelection(root));
            assertEquals(0L, f.blue.processing().processNext(root).stats().committedTransitions());
        }
    }

    @Test void rootAuditRejectsForeignMissingNullAndClosedRuntimeInputs() throws Exception {
        // given
        try (var f = new RootedSdkFixture(); var other = new RootedSdkFixture()) {
            var root = f.start("source.yaml", "rcp2/source", Map.of());
            var foreign = other.start("source.yaml", "rcp2/source", Map.of());
            var before = f.history(root);
            // when
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> f.blue.advanced().auditNextRootProcessingSelection(foreign));
            // then
            assertEquals(assertThrows(IllegalArgumentException.class,
                    () -> f.blue.processing().processNext(foreign)).getMessage(), rejected.getMessage());
            assertThrows(NullPointerException.class, () -> f.blue.advanced().auditNextRootProcessingSelection(null));
            var engine = (DefaultCoordinationEngine) f.blue.advanced().rawEngine();
            assertThrows(RuntimeException.class,
                    () -> engine.auditNextRootProcessingSelection(DocumentId.of("not-admitted")));
            assertEquals(before, f.history(root));
            f.blue.close();
            assertThrows(IllegalStateException.class, () -> f.blue.advanced().auditNextRootProcessingSelection(root));
        }
    }
}
