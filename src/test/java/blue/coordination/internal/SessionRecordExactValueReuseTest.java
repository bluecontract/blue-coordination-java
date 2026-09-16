package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNodeStorageCodec;
import java.util.List;
import org.junit.jupiter.api.Test;
import static blue.language.snapshot.ExactNodeStorageCodec.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete immutable inline frames only; no session, owner, index or publication cache. */
final class SessionRecordExactValueReuseTest {
    private static final int MAX = 4 * 1024 * 1024;
    private static final int DEPTH = 128;
    private static final String FORMAT = "blue-coordination/exact-value-storage/1";

    @Test void completeCyclicFrameIsVerifiedOnceAcrossRowsAndReturnedBytesAndBodiesAreDetached() {
        try (var cache = cache(100)) {
            var cold = new SessionRecordCodec(MAX, DEPTH);
            var firstRows = new SessionRecordCodec(MAX, DEPTH, cache);
            var otherRows = new SessionRecordCodec(MAX, DEPTH, cache);
            ExactValue original = cyclic();
            byte[] frame = encode(cold, original);
            ExactValue first = decode(firstRows, frame);
            assertEquals(1, cache.statistics().loads());
            assertTrue(first.isCyclicMember());
            assertEquals(original.blueId(), first.blueId());
            assertSame(first, decode(otherRows, frame));
            assertEquals(1, cache.statistics().loads());
            long hits = cache.statistics().hits();
            assertArrayEquals(frame, encode(otherRows, first));
            assertEquals(hits + 1, cache.statistics().hits(), "Writer used the complete canonical certificate");
            first.copyNode().name("caller changed detached body");
            first.cyclicSetProof().orElseThrow().declaredPlaceholderSet().get(0).name("caller changed detached proof");
            byte[] callerBytes = encode(otherRows, first); callerBytes[callerBytes.length - 1] ^= 1;
            assertArrayEquals(frame, encode(otherRows, first));
            assertNotSame(first, decode(cold, frame));
        }
    }

    @Test void completeSnapshotLaneRetainsResolutionEvidenceAcrossCodecOwners() {
        var provider = new BasicNodeProvider(new Node().name("PaymentInstruction")
                .properties("reserved", new Node().value(true)));
        ExactValue value;
        try (var language = blue.language.runtime.BlueLanguage.builder().nodeProvider(provider).build()) {
            value = ExactValue.fromSnapshot(language.snapshots().resolve(
                    new Node().type(new Node().blueId(provider.getBlueIdByName("PaymentInstruction")))));
        }
        try (var cache = cache(100)) {
            var cold = new SessionRecordCodec(MAX, DEPTH);
            byte[] bytes = encode(cold, value);
            var first = decode(new SessionRecordCodec(MAX, DEPTH, cache), bytes);
            var second = decode(new SessionRecordCodec(MAX, DEPTH, cache), bytes);
            assertSame(first, second);
            assertNotNull(second.snapshot().orElseThrow().verifiedReferenceResolution());
            assertArrayEquals(bytes, encode(cold, second));
            assertEquals(1, cache.statistics().loads());
            ExactValue direct = ExactValue.fromFrozen(value.frozen());
            assertEquals(value.blueId(), direct.blueId());
            byte[] otherLane = encode(cold, direct);
            assertFalse(java.util.Arrays.equals(bytes, otherLane));
            var directRestored = decode(new SessionRecordCodec(MAX, DEPTH, cache), otherLane);
            assertNotSame(first, directRestored, "Equal BlueId does not replace complete-frame identity");
            assertTrue(directRestored.snapshot().isEmpty());
            assertEquals(2, cache.statistics().loads());
        }
    }

