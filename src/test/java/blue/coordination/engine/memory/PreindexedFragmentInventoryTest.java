package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentMetadataRecord;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Durable-equivalence proof for the inventory's body-ownership query. */
final class PreindexedFragmentInventoryTest {

    @Test
    void shouldMatchPortableOwnershipAnswersWithoutProviderReads() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph(
                        "preindexed-answers");
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(graph);
        store.putInventory(graph.inventory);
        Set<String> portableOwned = portableOwnedBodies(graph.inventory);
        store.resetReadCounts();

        // when / then
        for (String blueId : graph.inventory.fragmentBlueIds()) {
            assertEquals(portableOwned.contains(blueId),
                    graph.inventory.ownsExactBody(blueId), blueId);
        }
        assertFalse(graph.inventory.ownsExactBody(
                "not-an-inventory-member"));
        assertEquals(0L, store.singleReadCount());
        assertEquals(0L, store.batchReadCount());
        assertEquals(0L, store.requestedIdentityCount());
    }

    @Test
    void shouldRehydrateAndReconstructExactlyAndRejectTampering() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph(
                        "preindexed-rehydration");
        Map<String, Object> persisted = graph.inventory.toMap();
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(graph);

        // when
        CoordinationFragmentInventory restored =
                CoordinationFragmentInventory.rehydrate(persisted);
        Node reconstructed = restored.reconstruct(
                store.canonicalFragmentProvider());

        // then
        assertEquals(graph.inventory.toMap(), restored.toMap());
        assertEquals(portableOwnedBodies(graph.inventory),
                portableOwnedBodies(restored));
        assertEquals(NodeWireForm.get(graph.exact),
                NodeWireForm.get(reconstructed));
        assertEquals(restored.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));

        Map<String, Object> tampered =
                new LinkedHashMap<String, Object>(persisted);
        tampered.put("inventoryIdentity", "sha256:tampered");
        assertThrows(IllegalArgumentException.class,
                () -> CoordinationFragmentInventory.rehydrate(tampered));
    }

    private static Set<String> portableOwnedBodies(
            CoordinationFragmentInventory inventory) {
        Set<String> result = new LinkedHashSet<String>();
        result.add(inventory.rootBlueId());
        for (FragmentRootRecord root : inventory.fragmentRoots()) {
            result.add(root.blueId());
        }
        for (FragmentMetadataRecord metadata : inventory.metadata()) {
            result.add(metadata.blueId());
        }
        for (FragmentEdgeRecord edge : inventory.edges()) {
            result.add(edge.ownerNodeBlueId());
            if (edge.splitterCreated()) {
                result.add(edge.childBlueId());
            }
        }
        return result;
    }
}
