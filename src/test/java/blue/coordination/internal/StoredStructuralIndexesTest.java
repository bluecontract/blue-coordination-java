package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ComponentKind;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

final class StoredStructuralIndexesTest {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(4 * 1024 * 1024, 4096, 3 * 1024 * 1024, 4096, 32);
    private static final DocumentSessionStorage.Limits SESSION = new DocumentSessionStorage.Limits(32 * 1024 * 1024, 256, 256L * 1024 * 1024);

    @Test void actualPendingJoinOriginalResultRoundTripsWithoutSessionOrIndexRestoration() throws Exception {
        try (var f = new RootedJoinEligibilityTest.Fixture()) {
            var a = f.start("A"); var b = f.start("B");
            f.attach(a, "b", b); f.settle(a); f.attach(b, "a", a);
            var result = f.engine.documents().require(b.id()).rootedView().result();
            assertTrue(result.commits()); assertNotNull(result.rootedProjection());
            var codec = new blue.language.processor.closure.ClosureProcessResultStorageCodec(32 * 1024 * 1024, 256);
            byte[] bytes = codec.encode(result);
            var artifact = java.nio.file.Path.of("build/reports/stored-structural-indexes/pending-join-original-result.bin");
            java.nio.file.Files.createDirectories(artifact.getParent()); java.nio.file.Files.write(artifact, bytes);
            assertArrayEquals(bytes, codec.encode(codec.decode(bytes)));
        }
    }

    @Test void actualPendingJoinOpensBothDirectionsFromExactColdSessionsThenRetiresWithoutChangingOldRoots() throws Exception {
        try (var f = new RootedJoinEligibilityTest.Fixture()) {
            var a = f.start("A"); var b = f.start("B"); var unrelated = f.start("Unrelated");
            f.attach(a, "b", b); f.settle(a); f.attach(b, "a", a);
            var before = state(f.engine); assertTrue(before.componentIndex().hasRootedViews());
            assertEquals(List.of(b.id()), before.componentIndex().pendingJoinRootsFor(a.id()));
            var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new StoredTopologyIndexes(bytes, LIMITS);
            var stored = storage.retainPartition(before.componentIndex()); var sessions = new DocumentSessionStorage(bytes, SESSION);
            var addresses = new HashMap<DocumentId, String>();
            for (var id : List.of(a.id(), b.id())) addresses.put(id, sessions.retain(f.engine.documents().require(id)));
            var coldBytes = bytes.copy(); var cold = new StoredTopologyIndexes(coldBytes, LIMITS);
            var reopened = cold.open(root -> storage.root(stored, root), stored.hasRootedViews());
            int writes = coldBytes.writes;
            try (var scope = new DocumentSessionStorage(coldBytes, SESSION).openScope()) {
                assertEquals(List.of(b.id()), cold.pendingRoots(reopened, a.id(), id -> {
                    assertNotEquals(unrelated.id(), id); return scope.open(id, addresses.get(id)).rootedView();
                }));
                cold.requireJoinRoot(reopened, b.id(), scope.open(b.id(), addresses.get(b.id())).rootedView());
                for (var id : List.of(a.id(), b.id())) assertEquals(before.componentIndex().component(id), cold.component(reopened, id).orElseThrow());
                var emptyJoins = RootedJoinCandidateIndex.empty(); var s = before.componentIndex().storedIndexes();
                var without = storage.retainPartition(ProcessEmbeddedComponentIndex.restoreIndexes(new ProcessEmbeddedComponentIndex.StoredIndexes(
                        s.components(), s.targets(), s.sources(), true, emptyJoins)));
                var missingReverse = cold.open(root -> storage.root(root == StoredTopologyIndexes.Root.JOIN_ROOTS ? without : stored, root), true);
                assertThrows(CoordinationObjectStorageException.class, () -> cold.requireJoinRoot(missingReverse, b.id(), scope.open(b.id(), addresses.get(b.id())).rootedView()));
            }
            assertEquals(writes, coldBytes.writes);
            f.settle(b); var complete = state(f.engine); assertTrue(complete.componentIndex().pendingJoinRootsFor(a.id()).isEmpty());
            var after = storage.retainPartition(complete.componentIndex());
            assertTrue(storage.open(root -> storage.root(after, root), true).pendingJoinRootsFor(a.id()).isEmpty());
            assertEquals(List.of(b.id()), reopened.pendingJoinRootsFor(a.id()));
            assertEquals(complete.componentIndex().components(), storage.open(root -> storage.root(after, root), true).components());
        }
    }

