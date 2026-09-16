package blue.coordination.sdk;

import blue.coordination.internal.DocumentStoreStorageFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredDocumentStoreSdkTest {
    @Test void actualSuccessorAndRepresentationApplicationsRemainExactInTheCompleteColdStore() throws Exception {
        try (var scenario = new RootedTerminalTailSdkScenario(100_000L, false, 1)) {
            var storage = new DocumentStoreStorageFixture(scenario.f.blue.advanced().rawEngine());
            var before = scenario.consumerProgress(); storage.verifyCurrent(false); assertEquals(before, scenario.consumerProgress());
            scenario.finishExactlyTwoPositions();
            var complete = scenario.consumerProgress(); assertEquals(3, storage.verifyCurrent(true));
            assertEquals(complete, scenario.consumerProgress());
        }
    }
}
