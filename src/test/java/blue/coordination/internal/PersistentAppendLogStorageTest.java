package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PersistentAppendLogStorageTest {
    private static final PersistentAppendLogStorage.Limits LIMITS = new PersistentAppendLogStorage.Limits(16384, 4096, 1024, 4);
    private static final PersistentMapCodec<String> TEXT = new PersistentMapCodec<>() {
        public String identity() { return "test/exact-text/1"; }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    };

    @Test void workingSuffixRetainsOnlyNewCompleteChunksWithoutOpeningOldValues() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes, "events");
        var prefix = storage.open(null);
        for (int i = 0; i < 100; i++) prefix = prefix.appendAll(List.of("old" + i));
        byte[] original = prefix.storedRootDescriptor(); bytes.reads = 0; bytes.writes = 0;
        var working = prefix.workingCopy().appendAll(List.of("a", "a")).appendAll(List.of("b"));
        assertEquals(0, bytes.reads); assertEquals(0, bytes.writes); assertEquals(103, working.size());
        assertTrue(working.extendsLog(prefix)); assertEquals(0, bytes.reads);
        var staged = storage.retain(working);
        assertEquals(2, bytes.writes, "Preserve the exact two append chunks"); assertTrue(bytes.reads <= 1, "Only authenticate the selected prefix root");
        assertTrue(staged.extendsLog(prefix));
        assertEquals(working.values(), staged.values());
        assertEquals(List.of("a", "a", "b"), staged.values().subList(100, 103));
        assertArrayEquals(original, storage.retain(prefix.workingCopy()).storedRootDescriptor());
        assertEquals(100, storage.open(original).size());
    }

    @Test void failedWorkingSuffixStageKeepsOriginalPrefixAndExactMutableRowsForRetry() {
        var bytes = new DocumentSessionStorageTest.Bytes();
        var mutableRows = new PersistentMapCodec<StringBuilder>() {
            public String identity() { return "test/mutable-log/1"; }
            public byte[] encode(StringBuilder value) { return TEXT.encode(value.toString()); }
            public StringBuilder decode(byte[] value) { return new StringBuilder(TEXT.decode(value)); }
        };
        var storage = new PersistentAppendLogStorage<>(bytes, "events", mutableRows, LIMITS);
        var prefix = storage.open(null).appendAll(List.of(new StringBuilder("old")));
        byte[] original = prefix.storedRootDescriptor();
        var value = new StringBuilder("interim");
        var working = prefix.workingCopy().appendAll(List.of(value)).appendAll(List.of(new StringBuilder("tail")));
        value.replace(0, value.length(), "complete");
        bytes.failAtWrite = bytes.writes + 2;
        assertThrows(CoordinationObjectStorageException.class, () -> storage.retain(working));
        bytes.failAtWrite = -1;
        assertSame(value, working.values().get(1));
        var staged = storage.retain(working);
        assertEquals(List.of("old", "complete", "tail"), staged.values().stream().map(Object::toString).toList());
        assertEquals(List.of("old"), storage.open(original).values().stream().map(Object::toString).toList());
        assertTrue(staged.extendsLog(prefix));
    }

    @Test void coldChunksPreserveExactOrderMultiplicityBoundariesAndPrefixAcrossWrappers() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes, "events");
        var resident = PersistentAppendLog.<String>empty().appendAll(List.of("a", "a")).appendAll(List.of("b"));
        var retained = storage.retain(resident); var first = storage.retain(PersistentAppendLog.<String>empty().appendAll(List.of("a", "a")));
        var coldBytes = bytes.copy(); var cold = storage(coldBytes, "events"); var opened = cold.open(retained.storedRootDescriptor());
        assertEquals(resident.values(), opened.values()); assertEquals(3, opened.size());
        assertTrue(opened.extendsLog(cold.open(first.storedRootDescriptor())));
        assertTrue(opened.extendsLog(cold.open(null)));
        // Equal flattened rows with a different append boundary do not invent an original publication prefix.
        var otherBoundary = cold.retain(PersistentAppendLog.of(List.of("a")));
        assertFalse(opened.extendsLog(otherBoundary));
        var changed = opened.appendAll(List.of("b", "c"));
        assertArrayEquals(changed.storedRootDescriptor(), cold.open(opened.storedRootDescriptor()).appendAll(List.of("b", "c")).storedRootDescriptor(),
                "Retrying one exact immutable append retains the same physical result");
        assertEquals(List.of("a", "a", "b", "b", "c"), changed.values());
        assertEquals(List.of("a", "a", "b"), opened.values());
        assertTrue(changed.extendsLog(opened)); assertFalse(opened.extendsLog(changed));
        assertSame(changed, changed.appendAll(List.of()));
        int writes = coldBytes.writes;
        assertTrue(cold.open(changed.storedRootDescriptor()).extendsLog(cold.open(opened.storedRootDescriptor())));
        assertEquals(writes, coldBytes.writes, "Cold read and prefix qualification cannot retain objects");
    }

    @Test void oneAppendAfterLargeHistoryWritesOneChunkWithoutReadingOrCopyingItsPrefix() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes, "checkpoint");
        var log = storage.open(null);
        for (int i = 0; i < 1000; i++) log = log.appendAll(List.of(Integer.toString(i)));
        var coldBytes = bytes.copy(); var cold = storage(coldBytes, "checkpoint"); var prefix = cold.open(log.storedRootDescriptor());
        coldBytes.reads = 0; coldBytes.writes = 0;
        var appended = prefix.appendAll(List.of("1000"));
        assertEquals(1, coldBytes.writes); assertEquals(0, coldBytes.reads);
        assertTrue(appended.extendsLog(prefix)); assertTrue(coldBytes.reads <= 1);
        assertEquals(1001, appended.size()); assertEquals("0", appended.values().get(0)); assertEquals("1000", appended.values().get(1000));
    }

    @Test void missingCorruptWrongBindingAndBadAcknowledgmentNeverReturnAPublishableNewRoot() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes, "events");
        var initial = storage.open(null).appendAll(List.of("before")); var descriptor = initial.storedRootDescriptor();
        var coldBytes = bytes.copy(); var cold = storage(coldBytes, "events");
        assertThrows(CoordinationObjectStorageException.class, () -> storage(coldBytes, "checkpoints").open(descriptor));
        var root = coldBytes.records.keySet().iterator().next(); coldBytes.records.remove(root);
        assertThrows(CoordinationObjectStorageException.class, () -> cold.open(descriptor));
        coldBytes.records.put(root, bytes.records.get(root).clone()); coldBytes.records.get(root)[0] ^= 1;
        assertThrows(CoordinationObjectStorageException.class, () -> cold.open(descriptor));
        bytes.badAck = true; assertThrows(CoordinationObjectStorageException.class, () -> initial.appendAll(List.of("unpublished")));
        bytes.badAck = false; assertArrayEquals(descriptor, initial.storedRootDescriptor()); assertEquals(List.of("before"), initial.values());
        var retry = initial.appendAll(List.of("unpublished")); assertEquals(List.of("before", "unpublished"), retry.values());
        bytes.failRead = true; assertThrows(CoordinationObjectStorageException.class, retry::values);
        bytes.failRead = false; assertEquals(List.of("before", "unpublished"), retry.values());
    }

    @Test void physicalBoundsAndCanonicalDecodeDoNotBecomeSemanticEmptyHistory() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes, "events");
        var log = storage.open(null); assertThrows(CoordinationObjectStorageException.class, () -> log.appendAll(List.of("x".repeat(4097))));
        assertTrue(log.isEmpty());
        var stored = log.appendAll(List.of("MixedCase"));
        var coercing = new PersistentMapCodec<String>() {
            public String identity() { return TEXT.identity(); }
            public byte[] encode(String value) { return TEXT.encode(value); }
            public String decode(byte[] value) { return TEXT.decode(value).toLowerCase(Locale.ROOT); }
        };
        var wrong = new PersistentAppendLogStorage<>(bytes, "events", coercing, LIMITS).open(stored.storedRootDescriptor());
        assertThrows(CoordinationObjectStorageException.class, wrong::values);
        var small = new PersistentAppendLogStorage<>(bytes, "events", TEXT, new PersistentAppendLogStorage.Limits(128, 16, 1024, 1));
        assertThrows(CoordinationObjectStorageException.class, () -> small.open(stored.storedRootDescriptor()));
        assertEquals(List.of("MixedCase"), stored.values());
    }

    @Test void malformedPredecessorCoverageAndUnavailableSelectedPrefixFailBeforePartialOutput() throws Exception {
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes, "events");
        byte[] wrongCoverage = chunk(2, 2, "0".repeat(64));
        String wrongDigest = digest(wrongCoverage); bytes.records.put(wrongDigest, wrongCoverage);
        assertThrows(CoordinationObjectStorageException.class, () -> storage.open(storage.descriptor(new PersistentAppendLogStorage.Handle(wrongDigest, 2))));
        byte[] missingPrefix = chunk(2, 1, "0".repeat(64));
        String missingDigest = digest(missingPrefix); bytes.records.put(missingDigest, missingPrefix);
        var incomplete = storage.open(storage.descriptor(new PersistentAppendLogStorage.Handle(missingDigest, 2)));
        assertThrows(CoordinationObjectStorageException.class, incomplete::values);
        var unrelated = storage.open(null).appendAll(List.of("available"));
        assertEquals(List.of("available"), unrelated.values(), "A failed read scope must not poison other selected logs");
        assertFalse(unrelated.extendsLog(incomplete));
    }

    private static byte[] chunk(int size, int previousSize, String previousDigest) {
        return SessionStorageWire.encode(LIMITS.chunkBytes(), w -> {
            w.text("blue-coordination/append-chunk/1"); w.text("events"); w.text(TEXT.identity()); w.integer(size);
            w.bool(true); w.text(previousDigest); w.integer(previousSize); w.integer(1); w.bytes(TEXT.encode("tail"));
        });
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static PersistentAppendLogStorage<String> storage(DocumentSessionStorageTest.Bytes bytes, String binding) {
        return new PersistentAppendLogStorage<>(bytes, binding, TEXT, LIMITS);
    }
}
