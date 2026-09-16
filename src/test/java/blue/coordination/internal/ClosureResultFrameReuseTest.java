package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

/** Real complete results, not body-only or logical-ID cache entries. */
final class ClosureResultFrameReuseTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final ClosureProcessResultStorageCodec RAW = new ClosureProcessResultStorageCodec(MAX, 256);

    @Test void sameCompleteFrameSharesOnlyVerifiedResultAndEncodingAcrossCodecOwners() throws Exception {
        var original = result(false); byte[] frame = RAW.encode(original);
        var decodes = new AtomicInteger(); var encodes = new AtomicInteger();
        try (var cache = cache(4)) {
            var first = new StoredClosureResultCodec(MAX, 256, cache, decodes::incrementAndGet, encodes::incrementAndGet);
            assertArrayEquals(frame, first.encode(original));
            assertEquals(0, cache.statistics().retainedEntries(), "Encoding processor output grants no certificate");
            var restored = first.decode(frame);
            assertNotSame(original, restored); assertEquals(1, decodes.get()); assertEquals(1, encodes.get(),
                    "Language verifies canonical bytes internally; there is no second C encoder round-trip");
            var second = new StoredClosureResultCodec(MAX, 256, cache,
                    () -> fail("Warm second owner must not decode"), () -> fail("Warm exact identity must not encode"));
            assertSame(restored, second.decode(frame.clone()));
            assertArrayEquals(frame, second.encode(restored));
            assertArrayEquals(frame, second.encode(restored, ignored -> { throw new AssertionError("Unused proof lookup"); }));
            assertThrows(NullPointerException.class, () -> second.encode(restored, null));
            first.encode(original); assertEquals(2, encodes.get(), "Equal original output is still uncertified");
        }
    }

    @Test void bytesAndPublicDocumentAndEventCopiesCannotMutateTheRetainedResult() throws Exception {
        byte[] frame = RAW.encode(result(false)); byte[] input = frame.clone();
        try (var cache = cache(4)) {
            var codec = new StoredClosureResultCodec(MAX, 256, cache); var restored = codec.decode(input);
            input[0] ^= 1; byte[] returned = codec.encode(restored); returned[0] ^= 1;
            restored.resultingDocuments().get(0).document().name("caller body mutation");
            assertFalse(restored.publicEvents().isEmpty()); restored.publicEvents().get(0).event().name("caller event mutation");
            assertSame(restored, codec.decode(frame)); assertArrayEquals(frame, codec.encode(restored));
            assertArrayEquals(frame, RAW.encode(restored), "Independent raw encoding still matches the certified frame");
        }
    }

    @Test void corruptFramesNeverPoisonTheWarmFamilyOrBorrowItsValidation() throws Exception {
        byte[] frame = RAW.encode(result(false));
        try (var cache = cache(4)) {
            var codec = new StoredClosureResultCodec(MAX, 256, cache); var restored = codec.decode(frame);
            byte[] corrupt = frame.clone(); corrupt[corrupt.length / 2] ^= 1;
            for (byte[] bad : new byte[][]{corrupt, Arrays.copyOf(frame, frame.length - 1), Arrays.copyOf(frame, frame.length + 1)})
                assertThrows(RuntimeException.class, () -> codec.decode(bad));
            assertEquals(1, cache.statistics().retainedEntries());
            assertSame(restored, codec.decode(frame)); assertArrayEquals(frame, codec.encode(restored));
        }
    }

    @Test void byteAndDepthProfilesStaySeparateAfterWideProfileWarmsTheFrame() throws Exception {
        byte[] frame = RAW.encode(result(false));
        try (var cache = cache(4)) {
            var wide = new StoredClosureResultCodec(MAX, 256, cache); var restored = wide.decode(frame);
            var encodes = new AtomicInteger();
            var different = new StoredClosureResultCodec(MAX, 255, cache, null, encodes::incrementAndGet);
            assertNotSame(restored, different.decode(frame)); int previous = encodes.get();
            assertArrayEquals(frame, different.encode(restored)); assertEquals(previous + 1, encodes.get(),
                    "Another profile's exact object must take the raw encoder");
            assertThrows(RuntimeException.class, () -> new StoredClosureResultCodec(frame.length - 1, 256, cache).decode(frame));
            assertThrows(RuntimeException.class, () -> new StoredClosureResultCodec(frame.length - 1, 256, cache).encode(restored));
            assertThrows(RuntimeException.class, () -> new StoredClosureResultCodec(MAX, 1, cache).decode(frame));
            assertSame(restored, wide.decode(frame));
        }
    }

    @Test void equalDocumentCounterDoesNotCollapseDifferentInvocationFrames() throws Exception {
        var first = result(false, 100); var second = result(false, 200);
        assertEquals(first.resultingDocuments().get(0).document().getProperties().get("counter").getValue(),
                second.resultingDocuments().get(0).document().getProperties().get("counter").getValue());
        assertNotEquals(first.invocationIdentity(), second.invocationIdentity());
        byte[] a = RAW.encode(first), b = RAW.encode(second); assertFalse(Arrays.equals(a, b));
        try (var cache = cache(4)) {
            var codec = new StoredClosureResultCodec(MAX, 256, cache);
            var decodedA = codec.decode(a); var decodedB = codec.decode(b);
            assertNotSame(decodedA, decodedB); assertSame(decodedA, codec.decode(a)); assertSame(decodedB, codec.decode(b));
            assertArrayEquals(a, codec.encode(decodedA)); assertArrayEquals(b, codec.encode(decodedB));
        }
    }

    @Test void failureInTheColdLoaderDoesNotPublishAResult() throws Exception {
        byte[] frame = RAW.encode(result(false)); var decodes = new AtomicInteger(); var encodes = new AtomicInteger();
        try (var cache = cache(4)) {
            var codec = new StoredClosureResultCodec(MAX, 256, cache, () -> {
                if (decodes.incrementAndGet() == 1) throw new IllegalStateException("Injected cold loader failure");
            }, encodes::incrementAndGet);
            assertThrows(IllegalStateException.class, () -> codec.decode(frame));
            assertEquals(0, cache.statistics().retainedEntries()); assertEquals(1, cache.statistics().failedLoads());
            var restored = codec.decode(frame); assertEquals(2, decodes.get()); assertEquals(0, encodes.get());
            assertArrayEquals(frame, codec.encode(restored)); assertEquals(0, encodes.get());
        }
    }

    @Test void disabledOversizeEvictedAndClearedFramesUseCompleteFallback() throws Exception {
        byte[] frame = RAW.encode(result(false));
        for (var cache : new RootedStorageCache[]{new RootedStorageCache(0, 0, 0), new RootedStorageCache(1024, 4, 1)}) {
            try (cache) {
                var codec = new StoredClosureResultCodec(MAX, 256, cache);
                assertNotSame(codec.decode(frame), codec.decode(frame));
                assertEquals(0, cache.statistics().retainedEntries());
            }
        }
        try (var cache = cache(1)) {
            var encodes = new AtomicInteger();
            var codec = new StoredClosureResultCodec(MAX, 256, cache, null, encodes::incrementAndGet);
            var restored = codec.decode(frame); int count = encodes.get();
            codec.encode(restored); assertEquals(count, encodes.get());
            // An unrelated retained frame evicts the result without supplying any result authority.
            cache.decode("test/other-frame", new byte[]{1}, () -> "other");
            codec.encode(restored); assertEquals(count + 1, encodes.get());
            var again = codec.decode(frame); assertNotSame(restored, again);
            cache.clear(); int afterClear = encodes.get(); codec.encode(again);
            assertEquals(afterClear + 1, encodes.get()); assertEquals(0, cache.statistics().retainedEntries());
        }
        var raw = new StoredClosureResultCodec(MAX, 256, null);
        assertNotSame(raw.decode(frame), raw.decode(frame));
        assertArrayEquals(frame, raw.encode(raw.decode(frame)));
    }

    @Test void cachedRollbackReceiptCannotBecomeOriginalPublishedRows() throws Exception {
        var rollback = result(true); assertFalse(rollback.commits()); byte[] frame = RAW.encode(rollback);
        var objects = new DocumentSessionStorageTest.Bytes(); String address = digest(frame);
        objects.putIfAbsent(address, frame);
        try (var cache = cache(4)) {
            var receipts = new CoreReceiptStorageCodec(MAX, 256, cache);
            byte[] attempt = SessionStorageWire.encode(MAX, out -> receipts.attempt(out,
                    blue.language.processor.closure.ClosureAttemptResult.complete(rollback)));
            var restored = SessionStorageWire.decode(attempt, MAX, receipts::attempt).processResult();
            assertFalse(restored.commits());
            assertSame(restored, new StoredClosureResultCodec(MAX, 256, cache).decode(frame));
            long hits = cache.statistics().hits();
            for (int i = 0; i < 2; i++) try (var rows = new StoredResultRows(objects, LIMITS, cache)) {
                var failure = assertThrows(CoordinationObjectStorageException.class, () -> rows.openResult(address));
                assertTrue(failure.getMessage().contains("Noncommitting result"));
            }
            assertTrue(cache.statistics().hits() >= hits + 2, "Owner-specific rejection must happen after warm hits");
        }
    }

    @Test void twoOwnersStillAuthenticatePhysicalRowsAndRegisterTheirOriginalMembers() throws Exception {
        var original = result(false); byte[] frame = RAW.encode(original);
        var objects = new DocumentSessionStorageTest.Bytes(); String address; byte[] event, checkpoint;
        try (var writer = new StoredResultRows(objects, LIMITS)) {
            address = writer.retain(original);
            event = writer.outboxCodec().encode(original.publicEvents().get(0));
            checkpoint = writer.checkpointCodec().encode(original.checkpointWrites().get(0));
        }
        try (var cache = cache(4)) {
            var common = new StoredClosureResultCodec(MAX, 256, cache); var restored = common.decode(frame);
            for (int i = 0; i < 2; i++) try (var rows = new StoredResultRows(objects, LIMITS, cache)) {
                assertThrows(CoordinationObjectStorageException.class, () -> rows.outboxCodec().encode(restored.publicEvents().get(0)));
                assertSame(restored, rows.openResult(address));
                assertSame(restored.publicEvents().get(0), rows.outboxCodec().decode(event));
                assertArrayEquals(checkpoint, rows.checkpointCodec().encode(restored.checkpointWrites().get(0)));
                assertThrows(CoordinationObjectStorageException.class,
                        () -> rows.outboxCodec().encode(RAW.decode(frame).publicEvents().get(0)));
            }
            objects.records.remove(address);
            try (var rows = new StoredResultRows(objects, LIMITS, cache)) {
                assertThrows(CoordinationObjectStorageException.class, () -> rows.openResult(address));
            }
            byte[] corrupt = frame.clone(); corrupt[0] ^= 1; objects.records.put(address, corrupt);
            try (var rows = new StoredResultRows(objects, LIMITS, cache)) {
                assertThrows(CoordinationObjectStorageException.class, () -> rows.openResult(address));
            }
            objects.records.put(address, frame);
            assertTrue(frame.length >= 1024);
            new StoredClosureResultCodec(frame.length, 256, cache).decode(frame);
            long hits = cache.statistics().hits();
            try (var rows = new StoredResultRows(objects, new DocumentSessionStorage.Limits(frame.length, 256, frame.length), cache)) {
                var failure = assertThrows(CoordinationObjectStorageException.class, () -> rows.openResult(address));
                assertTrue(failure.getMessage().contains("scope byte bound"));
            }
            assertTrue(cache.statistics().hits() > hits, "Even a warm result is charged to the new owner scope");
        }
    }

    private static ClosureProcessResult result(boolean rollback) throws Exception {
        return result(rollback, 100);
    }

    private static ClosureProcessResult result(boolean rollback, long clock) throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var root = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.clock = clock;
            var entry = f.append(root, "rcp2/source", "tick");
            var exact = f.engine.auditTimelineEntry(entry.blueId()).orElseThrow();
            if (rollback) {
                var input = f.engine.contractsClosureAdapter().captureRoot(root.id(), exact).invocations().get(0).input();
                var successful = new BlueClosureContracts(f.engine.runtime().documentProcessor()).processClosure(input).processResult();
                assertTrue(successful.commits()); assertTrue(successful.totalGas() > 1);
                var drain = f.engine.processRootInput(root.id(), exact,
                        ContractsExecutionPolicy.exactSharedGas(successful.totalGas() - 1, "complete-frame-tight-budget"));
                return drain.contractsAttemptsFor(entry.blueId()).get(0).attempt().processResult();
            }
            f.process(root, entry); return f.engine.documents().require(root.id()).rootedView().result();
        }
    }

    private static RootedStorageCache cache(int entries) { return new RootedStorageCache(512L * 1024 * 1024, entries, 256L * 1024 * 1024); }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
