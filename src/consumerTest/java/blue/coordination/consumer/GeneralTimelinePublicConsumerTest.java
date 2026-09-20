package blue.coordination.consumer;

import blue.coordination.sdk.*;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled against the built JAR; the new facet does not require internal types. */
final class GeneralTimelinePublicConsumerTest {
    @Test void exactGeneralEntryAndOperationConvenienceShareOnePublicManagedPath() {
        // given
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var timeline = blue.timelines().register("consumer/general", "alice");
            var root = blue.documents().admitStaticProcessEmbedded("""
                    counter: 0
                    contracts:
                      owner:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: consumer/general}
                        actor: {type: MyOS/Principal Actor, accountId: alice}
                      onMessage:
                        type: Coordination/Sequential Workflow
                        channel: owner
                        event: {message: {kind: CreditRecorded}}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: {$event: /message/amount}}
                              - $return: true
                      reset:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: 0}
                              - $return: true
                    """, ActivationPolicy.importFullHistory()).document("root");
            var exact = blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: consumer/general}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    timestamp: 20
                    message: {kind: CreditRecorded, amount: 7}
                    """);
            // when
            var entry = blue.events().from(timeline).exact(exact).submit();
            var outcome = blue.processing().processNextStage(root);
            // then
            assertEquals(EntryDisposition.APPLIED, outcome.entry(entry).disposition());
            assertEquals(7, root.snapshot().longAt("/counter"));
            TimelineEntrySnapshot audit = blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow();
            assertTrue(audit.operationDetails().isEmpty()); assertTrue(audit.request().isEmpty());
            assertThrows(NoSuchElementException.class, audit::operation);
            assertThrows(NoSuchElementException.class, audit::channel);
            assertEquals(exact.blueId(), audit.exact().blueId());
            var reset = blue.operations().on(root).from(timeline).call("reset").through("owner").execute();
            assertEquals(EntryDisposition.APPLIED, reset.disposition());
            assertEquals(0, root.snapshot().longAt("/counter"));
            var operation = blue.advanced().auditTimelineEntry(reset.entry().blueId()).orElseThrow();
            assertEquals("reset", operation.operationDetails().orElseThrow().operation());
            assertEquals("owner", operation.channel());
            assertTrue(operation.request().isEmpty());
        }
    }
}
