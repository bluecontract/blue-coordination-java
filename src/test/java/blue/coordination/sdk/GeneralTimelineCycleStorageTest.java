package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** The full original finite-cycle, complete-owner, split and cold evidence oracle with general input. */
final class GeneralTimelineCycleStorageTest {
    @Test void ordinaryGeneralInputKeepsWholeCycleAtomicAcrossColdOwners() throws Exception {
        // given
        var scenario = new RootedCoordinationStorageTest().generalCycleEntries();
        // when
        org.junit.jupiter.api.function.Executable execute = scenario::coldReciprocalCycleMatchesFullFiniteAndSplitEvidenceAfterProducerCloses;
        // then
        assertDoesNotThrow(execute);
    }
}
