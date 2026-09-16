package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only observation of actual SDK-selected drains; never replaces state or invents a receipt. */
public final class ReceiptStorageFixture {
    private static final int MAX = 32 * 1024 * 1024;
    private final DefaultCoordinationEngine engine;
    private final CoreReceiptStorageCodec core = new CoreReceiptStorageCodec(MAX, 256);
    private final PublicationReceiptStorageCodec publications = new PublicationReceiptStorageCodec(MAX, 256);
    private final DocumentSessionStorage sessions = new DocumentSessionStorage(new DocumentSessionStorageTest.Bytes(),
            new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024));
    private final List<byte[]> retained = new ArrayList<>();
    private final Map<String, ManagedEpochApplicationWork> originalWork = new HashMap<>();

    public ReceiptStorageFixture(CoordinationEngine engine) { this.engine = (DefaultCoordinationEngine) engine; }

    public ProcessingDrainReceipt next(DocumentId root) {
        var raw = engine.processNextRoot(root);
        raw.managedEpochApplicationAttempts().forEach(attempt -> originalWork.put(attempt.work().workIdentity(), attempt.work()));
        byte[] bytes = core.encodeDrain(raw, originalWork::get); retained.add(bytes);
        var before = engine.metricsSnapshot().phaseNanos();
        var cold = core.decodeDrain(bytes);
        assertEquals(before, engine.metricsSnapshot().phaseNanos(), "Decode performs no PROCESS or provider phase");
        assertArrayEquals(bytes, core.encodeDrain(cold, originalWork::get));
        assertEquals(raw.managedEpochApplications().size(), cold.managedEpochApplications().size());
        var applicationCodec = new ManagedApplicationStorageCodec(MAX, 256);
        for (int index = 0; index < raw.managedEpochApplications().size(); index++) {
            var original = raw.managedEpochApplications().get(index); var restored = cold.managedEpochApplications().get(index);
            assertArrayEquals(applicationCodec.encode(original, originalWork.get(original.workIdentity())),
                    applicationCodec.encode(restored, originalWork.get(restored.workIdentity())));
        }
        assertEquals(raw.managedEpochApplicationAttempts().size(), cold.managedEpochApplicationAttempts().size());
        assertEquals(raw.rootedRetainedAttempts().size(), cold.rootedRetainedAttempts().size());
        for (var application : raw.managedEpochApplications()) {
            checkPublication(engine.documents().closureReceiptForApplication(application).orElseThrow());
            assertThrows(CoordinationObjectStorageException.class, () -> core.encodeDrain(raw, ignored -> null));
        }
        for (var retained : raw.rootedRetainedAttempts()) {
            var publication = engine.documents().closurePublicationReceipt(retained.attempt().publicationIdentity()).orElseThrow();
            assertTrue(publication.rootedTerminalEvidence().identifiesHistoricalWork(retained.work()));
            checkPublication(publication);
        }
        for (var attempts : raw.contractsAttemptsByEntry().values()) for (var attempt : attempts)
            engine.documents().closurePublicationReceipt(attempt.publicationIdentity()).ifPresent(this::checkPublication);
        return cold;
    }

    private void checkPublication(ContractsClosurePublicationReceipt original) {
        var results = new ClosureProcessResultStorageCodec(MAX, 256);
        byte[] bytes = publications.encodePublication(original, sessions::retainView);
        var before = engine.metricsSnapshot().phaseNanos();
        try (var scope = sessions.openScope()) {
            var cold = publications.decodePublication(bytes, scope);
            assertEquals(before, engine.metricsSnapshot().phaseNanos());
            assertArrayEquals(bytes, publications.encodePublication(cold, scope::addressOf));
            assertArrayEquals(results.encode(original.attempt().processResult()), results.encode(cold.attempt().processResult()));
            assertEquals(original.rootedTerminalEvidence().requiredTimelineIds(), cold.rootedTerminalEvidence().requiredTimelineIds());
            assertEquals(original.rootedTerminalEvidence().storedState().historicalWorkIdentity(),
                    cold.rootedTerminalEvidence().storedState().historicalWorkIdentity());
        }
    }

    /** Producer may already be closed: only pinned bytes and original work packets are used. */
    public void reopenAll() {
        var fresh = new CoreReceiptStorageCodec(MAX, 256);
        for (byte[] bytes : retained) assertArrayEquals(bytes, fresh.encodeDrain(fresh.decodeDrain(bytes), originalWork::get));
    }
}
