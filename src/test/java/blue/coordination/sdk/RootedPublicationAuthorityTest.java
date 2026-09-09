package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class RootedPublicationAuthorityTest {
    @Test void terminalVerifierRejectsExtraMissingDuplicateOwnersAndAnotherCause() throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var s = fixture.start("source.yaml", "rcp2/source", Map.of());
            var p = fixture.start("parent.yaml", "rcp2/parent", Map.of("child", s.snapshot().blueId()));
            var first = fixture.append(s, "rcp2/source", "tick", 100, "{}");
            var second = fixture.append(s, "rcp2/source", "tick", 110, "{}");
            var result = fixture.control.precompute(p.id(), first.blueId());
            assertTrue(result.commits());
            var projection = result.rootedProjection();
            String key = projection.context().terminalKey(projection.deliveryBasisIdentity());
            String source = s.snapshot().blueId();
            String parent = p.snapshot().blueId();
            assertDoesNotThrow(() -> fixture.control.verifyTerminalCandidate(p.id(), first.blueId(), result, List.of(p.id()), key));
            assertThrows(IllegalArgumentException.class, () -> fixture.control.verifyTerminalCandidate(
                    p.id(), first.blueId(), result, List.of(p.id(), s.id()), key));
            assertThrows(IllegalArgumentException.class, () -> fixture.control.verifyTerminalCandidate(
                    p.id(), first.blueId(), result, List.of(s.id()), key));
            assertThrows(IllegalArgumentException.class, () -> fixture.control.verifyTerminalCandidate(
                    p.id(), first.blueId(), result, List.of(p.id(), p.id()), key));
            assertThrows(IllegalArgumentException.class, () -> fixture.control.verifyTerminalCandidate(
                    p.id(), first.blueId(), result, List.of(p.id()), "sha256:" + "0".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> fixture.control.verifyTerminalCandidate(
                    p.id(), second.blueId(), result, List.of(p.id()), key));
            assertEquals(source, s.snapshot().blueId());
            assertEquals(parent, p.snapshot().blueId());
            assertEquals(1, fixture.history(s).size());
            assertEquals(1, fixture.history(p).size());
        }
    }
}
