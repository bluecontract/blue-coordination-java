package blue.coordination.consumer;

import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DocumentRevision;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.TimelineHandle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minimal SDK packaging smoke test compiled against the built JAR. */
final class SdkBuiltJarConsumerTest {
    @Test
    void sdkCounterCompilesAndRunsAgainstBuiltJar() {
        // given
        String timelineId = "consumer/sdk-counter/alice";
        String id = "consumer-sdk-counter";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "alice");
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(id, counterYaml(id, timelineId))
                            .publicRoot()
                            .fromNow());

            // when
            EntryResult result = coordination.operations().on(counter)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 3")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED,
                    result.disposition());
            assertEquals(3L, counter.snapshot().longAt("/counter"));
            assertEquals(1L, counter.snapshot().epoch());
            assertTrue(result.stats().gas() > 0L);
        }
    }

    @Test
    void requiredRequestDeclarationIsValidatedOnlyAtInvocation() {
        // given
        String timelineId = "consumer/required-request/alice";
        String id = "consumer-required-request";
        String source = counterYaml(id, timelineId).replace(
                "amount: {type: Integer}",
                "amount: {type: Integer, schema: {required: true}}");
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(timelineId, "alice");
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(id, source).publicRoot().fromNow());
            // when
            EntryResult absent = coordination.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("{}").execute();
            long afterAbsent = counter.snapshot().longAt("/counter");
            EntryResult wrongKind = coordination.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("amount: wrong").execute();
            long afterWrongKind = counter.snapshot().longAt("/counter");
            EntryResult valid = coordination.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("amount: 3").execute();
            // then
            assertEquals(EntryDisposition.NO_MATCH, absent.disposition());
            assertEquals(0L, afterAbsent);
            assertEquals(EntryDisposition.NO_MATCH, wrongKind.disposition());
            assertEquals(0L, afterWrongKind);
            assertEquals(EntryDisposition.APPLIED, valid.disposition());
            assertEquals(3L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void scalarFactoryCreatesAndInitializesAChildAgainstBuiltJar() throws Exception {
        // given
        String source;
        try (var input = getClass().getResourceAsStream("/rc/scalar-factory.yaml")) {
            source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (BlueCoordination coordination = BlueCoordination.builder().release(
                "sha256:77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144",
                "sha256:0d7496790fb87d4589628c81fa8ca5e72b7d955458e20bc393f7837115ecb3b7")
                .contentDerivedDocumentIds().build()) {
            TimelineHandle timeline = coordination.timelines().register("tutorial/order-factory/merchant", "merchant");
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(coordination.values().yaml(source).blueId(), source).publicRoot().fromNow());
            // when
            EntryResult created = coordination.operations().on(host).from(timeline)
                    .call("createOrder01").through("merchantChannel")
                    .requestYaml("customerReference: consumer-customer\nquantity: 7").execute();
            // then
            assertEquals(EntryDisposition.APPLIED, created.disposition(), created.diagnostic().toString());
            assertEquals(1L, host.snapshot().longAt("/initializedOrderCount"));
            assertTrue(coordination.advanced().auditManagedOccurrence(host.id(), "/orders/order-01").isPresent());
        }
    }

    @Test
    void rootedScalarFactorySeparatesSourceAdmissionAndCatchUpAgainstBuiltJar() throws Exception {
        // given
        String source;
        try (var input = getClass().getResourceAsStream("/rc/scalar-factory.yaml")) {
            source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (BlueCoordination coordination = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            TimelineHandle timeline = coordination.timelines().register("tutorial/order-factory/merchant", "merchant");
            coordination.timelines().register("tutorial/orders/alice", "alice");
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(coordination.values().yaml(source).blueId(), source).publicRoot().fromNow());
            // when
            EntryResult created = coordination.operations().on(host).from(timeline)
                    .call("createOrder01").through("merchantChannel")
                    .requestYaml("customerReference: consumer-customer\nquantity: 7").execute();
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, created.disposition());
            assertEquals(0L, host.snapshot().longAt("/initializedOrderCount"));
            var pendingEntry = created.entry();
            var prerequisite = coordination.advanced().sourceHistoryPrerequisites(host);
            assertEquals(1, prerequisite.size());
            assertEquals("ADMISSION", prerequisite.get(0).kind().name());
            var admitted = coordination.advanced().processSourceHistoryPrerequisite(prerequisite.get(0));
            assertTrue(admitted.admission().isPresent());
            assertTrue(coordination.advanced().processSourceHistoryPrerequisite(prerequisite.get(0)).replayed());
            created = coordination.processing().processNext(host).entry(pendingEntry);
            assertEquals(pendingEntry.blueId(), created.entry().blueId());
            assertEquals(EntryDisposition.APPLIED, created.disposition(), created.diagnostic().toString());
            assertTrue(coordination.processing().drain().quiescent());
            assertEquals(1L, host.snapshot().longAt("/initializedOrderCount"));
            assertTrue(coordination.advanced().auditManagedOccurrence(host.id(), "/orders/order-01").isPresent());
            var child = coordination.documents().require(prerequisite.get(0).sourceDocumentId());
            assertEquals(1L, child.history().stream()
                    .filter(revision -> revision.kind() == DocumentRevision.Kind.INITIALIZATION).count());
            var parentHistory = historyEvidence(host);
            var childHistory = historyEvidence(child);
            var entries = coordination.advanced().auditTimelineEntries();
            assertTrue(coordination.processing().drain().quiescent());
            assertEquals(parentHistory, historyEvidence(host));
            assertEquals(childHistory, historyEvidence(child));
            assertEquals(entries, coordination.advanced().auditTimelineEntries());
        }
    }

    private static java.util.List<java.util.List<Object>> historyEvidence(DocumentHandle document) {
        return document.history().stream().map(revision -> java.util.List.<Object>of(
                revision.documentId(), revision.epoch(), revision.kind(),
                revision.before().map(value -> java.util.List.of(value.blueId(), value.json())),
                java.util.List.of(revision.after().blueId(), revision.after().json()),
                revision.sourceEntry().map(entry -> java.util.List.of(entry.blueId(), entry.timeline(),
                        entry.globalSequence(), entry.timelineSequence())),
                revision.publicEvents().stream().map(event -> java.util.List.of(event.blueId(),
                        event.exact().json(), event.sourceDocument(), event.occurrencePath())).toList(),
                revision.processingGas(), revision.managedEpochReceipt().map(receipt -> receipt.receiptIdentity())
        )).toList();
    }

    private static String counterYaml(
            String id,
            String timelineId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val:
                                $add:
                                  - $document: /counter
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(id, timelineId);
    }
}
