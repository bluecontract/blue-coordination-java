package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Completed journal history must not multiply the cost of reconstructing one frozen root. */
final class RootedSelectionCostTest {
    @Test void aScanCapturesOneViewAndTheNextScanSeesNewCommittedProgress() throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            for (int n = 1; n <= 12; n++) {
                var entry = fixture.append(source, "rcp2/source", "tick", n, "{}");
                assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().process(source, entry)
                        .entry(entry).disposition());
            }
            var pending = fixture.append(source, "rcp2/source", "tick", 13, "{}");
            var control = CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
            long before = control.metricsSnapshot().counters().getOrDefault("routing.surfaceCompilations", 0L);
            var history = fixture.history(source);
            assertEquals(pending.blueId(), fixture.control.nextLiveInput(source.id()).orElseThrow());
            long compiled = control.metricsSnapshot().counters().getOrDefault("routing.surfaceCompilations", 0L)-before;
            assertEquals(1L, compiled, "One selection decision needs one frozen routing surface, independent of past entries");
            assertEquals(history, fixture.history(source));
            assertEquals(12L, source.snapshot().longAt("/counter"));
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(source).entry(pending).disposition());
            assertTrue(fixture.control.nextLiveInput(source.id()).isEmpty());
            var next = fixture.append(source, "rcp2/source", "tick", 14, "{}");
            assertEquals(next.blueId(), fixture.control.nextLiveInput(source.id()).orElseThrow());
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(source).entry(next).disposition());
            assertEquals(14L, source.snapshot().longAt("/counter"));
        }
    }
}
