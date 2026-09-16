package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Only pure verification of a newly read closed-storage pair can be reused. */
final class WholeObjectCyclicPairReuseTest {
    private static final int MAX = 4 * 1024 * 1024;
    private static final int DEPTH = 128;

    @Test void actualProviderReadsAcrossOwnersVerifyOnceButReadBothRowsAndDetachEachTime() {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            var first = f.store(cache, verified, MAX, DEPTH);
            int reads = f.bytes.reads;
            Node a = first.requireProviderDocument(f.a), b = first.requireProviderDocument(f.a);
            Node c = f.store(cache, verified, MAX, DEPTH).requireProviderDocument(f.a);
            assertEquals(1, verified.get(), "Three real provider calls perform one full pair verification");
            assertEquals(6, f.bytes.reads - reads, "Both physical rows are freshly read on every call");
            assertNotSame(a, b); assertNotSame(b, c);
            assertWire(f.wireA, a); assertWire(a, b); assertWire(b, c);
            a.name("mutated returned body");
            assertWire(f.wireA, first.requireProviderDocument(f.a));
            assertEquals(1, verified.get());
        }
    }

    @Test void warmMemoDoesNotHideMissingCorruptOrWrongCurrentSelectedRows() {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            var store = f.store(cache, verified, MAX, DEPTH); store.requireProviderDocument(f.a);
            String bodyAddress = f.index.entries.get(f.a.blueId()), proofAddress = f.index.proofs.get(f.master);
            for (String address : List.of(bodyAddress, proofAddress)) {
                byte[] original = f.bytes.records.remove(address);
                assertThrows(CoordinationObjectStorageException.class, () -> store.requireProviderDocument(f.a));
                byte[] corrupt = original.clone(); corrupt[0] ^= 1; f.bytes.records.put(address, corrupt);
                assertThrows(CoordinationObjectStorageException.class, () -> store.requireProviderDocument(f.a));
                f.bytes.records.put(address, original);
                assertWire(f.wireA, store.requireProviderDocument(f.a));
            }
            f.index.entries.put(f.a.blueId(), f.index.entries.get(f.b.blueId()));
            assertThrows(CoordinationObjectStorageException.class, () -> store.requireProviderDocument(f.a));
            f.index.entries.put(f.a.blueId(), bodyAddress);
            var wrongMaster = f.storage.retain(new WholeObjectBacking.Changes(Map.of(),
                    Map.of("another-master", f.proof)));
            f.index.proofs.put(f.master, wrongMaster.proofs().get("another-master"));
            assertThrows(CoordinationObjectStorageException.class, () -> store.requireProviderDocument(f.a));
            f.index.proofs.put(f.master, proofAddress);
            assertWire(f.wireA, store.requireProviderDocument(f.a)); assertEquals(1, verified.get());
        }
    }

    @Test void changedDigestValidBodyAndProofPairsFailRepeatedlyInsteadOfUsingWarmSuccess() {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            var store = f.store(cache, verified, MAX, DEPTH); store.requireProviderDocument(f.a);
            String bodyAddress = f.index.entries.get(f.a.blueId()), proofAddress = f.index.proofs.get(f.master);
            var changedBody = f.storage.retain(new WholeObjectBacking.Changes(Map.of(f.a.blueId(),
                    new WholeObjectBacking.Entry(f.a, f.a, f.wireA.clone().name("wrong body"), "changed")), Map.of()));
            f.index.entries.put(f.a.blueId(), changedBody.entries().get(f.a.blueId()));
            for (int i = 0; i < 2; i++) assertThrows(CoordinationObjectStorageException.class,
                    () -> store.requireProviderDocument(f.a), "An invalid pair is never retained");
            assertEquals(3, verified.get());
            f.index.entries.put(f.a.blueId(), bodyAddress);
            var bad = CyclicSetProof.fromDeclaredPlaceholderSet(List.of(new Node().value("wrong proof")));
            var changedProof = f.storage.retain(new WholeObjectBacking.Changes(Map.of(), Map.of(f.master, bad)));
            f.index.proofs.put(f.master, changedProof.proofs().get(f.master));
            for (int i = 0; i < 2; i++) assertThrows(CoordinationObjectStorageException.class,
                    () -> store.requireProviderDocument(f.a));
            assertEquals(5, verified.get());
            f.index.proofs.put(f.master, proofAddress);
            assertWire(f.wireA, store.requireProviderDocument(f.a)); assertEquals(5, verified.get());
        }
    }

    @Test void memberAndProfileBindTheMemoWhileClearForcesFreshVerification() {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            var store = f.store(cache, verified, MAX, DEPTH);
            assertWire(f.wireA, store.requireProviderDocument(f.a));
            assertWire(f.wireB, store.requireProviderDocument(f.b)); assertEquals(2, verified.get());
            assertWire(f.wireA, f.store(cache, verified, MAX, DEPTH - 1).requireProviderDocument(f.a));
            assertEquals(3, verified.get());
            assertThrows(CoordinationObjectStorageException.class,
                    () -> f.store(cache, verified, 128, DEPTH).requireProviderDocument(f.a));
            assertThrows(CoordinationObjectStorageException.class,
                    () -> f.store(cache, verified, MAX, 1).requireProviderDocument(f.a));
            cache.clear(); assertEquals(0, cache.statistics().retainedEntries());
            assertWire(f.wireA, store.requireProviderDocument(f.a)); assertEquals(4, verified.get());
        }
    }

    @Test void disabledAndTinyRetentionBudgetsNeverChangeTheAcceptedProviderRead() {
        var f = new Fixture();
        for (var cache : List.of(new RootedStorageCache(0, 0, 0),
                new RootedStorageCache(1024, 4, 1024))) {
            try (cache) {
                var verified = new AtomicInteger(); var store = f.store(cache, verified, MAX, DEPTH);
                assertFalse(cache.canRetainEncodedBytes(Long.MAX_VALUE));
                assertWire(f.wireA, store.requireProviderDocument(f.a));
                assertWire(f.wireA, store.requireProviderDocument(f.a));
                assertEquals(2, verified.get(), "Oversized memo keys fall back to the same verifier");
            }
        }
    }

    @Test void validLocalOverlayAndRollbackKeepResidentProcessingOutsideTheMemo() {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            var store = f.store(cache, verified, MAX, DEPTH); store.requireProviderDocument(f.a);
            var mark = store.mark();
            store.putVerifiedProviderEvidence(f.a, f.wireA, f.proof, "local-overlay");
            assertWire(f.wireA, store.requireProviderDocument(f.a));
            assertEquals(1, verified.get(), "Only the previous persistent pair entered the memo");
            store.rollbackTo(mark);
            assertWire(f.wireA, store.requireProviderDocument(f.a)); assertEquals(1, verified.get());
        }
    }

    @Test void eitherLocalBodyOrLocalProofAlonePreventsStorageMemoAuthority() throws Exception {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            for (String field : List.of("cyclicProviderBodyByBlueId", "cyclicProofByMasterBlueId")) {
                var store = f.store(cache, verified, MAX, DEPTH);
                assertWire(f.wireA, store.requireProviderDocument(f.a));
                var mapField = WholeObjectStore.class.getDeclaredField(field); mapField.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, Object> overlay = (Map<String, Object>) mapField.get(store);
                if (field.equals("cyclicProviderBodyByBlueId"))
                    overlay.put(f.a.blueId(), f.wireA.clone().name("invalid local body"));
                else overlay.put(f.master, CyclicSetProof.fromDeclaredPlaceholderSet(List.of(new Node().name("invalid local proof"))));
                assertThrows(IllegalStateException.class, () -> store.requireProviderDocument(f.a),
                        "A cached persisted pair cannot authorize a changed local overlay");
                overlay.clear(); assertWire(f.wireA, store.requireProviderDocument(f.a));
            }
            assertEquals(1, verified.get());
        }
    }

    @Test void concurrentIndependentOwnersShareOnlyTheSuccessfulPureVerification() throws Exception {
        var f = new Fixture(); var verified = new AtomicInteger();
        try (var cache = cache()) {
            var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
            try {
                var results = new java.util.ArrayList<java.util.concurrent.Future<Node>>();
                for (int i = 0; i < 4; i++) results.add(pool.submit(() -> {
                    start.await(); return f.store(cache, verified, MAX, DEPTH).requireProviderDocument(f.a);
                }));
                start.countDown();
                Node prior = null;
                for (var result : results) {
                    Node current = result.get(30, TimeUnit.SECONDS); assertWire(f.wireA, current);
                    if (prior != null) assertNotSame(prior, current); prior = current;
                }
                assertEquals(1, verified.get());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS)); }
        }
    }

    private static void assertWire(Node expected, Node actual) {
        var codec = new ExactNodeStorageCodec(MAX, DEPTH);
        assertArrayEquals(codec.encode(expected), codec.encode(actual));
    }
    private static RootedStorageCache cache() { return new RootedStorageCache(128L * 1024 * 1024, 128, 64L * 1024 * 1024); }

    private static final class Fixture {
        final DocumentSessionStorageTest.Bytes bytes = new DocumentSessionStorageTest.Bytes();
        final WholeObjectStorage storage = new WholeObjectStorage(bytes, MAX, DEPTH);
        final ExactValue a, b;
        final Node wireA, wireB;
        final CyclicSetProof proof;
        final String master;
        final MutableIndex index;
        Fixture() {
            var provider = new BasicNodeProvider(new Node().items(List.of(
                    new Node().name("memo-a").properties("peer", new Node().blueId("this#1")),
                    new Node().name("memo-b").properties("peer", new Node().blueId("this#0")))));
            String aid = provider.getBlueIdByName("memo-a"), bid = provider.getBlueIdByName("memo-b");
            master = BlueIds.cyclicSetMasterBlueId(aid); proof = provider.cyclicSetProofFor(aid).proof().orElseThrow();
            wireA = provider.fetchByBlueId(aid).get(0); wireB = provider.fetchByBlueId(bid).get(0);
            a = ExactValue.fromVerifiedProviderEvidence(aid, wireA, proof);
            b = ExactValue.fromVerifiedProviderEvidence(bid, wireB, proof);
            index = new MutableIndex(storage.retain(new WholeObjectBacking.Changes(Map.of(
                    aid, new WholeObjectBacking.Entry(a, a, wireA, "memo-a"),
                    bid, new WholeObjectBacking.Entry(b, b, wireB, "memo-b")), Map.of(master, proof))));
        }
        WholeObjectStore store(RootedStorageCache cache, AtomicInteger verified, int maximumBytes, int maximumDepth) {
            return new WholeObjectStore(new EngineMetrics(),
                    new WholeObjectStorage(bytes, maximumBytes, maximumDepth, cache, verified::incrementAndGet).open(index));
        }
    }

    private static final class MutableIndex implements WholeObjectStorage.Index {
        final Map<String, String> entries, proofs;
        final Map<String, List<String>> members;
        MutableIndex(WholeObjectStorage.References refs) {
            entries = new LinkedHashMap<>(refs.entries()); proofs = new LinkedHashMap<>(refs.proofs()); members = refs.cyclicMembers();
        }
        public Optional<String> entryAddress(String id) { return Optional.ofNullable(entries.get(id)); }
        public Optional<String> proofAddress(String master) { return Optional.ofNullable(proofs.get(master)); }
        public Iterable<String> cyclicMembers(String master) { return members.getOrDefault(master, List.of()); }
        public int size() { return entries.size(); }
    }
}
