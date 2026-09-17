package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalStorageCodecTest {
    private record Value(int number) { }
    private static final class CountingCodec implements PersistentMapCodec<Value> {
        final AtomicInteger encodes = new AtomicInteger(), decodes = new AtomicInteger();
        @Override public String identity() { return "test/immutable-value/1"; }
        @Override public byte[] encode(Value value) { encodes.incrementAndGet(); return new byte[]{(byte) value.number()}; }
        @Override public Value decode(byte[] bytes) {
            decodes.incrementAndGet();
            if (bytes.length == 0) throw new CoordinationObjectStorageException("Empty frame");
            return new Value(Byte.toUnsignedInt(bytes[0]));
        }
    }
    private static RootedStorageCache cache(int entries) { return new RootedStorageCache(100_000, entries, 100_000); }
    private static CanonicalStorageCodec<Value> codec(CountingCodec raw, RootedStorageCache cache) {
        return new CanonicalStorageCodec<>(raw, cache, 32, 8);
    }

    @Test void repeatedCanonicalChecksAvoidBothFullDecodeAndFullEncode() {
        // given
        try (var cache = cache(10)) {
            var raw = new CountingCodec(); var codec = codec(raw, cache);
            // when
            var decoded = codec.decode(new byte[]{7});
            // then
            assertEquals(1, raw.decodes.get()); assertEquals(1, raw.encodes.get());
            for (int i = 0; i < 10; i++) {
                assertSame(decoded, codec.decode(new byte[]{7}));
                assertArrayEquals(new byte[]{7}, codec.encode(decoded));
            }
            assertEquals(1, raw.decodes.get()); assertEquals(1, raw.encodes.get());
            assertArrayEquals(new byte[]{7}, codec.encode(new Value(7)));
            assertEquals(2, raw.encodes.get(), "Equal but distinct values have no identity certificate");
        }
    }

    @Test void persistentMapReadKeepsCanonicalRoundTripWithoutRepeatingFullWork() {
        // given
        try (var cache = cache(10)) {
            var raw = new CountingCodec(); var values = codec(raw, cache);
            var objects = new DocumentSessionStorageTest.Bytes();
            var limits = new PersistentMapStorage.Limits(4096, 256, 1024, 256, 8);
            var keys = new StoreIndexCodecs(objects, limits).text;
            var map = PersistentOrderedMap.<String, Value>empty(String::compareTo).put("a", new Value(7)).map();
            var stored = map.storedCopy("canonical-value-test", keys, values, objects, limits);
            var descriptor = stored.storedRootDescriptor();
            var selected = stored.get("a");
            int decodes = raw.decodes.get(), encodes = raw.encodes.get();
            for (int i = 0; i < 5; i++) {
                // when
                var reopened = PersistentOrderedMap.stored(String::compareTo, "canonical-value-test", keys, values,
                        objects, limits, descriptor);
                // then
                assertSame(selected, reopened.get("a"));
            }
            assertEquals(decodes, raw.decodes.get()); assertEquals(encodes, raw.encodes.get());
        }
    }

    @Test void genericDecodedEntryCannotCertifyCanonicalValueAndNoncanonicalFrameIsNeverRetained() {
        // given
        try (var cache = cache(10)) {
            var raw = new CountingCodec(); var codec = codec(raw, cache);
            var value = new Value(7);
            cache.decode(raw.identity() + "/32/8/", new byte[]{7}, () -> value);
            // when
            codec.encode(value);
            // then
            assertEquals(1, raw.encodes.get());
            assertNotSame(value, codec.decode(new byte[]{7}));
            for (int i = 0; i < 2; i++) assertThrows(CoordinationObjectStorageException.class,
                    () -> codec.decode(new byte[]{7, 0}));
            assertEquals(2, cache.statistics().failedLoads());
            assertEquals(2, cache.statistics().retainedEntries(), "Only generic and valid canonical frames remain");
        }
    }

    @Test void framesAreOwnedOnInputAndEveryReturnedEncoding() {
        // given
        try (var cache = cache(10)) {
            var raw = new CountingCodec(); var codec = codec(raw, cache);
            byte[] input = {7}; var value = codec.decode(input); input[0] = 8;
            byte[] first = codec.encode(value);
            // when
            first[0] = 9;
            // then
            assertArrayEquals(new byte[]{7}, codec.encode(value));
            assertSame(value, codec.decode(new byte[]{7})); assertEquals(1, raw.encodes.get());
        }
    }

    @Test void allValidationProfilesAreIsolatedWithoutChangingWireIdentity() {
        // given
        try (var cache = cache(10)) {
            var raw = new CountingCodec(); var codec = codec(raw, cache);
            // when
            var value = codec.decode(new byte[]{7});
            for (var other : java.util.List.of(new CanonicalStorageCodec<>(raw, cache, 16, 8),
                    new CanonicalStorageCodec<>(raw, cache, 32, 4),
                    new CanonicalStorageCodec<>(raw, cache, 32, 8, "keyBytes=4"))) {
                // then
                assertEquals(codec.identity(), other.identity());
                assertNotSame(value, other.decode(new byte[]{7}));
            }
            assertEquals(4, raw.decodes.get());
            assertThrows(CoordinationObjectStorageException.class,
                    () -> new CanonicalStorageCodec<>(raw, cache, 1, 8).decode(new byte[]{7, 0}));
        }
    }

    @Test void encodingAccessRefreshesLruAndEvictionClearRemoveReverseEvidence() {
        // given
        try (var cache = cache(2)) {
            var raw = new CountingCodec(); var codec = codec(raw, cache);
            var one = codec.decode(new byte[]{1}); var two = codec.decode(new byte[]{2});
            codec.encode(one); codec.decode(new byte[]{3});
            int before = raw.encodes.get();
            // when
            codec.encode(one);
            // then
            assertEquals(before, raw.encodes.get());
            codec.encode(two); assertEquals(before + 1, raw.encodes.get());
            assertEquals(1, cache.statistics().evictions());
            cache.clear(); codec.encode(one); assertEquals(before + 2, raw.encodes.get());
            assertEquals(0, cache.statistics().retainedEntries());
        }
    }

    @Test void disabledAndOversizeEntriesAlwaysUseFullFallback() {
        // given
        for (var cache : new RootedStorageCache[]{new RootedStorageCache(0, 0, 0),
                new RootedStorageCache(10000, 10, 1)}) {
            try (cache) {
                var raw = new CountingCodec(); var codec = codec(raw, cache);
                // when
                var first = codec.decode(new byte[]{7});
                // then
                assertNotSame(first, codec.decode(new byte[]{7}));
                int before = raw.encodes.get(); codec.encode(first); assertEquals(before + 1, raw.encodes.get());
                assertEquals(0, cache.statistics().retainedEntries());
            }
        }
    }

    @Test void rawValidationMustFinishBeforeCanonicalEvidenceCanBeUsed() throws Exception {
        // given
        var pool = Executors.newSingleThreadExecutor();
        try (var cache = cache(10)) {
            var value = new Value(7); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            // when
            var loaded = pool.submit(() -> cache.decodeCanonical("delayed", new byte[]{7}, bytes -> value, decoded -> {
                entered.countDown(); await(release); return new byte[]{7};
            }));
            // then
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { assertNull(cache.canonicalEncoding("delayed", value)); cache.clear(); }
            finally { release.countDown(); }
            assertSame(value, loaded.get(5, TimeUnit.SECONDS));
            assertNull(cache.canonicalEncoding("delayed", value), "Cleared generation cannot issue late evidence");
        } finally { pool.shutdownNow(); }
    }

    @Test void missingCacheKeepsTheExistingRawCodecPath() {
        // given
        var raw = new CountingCodec(); var codec = codec(raw, null);
        // when
        var value = codec.decode(new byte[]{7});
        // then
        assertEquals(0, raw.encodes.get());
        assertArrayEquals(new byte[]{7}, codec.encode(value)); assertEquals(1, raw.encodes.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Barrier timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
