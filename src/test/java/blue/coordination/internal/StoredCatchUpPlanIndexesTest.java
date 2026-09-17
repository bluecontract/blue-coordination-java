package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.coordination.sdk.ActivationPolicy;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredCatchUpPlanIndexesTest {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(65536, 4096, 32768, 2048, 8);

    @Test void actualSourceParentAdmissionRowsRemainExactAfterProducerClosure() throws Exception {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
        EnumMap<StoredCatchUpPlanIndexes.Root, byte[]> roots;
        byte[] barrierRoot; DocumentId parentId; DocumentId sourceId;
        List<byte[]> expectedPlans; List<byte[]> expectedBarriers;
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(DocumentSessionStorageTest.resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String admittedSource = source.snapshot().blueId();
            f.process(source, f.append(source, "rcp2/source", "tick")); f.retain(source);
            var parent = f.start(DocumentSessionStorageTest.resource("parent.yaml") + "\nchild: {blueId: " + admittedSource + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            parentId = parent.id(); sourceId = source.id();
            var original = f.engine.documents().catchUpPlansSnapshot();
            // when
            var selected = original.plansForConsumer(parentId).plans();
            // then
            assertFalse(selected.isEmpty(), "The actual rooted admission must create retained catch-up evidence");
            assertTrue(selected.stream().allMatch(p -> p.sourceDocumentId().equals(sourceId)));
            expectedPlans = selected.stream().map(storage.planCodec::encode).toList();
            expectedBarriers = selected.stream().map(ManagedOccurrenceCatchUpPlan::barrierIdentity).distinct()
                    .map(id -> storage.barrierCodec.encode(original.barrier(id).barrier())).toList();
            int reads = f.providerReads.get();
            roots = roots(storage, storage.retainPartition(original.storedPlans()));
            barrierRoot = storage.retainBarriers(original.storedBarriers()).storedRootDescriptor();
            assertEquals(reads, f.providerReads.get());
        }
        var coldBytes = bytes.copy(); var cold = storage(coldBytes); var plans = cold.open(roots::get, 0, 0);
        var barriers = cold.openBarriers(barrierRoot); int writes = coldBytes.writes;
        var selected = cold.forConsumer(plans, parentId).plans();
        assertEquals(expectedPlans.size(), selected.size());
        for (int i = 0; i < selected.size(); i++) assertArrayEquals(expectedPlans.get(i), cold.planCodec.encode(selected.get(i)));
        var selectedBarriers = selected.stream().map(ManagedOccurrenceCatchUpPlan::barrierIdentity).distinct().toList();
        assertEquals(expectedBarriers.size(), selectedBarriers.size());
        for (int i = 0; i < selectedBarriers.size(); i++) assertArrayEquals(expectedBarriers.get(i), cold.barrierCodec.encode(cold.barrier(plans, barriers, selectedBarriers.get(i))));
        assertEquals(selected.size(), cold.forSource(plans, sourceId).plans().size());
        assertEquals(writes, coldBytes.writes);
    }

    @Test void exactPlanAndBarrierRowsKeepAllProgressStatusesAndMixedLogicalOrderComponents() {
        // given
        var storage = storage(new DocumentSessionStorageTest.Bytes());
        for (var status : ManagedCatchUpStatus.values()) {
            var f = fixture(1, DocumentId.of("consumer"), DocumentId.of("source"));
            // when
            var row = progress(f.plan, status);
            // then
            assertArrayEquals(storage.planCodec.encode(row), storage.planCodec.encode(storage.planCodec.decode(storage.planCodec.encode(row))));
            var initial = ManagedCatchUpPlanIndex.empty().withPlan(f.plan, false);
            var index = status == ManagedCatchUpStatus.COMPLETE ? complete(initial, f.plan) : initial.withPlan(row, false);
            var barrier = CatchUpPlanStore.canonicalBarrier(index, f.barrier);
            assertArrayEquals(storage.barrierCodec.encode(barrier), storage.barrierCodec.encode(storage.barrierCodec.decode(storage.barrierCodec.encode(barrier))));
            assertEquals(f.barrier.causeOrder().components(), storage.barrierCodec.decode(storage.barrierCodec.encode(barrier)).causeOrder().components());
        }
        var waiting = progress(fixture(3, DocumentId.of("c"), DocumentId.of("s")).plan, ManagedCatchUpStatus.WAITING_FOR_HISTORY);
        byte[] wrongIdentity = storage.planCodec.encode(waiting); wrongIdentity[wrongIdentity.length - 1] ^= 1;
        assertThrows(CoordinationObjectStorageException.class, () -> storage.planCodec.decode(wrongIdentity), "Complete row identity must reject changed waiting evidence");
    }

    @Test void coldIndexesPreserveCanonicalBucketsMutationShapeCountersAndOldRoots() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
        var resident = ManagedCatchUpPlanIndex.empty(); var rows = new ArrayList<Fixture>();
        for (int i = 1; i <= 48; i++) {
            var f = fixture(i, DocumentId.of("consumer-" + i % 3), DocumentId.of("source-" + i % 5));
            rows.add(f); resident = resident.withPlan(f.plan, false);
        }
        var retained = storage.retainPartition(resident); var roots = roots(storage, retained);
        var coldBytes = bytes.copy(); var coldStorage = storage(coldBytes);
        // when
        var cold = coldStorage.open(roots::get, retained.lastMutationComparisons(), retained.lastMutationNodeCopies());
        // then
        assertEquals(resident.lastMutationComparisons(), cold.lastMutationComparisons());
        assertEquals(resident.lastMutationNodeCopies(), cold.lastMutationNodeCopies());
        int writes = coldBytes.writes;
        for (int i = 0; i < 5; i++) {
            var id = DocumentId.of("source-" + i);
            assertAudit(resident.forSource(id), coldStorage.forSource(cold, id));
        }
        for (var f : rows) {
            assertArrayEquals(storage.planCodec.encode(f.plan), coldStorage.planCodec.encode(coldStorage.exact(cold, f.plan.planIdentity()).plan()));
            assertAudit(resident.forConsumer(f.plan.consumerDocumentId()), coldStorage.forConsumer(cold, f.plan.consumerDocumentId()));
            assertAudit(resident.forOccurrence(f.plan.targetOccurrenceIdentity()), coldStorage.forOccurrence(cold, f.plan.targetOccurrenceIdentity()));
        }
        assertEquals(writes, coldBytes.writes, "Selected reads cannot retain new rows");
        var selected = rows.get(0); var waiting = progress(selected.plan, ManagedCatchUpStatus.WAITING_FOR_HISTORY);
        var residentChanged = resident.withPlan(waiting, false); var coldChanged = cold.withPlan(waiting, false);
        assertEquals(residentChanged.lastMutationComparisons(), coldChanged.lastMutationComparisons());
        assertEquals(residentChanged.lastMutationNodeCopies(), coldChanged.lastMutationNodeCopies());
        assertEquals(ManagedCatchUpStatus.PENDING, coldStorage.exact(coldStorage.open(roots::get, 0, 0), selected.plan.planIdentity()).plan().status());
        assertEquals(ManagedCatchUpStatus.WAITING_FOR_HISTORY, coldStorage.exact(coldChanged, selected.plan.planIdentity()).plan().status());
        coldChanged.assertStructurallyValid();
        var complete = complete(coldChanged, selected.plan);
        assertAudit(complete(residentChanged, selected.plan).forSource(selected.plan.sourceDocumentId()),
                coldStorage.forSource(complete, selected.plan.sourceDocumentId()));
    }

    @Test void mixedFamilyRootsMissingMembersAndFalseActiveCountsRejectSelectedAuthority() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
        var f = fixture(10, DocumentId.of("c"), DocumentId.of("s"));
        var retained = storage.retainPartition(ManagedCatchUpPlanIndex.empty().withPlan(f.plan, false)); var roots = roots(storage, retained);
        for (var missing : List.of(StoredCatchUpPlanIndexes.Root.CONSUMER, StoredCatchUpPlanIndexes.Root.SOURCE,
                StoredCatchUpPlanIndexes.Root.OCCURRENCE, StoredCatchUpPlanIndexes.Root.BARRIER, StoredCatchUpPlanIndexes.Root.ACTIVE_SOURCE)) {
            // when
            var mixed = storage.open(r -> r == missing ? null : roots.get(r), 0, 0);
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(mixed, f.plan.planIdentity()), missing.name());
        }
        var s = retained.storedIndexes();
        var wrongCount = ManagedCatchUpPlanIndex.restoreIndexes(new ManagedCatchUpPlanIndex.StoredIndexes(s.identities(), s.consumers(), s.sources(),
                s.occurrences(), s.barriers(), s.activeSources().put(f.plan.sourceDocumentId(), 2).map(), s.comparisons(), s.copiedNodes()));
        assertThrows(CoordinationObjectStorageException.class, () -> storage.forSource(wrongCount, f.plan.sourceDocumentId()));
        // Family binding is authenticated before a superficially compatible map can supply memberships.
        assertThrows(CoordinationObjectStorageException.class, () -> storage.open(
                r -> r == StoredCatchUpPlanIndexes.Root.CONSUMER ? roots.get(StoredCatchUpPlanIndexes.Root.SOURCE) : roots.get(r), 0, 0));
    }

    @Test void barrierSnapshotRequiresEveryExactMemberAndCurrentCanonicalStatusWithoutReexecutingWork() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
        var f = fixture(21, DocumentId.of("consumer"), DocumentId.of("source"));
        var store = CatchUpPlanStore.empty().withPlan(f.plan).withBarrier(f.barrier);
        var plans = storage.retainPartition(store.storedPlans()); var barrierRows = storage.retainBarriers(store.storedBarriers());
        var coldStorage = storage(bytes.copy()); var cold = coldStorage.open(roots(storage, plans)::get, 0, 0);
        // when
        var coldBarriers = coldStorage.openBarriers(barrierRows.storedRootDescriptor());
        // then
        assertArrayEquals(storage.barrierCodec.encode(f.barrier), coldStorage.barrierCodec.encode(coldStorage.barrier(cold, coldBarriers, f.barrier.barrierIdentity())));
        var changedStore = store.withWaitingForHistory(f.plan.planIdentity(), "EXACT_HISTORY_MISSING", "keep exact waiting message");
        var changedPlans = cold.withPlan(changedStore.plan(f.plan.planIdentity()).plan(), false);
        assertThrows(CoordinationObjectStorageException.class, () -> coldStorage.barrier(changedPlans, coldBarriers, f.barrier.barrierIdentity()), "Old OPEN barrier cannot accompany new WAITING plan");
        var changedRows = coldStorage.retainBarriers(changedStore.storedBarriers());
        assertEquals(ManagedCatchUpBarrierStatus.WAITING_FOR_HISTORY, coldStorage.barrier(changedPlans, changedRows, f.barrier.barrierIdentity()).status());
        var emptyBarriers = coldStorage.openBarriers(null);
        assertThrows(CoordinationObjectStorageException.class, () -> coldStorage.barrier(changedPlans, emptyBarriers, f.barrier.barrierIdentity()));
        assertNull(coldStorage.barrier(cold, coldBarriers, hash(999)));
        assertEquals(ManagedCatchUpBarrierStatus.OPEN, coldStorage.barrier(cold, coldBarriers, f.barrier.barrierIdentity()).status());
    }

    @Test void unavailableOrCorruptSelectedBytesNeverBecomeAbsentAndFailedStagingKeepsPriorRoots() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
        var f = fixture(30, DocumentId.of("c"), DocumentId.of("s"));
        var retained = storage.retainPartition(ManagedCatchUpPlanIndex.empty().withPlan(f.plan, false));
        // when
        var roots = roots(storage, retained);
        // then
        assertFalse(storage.exact(retained, hash(999)).found());
        bytes.failRead = true;
        assertThrows(CoordinationObjectStorageException.class, () -> storage.open(roots::get, 0, 0));
        bytes.failRead = false;
        var reopened = storage.open(roots::get, 0, 0);
        bytes.badAck = true;
        assertThrows(CoordinationObjectStorageException.class, () -> reopened.withPlan(progress(f.plan, ManagedCatchUpStatus.WAITING_FOR_HISTORY), false));
        bytes.badAck = false;
        assertEquals(ManagedCatchUpStatus.PENDING, storage.exact(storage.open(roots::get, 0, 0), f.plan.planIdentity()).plan().status());
        var corrupt = bytes.copy(); corrupt.records.replaceAll((id, value) -> { byte[] changed = value.clone(); changed[0] ^= 1; return changed; });
        assertThrows(CoordinationObjectStorageException.class, () -> storage(corrupt).open(roots::get, 0, 0));
    }

    @Test void blockedCanonicalStatusDoesNotHideAnotherMembersMismatchedOwner() {
        // given
        var storage = storage(new DocumentSessionStorageTest.Bytes());
        var f = fixture(71, DocumentId.of("consumer"), DocumentId.of("source"));
        var blocked = progress(f.plan, ManagedCatchUpStatus.BLOCKED);
        ManagedOccurrenceCatchUpPlan wrong = null;
        for (int seed = 72; seed < 1000; seed++) {
            var candidate = ManagedOccurrenceCatchUpPlan.identified(f.plan.barrierIdentity(), DocumentId.of("other-consumer"), hash(seed), "/other", 1,
                    f.plan.sourceDocumentId(), -1, f.plan.admittedSourceBlueId(), 0, 2, f.plan.causedByIdentity(), ManagedCatchUpStatus.PENDING, null, null);
            if (candidate.planIdentity().compareTo(blocked.planIdentity()) > 0) { wrong = candidate;
            // when
            break; }
        }
        // then
        assertNotNull(wrong, "Fixture must put the malformed member after the blocking member");
        var index = ManagedCatchUpPlanIndex.empty().withPlan(f.plan, false).withPlan(blocked, false).withPlan(wrong, false);
        var barrier = ManagedCatchUpBarrier.identified(f.plan.consumerDocumentId(), f.plan.causedByIdentity(), f.barrier.causeOrder(),
                List.of(blocked.planIdentity(), wrong.planIdentity()), ManagedCatchUpBarrierStatus.BLOCKED,
                blocked.waitingCode().orElseThrow(), blocked.waitingMessage().orElse(null));
        var rows = storage.retainBarriers(PersistentOrderedMap.<String, ManagedCatchUpBarrier>empty(EmbeddingBinding.TEXT_ORDER).put(barrier.barrierIdentity(), barrier).map());
        var stored = storage.retainPartition(index);
        assertThrows(CoordinationObjectStorageException.class, () -> storage.barrier(stored, rows, barrier.barrierIdentity()));
    }

    private static void assertAudit(ManagedCatchUpPlanIndex.PlanAudit expected, ManagedCatchUpPlanIndex.PlanAudit actual) {
        assertEquals(expected.plans().stream().map(ManagedOccurrenceCatchUpPlan::snapshotIdentity).toList(), actual.plans().stream().map(ManagedOccurrenceCatchUpPlan::snapshotIdentity).toList());
        assertEquals(expected.indexComparisons(), actual.indexComparisons()); assertEquals(expected.planRowsRead(), actual.planRowsRead());
        assertEquals(expected.unrelatedPlanReads(), actual.unrelatedPlanReads());
    }
    private static EnumMap<StoredCatchUpPlanIndexes.Root, byte[]> roots(StoredCatchUpPlanIndexes storage, ManagedCatchUpPlanIndex index) {
        var roots = new EnumMap<StoredCatchUpPlanIndexes.Root, byte[]>(StoredCatchUpPlanIndexes.Root.class);
        for (var root : StoredCatchUpPlanIndexes.Root.values()) roots.put(root, storage.root(index, root));
        return roots;
    }
    private static ManagedOccurrenceCatchUpPlan progress(ManagedOccurrenceCatchUpPlan p, ManagedCatchUpStatus status) {
        boolean waiting = status == ManagedCatchUpStatus.WAITING_FOR_HISTORY || status == ManagedCatchUpStatus.BLOCKED;
        return ManagedOccurrenceCatchUpPlan.identified(p.barrierIdentity(), p.consumerDocumentId(), p.targetOccurrenceIdentity(), p.targetPath(),
                p.activationGeneration(), p.sourceDocumentId(), p.admittedSourceEpoch(), p.admittedSourceBlueId(),
                status == ManagedCatchUpStatus.COMPLETE ? p.requiredThroughSourceEpoch() + 1 : p.nextSourceEpoch(),
                p.requiredThroughSourceEpoch(), p.causedByIdentity(), status, waiting ? "MISSING_HISTORY" : null, waiting ? "Exact source position unavailable" : null);
    }
    private static ManagedCatchUpPlanIndex complete(ManagedCatchUpPlanIndex index, ManagedOccurrenceCatchUpPlan p) {
        for (long cursor = p.nextSourceEpoch() + 1; cursor <= p.requiredThroughSourceEpoch() + 1; cursor++) {
            index = index.withPlan(ManagedOccurrenceCatchUpPlan.identified(p.barrierIdentity(), p.consumerDocumentId(), p.targetOccurrenceIdentity(),
                    p.targetPath(), p.activationGeneration(), p.sourceDocumentId(), p.admittedSourceEpoch(), p.admittedSourceBlueId(), cursor,
                    p.requiredThroughSourceEpoch(), p.causedByIdentity(), cursor == p.requiredThroughSourceEpoch() + 1 ? ManagedCatchUpStatus.COMPLETE : ManagedCatchUpStatus.RUNNING,
                    null, null), true);
        }
        return index;
    }
    private static Fixture fixture(int seed, DocumentId consumer, DocumentId source) {
        var order = ExternalOrderKey.of(List.of(BigInteger.valueOf(seed), "source", BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE)));
        var definition = ManagedCatchUpBarrier.identified(consumer, hash(seed), order, List.of(), ManagedCatchUpBarrierStatus.OPEN, null, null);
        var value = ExactValue.verified(new Node().properties("state", new Node().value("admitted-" + seed)));
        var plan = ManagedOccurrenceCatchUpPlan.identified(definition.barrierIdentity(), consumer, hash(1000 + seed), "/child/" + seed, 1,
                source, -1, value.blueId(), 0, 2, hash(seed), ManagedCatchUpStatus.PENDING, null, null);
        var barrier = ManagedCatchUpBarrier.identified(consumer, hash(seed), order, List.of(plan.planIdentity()), ManagedCatchUpBarrierStatus.OPEN, null, null);
        return new Fixture(plan, barrier);
    }
    private static String hash(int value) { return "sha256:" + String.format("%064x", value); }
    private static StoredCatchUpPlanIndexes storage(DocumentSessionStorageTest.Bytes bytes) { return new StoredCatchUpPlanIndexes(bytes, LIMITS); }
    private record Fixture(ManagedOccurrenceCatchUpPlan plan, ManagedCatchUpBarrier barrier) { }
}
