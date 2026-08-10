package blue.coordination.api;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fast public API smoke and append-publication unit tests. */
final class CoordinationEngineTest {
    private static final String COUNTER = """
            documentId: counter
            name: Counter
            counter: 0
            contracts:
              aliceChannel:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: counter/alice
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
              bobChannel:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: counter/bob
                actor:
                  type: MyOS/Principal Actor
                  accountId: bob
              increment:
                type: Coordination/Sequential Workflow Operation
                channel: aliceChannel
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
              decrement:
                type: Coordination/Sequential Workflow Operation
                channel: bobChannel
                request:
                  amount: {type: Integer}
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: replace
                          path: /counter
                          val:
                            $subtract:
                              - $document: /counter
                              - $binding: event/message/request/amount
                      - $return: true
            """;

    @Test
    void counterQuickstartProducesTwo() {
        try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
            Timeline alice = engine.registerTimeline(
                    "counter/alice", "alice");
            Timeline bob = engine.registerTimeline("counter/bob", "bob");
            DocumentId counter = DocumentId.of("counter");
            engine.startDocument(counter, COUNTER);

            TimelineEntry increment = engine.append(alice, Operation.yaml(
                    "increment", "aliceChannel", "amount: 3"));
            assertEquals(1L, engine.metrics().counter(
                    CoordinationMetrics.Counter.ENTRIES_STORED_WHOLE));
            assertEquals(0L, engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            engine.drainThrough(increment.sourceOrderKey());
            TimelineEntry decrement = engine.append(bob, Operation.yaml(
                    "decrement", "bobChannel", "amount: 1"));
            engine.drainThrough(decrement.sourceOrderKey());

            assertEquals(BigInteger.valueOf(2L), engine.document(counter)
                    .valueAt("/counter").copyNode().getValue());
            assertEquals(2L, engine.document(counter).epoch());
            assertEquals(2, engine.metrics().journalEntryCount());
            assertEquals(2L, engine.metrics().counter(
                    CoordinationMetrics.Counter.ENTRIES_STORED_WHOLE));
            assertEquals(2L, engine.metrics().counter(
                    CoordinationMetrics.Counter.ROUTE_INDEX_LOOKUPS));
            assertEquals(1L, engine.metrics().counter(
                    CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS));
            assertEquals(2L, engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertEquals(0L, engine.metrics().counter(
                    CoordinationMetrics.Counter.CHILD_EPOCHS_COMMITTED));
            assertEquals(
                    CoordinationMetrics.Counter.values().length,
                    engine.metrics().counters().size());
        }
    }

    @Test
    void failedAppendDoesNotConsumeClockOrJournalCoordinates() {
        try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
            Timeline alice = engine.registerTimeline(
                    "counter/alice", "alice");
            CoordinationMetrics before = engine.metrics();

            assertThrows(RuntimeException.class, () -> engine.append(
                    alice,
                    Operation.yaml("increment", "aliceChannel", "[")));

            CoordinationMetrics rejected = engine.metrics();
            assertEquals(before.logicalClockMicros(),
                    rejected.logicalClockMicros());
            assertEquals(0, rejected.journalEntryCount());
            TimelineEntry accepted = engine.append(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 1"));
            assertEquals(1L, accepted.globalSequence());
            assertEquals(1L, accepted.timelineSequence());
            assertEquals(before.logicalClockMicros() + 1L,
                    accepted.timestampMicros());
        }
    }
}
