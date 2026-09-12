package blue.coordination.sdk;

import blue.coordination.api.ProcessingSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded Journal authority must not need an unrelated future LIVE input to finish retained work. */
final class RootedJournalCutoffFairnessTest {
    @Test
    void futureSubmittedLiveDoesNotStarveHistoryBeforeTheOriginalCutoff() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var scenario = firstHistoricalApplication(f, true);
            var sourcePrefix = f.history(scenario.source());
            var futurePrefix = f.history(scenario.futureRoot());
            assertEquals(ProcessingSelection.Kind.JOURNAL, f.blue.advanced().auditNextProcessingSelection().kind());

            // when
            var terminal = finishThrough(f, scenario);

            // then
            assertTrue(terminal.quiescent(), "Completion must be reported for the original cutoff");
            assertEquals(2L, scenario.parent().snapshot().longAt("/seen"));
            assertEquals(1L, scenario.parent().snapshot().longAt("/log/0"));
            assertEquals(2L, scenario.parent().snapshot().longAt("/log/1"));
            assertEquals(sourcePrefix, f.history(scenario.source()), "Import reuses the independently committed source");
            assertEquals(futurePrefix, f.history(scenario.futureRoot()), "A future submit is not execution authority");
            assertEquals(0L, scenario.futureRoot().snapshot().longAt("/counter"));

