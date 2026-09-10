package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A generated event is exact invocation input, including through its bridge reference. */
final class SdkEmbeddedEventPayloadTest {
    @Test
    void generatedPayloadRemainsReadableThroughEmbeddedDeliveryReference() {
        // given
        var builder = LegacyContracts10TestProfile.builder();
        // when
        var result = runPayload(builder, java.util.List.of("payload-observed"),
                java.util.List.of("payload-parent"));
        // then
        assertEquals(EntryDisposition.APPLIED, result.disposition());
    }

    @Test
    void rootedPayloadPublishesSourceAndObserverEventsOnceWithTheirOwners() {
        // given
        var builder = BlueCoordination.builder();
        // when
        var result = runPayload(builder, java.util.List.of("payload-signal", "payload-observed"),
                java.util.List.of("payload-child", "payload-parent"));
        // then
        assertEquals(EntryDisposition.APPLIED, result.disposition());
    }

    private static EntryResult runPayload(BlueCoordination.Builder builder,
            java.util.List<String> expectedKinds, java.util.List<String> expectedOwners) {
        // given
        try (BlueCoordination coordination = builder.build()) {
            TimelineHandle timeline = coordination.timelines().register("payload/alice", "alice");
            ManagedClosure definition = ManagedClosure.builder()
                    .document("parent", DocumentId.of("payload-parent"), """
                            documentId: payload-parent
                            observed: 0
                            contracts:
                              embedded: {type: Process Embedded, paths: [/child]}
                              childEvents:
                                type: Embedded Node Channel
                                sourcePath: /child
                                event: {type: Coordination/Event, kind: payload-signal}
                              observe:
                                type: Coordination/Sequential Workflow
                                channel: childEvents
                                event: {type: Coordination/Event, kind: payload-signal}
                                steps:
                                  - type: Coordination/Compute
                                    do:
                                      - $appendChange:
                                          op: replace
                                          path: /observed
                                          val: {$binding: event/event/amount}
                                      - $appendEvent:
                                          type: Coordination/Event
                                          kind: payload-observed
                                          amount: {$binding: event/event/amount}
                                      - $return: true
                            """)
                    .document("child", DocumentId.of("payload-child"), """
                            documentId: payload-child
                            contracts:
                              owner:
                                type: Coordination/Timeline Channel
                                timeline: {type: MyOS/MyOS Timeline, timelineId: payload/alice}
                                actor: {type: MyOS/Principal Actor, accountId: alice}
                              emit:
                                type: Coordination/Sequential Workflow Operation
                                channel: owner
                                request: {}
                                steps:
                                  - type: Coordination/Compute
                                    do:
                                      - $appendEvent:
                                          type: Coordination/Event
                                          kind: payload-signal
                                          amount: {$add: [600, 7]}
                                      - $return: true
                            """)
                    .bindOccurrence("parent", "/child", "child")
                    .publicRoot("parent").fromNow().build();
            ClosureHandle closure = coordination.documents().admit(definition);
            // when
            EntryResult result = coordination.operations().on(closure.document("child"))
                    .from(timeline).call("emit").through("owner")
                    .request(request -> { }).execute();
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertEquals(607L, closure.document("parent").snapshot().longAt("/observed"));
            assertEquals(expectedKinds, result.publicEvents().stream()
                    .map(event -> event.exact().scalarAt("/kind")).toList());
            assertEquals(expectedOwners, result.publicEvents().stream()
                    .map(event -> event.sourceDocument().orElseThrow().value()).toList());
            result.publicEvents().forEach(event -> assertEquals("607", event.exact().scalarAt("/amount").toString()));
            assertEquals(java.util.List.of(), coordination.processing().drain().entries());
            return result;
        }
    }
}
