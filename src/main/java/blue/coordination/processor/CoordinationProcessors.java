package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.coordination.processor.merge.CoordinationMerging;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.Blue;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingMetricsSink;
import blue.repo.BlueRepositoryModels;
import blue.repo.coordination.TimelineChannel;

/**
 * Installs the complete fixed-repository Coordination processor set into a
 * Language runtime or a {@link DocumentProcessor.Builder}.
 *
 * <p>Registration reuses the generic Contracts engine for matching,
 * snapshots, patches, checkpoints, and atomic Root transitions. This facade
 * contributes only Coordination channels, handlers, and workflow
 * execution.</p>
 */
public final class CoordinationProcessors {
    private CoordinationProcessors() {
    }

    public static Blue registerWith(Blue blue) {
        return registerWith(blue, null);
    }

    public static Blue registerWith(Blue blue, CoordinationProcessorOptions options) {
        if (blue == null) {
            throw new IllegalArgumentException("blue must not be null");
        }
        BexProcessingMetrics metrics = processingMetrics(options);
        if (metrics != null) {
            installProcessingMetrics(blue.getDocumentProcessor(), metrics);
        }
        SequentialWorkflowRunner runner = workflowRunner(options);
        BlueRepositoryModels.registerAll(blue.getDocumentProcessor().getContractTypeResolver());
        blue.registerContractProcessor(new TimelineChannelProcessor());
        blue.registerContractProcessor(new AllTimelinesChannelProcessor());
        blue.registerContractProcessor(new CompositeTimelineChannelProcessor());
        blue.registerContractProcessor(new OperationProcessor());
        blue.registerContractProcessor(new ChatWorkflowOperationProcessor(runner));
        blue.registerContractProcessor(new SequentialWorkflowProcessor(runner));
        blue.registerContractProcessor(new SequentialWorkflowOperationProcessor(runner));
        CoordinationMerging.install(blue);
        return blue;
    }

    public static DocumentProcessor.Builder configure(DocumentProcessor.Builder builder) {
        return configure(builder, null);
    }

    /**
     * Adds Coordination models and processors to the supplied builder.
     *
     * <p>Required model mappings are registered into the resolver already
     * owned by the builder. A resolver installed by the host is therefore
     * preserved, while an incompatible duplicate mapping still fails
     * closed.</p>
     *
     * @param builder host-owned processor builder
     * @param options optional Coordination dependency overrides
     * @return the supplied builder
     */
    public static DocumentProcessor.Builder configure(DocumentProcessor.Builder builder,
                                                      CoordinationProcessorOptions options) {
        if (builder == null) {
            throw new IllegalArgumentException("builder must not be null");
        }
        BexProcessingMetrics metrics = processingMetrics(options);
        if (metrics != null) {
            builder.withProcessingMetricsSink(metrics);
        }
        SequentialWorkflowRunner runner = workflowRunner(options);
        return builder
                .scanContractTypes("blue.language.processor.model")
                .scanContractTypes("blue.repo")
                .registerContractProcessor(new TimelineChannelProcessor())
                .registerContractProcessor(new AllTimelinesChannelProcessor())
                .registerContractProcessor(new CompositeTimelineChannelProcessor())
                .registerContractProcessor(new OperationProcessor())
                .registerContractProcessor(new ChatWorkflowOperationProcessor(runner))
                .registerContractProcessor(new SequentialWorkflowProcessor(runner))
                .registerContractProcessor(new SequentialWorkflowOperationProcessor(runner));
    }

    /**
     * Explicitly registers one exact Timeline Channel subtype with the
     * standard finite Timeline subscription, acceptance, and checkpoint
     * semantics.
     *
     * <p>The configured provider remains responsible for supplying exact
     * canonical type evidence. Language's verified type matcher, rather than
     * this Java class relationship, decides whether content is semantically a
     * Timeline Channel subtype.</p>
     *
     * @param <T> exact Timeline Channel subtype model
     * @param blue configured Language runtime
     * @param contractType exact subtype model class
     * @return the supplied runtime
     */
    public static <T extends TimelineChannel> Blue
    registerTimelineSubtype(
            Blue blue,
            Class<T> contractType) {
        Blue exact = requireBlue(blue);
        exact.registerContractProcessor(
                new TimelineChannelSubtypeProcessor<T>(
                        contractType));
        return exact;
    }

