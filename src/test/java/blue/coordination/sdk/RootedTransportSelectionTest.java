package blue.coordination.sdk;

import blue.coordination.api.ProcessingSelection;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A transport-only result remains selectable without inventing a rooted operation. */
final class RootedTransportSelectionTest {
    @Test void unmatchedBroadcastCompletesOnceWithoutProcessingOrChangingHistory() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var before = source.snapshot().exact().json();
            var history = historyEvidence(f, source);
            var entry = broadcast(f, "rcp2/source", "removedOperation", 100L);

            assertEquals(ProcessingSelection.Kind.JOURNAL,
                    f.blue.advanced().auditNextProcessingSelection().kind());
            var drained = f.blue.advanced().drainJournalThrough(entry, DrainBudget.unlimited());
            assertEquals(List.of(entry), drained.entries().stream().map(EntryResult::entry).toList());
            assertEquals(EntryDisposition.NO_MATCH, drained.entry(entry).disposition());
            assertEquals("NONE", drained.entry(entry).diagnostic().code());
            assertTrue(drained.entry(entry).closures().isEmpty());
            assertTrue(drained.entry(entry).publicEvents().isEmpty());
            assertEquals(0L, drained.stats().gas());
            assertEquals(0L, drained.stats().committedTransitions());
            assertTrue(drained.quiescent());
            assertEquals(before, source.snapshot().exact().json());
            assertEquals(history, historyEvidence(f, source));
            assertEquals(ProcessingSelection.Kind.NONE,
                    f.blue.advanced().auditNextProcessingSelection().kind());
            assertTrue(f.blue.processing().drain().entries().isEmpty());
        }
    }

    @Test void transportCompletionHonorsCutoffAndDoesNotRepeatAfterRestart() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var before = source.snapshot().exact().json();
            var history = historyEvidence(f, source);
            var first = broadcast(f, "rcp2/source", "absent", 100L);
            var later = broadcast(f, "rcp2/source", "absent", 200L);

            var drained = f.blue.advanced().drainJournalThrough(first, new DrainBudget(1, 1));
            assertEquals(List.of(first), drained.entries().stream().map(EntryResult::entry).toList());
            assertEquals(EntryDisposition.NO_MATCH, drained.entry(first).disposition());
            assertEquals(0L, drained.stats().gas());
            assertTrue(f.blue.advanced().drainJournalThrough(first, DrainBudget.unlimited()).entries().isEmpty());
            assertEquals(ProcessingSelection.Kind.JOURNAL, f.blue.advanced().auditNextProcessingSelection().kind());

            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            var completed = f.blue.processing().drainJournal(new DrainBudget(1, 1));
            assertEquals(List.of(later), completed.entries().stream().map(EntryResult::entry).toList());
            assertEquals(EntryDisposition.NO_MATCH, completed.entry(later).disposition());
            assertEquals(0L, completed.stats().gas());
            assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
            assertEquals(before, source.snapshot().exact().json());
            assertEquals(history, historyEvidence(f, source));
        }
    }

    @Test void aLateUnmatchedImportBehindTheTransportFrontierIsStillReportedOnce() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var history = historyEvidence(f, source);
            var later = broadcast(f, "rcp2/source", "absent", 200L);
            assertEquals(EntryDisposition.NO_MATCH,
                    f.blue.advanced().drainJournalThrough(later, DrainBudget.unlimited()).entry(later).disposition());
            f.timelines.put("rcp2/unsubscribed", f.blue.timelines().register("rcp2/unsubscribed", "alice"));
            var imported = broadcast(f, "rcp2/unsubscribed", "absent", 100L);

            assertEquals(ProcessingSelection.Kind.JOURNAL, f.blue.advanced().auditNextProcessingSelection().kind());
            var drained = f.blue.advanced().drainJournalThrough(imported, DrainBudget.unlimited());
            assertEquals(List.of(imported), drained.entries().stream().map(EntryResult::entry).toList());
            assertEquals(EntryDisposition.NO_MATCH, drained.entry(imported).disposition());
            assertEquals(0L, drained.stats().gas());
            assertEquals(history, historyEvidence(f, source));
            assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
        }
    }

    @Test void unavailableHistoricalWorkCannotBeBypassedByUnmatchedTransport() throws Exception {
        try (var f = new RootedSdkFixture()) {
            String sourceYaml = RootedSdkFixture.resource("source.yaml");
            String authoredSource = f.blue.values().yaml(sourceYaml).blueId();
            var source = f.startYaml(sourceYaml, "rcp2/source");
            var tick = f.append(source, "rcp2/source", "tick", 100L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(tick).disposition());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var attachment = f.append(parent, "rcp2/parent", "attach", 200L,
                    "child: {blueId: " + authoredSource + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(attachment).disposition());
            assertEquals(1, f.blue.processing().processNext(parent).managedEpochApplications().size());
            var work = f.control.registeredOwnedHistory(parent.id());
            assertEquals(1L, work.sourceEpoch());
            f.control.deferRegisteredOwnedHistory(work);
            var unmatched = broadcast(f, "rcp2/parent", "absent", 300L);
            var histories = List.of(historyEvidence(f, source), historyEvidence(f, parent));
            var heads = List.of(source.snapshot().exact().json(), parent.snapshot().exact().json());

            for (boolean restart : List.of(false, true)) {
                if (restart) CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
                var failure = assertThrows(CoordinationException.class,
                        () -> f.blue.advanced().drainJournalThrough(unmatched, DrainBudget.unlimited()));
                assertEquals(CoordinationErrorCode.PROCESSING_SELECTION_MISMATCH, failure.code());
                var waiting = f.blue.processing().drain();
                assertTrue(waiting.entries().isEmpty());
                assertFalse(waiting.quiescent());
                assertEquals(0L, waiting.stats().gas());
                assertEquals(histories, List.of(historyEvidence(f, source), historyEvidence(f, parent)));
                assertEquals(heads, List.of(source.snapshot().exact().json(), parent.snapshot().exact().json()));
            }
        }
    }

    /** SDK receipt wrappers intentionally have no value equals; compare their complete evidence. */
    private static List<List<Object>> historyEvidence(RootedSdkFixture f, DocumentHandle document) {
        return f.blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> List.<Object>of(
                receipt.receiptIdentity(), receipt.documentId(), receipt.epoch(), receipt.kind(),
                receipt.beforeBlueId(), receipt.afterBlueId(), receipt.afterDocument().json(),
                receipt.originalCauseIdentity(), receipt.sourceEntry().map(entry -> List.of(
                        entry.exact().json(), entry.globalSequence(), entry.timelineSequence())),
                receipt.sourceOrder(), receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(),
                receipt.emittedEvents().stream().map(event -> List.of(event.managedEventIdentity(), event.ordinal(),
                        event.eventOccurrenceOrdinal(), event.sourceDocumentId(), event.eventOccurrenceIdentity(),
                        event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList(),
                receipt.processingGas())).toList();
    }

    private static EntryHandle broadcast(RootedSdkFixture f, String timeline, String operation, long timestamp) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                message:
                  type: Coordination/Operation Request
                  operation: %s
                  channel: owner
                  request: {}
                """.formatted(timeline, timestamp, operation);
        String previous = f.previousEntries.get(timeline);
        if (previous != null) yaml += "\nprevEntry:\n  blueId: " + previous + "\n";
        var entry = f.blue.events().from(f.timelines.get(timeline)).exact(f.blue.values().yaml(yaml)).submit();
        f.previousEntries.put(timeline, entry.blueId());
        return entry;
    }
}
