package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecordStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalRecordMapTest {
    private static final Bytes SCOPE = new Bytes(new byte[] {1});
    private static final Bytes EVIDENCE = new Bytes(new byte[] {2});
    private static final Address ADDRESS = new Address("test", "instance");

    @Test void discardedBranchesNeverLeakWritesAndOriginalViewsStayImmutable() {
        // given
        var store = new Store(); store.seed("a", "original"); store.seed("z", "unrelated");
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt); var base = map(context);
        // when
        var discarded = base.put("a", "discarded").map().put("c", "discarded").map();
        var selected = base.put("b", "selected").map(); selected.selectLogicalRecords();
        String unchanged = base.get("a"); context.flush();
        var packet = attempt.prepare("one", List.of(), EVIDENCE);
        // then
        assertEquals("original", unchanged);
        assertEquals(List.of("b"), packet.mutations().stream().map(m -> text(m.key().key())).toList());
        assertTrue(store.publish(packet));
        var cold = map(new LogicalRecordContext(store.attempt()));
        assertEquals(List.of("a", "b", "z"), cold.keys()); assertEquals("original", cold.get("a"));
        assertThrows(IllegalStateException.class, () -> discarded.get("c"));
        assertThrows(IllegalStateException.class, selected::minimum);
    }

    @Test void independentBranchesPreparedTogetherPublishInBothOrdersWithoutCatalogConditions() {
        // given
        for (boolean reverse : List.of(false, true)) {
            var store = new Store(); store.seed("a", "a0"); store.seed("b", "b0");
            var a = store.attempt(); var b = store.attempt();
            var ca = new LogicalRecordContext(a); var cb = new LogicalRecordContext(b);
            // when
            assertEquals("a0", map(ca).get("a")); assertEquals("b0", map(cb).get("b"));
            map(ca).put("a", "a1").map().selectLogicalRecords(); ca.flush();
            map(cb).put("b", "b1").map().selectLogicalRecords(); cb.flush();
            var pa = a.prepare("a", List.of(), EVIDENCE); var pb = b.prepare("b", List.of(), EVIDENCE);
            // then
            assertTrue(pa.queries().isEmpty()); assertTrue(pb.queries().isEmpty());
            assertTrue(store.publish(reverse ? pb : pa)); assertTrue(store.publish(reverse ? pa : pb));
            var cold = map(new LogicalRecordContext(store.attempt()));
            assertEquals("a1", cold.get("a")); assertEquals("b1", cold.get("b"));
        }
    }

    @Test void minimumAndLazyRangesProtectOnlyTheVisitedPrefix() {
        // given
        var store = new Store(); store.seed("b", "b0"); store.seed("z", "z0");
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        var selected = map(context).put("a", "a0").map();
        // when
        assertEquals("a", selected.minimum().entry().getKey());
        var range = selected.range("b", "z"); assertTrue(range.hasNext()); assertEquals("b", range.next().getKey());
        selected.selectLogicalRecords(); context.flush(); var packet = attempt.prepare("a", List.of(), EVIDENCE);
        store.seed("z", "z1");
        // then
        assertTrue(store.publish(packet), "Changing an unvisited suffix cannot invalidate the selected prefix");
        assertTrue(packet.queries().stream().noneMatch(q -> q.expected().stream().anyMatch(r -> text(r.key().key()).equals("z"))));
        var next = store.attempt(); var nc = new LogicalRecordContext(next);
        assertEquals("b", map(nc).higherThan("a").entry().getKey());
        nc.flush(); var after = next.prepare("next", List.of(), EVIDENCE);
        store.seed("aa", "earlier"); assertFalse(store.publish(after), "A new earlier candidate must invalidate the selection");
    }

    @Test void projectedSessionStyleValuesStageOnlyChangedRowsAndDoNotHydrateMembership() {
        // given
        var store = new Store(); store.seed("a", "a0"); store.seed("b", "b0");
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt); var original = map(context);
        var hydrated = new AtomicInteger(); var retained = new AtomicInteger();
        var projection = original.projectValues((key, value) -> { hydrated.incrementAndGet(); return new StringBuilder(value); });
        var working = projection.open();
        // when
        assertTrue(working.containsKeyWithoutValue("a"));
        var changed = working.put("a", new StringBuilder("a1")).map();
        var staged = projection.stage(changed, (key, value) -> { retained.incrementAndGet(); return value.toString(); });
        staged.selectLogicalRecords(); context.flush(); var packet = attempt.prepare("projection", List.of(), EVIDENCE);
        // then
        assertEquals(0, hydrated.get()); assertEquals(1, retained.get()); assertEquals(1, packet.mutations().size());
        assertTrue(store.publish(packet));
        assertEquals("b0", map(new LogicalRecordContext(store.attempt())).get("b"));
        assertThrows(IllegalArgumentException.class, () -> projection.stage(PersistentOrderedMap.empty(String::compareTo), (k, v) -> ""));
    }

    @Test void completeEnumerationAndRemovalRetainAbsenceAndMembershipConditions() {
        // given
        var store = new Store(); store.seed("a", "a0"); store.seed("b", "b0");
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        var base = map(context); var cleared = base.emptyCopy().put("c", "c0").map();
        // when
        assertEquals(List.of("a", "b"), base.keys()); assertEquals(List.of("c"), cleared.keys());
        cleared.selectLogicalRecords(); context.flush(); var packet = attempt.prepare("clear", List.of(), EVIDENCE);
        store.seed("d", "new member");
        // then
        assertFalse(store.publish(packet));
        assertEquals(3, packet.mutations().size());
        assertTrue(packet.mutations().stream().anyMatch(m -> text(m.key().key()).equals("a") && m.content() == null));
    }

    @Test void overlayAccessStillEnforcesThreadAndAttemptLifetime() throws Exception {
        // given
        var store = new Store(); var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        var selected = map(context).put("a", "pending").map(); var other = Executors.newSingleThreadExecutor();
        // when
        try {
            var error = assertThrows(ExecutionException.class, () -> other.submit(() -> selected.get("a")).get());
            assertInstanceOf(IllegalStateException.class, error.getCause());
        } finally { other.shutdownNow(); }
        attempt.close();
        // then
        assertThrows(IllegalStateException.class, () -> selected.get("a"));
        assertThrows(IllegalStateException.class, selected::selectLogicalRecords);
    }

    @Test void invalidStoredValueAndConflictingFinalBranchesRetireTheWholeAttempt() {
        // given
        var store = new Store(); store.data.put(key("a"), new Value(1, new Bytes(new byte[] {(byte) 0xff})));
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        // when
        assertThrows(IllegalArgumentException.class, () -> map(context).get("a"));
        // then
        assertThrows(IllegalStateException.class, () -> attempt.prepare("corrupt", List.of(), EVIDENCE));
        var other = new Store().attempt(); var selected = new LogicalRecordContext(other);
        var base = map(selected); base.put("a", "one").map().selectLogicalRecords();
        assertThrows(IllegalStateException.class, () -> base.put("a", "two").map().selectLogicalRecords());
        assertThrows(IllegalStateException.class, () -> other.prepare("mixed", List.of(), EVIDENCE));
    }

    @Test void keyEncodingPreservesCodePointSignedIntegerAndTupleOrder() {
        // given
        var texts = List.of("", "\u0000", "\u0000a", "a", "aa", "\ue000", "\ud800\udc00", "\udbff\udfff");
        var numbers = List.of(Long.MIN_VALUE, -2L, -1L, 0L, 1L, Long.MAX_VALUE);
        var text = OrderedRecordKey.text(); var integer = OrderedRecordKey.signedLong();
        // when
        var keys = texts.stream().map(text::encode).map(Bytes::new).toList();
        var longs = numbers.stream().map(integer::encode).map(Bytes::new).toList();
        // then
        for (int i = 1; i < keys.size(); i++) assertTrue(keys.get(i - 1).compareTo(keys.get(i)) < 0);
        for (int i = 1; i < longs.size(); i++) assertTrue(longs.get(i - 1).compareTo(longs.get(i)) < 0);
        for (String value : texts) assertEquals(value, text.decode(text.encode(value)));
        for (Long value : numbers) assertEquals(value, integer.decode(integer.encode(value)));
        var tuples = new ArrayList<Bytes>();
        for (String value : texts) for (long number : numbers) {
            byte[] encoded = OrderedRecordKey.tuple(text.encode(value), integer.encode(number));
            var parts = OrderedRecordKey.split(encoded, 2);
            assertEquals(value, text.decode(parts[0])); assertEquals(number, integer.decode(parts[1])); tuples.add(new Bytes(encoded));
        }
        for (int i = 1; i < tuples.size(); i++) assertTrue(tuples.get(i - 1).compareTo(tuples.get(i)) < 0);
        assertThrows(IllegalArgumentException.class, () -> text.encode("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> text.decode(new byte[] {(byte) 0xff}));
        assertThrows(IllegalArgumentException.class, () -> OrderedRecordKey.split(new byte[] {0}, 1));
        assertThrows(IllegalArgumentException.class, () -> OrderedRecordKey.split(new byte[] {0, 1}, 1));
    }

    @Test void externalOrderEncodingMatchesThePublishedLanguageComparator() {
        // given
        var integers = List.of(java.math.BigInteger.ONE.shiftLeft(257).negate(), java.math.BigInteger.valueOf(-256),
                java.math.BigInteger.valueOf(-1), java.math.BigInteger.ZERO, java.math.BigInteger.ONE,
                java.math.BigInteger.valueOf(255), java.math.BigInteger.ONE.shiftLeft(258));
        var tuples = new ArrayList<blue.language.processor.ExternalOrderKey>();
        tuples.add(blue.language.processor.ExternalOrderKey.of(List.of()));
        for (var integer : integers) {
            tuples.add(blue.language.processor.ExternalOrderKey.of(List.of(integer)));
            tuples.add(blue.language.processor.ExternalOrderKey.of(List.of(integer, "\u0000")));
            tuples.add(blue.language.processor.ExternalOrderKey.of(List.of(integer, "suffix", integer)));
        }
        for (String text : List.of("", "\u0000", "a", "\ue000", "\ud800\udc00"))
            tuples.add(blue.language.processor.ExternalOrderKey.of(List.of(text)));
        var codec = OrderedRecordKey.externalOrder();
        // when
        for (var tuple : tuples) assertEquals(tuple, codec.decode(codec.encode(tuple)));
        // then
        for (var left : tuples) for (var right : tuples)
            assertEquals(Integer.signum(left.compareTo(right)), Integer.signum(new Bytes(codec.encode(left)).compareTo(new Bytes(codec.encode(right)))));
        assertThrows(IllegalArgumentException.class, () -> OrderedRecordKey.integer().decode(new byte[] {2, 0, 0, 0, 1, 0}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(OrderedRecordKey.tuple(new byte[] {2})));
    }

    private static PersistentOrderedMap<String, String> map(LogicalRecordContext context) {
        return PersistentOrderedMap.logical(EmbeddingBinding.TEXT_ORDER, context, Family.SESSION, SCOPE,
                OrderedRecordKey.text(), OrderedRecordKey.text(), 1024, 4096);
    }
    private static Bytes bytes(String value) { return new Bytes(OrderedRecordKey.text().encode(value)); }
    private static String text(Bytes bytes) { return OrderedRecordKey.text().decode(bytes.copy()); }
    private static Key key(String value) { return new Key(Family.SESSION, SCOPE, bytes(value)); }

    static final class Store {
        final TreeMap<Key, Value> data = new TreeMap<>();
        void seed(String key, String value) { var address = key(key); data.put(address, new Value(data.getOrDefault(address, Value.absent()).revision() + 1, bytes(value))); }
        CoordinationRecordAttempt attempt() {
            var snapshot = new TreeMap<>(data);
            return new CoordinationRecordAttempt(new CoordinationRecordStore.ReadScope() {
                boolean closed;
                void open() { if (closed) throw new IllegalStateException("Closed fixture snapshot"); }
                public Address address() { open(); return ADDRESS; }
                public Value read(Key key) { open(); return snapshot.getOrDefault(key, Value.absent()); }
                public List<Row> query(Range range) { open(); return rows(snapshot, range); }
                public Optional<Row> first(Range range) { return query(range).stream().findFirst(); }
                public void close() { closed = true; }
            });
        }
        boolean publish(Publication packet) {
            for (var point : packet.points()) if (!point.expected().equals(data.getOrDefault(point.key(), Value.absent()))) return false;
            for (var query : packet.queries()) if (!query.expected().equals(rows(data, query.range()))) return false;
            for (var mutation : packet.mutations()) data.put(mutation.key(), new Value(data.getOrDefault(mutation.key(), Value.absent()).revision() + 1, mutation.content()));
            return true;
        }
        private static List<Row> rows(TreeMap<Key, Value> data, Range range) {
            return data.entrySet().stream().filter(e -> range.contains(e.getKey()) && e.getValue().content() != null)
                    .map(e -> new Row(e.getKey(), e.getValue())).toList();
        }
    }
}
