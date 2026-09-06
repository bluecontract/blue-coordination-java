package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact public Timeline ordering across a saved-original historical attachment. */
final class SdkTimestampCatchUpOrderTest {
    private static final long DAY = 2_100_000_000_000_000L;
    private static final long HOUR = 3_600_000_000L;

    @Test
    void fourThirtyRootInputCannotBeOvertakenByQueuedFivePmSourceInput() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            Timeline alice = blue.advanced().rawEngine().registerTimeline("tutorial/time/alice", "alice");
            Timeline bob = blue.advanced().rawEngine().registerTimeline("tutorial/time/bob", "bob");
            ExactBlueValue original = blue.values().yaml(CHILD);
            DocumentHandle child = blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of("tutorial-embedded-child"), CHILD).publicRoot().fromNow());
            DocumentHandle root = blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of("tutorial-embedded-host"), ROOT).publicRoot().fromNow());
            blue.advanced().rawEngine().appendAt(alice,
                    Operation.yaml("increment", "ownerChannel", "amount: 1"), DAY + 12 * HOUR);
            blue.processing().drain();
            blue.advanced().rawEngine().appendAt(alice,
                    Operation.yaml("increment", "ownerChannel", "amount: 1"), DAY + 15 * HOUR + HOUR / 2);
            blue.processing().drain();
            assertEquals(3, child.history().size());
            var retained = java.util.stream.LongStream.rangeClosed(0, 2)
                    .mapToObj(epoch -> blue.advanced().auditManagedEpoch(child.id(), epoch)
                            .orElseThrow().receiptIdentity()).toList();
            blue.advanced().rawEngine().appendAt(alice,
                    Operation.yaml("increment", "ownerChannel", "amount: 100"), DAY + 17 * HOUR);
            blue.advanced().rawEngine().appendAt(bob,
                    Operation.yaml("attachChild", "ownerChannel", "child:\n  blueId: " + original.blueId()), DAY + 16 * HOUR);
            blue.advanced().rawEngine().appendAt(bob,
                    Operation.yaml("mark", "ownerChannel", "{}"), DAY + 16 * HOUR + HOUR / 2);

            DrainResult drained = blue.processing().drain();
            assertTrue(drained.quiescent(), drained.diagnostic().toString());
            assertTrue(root.snapshot().ready());
            assertEquals(2L, root.snapshot().longAt("/observedAtMark"),
                    "16:30 must observe only source history through15:30");
            assertEquals(102L, child.snapshot().longAt("/counter"));
            assertEquals(102L, root.snapshot().longAt("/observedCounter"));
            assertEquals(4, child.history().size());
            assertEquals(retained, java.util.stream.LongStream.rangeClosed(0, 2)
                    .mapToObj(epoch -> blue.advanced().auditManagedEpoch(child.id(), epoch)
                            .orElseThrow().receiptIdentity()).toList());
        }
    }
    private static final String CHILD = """
            name: Embedded Child Counter
            documentId: tutorial-embedded-child
            counter: 0
            contracts:
              ownerChannel:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: tutorial/time/alice
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
              increment:
                type: Coordination/Sequential Workflow Operation
                channel: ownerChannel
                request:
                  amount:
                    type: Integer
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
                      - $appendEvent:
                          type: Coordination/Event
                          kind: Tutorial/Embedded Counter Changed
                      - $return: true
            """;
    private static final String ROOT = """
            name: Embedded Counter Host
            documentId: tutorial-embedded-host
            observedCounter: -1
            observedAtMark: -1
            contracts:
              embedded:
                type: Process Embedded
                paths:
                  - /child
              ownerChannel:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: tutorial/time/bob
                actor:
                  type: MyOS/Principal Actor
                  accountId: bob
              attachChild:
                type: Coordination/Sequential Workflow Operation
                channel: ownerChannel
                request:
                  child: {}
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: add
                          path: /child
                          val:
                            $binding: event/message/request/child
                      - $return: true
              fromChild:
                type: Embedded Node Channel
                sourcePath: /child
                event:
                  type: Coordination/Event
                  kind: Tutorial/Embedded Counter Changed
              reflectChild:
                type: Coordination/Sequential Workflow
                channel: fromChild
                event:
                  type: Coordination/Event
                  kind: Tutorial/Embedded Counter Changed
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: replace
                          path: /observedCounter
                          val:
                            $document: /child/counter
                      - $return: true
              mark:
                type: Coordination/Sequential Workflow Operation
                channel: ownerChannel
                request: {}
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: replace
                          path: /observedAtMark
                          val: {$document: /child/counter}
                      - $return: true
            """;
}
