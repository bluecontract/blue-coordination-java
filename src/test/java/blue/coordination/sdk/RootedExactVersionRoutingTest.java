package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class RootedExactVersionRoutingTest {
    @Test void savedAuthoredTargetRoutesOnlyWithANonExactPrecondition() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            String original = blue.advanced().auditDocument(source.id()).authoredInitialBlueId();
            // then
            assertNotEquals(original, source.snapshot().blueId());
            var exactOriginal = fixture.appendReference(original, "rcp2/source", "tick", 10, "{}", true);
            assertTrue(blue.processing().process(source, exactOriginal).entries().stream()
                    .allMatch(entry -> entry.closures().isEmpty() && entry.publicEvents().isEmpty()));
            assertEquals(0L, source.snapshot().longAt("/counter"));
            var accepted = fixture.appendReference(original, "rcp2/source", "tick", 20, "{}", false);
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(source).entry(accepted).disposition());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            var history = fixture.history(source);
            String unknown = blue.values().yaml("name: unrelated identity").blueId();
            var foreign = fixture.appendReference(unknown, "rcp2/source", "tick", 30, "{}", false);
            assertTrue(blue.processing().process(source, foreign).entries().stream()
                    .allMatch(entry -> entry.closures().isEmpty() && entry.publicEvents().isEmpty()));
            assertEquals(history, fixture.history(source));
            assertEquals(1L, source.snapshot().longAt("/counter"));
        }
    }

    @Test void exactTargetUsesTheSelectedViewAfterIndependentSourcePublication() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var s = fixture.start("source.yaml", "rcp2/source", Map.of());
            String s0 = s.snapshot().blueId();
            var p = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", s0));
            var input = fixture.append(s, "rcp2/source", "tick", 100, "{}", true);
            // then
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
