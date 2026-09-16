package blue.coordination.internal;

import blue.language.processor.NoncommittingExecutionException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.StoredRouteIndexesTest.Bytes;

final class StoredInsertionOrderedMapTest {
    private static final PersistentMapStorage.Limits INDEX = new PersistentMapStorage.Limits(65536, 4096, 60000, 2048, 32);
    private static final StoredInsertionOrderedMap.Limits LARGE = limits(2000, 4_000_000);
    private static final PersistentMapCodec<String> KEYS = new PersistentMapCodec<>() {
        @Override public String identity() { return "test/exact-text/1"; }
        @Override public byte[] encode(String value) { return SessionStorageWire.encode(4096, w -> w.text(value)); }
        @Override public String decode(byte[] bytes) { return SessionStorageWire.decode(bytes, 4096, r -> r.text(r.remaining())); }
    };

    // Deliberately no equals/hashCode: the actual engine plan classes have
    // identity equality, so structural-equivalent reconstruction is insufficient.
    private static final class Value {
        final String body;
        Value(String body) { this.body = body; }
    }
    private static final class Values implements PersistentMapCodec<Value> {
        int prepares, decodes, encodes;
        boolean forbiddenPrepare;
        @Override public String identity() { return "test/owned-plan/1"; }
        @Override public Value prepareForStorage(Value value) {
            if (forbiddenPrepare) throw new AssertionError("Read performed prewrite");
            prepares++; return new Value(value.body); // exact equivalent, not the caller's identity
        }
        @Override public byte[] encode(Value value) { encodes++; return KEYS.encode(value.body); }
        @Override public Value decode(byte[] bytes) { decodes++; return new Value(KEYS.decode(bytes)); }
    }

    @Test void mutationsAndInsertionOrderMatchLinkedHashMapIncludingIdentity() {
        var bytes = new Bytes(); var values = new Values(); var map = storage(bytes, values, LARGE).empty();
        var reference = new LinkedHashMap<String, Value>(); var random = new Random(104729L);
        for (int i = 0; i < 160; i++) {
            String key = "key/" + random.nextInt(16); var value = new Value("value/" + i);
            switch (random.nextInt(4)) {
                case 0 -> assertSame(reference.put(key, value), map.put(key, value));
                case 1 -> assertSame(reference.putIfAbsent(key, value), map.putIfAbsent(key, value));
                case 2 -> assertSame(reference.remove(key), map.remove(key));
                default -> assertSame(reference.get(key), map.get(key));
            }
            assertEquals(new ArrayList<>(reference.keySet()), new ArrayList<>(map.keySet()));
            assertEquals(new ArrayList<>(reference.values()), new ArrayList<>(map.values()));
            assertEquals(reference.size(), map.size()); assertEquals(reference.isEmpty(), map.isEmpty());
        }
        var snapshot = map.snapshot();
        var cold = storage(bytes.fresh(), new Values(), LARGE).open(snapshot);
        assertEquals(new ArrayList<>(reference.keySet()), new ArrayList<>(cold.keySet()));
        assertEquals(reference.values().stream().map(value -> value.body).toList(), cold.values().stream().map(value -> value.body).toList());
        assertEquals(snapshot.nextSequence(), cold.snapshot().nextSequence());
        if (!reference.isEmpty()) {
            String key = reference.keySet().iterator().next();
            assertNotSame(reference.get(key), cold.get(key));
            assertSame(cold.get(key), cold.putIfAbsent(key, new Value("ignored")));
            assertFalse(cold.remove(key, reference.get(key)));
            assertTrue(cold.remove(key, cold.get(key)));
        }
    }

    @Test void coldOpenSizeKeyIterationAndPointReadNeverDecodeUnrelatedBodiesOrPrewrite() {
        var bytes = new Bytes(); var map = storage(bytes, new Values(), LARGE).empty();
        for (int i = 0; i < 1023; i++) map.put("key/" + i, new Value("body/" + i));
        var coldBytes = bytes.fresh(); var values = new Values(); values.forbiddenPrepare = true;
        var cold = storage(coldBytes, values, LARGE).open(map.snapshot());
        assertEquals(0, values.decodes); assertEquals(0, cold.pinnedEntries());
        int openingReads = coldBytes.reads;
        assertEquals(1023, cold.size()); assertFalse(cold.isEmpty());
        assertEquals(openingReads, coldBytes.reads, "size metadata performs no object reads");
        assertTrue(cold.containsKey("key/510"));
        var keys = cold.keySet().iterator();
        assertEquals(List.of("key/0", "key/1", "key/2"), List.of(keys.next(), keys.next(), keys.next()));
        assertEquals(0, values.decodes, "key-only iteration must not decode values");
        var entries = cold.entrySet().iterator(); assertTrue(entries.hasNext());
        assertEquals(0, values.decodes, "hasNext is key-only");
        int before = coldBytes.reads;
        Value selected = cold.get("key/510");
        assertEquals("body/510", selected.body); assertSame(selected, cold.get("key/510"));
        assertEquals(1, values.decodes); assertEquals(0, values.prepares);
        assertTrue(coldBytes.reads - before < 70, "selected paths, not the full record catalog");
        assertEquals(0, coldBytes.writes);
        assertEquals("key/0", entries.next().getKey()); assertEquals(2, values.decodes);
    }