    /**
     * Explicitly registers one exact Timeline Channel subtype on a processor
     * builder.
     *
     * @param <T> exact Timeline Channel subtype model
     * @param builder configured processor builder
     * @param contractType exact subtype model class
     * @return the supplied builder
     */
    public static <T extends TimelineChannel>
    DocumentProcessor.Builder registerTimelineSubtype(
            DocumentProcessor.Builder builder,
            Class<T> contractType) {
        DocumentProcessor.Builder exact =
                requireBuilder(builder);
        return exact.registerContractProcessor(
                new TimelineChannelSubtypeProcessor<T>(
                        contractType));
    }

    private static Blue requireBlue(Blue blue) {
        if (blue == null) {
            throw new IllegalArgumentException(
                    "blue must not be null");
        }
        return blue;
    }

    private static DocumentProcessor.Builder requireBuilder(
            DocumentProcessor.Builder builder) {
        if (builder == null) {
            throw new IllegalArgumentException(
                    "builder must not be null");
        }
        return builder;
    }

    private static BexProcessingMetrics processingMetrics(CoordinationProcessorOptions options) {
        return options != null ? options.processingMetrics() : null;
    }

    private static void installProcessingMetrics(DocumentProcessor processor,
                                                 BexProcessingMetrics coordinationMetrics) {
        ProcessingMetricsSink existing = processor.processingMetricsSink();
        if (existing == coordinationMetrics) {
            return;
        }
        if (existing == null || existing == ProcessingMetricsSink.NOOP) {
            processor.processingMetricsSink(coordinationMetrics);
            return;
        }
        processor.processingMetricsSink(new CompositeProcessingMetricsSink(
                existing, coordinationMetrics));
    }

    private static SequentialWorkflowRunner workflowRunner(CoordinationProcessorOptions options) {
        if (options != null && options.sequentialWorkflowRunner() != null) {
            return options.sequentialWorkflowRunner();
        }
        BexEngine bexEngine = options != null && options.bexEngine() != null
                ? options.bexEngine()
                : BexEngine.builder()
                        .intrinsics(CoordinationBexIntrinsics.common())
                        .build();
        return SequentialWorkflowRunner.withBexEngine(bexEngine,
                options != null ? options.defaultComputeGasLimit() : 100_000L,
                processingMetrics(options),
                options != null
                        ? options.processingEventIdentityObserver()
                        : null);
    }

    /** Static, allocation-free-per-sample fan-out for preserving an independently installed sink. */
    private static final class CompositeProcessingMetricsSink implements ProcessingMetricsSink {
        private final ProcessingMetricsSink first;
        private final ProcessingMetricsSink second;

        private CompositeProcessingMetricsSink(ProcessingMetricsSink first,
                                               ProcessingMetricsSink second) {
            this.first = first;
            this.second = second;
        }