            // A separate explicit invocation can now authorize the future input.
            var futureResult = f.blue.advanced().drainJournalThrough(scenario.futureEntry(), new DrainBudget(1, 1));
            assertEquals(EntryDisposition.APPLIED, futureResult.entry(scenario.futureEntry()).disposition());
            assertEquals(1L, scenario.futureRoot().snapshot().longAt("/counter"));
            assertEquals(2L, scenario.parent().snapshot().longAt("/seen"));
        }
    }

    @Test
    void noFutureLiveControlFinishesHistoryThenAcknowledgesCutoffWithoutAnotherCommit() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var scenario = firstHistoricalApplication(f, false);
            assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION,
                    f.blue.advanced().auditNextProcessingSelection().kind());

            // when
            var terminal = finishThrough(f, scenario);

            // then
            assertTrue(terminal.quiescent());
            assertEquals(0L, terminal.stats().committedTransitions(), "Transport completion need not publish an epoch");
            assertEquals(2L, scenario.parent().snapshot().longAt("/seen"));
            assertEquals(1L, scenario.parent().snapshot().longAt("/log/0"));
            assertEquals(2L, scenario.parent().snapshot().longAt("/log/1"));
        }
    }

    @Test
    void terminalNoMatchCanCompleteTheCutoffWithoutCommittingAnEpoch() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var a = f.start("historical-a.yaml", "rcp2/a", Map.of());
            String a0 = a.snapshot().blueId();
            var tick = f.append(a, "rcp2/a", "tick", 100, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(a, tick).entry(tick).disposition());
            var prefix = f.history(a);
            var stale = f.appendReference(a0, "rcp2/a", "tick", 200, "{}", true);

            // when
            var result = f.blue.advanced().drainJournalThrough(stale, new DrainBudget(1, 1));

            // then
            assertEquals(EntryDisposition.NO_MATCH, result.entry(stale).disposition());
            assertEquals(0L, result.stats().committedTransitions());
            assertTrue(result.quiescent(), result.diagnostic().toString());
            assertEquals(prefix, f.history(a));
            assertEquals(1L, a.snapshot().longAt("/counter"));
        }
    }

    private static Scenario firstHistoricalApplication(RootedSdkFixture f, boolean submitFuture) throws Exception {
        var a = f.start("historical-a.yaml", "rcp2/a", Map.of());
        String a0 = a.snapshot().blueId();
        var b = f.start("historical-b.yaml", "rcp2/b", Map.of());
        var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                .replace("RCP2 Historical A", "Independent future C")
                .replace("rcp2/a", "rcp2/c"), "rcp2/c");
        for (int n = 1; n <= 2; n++) {
            var tick = f.append(a, "rcp2/a", "tick", 100 + n, "{}");
            var processed = f.blue.processing().process(a, tick);
            assertEquals(EntryDisposition.APPLIED, processed.entry(tick).disposition());
            var receipt = f.blue.advanced().auditManagedEpoch(a.id(), n).orElseThrow();
            assertEquals((long) n, ((Number) receipt.afterDocument().scalarAt("/counter")).longValue());
            assertEquals(1, receipt.emittedEvents().size());
            assertEquals("RCP2/Tick", receipt.emittedEvents().get(0).exactEvent().scalarAt("/kind"));
            assertEquals(a.id(), receipt.emittedEvents().get(0).sourceDocumentId());
            f.retain(a);
        }
        var attach = f.append(b, "rcp2/b", "attach", 200, "child:\n  blueId: " + a0);
        var attached = f.blue.advanced().drainJournalThrough(attach, new DrainBudget(1, 1));
        assertEquals(EntryDisposition.APPLIED, attached.entry(attach).disposition());
        assertEquals(0L, b.snapshot().longAt("/seen"));
        EntryHandle future = submitFuture ? f.append(c, "rcp2/c", "tick", 300, "{}") : null;

        // The first real historical turn resets fairness to the LIVE lane. A2 is still due.
        var selected = f.blue.advanced().auditNextProcessingSelection();
        assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selected.kind());
        var work = selected.managedEpochApplicationWork().orElseThrow();
        assertEquals(a.id(), work.sourceDocumentId());
        assertEquals(b.id(), work.consumerDocumentId());
        assertEquals(1L, work.sourceEpoch());
        var first = f.blue.processing().drainManagedEpochApplication(work.workIdentity());
        assertFalse(first.blocked(), first.diagnostic().toString());
        assertEquals(1, first.managedEpochApplications().size());
        // The public snapshot intentionally stays at the previous READY view until
        // the full import completes. Inspect the actual committed prefix, as the
        // maintained RootedSlicedSelectionTest does for its first historical step.
        assertEquals(1L, committed(f, b, "/seen"));
        assertEquals(1L, committed(f, b, "/log/0"));
        assertEquals(0L, b.snapshot().longAt("/seen"));
        assertEquals(2L, a.snapshot().longAt("/counter"));
        assertEquals(0L, c.snapshot().longAt("/counter"));
        return new Scenario(a, b, c, attach, future);
    }

    private static DrainResult finishThrough(RootedSdkFixture f, Scenario scenario) {
        List<String> observed = new ArrayList<>();
        for (int turn = 0; turn < 8; turn++) {
            var selected = f.blue.advanced().auditNextProcessingSelection();
            boolean journal = selected.kind() == ProcessingSelection.Kind.JOURNAL;
            DrainResult result;
            if (journal) {
                result = f.blue.advanced().drainJournalThrough(scenario.attachment(), new DrainBudget(1, 1));
            } else {
                assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selected.kind(), observed.toString());
                var work = selected.managedEpochApplicationWork().orElseThrow();
                assertEquals(scenario.parent().id(), work.consumerDocumentId(), "No unrelated retained work is needed");
                assertEquals(scenario.source().id(), work.sourceDocumentId());
                result = f.blue.processing().drainManagedEpochApplication(work.workIdentity());
            }
            observed.add(selected.kind() + " committed=" + result.stats().committedTransitions()
                    + " quiescent=" + result.quiescent() + " paused=" + result.paused()
                    + " committedSeen=" + committed(f, scenario.parent(), "/seen")
                    + " readySeen=" + scenario.parent().snapshot().longAt("/seen"));
            assertFalse(result.blocked(), observed.toString());
            assertEquals(0L, scenario.futureRoot().snapshot().longAt("/counter"), observed.toString());
            for (var entry : result.entries()) {
                long time = f.blue.advanced().auditTimelineEntry(entry.entry().blueId()).orElseThrow().timestampMicros();
                assertTrue(time <= 200L, "The T200 authority cannot consume T300: " + observed);
            }
            // Only the owner's bounded Journal receipt can close its inclusive scope.
            if (journal && result.quiescent()) return result;
        }
        fail("Authorized history must settle without consuming the unrelated future input: " + observed);
        throw new AssertionError("unreachable");
    }

    private static long committed(RootedSdkFixture f, DocumentHandle root, String path) {
        return ((Number) f.blue.advanced().auditDocument(root.id()).current().copyNode().get(path)).longValue();
    }

    private record Scenario(DocumentHandle source, DocumentHandle parent, DocumentHandle futureRoot,
                            EntryHandle attachment, EntryHandle futureEntry) { }
}