    @Test void scopePinsIdentityWithoutEvictionAndRejectsCapacityBeforePublication() {
        var bytes = new Bytes(); var map = storage(bytes, new Values(), LARGE).empty();
        map.put("a", new Value("A")); map.put("b", new Value("B")); map.put("c", new Value("C"));
        var values = new Values(); var cold = storage(bytes.fresh(), values, limits(2, 10000)).open(map.snapshot());
        Value a = cold.get("a"), b = cold.get("b"); var original = cold.snapshot();
        assertThrows(NoncommittingExecutionException.class, () -> cold.get("c"));
        assertThrows(NoncommittingExecutionException.class, () -> cold.put("d", new Value("D")));
        same(original, cold.snapshot()); assertEquals(2, values.decodes);
        assertSame(a, cold.get("a")); assertSame(b, cold.putIfAbsent("b", new Value("ignored")));
        assertFalse(cold.remove("a", new Value("A"))); assertTrue(cold.remove("a", a));
        assertEquals("C", cold.get("c").body); assertEquals(2, cold.pinnedEntries());
        assertSame(b, cold.get("b"));
        var freshValues = new Values();
        var tooSmall = storage(bytes.fresh(), freshValues, limits(2, 1)).open(map.snapshot());
        assertThrows(NoncommittingExecutionException.class, () -> tooSmall.get("a"));
        assertEquals(0, freshValues.decodes, "declared byte charge is checked before body decoding");
    }

    @Test void everyInsertionAndRemovalWriteFailureLeavesBothRootsCounterAndIdentityUnchanged() {
        var bytes = new Bytes(); var base = storage(bytes, new Values(), LARGE).empty();
        for (String key : List.of("b", "d", "a", "c", "e")) base.put(key, new Value(key));
        var initial = base.snapshot();
        for (boolean insertion : List.of(true, false)) {
            var countingBytes = bytes.fresh(); var success = storage(countingBytes, new Values(), LARGE).open(initial);
            if (insertion) success.put("new", new Value("new")); else success.remove("b");
            int writes = countingBytes.writes; assertTrue(writes >= 2);
            for (int failure = 1; failure <= writes; failure++) {
                var physical = bytes.fresh(); var map = storage(physical, new Values(), LARGE).open(initial);
                Value identity = map.get("b"); physical.failWriteAt = failure;
                assertThrows(NoncommittingExecutionException.class, () -> {
                    if (insertion) map.put("new", new Value("new")); else map.remove("b");
                });
                same(initial, map.snapshot()); assertSame(identity, map.get("b"));
                assertEquals(List.of("b", "d", "a", "c", "e"), new ArrayList<>(map.keySet()));
                physical.failWriteAt = -1;
                if (insertion) { map.put("new", new Value("new")); assertEquals(6, map.size()); }
                else { assertSame(identity, map.remove("b")); assertEquals(4, map.size()); }
            }
        }
    }

    @Test void missingCorruptedForeignAndNoncanonicalPayloadsFailOnlyWhenSelected() {
        var bytes = new Bytes(); var original = storage(bytes, new Values(), LARGE).empty();
        original.put("a", new Value("A")); original.put("b", new Value("B")); var snapshot = original.snapshot();
        String bAddress = recordAddress(bytes, "b");
        var missing = bytes.fresh(); missing.values.remove(bAddress);
        var values = new Values(); var cold = storage(missing, values, LARGE).open(snapshot);
        assertEquals(2, cold.size()); assertEquals(List.of("a", "b"), new ArrayList<>(cold.keySet()));
        assertEquals("A", cold.get("a").body);
        assertThrows(NoncommittingExecutionException.class, () -> cold.get("b"));
        var damaged = bytes.fresh(); damaged.values.get(bAddress)[0] ^= 1;
        var corrupt = storage(damaged, new Values(), LARGE).open(snapshot);
        assertThrows(NoncommittingExecutionException.class, () -> corrupt.get("b"));
        var lyingCodec = new PersistentMapCodec<Value>() {
            @Override public String identity() { return "test/owned-plan/1"; }
            @Override public byte[] encode(Value value) { return KEYS.encode(value.body); }
            @Override public Value decode(byte[] value) { return new Value(KEYS.decode(value) + "changed"); }
        };
        var noncanonical = storage(bytes.fresh(), lyingCodec, LARGE).open(snapshot);
        assertThrows(NoncommittingExecutionException.class, () -> noncanonical.get("a"));
        var wrongFamily = new StoredInsertionOrderedMap.Storage<>(bytes.fresh(), LARGE, "other", "text", String::compareTo, KEYS, new Values());
        assertThrows(NoncommittingExecutionException.class, () -> wrongFamily.open(snapshot));
    }

