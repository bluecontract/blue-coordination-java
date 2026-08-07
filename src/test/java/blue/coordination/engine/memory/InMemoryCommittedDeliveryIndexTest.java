package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class InMemoryCommittedDeliveryIndexTest {

    @Test
    void shouldRetainCompleteImmutableCommitEvidenceIdempotently() {
        InMemoryCommittedDeliveryIndex index =
                new InMemoryCommittedDeliveryIndex();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "root-a", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission, "after", "transition-a");

        CoordinationCommittedDelivery first = index.record(transition.plan);
        CoordinationCommittedDelivery repeated = index.record(transition.plan);

        assertSame(first, repeated);
        assertEquals(1, index.size());
        assertEquals(0L, first.plannedEpoch());
        assertEquals(1L, first.resultingEpoch());
        assertEquals(admission.session.currentRootBlueId(),
                first.plannedRootBlueId());
        assertEquals(transition.plan.resultingRootBlueId(),
                first.resultingRootBlueId());
        assertEquals(transition.plan.rootOutboxEventBlueIds(),
                first.rootOutboxEventBlueIds());
    }
}
