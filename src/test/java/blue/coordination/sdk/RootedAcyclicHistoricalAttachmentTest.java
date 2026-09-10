package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;
import blue.coordination.api.ExactValue;
import blue.language.model.NodePathEditor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Acyclic retained history isolates the application boundary from the separately required cyclic join. */
final class RootedAcyclicHistoricalAttachmentTest {
    @Test void savedA5AppliesFiveExactSuccessorsWithoutPublishingTheSourceAgain() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var b = fixture.start("historical-b.yaml", "rcp2/b", Map.of());
            var a = fixture.start("historical-a.yaml", "rcp2/a", Map.of());
            String a5 = null;
            for (int n = 1; n <= 10; n++) {
                var input = fixture.append(a, "rcp2/a", "tick", 100 + n, "{}");
                // then
                assertEquals(EntryDisposition.APPLIED, blue.processing().process(a, input).entry(input).disposition());
                assertEquals(n, a.snapshot().longAt("/counter"));
                fixture.retain(a);
                if (n == 5) a5 = a.snapshot().blueId();
            }
            var sourceHistory = fixture.history(a);
            String a10 = a.snapshot().blueId();
            long sourceEvents = blue.advanced().auditManagedEpochs(a.id()).stream().mapToLong(r -> r.emittedEvents().size()).sum();
            String readyB = b.snapshot().blueId();
            var attach = fixture.append(b, "rcp2/b", "attach", 200, "child:\n  blueId: " + a5);
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(b, attach).entry(attach).disposition());
            assertEquals(a5, ExactValue.verified(NodePathEditor.getOrNull(blue.advanced().auditDocument(b.id()).current().copyNode(), "/child")).blueId());
            assertEquals(readyB, b.snapshot().blueId(), "Application reads retain the previous ready view during catch-up");
            for (int n = 6; n <= 10; n++) {
                var step = blue.processing().processNext(b);
                assertEquals(1, step.managedEpochApplications().size(), step.managedEpochApplicationAttempts().toString());
                assertEquals(n, step.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
                assertEquals(n, ((Number) blue.advanced().auditDocument(b.id()).current().copyNode().get("/seen")).longValue());
                assertEquals(n, ((Number) blue.advanced().auditDocument(b.id()).current().copyNode().get("/log/" + (n - 6))).longValue());
                assertEquals(a10, a.snapshot().blueId());
                assertEquals(sourceHistory, fixture.history(a));
                assertEquals(n == 10, step.quiescent());
                if (n < 10) assertEquals(readyB, b.snapshot().blueId());
            }
            assertTrue(blue.advanced().auditManagedOccurrence(b.id(), "/child").orElseThrow().active());
            assertEquals(sourceEvents, blue.advanced().auditManagedEpochs(a.id()).stream().mapToLong(r -> r.emittedEvents().size()).sum());
            var before = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var histories = List.of(fixture.history(a), fixture.history(b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(before, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(a), fixture.history(b)));
            assertTrue(blue.processing().processNext(b).quiescent());
        }
    }
}
