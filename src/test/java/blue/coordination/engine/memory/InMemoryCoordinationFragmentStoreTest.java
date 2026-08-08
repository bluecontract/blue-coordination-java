package blue.coordination.engine.memory;

import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationCanonicalFragmentHandleStore;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.internal.CoordinationProcessingViews;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryCoordinationFragmentStoreTest
        extends CoordinationFragmentStoreContract {

    @Override
    CoordinationFragmentStore createStore() {
        return new InMemoryCoordinationFragmentStore(
                CoordinationEngineStorageTestFixtures.PROFILE);
    }

    @Test
    void shouldReportPhysicalDeduplicationAndReadMetricsExactly() {
        // given
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationEngineStorageTestFixtures.PROFILE);
        Node exact = new Node().value("metrics");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
        store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact);
        store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact);
        store.resetReadCounts();

        // when
        store.fetchByBlueId(blueId);
        store.fetchResultByBlueId("absent-fragment");
        store.readAll(Arrays.asList(blueId, "absent-fragment"));

        // then
        assertEquals(1, store.physicalFragmentCount());
        assertEquals(2L, store.singleReadCount());
        assertEquals(1L, store.batchReadCount());
        assertEquals(4L, store.requestedIdentityCount());
    }

    @Test
    void shouldReportOneInventoryAfterRepeatedIdempotentPersistence() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("count");
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(graph);

        // when
        store.putInventory(graph.inventory);
        store.putInventory(graph.inventory);

        // then
        assertEquals(1, store.inventoryCount());
    }

    @Test
    void shouldReadTwoInventoryPartitionsInOnePhysicalBatch() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph root =
                CoordinationEngineStorageTestFixtures.graph("multi-root");
        CoordinationEngineStorageTestFixtures.FragmentGraph event =
                CoordinationEngineStorageTestFixtures.graph("multi-event");
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(
                        root, event);
        store.putInventory(root.inventory);
        store.putInventory(event.inventory);
        store.putProcessingViews(
                root.inventory.inventoryIdentity(),
                Collections.<String, Node>emptyMap());
        store.putProcessingViews(
                event.inventory.inventoryIdentity(),
                Collections.<String, Node>emptyMap());
        Map<String, Collection<String>> requested =
                new LinkedHashMap<String, Collection<String>>();
        requested.put(
                root.inventory.inventoryIdentity(),
                Collections.singleton(root.inventory.rootBlueId()));
        requested.put(
                event.inventory.inventoryIdentity(),
                Collections.singleton(event.inventory.rootBlueId()));
        store.resetReadCounts();

        // when
        CoordinationFragmentStore.InventoryFragmentRepresentations result =
                store.readRepresentationsByInventory(requested);

        // then
        assertEquals(1, result.backendReadCount());
        assertEquals(1L, store.batchReadCount());
        assertEquals(2L, store.requestedIdentityCount());
        assertEquals(2, result.byInventory().size());
    }

    @Test
    void shouldExposeVerifiedCanonicalHandlesAsOneSafeBatch() {
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph(
                        "canonical-handle-spi");
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(graph);
        store.putInventory(graph.inventory);
        Collection<String> requested = graph.inventory.fragmentBlueIds()
                .subList(
                        0,
                        Math.min(2,
                                graph.inventory.fragmentBlueIds().size()));
        store.resetReadCounts();

        CoordinationCanonicalFragmentHandleStore
                .CanonicalFragmentHandleBatch batch =
                store.readCanonicalFragmentHandles(
                        graph.inventory.inventoryIdentity(),
                        requested);

        assertEquals(1, batch.batchReadCount());
        assertEquals(0, batch.singleReadCount());
        assertEquals(1L, store.batchReadCount());
        assertEquals(0L, store.singleReadCount());
        assertEquals(requested.size(), store.requestedIdentityCount());
        assertEquals(requested.size(), batch.handles().size());
        for (String blueId : requested) {
            ExactNodeHandle handle = batch.handles().get(blueId);
            Node mutableCopy = handle.copy();
            assertEquals(
                    blueId,
                    DirectBlueIdCalculator.calculateBlueId(mutableCopy));
            mutableCopy.value("caller mutation");
            assertEquals(
                    blueId,
                    DirectBlueIdCalculator.calculateBlueId(handle.copy()));
        }
    }

    @Test
    void canonicalHandleBatchNeverSubstitutesProcessHeaderViews() {
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph(
                        "physical-handle-namespace");
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(graph);
        store.putInventory(graph.inventory);
        Map<String, Node> processViews =
                CoordinationProcessingViews.collect(graph.split);
        assertFalse(processViews.isEmpty(),
                "fixture must contain an identity-equivalent PROCESS view");
        store.putProcessingViews(
                graph.inventory.inventoryIdentity(), processViews);
        String blueId = processViews.keySet().iterator().next();

        ExactNodeHandle physical = store.readCanonicalFragmentHandles(
                        graph.inventory.inventoryIdentity(),
                        Collections.singletonList(blueId))
                .handles().get(blueId);
        Node canonical = store.readCanonical(blueId).nodes().get(0);

        assertEquals(
                NodeWireForm.get(canonical),
                NodeWireForm.get(physical.copy()));
        assertFalse(NodeWireForm.get(processViews.get(blueId)).equals(
                NodeWireForm.get(physical.copy())));
    }
}
