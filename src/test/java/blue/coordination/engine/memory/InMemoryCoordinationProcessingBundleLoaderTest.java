package blue.coordination.engine.memory;

import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.fastpath.PreparedBundleGraphCache;
import blue.coordination.engine.fastpath.PreparedRequestNodeProvider;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationLocalityDiagnosticsProvider;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCoordinationProcessingBundleLoaderTest
        extends CoordinationProcessingBundleLoaderContract {

    @Override
    protected CoordinationFragmentStore createFragmentStore() {
        return new InMemoryCoordinationFragmentStore(
                CoordinationEngineStorageTestFixtures.PROFILE);
    }

    @Override
    protected CoordinationProcessingBundleLoader createLoader(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider) {
        return new InMemoryCoordinationProcessingBundleLoader(
                fragmentStore,
                runtimeProvider);
    }

    @Test
    void shouldBuildEachImmutableGraphOnceAndReuseItWithoutChangingTheBundle() {
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "loader-prepared-graph-cache", "root");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "event",
                        "transition:loader-prepared-graph-cache");
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationEngineStorageTestFixtures.PROFILE);
        install(store, admission.graph);
        install(store, transition.event);
        PreparedBundleGraphCache cache = new PreparedBundleGraphCache(4);
        InMemoryCoordinationProcessingBundleLoader loader =
                new InMemoryCoordinationProcessingBundleLoader(
                        store,
                        blueId -> Collections.<Node>emptyList(),
                        cache);

        LoadedProcessingBundle first = loader.load(
                admission.session,
                transition.transition.plan(),
                Collections.<String>emptyList());
        LoadedProcessingBundle second = loader.load(
                admission.session,
                transition.transition.plan(),
                Collections.<String>emptyList());

        assertEquals(first.backendLoadedBlueIds(),
                second.backendLoadedBlueIds());
        assertEquals(first.prefetchedBlueIds(), second.prefetchedBlueIds());
        assertEquals(first.batchCount(), second.batchCount());
        assertEquals(first.loadedBytes(), second.loadedBytes());
        assertSameWireForm(
                first,
                second,
                transition.transition.plan().rootReference().getBlueId());
        assertSameWireForm(
                first,
                second,
                transition.transition.plan().eventReference().getBlueId());

        int distinctInventories = transition.transition.plan()
                .rootInventory().inventoryIdentity().equals(
                        transition.transition.plan()
                                .eventInventory().inventoryIdentity())
                ? 1
                : 2;
        assertEquals(distinctInventories, cache.size());
        assertEquals(distinctInventories, cache.builds());
        assertEquals(distinctInventories, cache.misses());
        assertEquals(distinctInventories, cache.hits());
    }

    @Test
    void shouldUsePreparedSelectedHandlesInsteadOfTheWholeEventInventory() {
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "loader-prepared-selected", "root");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "event",
                        "transition:loader-prepared-selected");
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationEngineStorageTestFixtures.PROFILE);
        install(store, admission.graph);
        install(store, transition.event);
        CoordinationProcessingPlan source = transition.transition.plan();
        CoordinationProcessingPlan roundTripPlan =
                new CoordinationProcessingPlan(
                        source.session(),
                        source.rootReference(),
                        source.eventReference(),
                        source.preparedDelivery(),
                        source.rootInventory(),
                        source.eventInventory(),
                        source.requiredSeedBlueIds(),
                        source.eventInventory().fragmentBlueIds(),
                        source.demandBoundary(),
                        source.planIdentity() + ":round-trip",
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS);
        InMemoryCoordinationProcessingBundleLoader loader =
                new InMemoryCoordinationProcessingBundleLoader(
                        store,
                        blueId -> Collections.<Node>emptyList());
        store.resetReadCounts();

        LoadedProcessingBundle bundle = loader.load(
                admission.session,
                roundTripPlan,
                roundTripPlan.preferredPrefetchBlueIds());

        assertTrue(bundle.exactProvider()
                instanceof PreparedRequestNodeProvider);
        Set<String> expected = new LinkedHashSet<String>(
                roundTripPlan.requiredSeedBlueIds());
        assertEquals(expected, bundle.backendLoadedBlueIds());
        assertEquals(expected.size(), store.requestedIdentityCount());
        assertEquals(1L, store.batchReadCount());
        Set<String> completeInventory = new LinkedHashSet<String>(
                roundTripPlan.rootInventory().fragmentBlueIds());
        completeInventory.addAll(
                roundTripPlan.eventInventory().fragmentBlueIds());
        assertTrue(bundle.backendLoadedBlueIds().size()
                < completeInventory.size());

        bundle.exactProvider().fetchByBlueId(
                roundTripPlan.rootReference().getBlueId());
        bundle.exactProvider().fetchByBlueId(
                roundTripPlan.eventReference().getBlueId());
        LocalityDiagnostics diagnostics =
                ((CoordinationLocalityDiagnosticsProvider)
                        bundle.exactProvider()).diagnostics();
        assertEquals(0, diagnostics.fallbackReadCount());
        assertEquals(0, diagnostics.forbiddenReadCount());
        assertTrue(diagnostics.prefetchedButUnusedBlueIds().isEmpty());
    }

    private static void assertSameWireForm(
            LoadedProcessingBundle first,
            LoadedProcessingBundle second,
            String blueId) {
        assertEquals(
                NodeWireForm.get(first.exactProvider()
                        .fetchByBlueId(blueId).get(0)),
                NodeWireForm.get(second.exactProvider()
                        .fetchByBlueId(blueId).get(0)));
    }

    private static void install(
            InMemoryCoordinationFragmentStore store,
            CoordinationEngineStorageTestFixtures.FragmentGraph graph) {
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                graph.split.fragments());
        Map<String, Node> processViews =
                new LinkedHashMap<String, Node>();
        for (String blueId : graph.split.fragments().keySet()) {
            NodeProviderResult result = graph.split.provider()
                    .fetchResultByBlueId(blueId);
            if (result.outcome() == NodeProviderOutcome.FOUND
                    && result.nodes().size() == 1) {
                processViews.put(blueId, result.nodes().get(0));
            }
        }
        store.putInventory(graph.inventory);
        store.putProcessingViews(
                graph.inventory.inventoryIdentity(), processViews);
    }
}
