package blue.coordination.engine.memory;

import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.internal.RequestLocalNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Acceptance proof for frozen in-memory fragment batch ownership. */
final class FrozenFragmentBatchTest {

    @Test
    void shouldReturnPreparedHandlesAndSizesWithoutMutableStoreExposure() {
        // given
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("frozen-batch");
        Map<String, Node> callerFragments = graph.split.fragments();
        Map<String, Object> expectedWire = wireForms(callerFragments);
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationEngineStorageTestFixtures.PROFILE);
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                callerFragments);
        store.putInventory(graph.inventory);
        store.putProcessingViews(
                graph.inventory.inventoryIdentity(),
                Collections.<String, Node>emptyMap());
        for (Node callerFragment : callerFragments.values()) {
            callerFragment.value("caller mutation");
        }
        List<String> requested = new ArrayList<String>(
                graph.inventory.fragmentBlueIds().subList(
                        0,
                        Math.min(2, graph.inventory.fragmentBlueIds().size())));
        Map<String, Collection<String>> batch =
                new LinkedHashMap<String, Collection<String>>();
        batch.put(graph.inventory.inventoryIdentity(), requested);
        store.resetReadCounts();

        // when
        InMemoryCoordinationFragmentStore
                .PreparedInventoryFragmentRepresentations first =
                store.readPreparedRepresentationsByInventory(batch);

        // then
        assertEquals(1, first.backendReadCount());
        assertEquals(1L, store.batchReadCount());
        assertEquals(0L, store.singleReadCount());
        assertEquals(requested.size(), store.requestedIdentityCount());
        InMemoryCoordinationFragmentStore.PreparedFragmentRepresentations
                representations = first.byInventory().get(
                graph.inventory.inventoryIdentity());
        assertEquals(requested.size(), representations.physical().size());
        assertEquals(requested.size(), representations.processing().size());
        assertEquals(requested.size(),
                representations.physicalSizes().size());

        for (String blueId : requested) {
            ExactNodeHandle handle = representations.physical().get(blueId);
            Node materialized = handle.copy();
            assertEquals(blueId,
                    DirectBlueIdCalculator.calculateBlueId(materialized));
            assertEquals(expectedWire.get(blueId),
                    NodeWireForm.get(materialized));
            assertEquals(RequestLocalNodeProvider.bytes(materialized),
                    representations.physicalSizes().get(blueId));

            materialized.value("request-local mutation");
            assertEquals(blueId,
                    DirectBlueIdCalculator.calculateBlueId(handle.copy()));
        }

        InMemoryCoordinationFragmentStore
                .PreparedInventoryFragmentRepresentations second =
                store.readPreparedRepresentationsByInventory(batch);
        InMemoryCoordinationFragmentStore.PreparedFragmentRepresentations
                secondRepresentations = second.byInventory().get(
                graph.inventory.inventoryIdentity());
        for (String blueId : requested) {
            assertSame(representations.physical().get(blueId),
                    secondRepresentations.physical().get(blueId),
                    "direct reads reuse the verified immutable handle");
            assertSame(representations.physicalSizes().get(blueId),
                    secondRepresentations.physicalSizes().get(blueId),
                    "encoded byte metadata is read, not serialized again");
        }

        String firstBlueId = requested.get(0);
        NodeProviderResult publicFirst = store.fetchResultByBlueId(
                firstBlueId);
        Node mutablePublicCopy = publicFirst.nodes().get(0);
        mutablePublicCopy.value("public SPI mutation");
        NodeProviderResult publicSecond = store.fetchResultByBlueId(
                firstBlueId);
        assertNotSame(mutablePublicCopy, publicSecond.nodes().get(0));
        assertEquals(firstBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        publicSecond.nodes().get(0)));
        assertEquals(expectedWire.get(firstBlueId),
                NodeWireForm.get(publicSecond.nodes().get(0)));
    }

    private static Map<String, Object> wireForms(
            Map<String, Node> fragments) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Node> fragment : fragments.entrySet()) {
            result.put(fragment.getKey(), NodeWireForm.get(
                    fragment.getValue()));
        }
        return result;
    }
}
