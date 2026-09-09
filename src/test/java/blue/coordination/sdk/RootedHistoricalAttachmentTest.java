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
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var b = fixture.start("historical-b.yaml", "rcp2/b", Map.of());
            var a = fixture.start("historical-a.yaml", "rcp2/a", Map.of("peer", b.snapshot().blueId()));
            String a5 = null;
            for (int n = 1; n <= 10; n++) {
                var input = fixture.append(a, "rcp2/a", "tick", 100 + n, "{}");
                var result = blue.processing().process(a, input).entry(input);
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
            System.out.println("RCP-RUN-007 savedA5=" + a5 + " sourceA10=" + a10 + " finalHeads=" + heads);
        }
    }
}
