package blue.coordination.engine.memory;

import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.spi.CoordinationSessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCoordinationSessionStoreTest
        extends CoordinationSessionStoreContract {

    @Override
    CoordinationSessionStore createStore() {
        return new InMemoryCoordinationSessionStore();
    }

    @Override
    List<String> rootOutbox(
            CoordinationSessionStore store,
            DocumentSessionId sessionId) {
        return ((InMemoryCoordinationSessionStore) store)
                .rootOutbox(sessionId);
    }

    @Override
    List<String> terminalProgress(
            CoordinationSessionStore store,
            DocumentSessionId sessionId) {
        return ((InMemoryCoordinationSessionStore) store)
                .terminalProgress(sessionId);
    }

    @Test
    void shouldCommitRootOutboxAndTerminalProgressExactlyOnce() {
        // given
        InMemoryCoordinationSessionStore store =
                new InMemoryCoordinationSessionStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-observable", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-observable");

        // when
        store.commit(transition.plan);
        store.commit(transition.plan);
        List<String> outbox = store.rootOutbox(
                admission.session.sessionId());
        List<String> progress = store.terminalProgress(
                admission.session.sessionId());

        // then
        assertEquals(transition.plan.rootOutboxEventBlueIds(), outbox);
        assertEquals(1, progress.size());
        assertEquals(transition.plan.eventBlueId(), progress.get(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> outbox.add("forbidden"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> progress.add("forbidden"));
    }

    @Test
    void shouldCommitOnlyTerminalProgressForANoncommittingProcessResult() {
        // given
        InMemoryCoordinationSessionStore store =
                new InMemoryCoordinationSessionStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-progress-observable", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture progress =
                CoordinationEngineStorageTestFixtures.progressOnlyCommit(
                        admission,
                        "rejected",
                        "transition-progress-observable");

        // when
        CommitStatus status = store.commit(progress.plan).status();

        // then
        assertEquals(CommitStatus.COMMITTED, status);
        assertTrue(store.rootOutbox(admission.session.sessionId()).isEmpty());
        assertEquals(
                java.util.Collections.singletonList(progress.plan.eventBlueId()),
                store.terminalProgress(admission.session.sessionId()));
    }

    @Test
    void shouldKeepIndependentSessionCountsAndObservability() {
        // given
        InMemoryCoordinationSessionStore store =
                new InMemoryCoordinationSessionStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture first =
                CoordinationEngineStorageTestFixtures.admission(
                        "count-a", "shared");
        CoordinationEngineStorageTestFixtures.AdmissionFixture second =
                CoordinationEngineStorageTestFixtures.admission(
                        "count-b", "shared");

        // when
        store.admit(first.commit);
        store.admit(second.commit);

        // then
        assertEquals(2, store.sessionCount());
        assertTrue(store.rootOutbox(first.session.sessionId()).isEmpty());
        assertTrue(store.rootOutbox(second.session.sessionId()).isEmpty());
    }
}
