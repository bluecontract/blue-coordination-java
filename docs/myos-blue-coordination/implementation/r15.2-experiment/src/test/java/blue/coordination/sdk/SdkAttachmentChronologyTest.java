package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase-1 semantic traces over real Coordination + Contracts, without PostgreSQL. */
final class SdkAttachmentChronologyTest {
    private static final long T0 = 2_100_000_000_000_000L;
    private static final String A_TIMELINE = "poc/chronology/a";
    private static final String B_TIMELINE = "poc/chronology/b";

    @Test
    void attachmentAtTenImportsFiveWithoutBackdatingTheObserver() {
        try (Fixture f = new Fixture()) {
            EntryHandle change = f.submit(B_TIMELINE, 5L, "setCounter", "amount: 5", null);
            f.drain();
            assertEquals("Unset", f.a.snapshot().textAt("/counterB"));
            List<RevisionTrace> sourceBefore = trace(f.b);
            EntryHandle observation = f.submit(A_TIMELINE, 7L, "observe", "{}", null);
            f.drain();
            assertEquals("Unset", f.a.snapshot().textAt("/observed"));

            EntryHandle attachment = f.attach(10L, observation);
            DrainResult attachmentOnly = f.blue.processing().drain(new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attachmentOnly.entry(attachment).disposition());
            assertTrue(attachmentOnly.managedEpochApplications().isEmpty());
            assertEquals("Unset", f.blue.advanced().auditDocument(f.a.id())
                    .valueAt("/counterB").copyNode().getValue());

            DrainResult importedWork = f.drain();

            assertFalse(importedWork.stats().documentStepOrder().contains(f.b.id()),
                    "completed imports must not perform source document work in B");
            assertEquals(5L, f.a.snapshot().longAt("/counterB"));
            assertEquals("Unset", f.a.snapshot().textAt("/observed"),
                    "the T7 observation cannot be rewritten by T10 catch-up");
            assertEquals(sourceBefore, trace(f.b), "import must not append or rewrite B history");
            List<DocumentRevision> history = f.a.history();
            int attachPosition = positionOf(history, attachment);
            int updatePosition = firstCounterFive(history);
            assertTrue(updatePosition > attachPosition,
                    "A changes only after its T10 attachment, never back at source T5");
            assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    history.get(updatePosition).kind());
            assertTrue(history.get(updatePosition).publicEvents().isEmpty(),
                    "importing B's old public event must not publish it again as A's event");
            assertEquals(change.blueId(), history.get(updatePosition)
                    .managedEpochReceipt().orElseThrow().sourceEntry().orElseThrow().blueId());
            ManagedEpochReceipt sourceReceipt = f.b.history().get(1)
                    .managedEpochReceipt().orElseThrow();
            ManagedEpochReceipt imported = history.get(updatePosition)
                    .managedEpochReceipt().orElseThrow();
            assertEquals(sourceReceipt.sourceOrder(), imported.sourceOrder());
            assertEquals(sourceReceipt.originalCauseIdentity(), imported.originalCauseIdentity());
        }
    }

    @Test
    void attachmentAtOneObservesFiveThroughTheAlreadyActiveRelationship() {
        try (Fixture f = new Fixture()) {
            EntryHandle attachment = f.attach(1L);
            f.drain();
            assertEquals("Unset", f.a.snapshot().textAt("/counterB"));
            int historyBeforeFive = f.a.history().size();

            EntryHandle change = f.submit(B_TIMELINE, 5L, "setCounter", "amount: 5", null);
            DrainResult live = f.drain();

            assertEquals(5L, f.a.snapshot().longAt("/counterB"));
            assertEquals(EntryDisposition.APPLIED, live.entry(change).disposition());
            assertEquals(historyBeforeFive, firstCounterFive(f.a.history()));
            assertTrue(positionOf(f.a.history(), attachment) < historyBeforeFive);
            assertTrue(live.managedEpochApplications().isEmpty(),
                    "active parent reaction belongs to the live invocation, not a later import");
            assertEquals(f.b.history().get(1).managedEpochReceipt().orElseThrow()
                            .commitCompanionIdentity(),
                    f.a.history().get(historyBeforeFive).managedEpochReceipt().orElseThrow()
                            .commitCompanionIdentity(),
                    "B and its active A parent must share one committing Contracts result");
            f.submit(A_TIMELINE, 7L, "observe", "{}", attachment);
            f.drain();
            assertEquals(5L, f.a.snapshot().longAt("/observed"));
        }
    }

    @Test
    void boundedCatchUpHasTheSameExactHistoryAsUninterruptedCatchUp() {
        assertEquals(runLateAttachment(false), runLateAttachment(true));
    }

    private static List<List<RevisionTrace>> runLateAttachment(boolean bounded) {
        try (Fixture f = new Fixture()) {
            f.submit(B_TIMELINE, 5L, "setCounter", "amount: 5", null);
            f.drain();
            f.attach(10L);
            if (bounded) {
                boolean finished = false;
                for (int attempt = 0; attempt < 8; attempt++) {
                    if (f.blue.processing().drain(new DrainBudget(1L, 1L)).quiescent()) {
                        finished = true;
                        break;
                    }
                }
                assertTrue(finished, "this finite trace must settle under bounded drains");
            } else {
                f.drain();
            }
            assertEquals(5L, f.a.snapshot().longAt("/counterB"));
            return List.of(trace(f.a), trace(f.b));
        }
    }

    private static List<RevisionTrace> trace(DocumentHandle document) {
        return document.history().stream().map(r -> new RevisionTrace(
                r.epoch(), r.kind(), r.before().map(ExactBlueValue::blueId).orElse(""),
                r.after().blueId(), r.processingGas(),
                r.sourceEntry().map(EntryHandle::blueId).orElse(""),
                r.publicEvents(), r.managedEpochReceipt()
                        .map(ManagedEpochReceipt::receiptIdentity).orElse(""))).toList();
    }

    private static int positionOf(List<DocumentRevision> history, EntryHandle entry) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).sourceEntry().map(EntryHandle::blueId)
                    .filter(entry.blueId()::equals).isPresent()) {
                return i;
            }
        }
        throw new AssertionError("Missing entry " + entry.blueId());
    }

    private static int firstCounterFive(List<DocumentRevision> history) {
        for (int i = 0; i < history.size(); i++) {
            Object value = history.get(i).after().scalarAt("/counterB");
            if (value instanceof Number n && n.longValue() == 5L) {
                return i;
            }
        }
        throw new AssertionError("Missing counterB=5 transition");
    }

    private static String resource(String name) {
        try (InputStream input = SdkAttachmentChronologyTest.class.getResourceAsStream(
                "/poc/chronology/" + name + ".yaml")) {
            if (input == null) throw new IllegalArgumentException("Missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record RevisionTrace(long epoch, DocumentRevision.Kind kind,
                                 String beforeBlueId, String afterBlueId, long gas, String sourceEntry,
                                 List<PublicEvent> publicEvents, String managedReceiptIdentity) {}

    private static final class Fixture implements AutoCloseable {
        private final BlueCoordination blue = BlueCoordination.builder()
                .contentDerivedDocumentIds().build();
        private final TimelineHandle aTimeline = blue.timelines().register(A_TIMELINE, "alice");
        private final TimelineHandle bTimeline = blue.timelines().register(B_TIMELINE, "alice");
        private final ExactBlueValue source = blue.values().yaml(resource("source"));
        private final DocumentHandle b = admit(resource("source"));
        private final DocumentHandle a = admit(resource("observer"));

        private DocumentHandle admit(String yaml) {
            return blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of(blue.values().yaml(yaml).blueId()), yaml)
                    .publicRoot().fromNow());
        }

        private EntryHandle attach(long time) {
            return attach(time, null);
        }

        private EntryHandle attach(long time, EntryHandle previous) {
            return submit(A_TIMELINE, time, "attach",
                    "child:\n  blueId: " + source.blueId(), previous);
        }

        private EntryHandle submit(String timeline, long time, String operation,
                                   String request, EntryHandle previous) {
            String predecessor = previous == null ? "" :
                    "prevEntry:\n  blueId: " + previous.blueId() + "\n";
            ExactBlueValue event = blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: %d
                    %sactor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      operation: %s
                      channel: ownerChannel
                      request:
                    %s
                    """.formatted(timeline, T0 + time, predecessor, operation, request.indent(4)));
            return blue.events().from(timeline.equals(A_TIMELINE) ? aTimeline : bTimeline)
                    .exact(event).submit();
        }

        private DrainResult drain() {
            DrainResult result = blue.processing().drain();
            assertTrue(result.quiescent(), result.toString());
            assertFalse(result.paused());
            return result;
        }

        @Override public void close() { blue.close(); }
    }
}
