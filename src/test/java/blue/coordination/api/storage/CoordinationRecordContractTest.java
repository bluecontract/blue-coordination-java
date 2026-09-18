package blue.coordination.api.storage;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static blue.coordination.api.storage.CoordinationRecords.*;
import static org.junit.jupiter.api.Assertions.*;

final class CoordinationRecordContractTest {
    private static final Bytes SCOPE = bytes("root-a");
    private static final Address ADDRESS = new Address("tenant", "instance-1");
    private static final Key A = key("a"), B = key("b");
    private static final Range ALL = new Range(Family.SESSION, SCOPE, null, null);
    private static final DecodeLimits LIMITS = new DecodeLimits(1_000_000, 1_000, 100_000);

    @Test void payloadIsDetachedCanonicalAndCoversHostAuthorityAndEvidence() {
        // given
        byte[] content = { 1, 2, 3 };
        var owned = new Bytes(content); var digest = sha256(owned);
        var points = new ArrayList<>(List.of(new Point(B, Value.absent()), new Point(A, new Value(3, owned))));
        var host = new Key(Family.HOST_OWNER, SCOPE, bytes("lease"));
        points.add(new Point(host, new Value(9, bytes("token-9"))));
        var publication = new Publication(ADDRESS, "stage-1", points, List.of(),
                List.of(new Mutation(A, bytes("next"))), List.of(new Artifact(digest, 3)), bytes("exact-result"));
        // when
        Arrays.fill(content, (byte) 9); points.clear();
        Arrays.fill(publication.points().get(1).expected().content().copy(), (byte) 0);
        var reopened = decodePublication(publication.canonicalBytes(), LIMITS);
        // then
        assertEquals(publication.digest(), reopened.digest());
        assertEquals(new Bytes(new byte[] {1, 2, 3}), reopened.points().stream().filter(p -> p.key().equals(A)).findFirst().orElseThrow().expected().content());
        assertThrows(UnsupportedOperationException.class, () -> publication.mutations().clear());
        var reversed = new ArrayList<>(publication.points()); Collections.reverse(reversed);
        assertEquals(publication.digest(), packet(reversed, publication.evidence()).digest());
        var changed = reversed.stream().map(p -> p.key().equals(host) ? new Point(host, new Value(10, bytes("token-10"))) : p).toList();
        assertNotEquals(publication.digest(), packet(changed, publication.evidence()).digest());
        assertNotEquals(publication.digest(), packet(reversed, bytes("other-result")).digest());
    }

    @Test void immutableLookupFactsAreDetachedAndDoNotRelaxSemanticAbsenceConditions() {
        // given
        var object = new Key(Family.OBJECT_PROOF, SCOPE, bytes("blue-id"));
        var scope = new FixtureScope(); var attempt = new CoordinationRecordAttempt(scope);
        // when
        assertTrue(attempt.immutableFact(object).isEmpty());
        attempt.retainImmutableFact(object, bytes("body-address"));
        var packet = attempt.prepare("immutable", List.of(), bytes("result"));
        // then
        assertTrue(packet.points().isEmpty()); assertTrue(packet.mutations().isEmpty());
        assertEquals(List.of(new ImmutableFact(object, bytes("body-address"))), packet.immutableFacts());
        assertEquals(packet.digest(), decodePublication(packet.canonicalBytes(), LIMITS).digest());
        var observed = new CoordinationRecordAttempt(new FixtureScope());
        assertEquals(Value.absent(), observed.read(object)); observed.retainImmutableFact(object, bytes("body-address"));
        assertEquals(List.of(new Point(object, Value.absent())), observed.prepare("strict-absence", List.of(), bytes("e")).points());
        assertThrows(IllegalArgumentException.class, () -> new ImmutableFact(A, bytes("mutable-owner")));
        assertThrows(IllegalArgumentException.class, () -> new Publication(ADDRESS, "illegal", List.of(new Point(object, Value.absent())),
                List.of(), List.of(new Mutation(object, bytes("value"))), List.of(), bytes("e")));
    }

    @Test void immutableFactCodecRejectsCorruptionAndEveryTruncationAndCoversFactBytes() {
        // given
        var key = new Key(Family.OBJECT_PROOF, SCOPE, bytes("proof"));
        var packet = new Publication(ADDRESS, "fact", List.of(), List.of(), List.of(),
                List.of(new ImmutableFact(key, bytes("one"))), List.of(), bytes("e"));
        // when
        var encoded = packet.canonicalBytes().copy();
        // then
        for (int i = 0; i < encoded.length; i++) {
            var truncated = new Bytes(Arrays.copyOf(encoded, i));
            assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(truncated, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> new Publication(ADDRESS, "duplicate", List.of(), List.of(), List.of(),
                List.of(new ImmutableFact(key, bytes("one")), new ImmutableFact(key, bytes("one"))), List.of(), bytes("e")));
        assertNotEquals(packet.digest(), new Publication(ADDRESS, "fact", List.of(), List.of(), List.of(),
                List.of(new ImmutableFact(key, bytes("two"))), List.of(), bytes("e")).digest());
        assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(packet.canonicalBytes(), new DecodeLimits(encoded.length, 100, 1)));
    }

