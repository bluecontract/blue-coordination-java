package blue.coordination.sdk;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ReadyRevisionPointAuditTest {
    @Test void pointReadMatchesPublicHistoryAndDoesNotAdvanceProcessing() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            for (int i = 1; i <= 5; i++) {
                // when
                var input = f.append(source, "rcp2/source", "tick", i * 100L, "{}");
                // then
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, input).entry(input).disposition());
            }
            var before = f.history(source);
            for (var revision : source.history()) {
                var actual = f.blue.advanced().auditReadyRevision(source.id(), revision.epoch()).orElseThrow();
                assertEquals(revision.epoch(), actual.epoch());
                assertEquals(revision.after().blueId(), actual.after().blueId());
                assertEquals(revision.processingGas(), actual.processingGas());
                assertEquals(revision.managedEpochReceipt().orElseThrow().receiptIdentity(), actual.managedEpochReceipt().orElseThrow().receiptIdentity());
            }
            assertTrue(f.blue.advanced().auditReadyRevision(source.id(), Long.MAX_VALUE).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> f.blue.advanced().auditReadyRevision(source.id(), -1));
            assertEquals(before, f.history(source));
        }
    }

    @Test void managedProgressDoesNotExposeARevisionBeyondTheReadyBoundary() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var original = f.retain(source);
            var input = f.append(source, "rcp2/source", "tick", 100, "{}");
            f.blue.processing().process(source, input);
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var attach = f.append(parent, "rcp2/parent", "attach", 200, "child: {blueId: " + original + "}");
            f.blue.advanced().drainJournalThrough(attach, DrainBudget.unlimited());
            // when
            long ready = parent.snapshot().epoch();
            // then
            assertTrue(f.blue.advanced().auditReadyRevision(parent.id(), ready).isPresent());
            assertTrue(f.blue.advanced().auditReadyRevision(parent.id(), ready + 1).isEmpty());
            assertEquals(parent.history().size(), ready + 1);
        }
    }
}
