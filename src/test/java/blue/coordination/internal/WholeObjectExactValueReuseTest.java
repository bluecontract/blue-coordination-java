package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Whole-object reads retain fresh selection/proof checks while sharing only immutable value lanes. */
final class WholeObjectExactValueReuseTest {
    private static final int MAX = 4 * 1024 * 1024;
    private static final int DEPTH = 128;

    @Test void twoLanesAndNewReadersReuseTheSessionExactFrameWithoutCachingTheOuterEntry() {
        var objects = new DocumentSessionStorageTest.Bytes();
        var value = ExactValue.verified(new Node().name("shared-exact"));
        var cold = new WholeObjectStorage(objects, MAX, DEPTH);
        var refs = cold.retain(changes(value, null, Map.of()));
        try (var cache = cache()) {
            var rows = new SessionRecordCodec(MAX, DEPTH, cache);
            byte[] frame = SessionStorageWire.encode(MAX, out -> new SessionRecordCodec(MAX, DEPTH).exact(out, value));
            var warmed = SessionStorageWire.decode(frame, MAX, rows::exact);
            for (int i = 0; i < 2; i++) {
                var reader = new WholeObjectStorage(objects, MAX, DEPTH, cache).open(index(refs));
                var first = reader.find(value.blueId()).orElseThrow();
                var second = reader.find(value.blueId()).orElseThrow();
                assertNotSame(first, second, "Entry/purpose and physical selection are freshly read");
                assertSame(warmed, first.canonical()); assertSame(warmed, first.provider());
                assertSame(warmed, second.canonical()); assertEquals(1, cache.statistics().loads());
            }
            var fresh = new WholeObjectStorage(objects, MAX, DEPTH, cache);
            var retained = fresh.retain(changes(warmed, null, Map.of()));
            assertEquals(refs, retained, "Certified write bytes preserve the exact complete outer object");
        }
    }

    @Test void warmValuesNeverHideMissingCorruptOrWrongIndexSelectedOuterBytes() {
        var objects = new DocumentSessionStorageTest.Bytes(); var value = ExactValue.verified(new Node().name("selected"));
        var cold = new WholeObjectStorage(objects, MAX, DEPTH);
        var refs = cold.retain(changes(value, null, Map.of())); String address = refs.entries().get(value.blueId());
        byte[] stored = objects.records.get(address).clone();
        try (var cache = cache()) {
            var storage = new WholeObjectStorage(objects, MAX, DEPTH, cache); var reader = storage.open(index(refs));
            var warmed = reader.find(value.blueId()).orElseThrow().canonical();
            objects.records.remove(address);
            assertThrows(CoordinationObjectStorageException.class, () -> reader.find(value.blueId()));
            byte[] corrupt = stored.clone(); corrupt[0] ^= 1; objects.records.put(address, corrupt);
            assertThrows(CoordinationObjectStorageException.class, () -> reader.find(value.blueId()));
            objects.records.put(address, stored);
            assertSame(warmed, reader.find(value.blueId()).orElseThrow().canonical());
            var wrong = new WholeObjectStorage.References(Map.of("wrong-key", address), refs.proofs(), refs.cyclicMembers());
            assertThrows(CoordinationObjectStorageException.class, () -> storage.open(index(wrong)).find("wrong-key"));
            assertEquals(1, cache.statistics().loads());
        }
    }

