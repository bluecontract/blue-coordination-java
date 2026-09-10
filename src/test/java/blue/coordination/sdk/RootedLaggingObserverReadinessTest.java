package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A prior READY snapshot does not advertise the independently newer source frontier. */
final class RootedLaggingObserverReadinessTest {
    @Test void sourceAdvancesTwiceWhileObserverRetainsItsExactOlderReadyView() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            // when
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of("child", f.retain(source)));
            var oldReady = parent.snapshot();
            var oldFrontier = f.blue.advanced().auditDocument(parent.id()).readyThrough();
            var parentHistory = f.history(parent);
            var first = f.append(source, "rcp2/source", "tick", 100, "{}");
            var second = f.append(source, "rcp2/source", "tick", 150, "{}");
            for (var entry : java.util.List.of(first, second)) {
                // then
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(entry).disposition());
                assertEquals(oldReady.blueId(), parent.snapshot().blueId());
                assertEquals(oldReady.epoch(), parent.snapshot().epoch());
                assertEquals(oldReady.exact().json(), parent.snapshot().exact().json());
                assertEquals(oldFrontier, f.blue.advanced().auditDocument(parent.id()).readyThrough());
                assertEquals(parentHistory, f.history(parent));
                assertEquals(first.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());
            }
            assertEquals(2L, source.snapshot().longAt("/counter"));
            assertEquals(0L, parent.snapshot().longAt("/seen"));
            assertTrue(oldFrontier.compareTo(f.blue.advanced().auditDocument(source.id()).readyThrough()) < 0);
            var sourceHead = source.snapshot().blueId();
            var sourceHistory = f.history(source);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(first.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());
            assertEquals(oldReady.blueId(), parent.snapshot().blueId());
            assertEquals(oldFrontier, f.blue.advanced().auditDocument(parent.id()).readyThrough());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(first).disposition());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(second.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(second).disposition());
            assertEquals(2L, parent.snapshot().longAt("/seen"));
            assertTrue(f.blue.processing().processNext(parent).quiescent());
            assertEquals(sourceHead, source.snapshot().blueId());
            assertEquals(sourceHistory, f.history(source));
            assertEquals(f.blue.advanced().auditDocument(source.id()).readyThrough(),
                    f.blue.advanced().auditDocument(parent.id()).readyThrough());
        }
    }
}
