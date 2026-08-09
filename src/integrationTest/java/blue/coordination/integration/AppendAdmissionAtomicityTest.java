package blue.coordination.integration;

import blue.coordination.api.Operation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Timeline append commits journal coordinates and logical time together. */
final class AppendAdmissionAtomicityTest {
    @Test
    void invalidEntryDoesNotConsumeClockSequenceOrPredecessor() {
        try (TestEngine engine = TestEngine.create();
             TestEngine fresh = TestEngine.create()) {
            var timeline = engine.timeline("atomic/alice", "alice");
            var freshTimeline = fresh.timeline("atomic/alice", "alice");
            long clockBefore = engine.logicalClockMicros();
            int objectsBefore = engine.wholeObjectCount();

            assertThrows(
                    RuntimeException.class,
                    () -> engine.append(
                            timeline,
                            Operation.yaml(
                                    "increment", "ownerChannel", "[")));

            assertEquals(0, engine.journalSize());
            assertEquals(clockBefore, engine.logicalClockMicros());
            assertEquals(objectsBefore, engine.wholeObjectCount());

            var retry = engine.append(
                    timeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));
            var expected = fresh.append(
                    freshTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

            assertEquals(expected.timestampMicros(), retry.timestampMicros());
            assertEquals(expected.blueId(), retry.blueId());
            assertEquals(1L, retry.globalSequence());
            assertEquals(1L, retry.timelineSequence());
            assertEquals(expected.appendFrontier(), retry.appendFrontier());
            assertEquals(1, engine.journalSize());
        }
    }

    @Test
    void invalidExplicitTimestampAppendDoesNotAdvanceClock() {
        try (TestEngine engine = TestEngine.create()) {
            var timeline = engine.timeline("atomic/alice", "alice");
            long clockBefore = engine.logicalClockMicros();

            assertThrows(
                    RuntimeException.class,
                    () -> engine.appendAt(
                            timeline,
                            Operation.yaml(
                                    "increment", "ownerChannel", "["),
                            clockBefore + 100L));

            assertEquals(clockBefore, engine.logicalClockMicros());
            assertEquals(0, engine.journalSize());
            var entry = engine.append(
                    timeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));
            assertEquals(clockBefore + 1L, entry.timestampMicros());
            assertEquals(1L, entry.globalSequence());
            assertEquals(1L, entry.timelineSequence());
        }
    }
}