        @Override public void addMetric(String name, long delta) { first.addMetric(name, delta); second.addMetric(name, delta); }
        @Override public void setMetric(String name, long value) { first.setMetric(name, value); second.setMetric(name, value); }
        @Override public void recordMetricHighWater(String name, long value) { first.recordMetricHighWater(name, value); second.recordMetricHighWater(name, value); }
        @Override public void addProcessDocumentNanos(long value) { first.addProcessDocumentNanos(value); second.addProcessDocumentNanos(value); }
        @Override public void addBlueProcessDocumentNanos(long value) { first.addBlueProcessDocumentNanos(value); second.addBlueProcessDocumentNanos(value); }
        @Override public void addEventPreprocessNanos(long value) { first.addEventPreprocessNanos(value); second.addEventPreprocessNanos(value); }
        @Override public void addResultSnapshotAttachNanos(long value) { first.addResultSnapshotAttachNanos(value); second.addResultSnapshotAttachNanos(value); }
        @Override public void addBlueIdCalculationNanos(long value) { first.addBlueIdCalculationNanos(value); second.addBlueIdCalculationNanos(value); }
        @Override public void addProcessingSnapshotCacheLookupNanos(long value) { first.addProcessingSnapshotCacheLookupNanos(value); second.addProcessingSnapshotCacheLookupNanos(value); }
        @Override public void incrementProcessingSnapshotCacheHits() { first.incrementProcessingSnapshotCacheHits(); second.incrementProcessingSnapshotCacheHits(); }
        @Override public void incrementProcessingSnapshotCacheMisses() { first.incrementProcessingSnapshotCacheMisses(); second.incrementProcessingSnapshotCacheMisses(); }
        @Override public void addProcessingSnapshotFromDocumentNanos(long value) { first.addProcessingSnapshotFromDocumentNanos(value); second.addProcessingSnapshotFromDocumentNanos(value); }
        @Override public void incrementProcessingSnapshotFromDocumentBuilds() { first.incrementProcessingSnapshotFromDocumentBuilds(); second.incrementProcessingSnapshotFromDocumentBuilds(); }
        @Override public void incrementProcessEventSnapshotAttempts() { first.incrementProcessEventSnapshotAttempts(); second.incrementProcessEventSnapshotAttempts(); }
        @Override public void incrementProcessEventSnapshotBuilds() { first.incrementProcessEventSnapshotBuilds(); second.incrementProcessEventSnapshotBuilds(); }
        @Override public void incrementProcessEventSnapshotFailures() { first.incrementProcessEventSnapshotFailures(); second.incrementProcessEventSnapshotFailures(); }
        @Override public void addProcessEventSnapshotConstructionNanos(long value) { first.addProcessEventSnapshotConstructionNanos(value); second.addProcessEventSnapshotConstructionNanos(value); }
        @Override public void addBundleLoadNanos(long value) { first.addBundleLoadNanos(value); second.addBundleLoadNanos(value); }
        @Override public void addBundleLoadCacheKeyBuildNanos(long value) { first.addBundleLoadCacheKeyBuildNanos(value); second.addBundleLoadCacheKeyBuildNanos(value); }
        @Override public void addBundleLoadActualBuildNanos(long value) { first.addBundleLoadActualBuildNanos(value); second.addBundleLoadActualBuildNanos(value); }
        @Override public void addBundleLoadReuseNanos(long value) { first.addBundleLoadReuseNanos(value); second.addBundleLoadReuseNanos(value); }
        @Override public void incrementBundleLoadCacheHits() { first.incrementBundleLoadCacheHits(); second.incrementBundleLoadCacheHits(); }
        @Override public void incrementBundleLoadCacheMisses() { first.incrementBundleLoadCacheMisses(); second.incrementBundleLoadCacheMisses(); }
        @Override public void incrementBundlesBuilt() { first.incrementBundlesBuilt(); second.incrementBundlesBuilt(); }
        @Override public void incrementBundlesReused() { first.incrementBundlesReused(); second.incrementBundlesReused(); }
        @Override public void incrementBundleScopeLoadAttempts() { first.incrementBundleScopeLoadAttempts(); second.incrementBundleScopeLoadAttempts(); }
        @Override public void incrementBundleScopeExecutionCacheHits() { first.incrementBundleScopeExecutionCacheHits(); second.incrementBundleScopeExecutionCacheHits(); }
        @Override public void incrementBundleScopeRefreshes() { first.incrementBundleScopeRefreshes(); second.incrementBundleScopeRefreshes(); }
        @Override public void addBundleScopeTerminationCheckNanos(long value) { first.addBundleScopeTerminationCheckNanos(value); second.addBundleScopeTerminationCheckNanos(value); }
        @Override public void addBundleScopeResolvedLookupNanos(long value) { first.addBundleScopeResolvedLookupNanos(value); second.addBundleScopeResolvedLookupNanos(value); }
        @Override public void addBundleScopeContractLoadNanos(long value) { first.addBundleScopeContractLoadNanos(value); second.addBundleScopeContractLoadNanos(value); }
        @Override public void addChannelDiscoveryNanos(long value) { first.addChannelDiscoveryNanos(value); second.addChannelDiscoveryNanos(value); }
        @Override public void addChannelMatchNanos(long value) { first.addChannelMatchNanos(value); second.addChannelMatchNanos(value); }
        @Override public void incrementChannelEvaluations() { first.incrementChannelEvaluations(); second.incrementChannelEvaluations(); }
        @Override public void incrementRoutedChannelDeliveries() { first.incrementRoutedChannelDeliveries(); second.incrementRoutedChannelDeliveries(); }
        @Override public void incrementDeduplicatedChannelDeliveries() { first.incrementDeduplicatedChannelDeliveries(); second.incrementDeduplicatedChannelDeliveries(); }
        @Override public void addHandlerDiscoveryNanos(long value) { first.addHandlerDiscoveryNanos(value); second.addHandlerDiscoveryNanos(value); }
        @Override public void addHandlerMatchNanos(long value) { first.addHandlerMatchNanos(value); second.addHandlerMatchNanos(value); }
        @Override public void incrementHandlerMatchAttempts() { first.incrementHandlerMatchAttempts(); second.incrementHandlerMatchAttempts(); }
        @Override public void addHandlerExecutionNanos(long value) { first.addHandlerExecutionNanos(value); second.addHandlerExecutionNanos(value); }
        @Override public void incrementHandlersExecuted() { first.incrementHandlersExecuted(); second.incrementHandlersExecuted(); }
        @Override public void addTriggeredEventRoutingNanos(long value) { first.addTriggeredEventRoutingNanos(value); second.addTriggeredEventRoutingNanos(value); }
        @Override public void incrementTriggeredEventsRouted() { first.incrementTriggeredEventsRouted(); second.incrementTriggeredEventsRouted(); }
        @Override public void addCheckpointUpdateNanos(long value) { first.addCheckpointUpdateNanos(value); second.addCheckpointUpdateNanos(value); }
        @Override public void addCheckpointEnsureNanos(long value) { first.addCheckpointEnsureNanos(value); second.addCheckpointEnsureNanos(value); }
        @Override public void addCheckpointFindNanos(long value) { first.addCheckpointFindNanos(value); second.addCheckpointFindNanos(value); }
        @Override public void addCheckpointCurrentIdentityNanos(long value) { first.addCheckpointCurrentIdentityNanos(value); second.addCheckpointCurrentIdentityNanos(value); }
        @Override public void addCheckpointIsNewerNanos(long value) { first.addCheckpointIsNewerNanos(value); second.addCheckpointIsNewerNanos(value); }
        @Override public void addCheckpointDuplicateNanos(long value) { first.addCheckpointDuplicateNanos(value); second.addCheckpointDuplicateNanos(value); }
        @Override public void addCheckpointPersistNanos(long value) { first.addCheckpointPersistNanos(value); second.addCheckpointPersistNanos(value); }
        @Override public void incrementCheckpointIdentityCacheHits() { first.incrementCheckpointIdentityCacheHits(); second.incrementCheckpointIdentityCacheHits(); }
        @Override public void incrementCheckpointIdentityCacheMisses() { first.incrementCheckpointIdentityCacheMisses(); second.incrementCheckpointIdentityCacheMisses(); }
        @Override public void incrementCheckpointStoredIdentityCacheHits() { first.incrementCheckpointStoredIdentityCacheHits(); second.incrementCheckpointStoredIdentityCacheHits(); }
        @Override public void incrementCheckpointStoredIdentityCacheMisses() { first.incrementCheckpointStoredIdentityCacheMisses(); second.incrementCheckpointStoredIdentityCacheMisses(); }
        @Override public void addCheckpointDirectBlueIdNanos(long value) { first.addCheckpointDirectBlueIdNanos(value); second.addCheckpointDirectBlueIdNanos(value); }
        @Override public void addCheckpointContentBlueIdNanos(long value) { first.addCheckpointContentBlueIdNanos(value); second.addCheckpointContentBlueIdNanos(value); }
        @Override public void addCheckpointFallbackNanos(long value) { first.addCheckpointFallbackNanos(value); second.addCheckpointFallbackNanos(value); }
        @Override public void addSnapshotCommitNanos(long value) { first.addSnapshotCommitNanos(value); second.addSnapshotCommitNanos(value); }
        @Override public void addPostProcessingNanos(long value) { first.addPostProcessingNanos(value); second.addPostProcessingNanos(value); }
        @Override public void addPatchBoundaryNanos(long value) { first.addPatchBoundaryNanos(value); second.addPatchBoundaryNanos(value); }
        @Override public void addPatchGasNanos(long value) { first.addPatchGasNanos(value); second.addPatchGasNanos(value); }
        @Override public void addDocumentUpdateRoutingNanos(long value) { first.addDocumentUpdateRoutingNanos(value); second.addDocumentUpdateRoutingNanos(value); }
        @Override public void incrementDocumentUpdateEventsBuilt() { first.incrementDocumentUpdateEventsBuilt(); second.incrementDocumentUpdateEventsBuilt(); }
        @Override public void incrementDocumentUpdateEventsSkippedNoChannel() { first.incrementDocumentUpdateEventsSkippedNoChannel(); second.incrementDocumentUpdateEventsSkippedNoChannel(); }
        @Override public void addBatchPatchPlanningNanos(long value) { first.addBatchPatchPlanningNanos(value); second.addBatchPatchPlanningNanos(value); }
        @Override public void addBatchPatchConformanceNanos(long value) { first.addBatchPatchConformanceNanos(value); second.addBatchPatchConformanceNanos(value); }
        @Override public void addBatchPatchBuildUpdatesNanos(long value) { first.addBatchPatchBuildUpdatesNanos(value); second.addBatchPatchBuildUpdatesNanos(value); }
        @Override public void addBatchPatchCommitNanos(long value) { first.addBatchPatchCommitNanos(value); second.addBatchPatchCommitNanos(value); }
        @Override public void incrementDocumentUpdateBeforeMaterializations() { first.incrementDocumentUpdateBeforeMaterializations(); second.incrementDocumentUpdateBeforeMaterializations(); }
        @Override public void incrementDocumentUpdateAfterMaterializations() { first.incrementDocumentUpdateAfterMaterializations(); second.incrementDocumentUpdateAfterMaterializations(); }
        @Override public void incrementPatchSequencesPrepared() { first.incrementPatchSequencesPrepared(); second.incrementPatchSequencesPrepared(); }
        @Override public void addPatchesPrepared(long value) { first.addPatchesPrepared(value); second.addPatchesPrepared(value); }
        @Override public void incrementSingletonPatchTransactions() { first.incrementSingletonPatchTransactions(); second.incrementSingletonPatchTransactions(); }
        @Override public void addSequencePlanningNanos(long value) { first.addSequencePlanningNanos(value); second.addSequencePlanningNanos(value); }
        @Override public void addSequenceConformanceNanos(long value) { first.addSequenceConformanceNanos(value); second.addSequenceConformanceNanos(value); }
        @Override public void addSequenceCommitNanos(long value) { first.addSequenceCommitNanos(value); second.addSequenceCommitNanos(value); }
        @Override public void addSequenceFinalCacheCommitNanos(long value) { first.addSequenceFinalCacheCommitNanos(value); second.addSequenceFinalCacheCommitNanos(value); }
        @Override public void incrementSequenceIntermediateSnapshotAdvances() { first.incrementSequenceIntermediateSnapshotAdvances(); second.incrementSequenceIntermediateSnapshotAdvances(); }
        @Override public void incrementSequenceSharedSnapshotCacheInserts() { first.incrementSequenceSharedSnapshotCacheInserts(); second.incrementSequenceSharedSnapshotCacheInserts(); }
        @Override public void incrementSequenceFinalSnapshotCacheInserts() { first.incrementSequenceFinalSnapshotCacheInserts(); second.incrementSequenceFinalSnapshotCacheInserts(); }
        @Override public void incrementSequenceSuffixRebases() { first.incrementSequenceSuffixRebases(); second.incrementSequenceSuffixRebases(); }
        @Override public void incrementSequenceStalePreviewFallbacks() { first.incrementSequenceStalePreviewFallbacks(); second.incrementSequenceStalePreviewFallbacks(); }
        @Override public void incrementSequenceFallbackPatches() { first.incrementSequenceFallbackPatches(); second.incrementSequenceFallbackPatches(); }
        @Override public void incrementParsedPointerCacheHits() { first.incrementParsedPointerCacheHits(); second.incrementParsedPointerCacheHits(); }
        @Override public void incrementParsedPointerCacheMisses() { first.incrementParsedPointerCacheMisses(); second.incrementParsedPointerCacheMisses(); }
        @Override public void incrementFrozenPatchValueHits() { first.incrementFrozenPatchValueHits(); second.incrementFrozenPatchValueHits(); }
        @Override public void incrementPatchValueMaterializations() { first.incrementPatchValueMaterializations(); second.incrementPatchValueMaterializations(); }
        @Override public void incrementFrozenNodesCreated() { first.incrementFrozenNodesCreated(); second.incrementFrozenNodesCreated(); }
        @Override public void incrementFrozenNodesReused() { first.incrementFrozenNodesReused(); second.incrementFrozenNodesReused(); }
        @Override public void incrementCanonicalIdentityCalculations() { first.incrementCanonicalIdentityCalculations(); second.incrementCanonicalIdentityCalculations(); }
        @Override public void incrementResolvedIdentityCalculations() { first.incrementResolvedIdentityCalculations(); second.incrementResolvedIdentityCalculations(); }
        @Override public void addCanonicalBytesWritten(long value) { first.addCanonicalBytesWritten(value); second.addCanonicalBytesWritten(value); }
        @Override public void incrementJcsFallbacks() { first.incrementJcsFallbacks(); second.incrementJcsFallbacks(); }
        @Override public void addBase58EncodeNanos(long value) { first.addBase58EncodeNanos(value); second.addBase58EncodeNanos(value); }
        @Override public void addBase58DecodeNanos(long value) { first.addBase58DecodeNanos(value); second.addBase58DecodeNanos(value); }
        @Override public void addBlueIdDigestNanos(long value) { first.addBlueIdDigestNanos(value); second.addBlueIdDigestNanos(value); }
        @Override public void incrementResolvedStructuralKeyBuilds() { first.incrementResolvedStructuralKeyBuilds(); second.incrementResolvedStructuralKeyBuilds(); }
    }
}
