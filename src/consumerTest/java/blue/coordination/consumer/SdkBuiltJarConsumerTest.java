package blue.coordination.consumer;

import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
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
        String timelineId = "consumer/required-request/alice";
        String id = "consumer-required-request";
        String source = counterYaml(id, timelineId).replace(
                "amount: {type: Integer}",
                "amount: {type: Integer, schema: {required: true}}");
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(timelineId, "alice");
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(id, source).publicRoot().fromNow());
            EntryResult absent = coordination.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("{}").execute();
            assertEquals(EntryDisposition.NO_MATCH, absent.disposition(),
                    () -> "counter=" + counter.snapshot().longAt("/counter") + "; stats=" + absent.stats());
            assertEquals(0L, counter.snapshot().longAt("/counter"));
            EntryResult wrongKind = coordination.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("amount: wrong").execute();
            assertEquals(EntryDisposition.NO_MATCH, wrongKind.disposition());
            assertEquals(0L, counter.snapshot().longAt("/counter"));
            EntryResult valid = coordination.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("amount: 3").execute();
            assertEquals(EntryDisposition.APPLIED, valid.disposition());
            assertEquals(3L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void scalarFactoryCreatesAndInitializesAChildAgainstBuiltJar() throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/rc/scalar-factory.yaml")) {
            source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (BlueCoordination coordination = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            TimelineHandle timeline = coordination.timelines().register("tutorial/order-factory/merchant", "merchant");
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(coordination.values().yaml(source).blueId(), source).publicRoot().fromNow());
            EntryResult created = coordination.operations().on(host).from(timeline)
                    .call("createOrder01").through("merchantChannel")
                    .requestYaml("customerReference: consumer-customer\nquantity: 7").execute();
            assertEquals(EntryDisposition.APPLIED, created.disposition(), created.diagnostic().toString());
            assertEquals(1L, host.snapshot().longAt("/initializedOrderCount"));
            assertTrue(coordination.advanced().auditManagedOccurrence(host.id(), "/orders/order-01").isPresent());
        }
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
