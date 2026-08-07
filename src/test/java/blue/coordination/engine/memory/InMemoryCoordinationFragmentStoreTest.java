package blue.coordination.engine.memory;

import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