    @Test void actualCyclicProofBytesRemainBoundToOriginalPublicationAndRejectAlteredProofOrMissingMembership() throws Exception {
        try (var f = new RootedJoinEligibilityTest.Fixture()) {
            var a = f.start("A"); var b = f.start("B"); f.attach(a, "b", b); f.settle(a); f.attach(b, "a", a); f.settle(b);
            var current = state(f.engine); var original = current.componentStateInventory().forDocument(a.id());
            assertEquals(ComponentKind.CYCLIC, original.kind()); assertNotNull(original.completeCyclicProof());
            var authority = f.engine.documents().require(a.id()).rootedView().result().resultingComponents();
            var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new StoredComponentStateIndexes(bytes, LIMITS, 256);
            var stored = storage.retainPartition(current.componentStateInventory());
            var cold = new StoredComponentStateIndexes(bytes.copy(), LIMITS, 256); var reopened = cold.open(root -> storage.root(stored, root));
            assertArrayEquals(storage.exactRow(original), cold.exactRow(cold.forDocument(reopened, a.id(), authority).orElseThrow()));
            assertNotSame(original, cold.forDocument(reopened, a.id(), authority).orElseThrow());
            var proof = new ArrayList<>(original.completeCyclicProof().declaredPlaceholderSet()); proof.set(0, proof.get(0).clone().name("altered-original-proof"));
            var changed = new ComponentSnapshot(original.componentIdentity(), original.componentStateIdentity(), original.componentGeneration(), original.kind(),
                    original.orderedMemberDocumentIds(), original.orderedMemberBlueIds(), original.masterBlueId(),
                    blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(proof), original.cyclicProofIdentity());
            assertThrows(CoordinationObjectStorageException.class, () -> cold.forDocument(reopened, a.id(), List.of(changed)));
            var s = stored.storedIndexes(); var missing = storage.retainPartition(ComponentStateInventory.restoreIndexes(new ComponentStateInventory.StoredIndexes(
                    s.lineages(), s.states(), s.documents().remove(b.id()).map())));
            assertThrows(CoordinationObjectStorageException.class, () -> storage.forDocument(missing, a.id(), authority));
            assertThrows(CoordinationObjectStorageException.class, () -> cold.forDocument(reopened, a.id(), List.of()));
        }
    }

    @Test void actualSameEpochRepresentationPreservesStoredSubscriptionMutationCountersAndCompleteRows() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml") + """
                  emitUnmatched:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendEvent:
                          type: Coordination/Event
                          kind: RCP2/Unmatched
                      - $return: true
                """, "rcp2/source", ActivationPolicy.importFullHistory());
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n", "rcp2/parent", ActivationPolicy.importFullHistory());
            var before = state(f.engine); var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new StoredSubscriptionIndexes(bytes, LIMITS);
            var stored = storage.retainPartition(before.closureSubscriptions()); var coldBytes = bytes.copy();
            var cold = new StoredSubscriptionIndexes(coldBytes, LIMITS); var reopened = open(cold, stored);
            assertFalse(cold.statesFor(reopened, parent.id()).isEmpty());
            assertSubscriptions(before.closureSubscriptions(), reopened, parent.id(), cold);
            assertDemands(before.closureSubscriptions().embeddedDemandsFor(parent.id()), cold.demandsFor(reopened, parent.id()));
            String initial = parent.snapshot().blueId();
            f.process(parent, f.append(source, "rcp2/source", "emitUnmatched"));
            var next = f.engine.documents().require(parent.id()); assertEquals(0L, next.epoch()); assertNotEquals(initial, next.currentRepresentation().blueId());
            var fences = Map.of(parent.id(), before.graphGenerations().require(parent.id())); var result = next.rootedView().result();
            var expected = before.closureSubscriptions().applyOwned(result, fences); var changed = reopened.applyOwned(result, fences);
            assertSubscriptions(expected, changed, parent.id(), cold);
            assertEquals(expected.lastOperationComparisonsForTesting(), changed.lastOperationComparisonsForTesting());
            assertEquals(expected.lastOperationCopiedNodesForTesting(), changed.lastOperationCopiedNodesForTesting());
            assertEquals(expected.lastOperationVisitedRowsForTesting(), changed.lastOperationVisitedRowsForTesting());
            assertSubscriptions(before.closureSubscriptions(), open(cold, stored), parent.id(), cold);
            var latest = cold.retainPartition(changed); assertSubscriptions(expected, open(cold, latest), parent.id(), cold);
            var s = latest.storedIndexes(); var one = cold.statesFor(latest, parent.id()).get(0);
            var broken = ClosureSubscriptionInventory.restoreIndexes(new ClosureSubscriptionInventory.StoredIndexes(s.slots(),
                    s.identities().remove(one.subscriptionIdentity()).map(), s.documents(), s.demands(), s.comparisons(), s.copiedNodes(), s.visitedRows()));
            assertThrows(CoordinationObjectStorageException.class, () -> cold.statesFor(broken, parent.id()));
        }
    }

    private static ClosureSubscriptionInventory open(StoredSubscriptionIndexes storage, ClosureSubscriptionInventory value) {
        var s = value.storedIndexes(); return storage.open(root -> storage.root(value, root), s.comparisons(), s.copiedNodes(), s.visitedRows());
    }
    private static InMemoryDocumentStore.StoreState state(DefaultCoordinationEngine engine) throws Exception {
        Field field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true); return (InMemoryDocumentStore.StoreState) field.get(engine.documents());
    }
    private static void assertSubscriptions(ClosureSubscriptionInventory expected, ClosureSubscriptionInventory actual, DocumentId id, StoredSubscriptionIndexes storage) {
        var codec = new StoreIndexCodecs(new DocumentSessionStorageTest.Bytes(), LIMITS).subscriptions;
        var a = expected.statesFor(id); var b = storage.statesFor(actual, id); assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertArrayEquals(codec.encode(a.get(i)), codec.encode(b.get(i)));
    }
    private static void assertDemands(List<ClosureSubscriptionInventory.EmbeddedDemand> expected, List<ClosureSubscriptionInventory.EmbeddedDemand> actual) {
        var codec = new StoreIndexCodecs(new DocumentSessionStorageTest.Bytes(), LIMITS).embeddedDemands; assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(codec.encode(expected.get(i)), codec.encode(actual.get(i)));
    }
}
