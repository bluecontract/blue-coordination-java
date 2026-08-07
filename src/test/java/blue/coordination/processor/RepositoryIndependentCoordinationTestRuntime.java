package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.codec.BlueFormat;
import blue.language.mapping.BlueMapper;
import blue.language.mapping.TypeClassResolver;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.IndexedDeliveryEvaluator;
import blue.language.processor.SubscriptionSurfaceProjection;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.TimelineChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repository-independent test composition for the production Coordination
 * processor stack.
 *
 * <p>The fixture builds one current Language runtime, one public Contracts
 * service, and one standalone {@link DocumentProcessor} importing that
 * service's immutable {@link BlueContracts#runtimeAccess() runtime access}.
 * Model mappings are explicit; no package scan or Repository composition root
 * participates in construction.</p>
 */
public final class RepositoryIndependentCoordinationTestRuntime
        implements AutoCloseable {

    public static final String RUNTIME_REGISTRY_IDENTITY =
            "blue.coordination/test/repository-independent-runtime/1:"
                    + RepositoryIndependentCoordinationTypes
                    .semanticTypes().profileIdentity();

    private final List<NodeProvider> additionalProviders =
            new ArrayList<NodeProvider>();

    private CoordinationProcessorOptions options;
    private ProcessingObserver explicitObserver;
    private ExternalDeliveryPlanDeriver deliveryPlanDeriver;
    private RepositoryIndependentCoordinationProvider typeProvider;
    private NodeProvider nodeProvider;
    private TypeClassResolver typeClassResolver;
    private BlueMapper mapping;
    private BlueLanguage language;
    private ContractProcessorRegistry registry;
    private BlueContracts contracts;
    private DocumentProcessor processor;
    private SequentialWorkflowRunner workflowRunner;
    private boolean ownsWorkflowRunner;
    private boolean closed;

    private RepositoryIndependentCoordinationTestRuntime() {
        rebuildGeneration();
    }

    /** Opens a production-processor runtime without loading a Repository root. */
    public static RepositoryIndependentCoordinationTestRuntime create() {
        return new RepositoryIndependentCoordinationTestRuntime();
    }

    /** Synonym convenient for try-with-resources fixture code. */
    public static RepositoryIndependentCoordinationTestRuntime open() {
        return create();
    }

    /** Returns the current focused Language generation. */
    public BlueLanguage language() {
        ensureOpen();
        return language;
    }

    /** Returns the public Contracts services sharing this Language runtime. */
    public BlueContracts contracts() {
        ensureOpen();
        return contracts;
    }

    /** Returns the standalone traced production Coordination processor. */
    public DocumentProcessor processor() {
        ensureOpen();
        return processor;
    }

    /**
     * Returns the same standalone processor through its public atomic host
     * commit surface.
     */
    public DocumentProcessor platformProcessor() {
        return processor();
    }

    /** Projection owned by the exact public Contracts generation. */
    public SubscriptionSurfaceProjection subscriptionSurfaceProjection() {
        return contracts().subscriptionSurfaceProjection();
    }

    /** Indexed evaluator owned by the exact public Contracts generation. */
    public IndexedDeliveryEvaluator indexedDeliveryEvaluator() {
        return contracts().indexedDeliveryEvaluator();
    }

    /** Public Contracts compatibility deriver over supplied exact intervals. */
    public ExternalDeliveryPlanDeriver currentRootDeliveryPlanDeriver(
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> activeIntervals) {
        return contracts().currentRootDeliveryPlanDeriver(
                rootRevision, eventOrderKey, activeIntervals);
    }

    /** Returns the exact provider chain shared by Language and Contracts. */
    public NodeProvider nodeProvider() {
        ensureOpen();
        return nodeProvider;
    }

    /** Returns the provider for the test-owned processor type nodes. */
    public RepositoryIndependentCoordinationProvider typeProvider() {
        ensureOpen();
        return typeProvider;
    }

    /** Returns the explicit generated-model mapping retained by this fixture. */
    public TypeClassResolver typeClassResolver() {
        ensureOpen();
        return typeClassResolver;
    }

    /** Compatibility spelling used by some older fixture call sites. */
    public TypeClassResolver getTypeClassResolver() {
        return typeClassResolver();
    }

    /** Rebuilds the immutable generation with a highest-priority provider. */
    public void addNodeProvider(NodeProvider provider) {
        ensureOpen();
        additionalProviders.add(0, Objects.requireNonNull(
                provider, "provider"));
        rebuildGeneration();
    }

    /** Rebuilds with explicit production Coordination runtime options. */
    public void configure(CoordinationProcessorOptions newOptions) {
        ensureOpen();
        options = Objects.requireNonNull(newOptions, "newOptions");
        rebuildGeneration();
    }

    /** Rebuilds with production options and failure-isolated observation. */
    public void configure(
            CoordinationProcessorOptions newOptions,
            ProcessingObserver observer) {
        ensureOpen();
        options = Objects.requireNonNull(newOptions, "newOptions");
        explicitObserver = Objects.requireNonNull(observer, "observer");
        rebuildGeneration();
    }

    /**
     * Replaces only the standalone processor's immutable delivery generation.
     * The public Contracts projection/evaluation services remain current.
     */
    public void configureDeliveryPlanDeriver(
            ExternalDeliveryPlanDeriver deriver) {
        ensureOpen();
        deliveryPlanDeriver = Objects.requireNonNull(deriver, "deriver");
        rebuildStandaloneProcessor();
    }

    /** Parses YAML source without preprocessing it. */
    public Node parseSourceYaml(String yaml) {
        ensureOpen();
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    /** Parses JSON source without preprocessing it. */
    public Node parseSourceJson(String json) {
        ensureOpen();
        return language.codec().parseSource(json, BlueFormat.JSON);
    }

    /** Parses and preprocesses YAML against only current/test aliases. */
    public Node yamlToNode(String yaml) {
        return preprocess(parseSourceYaml(yaml));
    }

    /** Parses and preprocesses JSON against only current/test aliases. */
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

    public String calculateBlueId(Node exactInput) {
        ensureOpen();
        return language.identity().directBlueId(exactInput);
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

    public DocumentProcessingResult processDocument(Node root, Node event) {
        ensureOpen();
        return processor.processDocument(root, event);
    }

    /**
     * Binds a public exact delivery plan to this standalone processor's
     * explicit non-default registry identity.
     */
    public VerifiedExecutionEvidence executionEvidence(
            Node root,
            Node event,
            ExternalDeliveryPlan plan) {
        ensureOpen();
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        ExternalDeliveryPlan exactPlan = Objects.requireNonNull(
                plan, "plan");
        VerifiedExecutionEvidence.Builder evidence =
                VerifiedExecutionEvidence.builder(
                                calculateBlueId(root),
                                calculateBlueId(event))
                        .revisions(
                                exactPlan.managedRootRevision(),
                                exactPlan.indexedRootRevision())
                        .runtimeRegistryIdentity(
                                RUNTIME_REGISTRY_IDENTITY)
                        .eventOrderKey(exactPlan.eventOrderKey());
        for (ExternalDeliverySnapshot delivery
                : exactPlan.deliveries()) {
            evidence.delivery(delivery);
        }
        if (exactPlan.hasActiveSubscriptionIntervals()) {
            evidence.activeSubscriptionIntervals(
                    exactPlan.activeSubscriptionIntervals());
        }
        for (String blueId : exactPlan.availableExactNodeBlueIds()) {
            evidence.availableExactNode(blueId);
        }
        for (String blueId : exactPlan.requiredExactNodeBlueIds()) {
            evidence.requiredExactNode(blueId);
        }
        return evidence.build();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeGeneration();
    }

    private void rebuildGeneration() {
        closeGeneration();

        RepositoryIndependentCoordinationTypes
                .assertCanonicalIdentities();
        typeProvider = RepositoryIndependentCoordinationProvider.types();

        List<NodeProvider> providers = new ArrayList<NodeProvider>();
        providers.addAll(additionalProviders);
        providers.add(typeProvider);
        providers.add(BlueRuntimeTypeRegistry.getDefault()
                .asProcessorSnapshotProvider());
        nodeProvider = new SequentialNodeProvider(providers);

        Map<String, String> imports =
                new LinkedHashMap<String, String>();
        imports.putAll(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID);
        imports.putAll(RepositoryIndependentCoordinationTypes.aliases());
        language = BlueLanguage.builder()
                .nodeProvider(nodeProvider)
                .preprocessingAliases(imports)
                .environmentImports(imports)
                .build();

        typeClassResolver =
                RepositoryIndependentCoordinationTypes.newTypeResolver();
        mapping = BlueMapper.builder()
                .registerMappings(typeClassResolver)
                .build();

        CoordinationProcessorOptions effective =
                optionsWithCurrentLanguage(language);
        RunnerSelection selected = workflowRunner(effective, language);
        workflowRunner = selected.runner;
        ownsWorkflowRunner = selected.owned;
        registry = registry(
                workflowRunner,
                effective.semanticTypeIdentities());

        ProcessingObserver observer = CoordinationProcessors.observers(
                explicitObserver,
                effective.processingMetrics());
        BlueContracts.Builder contractsBuilder = BlueContracts.builder(
                        language.processing())
                .runtimeRegistry(registry)
                .gasSchedule(GasSchedule.contracts10());
        if (observer != null) {
            contractsBuilder.observer(observer);
        }
        contracts = contractsBuilder.build();
        rebuildStandaloneProcessor();
    }

    private void rebuildStandaloneProcessor() {
        close(processor);
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(contracts.runtimeAccess())
                .runtimeRegistry(registry)
                .contractTypeResolver(typeClassResolver)
                .gasSchedule(GasSchedule.contracts10())
                .runtimeRegistryIdentity(RUNTIME_REGISTRY_IDENTITY);
        ProcessingObserver observer = CoordinationProcessors.observers(
                explicitObserver,
                options != null ? options.processingMetrics() : null);
        if (observer != null) {
            builder.observer(observer);
        }
        if (deliveryPlanDeriver != null) {
            builder.deliveryPlanDeriver(deliveryPlanDeriver);
        }
        processor = builder.build();
    }

    private static ContractProcessorRegistry registry(
            SequentialWorkflowRunner runner,
            CoordinationSemanticTypeIdentities identities) {
        ContractProcessorRegistryBuilder builder =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .register(new TimelineChannelProcessor())
                        .register(new AllTimelinesChannelProcessor())
                        .register(new CompositeTimelineChannelProcessor())
                        .register(new OperationProcessor())
                        .register(new ChatWorkflowOperationProcessor(runner))
                        .register(new SequentialWorkflowProcessor(
                                runner))
                        .register(new SequentialWorkflowOperationProcessor(
                                runner));
        builder.register(
                RepositoryIndependentCoordinationTypes
                        .TIMELINE_CHANNEL_BLUE_ID,
                RepositoryIndependentCoordinationTypes
                        .timelineChannelType(),
                new TimelineChannelProcessor(identities));
        builder.register(
                RepositoryIndependentCoordinationTypes
                        .SEQUENTIAL_WORKFLOW_BLUE_ID,
                RepositoryIndependentCoordinationTypes
                        .sequentialWorkflowType(),
                new SequentialWorkflowProcessor(runner, identities));
        builder.register(
                RepositoryIndependentCoordinationTypes
                        .SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID,
                RepositoryIndependentCoordinationTypes
                        .sequentialWorkflowOperationType(),
                new SequentialWorkflowOperationProcessor(
                        runner, identities));
        builder.register(
                RepositoryIndependentCoordinationTypes
                        .COMPOSITE_TIMELINE_CHANNEL_BLUE_ID,
                RepositoryIndependentCoordinationTypes
                        .compositeTimelineChannelType(),
                new CompositeTimelineChannelProcessor(
                        RepositoryIndependentCoordinationTypes
                                .TIMELINE_CHANNEL_BLUE_ID,
                        identities));
        builder.register(
                RepositoryIndependentCoordinationTypes
                        .ALL_TIMELINES_CHANNEL_BLUE_ID,
                RepositoryIndependentCoordinationTypes
                        .allTimelinesChannelType(),
                new AllTimelinesChannelProcessor(
                        RepositoryIndependentCoordinationTypes
                                .TIMELINE_CHANNEL_BLUE_ID,
                        identities));
        return builder.build();
    }

    private CoordinationProcessorOptions optionsWithCurrentLanguage(
            BlueLanguage currentLanguage) {
        if (options == null) {
            return CoordinationProcessorOptions.builder()
                    .language(currentLanguage)
                    .semanticTypeIdentities(
                            RepositoryIndependentCoordinationTypes
                                    .semanticTypes())
                    .build();
        }
        CoordinationProcessorOptions.Builder builder =
                CoordinationProcessorOptions.builder()
                        .defaultComputeGasLimit(
                                options.defaultComputeGasLimit())
                        .processingMetrics(options.processingMetrics())
                        .processingEventIdentityObserver(
                                options.processingEventIdentityObserver())
                        .semanticTypeIdentities(
                                RepositoryIndependentCoordinationTypes
                                        .semanticTypes());
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

    private static RunnerSelection workflowRunner(
            CoordinationProcessorOptions effective,
            BlueLanguage currentLanguage) {
        if (effective.sequentialWorkflowRunner() != null) {
            return new RunnerSelection(
                    effective.sequentialWorkflowRunner(), false);
        }
        BexProcessingMetrics metrics = effective.processingMetrics();
        if (effective.bexEngine() != null) {
            return new RunnerSelection(
                    SequentialWorkflowRunner.withBexEngine(
                            effective.bexEngine(),
                            effective.defaultComputeGasLimit(),
                            metrics,
                            effective.processingEventIdentityObserver()),
                    false);
        }
        return new RunnerSelection(
                SequentialWorkflowRunner.withLanguage(
                        currentLanguage,
                        effective.defaultComputeGasLimit(),
                        metrics,
                        effective.processingEventIdentityObserver(),
                        RepositoryIndependentCoordinationTypes
                                .workflowStepTypes()),
                true);
    }

    private void closeGeneration() {
        close(processor);
        processor = null;
        close(contracts);
        contracts = null;
        if (ownsWorkflowRunner) {
            close(workflowRunner);
        }
        workflowRunner = null;
        ownsWorkflowRunner = false;
        close(language);
        language = null;
        registry = null;
        mapping = null;
        typeClassResolver = null;
        nodeProvider = null;
        typeProvider = null;
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
                    "Could not close repository-independent test runtime",
                    failure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Repository-independent Coordination runtime is closed");
        }
    }

    private static final class RunnerSelection {
        private final SequentialWorkflowRunner runner;
        private final boolean owned;

        private RunnerSelection(
                SequentialWorkflowRunner runner,
                boolean owned) {
            this.runner = Objects.requireNonNull(runner, "runner");
            this.owned = owned;
        }
    }
}
