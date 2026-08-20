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
            // given
            var timeline = engine.timeline("atomic/alice", "alice");
            var freshTimeline = fresh.timeline("atomic/alice", "alice");
            long clockBefore = engine.logicalClockMicros();
            int objectsBefore = engine.wholeObjectCount();

            // when
            assertThrows(
                    RuntimeException.class,
                    () -> engine.append(
                            timeline,
                            Operation.yaml(
                                    "increment", "ownerChannel", "[")));
            int journalSizeAfterFailure = engine.journalSize();
            long clockAfterFailure = engine.logicalClockMicros();
            int objectsAfterFailure = engine.wholeObjectCount();

            var retry = engine.append(
                    timeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));
            var expected = fresh.append(
                    freshTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

            // then
            assertEquals(0, journalSizeAfterFailure);
            assertEquals(clockBefore, clockAfterFailure);
            assertEquals(objectsBefore, objectsAfterFailure);
            assertEquals(expected.timestampMicros(), retry.timestampMicros());
            assertEquals(expected.blueId(), retry.blueId());
            assertEquals(1L, retry.globalSequence());
            assertEquals(1L, retry.timelineSequence());
            assertEquals(1, engine.journalSize());
        }
    }

    @Test
    void invalidExplicitTimestampAppendDoesNotAdvanceClock() {
        try (TestEngine engine = TestEngine.create()) {
            // given
            var timeline = engine.timeline("atomic/alice", "alice");
            long clockBefore = engine.logicalClockMicros();

            // when
            assertThrows(
                    RuntimeException.class,
                    () -> engine.appendAt(
                            timeline,
                            Operation.yaml(
                                    "increment", "ownerChannel", "["),
                            clockBefore + 100L));
            long clockAfterFailure = engine.logicalClockMicros();
            int journalSizeAfterFailure = engine.journalSize();
            var entry = engine.append(
                    timeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

            // then
            assertEquals(clockBefore, clockAfterFailure);
            assertEquals(0, journalSizeAfterFailure);
            assertEquals(clockBefore + 1L, entry.timestampMicros());
            assertEquals(1L, entry.globalSequence());
            assertEquals(1L, entry.timelineSequence());
        }
    }
}
