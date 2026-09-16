package blue.coordination.internal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Private append/index primitives: structural sharing is not permission to ignore selected records. */
final class SessionHistoryCollectionsTest {
    @Test void listCopyAndIteratorKeepTheOldExactPrefixWhileOnlyTheNewCursorAppends() {
        var original = SessionHistoryList.<String>empty();
        original.add("zero"); original.add("one"); original.add("two");
        var snapshot = original.copy(); var iterator = original.iterator();
        var range = original.subList(1, 3);
        assertSame(original.root(), snapshot.root(), "Copy shares the immutable root without enumerating its values");
        original.add("three"); snapshot.add("other-three");
        assertEquals(List.of("zero", "one", "two"), drain(iterator));
        assertEquals(List.of("one", "two"), range);
        assertEquals(List.of("zero", "one", "two", "three"), original);
        assertEquals(List.of("zero", "one", "two", "other-three"), snapshot);
        assertNotSame(original.root(), snapshot.root());
        assertThrows(UnsupportedOperationException.class, () -> range.add("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> original.set(0, "replaced"));
        assertThrows(UnsupportedOperationException.class, () -> original.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> original.add(0, "inserted"));
        assertThrows(IndexOutOfBoundsException.class, () -> original.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> original.get(original.size()));
    }

    @Test void physicalProjectedListLoadsOnlyTheSelectedPointOrRangeAndCopyAppendDoNotLoadOldValues() {
        var bytes = new RootedHistoryAccessObjects();
        var persisted = PersistentOrderedMap.stored(Long::compare, "test/history-ordinal/1", LONGS,
                StoredMapFixtures.TEXT, bytes, StoredMapFixtures.LIMITS, null);
        for (long i = 0; i < 50; i++) persisted = persisted.put(i, "v" + i).map();
        var cold = PersistentOrderedMap.stored(Long::compare, "test/history-ordinal/1", LONGS,
                StoredMapFixtures.TEXT, bytes.detachedCopy(), StoredMapFixtures.LIMITS, persisted.storedRootDescriptor());
        var projected = new ArrayList<Long>();
        var projection = cold.projectValues((key, value) -> { projected.add(key); return value; });
        var history = SessionHistoryList.fromRoot(projection.open());
        assertEquals(50, history.size()); assertTrue(projected.isEmpty());
        assertEquals("v49", history.get(49)); assertEquals(List.of(49L), projected);
        projected.clear(); var copy = history.copy();
        assertSame(history.root(), copy.root()); assertTrue(projected.isEmpty());
        copy.add("v50"); assertTrue(projected.isEmpty(), "Append may read index paths, not old payload values");
        assertEquals("v50", copy.get(50)); assertTrue(projected.isEmpty());
        assertEquals(50, history.size());
        assertEquals(List.of("v10", "v11", "v12"), copy.subList(10, 13));
        assertEquals(List.of(10L, 11L, 12L), projected);
        projected.clear(); var retainedNew = new ArrayList<Long>();
        var staged = projection.stage(copy.root(), (key, value) -> { retainedNew.add(key); return value; });
        assertEquals(List.of(50L), retainedNew, "Only the appended value is retained; old physical subtrees remain shared");
        assertTrue(projected.isEmpty()); assertEquals("v50", staged.get(50L)); assertNull(cold.get(50L));
    }

    @Test void selectedListGapAndAppendCollisionFailWithoutReplacingTheRetainedRoot() {
        var root = PersistentOrderedMap.<Long, String>empty(Long::compare).put(0L, "zero").map().put(2L, "two").map();
        var invalid = SessionHistoryList.fromRoot(root);
        assertEquals("zero", invalid.get(0));
        assertThrows(NullPointerException.class, () -> invalid.get(1));
        assertThrows(IllegalStateException.class, () -> invalid.subList(0, 2));
        assertThrows(IllegalStateException.class, () -> invalid.add("would-overwrite-two"));
        assertSame(root, invalid.root()); assertEquals("two", root.get(2L));
    }

    @Test void mapCopiesAndCapturedIterationPreserveInsertionOrderWhenAValueIsUpdated() {
        var original = SessionHistoryMap.<String, Integer>empty(Comparator.naturalOrder());
        original.put("z", 1); original.put("a", 2); original.put("m", 3);
        var copy = original.copy(); var iterator = original.entrySet().iterator();
        assertSame(original.valuesRoot(), copy.valuesRoot()); assertSame(original.orderRoot(), copy.orderRoot());
        assertEquals(2, original.put("a", 20)); original.put("b", 4);
        assertEquals(List.of(Map.entry("z", 1), Map.entry("a", 2), Map.entry("m", 3)), drain(iterator));
        assertEquals(List.of("z", "a", "m", "b"), new ArrayList<>(original.keySet()));
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(copy.keySet()));
        assertEquals(2, copy.get("a")); assertEquals(20, original.get("a"));
        var prior = original.valuesRoot();
        assertEquals(20, original.putIfAbsent("a", 99)); assertSame(prior, original.valuesRoot());
        assertThrows(UnsupportedOperationException.class, () -> original.remove("a"));
        assertThrows(UnsupportedOperationException.class, original::clear);
    }

    @Test void mismatchedMapRootsAndMissingSelectedMapValueAreRejected() {
        var values = PersistentOrderedMap.<String, Integer>empty(String::compareTo).put("present", 1).map();
        var emptyOrder = PersistentOrderedMap.<Long, String>empty(Long::compare);
        assertThrows(IllegalArgumentException.class, () -> SessionHistoryMap.fromRoots(values, emptyOrder));
        var wrongOrder = emptyOrder.put(0L, "absent").map();
        var invalid = SessionHistoryMap.fromRoots(values, wrongOrder);
        assertThrows(NullPointerException.class, () -> invalid.entrySet().iterator().next());
    }

    @Test void setDeduplicationKeepsInsertionOrderAndDetachedCopy() {
        var original = SessionHistorySet.<String>empty(Comparator.naturalOrder());
        assertTrue(original.add("z")); assertTrue(original.add("a")); assertFalse(original.add("z"));
        var copy = original.copy(); var iterator = original.iterator();
        assertSame(original.map().valuesRoot(), copy.map().valuesRoot());
        assertTrue(original.add("m")); assertTrue(copy.add("x"));
        assertEquals(List.of("z", "a"), drain(iterator));
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(original));
        assertEquals(List.of("z", "a", "x"), new ArrayList<>(copy));
        assertThrows(UnsupportedOperationException.class, () -> original.remove("a"));
    }

    private static <T> List<T> drain(Iterator<T> iterator) {
        var values = new ArrayList<T>(); iterator.forEachRemaining(values::add); return List.copyOf(values);
    }
    private static final PersistentMapCodec<Long> LONGS = new PersistentMapCodec<>() {
        @Override public String identity() { return "test/history-int64/1"; }
        @Override public byte[] encode(Long value) { return ByteBuffer.allocate(Long.BYTES).putLong(value).array(); }
        @Override public Long decode(byte[] bytes) {
            if (bytes.length != Long.BYTES) throw new IllegalArgumentException("Not an exact int64 frame");
            return ByteBuffer.wrap(bytes).getLong();
        }
    };
}
