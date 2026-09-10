package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** A declared birth and a bare source occurrence have distinct ownership obligations. */
final class RootedMixedDraftExpansionTest {
    @ParameterizedTest
    @ValueSource(strings = {"singlePatchExtra", "sequentialExtra"})
    void extraBareOccurrenceRequiresAdmissionBeforeRetryingTheSameEntry(String operation) throws Exception {
        // given
        try (var blue = BlueCoordination.inMemory()) {
            var timeline = blue.timelines().register("rooted/rejected-birth", "alice");
            var host = blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of("rooted-rejected-birth-host"), resource("host.yaml")).publicRoot().fromNow());
            var child = blue.documents().draft(DocumentId.of("rooted-rejected-birth-child"),
                    blue.values().yaml(resource("child.yaml")));
            var sourceId = DocumentId.of(child.initial().blueId());
            var before = host.exact().json();
            var history = receipts(host);
            // when
            var pending = blue.operations().on(host).from(timeline).call(operation).through("ownerChannel")
                    .request(request -> request.managed("order", child))
                    .expectOccurrence("/orders/expected", child).execute();
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, pending.disposition());
            assertEquals(before, host.exact().json());
            assertEquals(history, receipts(host));
            assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(child.id()));
            var prerequisites = blue.advanced().sourceHistoryPrerequisites(host);
            assertEquals(1, prerequisites.size());
            assertEquals("ADMISSION", prerequisites.get(0).kind().name());
            assertEquals(sourceId, prerequisites.get(0).sourceDocumentId());
            var admission = blue.advanced().processSourceHistoryPrerequisite(prerequisites.get(0));
            assertTrue(admission.admission().isPresent());
            assertTrue(blue.advanced().processSourceHistoryPrerequisite(prerequisites.get(0)).replayed());
            var source = blue.documents().require(sourceId);
            var sourceHistory = receipts(source);
            var applied = blue.processing().processNext(host).entry(pending.entry());
            assertEquals(EntryDisposition.APPLIED, applied.disposition(), applied.diagnostic().toString());
            assertEquals(pending.entry().blueId(), applied.entry().blueId());
            assertTrue(blue.processing().drain().quiescent());
            var born = blue.documents().require(child.id());
            assertEquals(1, born.snapshot().longAt("/initializationCount"));
            assertEquals(1, source.snapshot().longAt("/initializationCount"));
            assertEquals(sourceHistory, receipts(source), "Root calculation cannot republish the independently admitted source");
            assertEquals(born.snapshot().blueId(), host.snapshot().valueAt("/orders/expected").blueId());
            assertEquals(source.snapshot().blueId(), host.snapshot().valueAt("/orders/extra").blueId());
            var settled = List.of(receipts(host), receipts(born), receipts(source));
            var entries = blue.advanced().auditTimelineEntries();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertTrue(blue.processing().drain().quiescent());
            assertEquals(settled, List.of(receipts(host), receipts(born), receipts(source)));
            assertEquals(entries, blue.advanced().auditTimelineEntries());
        }
    }

    private static List<String> receipts(DocumentHandle document) {
        return document.history().stream().map(revision -> revision.managedEpochReceipt().orElseThrow().receiptIdentity()).toList();
    }

    private static String resource(String name) throws java.io.IOException {
        try (var in = RootedMixedDraftExpansionTest.class.getResourceAsStream("/rooted-managed-rejections/" + name)) {
            if (in == null) throw new java.io.IOException("Missing mixed birth fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
