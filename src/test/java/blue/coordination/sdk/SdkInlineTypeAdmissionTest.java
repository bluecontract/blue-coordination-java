package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Supplied inline definitions are admission evidence without a prior upload. */
final class SdkInlineTypeAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {"document", "static", "closure"})
    void freshInlineTypeSurvivesAdmissionInvalidMutationAndRestart(String admission) {
        String source = """
                name: Fresh inline counter
                type:
                  name: Fresh inline counter definition
                  counter:
                    type: Integer
                    schema: {minimum: 0}
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/inline-type/alice
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$add: [{$document: /counter}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Tutorial/Inline Increment
                          - $return: true
                  wrongValue:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: wrong
                          - $return: true
                """;
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle timeline = blue.timelines().register("sdk/inline-type/alice", "alice");
            DocumentId id = DocumentId.of("sdk-inline-type-counter");
            DocumentHandle counter = switch (admission) {
                case "static" -> blue.documents().admitStaticProcessEmbedded(source).document("root");
                case "closure" -> blue.documents().admit(ManagedClosure.builder()
                        .document("root", id, source).publicRoot("root").fromNow().build())
                        .document("root");
                default -> blue.documents().admit(ManagedDocument.yaml(id, source).publicRoot().fromNow());
            };
            assertTrue(counter.snapshot().ready());
            assertEquals(0L, counter.snapshot().epoch());
            assertEquals(0L, counter.snapshot().longAt("/counter"));
            String before = counter.snapshot().blueId();
            EntryResult invalid = blue.operations().on(counter).from(timeline)
                    .call("wrongValue").through("ownerChannel").requestYaml("{}").execute();
            assertEquals(EntryDisposition.REJECTED, invalid.disposition(), invalid.diagnostic().toString());
            assertEquals(before, counter.snapshot().blueId());
            assertEquals(1, counter.history().size());
            assertTrue(invalid.publicEvents().isEmpty());
            EntryResult applied = blue.operations().on(counter).from(timeline)
                    .call("increment").through("ownerChannel").requestYaml("{}").execute();
            assertEquals(EntryDisposition.APPLIED, applied.disposition(), applied.diagnostic().toString());
            assertEquals(1L, counter.snapshot().longAt("/counter"));
            assertEquals(1L, counter.snapshot().epoch());
            assertEquals(1, applied.publicEvents().size());
            String after = counter.snapshot().blueId();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(after, counter.snapshot().blueId());
            assertEquals(1L, counter.snapshot().longAt("/counter"));
            assertEquals(2, counter.history().size());
            assertTrue(counter.snapshot().ready());
        }
    }
}
