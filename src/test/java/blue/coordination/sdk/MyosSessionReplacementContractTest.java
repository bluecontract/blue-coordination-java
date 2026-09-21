package blue.coordination.sdk;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary re-admission compatibility; HOST-01 replacement uses the separate native lifecycle API. */
final class MyosSessionReplacementContractTest {
    @Test void reAdmittingOriginalContentCannotReplaceOnlyOneAccountLocalSession() throws Exception {
        // given
        String initial;
        try (var input = getClass().getResourceAsStream("/rooted/source.yaml")) {
            initial = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var alice = BlueCoordination.inMemory(); var bob = BlueCoordination.inMemory()) {
            var timeline = alice.timelines().register("rcp2/source", "alice");
            var a = alice.documents().admitStaticProcessEmbedded(initial,
                    ActivationPolicy.importFullHistory()).document("root");
            var b = alice.documents().admitStaticProcessEmbedded(initial.replace("RCP2 Source", "Independent B"),
                    ActivationPolicy.importFullHistory()).document("root");
            var bobA = bob.documents().admitStaticProcessEmbedded(initial,
                    ActivationPolicy.importFullHistory()).document("root");
            assertEquals(a.id(), bobA.id());
            var entry = alice.operations().on(a).from(timeline).call("setCounter")
                    .through("owner").requestYaml("counterValue: 7").submit();
            assertEquals(EntryDisposition.APPLIED, alice.processing().processNext(a).entry(entry).disposition());
            assertEquals(7, a.snapshot().longAt("/counter"));
            String unchangedB = b.snapshot().blueId();
            // when
            var replacement = alice.documents().admitStaticProcessEmbedded(initial,
                    ActivationPolicy.importFullHistory()).document("root");
            // then
            assertEquals(a.id(), replacement.id());
            assertEquals(unchangedB, b.snapshot().blueId());
            assertEquals(0, bobA.snapshot().longAt("/counter"));
            assertEquals(7, replacement.snapshot().longAt("/counter"),
                    "Ordinary re-admission preserves the active instance; explicit replacement is a separate operation");
        }
    }
}
