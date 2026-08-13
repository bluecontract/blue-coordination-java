package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.model.MarkerContract;
import blue.language.runtime.BlueLanguage;
import blue.repo.coordination.ActorPolicy;
import blue.repo.coordination.ComputeDefinition;
import blue.repo.coordination.DocumentAnchors;
import blue.repo.coordination.DocumentLinks;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.SearchContract;
import blue.repo.workflows.ContractsChangePolicy;
import blue.repo.workflows.DocumentSection;

import java.util.Objects;

/**
 * Registers the fixed-repository Coordination processors with the current
 * immutable Contracts configuration APIs.
 *
 * <p>The facade does not own or mutate a Language runtime. Applications build
 * one {@link BlueLanguage}, supply it through
 * {@link CoordinationProcessorOptions.Builder#language(BlueLanguage)}, and
 * use the configured registry with {@code BlueContracts}. Standalone
 * {@link DocumentProcessor.Builder} composition remains available for focused
 * tests and tools.</p>
 */
public final class CoordinationProcessors {
    private CoordinationProcessors() {
    }

    /**
     * Builds one focused Contracts service containing the Coordination
     * processors and borrowing the supplied Language runtime.
     *
     * <p>The returned service owns its Contracts processor generation but
     * does not own {@code language}. Hosts should close the returned service
     * before closing the borrowed Language runtime.</p>
     *
     * @param language exact Language runtime shared with hosted BEX
     * @return independently owned Coordination Contracts service
     */
    public static BlueContracts contracts(BlueLanguage language) {
        return contracts(language, null);
    }

    /**
     * Builds one focused Contracts service containing the Coordination
     * processors and optional hosted-runtime configuration.
     *
     * @param language exact Language runtime shared with hosted BEX
     * @param options optional Coordination processor configuration
     * @return independently owned Coordination Contracts service
     */
    public static BlueContracts contracts(
            BlueLanguage language,
            CoordinationProcessorOptions options) {
        BlueLanguage exactLanguage = requireLanguage(language);
        CoordinationProcessorOptions effective = optionsWithLanguage(
                exactLanguage, options);
        ContractProcessorRegistryBuilder registry = configure(
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults(),
                effective);
        BlueContracts.Builder builder = BlueContracts.builder(
                        exactLanguage.processing())
                .runtimeRegistry(registry.build());
        BexProcessingMetrics metrics = processingMetrics(effective);
        if (metrics != null) {
            builder.observer(metrics);
        }
        return builder.build();
    }

    /** Adds Coordination processors to a standalone processor builder. */
    public static DocumentProcessor.Builder configure(
            DocumentProcessor.Builder builder) {
        return configure(builder, null);
    }

    /**
     * Adds repository model mappings, processors, and the optional observer to
     * a host-owned immutable processor builder.
     */
    public static DocumentProcessor.Builder configure(
            DocumentProcessor.Builder builder,
            CoordinationProcessorOptions options) {
        if (builder == null) {
            throw new IllegalArgumentException("builder must not be null");
        }
        BexProcessingMetrics metrics = processingMetrics(options);
        if (metrics != null) {
            builder.observer(metrics);
        }
        SequentialWorkflowRunner runner = workflowRunner(options);
        CoordinationSemanticTypeIdentities identities =
                semanticTypeIdentities(options);
        CoordinationCurrentRepositoryIdentities current =
                CoordinationCurrentRepositoryIdentities.current();
        DocumentProcessor.Builder configured = builder
                .scanContractTypes("blue.language.processor.model")
                .scanContractTypes("blue.repo")
                .registerContractProcessor(
                        new TimelineChannelProcessor(identities))
                .registerContractProcessor(
                        new AllTimelinesChannelProcessor(
                                current.timelineChannelBlueId(),
                                identities))
                .registerContractProcessor(
                        new CompositeTimelineChannelProcessor(
                                current.timelineChannelBlueId(),
                                identities))
                .registerContractProcessor(new OperationProcessor())
                .registerContractProcessor(new ChatWorkflowOperationProcessor(runner))
                .registerContractProcessor(
                        new SequentialWorkflowProcessor(
                                runner, identities))
                .registerContractProcessor(
                        new SequentialWorkflowOperationProcessor(
                                runner, identities));
        return registerCurrentRepositoryMarkers(configured);
    }

    /** Adds Coordination processors to the registry used by BlueContracts. */
    public static ContractProcessorRegistryBuilder configure(
            ContractProcessorRegistryBuilder registry) {
        return configure(registry, null);
    }

