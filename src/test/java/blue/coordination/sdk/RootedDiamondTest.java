package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class RootedDiamondTest {
    @Test void canonicalNoncommutativeDiamondIsIndependentOfCacheEnumeration() throws IOException {
        assertEquals(run(false), run(true));
    }

    private static Outcome run(boolean reversed) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var d = fixture.start("source.yaml", "rcp2/source", Map.of());
            var b = fixture.start("diamond-b.yaml", "rcp2/diamond-B", Map.of("child", d.snapshot().blueId()));
            var c = fixture.start("diamond-c.yaml", "rcp2/diamond-C", Map.of("child", d.snapshot().blueId()));
            var r = fixture.start("diamond-root.yaml", "rcp2/diamond-root", Map.of("b", b.snapshot().blueId(), "c", c.snapshot().blueId()));
            var before = List.of(d.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId());
            var histories = List.of(fixture.history(d), fixture.history(b), fixture.history(c));
            if (reversed) fixture.control.warmSelectedView(r.id(), true);
            var entry = fixture.append(d, "rcp2/source", "tick", 100, "{}");
            var result = blue.processing().process(r, entry).entry(entry);
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertEquals(List.of(r.id()), result.closures().stream().flatMap(x -> x.changes().stream()).map(DocumentChange::documentId).toList());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(1L, ((Number) fixture.selected(r, "/b").scalarAt("/seen")).longValue());
            assertEquals(1L, ((Number) fixture.selected(r, "/c").scalarAt("/seen")).longValue());
            List<String> expected = b.id().value().compareTo(c.id().value()) < 0 ? List.of("B", "C") : List.of("C", "B");
            assertEquals(expected.get(0), r.snapshot().textAt("/log/0"));
            assertEquals(expected.get(1), r.snapshot().textAt("/log/1"));
            assertEquals(before, List.of(d.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(d), fixture.history(b), fixture.history(c)));
            String head = r.snapshot().blueId();
            var receipts = fixture.history(r);
            String terminal = result.closures().get(0).closureId();
            var actual = blue.advanced().closureExecution(terminal).orElseThrow();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(head, r.snapshot().blueId());
            assertEquals(receipts, fixture.history(r));
            assertEquals(histories, List.of(fixture.history(d), fixture.history(b), fixture.history(c)));
            return new Outcome(head, receipts, actual.totalGas(), actual.gasTraceIdentity());
        }
    }
    private record Outcome(String head, List<String> receipts, long gas, String trace) { }
}
