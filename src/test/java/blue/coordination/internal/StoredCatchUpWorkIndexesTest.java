package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredCatchUpWorkIndexesTest {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 8);

    @Test void logicalDueWorkAndApplicationsColdReopenWithSelectiveMinimum() {
        // given
        var store = new LogicalRecordMapTest.Store(); var storage = storage(new DocumentSessionStorageTest.Bytes());
        var early = fixture(1, "early"); var late = fixture(9, "late");
        var a = store.attempt(); var b = store.attempt(); var ca = new LogicalRecordContext(a); var cb = new LogicalRecordContext(b);
        // when
        storage.selectLogical(storage.openLogical(ca).withWork(early.work, early.barrier, early.source));
        storage.selectLogical(storage.openLogical(cb).withWork(late.work, late.barrier, late.source));
        ca.flush(); cb.flush(); var evidence = new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {2});
        var pa = a.prepare("a", List.of(), evidence); var pb = b.prepare("b", List.of(), evidence);
        // then
        assertTrue(store.publish(pb)); assertTrue(store.publish(pa));
        try (var select = store.attempt(); var apply = store.attempt()) {
            var cs = new LogicalRecordContext(select); var cp = new LogicalRecordContext(apply);
            var selected = storage.openLogical(cs); var changing = storage.openLogical(cp);
            assertEquals(early.work.workIdentity(), selected.nextDueWorkExcluding(Set.of()).work().workIdentity());
            storage.selectLogical(changing.withApplication(late.work, application(late.work, 100)));
            cs.flush(); cp.flush(); var ps = select.prepare("selection", List.of(), evidence); var pp = apply.prepare("application", List.of(), evidence);
            assertTrue(store.publish(pp)); assertTrue(store.publish(ps), "Later work completion cannot invalidate the earliest due row");
        }
        try (var cold = store.attempt()) {
            var restored = storage.openLogical(new LogicalRecordContext(cold));
            assertEquals(early.work.workIdentity(), storage.nextDue(restored, Set.of(),
                    id -> early.barrier, ignored -> early.source).work().workIdentity());
            assertTrue(storage.applicationByWork(restored, late.work.workIdentity()).found());
            assertFalse(storage.pending(restored, late.work.planIdentity()).found());
        }
    }

    @Test void warmWorkAndApplicationFramesStillRequireCurrentCrosslinks() {
        // given
        try (var cache = new RootedStorageCache(16 * 1024 * 1024, 100, 16 * 1024 * 1024)) {
            var bytes = new DocumentSessionStorageTest.Bytes(); var fixture = fixture(3, "consumer");
            var storage = new StoredCatchUpWorkIndexes(bytes, LIMITS, 256, cache);
            var stored = storage.retainPartition(ManagedCatchUpWorkIndex.empty().withWork(fixture.work, fixture.barrier, fixture.source));
            var selected = storage.pending(stored, fixture.work.planIdentity()).work();
            long loads = cache.statistics().loads();
            for (int i = 0; i < 5; i++) {
                // when
                var reopened = storage.open(roots(storage, stored)::get, 0, 0);
                // then
                assertSame(selected, storage.pending(reopened, fixture.work.planIdentity()).work());
            }
            assertEquals(loads, cache.statistics().loads());
            var s = stored.storedIndexes();
            var missingPending = state(s, s.work(), s.pending().remove(fixture.work.planIdentity()).map(),
                    s.applications(), s.applicationsByWork(), s.due());
            assertThrows(CoordinationObjectStorageException.class, () -> storage.work(missingPending, fixture.work.workIdentity()));
            var applied = stored.withApplication(selected, application(selected, 50));
            var receipt = storage.applicationByWork(applied, selected.workIdentity()).receipt();
            loads = cache.statistics().loads();
            assertSame(receipt, storage.applicationByWork(applied, selected.workIdentity()).receipt());
            assertEquals(loads, cache.statistics().loads());
            var a = applied.storedIndexes();
            var missingLink = state(a, a.work(), a.pending(), a.applications(),
                    a.applicationsByWork().remove(selected.workIdentity()).map(), a.due());
            assertThrows(CoordinationObjectStorageException.class,
                    () -> storage.application(missingLink, receipt.applicationReceiptIdentity()));
        }
    }

    @Test void cachedRegisteredWorkCannotBypassStricterNestedKeyLimit() throws Exception {
        // given
        try (var cache = new RootedStorageCache(16 * 1024 * 1024, 100, 16 * 1024 * 1024)) {
            var bytes = new DocumentSessionStorageTest.Bytes(); var fixture = fixture(3, "consumer");
            var permissive = new StoredCatchUpWorkIndexes(bytes, LIMITS, 256, cache);
            var retained = permissive.retainPartition(ManagedCatchUpWorkIndex.empty().withWork(fixture.work, fixture.barrier, fixture.source));
            var raw = retained.storedIndexes().work().get(fixture.work.workIdentity());
            var firstCodec = registeredCodec(permissive);
            // when
            byte[] frame = firstCodec.encode(raw);
            // then
            assertSame(raw, firstCodec.decode(frame));
            var strictLimits = new PersistentMapStorage.Limits(LIMITS.nodeBytes(), 8, LIMITS.valueBytes(), LIMITS.descriptorBytes(), LIMITS.cachedNodes());
            var strict = registeredCodec(new StoredCatchUpWorkIndexes(bytes, strictLimits, 256, cache));
            assertEquals(firstCodec.identity(), strict.identity(), "Transport format remains unchanged");
            assertThrows(CoordinationObjectStorageException.class, () -> strict.decode(frame));
        }
    }

    @SuppressWarnings("unchecked")
    private static PersistentMapCodec<ManagedCatchUpWorkIndex.RegisteredWork> registeredCodec(StoredCatchUpWorkIndexes index) throws Exception {
        var field = StoredCatchUpWorkIndexes.class.getDeclaredField("work"); field.setAccessible(true); var binding = field.get(index);
        var values = binding.getClass().getDeclaredField("values"); values.setAccessible(true);
        return (PersistentMapCodec<ManagedCatchUpWorkIndex.RegisteredWork>) values.get(binding);
    }

    @Test void coldDueAndApplicationIndexesPreserveOrderCountersDuplicatesAndOldRoots() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
        var resident = ManagedCatchUpWorkIndex.empty(); var fixtures = new ArrayList<Fixture>();
        for (int i = 1; i <= 16; i++) {
            var f = fixture(i, "consumer-" + i % 3); fixtures.add(f); resident = resident.withWork(f.work, f.barrier, f.source);
        }
        var retained = storage.retainPartition(resident); var roots = roots(storage, retained);
        var copy = bytes.copy(); var coldStorage = storage(copy); var cold = coldStorage.open(roots::get, retained.lastMutationComparisons(), retained.lastMutationNodeCopies());
        var barriers = new HashMap<String, ManagedCatchUpBarrier>(); var receipts = new HashMap<String, ManagedEpochReceipt>();
        // when
        fixtures.forEach(f -> { barriers.put(f.barrier.barrierIdentity(), f.barrier); receipts.put(f.source.receiptIdentity(), f.source); });
        for (var excluded : List.of(Set.<DocumentId>of(), Set.of(DocumentId.of("consumer-1")), Set.of(DocumentId.of("consumer-1"), DocumentId.of("consumer-2")))) {
            // then
            assertDue(resident.nextDueWorkExcluding(excluded), coldStorage.nextDue(cold, excluded, barriers::get, w -> receipts.get(w.sourceReceiptIdentity())));
        }
        for (var f : fixtures) {
            var original = resident.work(f.work.workIdentity()); var read = coldStorage.work(cold, f.work.workIdentity());
            assertEquals(original.work().workIdentity(), read.work().workIdentity()); assertEquals(original.indexComparisons(), read.indexComparisons());
            assertEquals(original.workRowsRead(), read.workRowsRead()); assertEquals(original.unrelatedPlanReads(), read.unrelatedPlanReads());
            assertEquals(f.work.workIdentity(), coldStorage.pending(cold, f.work.planIdentity()).work().workIdentity());
        }
        var selected = resident.nextDueWork().work(); var application = application(selected, 700);
        var referenceChanged = resident.withApplication(selected, application); var changed = cold.withApplication(selected, application);
        assertEquals(referenceChanged.lastMutationComparisons(), changed.lastMutationComparisons());
        assertEquals(referenceChanged.lastMutationNodeCopies(), changed.lastMutationNodeCopies());
        assertSame(changed, changed.withApplication(selected, application));
        assertArrayEquals(storage.applicationsCodec.encode(referenceChanged.storedIndexes().applications().get(application.applicationReceiptIdentity())),
                coldStorage.applicationsCodec.encode(changed.storedIndexes().applications().get(application.applicationReceiptIdentity())));
        assertEquals(application.applicationReceiptIdentity(), coldStorage.application(changed, application.applicationReceiptIdentity()).receipt().applicationReceiptIdentity());
        assertEquals(application.applicationReceiptIdentity(), coldStorage.applicationByWork(changed, selected.workIdentity()).receipt().applicationReceiptIdentity());
        var reopened = coldStorage.open(roots(coldStorage, changed)::get, changed.lastMutationComparisons(), changed.lastMutationNodeCopies());
        assertSame(reopened, reopened.withApplication(selected, application));
        assertDue(resident.nextDueWork(), coldStorage.nextDue(coldStorage.open(roots::get, 0, 0), Set.of(), barriers::get, w -> receipts.get(w.sourceReceiptIdentity())));
        int writes = copy.writes; coldStorage.applicationByWork(reopened, selected.workIdentity()); assertEquals(writes, copy.writes);
        var next = changed.nextDueWork().work();
        var retired = changed.withoutPendingWorkForPlan(next.planIdentity());
        var referenceRetired = referenceChanged.withoutPendingWorkForPlan(next.planIdentity());
        assertEquals(referenceRetired.lastMutationComparisons(), retired.lastMutationComparisons()); assertEquals(referenceRetired.lastMutationNodeCopies(), retired.lastMutationNodeCopies());
        assertTrue(coldStorage.work(retired, next.workIdentity()).found(), "Retired pending work retains its original immutable work");
        assertFalse(coldStorage.pending(retired, next.planIdentity()).found());
    }

    @Test void conflictingDueOrderingAndMissingCrosslinksArePhysicalFailuresNotAlternativeSelection() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes); var f = fixture(1, "consumer");
        var stored = storage.retainPartition(ManagedCatchUpWorkIndex.empty().withWork(f.work, f.barrier, f.source)); var s = stored.storedIndexes();
        var registered = s.work().get(f.work.workIdentity()); var k = registered.dueKey();
        var wrongOrder = new ManagedCatchUpWorkIndex.DueKey(order(-1), k.sourceOrder(), k.sourceDocumentId(), k.sourceEpoch(), k.consumerDocumentId(), k.targetPath(), k.activationGeneration());
        // when
        var extraEarlier = state(s, s.work(), s.pending(), s.applications(), s.applicationsByWork(), s.due().put(wrongOrder, f.work.workIdentity()).map());
        // then
        assertThrows(CoordinationObjectStorageException.class, () -> storage.nextDue(extraEarlier, Set.of(), id -> f.barrier, w -> f.source), "A duplicate earlier due key cannot overtake the registered canonical key");
        var missingPending = state(s, s.work(), s.pending().remove(f.work.planIdentity()).map(), s.applications(), s.applicationsByWork(), s.due());
        assertThrows(CoordinationObjectStorageException.class, () -> storage.work(missingPending, f.work.workIdentity()));
        var consistentlyWrongOrder = state(s, s.work().put(f.work.workIdentity(), new ManagedCatchUpWorkIndex.RegisteredWork(f.work, wrongOrder)).map(),
                s.pending(), s.applications(), s.applicationsByWork(), s.due().remove(k).map().put(wrongOrder, f.work.workIdentity()).map());
        assertThrows(CoordinationObjectStorageException.class, () -> storage.nextDue(consistentlyWrongOrder, Set.of(), id -> f.barrier, w -> f.source), "Exact source/barrier evidence remains authority even if both derived indexes agree");
        var applied = stored.withApplication(f.work, application(f.work, 50)); var a = applied.storedIndexes();
        var missingByWork = state(a, a.work(), a.pending(), a.applications(), a.applicationsByWork().remove(f.work.workIdentity()).map(), a.due());
        assertThrows(CoordinationObjectStorageException.class, () -> storage.application(missingByWork, application(f.work, 50).applicationReceiptIdentity()));
    }

    @Test void unavailableSelectedSourceAndFailedMultiRootStagingCannotDropOriginalPendingWork() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes); var f = fixture(4, "consumer");
        var stored = storage.retainPartition(ManagedCatchUpWorkIndex.empty().withWork(f.work, f.barrier, f.source));
        // when
        var roots = roots(storage, stored);
        // then
        assertThrows(CoordinationObjectStorageException.class, () -> storage.nextDue(stored, Set.of(), id -> f.barrier,
                w -> { throw new CoordinationObjectStorageException("Selected exact source unavailable"); }));
        bytes.failAtWrite = bytes.writes + 2;
        assertThrows(CoordinationObjectStorageException.class, () -> stored.withApplication(f.work, application(f.work, 55)));
        bytes.failAtWrite = -1;
        var original = storage.open(roots::get, 0, 0);
        assertEquals(f.work.workIdentity(), storage.pending(original, f.work.planIdentity()).work().workIdentity());
        assertFalse(storage.applicationByWork(original, f.work.workIdentity()).found());
        var result = original.withApplication(f.work, application(f.work, 55));
        assertEquals(1, result.applicationCount()); assertEquals(0, result.dueCount());
        assertSame(result, result.withApplication(f.work, application(f.work, 55)));
        bytes.failRead = true; assertThrows(CoordinationObjectStorageException.class, () -> storage.open(roots::get, 0, 0));
        bytes.failRead = false; assertEquals(f.work.workIdentity(), storage.work(storage.open(roots::get, 0, 0), f.work.workIdentity()).work().workIdentity());
    }

    @Test void applicationTransportCannotSilentlyChangeReceiptCoordinatesOrOriginalWork() {
        // given
        var storage = storage(new DocumentSessionStorageTest.Bytes()); var f = fixture(9, "consumer");
        var receipt = application(f.work, 91); var row = new ManagedCatchUpWorkIndex.RegisteredApplication(receipt, f.work);
        // when
        byte[] encoded = storage.applicationsCodec.encode(row);
        // then
        assertArrayEquals(encoded, storage.applicationsCodec.encode(storage.applicationsCodec.decode(encoded)));
        var standalone = new ManagedApplicationStorageCodec(LIMITS.valueBytes(), 256);
        byte[] packet = standalone.encode(receipt, f.work); var decoded = standalone.decode(packet);
        assertArrayEquals(packet, standalone.encode(decoded.receipt(), decoded.work()));
        packet[0] ^= 1;
        assertThrows(CoordinationObjectStorageException.class, () -> standalone.decode(packet));
        encoded[encoded.length - 1] = 2;
        assertThrows(CoordinationObjectStorageException.class, () -> storage.applicationsCodec.decode(encoded));
        var another = fixture(10, "consumer");
        assertThrows(IllegalArgumentException.class, () -> new ManagedCatchUpWorkIndex.RegisteredApplication(receipt, another.work));
        assertThrows(CoordinationObjectStorageException.class, () -> storage.open(r -> new byte[]{1, 2, 3}, 0, 0));
    }

    private static ManagedCatchUpWorkIndex state(ManagedCatchUpWorkIndex.StoredIndexes s,
            PersistentOrderedMap<String, ManagedCatchUpWorkIndex.RegisteredWork> work, PersistentOrderedMap<String, String> pending,
            PersistentOrderedMap<String, ManagedCatchUpWorkIndex.RegisteredApplication> applications, PersistentOrderedMap<String, String> byWork,
            PersistentMinimumMap<ManagedCatchUpWorkIndex.DueKey, String> due) {
        return ManagedCatchUpWorkIndex.restoreIndexes(new ManagedCatchUpWorkIndex.StoredIndexes(work, pending, applications, byWork, due, s.comparisons(), s.copiedNodes()));
    }
    private static void assertDue(ManagedCatchUpWorkIndex.DueWorkRead expected, ManagedCatchUpWorkIndex.DueWorkRead actual) {
        assertEquals(expected.found(), actual.found()); if (expected.found()) assertEquals(expected.work().workIdentity(), actual.work().workIdentity());
        assertEquals(expected.indexRowsRead(), actual.indexRowsRead()); assertEquals(expected.workRowsRead(), actual.workRowsRead()); assertEquals(expected.unrelatedPlanReads(), actual.unrelatedPlanReads());
    }
    private static EnumMap<StoredCatchUpWorkIndexes.Root, byte[]> roots(StoredCatchUpWorkIndexes storage, ManagedCatchUpWorkIndex index) {
        var result = new EnumMap<StoredCatchUpWorkIndexes.Root, byte[]>(StoredCatchUpWorkIndexes.Root.class);
        for (var root : StoredCatchUpWorkIndexes.Root.values()) result.put(root, storage.root(index, root)); return result;
    }
    private static Fixture fixture(int seed, String consumerName) {
        var source = DocumentId.of("source-" + seed); var consumer = DocumentId.of(consumerName); String plan = hash(1000 + seed);
        var barrier = ManagedCatchUpBarrier.identified(consumer, hash(2000 + seed), order(seed), List.of(plan), ManagedCatchUpBarrierStatus.OPEN, null, null);
        var sourceReceipt = ManagedEpochReceipt.identified(source, 0, DocumentRevision.Kind.INITIALIZATION, null, exact("source-" + seed),
                hash(3000 + seed), null, order(seed * 10), hash(4000 + seed), hash(5000 + seed), List.of(), seed);
        var work = ManagedEpochApplicationWork.identified(plan, barrier.barrierIdentity(), sourceReceipt.receiptIdentity(), source, 0, consumer,
                hash(6000 + seed), "/child/" + seed, 1, 0, exact("consumer-" + seed).blueId(), 0);
        return new Fixture(work, barrier, sourceReceipt);
    }
    private static ManagedEpochApplicationReceipt application(ManagedEpochApplicationWork work, int seed) {
        return ManagedEpochApplicationReceipt.identified(work.workIdentity(), work.planIdentity(), work.sourceReceiptIdentity(), hash(seed), hash(seed + 1),
                hash(seed + 2), work.consumerDocumentId(), 1, hash(seed + 3), exact("result-" + seed).blueId(), 1);
    }
    private static String hash(int n) { return "sha256:" + String.format("%064x", n); }
    private static ExternalOrderKey order(long n) { return ExternalOrderKey.of(List.of(n)); }
    private static ExactValue exact(String value) { return ExactValue.verified(new Node().properties("value", new Node().value(value))); }
    private static StoredCatchUpWorkIndexes storage(DocumentSessionStorageTest.Bytes bytes) { return new StoredCatchUpWorkIndexes(bytes, LIMITS, 256); }
    private record Fixture(ManagedEpochApplicationWork work, ManagedCatchUpBarrier barrier, ManagedEpochReceipt source) { }
}
