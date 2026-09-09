package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physical first loading cannot become a bare source's semantic birth. */
final class RootedSourceDiscoveryTest {
    @Test void missingHistoryRemainsAWaitUntilCommittedEvidenceIsSupplied() throws IOException {
        String sourceYaml = RootedSdkFixture.resource("source.yaml");
        String initialId;
        String providerJson;
        try (var preparation = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var source = preparation.values().yaml(sourceYaml);
            initialId = source.blueId(); providerJson = source.json();
        }
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            fixture.timelines.put("rcp2/source", blue.timelines().register("rcp2/source", "alice"));
            var source15 = fixture.appendReference(initialId, "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            var attachment = fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + initialId);
            var before = parent.snapshot().blueId();
            assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(parent).entry(attachment).disposition());
            fixture.exact.put(initialId, "name: wrong supplied source\ncounter: 999\n");
            var wrongIdentity = assertThrows(CoordinationException.class,
                    () -> blue.processing().processNext(parent));
            assertEquals(CoordinationErrorCode.FROZEN_PROCESSING_FAILED, wrongIdentity.code());
            var identityCause = assertInstanceOf(CoordinationException.class, wrongIdentity.getCause());
            assertEquals(CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY, identityCause.code());
            assertEquals(initialId, identityCause.details().get("blueId"));
            assertEquals(before, parent.snapshot().blueId());
            assertEquals(1, fixture.history(parent).size());
            fixture.exact.put(initialId, providerJson);
            var bodyOnly = blue.processing().processNext(parent);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, bodyOnly.entry(attachment).disposition());
            assertFalse(bodyOnly.quiescent());
            assertEquals(before, parent.snapshot().blueId());
            assertEquals(1, fixture.history(parent).size());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(EntryDisposition.NEEDS_RESOURCES, blue.processing().processNext(parent).entry(attachment).disposition());
            assertEquals(before, parent.snapshot().blueId());

            // Supply history through independent, real source admission and
            // processing. Exact source bytes alone must never forge these receipts.
            var source = fixture.startYaml(sourceYaml, "rcp2/source");
            assertEquals(initialId, source.id().value());
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(source).entry(source15).disposition());
            assertEquals("FULL_HISTORY", ((Map<?, ?>) fixture.control.historyBasis(source.id()).get("admission")).get("mode"));
            var sourceHistory = fixture.history(source);
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(parent).entry(attachment).disposition());
            assertEquals(1, blue.processing().processNext(parent).managedEpochApplications().size(), "Authored -1 to initialized zero");
            var completed = blue.processing().processNext(parent);
            assertEquals(1, completed.managedEpochApplications().size(), "The immutable source15 revision");
            assertTrue(completed.quiescent());
            assertEquals(5L, parent.snapshot().longAt("/seen"));
            assertEquals(sourceHistory, fixture.history(source));
            var head = parent.snapshot().blueId();
            var history = fixture.history(parent);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(head, parent.snapshot().blueId());
            assertEquals(history, fixture.history(parent));
            assertEquals(sourceHistory, fixture.history(source));
        }
    }

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