    /** Adds Coordination processors to the registry used by BlueContracts. */
    public static ContractProcessorRegistryBuilder configure(
            ContractProcessorRegistryBuilder registry,
            CoordinationProcessorOptions options) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        SequentialWorkflowRunner runner = workflowRunner(options);
        CoordinationSemanticTypeIdentities identities =
                semanticTypeIdentities(options);
        CoordinationCurrentRepositoryIdentities current =
                CoordinationCurrentRepositoryIdentities.current();
        ContractProcessorRegistryBuilder configured = registry
                .register(new TimelineChannelProcessor(identities))
                .register(new AllTimelinesChannelProcessor(
                        current.timelineChannelBlueId(),
                        identities))
                .register(new CompositeTimelineChannelProcessor(
                        current.timelineChannelBlueId(),
                        identities))
                .register(new OperationProcessor())
                .register(new ChatWorkflowOperationProcessor(runner))
                .register(new SequentialWorkflowProcessor(
                        runner, identities))
                .register(new SequentialWorkflowOperationProcessor(
                        runner, identities));
        return registerCurrentRepositoryMarkers(configured);
    }

    /** Registers one exact Timeline Channel subtype on a processor builder. */
    public static <T extends TimelineChannel>
    DocumentProcessor.Builder registerTimelineSubtype(
            DocumentProcessor.Builder builder,
            Class<T> contractType) {
        if (builder == null) {
            throw new IllegalArgumentException("builder must not be null");
        }
        return builder.registerContractProcessor(
                new TimelineChannelSubtypeProcessor<T>(contractType));
    }

    /** Registers one exact Timeline Channel subtype in a Contracts registry. */
    public static <T extends TimelineChannel>
    ContractProcessorRegistryBuilder registerTimelineSubtype(
            ContractProcessorRegistryBuilder registry,
            Class<T> contractType) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        return registry.register(
                new TimelineChannelSubtypeProcessor<T>(contractType));
    }

    /**
     * Returns the immutable identity of the Coordination registrations that
     * are actually installed in the supplied processor generation.
     *
     * @param processor exact processor generation to inspect
     * @return deterministic Coordination runtime-registration identity
     */
    public static String runtimeRegistrationIdentity(
            DocumentProcessor processor) {
        return CoordinationRuntimeRegistrations.identity(
                Objects.requireNonNull(processor, "processor"));
    }

    /**
     * Returns a failure-isolated observer fan-out. Observer failures never
     * enter semantic execution.
     */
    public static ProcessingObserver observers(
            final ProcessingObserver first,
            final ProcessingObserver second) {
        if (first == null) {
            return second;
        }
        if (second == null || first == second) {
            return first;
        }
        return observation -> {
            try {
                first.record(observation);
            } catch (RuntimeException ignored) {
                // Operational diagnostics are deliberately failure-isolated.
            }
            try {
                second.record(observation);
            } catch (RuntimeException ignored) {
                // Operational diagnostics are deliberately failure-isolated.
            }
        };
    }

    private static BexProcessingMetrics processingMetrics(
            CoordinationProcessorOptions options) {
        return options != null ? options.processingMetrics() : null;
    }

    private static BlueLanguage requireLanguage(BlueLanguage language) {
        if (language == null) {
            throw new IllegalArgumentException("language must not be null");
        }
        return language;
    }

    private static CoordinationProcessorOptions optionsWithLanguage(
            BlueLanguage language,
            CoordinationProcessorOptions options) {
        if (options == null) {
            return CoordinationProcessorOptions.builder()
                    .language(language)
                    .build();
        }
        if (options.sequentialWorkflowRunner() != null
                || options.bexEngine() != null) {
            return options;
        }
        return CoordinationProcessorOptions.builder()
                .language(language)
                .defaultComputeGasLimit(options.defaultComputeGasLimit())
                .processingMetrics(options.processingMetrics())
                .processingEventIdentityObserver(
                        options.processingEventIdentityObserver())
                .semanticTypeIdentities(
                        options.semanticTypeIdentities())
                .build();
    }

    private static SequentialWorkflowRunner workflowRunner(
            CoordinationProcessorOptions options) {
        if (options != null && options.sequentialWorkflowRunner() != null) {
            return options.sequentialWorkflowRunner();
        }
        if (options != null && options.bexEngine() != null) {
            return SequentialWorkflowRunner.withBexEngine(
                    options.bexEngine(),
                    options.defaultComputeGasLimit(),
                    processingMetrics(options),
                    options.processingEventIdentityObserver());
        }
        if (options != null && options.language() != null) {
            return SequentialWorkflowRunner.withLanguage(
                    options.language(),
                    options.defaultComputeGasLimit(),
                    processingMetrics(options),
                    options.processingEventIdentityObserver());
        }
        BexEngine engine = BexEngine.builder()
                .intrinsics(CoordinationBexIntrinsics.common())
                .build();
        return SequentialWorkflowRunner.withBexEngine(
                engine,
                options != null ? options.defaultComputeGasLimit() : 100_000L,
                processingMetrics(options),
                options != null
                        ? options.processingEventIdentityObserver()
                        : null);
    }

    private static CoordinationSemanticTypeIdentities
    semanticTypeIdentities(CoordinationProcessorOptions options) {
        return options != null
                ? options.semanticTypeIdentities()
                : CoordinationSemanticTypeIdentities.publishedDefaults();
    }

    private static DocumentProcessor.Builder registerCurrentRepositoryMarkers(
            DocumentProcessor.Builder builder) {
        DocumentProcessor.Builder configured = builder;
        for (Class<? extends MarkerContract> marker
                : currentRepositoryMarkerTypes()) {
            configured = registerMarker(configured, marker);
        }
        return configured;
    }

    private static ContractProcessorRegistryBuilder
    registerCurrentRepositoryMarkers(
            ContractProcessorRegistryBuilder registry) {
        ContractProcessorRegistryBuilder configured = registry;
        for (Class<? extends MarkerContract> marker
                : currentRepositoryMarkerTypes()) {
            configured = registerMarker(configured, marker);
        }
        return configured;
    }

    private static Class<? extends MarkerContract>[]
    currentRepositoryMarkerTypes() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends MarkerContract>[] result = new Class[] {
                ActorPolicy.class,
                ComputeDefinition.class,
                DocumentAnchors.class,
                DocumentLinks.class,
                SearchContract.class,
                ContractsChangePolicy.class,
                DocumentSection.class
        };
        return result;
    }

    private static <T extends MarkerContract>
    DocumentProcessor.Builder registerMarker(
            DocumentProcessor.Builder builder,
            Class<T> marker) {
        return builder.registerContractProcessor(
                new CurrentRepositoryMarkerProcessor<T>(marker));
    }

    private static <T extends MarkerContract>
    ContractProcessorRegistryBuilder registerMarker(
            ContractProcessorRegistryBuilder registry,
            Class<T> marker) {
        return registry.register(
                new CurrentRepositoryMarkerProcessor<T>(marker));
    }
}
