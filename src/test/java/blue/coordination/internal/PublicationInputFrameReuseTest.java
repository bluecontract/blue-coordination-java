package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact existing SDK histories; no fabricated result, policy, execution proof or decoder shortcut. */
final class PublicationInputFrameReuseTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static ContractsClosurePublicationReceipt historical, live;

    @BeforeAll static void actualRootedRepresentationPublications() throws Exception {
        // Reuse the existing package-private SDK fixture without publishing a test-only SDK API.
        var type = Class.forName("blue.coordination.sdk.RootedTerminalTailSdkScenario");
        var constructor = type.getDeclaredConstructor(long.class, boolean.class, int.class);
        constructor.setAccessible(true);
        try (var scenario = (AutoCloseable) constructor.newInstance(100_000L, false, 1)) {
            var f = field(scenario, "f");
            var blue = (BlueCoordination) field(f, "blue");
            var consumer = (DocumentHandle) field(scenario, "consumer");
            blue.processing().processNext(consumer);
            blue.processing().processNext(consumer);
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var receipts = engine.documents().storedState().closurePublicationReceipts().values();
            historical = receipts.stream().filter(row -> row.rootedTerminalEvidence() != null
                    && row.rootedTerminalEvidence().storedState().historicalWork() != null
                    && row.rootedTerminalEvidence().storedState().historicalWork().isRepresentationApplication())
                    .findFirst().orElseThrow();
            live = receipts.stream().filter(row -> row.commits() && row.rootedTerminalEvidence() != null
                    && row.rootedTerminalEvidence().storedState().historicalWork() == null).findFirst().orElseThrow();
        }
        assertSame(historical.managedSurfaceEvidence().originalInvocation(), historical.rootedTerminalEvidence().input());
        assertSame(live.managedSurfaceEvidence().originalInvocation(), live.rootedTerminalEvidence().input());
    }

    @Test void oneReceiptCaptureEncodesItsSharedInputOnceAndEqualDistinctInputsIndependently() {
        var counts = new HashMap<String, Integer>();
        try (var cache = new RootedStorageCache(256L * 1024 * 1024, 1024, 128L * 1024 * 1024)) {
            var codec = measured(cache, counts);
            var storage = storage();
            for (var receipt : List.of(historical, live)) {
                counts.clear();
                byte[] actual = codec.encodePublication(receipt, storage::retainView);
                assertEquals(1, counts.get("invocation-encode"));
                // Equivalent separately decoded input must not hit the identity-only encoder scope.
                var detached = detachedTerminalInput(receipt);
                assertNotSame(detached.managedSurfaceEvidence().originalInvocation(), detached.rootedTerminalEvidence().input());
                counts.clear();
                assertArrayEquals(actual, codec.encodePublication(detached, storage::retainView),
                        "Sharing changes no physical byte or field order");
                assertEquals(2, counts.get("invocation-encode"));
                counts.clear();
                assertArrayEquals(actual, codec.encodePublication(receipt, storage::retainView));
                assertEquals(1, counts.get("invocation-encode"), "No encoder result survives into a later call");
            }
        }
    }

    @Test void coldExactRepeatedFrameIsDecodedOnceWithoutSkippingEnvelopeValidation() {
        var counts = new HashMap<String, Integer>();
        var codec = measured(null, counts);
        var storage = storage();
        byte[] bytes = codec.encodePublication(historical, storage::retainView);
        try (var scope = storage.openScope()) {
            for (int call = 0; call < 2; call++) {
                counts.clear();
                var restored = codec.decodePublication(bytes, scope);
                assertEquals(1, counts.get("invocation-decode"));
                assertEquals(0, counts.getOrDefault("invocation-encode", 0),
                        "Complete envelope equality reuses invocation bytes already checked by its full decoder");
                assertSame(restored.managedSurfaceEvidence().originalInvocation(), restored.rootedTerminalEvidence().input());
                assertArrayEquals(bytes, codec.encodePublication(restored, scope::addressOf));
                assertEquals(1, counts.get("invocation-encode"),
                        "The decoder's subframe certificate does not survive into a later encode call");
                assertEquals(historical.attempt().processResult().gasTraceIdentity(), restored.attempt().processResult().gasTraceIdentity());
                assertEquals(historical.attempt().processResult().totalGas(), restored.attempt().processResult().totalGas());
            }
            assertThrows(CoordinationObjectStorageException.class,
                    () -> codec.decodePublication(Arrays.copyOf(bytes, bytes.length - 1), scope));
            assertThrows(CoordinationObjectStorageException.class,
                    () -> codec.decodePublication(Arrays.copyOf(bytes, bytes.length + 1), scope));
        }
    }

    @Test void changedSecondInputIsNotAcceptedAsTheFirstVerifiedInput() {
        var counts = new HashMap<String, Integer>();
        var codec = measured(null, counts);
        var storage = storage();
        byte[] bytes = codec.encodePublication(historical, storage::retainView);
        byte[] input = new ClosureExecutionEvidenceStorageCodec(MAX, 256).encodeInvocation(historical.rootedTerminalEvidence().input());
        int first = indexOf(bytes, input, 0), second = indexOf(bytes, input, first + input.length);
        assertTrue(first >= 0 && second > first);
        assertEquals(-1, indexOf(bytes, input, second + input.length));
        byte[] corrupt = bytes.clone(); corrupt[second] ^= 1;
        try (var scope = storage.openScope()) {
            counts.clear();
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decodePublication(corrupt, scope));
            assertEquals(1, counts.get("invocation-decode"), "First input verified; changed second frame must fail its own decoder");
            counts.clear();
            assertNotNull(codec.decodePublication(bytes, scope));
            assertEquals(1, counts.get("invocation-decode"), "Failed envelope lends no proof to another call");
        }
    }

    @Test void failedCaptureAndTightEnvelopeBoundsDoNotRetainAnInputAcrossCalls() {
        var counts = new HashMap<String, Integer>();
        var codec = measured(null, counts);
        byte[] bytes = codec.encodePublication(historical, storage()::retainView);
        assertEquals(1, counts.get("invocation-encode"));
        var bounded = new PublicationReceiptStorageCodec(bytes.length - 1, 256, null,
                (kind, value) -> counts.merge(kind, 1, Integer::sum));
        for (int call = 0; call < 2; call++) {
            counts.clear();
            assertThrows(CoordinationObjectStorageException.class,
                    () -> bounded.encodePublication(historical, storage()::retainView));
            assertEquals(1, counts.get("invocation-encode"),
                    "Input was fully encoded before the late outer bound failed; retry encodes it again");
        }
        counts.clear();
        assertArrayEquals(bytes, codec.encodePublication(historical, storage()::retainView));
        assertEquals(1, counts.get("invocation-encode"));
    }

    private static ContractsClosurePublicationReceipt detachedTerminalInput(ContractsClosurePublicationReceipt value) {
        var codec = new ClosureExecutionEvidenceStorageCodec(MAX, 256);
        var old = value.rootedTerminalEvidence().storedState();
        var input = codec.decodeInvocation(codec.encodeInvocation(old.input()));
        var terminal = RootedTerminalEvidence.restoreStored(new RootedTerminalEvidence.StoredState(input,
                old.managedDraftPlan(), old.rooted(), old.executedInvocationIdentity(), old.historicalWorkIdentity(),
                old.historicalWork(), old.requiredTimelineIds()));
        return new ContractsClosurePublicationReceipt(value.publicationIdentity(), value.documentIds(), value.attempt(),
                value.automaticRetryCount(), value.managedSurfaceEvidence(), value.rejectedDraftPlan(), terminal);
    }

    private static PublicationReceiptStorageCodec measured(RootedStorageCache cache, Map<String, Integer> counts) {
        return new PublicationReceiptStorageCodec(MAX, 256, cache, (kind, value) -> counts.merge(kind, 1, Integer::sum));
    }
    private static DocumentSessionStorage storage() { return new DocumentSessionStorage(new DocumentSessionStorageTest.Bytes(), LIMITS); }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static int indexOf(byte[] bytes, byte[] selected, int start) {
        for (int offset = start; offset <= bytes.length - selected.length; offset++)
            if (Arrays.equals(bytes, offset, offset + selected.length, selected, 0, selected.length)) return offset;
        return -1;
    }
}
