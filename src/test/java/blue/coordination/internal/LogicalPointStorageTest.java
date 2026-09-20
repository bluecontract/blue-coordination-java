package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalPointStorageTest {
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final InsertionOrderedStorage.Limits LIMITS = new InsertionOrderedStorage.Limits(
            4096, 1024, 4096, 4096, 16, 4096, 8192, 16);
    private static final InsertionOrderedStorage.Codec<String> TEXT = new InsertionOrderedStorage.Codec<>() {
        public String identity() { return "test/text/1"; }
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        public String decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    };

    @Test void artifactsAreBoundAutomaticallyAndChangedAcknowledgementsFailClosed() {
        // given
        var attempt = new LogicalRecordMapTest.Store().attempt(); var binding = new LogicalPointStorage(attempt);
        var bytes = new DocumentSessionStorageTest.Bytes();
        var tracked = binding.trackArtifacts(RootedEngineStorage.controlledNamespace(bytes));
        byte[] body = new byte[] {1, 2, 3};
        var digest = blue.coordination.api.storage.CoordinationRecords.sha256(new Bytes(body));
        // when
        tracked.putIfAbsent(digest.hex(), body); tracked.get(digest.hex(), 3).orElseThrow()[0] = 9;
        binding.stage(); var packet = attempt.prepare("artifacts", List.of(), EVIDENCE);
        // then
        assertEquals(List.of(new Artifact(digest, 3)), packet.artifacts());
        assertTrue(RootedEngineStorage.isControlledNamespace(tracked));
        assertThrows(IllegalStateException.class, () -> tracked.get(digest.hex(), 3));
        var failed = new LogicalRecordMapTest.Store().attempt(); var broken = new LogicalPointStorage(failed)
                .trackArtifacts(new blue.coordination.api.storage.CoordinationImmutableObjectStore() {
                    public byte[] putIfAbsent(String id, byte[] input) { return new byte[] {7}; }
                    public Optional<byte[]> get(String id, int maximum) { return Optional.of(new byte[] {7}); }
                });
        assertThrows(IllegalArgumentException.class, () -> broken.putIfAbsent(digest.hex(), body));
        assertThrows(IllegalStateException.class, () -> failed.prepare("wrong", List.of(), EVIDENCE));
    }

    @Test void completeMembershipDetectsPhantomsWhileUnobservedKeysStayIndependent() {
        // given
        var store = new LogicalRecordMapTest.Store();
        var first = store.attempt(); var second = store.attempt();
        var a = new LogicalPointStorage(first); var b = new LogicalPointStorage(second);
        var left = a.open(Family.SDK_TIMELINE, "sdk/1", TEXT, TEXT, LIMITS);
        var right = b.open(Family.SDK_TIMELINE, "sdk/1", TEXT, TEXT, LIMITS);
        // when
        assertTrue(left.isEmpty()); right.put("new", "value"); b.stage(); a.stage();
        var p = first.prepare("observed", List.of(), EVIDENCE);
        var q = second.prepare("inserted", List.of(), EVIDENCE);
        // then
        assertTrue(store.publish(q)); assertFalse(store.publish(p));
        assertEquals(1, p.queries().size()); assertTrue(q.queries().isEmpty());
    }

    @Test void viewsKeepIdentityAndMapMutationSemanticsAcrossColdOpen() {
        // given
        var store = new LogicalRecordMapTest.Store(); var attempt = store.attempt();
        var storage = new LogicalPointStorage(attempt);
        var map = storage.open(Family.SDK_INTENT, "sdk/1", TEXT, TEXT, LIMITS);
        String original = new String("original");
        // when
        map.put("z", original); map.put("a", "first");
        assertSame(original, map.get("z"));
        var iterator = map.entrySet().iterator(); var first = iterator.next();
        first.setValue("changed"); iterator.next(); iterator.remove();
        storage.stage(); assertTrue(store.publish(attempt.prepare("rows", List.of(), EVIDENCE)));
        var cold = new LogicalPointStorage(store.attempt()).open(Family.SDK_INTENT, "sdk/1", TEXT, TEXT, LIMITS);
        // then
        assertEquals(Map.of("a", "changed"), cold);
        assertSame(cold.get("a"), cold.get("a")); assertNull(cold.get("z"));
        assertThrows(IllegalStateException.class, () -> map.get("z"));
        assertThrows(IllegalStateException.class, iterator::hasNext);
    }

    @Test void pinOverflowRetiresAttemptInsteadOfEvictingSelectedIdentities() {
        // given
        var store = new LogicalRecordMapTest.Store(); var attempt = store.attempt();
        var storage = new LogicalPointStorage(attempt);
        var limits = new InsertionOrderedStorage.Limits(4096, 1024, 4096, 4096, 16, 4096, 8, 1);
        var map = storage.open(Family.SDK_ENTRY, "sdk/1", TEXT, TEXT, limits);
        // when
        map.put("a", "small");
        // then
        assertThrows(IllegalStateException.class, () -> map.get("b"));
        assertThrows(IllegalStateException.class, () -> attempt.prepare("overflow", List.of(), EVIDENCE));
        assertTrue(store.data.isEmpty());
    }

    @Test void closedViewsAndForeignThreadsCannotUseWarmPins() throws Exception {
        // given
        var storage = new LogicalPointStorage(new LogicalRecordMapTest.Store().attempt());
        var map = storage.open(Family.SDK_RESULT, "sdk/1", TEXT, TEXT, LIMITS);
        map.put("a", "value"); var executor = Executors.newSingleThreadExecutor();
        // when
        try {
            var failure = executor.submit(() -> assertThrows(IllegalStateException.class, () -> map.get("a"))).get();
            // then
            assertNotNull(failure);
        } finally { executor.shutdownNow(); }
        map.close(); assertThrows(IllegalStateException.class, () -> map.get("a"));
        assertThrows(IllegalStateException.class, storage::stage);
    }
}
