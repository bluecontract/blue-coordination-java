package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Completed journal history must not multiply the cost of reconstructing one frozen root. */
final class RootedSelectionCostTest {
    @Test void aScanCapturesOneViewAndTheNextScanSeesNewCommittedProgress() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            for (int n = 1; n <= 12; n++) {
                var entry = fixture.append(source, "rcp2/source", "tick", n, "{}");
                // then
                assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().process(source, entry)
                        .entry(entry).disposition());
            }
            var pending = fixture.append(source, "rcp2/source", "tick", 13, "{}");
            var control = CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
            long before = control.metricsSnapshot().counters().getOrDefault("routing.surfaceCompilations", 0L);
            var history = fixture.history(source);
            assertEquals(pending.blueId(), fixture.control.nextLiveInput(source.id()).orElseThrow());
            long compiled = control.metricsSnapshot().counters().getOrDefault("routing.surfaceCompilations", 0L)-before;
            assertTrue(compiled <= 1L, "At most one frozen routing surface; an unchanged captured view may already be retained");
            long captures = control.metricsSnapshot().counters().getOrDefault("rooted.observation.captures", 0L);
            for (int i = 0; i < 8; i++)
                assertEquals(pending.blueId(), fixture.control.nextLiveInput(source.id()).orElseThrow());
            assertTrue(control.metricsSnapshot().counters().getOrDefault("rooted.observation.captureReuses", 0L) > 0);
            assertEquals(history, fixture.history(source));
            assertEquals(12L, source.snapshot().longAt("/counter"));
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(source).entry(pending).disposition());
            assertTrue(fixture.control.nextLiveInput(source.id()).isEmpty());
            var next = fixture.append(source, "rcp2/source", "tick", 14, "{}");
            assertEquals(next.blueId(), fixture.control.nextLiveInput(source.id()).orElseThrow());
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().processNext(source).entry(next).disposition());
            assertEquals(14L, source.snapshot().longAt("/counter"));
            assertTrue(control.metricsSnapshot().counters().getOrDefault("rooted.observation.captures", 0L) > captures,
                    "Publishing a new epoch must invalidate the captured state");
        }
    }
}
