package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationFragmentInventoryTest {

    @Test
    void shouldPersistOnlyClosedBodyFreeCanonicalData() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("body-free");

        // when
        Map<String, Object> persisted = graph.inventory.toMap();
        List<String> sortedIds = new ArrayList<String>(
                graph.inventory.fragmentBlueIds());
        Collections.sort(sortedIds);

        // then
        assertEquals(CoordinationFragmentInventory.SCHEMA_VERSION,
                persisted.get("schemaVersion"));
        assertEquals(sortedIds, graph.inventory.fragmentBlueIds());
        assertTrue(graph.inventory.inventoryIdentity().startsWith("sha256:"));
        assertEquals(graph.inventory.inventoryIdentity(),
                persisted.get("inventoryIdentity"));
        assertFalse(containsNode(persisted));
        assertThrows(
                UnsupportedOperationException.class,
                () -> persisted.put("extra", "forbidden"));
    }

    @Test
    void shouldRehydrateWithTheSameExactIdentityAndGraphRecords() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("round-trip");
        Map<String, Object> persisted = graph.inventory.toMap();

        // when
        CoordinationFragmentInventory restored =
                CoordinationFragmentInventory.rehydrate(persisted);

        // then
        assertEquals(graph.inventory.inventoryIdentity(),
                restored.inventoryIdentity());
        assertEquals(graph.inventory.toMap(), restored.toMap());
        assertEquals(graph.inventory.fragmentRoots(), restored.fragmentRoots());
        assertEquals(graph.inventory.edges(), restored.edges());
        assertEquals(graph.inventory.metadata(), restored.metadata());
    }

    @Test
    void shouldRejectPersistedContentWithATamperedIdentity() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("tampered");
        Map<String, Object> tampered = new LinkedHashMap<String, Object>(
                graph.inventory.toMap());
        tampered.put("inventoryIdentity", "sha256:tampered");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationFragmentInventory.rehydrate(tampered));

        // then
        assertTrue(failure.getMessage().contains("identity"));
    }

    @Test
    void shouldRejectPersistedContentWithAnUnknownField() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("open-map");
        Map<String, Object> openMap = new LinkedHashMap<String, Object>(
                graph.inventory.toMap());
        openMap.put("fragmentBodies", Collections.emptyList());

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationFragmentInventory.rehydrate(openMap));

        // then
        assertTrue(failure.getMessage().contains("fields differ"));
    }

    @Test
    void shouldRejectAnUnsupportedInventorySchema() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("schema");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationFragmentInventory(
                        "blue.coordination/fragment-inventory/2.0",
                        graph.inventory.fragmentationProfileIdentity(),
                        graph.inventory.edgeMetadataSchemaIdentity(),
                        graph.inventory.rootBlueId(),
                        graph.inventory.fragmentBlueIds(),
                        graph.inventory.fragmentRoots(),
                        graph.inventory.edges(),
                        graph.inventory.metadata()));

        // then
        assertTrue(failure.getMessage().contains("Unsupported"));
    }

    @Test
    void shouldRejectAnUnsupportedFragmentationProfile() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("profile");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        "blue.coordination/fragmentation/other",
                        graph.inventory.edgeMetadataSchemaIdentity(),
                        graph.inventory.rootBlueId(),
                        graph.inventory.fragmentBlueIds(),
                        graph.inventory.fragmentRoots(),
                        graph.inventory.edges(),
                        graph.inventory.metadata()));

        // then
        assertTrue(failure.getMessage().contains("Unsupported"));
    }

    @Test
    void shouldRejectAnInventoryThatOmitsItsRootBody() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("missing-root");
        List<String> withoutRoot = new ArrayList<String>(
                graph.inventory.fragmentBlueIds());
        withoutRoot.remove(graph.inventory.rootBlueId());

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        graph.inventory.fragmentationProfileIdentity(),
                        graph.inventory.edgeMetadataSchemaIdentity(),
                        graph.inventory.rootBlueId(),
                        withoutRoot,
                        graph.inventory.fragmentRoots(),
                        graph.inventory.edges(),
                        graph.inventory.metadata()));

        // then
        assertTrue(failure.getMessage().contains("Root body"));
    }

    @Test
    void shouldReconstructTheExactSemanticRootThroughProviderReads() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("reconstruct");
        InMemoryCoordinationFragmentStore store =
                CoordinationEngineStorageTestFixtures.fragmentStore(graph);
        store.resetReadCounts();

        // when
        Node reconstructed = graph.inventory.reconstruct(store);

        // then
        assertEquals(graph.inventory.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));
        assertEquals(NodeWireForm.get(graph.exact),
                NodeWireForm.get(reconstructed));
        assertEquals(0L, store.batchReadCount());
        assertEquals(graph.inventory.fragmentBlueIds().size(),
                store.requestedIdentityCount());
        assertEquals(graph.inventory.fragmentBlueIds().size(),
                store.singleReadCount());
    }

    @Test
    void shouldFailReconstructionWhenOneExactFragmentIsAbsent() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("absent");
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationEngineStorageTestFixtures.PROFILE);
        Map<String, Node> partial = new LinkedHashMap<String, Node>(
                graph.split.fragments());
        partial.remove(graph.inventory.fragmentBlueIds().get(0));
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE, partial);

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> graph.inventory.reconstruct(store));

        // then
        assertTrue(failure.getMessage().contains("unavailable"));
    }

    @Test
    void shouldReconstructThroughAProfileNeutralNodeProvider() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("wrong-store");
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore("another-profile");
        store.putAllIfAbsent("another-profile", graph.split.fragments());

        // when
        Node reconstructed = graph.inventory.reconstruct(store);

        // then
        assertEquals(graph.inventory.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));
    }

    private static boolean containsNode(Object value) {
        if (value instanceof Node) return true;
        if (value instanceof Map) {
            for (Object nested : ((Map<?, ?>) value).values()) {
                if (containsNode(nested)) return true;
            }
        }
        if (value instanceof Iterable) {
            for (Object nested : (Iterable<?>) value) {
                if (containsNode(nested)) return true;
            }
        }
        return false;
    }

}
