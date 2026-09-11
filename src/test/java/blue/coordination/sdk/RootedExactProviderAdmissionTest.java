package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.SourceHistoryPrerequisite;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** An already authenticated provider value is not a request to re-author its source. */
final class RootedExactProviderAdmissionTest {
    @Test void sourceAdmissionPreservesTheProviderAuthoredIdentity() throws Exception {
        String providerId; String providerJson; String processingId;
        try (var preparation = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            String source = RootedSdkFixture.resource("source.yaml");
            var provider = preparation.values().providerContentYaml(source);
            providerId = provider.blueId(); providerJson = provider.json();
            processingId = preparation.values().yaml(source).blueId();
        }
        assertNotEquals(processingId, providerId, "The fixture must expose the two distinct supported ingress forms");
        try (var f = new RootedSdkFixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", java.util.Map.of());
            var otherIngress = f.start("source.yaml", "rcp2/source", java.util.Map.of());
            assertEquals(processingId, otherIngress.id().value());
            var otherHead = otherIngress.snapshot().blueId(); var otherHistory = f.history(otherIngress);
            f.exact.put(providerId, providerJson);
            var beforeParent = parent.snapshot().blueId(); var beforeHistory = f.history(parent);
            var entry = f.append(parent, "rcp2/parent", "attach", 20L, "child:\n  blueId: " + providerId);
            var stopped = f.blue.processing().processNext(parent).entry(entry);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition());
            var selected = f.blue.advanced().sourceHistoryPrerequisites(parent);
            assertEquals(1, selected.size());
            var admission = selected.get(0);
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, admission.kind());
            assertEquals(providerId, admission.authoredBlueId());
            assertEquals(providerId, admission.sourceDocumentId().value());
            assertTrue(f.blue.advanced().processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            var source = f.blue.documents().require(DocumentId.of(providerId));
            assertEquals(1, f.history(source).size());
            assertEquals(beforeParent, parent.snapshot().blueId()); assertEquals(beforeHistory, f.history(parent));
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(entry).disposition());
            assertEquals(1, f.history(source).size(), "Parent reuse cannot initialize the same source a second time");
            f.blue.processing().processNext(parent); // the normal post-attachment checkpoint
            var tick = f.append(source, "rcp2/source", "setCounter", 30L, "counterValue: 5");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(tick).disposition());
            for (int i = 0; i < 4; i++) {
                if (f.blue.processing().processNext(parent).quiescent()) break;
            }
            assertEquals("5", parent.snapshot().valueAt("/seen").copyNode().getValue().toString());
            assertEquals(otherHead, otherIngress.snapshot().blueId());
            assertEquals(otherHistory, f.history(otherIngress), "Different exact authored lineage must not be overwritten");
        }
    }
}