    @Test void changedOrTombstonedImmutableFactsRetireTheAttempt() {
        // given
        var key = new Key(Family.OBJECT_MEMBER, SCOPE, bytes("member"));
        var scope = new FixtureScope(); scope.values.put(key, new Value(1, bytes("one")));
        var attempt = new CoordinationRecordAttempt(scope);
        // when
        assertThrows(IllegalArgumentException.class, () -> attempt.retainImmutableFact(key, bytes("two")));
        // then
        assertTrue(scope.closed); assertThrows(IllegalStateException.class, () -> attempt.prepare("bad", List.of(), bytes("e")));
        var deleted = new FixtureScope(); deleted.values.put(key, new Value(2, null));
        assertThrows(IllegalArgumentException.class, () -> new CoordinationRecordAttempt(deleted).immutableFact(key)); assertTrue(deleted.closed);
    }

    private Publication packet(List<Point> points, Bytes evidence) {
        return new Publication(ADDRESS, "stage-1", points, List.of(), List.of(new Mutation(A, bytes("next"))),
                List.of(new Artifact(sha256(new Bytes(new byte[] {1, 2, 3})), 3)), evidence);
    }

    @Test void overlappingPredicatesCannotDescribeDifferentSnapshots() {
        // given
        var a = new Row(A, new Value(1, bytes("a1")));
        var b = new Row(B, new Value(2, bytes("b2")));
        var right = new Range(Family.SESSION, SCOPE, B.key(), null);
        // when
        assertThrows(IllegalArgumentException.class, () -> new Publication(ADDRESS, "id", List.of(),
                List.of(new Query(ALL, List.of(a, b)), new Query(right, List.of())), List.of(), List.of(), bytes("result")));
        // then
        assertThrows(IllegalArgumentException.class, () -> new Publication(ADDRESS, "id", List.of(new Point(B, Value.absent())),
                List.of(new Query(ALL, List.of(a, b))), List.of(), List.of(), bytes("result")));
        assertThrows(IllegalArgumentException.class, () -> new Query(ALL, List.of(b, a)));
        assertThrows(IllegalArgumentException.class, () -> new Query(ALL, List.of(a, a)));
    }

    @Test void absenceAndDeleteRecreateHaveDistinctConditionsAndBlindWritesFail() {
        // given
        var deleted = new Publication(ADDRESS, "id", List.of(new Point(A, new Value(2, null))), List.of(),
                List.of(new Mutation(A, bytes("recreated"))), List.of(), bytes("result"));
        // when
        var reopened = decodePublication(deleted.canonicalBytes(), LIMITS);
        // then
        assertNotEquals(Value.absent(), new Value(2, null));
        assertThrows(IllegalArgumentException.class, () -> new Value(0, bytes("illegal")));
        assertThrows(IllegalArgumentException.class, () -> new Publication(ADDRESS, "id", List.of(), List.of(),
                List.of(new Mutation(A, bytes("blind"))), List.of(), bytes("result")));
        assertThrows(IllegalArgumentException.class, () -> new Publication(ADDRESS, "id", List.of(new Point(A, new Value(Long.MAX_VALUE, null))),
                List.of(), List.of(new Mutation(A, bytes("overflow"))), List.of(), bytes("result")));
        assertEquals(new Value(2, null), reopened.points().get(0).expected());
    }

