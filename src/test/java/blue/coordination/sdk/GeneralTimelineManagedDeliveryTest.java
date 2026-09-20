package blue.coordination.sdk;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real managed driver; no operation wrapper or processor bypass. */
final class GeneralTimelineManagedDeliveryTest {
    @Test void deliversOriginalGeneralEnvelopeThroughOrdinaryWorkflow() {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.startYaml("""
                counter: 0
                contracts:
                  owner:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: probe/general}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  onCredit:
                    type: Coordination/Sequential Workflow
                    channel: owner
                    event:
                      message:
                        kind: CreditRecorded
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$event: /message/amount}
                          - $return: true
                """, "probe/general");
            var exact = fixture.blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: probe/general}
                actor: {type: MyOS/Principal Actor, accountId: alice}
                timestamp: 1800000000000000
                message:
                  kind: CreditRecorded
                  amount: 7
                """);
            // when
            var entry = fixture.blue.events().from(fixture.timelines.get("probe/general")).exact(exact).submit();
            var result = fixture.blue.processing().processNext(document).entry(entry);
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertEquals(BigInteger.valueOf(7), document.snapshot().exact().copyNode().getProperties().get("counter").getValue());
            assertEquals(exact.blueId(), fixture.blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow().exact().blueId());
            assertTrue(fixture.blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow().operationDetails().isEmpty());
            assertTrue(fixture.blue.processing().processNext(document).quiescent());
        }
    }
    @Test void acceptedChannelWithoutAMatchingHandlerStillSettlesItsCheckpoint() {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.startYaml("""
                counter: 0
                contracts:
                  owner:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: probe/quiet}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                """, "probe/quiet");
            var exact = fixture.blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: probe/quiet}
                actor: {type: MyOS/Principal Actor, accountId: alice}
                timestamp: 1800000000000000
                message: {kind: CreditRecorded, amount: 7}
                """);
            var before = document.snapshot().blueId();
            // when
            var entry = fixture.blue.events().from(fixture.timelines.get("probe/quiet")).exact(exact).submit();
            var result = fixture.blue.processing().processNext(document).entry(entry);
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertEquals(BigInteger.ZERO, document.snapshot().exact().copyNode().getProperties().get("counter").getValue());
            assertNotEquals(before, document.snapshot().blueId(), "The accepted subject must settle its channel checkpoint");
            assertTrue(fixture.blue.processing().processNext(document).quiescent());
        }
    }

    @Test void unrelatedTimelineDoesNotCreateAnEmptyCommittedEpoch() {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.startYaml("""
                counter: 0
                contracts:
                  owner:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: probe/quiet}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                """, "probe/quiet");
            var other = fixture.blue.timelines().register("probe/other", "alice");
            var exact = fixture.blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: probe/other}
                actor: {type: MyOS/Principal Actor, accountId: alice}
                timestamp: 1800000000000000
                message: {kind: CreditRecorded, amount: 7}
                """);
            var before = document.snapshot().blueId();
            // when
            fixture.blue.events().from(other).exact(exact).submit();
            var result = fixture.blue.processing().processNext(document);
            // then
            assertTrue(result.quiescent());
            assertEquals(before, document.snapshot().blueId());
            assertTrue(result.entries().isEmpty());
        }
    }

}
