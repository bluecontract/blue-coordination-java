package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.codec.BlueFormat;
import blue.language.conformance.ConformanceEngine;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import blue.repo.BlueRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * One immutable production composition of Language, Contracts, BEX, and the
 * current generated Repository.
 */
final class BlueRuntime implements AutoCloseable {
    static final String PROVIDER_EXACT_NODE_READS =
            "provider.exactNodeReads";

    private final NodeProvider nodeProvider;
    private final BlueLanguage language;
    private final BlueContracts contracts;
    private final DocumentProcessor processor;
    private final ConformanceEngine processorConformanceEngine;
    private final EngineMetrics metrics;
    private boolean closed;

    private BlueRuntime(
            NodeProvider nodeProvider,
            BlueLanguage language,
            BlueContracts contracts,
            DocumentProcessor processor,
            ConformanceEngine processorConformanceEngine,
            EngineMetrics metrics) {
        this.nodeProvider = Objects.requireNonNull(
                nodeProvider, "nodeProvider");
        this.language = Objects.requireNonNull(language, "language");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.processorConformanceEngine = Objects.requireNonNull(
                processorConformanceEngine, "processorConformanceEngine");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    static BlueRuntime create(WholeObjectStore wholeObjects) {
        return create(wholeObjects, new EngineMetrics());
    }

    static BlueRuntime create(
            WholeObjectStore wholeObjects,
            EngineMetrics metrics) {
        return create(wholeObjects, metrics, null);
    }

    /** Creates an isolated runtime with one optional read-only provider leaf. */
    static BlueRuntime create(
            WholeObjectStore wholeObjects,
            EngineMetrics metrics,
            NodeProvider exactNodeProvider) {
        BlueRepository repository = BlueRepository.current();
        List<NodeProvider> providers = new ArrayList<>();
        providers.add(metered(
                Objects.requireNonNull(wholeObjects, "wholeObjects"),
                metrics));
        providers.add(metered(BlueCoreTypeRegistry.INSTANCE.verifiedProvider(), metrics));
        providers.add(metered(BlueRuntimeTypeRegistry.getDefault()
                .asProcessorSnapshotProvider(), metrics));
        RepositoryNodeProviders repositoryProviders =
                repositoryNodeProviders(repository);
        providers.add(metered(
                repositoryProviders.repositoryProvider(), metrics));
        providers.add(metered(
                repositoryProviders.exactNodes(), metrics));
        if (exactNodeProvider != null) {
            providers.add(metered(exactNodeProvider, metrics));
        }
        NodeProvider nodeProvider =
                new CyclicAwareSequentialNodeProvider(providers);

        Map<String, String> imports = new LinkedHashMap<>();
        imports.putAll(repository.preprocessingAliases());
        imports.putAll(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID);
        BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(nodeProvider)
                .preprocessingAliases(imports)
                .environmentImports(imports)
                .cachePolicy(BlueCachePolicy.highThroughputDefaults())
                .build();
        CoordinationProcessorOptions options =
                CoordinationProcessorOptions.builder()
                        .language(language)
                        .build();
        ContractProcessorRegistry runtimeRegistry =
                CoordinationProcessors.configure(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults(),
                        options)
                .build();
        String runtimeRegistryIdentity =
                runtimeRegistry.generationIdentity();
        BlueContracts contracts = BlueContracts.builder(
                        language.processing())
                .runtimeRegistry(runtimeRegistry)
                .build();
        ConformanceEngine processorConformanceEngine =
                language.processing().newConformanceEngine();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeAccess(contracts.runtimeAccess())
                .runtimeRegistry(runtimeRegistry)
                .runtimeRegistryIdentity(runtimeRegistryIdentity)
                .conformanceEngine(processorConformanceEngine)
                .build();
        return new BlueRuntime(
                nodeProvider,
                language,
                contracts,
                processor,
                processorConformanceEngine,
                metrics);
    }

    Node parseSourceYaml(String yaml) {
        ensureOpen();
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    Node preprocess(Node source) {
        ensureOpen();
        return language.preprocessing().preprocess(source);
    }

    String nodeToYaml(Node node) {
        ensureOpen();
        return language.codec().write(node, BlueFormat.YAML);
    }

    ResolvedSnapshot resolveToSnapshot(Node source) {
        ensureOpen();
        return language.snapshots().resolve(source);
    }

    ResolvedSnapshot resolveToSnapshotPreservingPaths(
            Node source,
            Collection<String> paths) {
        ensureOpen();
        return language.snapshots().resolvePreservingPaths(source, paths);
    }

    ResolvedSnapshot loadExactSnapshot(String blueId) {
        ensureOpen();
        FrozenNode reference = FrozenNode.fromNode(new Node().blueId(
                Objects.requireNonNull(blueId, "blueId")));
        BlueOperationResult<FrozenNode> result = contracts.runtimeAccess()
                .materializeVerifiedExactReference(reference);
        BlueOperationOutcome outcome = result.outcome();
        String reason = result.reason().orElse(
                "Exact provider content could not be established for "
                        + blueId);
        if (outcome == BlueOperationOutcome.INCOMPLETE) {
            throw new ExecutionEvidenceUnavailableException(
                    reason, result.outstandingBlueIds());
        }
        if (outcome != BlueOperationOutcome.ESTABLISHED) {
            throw new InvalidExecutionEvidenceException(reason);
        }
        FrozenNode materialized = result.requireEstablished();
        return contracts.runtimeAccess().resolveTransient(
                materialized.toNode());
    }

    ResolvedSnapshot cache(ResolvedSnapshot snapshot) {
        ensureOpen();
        return language.snapshots().cache(
                Objects.requireNonNull(snapshot, "snapshot"));
    }

    BlueCacheStats cacheStats() {
        ensureOpen();
        return language.snapshots().stats();
    }

    DocumentProcessingResult initialize(ResolvedSnapshot snapshot) {
        ensureOpen();
        return processor.initializeDocument(snapshot);
    }

    List<SubscriptionDelta.Entry> projectInitialOwnedSubscriptions(
            FrozenNode processingRoot,
            long rootRevision,
            ExternalOrderKey activationOrderKey) {
        ensureOpen();
        SubscriptionDelta delta = contracts.subscriptionSurfaceProjection()
                .projectInitial(
                        Objects.requireNonNull(
                                processingRoot, "processingRoot").toNode(),
                        rootRevision,
                        Objects.requireNonNull(
                                activationOrderKey, "activationOrderKey"));
        if (!delta.removed().isEmpty()) {
            throw new IllegalStateException(
                    "Initial subscription projection retired an occurrence");
        }
        return delta.added();
    }

    PlatformProcessingResult process(
            Node currentRootRepresentation,
            String exactEventBlueId,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> rootSubscriptions) {
        ensureOpen();
        Node root = Objects.requireNonNull(
                currentRootRepresentation, "currentRootRepresentation");
        Node eventReference = new Node().blueId(Objects.requireNonNull(
                exactEventBlueId, "exactEventBlueId"));
        long planStarted = System.nanoTime();
        ExternalDeliveryPlan deliveryPlan = contracts
                .currentRootDeliveryPlanDeriver(
                        rootRevision,
                        Objects.requireNonNull(eventOrderKey, "eventOrderKey"),
                        Objects.requireNonNull(
                                rootSubscriptions, "rootSubscriptions"))
                .derive(root, eventReference);
        metrics.addNanos("process.deliveryPlanDerivation",
                System.nanoTime() - planStarted);
        PlatformProcessInvocation invocation =
                PlatformProcessInvocation.builder()
                        .deliveryPlan(deliveryPlan)
                        .nodeProvider(nodeProvider)
                        .build();
        return metrics.timed("process.platformCommit", () ->
                contracts.processForPlatformCommit(
                        root, eventReference, invocation));
    }

    SubscriptionDelta projectSubscriptionUpdate(
            FrozenNode processingRoot,
            List<SubscriptionDelta.Entry> priorActiveIntervals,
            Set<String> changedRuntimePointers,
            long resultingRootRevision,
            ExternalOrderKey transitionOrderKey) {
        ensureOpen();
        return contracts.subscriptionSurfaceProjection().projectUpdate(
                Objects.requireNonNull(
                        processingRoot, "processingRoot").toNode(),
                Objects.requireNonNull(
                        priorActiveIntervals, "priorActiveIntervals"),
                Objects.requireNonNull(
                        changedRuntimePointers, "changedRuntimePointers"),
                resultingRootRevision,
                Objects.requireNonNull(
                        transitionOrderKey, "transitionOrderKey"));
    }

    EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            String exactRootBlueId) {
        ensureOpen();
        return contracts.effectiveFragmentationCatalog(
                new Node().blueId(Objects.requireNonNull(
                        exactRootBlueId, "exactRootBlueId")));
    }

    ExactValue exactSource(
            String yaml,
            WholeObjectStore objects,
            String purpose) {
        Node source = parseSourceYaml(yaml);
        Node preprocessed = preprocess(source);
        return objects.put(
                cache(resolveToSnapshot(preprocessed)), purpose);
    }

    ExactValue exactProcessingSource(String yaml, WholeObjectStore objects, String purpose) {
        Node preprocessed = preprocess(parseSourceYaml(yaml));
        return objects.put(contracts.canonicalizeProcessingSource(preprocessed), purpose);
    }

    /**
     * Parses direct provider content under this runtime's preprocessing
     * aliases without resolving the declared type as an instance.
     */
    ExactValue exactProviderSource(String yaml) {
        Node source = parseSourceYaml(Objects.requireNonNull(yaml, "yaml"));
        Node preprocessed = preprocess(source);
        if (preprocessed.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider content must be a whole exact Blue value");
        }
        String blueId = DirectBlueIdCalculator.calculateBlueId(preprocessed);
        return ExactValue.verified(blueId, preprocessed);
    }

    static NodeProvider retainedExactProvider(
            WholeObjectStore objects, NodeProvider applicationProvider) {
        return new CyclicAwareSequentialNodeProvider(applicationProvider == null
                ? List.of(objects) : List.of(objects, applicationProvider));
    }

    NodeProvider nodeProvider() {
        ensureOpen();
        return nodeProvider;
    }

    /** Returns the exact configured processor borrowed by closure execution. */
    DocumentProcessor documentProcessor() {
        ensureOpen();
        return processor;
    }

    EngineMetrics metrics() {
        ensureOpen();
        return metrics;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        close(contracts);
        close(processor);
        close(processorConformanceEngine);
        close(language);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Blue runtime is closed");
        }
    }

    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Could not close Blue runtime component", failure);
        }
    }

    private static NodeProvider metered(
            NodeProvider delegate,
            EngineMetrics metrics) {
        return delegate instanceof CyclicAwareNodeProvider cyclic
                ? new MeteredCyclicAwareNodeProvider(
                        delegate, cyclic, metrics)
                : new MeteredNodeProvider(delegate, metrics);
    }

    static RepositoryNodeProviders repositoryNodeProviders(BlueRepository repository) {
        return repositoryNodeProviders(Objects.requireNonNull(
                repository, "repository").nodeProvider());
    }
    static RepositoryNodeProviders repositoryNodeProviders(NodeProvider provider) {
        Objects.requireNonNull(provider, "repositoryProvider");
        RepositoryExactNodeCache cache = new RepositoryExactNodeCache();
        return new RepositoryNodeProviders(observe(provider, cache), cache);
    }
    private static NodeProvider observe(NodeProvider delegate, RepositoryExactNodeCache cache) {
        return delegate instanceof CyclicAwareNodeProvider cyclic
                ? new ObservingCyclicRepositoryNodeProvider(delegate, cyclic, cache)
                : new ObservingRepositoryNodeProvider(delegate, cache);
    }

    /** Transparent leaf meter preserving the provider graph seen by Language. */
    private static class MeteredNodeProvider implements NodeProvider {
        private final NodeProvider delegate;
        private final EngineMetrics metrics;

        private MeteredNodeProvider(
                NodeProvider delegate,
                EngineMetrics metrics) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.metrics = Objects.requireNonNull(metrics, "metrics");
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            metrics.increment(PROVIDER_EXACT_NODE_READS);
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            metrics.increment(PROVIDER_EXACT_NODE_READS);
            return delegate.fetchResultByBlueId(blueId);
        }
    }

    /** Leaf meter retaining complete cyclic-set proof capability. */
    private static final class MeteredCyclicAwareNodeProvider
            extends MeteredNodeProvider implements CyclicAwareNodeProvider {
        private final CyclicAwareNodeProvider cyclicDelegate;

        private MeteredCyclicAwareNodeProvider(
                NodeProvider delegate,
                CyclicAwareNodeProvider cyclicDelegate,
                EngineMetrics metrics) {
            super(delegate, metrics);
            this.cyclicDelegate = Objects.requireNonNull(
                    cyclicDelegate, "cyclicDelegate");
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return cyclicDelegate.hasVerifiedContentForBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return cyclicDelegate.cyclicSetProofFor(blueId);
        }
    }

    /** Ordered provider chain that preserves complete cyclic-proof lookup. */
    private static final class CyclicAwareSequentialNodeProvider
            extends SequentialNodeProvider
            implements CyclicAwareNodeProvider {
        private final List<NodeProvider> orderedProviders;

        private CyclicAwareSequentialNodeProvider(
                List<NodeProvider> providers) {
            super(providers);
            orderedProviders = Collections.unmodifiableList(
                    new ArrayList<>(providers));
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            for (NodeProvider provider : orderedProviders) {
                if (provider instanceof CyclicAwareNodeProvider cyclic
                        && cyclic.hasVerifiedContentForBlueId(blueId)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            for (NodeProvider provider : orderedProviders) {
                if (!(provider instanceof CyclicAwareNodeProvider cyclic)) {
                    continue;
                }
                CyclicSetProofResult result = cyclic.cyclicSetProofFor(
                        blueId);
                if (result.outcome() != NodeProviderOutcome.NOT_FOUND) {
                    return result;
                }
            }
            return CyclicSetProofResult.notFound();
        }
    }

    record RepositoryNodeProviders(NodeProvider repositoryProvider, RepositoryExactNodeCache exactNodes) { }
    private static class ObservingRepositoryNodeProvider implements NodeProvider {
        private final NodeProvider delegate;
        private final RepositoryExactNodeCache cache;
        private ObservingRepositoryNodeProvider(NodeProvider delegate, RepositoryExactNodeCache cache) {
            this.delegate = delegate;
            this.cache = cache;
        }
        @Override public List<Node> fetchByBlueId(String blueId) {
            List<Node> nodes = delegate.fetchByBlueId(blueId);
            if (nodes != null && !nodes.isEmpty()) cache.observe(nodes);
            return nodes;
        }
        @Override public NodeProviderResult fetchResultByBlueId(String blueId) {
            NodeProviderResult result = delegate.fetchResultByBlueId(blueId);
            if (result.outcome() == NodeProviderOutcome.FOUND) cache.observe(result.nodes());
            return result;
        }
    }
    private static final class ObservingCyclicRepositoryNodeProvider extends ObservingRepositoryNodeProvider
            implements CyclicAwareNodeProvider {
        private final CyclicAwareNodeProvider cyclic;
        private ObservingCyclicRepositoryNodeProvider(NodeProvider delegate, CyclicAwareNodeProvider cyclic,
                RepositoryExactNodeCache cache) {
            super(delegate, cache);
            this.cyclic = cyclic;
        }
        @Override public boolean hasVerifiedContentForBlueId(String id) { return cyclic.hasVerifiedContentForBlueId(id); }
        @Override public CyclicSetProofResult cyclicSetProofFor(String id) { return cyclic.cyclicSetProofFor(id); }
    }

    static final class RepositoryExactNodeCache implements NodeProvider {
        private volatile Map<String, Node> snapshot = Map.of();
        @Override public List<Node> fetchByBlueId(String blueId) {
            Node found = snapshot.get(Objects.requireNonNull(blueId));
            return found == null ? null : Collections.singletonList(found.clone());
        }
        void observe(List<Node> nodes) {
            Map<String, Node> additions = collect(Objects.requireNonNull(nodes, "returnedNodes"));
            if (!additions.isEmpty()) publish(additions);
        }
        List<String> cachedBlueIds() { return List.copyOf(snapshot.keySet()); }
        private static Map<String, Node> collect(List<Node> nodes) {
            Map<String, Node> additions = sortedMap();
            IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
            Deque<Node> pending = new ArrayDeque<>();
            for (Node node : nodes) if (node != null) pending.addLast(node.clone());
            while (!pending.isEmpty()) {
                Node node = pending.removeFirst();
                if (node.isReferenceOnly() || visited.put(node, Boolean.TRUE) != null) continue;
                index(node, additions);
                enqueueChildren(node, pending);
            }
            return additions;
        }
        private static void index(Node node, Map<String, Node> additions) {
            Node exact = node.clone();
            String declared = exact.getBlueId();
            if (declared != null && declared.indexOf('#') >= 0) return;
            if (declared != null) exact.blueId(null);
            String blueId;
            try {
                blueId = DirectBlueIdCalculator.calculateBlueId(exact);
            } catch (IllegalArgumentException notDirect) {
                if (declared != null) throw new IllegalStateException(
                        "Repository subtree " + declared + " is not valid ordinary exact content", notDirect);
                return;
            }
            if (declared != null && !declared.equals(blueId)) throw new IllegalStateException(
                    "Repository subtree " + declared + " calculates to " + blueId);
            Node prior = additions.putIfAbsent(blueId, exact);
            requireSame(blueId, prior, exact);
        }
        private synchronized void publish(Map<String, Node> additions) {
            Map<String, Node> merged = sortedMap();
            merged.putAll(snapshot);
            for (Map.Entry<String, Node> addition : additions.entrySet()) {
                Node exact = addition.getValue().clone();
                Node prior = merged.putIfAbsent(addition.getKey(), exact);
                requireSame(addition.getKey(), prior, exact);
            }
            snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
        }
        private static Map<String, Node> sortedMap() { return new TreeMap<>(ExternalOrderKey::compareTextCodePoints); }
        private static void requireSame(String blueId, Node prior, Node exact) {
            if (prior != null
                    && !NodeWireForm.get(prior).equals(NodeWireForm.get(exact))) throw new IllegalStateException(
                    "Conflicting Repository content for " + blueId);
        }
        private static void enqueueChildren(Node node, Deque<Node> pending) {
            add(pending, node.getType());
            add(pending, node.getItemType());
            add(pending, node.getKeyType());
            add(pending, node.getValueType());
            add(pending, node.getBlue());
            add(pending, node.getContracts());
            if (node.getProperties() != null) node.getProperties().values().forEach(child -> add(pending, child));
            if (node.getItems() != null) node.getItems().forEach(child -> add(pending, child));
        }
        private static void add(Deque<Node> pending, Node child) { if (child != null) pending.addLast(child); }
    }
}
