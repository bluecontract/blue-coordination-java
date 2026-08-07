package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentMetadataRecord;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessingBundlePlanBinding;
import blue.coordination.engine.fastpath.FragmentGraphIndex;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.fastpath.PreparedBundleGraphCache;
import blue.coordination.engine.fastpath.PreparedBundleTemplate;
import blue.coordination.engine.fastpath.PreparedBundleTemplateCache;
import blue.coordination.engine.fastpath.PreparedProcessInput;
import blue.coordination.engine.internal.RequestLocalNodeProvider;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationFragmentReconstructor;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.util.PointerUtils;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** One-batch in-memory loader with selected-scope fragment locality. */
public final class InMemoryCoordinationProcessingBundleLoader
        implements CoordinationProcessingBundleLoader {

    private static final int DEFAULT_GRAPH_CACHE_SIZE = 256;
    private static final int DEFAULT_TEMPLATE_CACHE_SIZE = 256;

    private final CoordinationFragmentStore fragmentStore;
    private final NodeProvider runtimeProvider;
    private final PreparedBundleGraphCache graphCache;
    private final PreparedBundleTemplateCache templateCache;

    public InMemoryCoordinationProcessingBundleLoader(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider) {
        this(
                fragmentStore,
                runtimeProvider,
                new PreparedBundleGraphCache(DEFAULT_GRAPH_CACHE_SIZE),
                new PreparedBundleTemplateCache(
                        DEFAULT_TEMPLATE_CACHE_SIZE));
    }

    InMemoryCoordinationProcessingBundleLoader(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            PreparedBundleGraphCache graphCache) {
        this(
                fragmentStore,
                runtimeProvider,
                graphCache,
                new PreparedBundleTemplateCache(
                        DEFAULT_TEMPLATE_CACHE_SIZE));
    }

    InMemoryCoordinationProcessingBundleLoader(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            PreparedBundleGraphCache graphCache,
            PreparedBundleTemplateCache templateCache) {
        this.fragmentStore = Objects.requireNonNull(
                fragmentStore, "fragmentStore");
        this.runtimeProvider = Objects.requireNonNull(
                runtimeProvider, "runtimeProvider");
        this.graphCache = Objects.requireNonNull(graphCache, "graphCache");
        this.templateCache = Objects.requireNonNull(
                templateCache, "templateCache");
    }

    @Override
    public LoadedProcessingBundle load(
            ManagedDocumentSnapshot session,
            CoordinationProcessingPlan plan,
            Collection<String> preferredBlueIds) {
        Objects.requireNonNull(session, "session");
        CoordinationProcessingPlan checkedPlan = Objects.requireNonNull(
                plan, "plan");
        if (!session.sessionId().equals(checkedPlan.session().sessionId())
                || session.currentEpoch()
                != checkedPlan.session().currentEpoch()) {
            throw new IllegalArgumentException(
                    "Bundle request does not bind to the planned session");
        }
        Set<String> preferred = new LinkedHashSet<String>(
                Objects.requireNonNull(preferredBlueIds, "preferredBlueIds"));
        preferred.addAll(checkedPlan.requiredSeedBlueIds());
        FragmentGraphIndex rootGraph = graphCache.require(
                checkedPlan.rootInventory());
        FragmentGraphIndex eventGraph = rootGraph.inventoryIdentity().equals(
                checkedPlan.eventInventory().inventoryIdentity())
                ? rootGraph
                : graphCache.require(checkedPlan.eventInventory());
        if (fragmentStore instanceof InMemoryCoordinationFragmentStore) {
            return loadPrepared(
                    (InMemoryCoordinationFragmentStore) fragmentStore,
                    session,
                    checkedPlan,
                    preferred,
                    rootGraph,
                    eventGraph);
        }
        Set<String> allowed = allowedFragments(checkedPlan, rootGraph);
        if (checkedPlan.prefetchPolicy()
                == PrefetchPolicy.MINIMUM_ROUND_TRIPS) {
            addSelectedSeedClosure(
                    rootGraph,
                    preferred);
            addSelectedSeedClosure(
                    eventGraph,
                    preferred);
            /* Fetch the bounded header/selected-body ceiling in the same
             * backend batch so execution never falls back. Do not compute a
             * second transitive closure from this set: selector-catalog owner
             * headers span unrelated scopes and closing all of them expands
             * to the complete document inventory.
             */
            for (String candidate : allowed) {
                if (rootGraph.fragmentBlueIds().contains(candidate)
                        || eventGraph.fragmentBlueIds().contains(candidate)) {
                    preferred.add(candidate);
                }
            }
            allowed.addAll(preferred);
        }
        preferred.retainAll(allowed);
        Set<String> known = new LinkedHashSet<String>(
                rootGraph.fragmentBlueIds());
        known.addAll(eventGraph.fragmentBlueIds());
        Set<String> externallyManagedReferences =
                externallyManagedReferenceBlueIds(
                        checkedPlan, allowed);
        allowed.addAll(externallyManagedReferences);
        Map<String, FragmentOwnership> ownership = ownership(
                checkedPlan, rootGraph, eventGraph);
        Map<String, Collection<String>> partitions = partitions(
                checkedPlan,
                preferred,
                ownership,
                externallyManagedReferences);
        CoordinationFragmentStore.InventoryFragmentRepresentations
                representations = fragmentStore
                .readRepresentationsByInventory(partitions);
        Map<String, NodeProviderResult> batch =
                new LinkedHashMap<String, NodeProviderResult>();
        Map<String, NodeProviderResult> physicalBatch =
                new LinkedHashMap<String, NodeProviderResult>();
        mergeRepresentations(
                checkedPlan,
                preferred,
                ownership,
                externallyManagedReferences,
                representations.byInventory(),
                batch,
                physicalBatch);
        InitialAccounting accounting = initialAccounting(
                preferred, batch, physicalBatch);
        int backendReadCount = representations.backendReadCount();
        RequestLocalNodeProvider provider = new RequestLocalNodeProvider(
                fragmentStore,
                runtimeProvider,
                new LinkedHashMap<String, NodeProviderResult>(batch),
                allowed,
                known,
                backendReadCount,
                accounting.loadedBytes,
                selectedFragmentProvider(
                        checkedPlan,
                        rootGraph,
                        eventGraph,
                        ownership,
                        batch,
                        physicalBatch),
                inventoryScopedFallbackProvider(
                        checkedPlan, ownership),
                accounting.loadedBlueIds,
                externallyManagedReferences);
        return new LoadedProcessingBundle(
                provider,
                accounting.loadedBlueIds,
                preferred,
                backendReadCount,
                accounting.loadedBytes,
                new ProcessingBundlePlanBinding(
                        session.sessionId(),
                        session.currentEpoch(),
                        checkedPlan.rootReference().getBlueId(),
                        checkedPlan.eventReference().getBlueId(),
                        checkedPlan.planIdentity(),
                        session.subscriptions().digest(),
                        session.environmentIdentity()));
    }

    private LoadedProcessingBundle loadPrepared(
            InMemoryCoordinationFragmentStore store,
            ManagedDocumentSnapshot session,
            CoordinationProcessingPlan plan,
            Set<String> requestedPreferred,
            FragmentGraphIndex rootGraph,
            FragmentGraphIndex eventGraph) {
        Set<String> selected = selectedPreparedIdentities(
                plan, requestedPreferred, eventGraph);
        addSelectedSeedClosure(rootGraph, selected);
        addSelectedSeedClosure(eventGraph, selected);

        Set<String> allowed = allowedFragments(plan, rootGraph);
        Set<String> externallyManagedReferences =
                externallyManagedReferenceBlueIds(plan, allowed);
        allowed.addAll(externallyManagedReferences);
        /* A preferred hint is not authority to widen the admitted request
         * domain. Match the portable path by pruning hints which are neither
         * inventory members nor proven external references. Required seeds
         * are already included in allowedFragments and remain fail-closed in
         * the ownership/representation checks below. */
        selected.retainAll(allowed);

        Map<String, FragmentOwnership> ownership = ownership(
                plan, rootGraph, eventGraph);
        Set<String> locallyAllowed = new LinkedHashSet<String>(allowed);
        locallyAllowed.retainAll(ownership.keySet());
        Map<String, Collection<String>> partitions = partitions(
                plan,
                locallyAllowed,
                ownership,
                externallyManagedReferences);
        InMemoryCoordinationFragmentStore
                .PreparedInventoryFragmentRepresentations representations =
                store.readPreparedRepresentationsByInventory(
                        partitions, selected);

        Map<String, ExactNodeHandle> rootHandles =
                new LinkedHashMap<String, ExactNodeHandle>();
        Map<String, Long> rootSizes = new LinkedHashMap<String, Long>();
        Map<String, ExactNodeHandle> eventHandles =
                new LinkedHashMap<String, ExactNodeHandle>();
        Map<String, Long> eventSizes = new LinkedHashMap<String, Long>();
        Map<String, ExactNodeHandle> availableHandles =
                new LinkedHashMap<String, ExactNodeHandle>();
        Map<String, Long> availableSizes =
                new LinkedHashMap<String, Long>();
        for (String blueId : locallyAllowed) {
            FragmentOwnership owner = ownership.get(blueId);
            String inventoryIdentity = owner == FragmentOwnership.EVENT
                    ? plan.eventInventory().inventoryIdentity()
                    : plan.rootInventory().inventoryIdentity();
            InMemoryCoordinationFragmentStore.PreparedFragmentRepresentations
                    source = representations.byInventory().get(
                            inventoryIdentity);
            if (source == null) {
                throw new IllegalStateException(
                        "Prepared inventory read is absent: "
                                + inventoryIdentity);
            }
            ExactNodeHandle handle = owner == FragmentOwnership.SHARED
                    ? source.physical().get(blueId)
                    : source.processing().get(blueId);
            Long size = owner == FragmentOwnership.SHARED
                    ? source.physicalSizes().get(blueId)
                    : source.processingSizes().get(blueId);
            if (handle == null || size == null) {
                throw new IllegalStateException(
                        "Prepared representation is absent: " + blueId);
            }
            availableHandles.put(blueId, handle);
            availableSizes.put(blueId, size);
        }
        for (String blueId : selected) {
            FragmentOwnership owner = ownership.get(blueId);
            if (owner == null) {
                if (!externallyManagedReferences.contains(blueId)) {
                    throw new IllegalStateException(
                            "Prepared identity has no admitted owner: "
                                    + blueId);
                }
                continue;
            }
            ExactNodeHandle handle = availableHandles.get(blueId);
            Long size = availableSizes.get(blueId);
            if (handle == null || size == null) {
                throw new IllegalStateException(
                        "Selected prepared representation is absent: "
                                + blueId);
            }
            if (owner == FragmentOwnership.EVENT) {
                eventHandles.put(blueId, handle);
                eventSizes.put(blueId, size);
            } else {
                rootHandles.put(blueId, handle);
                rootSizes.put(blueId, size);
            }
        }

        PreparedBundleTemplate template = requireTemplate(
                plan.rootInventory().inventoryIdentity(),
                rootHandles,
                rootSizes);
        PreparedProcessInput input = template.bindEvent(
                plan.eventInventory().inventoryIdentity(),
                eventHandles,
                eventSizes,
                selected,
                externallyManagedReferences);
        Set<String> known = new LinkedHashSet<String>(
                rootGraph.fragmentBlueIds());
        known.addAll(eventGraph.fragmentBlueIds());
        blue.coordination.engine.fastpath.PreparedRequestNodeProvider provider =
                input.newProvider(
                        runtimeProvider,
                        known,
                        allowed,
                        externallyManagedReferences,
                        availableHandles,
                        representations.backendReadCount());
        return new LoadedProcessingBundle(
                provider,
                provider.loadedBlueIds(),
                selected,
                representations.backendReadCount(),
                input.encodedBytes(),
                new ProcessingBundlePlanBinding(
                        session.sessionId(),
                        session.currentEpoch(),
                        plan.rootReference().getBlueId(),
                        plan.eventReference().getBlueId(),
                        plan.planIdentity(),
                        session.subscriptions().digest(),
                        session.environmentIdentity()));
    }

    private static Set<String> selectedPreparedIdentities(
            CoordinationProcessingPlan plan,
            Set<String> requestedPreferred,
            FragmentGraphIndex eventGraph) {
        Set<String> selected = new LinkedHashSet<String>(
                requestedPreferred);
        selected.addAll(plan.requiredSeedBlueIds());
        if (plan.prefetchPolicy() == PrefetchPolicy.MINIMUM_ROUND_TRIPS
                && selected.containsAll(eventGraph.fragmentBlueIds())) {
            /* The plan's round-trip policy contributes the complete event
             * inventory as a cold-store hedge. In-memory prepared content has
             * no round trip to amortize, so retain only identities admitted by
             * the indexed delivery evidence. The event Root itself remains a
             * mandatory seed. */
            selected.removeAll(eventGraph.fragmentBlueIds());
            selected.addAll(plan.requiredSeedBlueIds());
            for (String blueId
                    : plan.preparedDelivery().prefetchIdentities()) {
                if (plan.rootInventory().fragmentBlueIds().contains(blueId)
                        || eventGraph.fragmentBlueIds().contains(blueId)) {
                    selected.add(blueId);
                }
            }
        }
        return selected;
    }

    private PreparedBundleTemplate requireTemplate(
            String inventoryIdentity,
            Map<String, ExactNodeHandle> handles,
            Map<String, Long> sizes) {
        return templateCache.require(inventoryIdentity, handles, sizes);
    }

    private static Map<String, FragmentOwnership> ownership(
            CoordinationProcessingPlan plan,
            FragmentGraphIndex rootGraph,
            FragmentGraphIndex eventGraph) {
        CoordinationFragmentInventory root = plan.rootInventory();
        CoordinationFragmentInventory event = plan.eventInventory();
        boolean sameInventory = root.inventoryIdentity().equals(
                event.inventoryIdentity());
        Map<String, FragmentOwnership> result =
                new LinkedHashMap<String, FragmentOwnership>();
        for (String blueId : rootGraph.fragmentBlueIds()) {
            result.put(
                    blueId,
                    !sameInventory
                            && eventGraph.fragmentBlueIds().contains(blueId)
                            ? FragmentOwnership.SHARED
                            : FragmentOwnership.ROOT);
        }
        for (String blueId : eventGraph.fragmentBlueIds()) {
            if (!result.containsKey(blueId)) {
                result.put(blueId, sameInventory
                        ? FragmentOwnership.ROOT
                        : FragmentOwnership.EVENT);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Collection<String>> partitions(
            CoordinationProcessingPlan plan,
            Collection<String> preferred,
            Map<String, FragmentOwnership> ownership,
            Set<String> externallyManagedReferences) {
        Set<String> root = new LinkedHashSet<String>();
        Set<String> event = new LinkedHashSet<String>();
        for (String blueId : preferred) {
            FragmentOwnership owner = ownership.get(blueId);
            if (owner == null) {
                if (externallyManagedReferences.contains(blueId)) {
                    continue;
                }
                throw new IllegalStateException(
                        "Preferred fragment is absent from both inventories: "
                                + blueId);
            }
            if (owner == FragmentOwnership.EVENT) {
                event.add(blueId);
            } else {
                root.add(blueId);
            }
        }
        Map<String, Collection<String>> result =
                new LinkedHashMap<String, Collection<String>>();
        if (!root.isEmpty()) {
            result.put(
                    plan.rootInventory().inventoryIdentity(), root);
        }
        if (!event.isEmpty()) {
            result.put(
                    plan.eventInventory().inventoryIdentity(), event);
        }
        return result;
    }

    private static void mergeRepresentations(
            CoordinationProcessingPlan plan,
            Collection<String> preferred,
            Map<String, FragmentOwnership> ownership,
            Set<String> externallyManagedReferences,
            Map<String, CoordinationFragmentStore.FragmentRepresentations>
                    byInventory,
            Map<String, NodeProviderResult> processing,
            Map<String, NodeProviderResult> physical) {
        for (String blueId : preferred) {
            FragmentOwnership owner = ownership.get(blueId);
            if (owner == null) {
                if (!externallyManagedReferences.contains(blueId)) {
                    throw new IllegalStateException(
                            "Preferred fragment is absent from both "
                                    + "inventories: " + blueId);
                }
                processing.put(blueId, NodeProviderResult.notFound());
                physical.put(blueId, NodeProviderResult.notFound());
                continue;
            }
            String inventoryIdentity = owner == FragmentOwnership.EVENT
                    ? plan.eventInventory().inventoryIdentity()
                    : plan.rootInventory().inventoryIdentity();
            CoordinationFragmentStore.FragmentRepresentations source =
                    byInventory.get(inventoryIdentity);
            NodeProviderResult physicalResult = source == null
                    ? NodeProviderResult.notFound()
                    : result(source.physical(), blueId);
            NodeProviderResult processingResult =
                    owner == FragmentOwnership.SHARED
                    ? physicalResult
                    : source == null
                    ? NodeProviderResult.notFound()
                    : result(source.processing(), blueId);
            processing.put(blueId, processingResult);
            physical.put(blueId, physicalResult);
        }
    }

    /**
     * Derives the only identities for which a batch miss may consult the
     * runtime provider. The edge must be an authored pure reference reached
     * through this plan's admitted boundary, and its target must not be a
     * physical member of either bound inventory.
     */
    private static Set<String> externallyManagedReferenceBlueIds(
            CoordinationProcessingPlan plan,
            Set<String> allowed) {
        Set<String> result = new LinkedHashSet<String>();
        addExternallyManagedReferenceBlueIds(
                plan.rootInventory(),
                plan.eventInventory(),
                allowed,
                result);
        addExternallyManagedReferenceBlueIds(
                plan.eventInventory(),
                plan.rootInventory(),
                allowed,
                result);
        return Collections.unmodifiableSet(result);
    }

    private static void addExternallyManagedReferenceBlueIds(
            CoordinationFragmentInventory inventory,
            CoordinationFragmentInventory peerInventory,
            Set<String> allowed,
            Set<String> result) {
        for (FragmentEdgeRecord edge : inventory.edges()) {
            if (edge.originalPureReference()
                    && allowed.contains(edge.ownerNodeBlueId())
                    && !inventory.ownsExactBody(edge.childBlueId())
                    && !peerInventory.ownsExactBody(edge.childBlueId())) {
                result.add(edge.childBlueId());
            }
        }
    }

    private static NodeProviderResult result(
            Map<String, NodeProviderResult> results,
            String blueId) {
        NodeProviderResult result = results.get(blueId);
        return result == null ? NodeProviderResult.notFound() : result;
    }

    private static InitialAccounting initialAccounting(
            Collection<String> preferred,
            Map<String, NodeProviderResult> processing,
            Map<String, NodeProviderResult> physical) {
        List<String> loaded = new ArrayList<String>();
        long loadedBytes = 0L;
        for (String blueId : preferred) {
            NodeProviderResult processResult = processing.get(blueId);
            NodeProviderResult physicalResult = physical.get(blueId);
            boolean processFound = isFound(processResult);
            boolean physicalFound = isFound(physicalResult);
            if (processFound || physicalFound) {
                loaded.add(blueId);
            }
            List<Object> retainedWireForms = new ArrayList<Object>();
            if (processFound) {
                loadedBytes += distinctBytes(
                        processResult.nodes(), retainedWireForms);
            }
            if (physicalFound) {
                loadedBytes += distinctBytes(
                        physicalResult.nodes(), retainedWireForms);
            }
        }
        return new InitialAccounting(loaded, loadedBytes);
    }

    private static long distinctBytes(
            List<Node> nodes,
            List<Object> retainedWireForms) {
        long bytes = 0L;
        for (Node node : nodes) {
            Object wireForm = NodeWireForm.get(node);
            if (!retainedWireForms.contains(wireForm)) {
                retainedWireForms.add(wireForm);
                bytes += RequestLocalNodeProvider.bytes(node);
            }
        }
        return bytes;
    }

    private static boolean isFound(NodeProviderResult result) {
        return result != null
                && result.outcome() == NodeProviderOutcome.FOUND;
    }

    private NodeProvider inventoryScopedFallbackProvider(
            CoordinationProcessingPlan plan,
            Map<String, FragmentOwnership> ownership) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : Collections.<Node>emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                FragmentOwnership owner = ownership.get(blueId);
                if (owner == null) {
                    return NodeProviderResult.notFound();
                }
                switch (owner) {
                    case ROOT:
                        return fragmentStore.readProcessing(
                                plan.rootInventory().inventoryIdentity(),
                                blueId);
                    case EVENT:
                        return fragmentStore.readProcessing(
                                plan.eventInventory().inventoryIdentity(),
                                blueId);
                    case SHARED:
                        return fragmentStore.readCanonical(blueId);
                    default:
                        throw new IllegalStateException(
                                "Unknown fragment ownership " + owner);
                }
            }
        };
    }

    private static NodeProvider selectedFragmentProvider(
            CoordinationProcessingPlan plan,
            FragmentGraphIndex rootGraph,
            FragmentGraphIndex eventGraph,
            Map<String, FragmentOwnership> ownership,
            Map<String, NodeProviderResult> processingBatch,
            Map<String, NodeProviderResult> physicalBatch) {
        Map<String, NodeProviderResult> memoized = new LinkedHashMap<>();
        return blueId -> {
            NodeProviderResult prior = memoized.get(blueId);
            if (prior != null) {
                return prior.nodes();
            }
            FragmentOwnership owner = ownership.get(blueId);
            CoordinationFragmentInventory inventory =
                    owner == FragmentOwnership.ROOT
                            ? plan.rootInventory()
                            : owner == FragmentOwnership.EVENT
                            ? plan.eventInventory()
                            : null;
            FragmentGraphIndex graph =
                    owner == FragmentOwnership.ROOT
                            ? rootGraph
                            : owner == FragmentOwnership.EVENT
                            ? eventGraph
                            : null;
            NodeProviderResult processView = processingBatch.get(blueId);
            NodeProviderResult direct = physicalBatch.get(blueId);
            Node processNode = singleFoundNode(processView);
            Node directNode = singleFoundNode(direct);
            if (processNode != null
                    && !processNode.isReferenceOnly()
                    && directNode != null
                    && graph != null
                    && graph.isSourceContribution(blueId)
                    && !NodeWireForm.get(processNode).equals(
                            NodeWireForm.get(directNode))) {
                memoized.put(blueId, processView);
                return Collections.singletonList(processNode);
            }
            if (inventory == null || directNode == null) {
                NodeProviderResult result = directNode == null
                        ? NodeProviderResult.notFound()
                        : NodeProviderResult.found(
                                Collections.singletonList(directNode));
                memoized.put(blueId, result);
                return result.nodes();
            }
            Set<String> closure = selectedClosure(
                    graph,
                    blueId,
                    physicalBatch);
            Map<String, Node> fragments = new TreeMap<>();
            for (String selected : closure) {
                NodeProviderResult value = physicalBatch.get(selected);
                Node selectedNode = singleFoundNode(value);
                if (selectedNode == null) {
                    NodeProviderResult result = processNode == null
                            ? NodeProviderResult.notFound()
                            : NodeProviderResult.found(
                                    Collections.singletonList(processNode));
                    memoized.put(blueId, result);
                    return result.nodes();
                }
                fragments.put(selected, selectedNode);
            }
            List<CoordinationDocumentSplitter.EdgeOccurrence> edges =
                    new ArrayList<>();
            for (FragmentEdgeRecord edge : inventory.edges()) {
                if (closure.contains(edge.ownerNodeBlueId())) {
                    edges.add(edge.toEdgeOccurrence(
                            inventory.fragmentationProfileIdentity()));
                }
            }
            Node expanded = CoordinationFragmentReconstructor
                    .reconstructSelectedFragment(
                            inventory.fragmentationProfileIdentity(),
                            inventory.rootBlueId(),
                            blueId,
                            fragments,
                            edges,
                            graph.executableBodyBlueIds());
            NodeProviderResult result = NodeProviderResult.found(
                    Collections.singletonList(expanded));
            memoized.put(blueId, result);
            return result.nodes();
        };
    }

    private static Node singleFoundNode(NodeProviderResult result) {
        if (!isFound(result)) {
            return null;
        }
        List<Node> nodes = result.nodes();
        return nodes.size() == 1 ? nodes.get(0) : null;
    }

    private static Set<String> selectedClosure(
            FragmentGraphIndex graph,
            String rootBlueId,
            Map<String, NodeProviderResult> batch) {
        Set<String> closure = new LinkedHashSet<>();
        if (!graph.fragmentBlueIds().contains(rootBlueId)) {
            return closure;
        }
        ArrayDeque<String> remaining = new ArrayDeque<String>();
        closure.add(rootBlueId);
        remaining.add(rootBlueId);
        while (!remaining.isEmpty()) {
            String ownerBlueId = remaining.removeFirst();
            Node owner = singleFoundNode(batch.get(ownerBlueId));
            for (FragmentEdgeRecord edge : graph.outgoing(ownerBlueId)) {
                if (edge.splitterCreated()
                        && CoordinationFragmentReconstructor.isPhysicalEdge(
                                owner,
                                edge.ownerRelativePointer(),
                                edge.childBlueId())
                        && graph.fragmentBlueIds().contains(
                                edge.childBlueId())
                        && closure.add(edge.childBlueId())) {
                    remaining.addLast(edge.childBlueId());
                }
            }
        }
        return closure;
    }

    private static Set<String> allowedFragments(
            CoordinationProcessingPlan plan,
            FragmentGraphIndex rootGraph) {
        Set<String> allowed = new LinkedHashSet<String>();
        allowed.addAll(plan.requiredSeedBlueIds());
        allowed.addAll(plan.preferredPrefetchBlueIds());
        allowed.addAll(plan.eventInventory().fragmentBlueIds());
        allowed.addAll(rootGraph.rootHeaderClosure());
        addSelectorCatalogHeaders(plan.rootInventory(), allowed);
        addSelected(plan.rootInventory(), plan, allowed);
        return allowed;
    }

    private static void addSelectedSeedClosure(
            FragmentGraphIndex graph,
            Set<String> preferred) {
        Set<String> retained = new LinkedHashSet<String>(preferred);
        retained.remove(graph.rootBlueId());
        preferred.addAll(
                graph.selectedSeedAndContributionClosure(retained));
    }

    /**
     * Allows body-free collection containers that Language's indexed-plan
     * verifier uses to reproduce the active selector catalog. Embedded member
     * Roots and their executable bodies remain outside this closure unless
     * the delivery plan selected them.
     */
    private static void addSelectorCatalogHeaders(
            CoordinationFragmentInventory inventory,
            Set<String> allowed) {
        for (FragmentEdgeRecord edge : inventory.edges()) {
            if (edge.edgeKind()
                    == blue.coordination.processor.CoordinationDocumentSplitter
                    .EdgeKind.EMBEDDED_ROOT) {
                allowed.add(edge.ownerNodeBlueId());
            }
        }
    }

    private static void addSelected(
            CoordinationFragmentInventory inventory,
            CoordinationProcessingPlan plan,
            Set<String> allowed) {
        List<String> scopes = plan.demandBoundary().selectedScopePaths();
        for (FragmentMetadataRecord metadata : inventory.metadata()) {
            if (metadata.scopePath() != null
                    && onSelectedChain(metadata.scopePath(), scopes)) {
                allowed.add(metadata.blueId());
            }
        }
        for (FragmentEdgeRecord edge : inventory.edges()) {
            if (edge.ownerScopePath() != null
                    && onSelectedChain(edge.ownerScopePath(), scopes)) {
                allowed.add(edge.ownerNodeBlueId());
                allowed.add(edge.childBlueId());
                allowed.addAll(edge.sourceContributionBlueIds());
            }
        }
    }

    private static boolean onSelectedChain(
            String candidate,
            List<String> selectedScopes) {
        for (String selected : selectedScopes) {
            if (PointerUtils.descendantOrEqual(selected, candidate)) {
                return true;
            }
        }
        return false;
    }

    private enum FragmentOwnership {
        ROOT,
        EVENT,
        SHARED
    }

    private static final class InitialAccounting {
        private final List<String> loadedBlueIds;
        private final long loadedBytes;

        private InitialAccounting(
                Collection<String> loadedBlueIds,
                long loadedBytes) {
            this.loadedBlueIds = Collections.unmodifiableList(
                    new ArrayList<String>(loadedBlueIds));
            this.loadedBytes = loadedBytes;
        }
    }
}