    @Test void warmCacheStillRejectsCorruptAndValidlyFramedWrongIdentityBytes() {
        try (var cache = cache(100)) {
            var rows = new SessionRecordCodec(MAX, DEPTH, cache);
            ExactValue value = ExactValue.verified(new Node().name("expected"));
            byte[] good = encode(new SessionRecordCodec(MAX, DEPTH), value);
            ExactValue retained = decode(rows, good);
            byte[] corrupt = good.clone(); corrupt[corrupt.length - 1] ^= 1;
            assertThrows(CoordinationObjectStorageException.class, () -> decode(rows, corrupt));
            String wrongId = ExactValue.verified(new Node().name("different")).blueId();
            byte[] incorrect = new ExactNodeStorageCodec(MAX, DEPTH).encodeEnvelope(FORMAT, out -> {
                writeText(out, wrongId); out.writeByte(0);
                writeBytes(out, new FrozenNodeStorageCodec(MAX, DEPTH).encode(value.frozen()));
            });
            byte[] framed = SessionStorageWire.encode(MAX, out -> out.bytes(incorrect));
            assertThrows(CoordinationObjectStorageException.class, () -> decode(rows, framed));
            assertEquals(2, cache.statistics().failedLoads());
            assertEquals(1, cache.statistics().retainedEntries());
            assertSame(retained, decode(rows, good));
        }
    }

    @Test void codecProfilesAndRecordBoundsRemainIndependentOnWarmCache() {
        try (var cache = cache(100)) {
            var cold = new SessionRecordCodec(MAX, DEPTH);
            byte[] bytes = encode(cold, ExactValue.verified(new Node().name("x".repeat(2048))));
            var first = decode(new SessionRecordCodec(MAX, DEPTH, cache), bytes);
            var anotherDepth = decode(new SessionRecordCodec(MAX, DEPTH - 1, cache), bytes);
            assertNotSame(first, anotherDepth);
            assertArrayEquals(encode(cold, first), encode(cold, anotherDepth));
            assertEquals(2, cache.statistics().loads());
            long loads = cache.statistics().loads();
            assertThrows(CoordinationObjectStorageException.class,
                    () -> decode(new SessionRecordCodec(128, DEPTH, cache), bytes));
            assertEquals(loads, cache.statistics().loads(), "Inline record bounds are checked before cache access");
        }
    }

    @Test void evictionAndDisabledCacheFallBackToCompleteOrdinaryVerification() {
        var cold = new SessionRecordCodec(MAX, DEPTH);
        byte[] one = encode(cold, ExactValue.verified(new Node().name("one")));
        byte[] two = encode(cold, ExactValue.verified(new Node().name("two")));
        try (var cache = cache(1)) {
            var rows = new SessionRecordCodec(MAX, DEPTH, cache);
            var first = decode(rows, one);
            decode(rows, two);
            assertNotSame(first, decode(rows, one));
            assertEquals(3, cache.statistics().loads());
            assertEquals(2, cache.statistics().evictions());
            assertArrayEquals(one, encode(rows, first), "Evicted certificate uses the ordinary encoder");
        }
        try (var cache = new RootedStorageCache(0, 0, 0)) {
            var rows = new SessionRecordCodec(MAX, DEPTH, cache);
            assertNotSame(decode(rows, one), decode(rows, one));
            assertEquals(0, cache.statistics().retainedEntries());
            assertArrayEquals(one, encode(rows, decode(rows, one)));
        }
    }

    private static ExactValue cyclic() {
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("exact-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("exact-b").properties("peer", new Node().blueId("this#0")))));
        String id = provider.getBlueIdByName("exact-a");
        return ExactValue.fromVerifiedProviderEvidence(id, provider.fetchByBlueId(id).get(0),
                provider.cyclicSetProofFor(id).proof().orElseThrow());
    }
    private static RootedStorageCache cache(int entries) {
        return new RootedStorageCache(64L * 1024 * 1024, entries, 32L * 1024 * 1024);
    }
    private static byte[] encode(SessionRecordCodec rows, ExactValue value) {
        return SessionStorageWire.encode(MAX, out -> rows.exact(out, value));
    }
    private static ExactValue decode(SessionRecordCodec rows, byte[] bytes) {
        return SessionStorageWire.decode(bytes, MAX, rows::exact);
    }
}