    @Test void cyclicWireBodiesAndCurrentProofRemainFreshOutsideTheValueCache() {
        var objects = new DocumentSessionStorageTest.Bytes();
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("cached-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("cached-b").properties("peer", new Node().blueId("this#0")))));
        String id = provider.getBlueIdByName("cached-a"), master = BlueIds.cyclicSetMasterBlueId(id);
        Node wire = provider.fetchByBlueId(id).get(0); var proof = provider.cyclicSetProofFor(id).proof().orElseThrow();
        var value = ExactValue.fromVerifiedProviderEvidence(id, wire, proof);
        var refs = new WholeObjectStorage(objects, MAX, DEPTH).retain(changes(value, wire, Map.of(master, proof)));
        try (var cache = cache()) {
            var backing = new WholeObjectStorage(objects, MAX, DEPTH, cache).open(index(refs));
            var store = new WholeObjectStore(new EngineMetrics(), backing);
            var warmed = store.require(id);
            backing.find(id).orElseThrow().cyclicProviderBody().name("caller mutation");
            backing.proof(master).orElseThrow().declaredPlaceholderSet().get(0).name("caller proof mutation");
            assertSame(warmed, store.require(id));
            assertEquals(value.blueId(), ExactValue.fromVerifiedProviderEvidence(id,
                    backing.find(id).orElseThrow().cyclicProviderBody(), backing.proof(master).orElseThrow()).blueId());
            assertEquals(1, cache.statistics().loads(), "Only identical exact-value lanes are cached");
            String proofAddress = refs.proofs().get(master); byte[] complete = objects.records.remove(proofAddress);
            assertThrows(CoordinationObjectStorageException.class, () -> store.require(id), "Warm value cannot hide missing current proof");
            objects.records.put(proofAddress, new byte[]{1, 2, 3});
            assertThrows(CoordinationObjectStorageException.class, () -> store.require(id), "Warm value cannot hide corrupt current proof");
            objects.records.put(proofAddress, complete); assertSame(warmed, store.require(id));
        }
    }

    @Test void changedProfileAndDisabledCacheUseOrdinaryExactValidation() {
        var objects = new DocumentSessionStorageTest.Bytes(); var value = ExactValue.verified(new Node().name("bounded"));
        var refs = new WholeObjectStorage(objects, MAX, DEPTH).retain(changes(value, null, Map.of()));
        try (var cache = cache()) {
            var first = new WholeObjectStorage(objects, MAX, DEPTH, cache).open(index(refs)).find(value.blueId()).orElseThrow();
            var different = new WholeObjectStorage(objects, MAX, DEPTH - 1, cache).open(index(refs)).find(value.blueId()).orElseThrow();
            assertNotSame(first.canonical(), different.canonical()); assertEquals(2, cache.statistics().loads());
            assertThrows(RuntimeException.class, () -> new WholeObjectStorage(objects, 128, DEPTH, cache)
                    .open(index(refs)).find(value.blueId()));
            cache.clear(); assertEquals(0, cache.statistics().retainedEntries());
            assertNotSame(first.canonical(), new WholeObjectStorage(objects, MAX, DEPTH, cache).open(index(refs))
                    .find(value.blueId()).orElseThrow().canonical());
        }
        try (var disabled = new RootedStorageCache(0, 0, 0)) {
            var backing = new WholeObjectStorage(objects, MAX, DEPTH, disabled).open(index(refs));
            assertNotSame(backing.find(value.blueId()).orElseThrow().canonical(), backing.find(value.blueId()).orElseThrow().canonical());
            assertEquals(0, disabled.statistics().retainedEntries());
        }
    }

    private static WholeObjectBacking.Changes changes(ExactValue value, Node wire,
            Map<String, blue.language.provider.CyclicSetProof> proofs) {
        return new WholeObjectBacking.Changes(Map.of(value.blueId(), new WholeObjectBacking.Entry(value, value, wire, "test")), proofs);
    }
    private static WholeObjectStorage.Index index(WholeObjectStorage.References refs) {
        return new WholeObjectStorage.Index() {
            public Optional<String> entryAddress(String id) { return Optional.ofNullable(refs.entries().get(id)); }
            public Optional<String> proofAddress(String id) { return Optional.ofNullable(refs.proofs().get(id)); }
            public Iterable<String> cyclicMembers(String id) { return refs.cyclicMembers().getOrDefault(id, List.of()); }
            public int size() { return refs.entries().size(); }
        };
    }
    private static RootedStorageCache cache() { return new RootedStorageCache(128L * 1024 * 1024, 128, 64L * 1024 * 1024); }
}
