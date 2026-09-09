package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physical first loading cannot become a bare source's semantic birth. */
final class RootedSourceDiscoveryTest {
    @Test void bareSourceKeepsHistoryBeforeTheLaterAttachment() throws IOException {
        run(false);
    }

    @Test void suspendingTheEarlierLoaderCannotDiscardTheSourcesEarlierInput() throws IOException {
        run(true);
    }

    private static void run(boolean suspendEarlier) throws IOException {
        String initialId;
        String providerJson;
        // Prepare exact source bytes outside the target realm: computing its ID
        // must not accidentally warm the provider whose suspension is tested.
        try (var preparation = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var source = preparation.values().yaml(RootedSdkFixture.resource("source.yaml"));
            initialId = source.blueId();
            providerJson = source.json();
        }
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var a = fixture.startYaml(RootedSdkFixture.resource("parent.yaml")
                    .replace("RCP2 Parent", "RCP2 Discovery A").replace("rcp2/parent", "rcp2/discovery-a"), "rcp2/discovery-a");
            var b = fixture.startYaml(RootedSdkFixture.resource("parent.yaml")
                    .replace("RCP2 Parent", "RCP2 Discovery B").replace("rcp2/parent", "rcp2/discovery-b"), "rcp2/discovery-b");
            fixture.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            fixture.appendReference(initialId, "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            var e10 = fixture.append(a, "rcp2/discovery-a", "attach", 10, "child:\n  blueId: " + initialId);
            var e20 = fixture.append(b, "rcp2/discovery-b", "attach", 20, "child:\n  blueId: " + initialId);
            var aHead = a.snapshot().blueId();
            if (suspendEarlier) {
                var blocked = blue.processing().processNext(a).entry(e10);
                assertEquals(EntryDisposition.NEEDS_RESOURCES, blocked.disposition());
                assertEquals(aHead, a.snapshot().blueId());
                assertEquals(1, fixture.history(a).size());
            }
            fixture.exact.put(initialId, providerJson);
            if (!suspendEarlier) assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(a).entry(e10).disposition());
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(b).entry(e20).disposition());
            if (suspendEarlier) assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(a).entry(e10).disposition());
            var caughtUp = blue.processing().processNext(b);
            String selected = blue.advanced().auditDocument(b.id()).current().canonicalBlueIdAt("/child");
            assertEquals(5L, ((Number) blue.values().retained(selected).orElseThrow().scalarAt("/counter")).longValue(),
                    "Bare discovery at 20 must include source input 15; physical first load at 10 or 20 is not a birth bound");
            assertEquals(5L, ((Number) blue.advanced().auditDocument(b.id()).valueAt("/seen").copyNode().getValue()).longValue());
            assertTrue(caughtUp.quiescent());
            var head = b.snapshot().blueId();
            var history = fixture.history(b);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(head, b.snapshot().blueId());
            assertEquals(history, fixture.history(b));
        }
    }
}
