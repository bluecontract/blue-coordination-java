package blue.coordination.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RootedStorageCacheTest {
    @Test void descriptorProofIsExactBoundedAndDisappearsOnEviction() {
        // given
        byte[] frame = {1, 2, 3};
        try (var cache = new RootedStorageCache(10000, 1, 10000)) {
            var value = cache.decodeCanonical("family/limit-3", frame, ignored -> new Object(), ignored -> frame);
            // when
            String digest = PersistentMapStorage.digest(frame);
            // then
            assertTrue(cache.hasCanonicalEncoding("family/limit-3", value, digest, 3));
            assertFalse(cache.hasCanonicalEncoding("family/limit-4", value, digest, 3));
            assertFalse(cache.hasCanonicalEncoding("family/limit-3", new Object(), digest, 3));
            assertFalse(cache.hasCanonicalEncoding("family/limit-3", value, digest, 2));
            assertFalse(cache.hasCanonicalEncoding("family/limit-3", value, "0".repeat(64), 3));
            cache.decode("other", new byte[]{4}, Object::new);
            assertFalse(cache.hasCanonicalEncoding("family/limit-3", value, digest, 3));
            cache.clear();
            assertFalse(cache.hasCanonicalEncoding("family/limit-3", value, digest, 3));
        }
    }
    @Test void exactFramesAndCodecFamiliesIsolateEntriesAndKeysAreOwned() {
        // given
        try (var cache = new RootedStorageCache(100_000, 10, 100_000)) {
            byte[] frame = {1, 2};
            // when
            Object original = new Object();
            // then
            assertSame(original, cache.decode("view/1/depth256", frame, () -> original));
            frame[0] = 3;
            assertSame(original, cache.decode("view/1/depth256", new byte[]{1, 2}, () -> fail("Decoded twice")));
            assertNotSame(original, cache.decode("view/1/depth128", new byte[]{1, 2}, Object::new));
            assertNotSame(original, cache.decode("view/1/depth256", frame, Object::new));
            assertEquals(1, cache.statistics().hits()); assertEquals(3, cache.statistics().loads());
        }
    }

    @Test void lruUsesLastAccessAndChargesTransitiveDependencies() {
        // given
        long each = RootedStorageCache.estimatedWeight(1, 0);
        try (var cache = new RootedStorageCache(each * 2, 10, each * 2)) {
            Object first = cache.decode("view", new byte[]{1}, Object::new);
            // when
            Object second = cache.decode("view", new byte[]{2}, Object::new);
            // then
            assertSame(first, cache.decode("view", new byte[]{1}, Object::new));
            cache.decode("view", new byte[]{3}, Object::new);
            assertSame(first, cache.decode("view", new byte[]{1}, Object::new));
            assertNotSame(second, cache.decode("view", new byte[]{2}, Object::new));
            assertEquals(2, cache.statistics().retainedEntries());
            assertEquals(each * 2, cache.statistics().retainedWeightBytes());
            assertEquals(2, cache.statistics().evictions());
            cache.clear();
            cache.decode("receipt", new byte[]{1}, ignored -> 100, ignored -> true, Object::new);
            assertEquals(0, cache.statistics().retainedEntries(), "Deep references count toward entry capacity");
        }
    }

    @Test void disabledOversizeAndFailedDecodeNeverRetain() {
        // given
        for (var cache : new RootedStorageCache[]{new RootedStorageCache(0, 0, 0),
                new RootedStorageCache(1000, 1, 10)}) {
            try (cache) {
                // when
                Object first = cache.decode("view", new byte[]{1}, Object::new);
                // then
                assertNotSame(first, cache.decode("view", new byte[]{1}, Object::new));
                assertEquals(0, cache.statistics().retainedEntries());
            }
        }
        try (var cache = new RootedStorageCache(10000, 10, 10000)) {
            for (int i = 0; i < 2; i++) assertThrows(IllegalArgumentException.class,
                    () -> cache.decode("bad", new byte[]{1}, () -> { throw new IllegalArgumentException("bad proof"); }));
            assertEquals(2, cache.statistics().failedLoads()); assertEquals(0, cache.statistics().retainedEntries());
            Object known = cache.decode("view", new byte[]{2}, Object::new);
            Object rebound = cache.decode("view", new byte[]{2}, ignored -> 0, ignored -> false, Object::new);
            assertNotSame(known, rebound); assertEquals(1, cache.statistics().incompatibleHits());
            assertThrows(IllegalArgumentException.class, () -> cache.decode("view", new byte[]{2}, ignored -> 0,
                    ignored -> { throw new IllegalArgumentException("missing dependency"); }, Object::new));
        }
    }

    @Test void sameKeyLoadsOnceWithoutBlockingAnUnrelatedKey() throws Exception {
        // given
        var pool = Executors.newFixedThreadPool(3);
        try (var cache = new RootedStorageCache(10000, 10, 10000)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var decodes = new AtomicInteger();
            // when
            var one = pool.submit(() -> cache.decode("view", new byte[]{1}, () -> {
                decodes.incrementAndGet(); entered.countDown(); await(release); return new Object();
            }));
            // then
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var two = pool.submit(() -> cache.decode("view", new byte[]{1}, () -> {
                decodes.incrementAndGet(); return new Object();
            }));
            try {
                assertNotNull(pool.submit(() -> cache.decode("view", new byte[]{2}, Object::new)).get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertSame(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertEquals(1, decodes.get());
        } finally { pool.shutdownNow(); }
    }

    @Test void clearingDuringLoadPreventsLateRepopulationAndCloseRejectsNewUse() throws Exception {
        // given
        var cache = new RootedStorageCache(10000, 10, 10000);
        var pool = Executors.newSingleThreadExecutor();
        try (cache) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            // when
            var loading = pool.submit(() -> cache.decode("view", new byte[]{1}, () -> {
                entered.countDown(); await(release); return new Object();
            }));
            // then
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { cache.clear(); } finally { release.countDown(); }
            assertNotNull(loading.get(5, TimeUnit.SECONDS)); assertEquals(0, cache.statistics().retainedEntries());
            cache.decode("view", new byte[]{1}, Object::new);
            assertEquals(1, cache.statistics().retainedEntries());
        } finally { pool.shutdownNow(); }
        assertEquals(0, cache.statistics().retainedEntries());
        assertThrows(IllegalStateException.class, () -> cache.decode("view", new byte[]{1}, Object::new));
    }

    @Test void limitsSupportGiBAndWeightsSaturateWithoutOverflow() {
        // given
        assertDoesNotThrow(() -> new RootedStorageCache(4L * 1024 * 1024 * 1024, 65536, 512L * 1024 * 1024));
        // when
        long saturatedWeight = RootedStorageCache.estimatedWeight(1, Long.MAX_VALUE);
        // then
        assertEquals(Long.MAX_VALUE, saturatedWeight);
        assertThrows(IllegalArgumentException.class, () -> new RootedStorageCache(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RootedStorageCache(1, 0, 1));
    }

    @Test void canonicalCrossRecordHitsAdoptDependenciesAndRejectedAliasesDecodeNormally() {
        // given
        record Packet(Object subject, long dependencyBytes) { }
        var decodes = new AtomicInteger(); var encodes = new AtomicInteger();
        // when
        var accepts = new AtomicInteger();
        // then
        Function<byte[], Packet> decode = bytes -> {
            decodes.incrementAndGet(); var value = new Packet(new Object(), 20);
            encodes.incrementAndGet(); assertArrayEquals(new byte[]{1, 2}, bytes); // The codec's own canonical check.
            return value;
        };
        try (var cache = new RootedStorageCache(100_000, 10, 100_000)) {
            byte[] input = {1, 2};
            var first = cache.decodeVerifiedCanonical("receipt", input, decode, Packet::subject,
                    Packet::dependencyBytes, value -> { accepts.incrementAndGet(); return true; });
            input[0] = 9;
            var warm = cache.decodeVerifiedCanonical("receipt", new byte[]{1, 2}, decode, Packet::subject,
                    Packet::dependencyBytes, value -> { accepts.incrementAndGet(); return true; });
            assertSame(first, warm); assertEquals(1, decodes.get()); assertEquals(1, encodes.get()); assertEquals(1, accepts.get());
            assertEquals(RootedStorageCache.estimatedWeight(2, 20), cache.statistics().retainedWeightBytes());
            byte[] detached = cache.canonicalEncoding("receipt", first.subject()); detached[0] = 9;
            assertArrayEquals(new byte[]{1, 2}, cache.canonicalEncoding("receipt", first.subject()));
            assertNull(cache.canonicalEncoding("other-limits", first.subject()));
            var rebound = cache.decodeVerifiedCanonical("receipt", new byte[]{1, 2}, decode, Packet::subject,
                    Packet::dependencyBytes, value -> false);
            assertNotSame(first, rebound); assertEquals(2, decodes.get()); assertEquals(2, encodes.get());
            assertNull(cache.canonicalEncoding("receipt", first.subject()), "Replacement retires the old identity certificate");
            assertThrows(IllegalStateException.class, () -> cache.decodeVerifiedCanonical("receipt", new byte[]{1, 2}, decode,
                    Packet::subject, Packet::dependencyBytes, value -> { throw new IllegalStateException("missing dependency"); }));
            assertEquals(2, decodes.get(), "A failed physical dependency check must propagate, not silently rebind");
        }
    }

    @Test void canonicalCrossRecordFailuresAndCapacityNeverGrantEncodingEvidence() {
        // given
        Object rejected = new Object();
        try (var cache = new RootedStorageCache(100_000, 10, 100_000)) {
            // when
            for (int i = 0; i < 2; i++) assertThrows(blue.coordination.api.storage.CoordinationObjectStorageException.class,
                    () -> cache.decodeVerifiedCanonical("receipt", new byte[]{1}, bytes -> {
                        SessionStorageWire.require(java.util.Arrays.equals(bytes, new byte[]{2}), "Noncanonical test frame");
                        return rejected;
                    },
                            value -> value, value -> 10, value -> true));
            // then
            assertEquals(0, cache.statistics().retainedEntries()); assertEquals(2, cache.statistics().failedLoads());
            assertNull(cache.canonicalEncoding("receipt", rejected));
            assertThrows(blue.coordination.api.storage.CoordinationObjectStorageException.class,
                    () -> cache.decodeCanonical("ordinary", new byte[]{1}, bytes -> new Object(), value -> new byte[]{2}));
        }
        long required = RootedStorageCache.estimatedWeight(1, 20);
        for (var cache : new RootedStorageCache[]{new RootedStorageCache(0, 0, 0),
                new RootedStorageCache(required - 1, 1, required - 1)}) {
            try (cache) {
                var value = cache.decodeVerifiedCanonical("receipt", new byte[]{1}, bytes -> {
                    assertArrayEquals(new byte[]{1}, bytes); return new Object();
                },
                        subject -> subject, ignored -> 20, ignored -> true);
                assertNull(cache.canonicalEncoding("receipt", value)); assertEquals(0, cache.statistics().retainedEntries());
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Barrier timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
