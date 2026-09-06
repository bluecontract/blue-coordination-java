package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positive requirements, not golden snapshots of the current implementation.
 * Run with pocSemanticTest. A red assertion is an unimplemented G1 requirement.
 */
final class PocHistoryChronologyTest {
    private static final long T0 = 2_100_000_000_000_000L;
    private static final String A_TIMELINE = "poc/chronology/a";
    private static final String B_TIMELINE = "poc/chronology/b";

    @ParameterizedTest(name = "attachment T{0}, observations T3/T7, late admission")
    @ValueSource(longs = {1L, 10L})
    void lateReconstructionPreservesTheHistoricalAttachmentTime(long attachmentTime) {
        try (Fixture f = new Fixture()) {
            EntryHandle source = f.submit(B_TIMELINE, 5L,
                    "setCounter", "amount: 5", null);
            String child = "child:\n" + resource("source").indent(2);
            EntryHandle beforeSourceChange;
            if (attachmentTime == 1L) {
                EntryHandle attachment = f.submit(A_TIMELINE, 1L, "attach", child, null);
                beforeSourceChange = f.submit(A_TIMELINE, 3L, "observe", "{}", attachment);
                f.submit(A_TIMELINE, 7L, "observe", "{}", beforeSourceChange);
            } else {
                beforeSourceChange = f.submit(A_TIMELINE, 3L, "observe", "{}", null);
                EntryHandle observation = f.submit(A_TIMELINE, 7L, "observe", "{}", beforeSourceChange);
                f.submit(A_TIMELINE, 10L, "attach", child, observation);
            }
            DrainResult settled = f.drain();
            assertEquals(EntryDisposition.APPLIED, settled.entry(source).disposition());
            assertEquals(4, settled.entries().size());
            assertEquals(3L, settled.entries().stream()
                    .filter(e -> e.disposition() == EntryDisposition.NO_MATCH).count(),
                    "A's original entries must already be settled with no recipient");
            List<String> sourceReceipts = f.sourceReceipts();

            DocumentHandle a = f.admit(resource("observer"), ActivationPolicy.importFullHistory());
            f.drain();

            assertAll(
                    () -> assertEquals(5L, numberOrUnset(a, "/counterB"),
                            "late admission must replay the attachment and its retained source history"),
                    () -> assertEquals(attachmentTime == 1L ? 5L : "Unset",
                            numberOrUnset(a, "/observed"),
                            "T7 observation depends on semantic attachment time, not admission wall time"),
                    () -> assertEquals("Unset", observationAt(a, beforeSourceChange),
                            "A@3 must not import B@5 early even when replay starts after T10"),
                    () -> assertEquals(sourceReceipts, f.sourceReceipts(),
                            "target replay must not change B's committed receipts"));
        }
    }

    @Test
    void initiallyEmbeddedHistoryInterleavesSourceTenParentTwentySourceThirty() {
        try (Fixture f = new Fixture()) {
            EntryHandle first = f.submit(B_TIMELINE, 10L, "setCounter", "amount: 1", null);
            EntryHandle read = f.submit(A_TIMELINE, 20L, "observe", "{}", null);
            EntryHandle last = f.submit(B_TIMELINE, 30L, "setCounter", "amount: 2", first);
            DrainResult settled = f.drain();
            assertEquals(EntryDisposition.APPLIED, settled.entry(first).disposition());
            assertEquals(EntryDisposition.NO_MATCH, settled.entry(read).disposition());
            assertEquals(EntryDisposition.APPLIED, settled.entry(last).disposition());
            List<String> sourceReceipts = f.sourceReceipts();
            String root = resource("observer") + "\nchild:\n" + resource("source").indent(2);

            DocumentHandle a = f.blue.documents().admitStaticProcessEmbedded(
                    root, ActivationPolicy.importFullHistory()).document("root");
            f.drain();

            assertAll(
                    () -> assertEquals(1L, numberOrUnset(a, "/observed"),
                            "A@20 must observe B@10, not Unset and not B@30"),
                    () -> assertEquals(1L, observationAt(a, read),
                            "the original T20 observation must occur exactly once and see B@10"),
                    () -> assertEquals(2L, numberOrUnset(a, "/counterB")),
                    () -> assertEquals(sourceReceipts, f.sourceReceipts()));
        }
    }

