package blue.coordination.internal;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocumentSessionStateEpochsTest {
    private static final DocumentId CHILD = DocumentId.of("recurrent-child");

    @Test
    void recurrentExactStateRequiresAnExplicitEpoch() {
        // given

        ExactValue x = exact("X");
        ExactValue y = exact("Y");
        DocumentSession.StateEpochs epochs = new DocumentSession.StateEpochs();
        epochs.record(revision(4L, x));

        // when
        epochs.record(revision(5L, y));

        // then
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
        // given

        ExactValue authored = exact("authored");
        DocumentSession.StateEpochs epochs = new DocumentSession.StateEpochs();

        // when
        epochs.record(revision(0L, authored));

        // then
        assertEquals(-1L, epochs.resolve(
                CHILD, authored.blueId(), authored.blueId()));
    }

    @Test
    void explicitEpochSelectsEitherOccurrenceOfARepeatedExactState() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession session = engine.start(CHILD, """
                    documentId: recurrent-child
                    state: initial
                    """);
            ExactValue x = exact("X");
            ExactValue previous = session.currentRevision().after();

            // when
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

            // then
            assertEquals(4L, session.resolveAdmissionEpoch(x.blueId(), 4L));
            assertEquals(7L, session.resolveAdmissionEpoch(x.blueId(), 7L));
            assertThrows(IllegalStateException.class,
                    () -> session.resolveAdmissionEpoch(x.blueId(), 5L));
            assertThrows(IllegalStateException.class,
                    () -> session.resolveAdmissionEpoch(x.blueId(), null));
        }
    }

    @Test
    void readyPublicationRequiresTheCurrentGraphEpoch() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {

            // when
            DocumentSession session = engine.start(CHILD, """
                    documentId: recurrent-child
                    state: initial
                    """);

            // then
            assertEquals(0L, session.epoch());
            assertEquals(0L, session.readyEpoch());
            assertEquals(0L, session.graphPublishedEpoch());
            assertTrue(session.isLocallyReady());

            ExactValue current = session.currentRevision().after();
            session.commit(
                    revision(1L, current, current),
                    session.layout(),
                    null,
                    session.activeSubscriptions(),
                    "synthetic|readiness");
            session.markCatchingUp();

            assertEquals(1L, session.epoch());
            assertEquals(0L, session.readyEpoch());
            assertEquals(0L, session.graphPublishedEpoch());
            assertFalse(session.isLocallyReady());
            assertThrows(IllegalStateException.class,
                    () -> session.markReady(session.readyThrough()));

            session.markGraphPublished();
            assertFalse(session.isLocallyReady(),
                    "graph publication alone must not expose the state");
            session.markReady(session.readyThrough());

            assertEquals(SessionStatus.READY, session.status());
            assertEquals(1L, session.readyEpoch());
            assertEquals(1L, session.graphPublishedEpoch());
            assertTrue(session.isLocallyReady());
        }
    }

    @Test
    void applicationReadRejectsEachStaleLocalPublicationEpoch() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession session = engine.start(CHILD, """
                    documentId: recurrent-child
                    state: initial
                    """);
            ExactValue current = session.currentRevision().after();
            session.commit(
                    revision(1L, current, current),
                    session.layout(),
                    null,
                    session.activeSubscriptions(),
                    "synthetic|application-read");

            // when
            session.restoreCoordinationState(
                    SessionStatus.READY, session.readyThrough(), 0L, 1L);

            // then
            assertNotReady(engine);

            session.restoreCoordinationState(
                    SessionStatus.READY, session.readyThrough(), 1L, 0L);
            assertNotReady(engine);

            session.restoreCoordinationState(
                    SessionStatus.READY, session.readyThrough(), 1L, 1L);
            assertEquals(1L, engine.document(CHILD).epoch());
        }
    }

    @Test
    void restartNormalizesLegacyReadyWithPendingTopLevelAdmission() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            engine.makeHistoricalUnavailable("provider window pending");
            engine.startDocument(
                    CHILD,
                    """
                    documentId: recurrent-child
                    state: initial
                    """,
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);

            // when
            DocumentSession session = engine.session(CHILD.value());

            // then
            assertEquals(SessionStatus.CATCHING_UP, session.status());

            session.restoreCoordinationState(
                    SessionStatus.READY,
                    session.readyThrough(),
                    session.epoch(),
                    session.epoch());
            assertEquals(SessionStatus.READY, session.status(),
                    "simulate the legacy marker retained before Round 11");

            engine.restartFromStores();

            assertEquals(SessionStatus.CATCHING_UP, session.status());
            assertNotReady(engine);
            engine.makeHistoricalAvailable();
            assertTrue(engine.drain().quiescent());
            assertEquals(SessionStatus.READY, engine.document(CHILD).status());
        }
    }

    private static void assertNotReady(DefaultCoordinationEngine engine) {
        CoordinationException failure = assertThrows(
                CoordinationException.class,
                () -> engine.document(CHILD));
        assertEquals(CoordinationErrorCode.DOCUMENT_NOT_READY,
                failure.code());
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
