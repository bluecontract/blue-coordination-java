package blue.coordination.sdk;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The durable host may complete an entry's observers without consuming later inputs. */
final class RootedJournalCutoffTest {
    @Test void journalSlicesFinishTheSameEntryAndLeaveFutureWorkPending() throws Exception {
        try (var f = new RootedSdkFixture(); var foreign = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of("child", f.retain(source)));
            var future = f.startYaml(RootedSdkFixture.resource("source.yaml")
                    .replace("RCP2 Source", "RCP2 Future").replace("rcp2/source", "rcp2/future"), "rcp2/future");
            var later = f.append(future, "rcp2/future", "tick", 500, "{}");
            var input = f.append(source, "rcp2/source", "tick", 100, "{}");
            var first = f.blue.advanced().drainJournalThrough(input, DrainBudget.unlimited());
            assertEquals(1, first.entry(input).closures().size());
            assertFalse(first.quiescent());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(0L, parent.snapshot().longAt("/seen"));
            var second = f.blue.advanced().drainJournalThrough(input, DrainBudget.unlimited());
            assertEquals(1, second.entry(input).closures().size());
            assertTrue(second.quiescent());
            assertNotEquals(first.entry(input).closures().get(0).closureId(), second.entry(input).closures().get(0).closureId());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(0L, future.snapshot().longAt("/counter"));
            var histories = List.of(f.history(source), f.history(parent), f.history(future));
            assertTrue(f.blue.advanced().drainJournalThrough(input, DrainBudget.unlimited()).entries().isEmpty());
            assertEquals(histories, List.of(f.history(source), f.history(parent), f.history(future)));
            var other = foreign.start("source.yaml", "rcp2/source", Map.of());
            var foreignEntry = foreign.append(other, "rcp2/source", "tick", 100, "{}");
            assertThrows(IllegalArgumentException.class, () -> f.blue.advanced().drainJournalThrough(foreignEntry, DrainBudget.unlimited()));
            assertEquals(histories, List.of(f.history(source), f.history(parent), f.history(future)));
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(future).entry(later).disposition());
        }
    }
    @Test void aHistoricalTurnCannotBeConsumedByTheJournalCutoff() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String original = f.retain(source);
            var tick = f.append(source, "rcp2/source", "tick", 100, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, tick).entry(tick).disposition());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var attach = f.append(parent, "rcp2/parent", "attach", 200, "child: {blueId: " + original + "}");
            var attached = f.blue.advanced().drainJournalThrough(attach, DrainBudget.unlimited());
            assertEquals(EntryDisposition.APPLIED, attached.entry(attach).disposition());
            assertTrue(attached.managedEpochApplications().isEmpty());
            var selection = f.blue.advanced().auditNextProcessingSelection();
            assertEquals(blue.coordination.api.ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selection.kind());
            var histories = List.of(f.history(source), f.history(parent));
            var failure = assertThrows(blue.coordination.api.CoordinationException.class,
                    () -> f.blue.advanced().drainJournalThrough(attach, DrainBudget.unlimited()));
            assertEquals(blue.coordination.api.CoordinationErrorCode.PROCESSING_SELECTION_MISMATCH, failure.code());
            assertEquals(histories, List.of(f.history(source), f.history(parent)));
            var applied = f.blue.processing().drainManagedEpochApplication(
                    selection.managedEpochApplicationWork().orElseThrow().workIdentity());
            assertEquals(1, applied.managedEpochApplications().size());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(histories.get(0), f.history(source));
        }
    }

}