    @Test
    void oneSettledLiveCauseReplaysIndependentlyToR2ThenR3() {
        try (Fixture f = new Fixture()) {
            EntryHandle source = f.submit(B_TIMELINE, 5L, "setCounter", "amount: 5", null);
            assertEquals(EntryDisposition.APPLIED, f.drain().entry(source).disposition());
            assertEquals(5L, f.b.snapshot().longAt("/counter"));
            List<String> r1Receipts = f.sourceReceipts();

            DocumentHandle r2 = f.admit(resource("source")
                    .replace("name: Chronology source B", "name: Replay R2"),
                    ActivationPolicy.importFullHistory());
            f.drain();
            DocumentHandle r3 = f.admit(resource("source")
                    .replace("name: Chronology source B", "name: Replay R3"),
                    ActivationPolicy.importFullHistory());
            f.drain();

            assertNotEquals(r2.id(), r3.id(), "different authored Roots are distinct lineages");
            assertAll(
                    () -> assertEquals(5L, r2.snapshot().longAt("/counter"), "R2 must replay E"),
                    () -> assertEquals(5L, r3.snapshot().longAt("/counter"), "R3 must also replay E"),
                    () -> assertEquals(1L, applicationCount(r2, source), "R2 must apply E exactly once"),
                    () -> assertEquals(1L, applicationCount(r3, source), "R3 must apply E exactly once"),
                    () -> assertEquals(r1Receipts, f.sourceReceipts(), "R1 must receive no replay duplicate"));
        }
    }

    private static Object numberOrUnset(DocumentHandle document, String path) {
        Object value = document.snapshot().exact().scalarAt(path);
        return value instanceof Number n ? n.longValue() : value;
    }

    private static Object observationAt(DocumentHandle document, EntryHandle entry) {
        List<DocumentRevision> matches = document.history().stream()
                .filter(r -> r.sourceEntry().map(EntryHandle::blueId)
                        .filter(entry.blueId()::equals).isPresent()).toList();
        assertEquals(1, matches.size(), "one target replay of the original observation is required");
        Object value = matches.get(0).after().scalarAt("/observed");
        return value instanceof Number n ? n.longValue() : value;
    }

    private static long applicationCount(DocumentHandle document, EntryHandle entry) {
        return document.history().stream()
                .filter(r -> r.sourceEntry().map(EntryHandle::blueId)
                        .filter(entry.blueId()::equals).isPresent()).count();
    }

    private static String resource(String name) {
        try (InputStream input = PocHistoryChronologyTest.class.getResourceAsStream(
                "/poc/chronology/" + name + ".yaml")) {
            if (input == null) throw new IllegalArgumentException("Missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final BlueCoordination blue = BlueCoordination.builder()
                .contentDerivedDocumentIds().build();
        private final TimelineHandle aTimeline = blue.timelines().register(A_TIMELINE, "alice");
        private final TimelineHandle bTimeline = blue.timelines().register(B_TIMELINE, "alice");
        private final DocumentHandle b = admit(resource("source"), ActivationPolicy.fromNow());

        private DocumentHandle admit(String yaml, ActivationPolicy policy) {
            return blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of(blue.values().yaml(yaml).blueId()), yaml)
                    .publicRoot().activation(policy));
        }

        private List<String> sourceReceipts() {
            return blue.advanced().auditManagedEpochs(b.id()).stream()
                    .map(ManagedEpochReceipt::receiptIdentity).toList();
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
            assertTrue(result.quiescent(), result.diagnostic().toString());
            assertFalse(result.paused());
            return result;
        }

        @Override public void close() { blue.close(); }
    }
}
