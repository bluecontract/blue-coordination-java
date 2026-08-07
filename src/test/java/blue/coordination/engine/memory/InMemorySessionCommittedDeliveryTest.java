package blue.coordination.engine.memory;

import blue.coordination.engine.api.CommitStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class InMemorySessionCommittedDeliveryTest {

    @Test
    void shouldCommitSessionAndDeliveryReceiptExactlyOnce() {
        // given
        InMemoryCoordinationSessionStore store =
                new InMemoryCoordinationSessionStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "receipt-session", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission, "after", "receipt-transition");

        // when
        CommitStatus first = store.commit(transition.plan).status();
        CommitStatus retry = store.commit(transition.plan).status();

        // then
        assertEquals(CommitStatus.COMMITTED, first);
        assertEquals(CommitStatus.ALREADY_COMMITTED, retry);
        assertEquals(1, store.committedDeliveries().size());
        assertEquals(
                transition.plan.transitionIdentity(),
                store.committedDeliveries().find(
                        transition.plan.eventBlueId(),
                        admission.session.sessionId()).get()
                        .transitionIdentity());
        assertEquals(transition.plan.resultingEpoch(),
                store.committedDeliveries().find(
                        transition.plan.eventBlueId(),
                        admission.session.sessionId()).get()
                        .resultingEpoch());
        assertEquals(1, store.terminalProgress(
                admission.session.sessionId()).size());
    }
}
