package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.internal.CoordinationTestControl;
import static org.junit.jupiter.api.Assertions.*;

final class RootedGlobalDriverTest {
    @Test void globalAuditSelectsEachExactHistoricalSuccessorBeforeResumingLiveWork() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var parent = fixture.start("historical-b.yaml", "rcp2/b", Map.of());
            var source = fixture.start("historical-a.yaml", "rcp2/a", Map.of());
            String saved = null;
            for (int n = 1; n <= 10; n++) {
                var entry = fixture.append(source, "rcp2/a", "tick", 100 + n, "{}");
                blue.processing().process(source, entry);
                fixture.retain(source);
                if (n == 5) saved = source.snapshot().blueId();
            }
            var sourceHistory = fixture.history(source);
            var attach = fixture.append(parent, "rcp2/b", "attach", 200, "child:\n  blueId: " + saved);
            blue.processing().process(parent, attach);
            for (int n = 6; n <= 10; n++) {
                var selected = blue.advanced().auditNextProcessingSelection();
                // then
                assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selected.kind());
                var work = selected.managedEpochApplicationWork().orElseThrow();
                assertEquals(n, work.sourceEpoch());
                var result = blue.processing().drainManagedEpochApplication(work.workIdentity());
                assertTrue(result.entries().isEmpty(), "A targeted retained step cannot execute another journal input");
                assertEquals(1, result.managedEpochApplications().size());
                assertEquals(sourceHistory, fixture.history(source));
                assertEquals(n == 10, result.quiescent());
            }
            assertEquals(10L, parent.snapshot().longAt("/seen"));
            assertTrue(blue.processing().drain().quiescent());
            assertEquals(sourceHistory, fixture.history(source));
        }
    }

    @Test void boundedJournalReportsTheStillPendingParentAndResumesAfterStoreRestart() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", source.snapshot().blueId()));
            var input = fixture.append(source, "rcp2/source", "tick", 100, "{}");
            var sourceStep = blue.processing().drainJournal(new DrainBudget(1, 1));
            // then
            assertTrue(sourceStep.paused()); assertFalse(sourceStep.quiescent());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(0L, parent.snapshot().longAt("/seen"));
            var history = fixture.history(source);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(ProcessingSelection.Kind.JOURNAL, blue.advanced().auditNextProcessingSelection().kind());
            var parentStep = blue.processing().drainJournal(new DrainBudget(1, 1));
            assertEquals(EntryDisposition.APPLIED, parentStep.entry(input).disposition());
            assertTrue(parentStep.quiescent());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(history, fixture.history(source));
            assertEquals(ProcessingSelection.Kind.NONE, blue.advanced().auditNextProcessingSelection().kind());
            assertTrue(blue.processing().drain().entries().isEmpty());
        }
    }

    @Test void anOlderParentsInputPrecedesANewerIndependentSourceInput() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", source.snapshot().blueId()));
            var first = fixture.append(source, "rcp2/source", "tick", 100, "{}");
            var later = fixture.append(source, "rcp2/source", "tick", 110, "{}");
            blue.processing().process(source, first);
            var step = blue.processing().drainJournal(new DrainBudget(1, 1));
            // then
            assertEquals(first, step.entries().get(0).entry());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertTrue(step.paused());
            var completed = blue.processing().drain();
            assertTrue(completed.quiescent());
            assertEquals(EntryDisposition.APPLIED, completed.entry(later).disposition());
            assertEquals(2L, parent.snapshot().longAt("/seen"));
            assertEquals(2L, source.snapshot().longAt("/counter"));
        }
    }

    @Test void globalDrainSchedulesEachRootWithoutErasingAnotherRootsNewness() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", source.snapshot().blueId()));
            var entry = fixture.append(source, "rcp2/source", "tick", 100, "{}");
            var drained = blue.processing().drain();
            // then
            assertTrue(drained.quiescent());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(1L, parent.snapshot().longAt("/seen"), "Global progress must not erase P's local pending input");
            assertEquals(2, fixture.history(source).size());
            assertEquals(2, fixture.history(parent).size());
            assertEquals(EntryDisposition.APPLIED, drained.entry(entry).disposition());
            var sourceHistory = fixture.history(source);
            var parentHistory = fixture.history(parent);
            assertTrue(blue.processing().drain().quiescent());
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(parentHistory, fixture.history(parent));
        }
    }
}
