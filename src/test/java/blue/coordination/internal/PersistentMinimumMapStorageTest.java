package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PersistentMinimumMapStorageTest {
    private static final String BINDING = "test/due-order/1";
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(8192, 128, 4096, 1024, 8);
    private static final PersistentMapCodec<Integer> KEYS = new PersistentMapCodec<>() {
        public String identity() { return "test/int32/1"; }
        public byte[] encode(Integer value) { return ByteBuffer.allocate(4).putInt(value).array(); }
        public Integer decode(byte[] bytes) { if (bytes.length != 4) throw new IllegalArgumentException("int32 width"); return ByteBuffer.wrap(bytes).getInt(); }
    };
    private static final PersistentMapCodec<String> VALUES = new PersistentMapCodec<>() {
        public String identity() { return "test/text/1"; }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public String decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    };

    @Test void coldMinimumSuccessorAndReadsPreserveExactResidentShapeAndDoNotWrite() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var resident = resident(1000);
        var stored = resident.storedCopy(BINDING, KEYS, VALUES, bytes, LIMITS); var copy = bytes.copy();
        var cold = open(copy, stored.storedRootDescriptor()); copy.reads = 0; int writes = copy.writes;
        assertEquals(resident.minimum(), cold.minimum()); assertTrue(copy.reads < 32);
        copy.reads = 0; assertEquals(resident.higherThan(498), cold.higherThan(498)); assertTrue(copy.reads < 32);
        for (int key : List.of(-1, 0, 1, 251, 499, 998, 999, 1000)) {
            assertEquals(resident.read(key), cold.read(key)); assertEquals(resident.higherThan(key), cold.higherThan(key));
        }
        assertEquals(writes, copy.writes); assertEquals(resident.size(), cold.size());
        cold.assertStructurallyValid();
    }

    @Test void randomizedColdMutationsPreserveAllLogicalCountersAndOldForks() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var resident = resident(128);
        var stored = resident.storedCopy(BINDING, KEYS, VALUES, bytes, LIMITS); var originalRoot = stored.storedRootDescriptor();
        var original = resident; var random = new Random(7146);
        for (int step = 0; step < 160; step++) {
            stored = open(bytes, stored.storedRootDescriptor());
            int key = random.nextInt(192); boolean remove = random.nextBoolean();
            var expected = remove ? resident.remove(key) : resident.put(key, "value-" + step);
            var actual = remove ? stored.remove(key) : stored.put(key, "value-" + step);
            assertEquals(expected.changed(), actual.changed()); assertEquals(expected.comparisons(), actual.comparisons());
            assertEquals(expected.copiedNodes(), actual.copiedNodes());
            if (!actual.changed()) assertSame(stored, actual.map());
            resident = expected.map(); stored = actual.map();
            assertEquals(resident.minimum(), stored.minimum()); assertEquals(resident.size(), stored.size());
            for (int i = 0; i < 5; i++) { int q = random.nextInt(200); assertEquals(resident.read(q), stored.read(q)); assertEquals(resident.higherThan(q), stored.higherThan(q)); }
        }
        stored.assertStructurallyValid();
        var old = open(bytes, originalRoot); assertEquals(original.minimum(), old.minimum());
        for (int key = 0; key < 130; key++) assertEquals(original.read(key), old.read(key));
    }

    @Test void missingSelectedSubtreeFailsWithoutHidingAvailableUnrelatedBranch() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var stored = resident(1000).storedCopy(BINDING, KEYS, VALUES, bytes, LIMITS);
        var traced = new TracedBytes(bytes.copy()); var cold = open(traced, stored.storedRootDescriptor());
        traced.selected.clear(); assertEquals(0, cold.minimum().entry().getKey()); var left = new HashSet<>(traced.selected);
        traced.selected.clear(); assertEquals("v999", cold.read(999).value()); left.removeAll(traced.selected);
        assertFalse(left.isEmpty(), "Fixture must identify a left-only addressed node");
        String missing = left.iterator().next(); byte[] original = traced.delegate.records.remove(missing);
        assertThrows(CoordinationObjectStorageException.class, cold::minimum);
        assertEquals("v999", cold.read(999).value());
        traced.delegate.records.put(missing, original); assertEquals(0, cold.minimum().entry().getKey());
        traced.delegate.records.get(missing)[0] ^= 1;
        assertThrows(CoordinationObjectStorageException.class, cold::minimum);
        assertEquals("v999", cold.read(999).value());
    }

    @Test void failedWritesWrongBindingAndPhysicalBoundsCannotReturnAnAcceptedNewRoot() {
        var bytes = new DocumentSessionStorageTest.Bytes(); var stored = resident(100).storedCopy(BINDING, KEYS, VALUES, bytes, LIMITS);
        byte[] old = stored.storedRootDescriptor(); bytes.badAck = true;
        var before = stored;
        assertThrows(CoordinationObjectStorageException.class, () -> before.put(-1, "first"));
        bytes.badAck = false; assertArrayEquals(old, stored.storedRootDescriptor()); assertEquals(0, stored.minimum().entry().getKey());
        var changed = stored.put(-1, "first").map(); assertEquals(-1, changed.minimum().entry().getKey());
        assertArrayEquals(changed.storedRootDescriptor(), open(bytes, old).put(-1, "first").map().storedRootDescriptor());
        assertThrows(CoordinationObjectStorageException.class, () -> PersistentMinimumMap.stored(Comparator.<Integer>naturalOrder(), "other-order", KEYS, VALUES, bytes, LIMITS, old));
        assertThrows(CoordinationObjectStorageException.class, () -> before.put(-2, "x".repeat(4097)));
        bytes.failRead = true; assertThrows(CoordinationObjectStorageException.class, changed::minimum);
        bytes.failRead = false; assertEquals(-1, changed.minimum().entry().getKey());
    }

    private static PersistentMinimumMap<Integer, String> resident(int size) {
        PersistentMinimumMap<Integer, String> result = PersistentMinimumMap.empty(Comparator.naturalOrder());
        for (int i = 0; i < size; i++) result = result.put(i, "v" + i).map();
        return result;
    }
    private static PersistentMinimumMap<Integer, String> open(CoordinationImmutableObjectStore bytes, byte[] root) {
        return PersistentMinimumMap.stored(Comparator.naturalOrder(), BINDING, KEYS, VALUES, bytes, LIMITS, root);
    }
    private static final class TracedBytes implements CoordinationImmutableObjectStore {
        final DocumentSessionStorageTest.Bytes delegate; final Set<String> selected = new HashSet<>();
        TracedBytes(DocumentSessionStorageTest.Bytes delegate) { this.delegate = delegate; }
        public byte[] putIfAbsent(String id, byte[] bytes) { return delegate.putIfAbsent(id, bytes); }
        public Optional<byte[]> get(String id, int max) { selected.add(id); return delegate.get(id, max); }
    }
}