    @Test void mixedRootsMissingOrderAndBackwardCounterAreNotAcceptedAsCompleteMaps() {
        var bytes = new Bytes(); var storage = storage(bytes, new Values(), LARGE);
        var first = storage.empty(); first.put("a", new Value("A")); first.put("b", new Value("B"));
        var second = storage.empty(); second.put("b", new Value("B")); second.put("a", new Value("A"));
        var a = first.snapshot(); var b = second.snapshot();
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(new StoredInsertionOrderedMap.Snapshot(a.keys(), a.order(), 0)));
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(new StoredInsertionOrderedMap.Snapshot(a.keys(), storage.empty().snapshot().order(), 2)));
        var mixed = storage.open(new StoredInsertionOrderedMap.Snapshot(a.keys(), b.order(), 2));
        assertThrows(NoncommittingExecutionException.class, () -> mixed.get("a"));
        assertThrows(NoncommittingExecutionException.class, () -> mixed.keySet().iterator().next());
        byte[] defensive = a.keys(); Arrays.fill(defensive, (byte) 0);
        assertEquals("A", storage.open(a).get("a").body);
    }

    @Test void iteratorsReplacementReinsertionClearAndClosedScopesRetainMapContracts() {
        var bytes = new Bytes(); var map = storage(bytes, new Values(), LARGE).empty();
        Value a = new Value("A"), b = new Value("B"); map.put("a", a); map.put("b", b);
        var iterator = map.entrySet().iterator(); var entry = iterator.next();
        Value replaced = new Value("new A"); assertSame(a, entry.setValue(replaced));
        assertSame(replaced, map.get("a")); assertEquals("b", iterator.next().getKey());
        iterator.remove(); assertEquals(List.of("a"), new ArrayList<>(map.keySet()));
        map.put("b", b); map.remove("a"); map.put("a", a);
        assertEquals(List.of("b", "a"), new ArrayList<>(map.keySet()));
        var stale = map.keySet().iterator(); map.put("c", new Value("C"));
        assertThrows(ConcurrentModificationException.class, stale::hasNext);
        long next = map.snapshot().nextSequence(); int writes = bytes.writes;
        map.clear(); assertEquals(writes, bytes.writes); assertEquals(next, map.snapshot().nextSequence());
        assertEquals(0, map.pinnedEntries()); assertTrue(map.isEmpty());
        var coldEmpty = storage(bytes.fresh(), new Values(), LARGE).open(map.snapshot()); assertTrue(coldEmpty.isEmpty());
        map.put("a", a); assertEquals(next + 1, map.snapshot().nextSequence());
        var heldIterator = map.keySet().iterator(); var keys = map.keySet();
        map.close(); map.close();
        assertThrows(NoncommittingExecutionException.class, map::size);
        assertThrows(NoncommittingExecutionException.class, () -> map.get("a"));
        assertThrows(NoncommittingExecutionException.class, heldIterator::hasNext);
        assertThrows(NoncommittingExecutionException.class, keys::iterator);
    }

    private static StoredInsertionOrderedMap.Limits limits(int entries, long bytes) {
        return new StoredInsertionOrderedMap.Limits(INDEX, 8192, bytes, entries);
    }
    private static StoredInsertionOrderedMap.Storage<String, Value> storage(Bytes bytes, PersistentMapCodec<Value> values, StoredInsertionOrderedMap.Limits limits) {
        return new StoredInsertionOrderedMap.Storage<>(bytes, limits, "plans", "text", String::compareTo, KEYS, values);
    }
    private static void same(StoredInsertionOrderedMap.Snapshot expected, StoredInsertionOrderedMap.Snapshot actual) {
        assertArrayEquals(expected.keys(), actual.keys()); assertArrayEquals(expected.order(), actual.order());
        assertEquals(expected.nextSequence(), actual.nextSequence());
    }
    private static String recordAddress(Bytes objects, String key) {
        for (var row : objects.values.entrySet()) {
            byte[] bytes = row.getValue();
            byte[] marker = "blue-coordination/insertion-map/1".getBytes(StandardCharsets.UTF_16BE);
            if (bytes.length < 4 + marker.length || !Arrays.equals(marker, Arrays.copyOfRange(bytes, 4, 4 + marker.length))) continue;
            boolean matches = SessionStorageWire.decode(bytes, LARGE.maximumRecordBytes(), r -> {
                r.text(r.remaining()); r.text(r.remaining()); String actual = KEYS.decode(r.bytes(4096)); r.bytes(8192);
                return key.equals(actual);
            });
            if (matches) return row.getKey();
        }
        throw new AssertionError("Missing fixture payload for " + key);
    }
}
