package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inline types inside exact executable fields retain their authored evidence. */
final class SdkBexLiteralTypeAdmissionTest {
    @ParameterizedTest
    @CsvSource({"document,false", "static,false", "closure,false",
            "document,true", "static,true", "closure,true"})
    void literalTypesRemainAvailableAndForbiddenExpressionsStillReject(String admission, boolean invalid) {
        String literalType = invalid ? "{$document: /type}" : "{name: Fresh BEX literal definition}";
        String source = """
                name: Inline literal type host
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/literal-type/alice
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  create:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /created
                              val:
                                $literal:
                                  type: %s
                                  ordinary: 1
                          - $return: true
                """.formatted(literalType);
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle timeline = blue.timelines().register("sdk/literal-type/alice", "alice");
            DocumentId id = DocumentId.of("sdk-literal-type-host");
            DocumentHandle host = switch (admission) {
                case "static" -> blue.documents().admitStaticProcessEmbedded(source).document("root");
                case "closure" -> blue.documents().admit(ManagedClosure.builder()
                        .document("root", id, source).publicRoot("root").fromNow().build())
                        .document("root");
                default -> blue.documents().admit(ManagedDocument.yaml(id, source).publicRoot().fromNow());
            };
            assertTrue(host.snapshot().ready());
            assertEquals(0L, host.snapshot().epoch());
            String before = host.snapshot().blueId();
            EntryResult result = blue.operations().on(host).from(timeline)
                    .call("create").through("ownerChannel").requestYaml("{}").execute();
            assertEquals(invalid ? EntryDisposition.REJECTED : EntryDisposition.APPLIED,
                    result.disposition(), result.diagnostic().toString());
            assertTrue(result.publicEvents().isEmpty());
            if (invalid) {
                assertTrue(result.diagnostic().message().contains("BEX expressions inside Blue type fields"),
                        result.diagnostic().toString());
                assertEquals(before, host.snapshot().blueId());
                assertEquals(1, host.history().size());
            } else {
                assertEquals(1L, host.snapshot().longAt("/created/ordinary"));
                assertEquals(1L, host.snapshot().epoch());
                assertEquals(2, host.history().size());
            }
            String after = host.snapshot().blueId();
            int epochs = host.history().size();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(after, host.snapshot().blueId());
            assertEquals(epochs, host.history().size());
            assertTrue(host.snapshot().ready());
        }
    }
}
