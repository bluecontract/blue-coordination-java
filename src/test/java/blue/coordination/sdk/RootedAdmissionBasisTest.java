package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public admission cases at empty and actual-entry logical frontiers. */
final class RootedAdmissionBasisTest {
    @Test void fromNowAtEmptyJournalRemainsAUsablePublicAdmission() {
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.blue.documents().admitStaticProcessEmbedded(
                    "name: Rooted empty frontier\ncounter: 0\n", ActivationPolicy.fromNow()).document("root");
            assertEquals(0L, document.snapshot().longAt("/counter"));
            assertEquals(0L, fixture.blue.advanced().auditDocument(document.id()).epoch());
        }
    }
}
