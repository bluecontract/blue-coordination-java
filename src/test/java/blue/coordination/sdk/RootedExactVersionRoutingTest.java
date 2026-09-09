package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class RootedExactVersionRoutingTest {
    @Test void exactTargetUsesTheSelectedViewAfterIndependentSourcePublication() throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var s = fixture.start("source.yaml", "rcp2/source", Map.of());
            String s0 = s.snapshot().blueId();
            var p = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", s0));
            var input = fixture.append(s, "rcp2/source", "tick", 100, "{}", true);
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(s, input).entry(input).disposition());
            String s1 = s.snapshot().blueId();
            var sourceHistory = fixture.history(s);
            var result = blue.processing().processNext(p);
            assertEquals(1, result.entries().size(), "P's selected S0 still matches the exact target");
            assertEquals(EntryDisposition.APPLIED, result.entry(input).disposition());
            assertEquals(1L, p.snapshot().longAt("/seen"));
            assertEquals(s1, s.snapshot().blueId());
            assertEquals(sourceHistory, fixture.history(s));
            assertTrue(blue.processing().processNext(s).entries().isEmpty(), "The original S0 request must not run again at S1");
        }
    }
}
