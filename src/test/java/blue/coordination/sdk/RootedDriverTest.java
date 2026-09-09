package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class RootedDriverTest {
    @Test void selectedRootConsumesOnlyItsNextNewInputAndKeepsIndependentSourceProgress() throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var s = fixture.start("source.yaml", "rcp2/source", Map.of());
            var p = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", s.snapshot().blueId()));
            var first = fixture.append(s, "rcp2/source", "tick", 100, "{}");
            var second = fixture.append(s, "rcp2/source", "tick", 110, "{}");
            var sourceHistory = fixture.history(s);
            var firstStep = blue.processing().processNext(p);
            assertEquals(EntryDisposition.APPLIED, firstStep.entry(first).disposition());
            assertFalse(firstStep.quiescent(), "The second input is still pending in this same root");
            assertTrue(firstStep.paused(), "One-step budget exhaustion must not be reported as a resource block");
            assertEquals(1L, p.snapshot().longAt("/seen"));
            assertEquals(sourceHistory, fixture.history(s));
            var secondStep = blue.processing().processNext(p);
            assertEquals(EntryDisposition.APPLIED, secondStep.entry(second).disposition());
            assertTrue(secondStep.quiescent(), "Only the independent source still has pending work");
            assertEquals(2L, p.snapshot().longAt("/seen"));
            assertEquals(0L, s.snapshot().longAt("/counter"));
            var receipts = fixture.history(p);
            var empty = blue.processing().processNext(p);
            assertTrue(empty.entries().isEmpty()); assertTrue(empty.quiescent());
            assertEquals(receipts, fixture.history(p));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertTrue(blue.processing().processNext(p).quiescent());
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(s).entry(first).disposition());
            assertEquals(1L, s.snapshot().longAt("/counter"));
            assertEquals(receipts, fixture.history(p));
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(s).entry(second).disposition());
            assertEquals(2L, s.snapshot().longAt("/counter"));
            assertEquals(receipts, fixture.history(p));
        }
    }
}