    @Test void codecRejectsEveryTruncationTrailingBytesAndBounds() {
        // given
        var p = new Publication(ADDRESS, "id", List.of(new Point(A, Value.absent())), List.of(new Query(ALL, List.of())),
                List.of(new Mutation(A, bytes("next"))), List.of(), bytes("result"));
        // when
        byte[] complete = p.canonicalBytes().copy();
        // then
        for (int i = 0; i < complete.length; i++) {
            var truncated = new Bytes(Arrays.copyOf(complete, i));
            assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(truncated, LIMITS));
        }
        assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(new Bytes(Arrays.copyOf(complete, complete.length + 1)), LIMITS));
        assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(p.canonicalBytes(), new DecodeLimits(complete.length - 1, 100, 1000)));
        assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(p.canonicalBytes(), new DecodeLimits(complete.length, 1, 1000)));
        assertThrows(CoordinationObjectStorageException.class, () -> decodePublication(p.canonicalBytes(), new DecodeLimits(complete.length, 100, 1)));
    }

    @Test void trackedAttemptRetainsOriginalSnapshotWhileOverlayingOwnChanges() {
        // given
        var scope = new FixtureScope(); scope.values.put(A, new Value(4, bytes("old")));
        var attempt = new CoordinationRecordAttempt(scope);
        // when
        assertEquals(1, attempt.query(ALL).size());
        attempt.put(A, bytes("new")); attempt.put(B, bytes("born"));
        assertEquals(2, attempt.query(ALL).size());
        attempt.delete(A);
        assertEquals(List.of(new Row(B, new Value(1, bytes("born")))), attempt.query(ALL));
        var p = attempt.prepare("id", List.of(), bytes("exact-evidence"));
        // then
        assertTrue(scope.closed); assertEquals(1, scope.queryCalls);
        assertEquals(new Value(4, bytes("old")), p.points().get(0).expected());
        assertEquals(Value.absent(), p.points().get(1).expected());
        assertEquals(List.of(new Row(A, new Value(4, bytes("old")))), p.queries().get(0).expected());
        assertThrows(IllegalStateException.class, () -> attempt.read(A));
        assertThrows(IllegalStateException.class, () -> attempt.prepare("again", List.of(), bytes("e")));
        attempt.close();
    }

    @Test void failedReadsRetireScopeAndThreadForeignCallsCannotTouchIt() throws Exception {
        // given
        var scope = new FixtureScope();
        var attempt = new CoordinationRecordAttempt(scope);
        var thread = Executors.newSingleThreadExecutor();
        // when
        try {
            var failure = assertThrows(ExecutionException.class, () -> thread.submit(() -> attempt.read(A)).get());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, scope.readCalls);
        } finally { thread.shutdownNow(); }
        scope.fail = true;
        assertThrows(CoordinationObjectStorageException.class, () -> attempt.read(A));
        // then
        assertTrue(scope.closed);
        assertThrows(IllegalStateException.class, () -> attempt.prepare("id", List.of(), bytes("e")));
    }

    @Test void scopeIsReleasedWhenPreparationRejectsInconsistentProvider() {
        // given
        var scope = new FixtureScope(); var attempt = new CoordinationRecordAttempt(scope);
        assertTrue(attempt.query(ALL).isEmpty());
        scope.values.put(A, new Value(1, bytes("illegally-new-snapshot")));
        // when
        attempt.read(A);
        assertThrows(IllegalArgumentException.class, () -> attempt.prepare("id", List.of(), bytes("e")));
        // then
        assertTrue(scope.closed);
    }

    @Test void sortKeysUseUnsignedBytesAndHalfOpenScopeBoundaries() {
        // given
        var low = new Bytes(new byte[] {0x7f}); var high = new Bytes(new byte[] {(byte) 0x80});
        // when
        var range = new Range(Family.SESSION, SCOPE, low, high);
        // then
        assertTrue(low.compareTo(high) < 0);
        assertTrue(range.contains(new Key(Family.SESSION, SCOPE, low)));
        assertFalse(range.contains(new Key(Family.SESSION, SCOPE, high)));
        assertFalse(range.contains(new Key(Family.SESSION, bytes("other"), low)));
        assertThrows(IllegalArgumentException.class, () -> new Address("\ud800", "instance"));
    }

    @Test void firstSelectionGuardsOnlyItsCompletePrefixAndIncludesPendingChanges() {
        // given
        var scope = new FixtureScope();
        scope.values.put(A, new Value(1, bytes("a"))); scope.values.put(B, new Value(1, bytes("b")));
        // when
        try (var attempt = new CoordinationRecordAttempt(scope)) {
            assertEquals(A, attempt.first(ALL).orElseThrow().key());
            var packet = attempt.prepare("first", List.of(), bytes("e"));
            // then
            assertEquals(List.of(new Row(A, new Value(1, bytes("a")))), packet.queries().get(0).expected());
            assertFalse(packet.queries().get(0).range().contains(B), "Later work must not become a publication precondition");
        }
        var changed = new FixtureScope(); changed.values.putAll(scope.values);
        try (var attempt = new CoordinationRecordAttempt(changed)) {
            attempt.delete(A);
            assertEquals(B, attempt.first(ALL).orElseThrow().key());
            var earlier = key("0"); attempt.put(earlier, bytes("new"));
            assertEquals(earlier, attempt.first(ALL).orElseThrow().key());
            var packet = attempt.prepare("overlay-first", List.of(), bytes("e"));
            assertEquals(2, packet.queries().size());
        }
    }

    private static final class FixtureScope implements CoordinationRecordStore.ReadScope {
        final Map<Key, Value> values = new TreeMap<>();
        boolean closed, fail; int queryCalls, readCalls;
        public Address address() { return ADDRESS; }
        public Value read(Key key) {
            readCalls++;
            if (fail) throw new CoordinationObjectStorageException("Injected physical read failure");
            return values.getOrDefault(key, Value.absent());
        }
        public List<Row> query(Range range) {
            queryCalls++;
            return values.entrySet().stream().filter(e -> e.getValue().present() && range.contains(e.getKey()))
                    .map(e -> new Row(e.getKey(), e.getValue())).toList();
        }
        public void close() { closed = true; }
    }
    private static Key key(String text) { return new Key(Family.SESSION, SCOPE, bytes(text)); }
    private static Bytes bytes(String text) { return new Bytes(text.getBytes(StandardCharsets.UTF_8)); }
}
