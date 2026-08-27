package blue.coordination.internal;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ManagedEpochReceipt;

import java.util.List;

/** Receipt anchors for legacy unit fixtures which bypass closure admission. */
final class ManagedEpochReceiptTestFixtures {
    private ManagedEpochReceiptTestFixtures() {
    }

    static void seedInitialization(
            InMemoryDocumentStore store,
            DocumentSession session,
            long identitySeed) {
        DocumentRevision initialization = session.revision(0L);
        ManagedEpochReceipt receipt = ManagedEpochReceipt.identified(
                session.documentId(),
                0L,
                DocumentRevision.Kind.INITIALIZATION,
                null,
                initialization.after(),
                hash(identitySeed),
                null,
                initialization.sourceOrderKey().orElseThrow(),
                hash(Math.addExact(identitySeed, 1L)),
                hash(Math.addExact(identitySeed, 2L)),
                List.of(),
                initialization.processingGas());
        store.retainManagedEpochReceiptForTesting(receipt);
    }

    private static String hash(long value) {
        return "sha256:" + String.format("%064x", value);
    }
}
