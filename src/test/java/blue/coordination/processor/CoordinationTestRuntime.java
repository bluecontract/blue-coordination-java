package blue.coordination.processor;

import blue.coordination.internal.RepositoryNodeProviderTestBridge;
import blue.language.api.BlueCachePolicy;
import blue.language.codec.BlueFormat;
import blue.language.mapping.BlueMapper;
import blue.language.mapping.TypeClassResolver;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.ContractProcessor;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.repo.BlueRepository;
import blue.repo.coordination.TimelineChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Current-API composition fixture for Coordination tests.
 *
 * <p>The fixture keeps Language, Contracts, mapping, and exact Repository
 * evidence as separate named services. Configuration changes rebuild an
 * immutable processor generation; no removed mutable {@code Blue} API is
 * reproduced in production code.</p>
 */
public final class CoordinationTestRuntime implements AutoCloseable {

    private final BlueRepository repository;
    private final BlueCachePolicy cachePolicy;
    private final NodeProvider currentRepositoryNodes;
    private final NodeProvider currentRepositoryExactNodes;
    private final List<NodeProvider> additionalProviders =
            new ArrayList<NodeProvider>();
    private final List<Class<? extends TimelineChannel>> timelineSubtypes =
            new ArrayList<Class<? extends TimelineChannel>>();
    private final List<ExternalRegistration> externalRegistrations =
            new ArrayList<ExternalRegistration>();
    private final TypeClassResolver typeClassResolver =
            new TypeClassResolver("blue.repo");
    private final BlueMapper mapping = BlueMapper.builder()
            .registerMappings(typeClassResolver)
            .build();

    private CoordinationProcessorOptions options;
    private ProcessingObserver explicitObserver;
    private NodeProvider nodeProvider;
    private BlueLanguage language;
    private BlueContracts contracts;
    private DocumentProcessor processor;
    private boolean closed;

    private CoordinationTestRuntime(
            BlueRepository repository,
            Collection<NodeProvider> initialProviders,
            BlueCachePolicy cachePolicy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        RepositoryNodeProviderTestBridge.Providers repositoryProviders =
                RepositoryNodeProviderTestBridge.create(repository);
        this.currentRepositoryNodes =
                repositoryProviders.repositoryProvider();
        this.currentRepositoryExactNodes =
                repositoryProviders.exactNodes();
        this.additionalProviders.addAll(Objects.requireNonNull(
                initialProviders, "initialProviders"));
        rebuild();
    }

    /** Creates a fixture bound to the exact selected Repository release. */
    public static CoordinationTestRuntime create(BlueRepository repository) {
        return new CoordinationTestRuntime(
                repository,
                Collections.<NodeProvider>emptyList(),
                BlueCachePolicy.boundedDefaults());
    }

    /**
     * Creates one runtime generation with its highest-priority provider and
     * cache policy already installed. This avoids constructing and immediately
     * closing a throwaway generation during test-environment startup.
     */
    public static CoordinationTestRuntime create(
            BlueRepository repository,
            NodeProvider highestPriorityProvider,
            BlueCachePolicy cachePolicy) {
        return new CoordinationTestRuntime(
                repository,
                Collections.singletonList(Objects.requireNonNull(
                        highestPriorityProvider,
                        "highestPriorityProvider")),
                cachePolicy);
    }

    /** Returns the focused Language runtime. */
    public BlueLanguage language() {
        ensureOpen();
        return language;
    }

    /** Returns the current immutable Contracts processor generation. */
    public DocumentProcessor processor() {
        ensureOpen();
        return processor;
    }

    /** Returns the focused Contracts service used by managed-host tests. */
    public BlueContracts contracts() {
        ensureOpen();
        return contracts;
    }

    /** Returns the exact provider shared by Language and Contracts. */
    public NodeProvider nodeProvider() {
        ensureOpen();
        return nodeProvider;
    }

    /** Returns the immutable test fixture's current Repository type map. */
    public TypeClassResolver typeClassResolver() {
        ensureOpen();
        return typeClassResolver;
    }

    /** Compatibility spelling retained only for older Coordination fixtures. */
    public TypeClassResolver getTypeClassResolver() {
        return typeClassResolver();
    }

    /** Rebuilds the fixture with one additional highest-priority provider. */
    public void addNodeProvider(NodeProvider provider) {
        ensureOpen();
        additionalProviders.add(0, Objects.requireNonNull(
                provider, "provider"));
        rebuild();
    }

    /** Rebuilds the fixture with explicit Coordination processor options. */
    public void configure(CoordinationProcessorOptions newOptions) {
        ensureOpen();
        options = Objects.requireNonNull(newOptions, "options");
        rebuild();
    }

