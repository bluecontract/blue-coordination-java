package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocumentSessionStateEpochsTest {
    private static final DocumentId CHILD = DocumentId.of("recurrent-child");

    @Test
    void recurrentExactStateRequiresAnExplicitEpoch() {
        ExactValue x = exact("X");
        ExactValue y = exact("Y");
        DocumentSession.StateEpochs epochs = new DocumentSession.StateEpochs();
        epochs.record(revision(4L, x));
        epochs.record(revision(5L, y));

        assertEquals(4L, epochs.resolve(CHILD, "authored", x.blueId()));

        epochs.record(revision(7L, x));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> epochs.resolve(CHILD, "authored", x.blueId()));
        assertTrue(failure.getMessage().contains(
                "exact admitted epoch is required"), failure::getMessage);
    }

    @Test
    void authoredStateMatchingInitializedEpochZeroKeepsPreInitCursor() {
        ExactValue authored = exact("authored");
        DocumentSession.StateEpochs epochs = new DocumentSession.StateEpochs();
        epochs.record(revision(0L, authored));

        assertEquals(-1L, epochs.resolve(
                CHILD, authored.blueId(), authored.blueId()));
    }

    @Test
    void explicitEpochSelectsEitherOccurrenceOfARepeatedExactState() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession session = engine.start(CHILD, """
                    documentId: recurrent-child
                    state: initial
                    """);
            ExactValue x = exact("X");
            ExactValue previous = session.currentRevision().after();
            for (long epoch = 1L; epoch <= 7L; epoch++) {
                ExactValue after = epoch == 4L || epoch == 7L
                        ? x : exact("state-" + epoch);
                session.commit(
                        revision(epoch, previous, after),
                        session.layout(),
                        null,
                        session.activeSubscriptions(),
                        "synthetic|" + epoch);
                previous = after;
            }

            assertEquals(4L, session.resolveAdmissionEpoch(x.blueId(), 4L));
            assertEquals(7L, session.resolveAdmissionEpoch(x.blueId(), 7L));
            assertThrows(IllegalStateException.class,
                    () -> session.resolveAdmissionEpoch(x.blueId(), 5L));
            assertThrows(IllegalStateException.class,
                    () -> session.resolveAdmissionEpoch(x.blueId(), null));
        }
    }

    private static DocumentRevision revision(long epoch, ExactValue after) {
        return revision(epoch, null, after);
    }

    private static DocumentRevision revision(
            long epoch,
            ExactValue before,
            ExactValue after) {
        return new DocumentRevision(
                CHILD, epoch, epoch, DocumentRevision.Kind.CATCH_UP_COMPLETED,
                before, after, null, null, List.of(), 0L);
    }

    private static ExactValue exact(String value) {
        return ExactValue.verified(new Node().properties(
                "state", new Node().value(value)));
    }
}
