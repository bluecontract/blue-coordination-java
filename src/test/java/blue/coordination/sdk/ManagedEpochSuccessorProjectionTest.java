package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DTO shape negatives complement, and do not replace, the actual SDK source-publication regression. */
final class ManagedEpochSuccessorProjectionTest {
    private static final DocumentId CONSUMER = DocumentId.of("consumer");
    private static final ManagedEpochApplicationWork.Position ANCHOR =
            new ManagedEpochApplicationWork.Position("anchor", "anchor", "goal", Optional.empty());
    private static final ManagedEpochApplicationWork.Position FIRST =
            new ManagedEpochApplicationWork.Position("anchor", "first", "goal", Optional.empty());
    private static final ManagedEpochApplicationWork.RepresentationStep SUCCESSOR =
            new ManagedEpochApplicationWork.RepresentationStep("future-cause", "before", "after", ANCHOR, FIRST, false);

    @Test
    void ordinaryReceiptStillCannotCarryAnUnboundCursor() {
        // given
        var cursor = Optional.of(ANCHOR);
        // when
        var rejected = assertThrows(IllegalArgumentException.class, () -> new ManagedEpochApplicationReceipt(
                "receipt", "work", "plan", "anchor", "invocation", "result", "companion", CONSUMER,
                2L, "consumer-receipt", "consumer-blue", 1L, Optional.empty(), cursor));
        // then
        assertEquals("Representation cursor requires its application cause", rejected.getMessage());
    }

    @Test
    void numberedReceiptCannotClaimItsFirstSuccessorAlreadyApplied() {
        // given
        var emptyGoal = new ManagedEpochApplicationWork.Position("anchor", "anchor", "anchor", Optional.empty());
        // when
        assertThrows(IllegalArgumentException.class, () -> receipt(FIRST, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> receipt(emptyGoal, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> receipt(ANCHOR, Optional.of("applied-cause")));
        // then
        assertEquals(ANCHOR, receipt(ANCHOR, Optional.empty()).resultingRepresentationCursor().orElseThrow());
    }

    @Test
    void workCannotExposeTheFutureSuccessorAsSimultaneouslyApplied() {
        // given
        var emptyGoal = new ManagedEpochApplicationWork.RepresentationStep("future-cause", "before", "after",
                new ManagedEpochApplicationWork.Position("anchor", "anchor", "anchor", Optional.empty()),
                new ManagedEpochApplicationWork.Position("anchor", "first", "anchor", Optional.empty()), false);
        // when
        assertThrows(IllegalArgumentException.class, () -> work(Optional.of(SUCCESSOR)));
        assertThrows(IllegalArgumentException.class, () -> work(Optional.empty(), emptyGoal));
        var numbered = work(Optional.empty());
        // then
        assertTrue(numbered.representationStep().isEmpty());
        assertEquals(SUCCESSOR, numbered.successorRepresentationStep().orElseThrow());
    }

    private static ManagedEpochApplicationReceipt receipt(ManagedEpochApplicationWork.Position cursor,
            Optional<String> appliedCause) {
        return new ManagedEpochApplicationReceipt("receipt", "work", "plan", "anchor", "invocation", "result",
                "companion", CONSUMER, 2L, "consumer-receipt", "consumer-blue", 1L, appliedCause,
                Optional.of(cursor), Optional.of("future-cause"));
    }

    private static ManagedEpochApplicationWork work(Optional<ManagedEpochApplicationWork.RepresentationStep> applied) {
        return work(applied, SUCCESSOR);
    }

    private static ManagedEpochApplicationWork work(Optional<ManagedEpochApplicationWork.RepresentationStep> applied,
            ManagedEpochApplicationWork.RepresentationStep successor) {
        return new ManagedEpochApplicationWork("work", "plan", "barrier", "anchor", DocumentId.of("source"),
                0L, CONSUMER, "occurrence", "/child", 1L, 1L, "consumer-blue", 1L,
                applied, Optional.of(successor));
    }
}
