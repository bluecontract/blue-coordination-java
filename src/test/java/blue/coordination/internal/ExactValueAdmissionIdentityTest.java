package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legacy exact references retain the identity authenticated at admission. */
final class ExactValueAdmissionIdentityTest {
    @Test
    void originalReferenceMatchesAdmissionWhileAnUnadmittedStateStaysRejected() {
        // given
        DocumentId id = DocumentId.of("legacy-exact-admission");
        String source = """
                documentId: legacy-exact-admission
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: legacy/exact-admission
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  update:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      points: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Example/Updated
                              points: {$binding: event/message/request/points}
                          - $return: true
                """;
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            engine.registerTimeline("legacy/exact-admission", "alice");
            ExactValue original = engine.exactValue(source);
            ExactValue unknown = engine.exactValue(source.replace("counter: 0", "counter: 1"));

            // when
            DocumentSession admitted = engine.start(id, source);

            // then
            assertEquals(original.blueId(), admitted.authoredInitialBlueId());
            assertEquals(original.blueId(), admitted.revision(0L).before().orElseThrow().blueId());
            assertEquals(-1L, admitted.resolveAdmissionEpoch(original.blueId(), null));
            assertNotEquals(original.blueId(), unknown.blueId());
            InvalidAdmissionEvidenceException rejected = assertThrows(
                    InvalidAdmissionEvidenceException.class,
                    () -> admitted.resolveAdmissionEpoch(unknown.blueId(), null));
            assertTrue(rejected.getMessage().contains("unknown state"));
            assertEquals(0L, admitted.epoch());
            String current = admitted.revision(0L).after().blueId();
            engine.restartFromStores();
            DocumentSession restored = engine.documents().require(id);
            assertEquals(original.blueId(), restored.authoredInitialBlueId());
            assertEquals(current, restored.revision(0L).after().blueId());
            assertThrows(InvalidAdmissionEvidenceException.class,
                    () -> restored.resolveAdmissionEpoch(unknown.blueId(), null));
        }
    }
}
