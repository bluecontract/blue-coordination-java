package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Exact literal RUN-007, including five retained successors and no current-state substitution. */
final class RootedHistoricalAttachmentTest {
    @Test void attachingSavedA5ConsumesExactlyFiveSuccessorsAndClosesTheLiveCycle() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var b = fixture.start("historical-b.yaml", "rcp2/b", Map.of());
            var a = fixture.start("historical-a.yaml", "rcp2/a", Map.of("peer", b.snapshot().blueId()));
            String a5 = null;
            for (int n = 1; n <= 10; n++) {
                var input = fixture.append(a, "rcp2/a", "tick", 100 + n, "{}");
                var result = blue.processing().process(a, input).entry(input);
                // then
                assertEquals(EntryDisposition.APPLIED, result.disposition(), "A step " + n + " " + result.diagnostic());
                assertEquals(n, blue.advanced().auditDocument(a.id()).epoch());
                var receipt = blue.advanced().auditManagedEpoch(a.id(), n).orElseThrow();
                assertEquals(n, ((Number) receipt.afterDocument().scalarAt("/counter")).intValue());
                fixture.retain(a);
                if (n == 5) a5 = receipt.afterBlueId();
            }
            assertNotNull(a5);
            String a10 = a.snapshot().blueId();
            var originalReceipts = fixture.history(a);
            long originalEvents = blue.advanced().auditManagedEpochs(a.id()).stream()
                    .mapToLong(x -> x.emittedEvents().size()).sum();
            var attach = fixture.append(b, "rcp2/b", "attach", 200, "child:\n  blueId: " + a5);
            var attachment = blue.processing().process(b, attach).entry(attach);
            assertEquals(EntryDisposition.APPLIED, attachment.disposition(), attachment.diagnostic().toString());
            assertEquals(10L, blue.advanced().auditDocument(a.id()).epoch());
            var control = new RootedCalculationFixture(blue.advanced().rawEngine());
            for (int epoch = 6; epoch <= 10; epoch++) {
                System.out.println("BEFORE historical " + epoch + " view=" + control.selectedView(b.id())
                        .managedDocuments().stream().map(d -> d.documentId() + ":" + d.epoch() + ":" + d.blueId()).toList());
                var application = blue.processing().processNext(b);
                assertEquals(1, application.managedEpochApplications().size(), application.managedEpochApplicationAttempts().toString());
                var work = application.managedEpochApplicationAttempts().get(0).work();
                assertEquals(b.id(), work.consumerDocumentId());
                assertEquals(a.id(), work.sourceDocumentId());
                assertEquals(epoch, work.sourceEpoch());
                assertEquals(10L, blue.advanced().auditDocument(a.id()).epoch());
                assertEquals(originalReceipts, fixture.history(a).subList(0, originalReceipts.size()));
                assertEquals(epoch == 10, application.quiescent());
                if (epoch < 10) assertEquals(a10, a.snapshot().blueId());
                if (epoch == 7) {
                    var beforeRestart = control.selectedView(b.id());
                    var receiptPrefixes = List.of(fixture.history(a), fixture.history(b));
                    CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                    assertEquals(beforeRestart.closureIdentity(), control.selectedView(b.id()).closureIdentity());
                    assertEquals(receiptPrefixes, List.of(fixture.history(a), fixture.history(b)));
                }
            }
            assertEquals(10L, a.snapshot().longAt("/counter"));
            assertEquals(10L, b.snapshot().longAt("/seen"));
            for (int n = 0; n < 5; n++) assertEquals(n + 6, b.snapshot().longAt("/log/" + n));
            assertTrue(blue.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow().active());
            assertTrue(blue.advanced().auditManagedOccurrence(b.id(), "/child").orElseThrow().active());
            assertEquals(originalEvents, blue.advanced().auditManagedEpochs(a.id()).stream()
                    .mapToLong(x -> x.emittedEvents().size()).sum());
            var heads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var histories = List.of(fixture.history(a), fixture.history(b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(a), fixture.history(b)));
            assertTrue(blue.processing().processNext(b).quiescent());
            assertEquals(histories, List.of(fixture.history(a), fixture.history(b)));
            var live = fixture.append(a, "rcp2/a", "tick", 300, "{}");
            var liveResult = blue.processing().process(a, live).entry(live);
            assertEquals(EntryDisposition.APPLIED, liveResult.disposition(), liveResult.diagnostic().toString());
            assertEquals(11L, a.snapshot().longAt("/counter"));
            assertEquals(11L, b.snapshot().longAt("/seen"));
            assertEquals(11L, b.snapshot().longAt("/log/5"));
            assertEquals(originalEvents + 1, blue.advanced().auditManagedEpochs(a.id()).stream()
                    .mapToLong(x -> x.emittedEvents().size()).sum());
            assertEquals(originalReceipts, fixture.history(a).subList(0, originalReceipts.size()));
            var finalHeads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var finalHistories = List.of(fixture.history(a), fixture.history(b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(finalHeads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(finalHistories, List.of(fixture.history(a), fixture.history(b)));
            assertTrue(blue.processing().processNext(a).quiescent());
            var staleExact = fixture.appendReference(heads.get(0), "rcp2/a", "tick", 400, "{}", true);
            assertEquals(EntryDisposition.NO_MATCH, blue.processing().process(a, staleExact).entry(staleExact).disposition());
            var foreign = fixture.appendReference(heads.get(1), "rcp2/a", "tick", 410, "{}", false);
            assertEquals(EntryDisposition.NO_MATCH, blue.processing().process(a, foreign).entry(foreign).disposition());
            var unknown = fixture.appendReference(blue.values().yaml("name: unowned routing target").blueId(),
                    "rcp2/a", "tick", 420, "{}", false);
            assertEquals(EntryDisposition.NO_MATCH, blue.processing().process(a, unknown).entry(unknown).disposition());
            assertEquals(finalHeads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(finalHistories, List.of(fixture.history(a), fixture.history(b)));
            var retainedRepresentation = fixture.appendReference(heads.get(0), "rcp2/a", "tick", 430, "{}", false);
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(a, retainedRepresentation)
                    .entry(retainedRepresentation).disposition());
            assertEquals(12L, a.snapshot().longAt("/counter"));
            assertEquals(12L, b.snapshot().longAt("/seen"));
            assertEquals(originalEvents + 2, blue.advanced().auditManagedEpochs(a.id()).stream()
                    .mapToLong(x -> x.emittedEvents().size()).sum());
            assertEquals(originalReceipts, fixture.history(a).subList(0, originalReceipts.size()));
            System.out.println("RCP-RUN-007 savedA5=" + a5 + " sourceA10=" + a10 + " finalHeads=" + heads);
        }
    }
}
