package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ProcessingBundlePlanBinding;
import blue.coordination.engine.internal.RequestLocalNodeProvider;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationLocalityDiagnosticsProvider;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationEngineProcessorTestFixtures;
import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable storage-adapter contract for one-batch PROCESS bundle loading.
 *
 * <p>Implementations supply a fragment store and loader while this contract
 * proves the portable storage behavior: one typed PROCESS-view multi-get,
 * bounded request-local fallback reads, exact diagnostics, and a canonical
 * namespace reserved for inventory reconstruction.</p>
 */
public abstract class CoordinationProcessingBundleLoaderContract {

    protected abstract CoordinationFragmentStore createFragmentStore();

    protected abstract CoordinationProcessingBundleLoader createLoader(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider);

    @Test
    protected void shouldBindTheLoadedBundleToTheExactRequestedPlan() {
        // given
        LoaderFixture fixture = fixture("loader-plan-binding");

        // when
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());

        // then
        assertTrue(bundle.planBinding().isPresent());
        ProcessingBundlePlanBinding binding = bundle.planBinding().get();
        assertEquals(fixture.session.sessionId(), binding.sessionId());
        assertEquals(fixture.session.currentEpoch(), binding.epoch());
        assertEquals(fixture.plan.rootReference().getBlueId(),
                binding.rootBlueId());
        assertEquals(fixture.plan.eventReference().getBlueId(),
                binding.eventBlueId());
        assertEquals(fixture.plan.planIdentity(), binding.planIdentity());
        assertEquals(fixture.session.subscriptions().digest(),
                binding.subscriptionDigest());
        assertEquals(fixture.session.environmentIdentity(),
                binding.environmentIdentity());
    }

    @Test
    protected void shouldLoadOneInitialProcessViewBatchAndPreserveTypedOutcomes() {
        // given
        LoaderFixture fixture = fixture("loader-typed");
        fixture.store.forceProcessingOutcome(
                fixture.eventBlueId,
                NodeProviderResult.notFound());

        // when
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());
        NodeProviderResult root = bundle.exactProvider()
                .fetchResultByBlueId(fixture.rootBlueId);
        NodeProviderResult event = bundle.exactProvider()
                .fetchResultByBlueId(fixture.eventBlueId);
        LocalityDiagnostics diagnostics = diagnostics(bundle);

        // then
        assertEquals(1, fixture.store.processingBatchCount());
        assertEquals(0, fixture.store.canonicalBatchCount());
        assertTrue(fixture.store.providerReads().isEmpty());
        assertEquals(
                Arrays.asList(fixture.rootBlueId, fixture.eventBlueId),
                fixture.store.lastProcessingRequest());
        assertEquals(NodeProviderOutcome.FOUND, root.outcome());
        assertEquals(NodeProviderOutcome.NOT_FOUND, event.outcome());
        assertEquals(
                NodeProviderOutcome.FOUND,
                fixture.store.lastProcessingOutcomes()
                        .get(fixture.rootBlueId).outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                fixture.store.lastProcessingOutcomes()
                        .get(fixture.eventBlueId).outcome());
        assertEquals(
                new LinkedHashSet<String>(Arrays.asList(
                        fixture.rootBlueId, fixture.eventBlueId)),
                bundle.backendLoadedBlueIds());
        assertEquals(1, bundle.batchCount());
        assertEquals(
                returnedBytes(
                        fixture.store.lastProcessingOutcomes(),
                        fixture.store.lastPhysicalOutcomes()),
                bundle.loadedBytes());
        assertEquals(1, diagnostics.batchCount());
        assertEquals(0, diagnostics.fallbackReadCount());
        assertEquals(0, diagnostics.forbiddenReadCount());
        assertEquals(
                Arrays.asList(fixture.rootBlueId, fixture.eventBlueId),
                diagnostics.requestedBlueIds());
        assertTrue(diagnostics.prefetchedButUnusedBlueIds().isEmpty());
    }

    @Test
    protected void shouldPreserveUnavailableAndInvalidInitialBatchOutcomes() {
        // given
        LoaderFixture fixture = fixture("loader-evidence-outcomes");
        fixture.store.forceProcessingOutcome(
                fixture.rootBlueId,
                NodeProviderResult.unavailable("storage-unavailable"));
        fixture.store.forceProcessingOutcome(
                fixture.eventBlueId,
                NodeProviderResult.invalidEvidence("storage-invalid"));

        // when
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());
        NodeProviderResult root = bundle.exactProvider()
                .fetchResultByBlueId(fixture.rootBlueId);
        NodeProviderResult event = bundle.exactProvider()
                .fetchResultByBlueId(fixture.eventBlueId);
        LocalityDiagnostics diagnostics = diagnostics(bundle);

        // then
        assertEquals(NodeProviderOutcome.UNAVAILABLE, root.outcome());
        assertEquals("storage-unavailable", root.diagnostic().get());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, event.outcome());
        assertEquals("storage-invalid", event.diagnostic().get());
        assertEquals(1, fixture.store.processingBatchCount());
        assertEquals(0, fixture.store.canonicalBatchCount());
        assertEquals(
                new LinkedHashSet<String>(Arrays.asList(
                        fixture.rootBlueId, fixture.eventBlueId)),
                bundle.backendLoadedBlueIds());
        assertEquals(1, diagnostics.batchCount());
        assertEquals(0, diagnostics.fallbackReadCount());
        assertEquals(
                returnedBytes(
                        fixture.store.lastProcessingOutcomes(),
                        fixture.store.lastPhysicalOutcomes()),
                diagnostics.loadedBytes());
        assertEquals(
                Arrays.asList(fixture.rootBlueId, fixture.eventBlueId),
                diagnostics.requestedBlueIds());
    }

    @Test
    protected void shouldNotMaskInventoryMissesWithTheRuntimeProvider() {
        // given
        LoaderFixture fixture = fixture("loader-no-cross-inventory-mask");
        fixture.store.forceProcessingOutcome(
                fixture.rootBlueId,
                NodeProviderResult.notFound());
        fixture.store.forceProcessingOutcome(
                fixture.eventBlueId,
                NodeProviderResult.invalidEvidence("inventory-invalid"));
        TrackingRuntimeProvider runtime = new TrackingRuntimeProvider(
                Collections.singletonMap(
                        fixture.rootBlueId,
                        new Node().value("runtime-copy")));
        CoordinationProcessingBundleLoader loader = createLoader(
                fixture.store, runtime);

        // when
        LoadedProcessingBundle bundle = loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());
        NodeProviderResult missing = bundle.exactProvider()
                .fetchResultByBlueId(fixture.rootBlueId);
        NodeProviderResult invalid = bundle.exactProvider()
                .fetchResultByBlueId(fixture.eventBlueId);

        // then
        assertEquals(NodeProviderOutcome.NOT_FOUND, missing.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, invalid.outcome());
        assertEquals("inventory-invalid", invalid.diagnostic().get());
        assertTrue(runtime.requests().isEmpty());
    }

    @Test
    protected void shouldResolveOnlyAProvenExternallyManagedReference() {
        // given
        LoaderFixture fixture = fixture("loader-external-reference");
        ExternalReferenceFixture external = externalReferenceFixture(fixture);
        TrackingRuntimeProvider runtime = new TrackingRuntimeProvider(
                Collections.singletonMap(
                        external.blueId,
                        external.exactNode));
        CoordinationProcessingBundleLoader loader = createLoader(
                fixture.store, runtime);

        // when
        LoadedProcessingBundle bundle = loader.load(
                fixture.session,
                external.plan,
                Collections.singleton(external.blueId));
        NodeProviderResult resolved = bundle.exactProvider()
                .fetchResultByBlueId(external.blueId);

        // then
        assertEquals(NodeProviderOutcome.FOUND, resolved.outcome());
        assertEquals(
                NodeWireForm.get(external.exactNode),
                NodeWireForm.get(resolved.nodes().get(0)));
        assertEquals(
                Collections.singletonList(external.blueId),
                runtime.requests());
        assertFalse(bundle.backendLoadedBlueIds().contains(external.blueId));
        assertTrue(bundle.prefetchedBlueIds().contains(external.blueId));
    }

    @Test
    protected void shouldRejectAnUnprovenExternalPrefetchIdentity() {
        // given
        LoaderFixture fixture = fixture("loader-unproven-reference");
        ExternalReferenceFixture external = externalReferenceFixture(fixture);
        String unproven = "unproven-external-reference";
        CoordinationProcessingPlan invalid = copyWithPreferred(
                external.plan,
                Arrays.asList(external.blueId, unproven));
        CoordinationProcessingBundleLoader loader = createLoader(
                fixture.store,
                new TrackingRuntimeProvider(
                        Collections.<String, Node>emptyMap()));

        // when / then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> loader.load(
                        fixture.session,
                        invalid,
                        Collections.singleton(unproven)));
        assertTrue(failure.getMessage().contains(
                "absent from both inventories"));
    }

    @Test
    protected void shouldServeAllowedDynamicFallbackWavesAfterOneInitialBatch() {
        // given
        LoaderFixture fixture = fixture("loader-fallback");
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());
        List<String> fallbackIds = eventFallbackIds(fixture.plan, 2);

        // when
        NodeProviderResult first = bundle.exactProvider()
                .fetchResultByBlueId(fallbackIds.get(0));
        NodeProviderResult second = bundle.exactProvider()
                .fetchResultByBlueId(fallbackIds.get(1));
        NodeProviderResult repeated = bundle.exactProvider()
                .fetchResultByBlueId(fallbackIds.get(0));
        LocalityDiagnostics diagnostics = diagnostics(bundle);

        // then
        assertEquals(NodeProviderOutcome.FOUND, first.outcome());
        assertEquals(NodeProviderOutcome.FOUND, second.outcome());
        assertEquals(NodeProviderOutcome.FOUND, repeated.outcome());
        assertEquals(1, fixture.store.processingBatchCount());
        assertEquals(0, fixture.store.canonicalBatchCount());
        assertEquals(fallbackIds, fixture.store.providerReads());
        assertEquals(1, diagnostics.batchCount());
        assertEquals(2, diagnostics.fallbackReadCount());
        assertEquals(
                Arrays.asList(
                        fallbackIds.get(0),
                        fallbackIds.get(1),
                        fallbackIds.get(0)),
                diagnostics.requestedBlueIds());
        assertEquals(fallbackIds, diagnostics.causallySelectedBlueIds());
        assertEquals(0, diagnostics.forbiddenReadCount());
        assertEquals(
                Arrays.asList(fixture.rootBlueId, fixture.eventBlueId),
                diagnostics.prefetchedButUnusedBlueIds());
        Set<String> expectedLoaded = new LinkedHashSet<String>(
                bundle.backendLoadedBlueIds());
        expectedLoaded.addAll(fallbackIds);
        assertEquals(
                new ArrayList<String>(expectedLoaded),
                diagnostics.backendLoadedBlueIds());
        assertEquals(
                bundle.loadedBytes()
                        + loadedBytes(first)
                        + loadedBytes(second),
                diagnostics.loadedBytes());
    }

    @Test
    protected void shouldUseCanonicalBytesForASharedInitialFragment() {
        // given
        LoaderFixture fixture = fixture("loader-shared-batch");
        String sharedBlueId = sharedFragmentId(fixture);
        fixture.store.forceProcessingOutcome(
                sharedBlueId,
                NodeProviderResult.found(Collections.singletonList(
                        new Node().blueId(sharedBlueId))));

        // when
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.singleton(sharedBlueId));
        NodeProviderResult returned = bundle.exactProvider()
                .fetchResultByBlueId(sharedBlueId);
        NodeProviderResult physical = fixture.store.lastPhysicalOutcomes()
                .get(sharedBlueId);

        // then
        assertEquals(NodeProviderOutcome.FOUND, returned.outcome());
        assertEquals(NodeProviderOutcome.FOUND, physical.outcome());
        assertEquals(
                NodeWireForm.get(physical.nodes().get(0)),
                NodeWireForm.get(returned.nodes().get(0)));
        assertTrue(fixture.store.lastProcessingOutcomes()
                .get(sharedBlueId).nodes().get(0).isReferenceOnly());
        assertFalse(returned.nodes().get(0).isReferenceOnly());
        assertEquals(1, bundle.batchCount());
    }

    @Test
    protected void shouldMemoizeCanonicalFallbackForASharedFragment() {
        // given
        LoaderFixture fixture = fixture("loader-shared-fallback");
        String sharedBlueId = sharedFragmentId(fixture);
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());

        // when
        NodeProviderResult first = bundle.exactProvider()
                .fetchResultByBlueId(sharedBlueId);
        NodeProviderResult second = bundle.exactProvider()
                .fetchResultByBlueId(sharedBlueId);
        LocalityDiagnostics diagnostics = diagnostics(bundle);

        // then
        assertEquals(NodeProviderOutcome.FOUND, first.outcome());
        assertEquals(NodeProviderOutcome.FOUND, second.outcome());
        assertEquals(
                Collections.singletonList(sharedBlueId),
                fixture.store.canonicalProviderReads());
        assertTrue(fixture.store.processingProviderReads().isEmpty());
        assertEquals(1, diagnostics.fallbackReadCount());
        assertEquals(
                Arrays.asList(sharedBlueId, sharedBlueId),
                diagnostics.requestedBlueIds());
        assertEquals(
                bundle.loadedBytes() + loadedBytes(first),
                diagnostics.loadedBytes());
    }

    @Test
    protected void shouldKeepHistoricalInventoryBodyFreeWithoutExtraReads() {
        // given
        LoaderFixture fixture = fixture("loader-reconstruction");

        // when
        LoadedProcessingBundle bundle = fixture.loader.load(
                fixture.session,
                fixture.plan,
                Collections.<String>emptyList());
        int canonicalReadsAfterLoad = fixture.store.canonicalBatchCount();
        Node retained = fixture.plan.rootInventory().directRootOrNull();

        // then
        assertEquals(1, fixture.store.processingBatchCount());
        assertEquals(0, canonicalReadsAfterLoad);
        assertEquals(0, fixture.store.canonicalBatchCount());
        assertNull(retained);
        assertEquals(1, bundle.batchCount());
        assertTrue(fixture.store.providerReads().isEmpty());
    }

    private LoaderFixture fixture(String sessionValue) {
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        sessionValue,
                        "root");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "event",
                        "transition:" + sessionValue);
        CoordinationFragmentStore backing = createFragmentStore();
        install(backing, admission.graph);
        install(backing, transition.event);
        TrackingFragmentStore tracking = new TrackingFragmentStore(backing);
        NodeProvider runtime = unavailableRuntime();
        return new LoaderFixture(
                tracking,
                createLoader(tracking, runtime),
                admission.session,
                transition.transition.plan(),
                admission.graph,
                admission.graph.inventory.rootBlueId(),
                transition.event.inventory.rootBlueId());
    }

    private ExternalReferenceFixture externalReferenceFixture(
            LoaderFixture fixture) {
        Node externalNode = new Node().properties(
                "managed", new Node().value("outside-inventory"));
        String externalBlueId = DirectBlueIdCalculator.calculateBlueId(
                externalNode.clone());
        Node exactEvent = new Node().properties(
                "kind", new Node().value("external-reference-event"),
                "managedReference", new Node().blueId(externalBlueId));
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(exactEvent);
        CoordinationFragmentInventory inventory =
                CoordinationFragmentInventory.from(split);
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                new CoordinationEngineStorageTestFixtures.FragmentGraph(
                        exactEvent,
                        split,
                        inventory);
        install(fixture.store, graph);
        assertFalse(inventory.fragmentBlueIds().contains(externalBlueId));
        assertTrue(inventory.edges().stream().anyMatch(
                edge -> edge.originalPureReference()
                        && externalBlueId.equals(edge.childBlueId())));

        String rootBlueId = fixture.plan.rootReference().getBlueId();
        CoordinationPreparedDelivery prepared =
                CoordinationEngineProcessorTestFixtures
                        .emptyPreparedDelivery(
                                rootBlueId,
                                inventory.rootBlueId(),
                                fixture.session.currentEpoch(),
                                fixture.plan.preparedDelivery()
                                        .deliveryPlan().eventOrderKey(),
                                fixture.session.subscriptions().digest());
        CoordinationProcessingPlan plan = new CoordinationProcessingPlan(
                fixture.session,
                new Node().blueId(rootBlueId),
                new Node().blueId(inventory.rootBlueId()),
                prepared,
                fixture.plan.rootInventory(),
                inventory,
                Arrays.asList(rootBlueId, inventory.rootBlueId()),
                Collections.singletonList(externalBlueId),
                prepared.demandBoundary(),
                "processing-plan-external-reference",
                fixture.plan.prefetchPolicy());
        return new ExternalReferenceFixture(
                plan, externalBlueId, externalNode);
    }

    private static CoordinationProcessingPlan copyWithPreferred(
            CoordinationProcessingPlan source,
            Collection<String> preferred) {
        return new CoordinationProcessingPlan(
                source.session(),
                source.rootReference(),
                source.eventReference(),
                source.preparedDelivery(),
                source.rootInventory(),
                source.eventInventory(),
                source.requiredSeedBlueIds(),
                preferred,
                source.demandBoundary(),
                source.planIdentity() + ":preferred-copy",
                source.prefetchPolicy());
    }

    private static void install(
            CoordinationFragmentStore store,
            CoordinationEngineStorageTestFixtures.FragmentGraph graph) {
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                graph.split.fragments());
        Map<String, Node> processViews = new LinkedHashMap<String, Node>();
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

    private static List<String> eventFallbackIds(
            CoordinationProcessingPlan plan,
            int count) {
        List<String> result = new ArrayList<String>();
        for (String blueId : plan.eventInventory().fragmentBlueIds()) {
            if (!plan.requiredSeedBlueIds().contains(blueId)) {
                result.add(blueId);
                if (result.size() == count) {
                    return result;
                }
            }
        }
        throw new AssertionError(
                "The loader TCK fixture requires " + count
                        + " non-seed event fragments");
    }

    private static String sharedFragmentId(LoaderFixture fixture) {
        CoordinationProcessingPlan plan = fixture.plan;
        Set<String> shared = new LinkedHashSet<String>(
                plan.rootInventory().fragmentBlueIds());
        shared.retainAll(plan.eventInventory().fragmentBlueIds());
        shared.removeAll(plan.requiredSeedBlueIds());
        for (String blueId : shared) {
            Node physical = fixture.store.read(
                    CoordinationEngineStorageTestFixtures.PROFILE,
                    blueId);
            if (physical != null && !physical.isReferenceOnly()) {
                return blueId;
            }
        }
        throw new AssertionError(
                "The loader TCK fixture requires a shared physical fragment");
    }

    private static LocalityDiagnostics diagnostics(
            LoadedProcessingBundle bundle) {
        assertTrue(bundle.exactProvider()
                instanceof CoordinationLocalityDiagnosticsProvider);
        return ((CoordinationLocalityDiagnosticsProvider)
                bundle.exactProvider()).diagnostics();
    }

    private static long loadedBytes(NodeProviderResult result) {
        long total = 0L;
        for (Node node : result.nodes()) {
            total += RequestLocalNodeProvider.bytes(node);
        }
        return total;
    }

    private static long returnedBytes(
            Map<String, NodeProviderResult> processing,
            Map<String, NodeProviderResult> physical) {
        Set<String> blueIds = new LinkedHashSet<String>(processing.keySet());
        blueIds.addAll(physical.keySet());
        long total = 0L;
        for (String blueId : blueIds) {
            List<Object> wireForms = new ArrayList<Object>();
            total += distinctBytes(processing.get(blueId), wireForms);
            total += distinctBytes(physical.get(blueId), wireForms);
        }
        return total;
    }

    private static long distinctBytes(
            NodeProviderResult result,
            List<Object> wireForms) {
        if (result == null
                || result.outcome() != NodeProviderOutcome.FOUND) {
            return 0L;
        }
        long total = 0L;
        for (Node node : result.nodes()) {
            Object wireForm = NodeWireForm.get(node);
            if (!wireForms.contains(wireForm)) {
                wireForms.add(wireForm);
                total += RequestLocalNodeProvider.bytes(node);
            }
        }
        return total;
    }

    private static NodeProvider unavailableRuntime() {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return NodeProviderResult.unavailable(
                        "loader-tck-runtime-unavailable");
            }
        };
    }

    private static final class LoaderFixture {
        private final TrackingFragmentStore store;
        private final CoordinationProcessingBundleLoader loader;
        private final ManagedDocumentSnapshot session;
        private final CoordinationProcessingPlan plan;
        private final CoordinationEngineStorageTestFixtures.FragmentGraph
                rootGraph;
        private final String rootBlueId;
        private final String eventBlueId;

        private LoaderFixture(
                TrackingFragmentStore store,
                CoordinationProcessingBundleLoader loader,
                ManagedDocumentSnapshot session,
                CoordinationProcessingPlan plan,
                CoordinationEngineStorageTestFixtures.FragmentGraph rootGraph,
                String rootBlueId,
                String eventBlueId) {
            this.store = store;
            this.loader = loader;
            this.session = session;
            this.plan = plan;
            this.rootGraph = rootGraph;
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
        }
    }

    private static final class ExternalReferenceFixture {
        private final CoordinationProcessingPlan plan;
        private final String blueId;
        private final Node exactNode;

        private ExternalReferenceFixture(
                CoordinationProcessingPlan plan,
                String blueId,
                Node exactNode) {
            this.plan = plan;
            this.blueId = blueId;
            this.exactNode = exactNode.clone();
        }
    }

    private static final class TrackingRuntimeProvider
            implements NodeProvider {
        private final Map<String, Node> exactNodes;
        private final List<String> requests = new ArrayList<String>();

        private TrackingRuntimeProvider(Map<String, Node> exactNodes) {
            this.exactNodes = new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> entry : exactNodes.entrySet()) {
                this.exactNodes.put(entry.getKey(), entry.getValue().clone());
            }
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult result = fetchResultByBlueId(blueId);
            return result.outcome() == NodeProviderOutcome.FOUND
                    ? result.nodes()
                    : Collections.<Node>emptyList();
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            requests.add(blueId);
            Node exact = exactNodes.get(blueId);
            return exact == null
                    ? NodeProviderResult.notFound()
                    : NodeProviderResult.found(
                            Collections.singletonList(exact.clone()));
        }

        private List<String> requests() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(requests));
        }
    }

    private static final class TrackingFragmentStore
            implements CoordinationFragmentStore {
        private final CoordinationFragmentStore delegate;
        private final Map<String, NodeProviderResult> forcedProcessing =
                new LinkedHashMap<String, NodeProviderResult>();
        private final List<String> providerReads = new ArrayList<String>();
        private final List<String> canonicalProviderReads =
                new ArrayList<String>();
        private final List<String> processingProviderReads =
                new ArrayList<String>();
        private List<String> lastProcessingRequest =
                Collections.emptyList();
        private Map<String, NodeProviderResult> lastProcessingOutcomes =
                Collections.emptyMap();
        private Map<String, NodeProviderResult> lastPhysicalOutcomes =
                Collections.emptyMap();
        private int processingBatchCount;
        private int canonicalBatchCount;

        private TrackingFragmentStore(CoordinationFragmentStore delegate) {
            this.delegate = delegate;
        }

        private void forceProcessingOutcome(
                String blueId,
                NodeProviderResult outcome) {
            forcedProcessing.put(blueId, outcome);
        }

        private int processingBatchCount() {
            return processingBatchCount;
        }

        private int canonicalBatchCount() {
            return canonicalBatchCount;
        }

        private List<String> providerReads() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(providerReads));
        }

        private List<String> canonicalProviderReads() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(canonicalProviderReads));
        }

        private List<String> processingProviderReads() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(processingProviderReads));
        }

        private List<String> lastProcessingRequest() {
            return lastProcessingRequest;
        }

        private Map<String, NodeProviderResult> lastProcessingOutcomes() {
            return lastProcessingOutcomes;
        }

        private Map<String, NodeProviderResult> lastPhysicalOutcomes() {
            return lastPhysicalOutcomes;
        }

        @Override
        public String fragmentationProfileIdentity() {
            return delegate.fragmentationProfileIdentity();
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult result = fetchResultByBlueId(blueId);
            return result.outcome() == NodeProviderOutcome.FOUND
                    ? result.nodes()
                    : Collections.<Node>emptyList();
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            providerReads.add(blueId);
            return delegate.fetchResultByBlueId(blueId);
        }

        @Override
        public NodeProviderResult readCanonical(String blueId) {
            providerReads.add(blueId);
            canonicalProviderReads.add(blueId);
            return delegate.readCanonical(blueId);
        }

        @Override
        public NodeProviderResult readProcessing(
                String inventoryIdentity,
                String blueId) {
            providerReads.add(blueId);
            processingProviderReads.add(blueId);
            return delegate.readProcessing(inventoryIdentity, blueId);
        }

        @Override
        public Node read(String profileIdentity, String blueId) {
            return delegate.read(profileIdentity, blueId);
        }

        @Override
        public boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node exactFragment) {
            return delegate.putIfAbsent(
                    profileIdentity,
                    blueId,
                    exactFragment);
        }

        @Override
        public boolean putAllIfAbsent(
                String profileIdentity,
                Map<String, Node> exactFragments) {
            return delegate.putAllIfAbsent(
                    profileIdentity,
                    exactFragments);
        }

        @Override
        public Map<String, NodeProviderResult> readAll(
                Collection<String> blueIds) {
            canonicalBatchCount++;
            return delegate.readAll(blueIds);
        }

        @Override
        public Map<String, NodeProviderResult> readProcessingAll(
                Collection<String> blueIds) {
            processingBatchCount++;
            lastProcessingRequest = Collections.unmodifiableList(
                    new ArrayList<String>(blueIds));
            Map<String, NodeProviderResult> actual =
                    delegate.readProcessingAll(blueIds);
            Map<String, NodeProviderResult> result =
                    new LinkedHashMap<String, NodeProviderResult>();
            for (String blueId : blueIds) {
                NodeProviderResult forced = forcedProcessing.get(blueId);
                result.put(
                        blueId,
                        forced == null ? actual.get(blueId) : forced);
            }
            lastProcessingOutcomes = Collections.unmodifiableMap(result);
            return lastProcessingOutcomes;
        }

        @Override
        public FragmentRepresentations readRepresentations(
                String inventoryIdentity,
                Collection<String> blueIds) {
            processingBatchCount++;
            lastProcessingRequest = Collections.unmodifiableList(
                    new ArrayList<String>(blueIds));
            FragmentRepresentations actual = delegate.readRepresentations(
                    inventoryIdentity, blueIds);
            Map<String, NodeProviderResult> processing =
                    new LinkedHashMap<String, NodeProviderResult>();
            for (String blueId : blueIds) {
                NodeProviderResult forced = forcedProcessing.get(blueId);
                processing.put(
                        blueId,
                        forced == null
                                ? actual.processing().get(blueId)
                                : forced);
            }
            lastProcessingOutcomes = Collections.unmodifiableMap(processing);
            lastPhysicalOutcomes = actual.physical();
            return new FragmentRepresentations(
                    lastProcessingOutcomes,
                    actual.physical());
        }

        @Override
        public InventoryFragmentRepresentations
                readRepresentationsByInventory(
                        Map<String, Collection<String>> blueIdsByInventory) {
            InventoryFragmentRepresentations actual = delegate
                    .readRepresentationsByInventory(blueIdsByInventory);
            processingBatchCount += actual.backendReadCount();
            List<String> requested = new ArrayList<String>();
            Map<String, NodeProviderResult> processing =
                    new LinkedHashMap<String, NodeProviderResult>();
            Map<String, NodeProviderResult> physical =
                    new LinkedHashMap<String, NodeProviderResult>();
            Map<String, FragmentRepresentations> byInventory =
                    new LinkedHashMap<String, FragmentRepresentations>();
            for (Map.Entry<String, Collection<String>> entry
                    : blueIdsByInventory.entrySet()) {
                FragmentRepresentations representation =
                        actual.byInventory().get(entry.getKey());
                if (representation == null) {
                    continue;
                }
                Map<String, NodeProviderResult> scopedProcessing =
                        new LinkedHashMap<String, NodeProviderResult>();
                for (String blueId : entry.getValue()) {
                    requested.add(blueId);
                    NodeProviderResult forced = forcedProcessing.get(blueId);
                    NodeProviderResult processResult = forced == null
                            ? representation.processing().get(blueId)
                            : forced;
                    scopedProcessing.put(blueId, processResult);
                    processing.put(blueId, processResult);
                    physical.put(
                            blueId,
                            representation.physical().get(blueId));
                }
                byInventory.put(
                        entry.getKey(),
                        new FragmentRepresentations(
                                scopedProcessing,
                                representation.physical()));
            }
            lastProcessingRequest = Collections.unmodifiableList(requested);
            lastProcessingOutcomes = Collections.unmodifiableMap(processing);
            lastPhysicalOutcomes = Collections.unmodifiableMap(physical);
            return new InventoryFragmentRepresentations(
                    byInventory, actual.backendReadCount());
        }

        @Override
        public void putProcessingViews(Map<String, Node> exactProcessingViews) {
            delegate.putProcessingViews(exactProcessingViews);
        }

        @Override
        public void putProcessingViews(
                String inventoryIdentity,
                Map<String, Node> exactProcessingViews) {
            delegate.putProcessingViews(
                    inventoryIdentity, exactProcessingViews);
        }

        @Override
        public void putInventory(CoordinationFragmentInventory inventory) {
            delegate.putInventory(inventory);
        }

        @Override
        public CoordinationFragmentInventory requireInventory(
                String inventoryIdentity) {
            return delegate.requireInventory(inventoryIdentity);
        }
    }
}
