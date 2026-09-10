package blue.coordination.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-API regression for the exact reported original-reference cycle. */
final class SdkOriginalReferenceReattachmentTest {
    @Test
    void inlinePeerCanBeReplacedWithItsSavedOriginalAfterTheCycleForms() throws IOException {
        // given
        var builder = LegacyContracts10TestProfile.builder();
        // when
        var result = runOriginalReferences(builder, ActivationPolicy.fromNow());
        // then
        assertEquals(EntryDisposition.APPLIED, result.disposition());
    }

    @Test
    void rootedSavedOriginalReferencesRetainTheirFullHistoryBasis() throws IOException {
        // given
        var builder = BlueCoordination.builder();
        // when
        var result = runOriginalReferences(builder, ActivationPolicy.importFullHistory());
        // then
        assertEquals(EntryDisposition.APPLIED, result.disposition());
    }

    private static EntryResult runOriginalReferences(BlueCoordination.Builder builder, ActivationPolicy activation) throws IOException {
        // given
        try (BlueCoordination coordination = builder.contentDerivedDocumentIds().build()) {
            String sourceA = fixture("user-inline-cycle-a.yaml");
            String sourceB = fixture("cycle-b.yaml");
            String originalA = coordination.values().yaml(sourceA).blueId();
            String originalB = coordination.values().yaml(sourceB).blueId();
            assertEquals("H5PqsgVoN3ZL1Sg3b9wMfMK24R6ihV8n4t7zRFiivDbT", originalA);
            assertEquals("ARLSEvSXoDQbAfXNuKxj5d1cLh48caJbE74wZyJ6UYgB", originalB);
            TimelineHandle owner = coordination.timelines().register("tutorial/cycle/alice", "alice");
            DocumentHandle b = coordination.documents().admitStaticProcessEmbedded(sourceB, activation).publicRoots().get(0);
            DocumentHandle a = coordination.documents().admitStaticProcessEmbedded(sourceA, activation).publicRoots().get(0);
            coordination.processing().drain();
            assertEquals(originalA, a.id().value());
            assertEquals(originalB, b.id().value());
            assertTrue(operation(coordination, b, owner, "connectA", "a:\n  blueId: " + originalA).applied());
            coordination.processing().drain();
            List<PublicEvent> beforeA = events(a);
            List<PublicEvent> beforeB = events(b);
            assertTrue(beforeA.isEmpty());
            assertTrue(beforeB.isEmpty());

            // when
            EntryResult reattached = operation(coordination, a, owner, "attachB", "b:\n  blueId: " + originalB);
            DrainResult catchUp = coordination.processing().drain();

            // then
            assertEquals(EntryDisposition.APPLIED, reattached.disposition(), reattached.toString());
            assertTrue(catchUp.quiescent());
            assertTrue(a.snapshot().ready());
            assertTrue(b.snapshot().ready());
            assertTrue(a.exact().cyclicMember());
            assertTrue(b.exact().cyclicMember());
            assertEquals(beforeA, events(a));
            assertEquals(beforeB, events(b));
            var aPeer = coordination.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow();
            var bPeer = coordination.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow();
            assertEquals(b.id(), aPeer.targetDocumentId());
            assertEquals(a.id(), bPeer.targetDocumentId());
            assertTrue(aPeer.active());
            assertTrue(bPeer.active());
            EntryResult finite = operation(coordination, a, owner, "startFinite", "{}");
            assertTrue(finite.applied());
            assertTrue(coordination.processing().drain().quiescent());
            assertEquals("done", a.snapshot().textAt("/phase"));
            assertEquals("relayed", b.snapshot().textAt("/phase"));
            assertEquals(1, events(a).size());
            assertEquals(1, events(b).size());
            assertFalse(finite.publicEvents().isEmpty());
            return finite;
        }
    }

    private static List<PublicEvent> events(DocumentHandle document) {
        return document.history().stream().flatMap(revision -> revision.publicEvents().stream()).toList();
    }

    private static EntryResult operation(BlueCoordination coordination, DocumentHandle document,
            TimelineHandle owner, String operation, String request) {
        return coordination.operations().on(document).from(owner).call(operation)
                .through("ownerChannel").requestYaml(request).execute();
    }

    private static String fixture(String name) throws IOException {
        try (var stream = SdkOriginalReferenceReattachmentTest.class.getResourceAsStream(
                "/public-api-original-reference/" + name)) {
            if (stream == null) throw new IOException("Missing exact original fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
