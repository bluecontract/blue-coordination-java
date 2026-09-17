package blue.coordination.sdk;

import blue.coordination.internal.CatchUpWorkStorageFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredCatchUpWorkSdkTest {
    @Test void actualNumberedSuccessorAndTwoRepresentationApplicationsMatchResidentThroughRepeatedColdIndexReplacement() throws Exception {
        // given
        try (var reference = new RootedTerminalTailSdkScenario(100_000L, false, 1);
             var stored = new RootedTerminalTailSdkScenario(100_000L, false, 1)) {
            var expected = new CatchUpWorkStorageFixture(reference.f.blue.advanced().rawEngine());
            var actual = new CatchUpWorkStorageFixture(stored.f.blue.advanced().rawEngine());
            // when
            var sourceBefore = stored.sourceState();
            // then
            assertEquals(reference.sourceState(), sourceBefore);
            assertEquals(reference.consumerProgress(), stored.consumerProgress());
            boolean complete = false;
            for (int step = 0; step < 32; step++) {
                var before = stored.consumerProgress(); var applications = actual.applications(); actual.reopen();
                assertEquals(before, stored.consumerProgress()); assertEquals(applications, actual.applications());
                var left = reference.f.blue.processing().processNext(reference.consumer);
                var right = stored.f.blue.processing().processNext(stored.consumer);
                assertFalse(left.blocked(), String.valueOf(left.diagnostic())); assertFalse(right.blocked(), String.valueOf(right.diagnostic()));
                assertEquals(left.managedEpochApplicationAttempts().stream().map(ManagedEpochApplicationAttempt::receipt).toList(),
                        right.managedEpochApplicationAttempts().stream().map(ManagedEpochApplicationAttempt::receipt).toList());
                assertEquals(reference.consumerProgress(), stored.consumerProgress(), "Full histories, receipt fields, ordered events and gas remain exact");
                assertEquals(expected.applications(), actual.applications(), "Keep complete original-work/receipt association, not just final values");
                assertEquals(sourceBefore, stored.sourceState()); assertEquals(sourceBefore, reference.sourceState());
                if (stored.f.blue.advanced().auditManagedDocumentReadiness(stored.consumer.id()).orElseThrow().ready()) { complete = true; break; }
            }
            assertTrue(complete, "The original 32-selection bound is unchanged");
            assertEquals(1, actual.numberedSuccessorApplications()); assertEquals(2, actual.representationApplications());
            assertEquals(stored.expectedConsumerChild, stored.consumer.snapshot().valueAt("/child").blueId());
            var finalEvidence = List.of(stored.consumerProgress(), stored.sourceState(), actual.applications()); actual.reopen();
            actual.rejectMismatchedApplicationEvidence();
            assertEquals(finalEvidence, List.of(stored.consumerProgress(), stored.sourceState(), actual.applications()));
            assertTrue(stored.f.blue.processing().processNext(stored.consumer).quiescent());
            assertEquals(finalEvidence, List.of(stored.consumerProgress(), stored.sourceState(), actual.applications()));
        }
    }
}