    /**
     * Rebuilds with independent host observation and workflow metrics.
     * Both callbacks remain observational and failure-isolated.
     */
    public void configure(
            CoordinationProcessorOptions newOptions,
            ProcessingObserver observer) {
        ensureOpen();
        options = Objects.requireNonNull(newOptions, "options");
        explicitObserver = Objects.requireNonNull(observer, "observer");
        rebuild();
    }

    /** Registers one Timeline Channel subtype in a successor generation. */
    public <T extends TimelineChannel> void registerTimelineSubtype(
            Class<T> contractType) {
        ensureOpen();
        timelineSubtypes.add(Objects.requireNonNull(
                contractType, "contractType"));
        rebuild();
    }

    /** Registers one exact external contract type in a successor generation. */
    public void registerExternalContractType(
            String blueId,
            Node canonicalType,
            ContractProcessor<? extends Contract> contractProcessor) {
        ensureOpen();
        externalRegistrations.add(new ExternalRegistration(
                blueId,
                canonicalType,
                contractProcessor));
        rebuild();
    }

    public Node parseSourceYaml(String yaml) {
        ensureOpen();
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    public Node parseSourceJson(String json) {
        ensureOpen();
        return language.codec().parseSource(json, BlueFormat.JSON);
    }

    public Node yamlToNode(String yaml) {
        return preprocess(parseSourceYaml(yaml));
    }

    public Node jsonToNode(String json) {
        return preprocess(parseSourceJson(json));
    }

    public String nodeToYaml(Node node) {
        ensureOpen();
        return language.codec().write(node, BlueFormat.YAML);
    }

    public String nodeToJson(Node node) {
        ensureOpen();
        return language.codec().write(node, BlueFormat.JSON);
    }

    public Node objectToNode(Object value) {
        ensureOpen();
        return preprocess(mapping.toNode(value));
    }

    public <T> T nodeToObject(Node node, Class<T> targetClass) {
        ensureOpen();
        return mapping.fromNode(node, targetClass);
    }

    public Node preprocess(Node source) {
        ensureOpen();
        return language.preprocessing().preprocess(source);
    }

    public Node resolve(Node source) {
        ensureOpen();
        return language.resolution().resolve(source);
    }

    public ResolvedSnapshot resolveToSnapshot(Node source) {
        ensureOpen();
        return language.snapshots().resolve(source);
    }

    public ResolvedSnapshot loadSnapshot(String blueId) {
        ensureOpen();
        return language.snapshots().load(blueId);
    }

    public ResolvedSnapshot loadSnapshot(Node canonicalIdentityInput) {
        ensureOpen();
        return language.snapshots().load(canonicalIdentityInput);
    }

    public ResolvedSnapshot resolveToSnapshotPreservingPaths(
            Node source,
            Collection<String> paths) {
        ensureOpen();
        return language.snapshots().resolvePreservingPaths(source, paths);
    }

    public void clearResolvedSnapshotCache() {
        ensureOpen();
        language.snapshots().clear();
    }

    public String calculateBlueId(Node exactInput) {
        ensureOpen();
        return language.identity().directBlueId(exactInput);
    }

    public String calculateSourceDocumentBlueId(Node source) {
        ensureOpen();
        return language.identity().sourceDocumentBlueId(source);
    }

    public Node canonicalize(Node source) {
        ensureOpen();
        return language.identity().canonicalIdentityInput(source);
    }

    public boolean nodeMatchesType(Node candidate, Node type) {
        ensureOpen();
        return language.matching().matches(candidate, type);
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        ensureOpen();
        return processor.initializeDocument(document);
    }

    public DocumentProcessingResult initializeDocument(
            ResolvedSnapshot snapshot) {
        ensureOpen();
        return processor.initializeDocument(snapshot);
    }

    public DocumentProcessingResult processDocument(Node root, Node event) {
        ensureOpen();
        return processor.processDocument(root, event);
    }

    public DocumentProcessingResult processDocument(
            ResolvedSnapshot snapshot,
            Node event) {
        ensureOpen();
        return processor.processDocument(snapshot, event);
    }

    public boolean isInitialized(Node document) {
        ensureOpen();
        return processor.isInitialized(document);
    }

    public boolean isInitialized(ResolvedSnapshot snapshot) {
        ensureOpen();
        return processor.isInitialized(snapshot);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeGeneration();
    }

    private void rebuild() {
        BlueContracts previousContracts = contracts;
        DocumentProcessor previousProcessor = processor;
        BlueLanguage previousLanguage = language;

        List<NodeProvider> providers = new ArrayList<NodeProvider>();
        providers.addAll(additionalProviders);
        providers.add(BlueRuntimeTypeRegistry.getDefault()
                .asProcessorSnapshotProvider());
        providers.add(currentRepositoryNodes);
        providers.add(currentRepositoryExactNodes);
        NodeProvider nextProvider = new SequentialNodeProvider(providers);

        Map<String, String> imports = new LinkedHashMap<String, String>();
        imports.putAll(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID);
        imports.putAll(repository.preprocessingAliases());
        BlueLanguage nextLanguage = BlueLanguage.builder()
                .nodeProvider(nextProvider)
                .preprocessingAliases(imports)
                .environmentImports(imports)
                .cachePolicy(cachePolicy)
                .build();

        CoordinationProcessorOptions effectiveOptions =
                optionsWithCurrentLanguage(nextLanguage);
        ContractProcessorRegistryBuilder registry =
                CoordinationProcessors.configure(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults(),
                        effectiveOptions);
        for (Class<? extends TimelineChannel> subtype : timelineSubtypes) {
            registerTimelineSubtype(registry, subtype);
        }
        for (ExternalRegistration registration : externalRegistrations) {
            registry.register(
                    registration.blueId,
                    registration.canonicalType.clone(),
                    registration.processor);
        }
        BlueContracts.Builder contractsBuilder = BlueContracts.builder(
                        nextLanguage.processing())
                .runtimeRegistry(registry.build());
        ProcessingObserver contractsObserver =
                CoordinationProcessors.observers(
                        explicitObserver,
                        effectiveOptions.processingMetrics());
        if (contractsObserver != null) {
            contractsBuilder.observer(contractsObserver);
        }
        BlueContracts nextContracts = contractsBuilder.build();

        DocumentProcessor.Builder builder =
                CoordinationProcessors.configure(
                        DocumentProcessor.builder()
                                .runtimeAccess(
                                        nextContracts.runtimeAccess()),
                        effectiveOptions);
        if (explicitObserver != null) {
            builder.observer(CoordinationProcessors.observers(
                    explicitObserver,
                    effectiveOptions.processingMetrics()));
        }
        for (Class<? extends TimelineChannel> subtype : timelineSubtypes) {
            builder = registerTimelineSubtype(builder, subtype);
        }
        for (ExternalRegistration registration : externalRegistrations) {
            builder.registerContractProcessor(
                    registration.blueId,
                    registration.canonicalType.clone(),
                    registration.processor);
        }
        builder.runtimeRegistryIdentity(
                "blue.coordination/test-support-runtime/1.0");
        DocumentProcessor nextProcessor = builder.build();

        nodeProvider = nextProvider;
        language = nextLanguage;
        contracts = nextContracts;
        processor = nextProcessor;
        close(previousContracts);
        close(previousProcessor);
        close(previousLanguage);
    }

    private CoordinationProcessorOptions optionsWithCurrentLanguage(
            BlueLanguage currentLanguage) {
        if (options == null) {
            return CoordinationProcessorOptions.builder()
                    .language(currentLanguage)
                    .build();
        }
        CoordinationProcessorOptions.Builder builder =
                CoordinationProcessorOptions.builder()
                        .defaultComputeGasLimit(
                                options.defaultComputeGasLimit())
                        .processingMetrics(options.processingMetrics())
                        .processingEventIdentityObserver(
                                options.processingEventIdentityObserver());
        if (options.sequentialWorkflowRunner() != null) {
            builder.sequentialWorkflowRunner(
                    options.sequentialWorkflowRunner());
        } else if (options.bexEngine() != null) {
            builder.bexEngine(options.bexEngine());
        } else {
            builder.language(currentLanguage);
        }
        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DocumentProcessor.Builder registerTimelineSubtype(
            DocumentProcessor.Builder builder,
            Class<? extends TimelineChannel> subtype) {
        return CoordinationProcessors.registerTimelineSubtype(
                builder,
                (Class) subtype);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ContractProcessorRegistryBuilder registerTimelineSubtype(
            ContractProcessorRegistryBuilder registry,
            Class<? extends TimelineChannel> subtype) {
        return CoordinationProcessors.registerTimelineSubtype(
                registry,
                (Class) subtype);
    }

    private void closeGeneration() {
        close(contracts);
        contracts = null;
        close(processor);
        processor = null;
        close(language);
        language = null;
        nodeProvider = null;
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Could not close test runtime generation", failure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Coordination test runtime is closed");
        }
    }

    private static final class ExternalRegistration {
        private final String blueId;
        private final Node canonicalType;
        private final ContractProcessor<? extends Contract> processor;

        private ExternalRegistration(
                String blueId,
                Node canonicalType,
                ContractProcessor<? extends Contract> processor) {
            this.blueId = Objects.requireNonNull(blueId, "blueId");
            this.canonicalType = Objects.requireNonNull(
                    canonicalType, "canonicalType").clone();
            this.processor = Objects.requireNonNull(
                    processor, "processor");
        }
    }
}
