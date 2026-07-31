package blue.coordination.processor.bex;

import blue.bex.result.BexMetrics;
import blue.language.processor.ProcessingMetricsSink;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Coordination and hosted-BEX metrics sink.
 *
 * <p>All metric names are bounded and snapshots are immutable, so optional
 * observability cannot alter workflow semantics or portable gas.</p>
 */
public final class BexProcessingMetrics implements ProcessingMetricsSink {
    /**
     * Language currently emits a fixed vocabulary, but keep the adapter safe if a future
     * integration accidentally supplies data-derived names.
     */
    public static final int MAX_LANGUAGE_METRIC_NAMES = 256;

    private final Object languageMetricNameLock = new Object();
    private final ConcurrentMap<String, Boolean> languageMetricNames =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> languageCounters =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> languageGauges =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> languageHighWaterMarks =
            new ConcurrentHashMap<>();
    private final AtomicLong droppedLanguageMetricNames = new AtomicLong();

    private final AtomicLong workflowStepsExecuted = new AtomicLong();
    private final AtomicLong workflowPlansBuilt = new AtomicLong();
    private final AtomicLong workflowPlanCacheHits = new AtomicLong();
    private final AtomicLong workflowPlanCacheMisses = new AtomicLong();
    private final AtomicLong workflowPlanCacheEvictions = new AtomicLong();
    private final AtomicLong workflowPlanWeightBytes = new AtomicLong();
    private final AtomicLong workflowExecutorLookups = new AtomicLong();
    private final AtomicLong workflowStepResultSnapshotsCreated = new AtomicLong();
    private final AtomicLong workflowStepResultViewHits = new AtomicLong();
    private final AtomicLong computeStepsExecuted = new AtomicLong();
    private final AtomicLong computePlansBuilt = new AtomicLong();
    private final AtomicLong computePlanCacheHits = new AtomicLong();
    private final AtomicLong computePlanCacheMisses = new AtomicLong();
    private final AtomicLong computePlanCacheEvictions = new AtomicLong();
    private final AtomicLong computePlanWeightBytes = new AtomicLong();
    private final AtomicLong updateDocumentStepsExecuted = new AtomicLong();
    private final AtomicLong triggerEventStepsExecuted = new AtomicLong();
    private final AtomicLong directBexChangesetHits = new AtomicLong();
    private final AtomicLong bexSyntheticProgramMaterializations = new AtomicLong();
    private final AtomicLong patchesApplied = new AtomicLong();
    private final AtomicLong eventsEmitted = new AtomicLong();
    private final AtomicLong successfulComputeTerminationRequests = new AtomicLong();
    private final AtomicLong declarativeTerminationSteps = new AtomicLong();
    private final AtomicLong computeResultValidationFailures = new AtomicLong();
    private final AtomicLong computeProgramNormalizations = new AtomicLong();
    private final AtomicLong computeDefinitionNormalizations = new AtomicLong();
    private final AtomicLong computeDefinitionMaterializations = new AtomicLong();
    private final AtomicLong computeDefinitionFrozenDirectHits = new AtomicLong();
    private final AtomicLong computeProgramSourceBuilds = new AtomicLong();
    private final AtomicLong computeDefinitionResolveHits = new AtomicLong();
    private final AtomicLong computeDefinitionResolveMisses = new AtomicLong();
    private final AtomicLong workflowRunnerNanos = new AtomicLong();
    private final AtomicLong computeStepNanos = new AtomicLong();
    private final AtomicLong computeDefinitionResolveNanos = new AtomicLong();
    private final AtomicLong computeContextBuildNanos = new AtomicLong();
    private final AtomicLong computeProgramSourceBuildNanos = new AtomicLong();
    private final AtomicLong computeCompileExecuteNanos = new AtomicLong();
    private final AtomicLong updateStepNanos = new AtomicLong();
    private final AtomicLong updateDirectChangesetNanos = new AtomicLong();
    private final AtomicLong updatePatchConversionNanos = new AtomicLong();
    private final AtomicLong updatePatchApplyNanos = new AtomicLong();
    private final AtomicLong updateBatchPatchApplications = new AtomicLong();
    private final AtomicLong updateIndividualPatchApplications = new AtomicLong();
    private final AtomicLong updateStaticTemplatesBuilt = new AtomicLong();
    private final AtomicLong updateStaticTemplateHits = new AtomicLong();
    private final AtomicLong updateReflectionFallbacks = new AtomicLong();
    private final AtomicLong triggerStepNanos = new AtomicLong();
    private final AtomicLong triggerEmitEventNanos = new AtomicLong();
    private final AtomicLong bexCompileNanos = new AtomicLong();
    private final AtomicLong bexExecuteNanos = new AtomicLong();
    private final AtomicLong bexCompileCacheHits = new AtomicLong();
    private final AtomicLong bexCompileCacheMisses = new AtomicLong();
    private final AtomicLong bexCompiledExecutions = new AtomicLong();
    private final AtomicLong bexNodeWriterNanos = new AtomicLong();
    private final AtomicLong directBexPatchEntryConversions = new AtomicLong();
    private final AtomicLong bexPatchFrozenDirectConversions = new AtomicLong();
    private final AtomicLong bexPatchNodeMaterializations = new AtomicLong();
    private final AtomicLong processDocumentNanos = new AtomicLong();
    private final AtomicLong blueProcessDocumentNanos = new AtomicLong();
    private final AtomicLong eventPreprocessNanos = new AtomicLong();
    private final AtomicLong resultSnapshotAttachNanos = new AtomicLong();
    private final AtomicLong blueIdCalculationNanos = new AtomicLong();
    private final AtomicLong processingSnapshotCacheLookupNanos = new AtomicLong();
    private final AtomicLong processingSnapshotCacheHits = new AtomicLong();
    private final AtomicLong processingSnapshotCacheMisses = new AtomicLong();
    private final AtomicLong processingSnapshotFromDocumentNanos = new AtomicLong();
    private final AtomicLong processingSnapshotFromDocumentBuilds = new AtomicLong();
    private final AtomicLong processEventSnapshotAttempts = new AtomicLong();
    private final AtomicLong processEventSnapshotBuilds = new AtomicLong();
    private final AtomicLong processEventSnapshotFailures = new AtomicLong();
    private final AtomicLong processEventSnapshotConstructionNanos = new AtomicLong();
    private final AtomicLong bundleLoadNanos = new AtomicLong();
    private final AtomicLong bundleLoadCacheKeyBuildNanos = new AtomicLong();
    private final AtomicLong bundleLoadActualBuildNanos = new AtomicLong();
    private final AtomicLong bundleLoadReuseNanos = new AtomicLong();
    private final AtomicLong bundleLoadCacheHits = new AtomicLong();
    private final AtomicLong bundleLoadCacheMisses = new AtomicLong();
    private final AtomicLong bundlesBuilt = new AtomicLong();
    private final AtomicLong bundlesReused = new AtomicLong();
    private final AtomicLong bundleScopeLoadAttempts = new AtomicLong();
    private final AtomicLong bundleScopeExecutionCacheHits = new AtomicLong();
    private final AtomicLong bundleScopeRefreshes = new AtomicLong();
    private final AtomicLong bundleScopeTerminationCheckNanos = new AtomicLong();
    private final AtomicLong bundleScopeResolvedLookupNanos = new AtomicLong();
    private final AtomicLong bundleScopeContractLoadNanos = new AtomicLong();
    private final AtomicLong channelDiscoveryNanos = new AtomicLong();
    private final AtomicLong channelMatchNanos = new AtomicLong();
    private final AtomicLong channelEvaluations = new AtomicLong();
    private final AtomicLong handlerDiscoveryNanos = new AtomicLong();
    private final AtomicLong handlerMatchNanos = new AtomicLong();
    private final AtomicLong handlerMatchAttempts = new AtomicLong();
    private final AtomicLong handlerExecutionNanos = new AtomicLong();
    private final AtomicLong handlersExecuted = new AtomicLong();
    private final AtomicLong triggeredEventRoutingNanos = new AtomicLong();
    private final AtomicLong triggeredEventsRouted = new AtomicLong();
    private final AtomicLong checkpointUpdateNanos = new AtomicLong();
    private final AtomicLong checkpointEnsureNanos = new AtomicLong();
    private final AtomicLong checkpointFindNanos = new AtomicLong();
    private final AtomicLong checkpointCurrentIdentityNanos = new AtomicLong();
    private final AtomicLong checkpointIsNewerNanos = new AtomicLong();
    private final AtomicLong checkpointDuplicateNanos = new AtomicLong();
    private final AtomicLong checkpointPersistNanos = new AtomicLong();
    private final AtomicLong checkpointIdentityCacheHits = new AtomicLong();
    private final AtomicLong checkpointIdentityCacheMisses = new AtomicLong();
    private final AtomicLong checkpointStoredIdentityCacheHits = new AtomicLong();
    private final AtomicLong checkpointStoredIdentityCacheMisses = new AtomicLong();
    private final AtomicLong checkpointDirectBlueIdNanos = new AtomicLong();
    private final AtomicLong checkpointContentBlueIdNanos = new AtomicLong();
    private final AtomicLong checkpointFallbackNanos = new AtomicLong();
    private final AtomicLong snapshotCommitNanos = new AtomicLong();
    private final AtomicLong postProcessingNanos = new AtomicLong();
    private final AtomicLong patchBoundaryNanos = new AtomicLong();
    private final AtomicLong patchGasNanos = new AtomicLong();
    private final AtomicLong documentUpdateRoutingNanos = new AtomicLong();
    private final AtomicLong documentUpdateEventsBuilt = new AtomicLong();
    private final AtomicLong documentUpdateEventsSkippedNoChannel = new AtomicLong();
    private final AtomicLong batchPatchPlanningNanos = new AtomicLong();
    private final AtomicLong batchPatchConformanceNanos = new AtomicLong();
    private final AtomicLong batchPatchBuildUpdatesNanos = new AtomicLong();
    private final AtomicLong batchPatchCommitNanos = new AtomicLong();
    private final AtomicLong documentUpdateBeforeMaterializations = new AtomicLong();
    private final AtomicLong documentUpdateAfterMaterializations = new AtomicLong();
    private final AtomicLong patchSequencesPrepared = new AtomicLong();
    private final AtomicLong patchesPrepared = new AtomicLong();
    private final AtomicLong singletonPatchTransactions = new AtomicLong();
    private final AtomicLong sequencePlanningNanos = new AtomicLong();
    private final AtomicLong sequenceConformanceNanos = new AtomicLong();
    private final AtomicLong sequenceCommitNanos = new AtomicLong();
    private final AtomicLong sequenceFinalCacheCommitNanos = new AtomicLong();
    private final AtomicLong sequenceIntermediateSnapshotAdvances = new AtomicLong();
    private final AtomicLong sequenceSharedSnapshotCacheInserts = new AtomicLong();
    private final AtomicLong sequenceFinalSnapshotCacheInserts = new AtomicLong();
    private final AtomicLong sequenceSuffixRebases = new AtomicLong();
    private final AtomicLong sequenceStalePreviewFallbacks = new AtomicLong();
    private final AtomicLong sequenceFallbackPatches = new AtomicLong();
    private final AtomicLong parsedPointerCacheHits = new AtomicLong();
    private final AtomicLong parsedPointerCacheMisses = new AtomicLong();
    private final AtomicLong frozenPatchValueHits = new AtomicLong();
    private final AtomicLong patchValueMaterializations = new AtomicLong();
    private final AtomicLong workflowDocumentViewsFromFrozen = new AtomicLong();
    private final AtomicLong workflowDocumentViewsFromDocument = new AtomicLong();
    private final AtomicLong workflowDocumentViewMisses = new AtomicLong();
    private final AtomicLong bexDocumentViewMaterializedHits = new AtomicLong();
    private final AtomicLong bexDocumentViewFrozenDirectHits = new AtomicLong();
    private final AtomicLong bexDocumentViewFrozenRootFallbackHits = new AtomicLong();
    private final AtomicLong bexDocumentViewUndefinedHits = new AtomicLong();

    public void incrementWorkflowStepsExecuted() {
        workflowStepsExecuted.incrementAndGet();
    }

    public void incrementWorkflowPlansBuilt() {
        workflowPlansBuilt.incrementAndGet();
    }

    public void incrementWorkflowPlanCacheHits() {
        workflowPlanCacheHits.incrementAndGet();
    }

    public void incrementWorkflowPlanCacheMisses() {
        workflowPlanCacheMisses.incrementAndGet();
    }

    public void incrementWorkflowPlanCacheEvictions() {
        workflowPlanCacheEvictions.incrementAndGet();
    }

    public void addWorkflowPlanWeightBytes(long delta) {
        addToNonNegativeGauge(workflowPlanWeightBytes, delta);
    }

    public void incrementWorkflowExecutorLookups() {
        workflowExecutorLookups.incrementAndGet();
    }

    public void incrementWorkflowStepResultSnapshotsCreated() {
        workflowStepResultSnapshotsCreated.incrementAndGet();
    }

    public void incrementWorkflowStepResultViewHits() {
        workflowStepResultViewHits.incrementAndGet();
    }

    public void incrementComputeStepsExecuted() {
        computeStepsExecuted.incrementAndGet();
    }

    public void incrementComputePlansBuilt() {
        computePlansBuilt.incrementAndGet();
    }

    public void incrementComputePlanCacheHits() {
        computePlanCacheHits.incrementAndGet();
    }

    public void incrementComputePlanCacheMisses() {
        computePlanCacheMisses.incrementAndGet();
    }

    public void incrementComputePlanCacheEvictions() {
        computePlanCacheEvictions.incrementAndGet();
    }

    public void addComputePlanWeightBytes(long delta) {
        addToNonNegativeGauge(computePlanWeightBytes, delta);
    }

    public void incrementUpdateDocumentStepsExecuted() {
        updateDocumentStepsExecuted.incrementAndGet();
    }

    public void incrementTriggerEventStepsExecuted() {
        triggerEventStepsExecuted.incrementAndGet();
    }

    public void incrementDirectBexChangesetHits() {
        directBexChangesetHits.incrementAndGet();
    }

    public void incrementBexSyntheticProgramMaterializations() {
        bexSyntheticProgramMaterializations.incrementAndGet();
    }

    public void addPatchesApplied(long count) {
        patchesApplied.addAndGet(count);
    }

    public void incrementEventsEmitted() {
        eventsEmitted.incrementAndGet();
    }

    public void incrementSuccessfulComputeTerminationRequests() {
        successfulComputeTerminationRequests.incrementAndGet();
    }

    public void incrementDeclarativeTerminationSteps() {
        declarativeTerminationSteps.incrementAndGet();
    }

    public void incrementComputeResultValidationFailures() {
        computeResultValidationFailures.incrementAndGet();
    }

    public void incrementComputeProgramNormalizations() {
        computeProgramNormalizations.incrementAndGet();
    }

    public void incrementComputeDefinitionNormalizations() {
        computeDefinitionNormalizations.incrementAndGet();
    }

    public void incrementComputeDefinitionMaterializations() {
        computeDefinitionMaterializations.incrementAndGet();
    }

    public void incrementComputeDefinitionFrozenDirectHits() {
        computeDefinitionFrozenDirectHits.incrementAndGet();
    }

    public void incrementComputeProgramSourceBuilds() {
        computeProgramSourceBuilds.incrementAndGet();
    }

    public void incrementComputeDefinitionResolveHits() {
        computeDefinitionResolveHits.incrementAndGet();
    }

    public void incrementComputeDefinitionResolveMisses() {
        computeDefinitionResolveMisses.incrementAndGet();
    }

    public void addWorkflowRunnerNanos(long nanos) {
        workflowRunnerNanos.addAndGet(nonNegative(nanos));
    }

    public void addComputeStepNanos(long nanos) {
        computeStepNanos.addAndGet(nonNegative(nanos));
    }

    public void addComputeDefinitionResolveNanos(long nanos) {
        computeDefinitionResolveNanos.addAndGet(nonNegative(nanos));
    }

    public void addComputeContextBuildNanos(long nanos) {
        computeContextBuildNanos.addAndGet(nonNegative(nanos));
    }

    public void addComputeProgramSourceBuildNanos(long nanos) {
        computeProgramSourceBuildNanos.addAndGet(nonNegative(nanos));
    }

    public void addComputeCompileExecuteNanos(long nanos) {
        computeCompileExecuteNanos.addAndGet(nonNegative(nanos));
    }

    public void addUpdateStepNanos(long nanos) {
        updateStepNanos.addAndGet(nonNegative(nanos));
    }

    public void addUpdateDirectChangesetNanos(long nanos) {
        updateDirectChangesetNanos.addAndGet(nonNegative(nanos));
    }

    public void addUpdatePatchConversionNanos(long nanos) {
        updatePatchConversionNanos.addAndGet(nonNegative(nanos));
    }

    public void addUpdatePatchApplyNanos(long nanos) {
        updatePatchApplyNanos.addAndGet(nonNegative(nanos));
    }

    public void incrementUpdateBatchPatchApplications() {
        updateBatchPatchApplications.incrementAndGet();
    }

    public void incrementUpdateIndividualPatchApplications() {
        updateIndividualPatchApplications.incrementAndGet();
    }

    public void incrementUpdateStaticTemplatesBuilt() {
        updateStaticTemplatesBuilt.incrementAndGet();
    }

    public void incrementUpdateStaticTemplateHits() {
        updateStaticTemplateHits.incrementAndGet();
    }

    public void incrementUpdateReflectionFallbacks() {
        updateReflectionFallbacks.incrementAndGet();
    }

    public void addTriggerStepNanos(long nanos) {
        triggerStepNanos.addAndGet(nonNegative(nanos));
    }

    public void addTriggerEmitEventNanos(long nanos) {
        triggerEmitEventNanos.addAndGet(nonNegative(nanos));
    }

    public void addBexNodeWriterNanos(long nanos) {
        bexNodeWriterNanos.addAndGet(nonNegative(nanos));
    }

    public void incrementDirectBexPatchEntryConversions() {
        directBexPatchEntryConversions.incrementAndGet();
    }

    public void incrementBexPatchFrozenDirectConversions() {
        bexPatchFrozenDirectConversions.incrementAndGet();
    }

    public void incrementBexPatchNodeMaterializations() {
        bexPatchNodeMaterializations.incrementAndGet();
    }

    public void addBexMetrics(BexMetrics metrics) {
        if (metrics == null) {
            return;
        }
        bexCompileCacheHits.addAndGet(metrics.compileCacheHits());
        bexCompileCacheMisses.addAndGet(metrics.compileCacheMisses());
        bexCompiledExecutions.addAndGet(metrics.compiledExecutions());
        bexCompileNanos.addAndGet(metrics.compileNanos());
        bexExecuteNanos.addAndGet(metrics.executeNanos());
    }

    @Override
    public void addProcessDocumentNanos(long nanos) {
        processDocumentNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBlueProcessDocumentNanos(long nanos) {
        blueProcessDocumentNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addEventPreprocessNanos(long nanos) {
        eventPreprocessNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addResultSnapshotAttachNanos(long nanos) {
        resultSnapshotAttachNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBlueIdCalculationNanos(long nanos) {
        blueIdCalculationNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addProcessingSnapshotCacheLookupNanos(long nanos) {
        processingSnapshotCacheLookupNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementProcessingSnapshotCacheHits() {
        processingSnapshotCacheHits.incrementAndGet();
    }

    @Override
    public void incrementProcessingSnapshotCacheMisses() {
        processingSnapshotCacheMisses.incrementAndGet();
    }

    @Override
    public void addProcessingSnapshotFromDocumentNanos(long nanos) {
        processingSnapshotFromDocumentNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementProcessingSnapshotFromDocumentBuilds() {
        processingSnapshotFromDocumentBuilds.incrementAndGet();
    }

    @Override
    public void incrementProcessEventSnapshotAttempts() {
        processEventSnapshotAttempts.incrementAndGet();
    }

    @Override
    public void incrementProcessEventSnapshotBuilds() {
        processEventSnapshotBuilds.incrementAndGet();
    }

    @Override
    public void incrementProcessEventSnapshotFailures() {
        processEventSnapshotFailures.incrementAndGet();
    }

    @Override
    public void addProcessEventSnapshotConstructionNanos(long nanos) {
        processEventSnapshotConstructionNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBundleLoadNanos(long nanos) {
        bundleLoadNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBundleLoadCacheKeyBuildNanos(long nanos) {
        bundleLoadCacheKeyBuildNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBundleLoadActualBuildNanos(long nanos) {
        bundleLoadActualBuildNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBundleLoadReuseNanos(long nanos) {
        bundleLoadReuseNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementBundleLoadCacheHits() {
        bundleLoadCacheHits.incrementAndGet();
    }

    @Override
    public void incrementBundleLoadCacheMisses() {
        bundleLoadCacheMisses.incrementAndGet();
    }

    @Override
    public void incrementBundlesBuilt() {
        bundlesBuilt.incrementAndGet();
    }

    @Override
    public void incrementBundlesReused() {
        bundlesReused.incrementAndGet();
    }

    @Override
    public void incrementBundleScopeLoadAttempts() {
        bundleScopeLoadAttempts.incrementAndGet();
    }

    @Override
    public void incrementBundleScopeExecutionCacheHits() {
        bundleScopeExecutionCacheHits.incrementAndGet();
    }

    @Override
    public void incrementBundleScopeRefreshes() {
        bundleScopeRefreshes.incrementAndGet();
    }

    @Override
    public void addBundleScopeTerminationCheckNanos(long nanos) {
        bundleScopeTerminationCheckNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBundleScopeResolvedLookupNanos(long nanos) {
        bundleScopeResolvedLookupNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBundleScopeContractLoadNanos(long nanos) {
        bundleScopeContractLoadNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addChannelDiscoveryNanos(long nanos) {
        channelDiscoveryNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addChannelMatchNanos(long nanos) {
        channelMatchNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementChannelEvaluations() {
        channelEvaluations.incrementAndGet();
    }

    @Override
    public void addHandlerDiscoveryNanos(long nanos) {
        handlerDiscoveryNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addHandlerMatchNanos(long nanos) {
        handlerMatchNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementHandlerMatchAttempts() {
        handlerMatchAttempts.incrementAndGet();
    }

    @Override
    public void addHandlerExecutionNanos(long nanos) {
        handlerExecutionNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementHandlersExecuted() {
        handlersExecuted.incrementAndGet();
    }

    @Override
    public void addTriggeredEventRoutingNanos(long nanos) {
        triggeredEventRoutingNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementTriggeredEventsRouted() {
        triggeredEventsRouted.incrementAndGet();
    }

    @Override
    public void addCheckpointUpdateNanos(long nanos) {
        checkpointUpdateNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointEnsureNanos(long nanos) {
        checkpointEnsureNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointFindNanos(long nanos) {
        checkpointFindNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointCurrentIdentityNanos(long nanos) {
        checkpointCurrentIdentityNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointIsNewerNanos(long nanos) {
        checkpointIsNewerNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointDuplicateNanos(long nanos) {
        checkpointDuplicateNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointPersistNanos(long nanos) {
        checkpointPersistNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementCheckpointIdentityCacheHits() {
        checkpointIdentityCacheHits.incrementAndGet();
    }

    @Override
    public void incrementCheckpointIdentityCacheMisses() {
        checkpointIdentityCacheMisses.incrementAndGet();
    }

    @Override
    public void incrementCheckpointStoredIdentityCacheHits() {
        checkpointStoredIdentityCacheHits.incrementAndGet();
    }

    @Override
    public void incrementCheckpointStoredIdentityCacheMisses() {
        checkpointStoredIdentityCacheMisses.incrementAndGet();
    }

    @Override
    public void addCheckpointDirectBlueIdNanos(long nanos) {
        checkpointDirectBlueIdNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointContentBlueIdNanos(long nanos) {
        checkpointContentBlueIdNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addCheckpointFallbackNanos(long nanos) {
        checkpointFallbackNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addSnapshotCommitNanos(long nanos) {
        snapshotCommitNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addPostProcessingNanos(long nanos) {
        postProcessingNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addPatchBoundaryNanos(long nanos) {
        patchBoundaryNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addPatchGasNanos(long nanos) {
        patchGasNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addDocumentUpdateRoutingNanos(long nanos) {
        documentUpdateRoutingNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementDocumentUpdateEventsBuilt() {
        documentUpdateEventsBuilt.incrementAndGet();
    }

    @Override
    public void incrementDocumentUpdateEventsSkippedNoChannel() {
        documentUpdateEventsSkippedNoChannel.incrementAndGet();
    }

    @Override
    public void addBatchPatchPlanningNanos(long nanos) {
        batchPatchPlanningNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBatchPatchConformanceNanos(long nanos) {
        batchPatchConformanceNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBatchPatchBuildUpdatesNanos(long nanos) {
        batchPatchBuildUpdatesNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addBatchPatchCommitNanos(long nanos) {
        batchPatchCommitNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementDocumentUpdateBeforeMaterializations() {
        documentUpdateBeforeMaterializations.incrementAndGet();
    }

    @Override
    public void incrementDocumentUpdateAfterMaterializations() {
        documentUpdateAfterMaterializations.incrementAndGet();
    }

    @Override
    public void incrementPatchSequencesPrepared() {
        patchSequencesPrepared.incrementAndGet();
    }

    @Override
    public void addPatchesPrepared(long count) {
        patchesPrepared.addAndGet(count);
    }

    @Override
    public void incrementSingletonPatchTransactions() {
        singletonPatchTransactions.incrementAndGet();
    }

    @Override
    public void addSequencePlanningNanos(long nanos) {
        sequencePlanningNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addSequenceConformanceNanos(long nanos) {
        sequenceConformanceNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addSequenceCommitNanos(long nanos) {
        sequenceCommitNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void addSequenceFinalCacheCommitNanos(long nanos) {
        sequenceFinalCacheCommitNanos.addAndGet(nonNegative(nanos));
    }

    @Override
    public void incrementSequenceIntermediateSnapshotAdvances() {
        sequenceIntermediateSnapshotAdvances.incrementAndGet();
    }

    @Override
    public void incrementSequenceSharedSnapshotCacheInserts() {
        sequenceSharedSnapshotCacheInserts.incrementAndGet();
    }

    @Override
    public void incrementSequenceFinalSnapshotCacheInserts() {
        sequenceFinalSnapshotCacheInserts.incrementAndGet();
    }

    @Override
    public void incrementSequenceSuffixRebases() {
        sequenceSuffixRebases.incrementAndGet();
    }

    @Override
    public void incrementSequenceStalePreviewFallbacks() {
        sequenceStalePreviewFallbacks.incrementAndGet();
    }

    @Override
    public void incrementSequenceFallbackPatches() {
        sequenceFallbackPatches.incrementAndGet();
    }

    @Override
    public void incrementParsedPointerCacheHits() {
        parsedPointerCacheHits.incrementAndGet();
    }

    @Override
    public void incrementParsedPointerCacheMisses() {
        parsedPointerCacheMisses.incrementAndGet();
    }

    @Override
    public void incrementFrozenPatchValueHits() {
        frozenPatchValueHits.incrementAndGet();
    }

    @Override
    public void incrementPatchValueMaterializations() {
        patchValueMaterializations.incrementAndGet();
    }

    public void incrementWorkflowDocumentViewsFromFrozen() {
        workflowDocumentViewsFromFrozen.incrementAndGet();
    }

    public void incrementWorkflowDocumentViewsFromDocument() {
        workflowDocumentViewsFromDocument.incrementAndGet();
    }

    public void incrementWorkflowDocumentViewMisses() {
        workflowDocumentViewMisses.incrementAndGet();
    }

    public void incrementBexDocumentViewMaterializedHits() {
        bexDocumentViewMaterializedHits.incrementAndGet();
    }

    public void incrementBexDocumentViewFrozenDirectHits() {
        bexDocumentViewFrozenDirectHits.incrementAndGet();
    }

    public void incrementBexDocumentViewFrozenRootFallbackHits() {
        bexDocumentViewFrozenRootFallbackHits.incrementAndGet();
    }

    public void incrementBexDocumentViewUndefinedHits() {
        bexDocumentViewUndefinedHits.incrementAndGet();
    }

    public long workflowStepsExecuted() {
        return workflowStepsExecuted.get();
    }

    public long workflowPlansBuilt() {
        return workflowPlansBuilt.get();
    }

    public long workflowPlanCacheHits() {
        return workflowPlanCacheHits.get();
    }

    public long workflowPlanCacheMisses() {
        return workflowPlanCacheMisses.get();
    }

    public long workflowPlanCacheEvictions() {
        return workflowPlanCacheEvictions.get();
    }

    public long workflowPlanWeightBytes() {
        return workflowPlanWeightBytes.get();
    }

    public long workflowExecutorLookups() {
        return workflowExecutorLookups.get();
    }

    public long workflowStepResultSnapshotsCreated() {
        return workflowStepResultSnapshotsCreated.get();
    }

    public long workflowStepResultViewHits() {
        return workflowStepResultViewHits.get();
    }

    public long computeStepsExecuted() {
        return computeStepsExecuted.get();
    }

    public long computePlansBuilt() {
        return computePlansBuilt.get();
    }

    public long computePlanCacheHits() {
        return computePlanCacheHits.get();
    }

    public long computePlanCacheMisses() {
        return computePlanCacheMisses.get();
    }

    public long computePlanCacheEvictions() {
        return computePlanCacheEvictions.get();
    }

    public long computePlanWeightBytes() {
        return computePlanWeightBytes.get();
    }

    public long updateDocumentStepsExecuted() {
        return updateDocumentStepsExecuted.get();
    }

    public long triggerEventStepsExecuted() {
        return triggerEventStepsExecuted.get();
    }

    public long directBexChangesetHits() {
        return directBexChangesetHits.get();
    }

    public long bexSyntheticProgramMaterializations() {
        return bexSyntheticProgramMaterializations.get();
    }

    public long patchesApplied() {
        return patchesApplied.get();
    }

    public long eventsEmitted() {
        return eventsEmitted.get();
    }

    public long successfulComputeTerminationRequests() {
        return successfulComputeTerminationRequests.get();
    }

    public long declarativeTerminationSteps() {
        return declarativeTerminationSteps.get();
    }

    public long computeResultValidationFailures() {
        return computeResultValidationFailures.get();
    }

    public long computeProgramNormalizations() {
        return computeProgramNormalizations.get();
    }

    public long computeDefinitionNormalizations() {
        return computeDefinitionNormalizations.get();
    }

    public long computeDefinitionMaterializations() {
        return computeDefinitionMaterializations.get();
    }

    public long computeDefinitionFrozenDirectHits() {
        return computeDefinitionFrozenDirectHits.get();
    }

    public long computeProgramSourceBuilds() {
        return computeProgramSourceBuilds.get();
    }

    public long computeDefinitionResolveHits() {
        return computeDefinitionResolveHits.get();
    }

    public long computeDefinitionResolveMisses() {
        return computeDefinitionResolveMisses.get();
    }

    public long workflowRunnerNanos() {
        return workflowRunnerNanos.get();
    }

    public long computeStepNanos() {
        return computeStepNanos.get();
    }

    public long computeDefinitionResolveNanos() {
        return computeDefinitionResolveNanos.get();
    }

    public long computeContextBuildNanos() {
        return computeContextBuildNanos.get();
    }

    public long computeProgramSourceBuildNanos() {
        return computeProgramSourceBuildNanos.get();
    }

    public long computeCompileExecuteNanos() {
        return computeCompileExecuteNanos.get();
    }

    public long updateStepNanos() {
        return updateStepNanos.get();
    }

    public long updateDirectChangesetNanos() {
        return updateDirectChangesetNanos.get();
    }

    public long updatePatchConversionNanos() {
        return updatePatchConversionNanos.get();
    }

    public long updatePatchApplyNanos() {
        return updatePatchApplyNanos.get();
    }

    public long updateBatchPatchApplications() {
        return updateBatchPatchApplications.get();
    }

    public long updateIndividualPatchApplications() {
        return updateIndividualPatchApplications.get();
    }

    public long updateStaticTemplatesBuilt() {
        return updateStaticTemplatesBuilt.get();
    }

    public long updateStaticTemplateHits() {
        return updateStaticTemplateHits.get();
    }

    public long updateReflectionFallbacks() {
        return updateReflectionFallbacks.get();
    }

    public long triggerStepNanos() {
        return triggerStepNanos.get();
    }

    public long triggerEmitEventNanos() {
        return triggerEmitEventNanos.get();
    }

    public long bexCompileNanos() {
        return bexCompileNanos.get();
    }

    public long bexExecuteNanos() {
        return bexExecuteNanos.get();
    }

    public long bexCompileCacheHits() {
        return bexCompileCacheHits.get();
    }

    public long bexCompileCacheMisses() {
        return bexCompileCacheMisses.get();
    }

    public long bexCompiledExecutions() {
        return bexCompiledExecutions.get();
    }

    public long bexNodeWriterNanos() {
        return bexNodeWriterNanos.get();
    }

    public long directBexPatchEntryConversions() {
        return directBexPatchEntryConversions.get();
    }

    public long bexPatchFrozenDirectConversions() {
        return bexPatchFrozenDirectConversions.get();
    }

    public long bexPatchNodeMaterializations() {
        return bexPatchNodeMaterializations.get();
    }

    public long processDocumentNanos() {
        return processDocumentNanos.get();
    }

    public long blueProcessDocumentNanos() {
        return blueProcessDocumentNanos.get();
    }

    public long eventPreprocessNanos() {
        return eventPreprocessNanos.get();
    }

    public long resultSnapshotAttachNanos() {
        return resultSnapshotAttachNanos.get();
    }

    public long blueIdCalculationNanos() {
        return blueIdCalculationNanos.get();
    }

    public long processingSnapshotCacheLookupNanos() {
        return processingSnapshotCacheLookupNanos.get();
    }

    public long processingSnapshotCacheHits() {
        return processingSnapshotCacheHits.get();
    }

    public long processingSnapshotCacheMisses() {
        return processingSnapshotCacheMisses.get();
    }

    public long processingSnapshotFromDocumentNanos() {
        return processingSnapshotFromDocumentNanos.get();
    }

    public long processingSnapshotFromDocumentBuilds() {
        return processingSnapshotFromDocumentBuilds.get();
    }

    public long processEventSnapshotAttempts() {
        return processEventSnapshotAttempts.get();
    }

    public long processEventSnapshotBuilds() {
        return processEventSnapshotBuilds.get();
    }

    public long processEventSnapshotFailures() {
        return processEventSnapshotFailures.get();
    }

    public long processEventSnapshotConstructionNanos() {
        return processEventSnapshotConstructionNanos.get();
    }

    public long bundleLoadNanos() {
        return bundleLoadNanos.get();
    }

    public long bundleLoadCacheKeyBuildNanos() {
        return bundleLoadCacheKeyBuildNanos.get();
    }

    public long bundleLoadActualBuildNanos() {
        return bundleLoadActualBuildNanos.get();
    }

    public long bundleLoadReuseNanos() {
        return bundleLoadReuseNanos.get();
    }

    public long bundleLoadCacheHits() {
        return bundleLoadCacheHits.get();
    }

    public long bundleLoadCacheMisses() {
        return bundleLoadCacheMisses.get();
    }

    public long bundlesBuilt() {
        return bundlesBuilt.get();
    }

    public long bundlesReused() {
        return bundlesReused.get();
    }

    public long bundleScopeLoadAttempts() {
        return bundleScopeLoadAttempts.get();
    }

    public long bundleScopeExecutionCacheHits() {
        return bundleScopeExecutionCacheHits.get();
    }

    public long bundleScopeRefreshes() {
        return bundleScopeRefreshes.get();
    }

    public long bundleScopeTerminationCheckNanos() {
        return bundleScopeTerminationCheckNanos.get();
    }

    public long bundleScopeResolvedLookupNanos() {
        return bundleScopeResolvedLookupNanos.get();
    }

    public long bundleScopeContractLoadNanos() {
        return bundleScopeContractLoadNanos.get();
    }

    public long channelDiscoveryNanos() {
        return channelDiscoveryNanos.get();
    }

    public long channelMatchNanos() {
        return channelMatchNanos.get();
    }

    public long channelEvaluations() {
        return channelEvaluations.get();
    }

    public long handlerDiscoveryNanos() {
        return handlerDiscoveryNanos.get();
    }

    public long handlerMatchNanos() {
        return handlerMatchNanos.get();
    }

    public long handlerMatchAttempts() {
        return handlerMatchAttempts.get();
    }

    public long handlerExecutionNanos() {
        return handlerExecutionNanos.get();
    }

    public long handlersExecuted() {
        return handlersExecuted.get();
    }

    public long triggeredEventRoutingNanos() {
        return triggeredEventRoutingNanos.get();
    }

    public long triggeredEventsRouted() {
        return triggeredEventsRouted.get();
    }

    public long checkpointUpdateNanos() {
        return checkpointUpdateNanos.get();
    }

    public long checkpointEnsureNanos() {
        return checkpointEnsureNanos.get();
    }

    public long checkpointFindNanos() {
        return checkpointFindNanos.get();
    }

    public long checkpointCurrentIdentityNanos() {
        return checkpointCurrentIdentityNanos.get();
    }

    public long checkpointIsNewerNanos() {
        return checkpointIsNewerNanos.get();
    }

    public long checkpointDuplicateNanos() {
        return checkpointDuplicateNanos.get();
    }

    public long checkpointPersistNanos() {
        return checkpointPersistNanos.get();
    }

    public long checkpointIdentityCacheHits() {
        return checkpointIdentityCacheHits.get();
    }

    public long checkpointIdentityCacheMisses() {
        return checkpointIdentityCacheMisses.get();
    }

    public long checkpointStoredIdentityCacheHits() {
        return checkpointStoredIdentityCacheHits.get();
    }

    public long checkpointStoredIdentityCacheMisses() {
        return checkpointStoredIdentityCacheMisses.get();
    }

    public long checkpointDirectBlueIdNanos() {
        return checkpointDirectBlueIdNanos.get();
    }

    public long checkpointContentBlueIdNanos() {
        return checkpointContentBlueIdNanos.get();
    }

    public long checkpointFallbackNanos() {
        return checkpointFallbackNanos.get();
    }

    public long snapshotCommitNanos() {
        return snapshotCommitNanos.get();
    }

    public long postProcessingNanos() {
        return postProcessingNanos.get();
    }

    public long patchBoundaryNanos() {
        return patchBoundaryNanos.get();
    }

    public long patchGasNanos() {
        return patchGasNanos.get();
    }

    public long documentUpdateRoutingNanos() {
        return documentUpdateRoutingNanos.get();
    }

    public long documentUpdateEventsBuilt() {
        return documentUpdateEventsBuilt.get();
    }

    public long documentUpdateEventsSkippedNoChannel() {
        return documentUpdateEventsSkippedNoChannel.get();
    }

    public long batchPatchPlanningNanos() {
        return batchPatchPlanningNanos.get();
    }

    public long batchPatchConformanceNanos() {
        return batchPatchConformanceNanos.get();
    }

    public long batchPatchBuildUpdatesNanos() {
        return batchPatchBuildUpdatesNanos.get();
    }

    public long batchPatchCommitNanos() {
        return batchPatchCommitNanos.get();
    }

    public long documentUpdateBeforeMaterializations() {
        return documentUpdateBeforeMaterializations.get();
    }

    public long documentUpdateAfterMaterializations() {
        return documentUpdateAfterMaterializations.get();
    }

    /**
     * Number of reusable Language patch-sequence planning sessions. This is the raw value from
     * {@link #incrementPatchSequencesPrepared()}.
     *
     * @return number of prepared reusable patch-sequence sessions
     */
    public long preparedPatchSequences() {
        return patchSequencesPrepared.get();
    }

    /**
     * Number of patches accepted by Language sequence sessions.
     *
     * @return number of accepted sequence-session patches
     */
    public long preparedPatches() {
        return patchesPrepared.get();
    }

    /**
     * Closest public proxy for a Language sequence transaction.
     *
     * <p>The callback identifies a reusable sequential <em>planning</em> session,
     * not the Language runtime's internal transaction counter, so reports must
     * retain this qualification.</p>
     *
     * @return number of reusable Language sequence planning sessions
     */
    public long languageSequenceTransactions() {
        return patchSequencesPrepared.get();
    }

    /**
     * Number of standalone one-patch Language transactions.
     *
     * @return number of singleton patch transactions
     */
    public long languageSingletonTransactions() {
        return singletonPatchTransactions.get();
    }

    public long sequencePlanningNanos() {
        return sequencePlanningNanos.get();
    }

    public long sequenceConformanceNanos() {
        return sequenceConformanceNanos.get();
    }

    public long sequenceCommitNanos() {
        return sequenceCommitNanos.get();
    }

    public long sequenceFinalCacheCommitNanos() {
        return sequenceFinalCacheCommitNanos.get();
    }

    public long languageIntermediateSnapshotAdvances() {
        return sequenceIntermediateSnapshotAdvances.get();
    }

    public long sequenceSharedSnapshotCacheInserts() {
        return sequenceSharedSnapshotCacheInserts.get();
    }

    /**
     * Maps to Language's final sequence snapshot-cache insertion callback.
     *
     * @return number of final sequence snapshot-cache insertions
     */
    public long languageFinalSnapshotPromotions() {
        return sequenceFinalSnapshotCacheInserts.get();
    }

    public long sequenceFinalSnapshotCacheInserts() {
        return sequenceFinalSnapshotCacheInserts.get();
    }

    public long languageSuffixRebases() {
        return sequenceSuffixRebases.get();
    }

    public long sequenceStalePreviewFallbacks() {
        return sequenceStalePreviewFallbacks.get();
    }

    public long languageFallbackPatches() {
        return sequenceFallbackPatches.get();
    }

    public long parsedPointerCacheHits() {
        return parsedPointerCacheHits.get();
    }

    public long parsedPointerCacheMisses() {
        return parsedPointerCacheMisses.get();
    }

    public long frozenPatchValueHits() {
        return frozenPatchValueHits.get();
    }

    public long languagePatchValueMaterializations() {
        return patchValueMaterializations.get();
    }

    public long workflowDocumentViewsFromFrozen() {
        return workflowDocumentViewsFromFrozen.get();
    }

    public long workflowDocumentViewsFromDocument() {
        return workflowDocumentViewsFromDocument.get();
    }

    public long workflowDocumentViewMisses() {
        return workflowDocumentViewMisses.get();
    }

    public long bexDocumentViewMaterializedHits() {
        return bexDocumentViewMaterializedHits.get();
    }

    public long bexDocumentViewFrozenDirectHits() {
        return bexDocumentViewFrozenDirectHits.get();
    }

    public long bexDocumentViewFrozenRootFallbackHits() {
        return bexDocumentViewFrozenRootFallbackHits.get();
    }

    public long bexDocumentViewUndefinedHits() {
        return bexDocumentViewUndefinedHits.get();
    }

    @Override
    public void addMetric(String metricName, long delta) {
        AtomicLong counter = languageMetric(languageCounters, metricName);
        if (counter != null) {
            counter.addAndGet(delta);
        }
    }

    @Override
    public void setMetric(String metricName, long value) {
        AtomicLong gauge = languageMetric(languageGauges, metricName);
        if (gauge != null) {
            gauge.set(value);
        }
    }

    @Override
    public void recordMetricHighWater(String metricName, long value) {
        AtomicLong highWater = languageMetric(languageHighWaterMarks, metricName);
        if (highWater == null) {
            return;
        }
        long current = highWater.get();
        while (value > current && !highWater.compareAndSet(current, value)) {
            current = highWater.get();
        }
    }

    /**
     * Immutable, name-sorted snapshot of Language's generic additive counters.
     *
     * @return immutable additive-counter values sorted by metric name
     */
    public Map<String, Long> languageCounters() {
        return immutableSortedValues(languageCounters);
    }

    /**
     * Immutable, name-sorted snapshot of Language's generic current-value
     * gauges.
     *
     * @return immutable current-value gauges sorted by metric name
     */
    public Map<String, Long> languageGauges() {
        return immutableSortedValues(languageGauges);
    }

    /**
     * Immutable, name-sorted snapshot of Language's generic high-water gauges.
     *
     * @return immutable high-water gauges sorted by metric name
     */
    public Map<String, Long> languageHighWaterMarks() {
        return immutableSortedValues(languageHighWaterMarks);
    }

    /**
     * Number of generic metric samples dropped because their new name exceeded
     * the cap.
     *
     * @return number of metric samples dropped due to the metric-name cap
     */
    public long droppedLanguageMetricNames() {
        return droppedLanguageMetricNames.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    private AtomicLong languageMetric(ConcurrentMap<String, AtomicLong> metrics,
                                      String metricName) {
        requireMetricName(metricName);
        if (!acceptLanguageMetricName(metricName)) {
            return null;
        }
        AtomicLong current = metrics.get(metricName);
        if (current != null) {
            return current;
        }
        AtomicLong created = new AtomicLong();
        AtomicLong raced = metrics.putIfAbsent(metricName, created);
        return raced != null ? raced : created;
    }

    private boolean acceptLanguageMetricName(String metricName) {
        if (languageMetricNames.containsKey(metricName)) {
            return true;
        }
        synchronized (languageMetricNameLock) {
            if (languageMetricNames.containsKey(metricName)) {
                return true;
            }
            if (languageMetricNames.size() >= MAX_LANGUAGE_METRIC_NAMES) {
                droppedLanguageMetricNames.incrementAndGet();
                return false;
            }
            languageMetricNames.put(metricName, Boolean.TRUE);
            return true;
        }
    }

    private static void requireMetricName(String metricName) {
        if (metricName == null || metricName.isEmpty()) {
            throw new IllegalArgumentException("metricName must not be empty");
        }
    }

    private static Map<String, Long> immutableSortedValues(
            ConcurrentMap<String, AtomicLong> source) {
        Map<String, Long> values = new TreeMap<>();
        for (Map.Entry<String, AtomicLong> entry : source.entrySet()) {
            values.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(values);
    }

    private static void addToNonNegativeGauge(final AtomicLong gauge, final long delta) {
        gauge.updateAndGet(current -> {
            if (delta >= 0L) {
                return current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
            }
            if (delta == Long.MIN_VALUE || current < -delta) {
                return 0L;
            }
            return current + delta;
        });
    }

    private static long nonNegative(long nanos) {
        return nanos > 0L ? nanos : 0L;
    }

    public static final class Snapshot {
        public final long workflowStepsExecuted;
        public final long workflowPlansBuilt;
        public final long workflowPlanCacheHits;
        public final long workflowPlanCacheMisses;
        public final long workflowPlanCacheEvictions;
        public final long workflowPlanWeightBytes;
        public final long workflowExecutorLookups;
        public final long workflowStepResultSnapshotsCreated;
        public final long workflowStepResultViewHits;
        public final long computeStepsExecuted;
        public final long computePlansBuilt;
        public final long computePlanCacheHits;
        public final long computePlanCacheMisses;
        public final long computePlanCacheEvictions;
        public final long computePlanWeightBytes;
        public final long updateDocumentStepsExecuted;
        public final long triggerEventStepsExecuted;
        public final long directBexChangesetHits;
        public final long bexSyntheticProgramMaterializations;
        public final long patchesApplied;
        public final long eventsEmitted;
        public final long successfulComputeTerminationRequests;
        public final long declarativeTerminationSteps;
        public final long computeResultValidationFailures;
        public final long computeProgramNormalizations;
        public final long computeDefinitionNormalizations;
        public final long computeDefinitionMaterializations;
        public final long computeDefinitionFrozenDirectHits;
        public final long computeProgramSourceBuilds;
        public final long computeDefinitionResolveHits;
        public final long computeDefinitionResolveMisses;
        public final long workflowRunnerNanos;
        public final long computeStepNanos;
        public final long computeDefinitionResolveNanos;
        public final long computeContextBuildNanos;
        public final long computeProgramSourceBuildNanos;
        public final long computeCompileExecuteNanos;
        public final long updateStepNanos;
        public final long updateDirectChangesetNanos;
        public final long updatePatchConversionNanos;
        public final long updatePatchApplyNanos;
        public final long updateBatchPatchApplications;
        public final long updateIndividualPatchApplications;
        public final long updateStaticTemplatesBuilt;
        public final long updateStaticTemplateHits;
        public final long updateReflectionFallbacks;
        public final long triggerStepNanos;
        public final long triggerEmitEventNanos;
        public final long bexCompileNanos;
        public final long bexExecuteNanos;
        public final long bexCompileCacheHits;
        public final long bexCompileCacheMisses;
        public final long bexCompiledExecutions;
        public final long bexNodeWriterNanos;
        public final long directBexPatchEntryConversions;
        public final long bexPatchFrozenDirectConversions;
        public final long bexPatchNodeMaterializations;
        public final long processDocumentNanos;
        public final long blueProcessDocumentNanos;
        public final long eventPreprocessNanos;
        public final long resultSnapshotAttachNanos;
        public final long blueIdCalculationNanos;
        public final long processingSnapshotCacheLookupNanos;
        public final long processingSnapshotCacheHits;
        public final long processingSnapshotCacheMisses;
        public final long processingSnapshotFromDocumentNanos;
        public final long processingSnapshotFromDocumentBuilds;
        public final long processEventSnapshotAttempts;
        public final long processEventSnapshotBuilds;
        public final long processEventSnapshotFailures;
        public final long processEventSnapshotConstructionNanos;
        public final long bundleLoadNanos;
        public final long bundleLoadCacheKeyBuildNanos;
        public final long bundleLoadActualBuildNanos;
        public final long bundleLoadReuseNanos;
        public final long bundleLoadCacheHits;
        public final long bundleLoadCacheMisses;
        public final long bundlesBuilt;
        public final long bundlesReused;
        public final long bundleScopeLoadAttempts;
        public final long bundleScopeExecutionCacheHits;
        public final long bundleScopeRefreshes;
        public final long bundleScopeTerminationCheckNanos;
        public final long bundleScopeResolvedLookupNanos;
        public final long bundleScopeContractLoadNanos;
        public final long channelDiscoveryNanos;
        public final long channelMatchNanos;
        public final long channelEvaluations;
        public final long handlerDiscoveryNanos;
        public final long handlerMatchNanos;
        public final long handlerMatchAttempts;
        public final long handlerExecutionNanos;
        public final long handlersExecuted;
        public final long triggeredEventRoutingNanos;
        public final long triggeredEventsRouted;
        public final long checkpointUpdateNanos;
        public final long checkpointEnsureNanos;
        public final long checkpointFindNanos;
        public final long checkpointCurrentIdentityNanos;
        public final long checkpointIsNewerNanos;
        public final long checkpointDuplicateNanos;
        public final long checkpointPersistNanos;
        public final long checkpointIdentityCacheHits;
        public final long checkpointIdentityCacheMisses;
        public final long checkpointStoredIdentityCacheHits;
        public final long checkpointStoredIdentityCacheMisses;
        public final long checkpointDirectBlueIdNanos;
        public final long checkpointContentBlueIdNanos;
        public final long checkpointFallbackNanos;
        public final long snapshotCommitNanos;
        public final long postProcessingNanos;
        public final long patchBoundaryNanos;
        public final long patchGasNanos;
        public final long documentUpdateRoutingNanos;
        public final long documentUpdateEventsBuilt;
        public final long documentUpdateEventsSkippedNoChannel;
        public final long batchPatchPlanningNanos;
        public final long batchPatchConformanceNanos;
        public final long batchPatchBuildUpdatesNanos;
        public final long batchPatchCommitNanos;
        public final long documentUpdateBeforeMaterializations;
        public final long documentUpdateAfterMaterializations;
        public final long preparedPatchSequences;
        public final long preparedPatches;
        public final long languageSequenceTransactions;
        public final long languageSingletonTransactions;
        public final long sequencePlanningNanos;
        public final long sequenceConformanceNanos;
        public final long sequenceCommitNanos;
        public final long sequenceFinalCacheCommitNanos;
        public final long languageIntermediateSnapshotAdvances;
        public final long sequenceSharedSnapshotCacheInserts;
        public final long languageFinalSnapshotPromotions;
        public final long sequenceFinalSnapshotCacheInserts;
        public final long languageSuffixRebases;
        public final long sequenceStalePreviewFallbacks;
        public final long languageFallbackPatches;
        public final long parsedPointerCacheHits;
        public final long parsedPointerCacheMisses;
        public final long frozenPatchValueHits;
        public final long languagePatchValueMaterializations;
        public final long workflowDocumentViewsFromFrozen;
        public final long workflowDocumentViewsFromDocument;
        public final long workflowDocumentViewMisses;
        public final long bexDocumentViewMaterializedHits;
        public final long bexDocumentViewFrozenDirectHits;
        public final long bexDocumentViewFrozenRootFallbackHits;
        public final long bexDocumentViewUndefinedHits;
        public final Map<String, Long> languageCounters;
        public final Map<String, Long> languageGauges;
        public final Map<String, Long> languageHighWaterMarks;
        public final long droppedLanguageMetricNames;

        private Snapshot(BexProcessingMetrics metrics) {
            this.workflowStepsExecuted = metrics.workflowStepsExecuted();
            this.workflowPlansBuilt = metrics.workflowPlansBuilt();
            this.workflowPlanCacheHits = metrics.workflowPlanCacheHits();
            this.workflowPlanCacheMisses = metrics.workflowPlanCacheMisses();
            this.workflowPlanCacheEvictions = metrics.workflowPlanCacheEvictions();
            this.workflowPlanWeightBytes = metrics.workflowPlanWeightBytes();
            this.workflowExecutorLookups = metrics.workflowExecutorLookups();
            this.workflowStepResultSnapshotsCreated = metrics.workflowStepResultSnapshotsCreated();
            this.workflowStepResultViewHits = metrics.workflowStepResultViewHits();
            this.computeStepsExecuted = metrics.computeStepsExecuted();
            this.computePlansBuilt = metrics.computePlansBuilt();
            this.computePlanCacheHits = metrics.computePlanCacheHits();
            this.computePlanCacheMisses = metrics.computePlanCacheMisses();
            this.computePlanCacheEvictions = metrics.computePlanCacheEvictions();
            this.computePlanWeightBytes = metrics.computePlanWeightBytes();
            this.updateDocumentStepsExecuted = metrics.updateDocumentStepsExecuted();
            this.triggerEventStepsExecuted = metrics.triggerEventStepsExecuted();
            this.directBexChangesetHits = metrics.directBexChangesetHits();
            this.bexSyntheticProgramMaterializations = metrics.bexSyntheticProgramMaterializations();
            this.patchesApplied = metrics.patchesApplied();
            this.eventsEmitted = metrics.eventsEmitted();
            this.successfulComputeTerminationRequests = metrics.successfulComputeTerminationRequests();
            this.declarativeTerminationSteps = metrics.declarativeTerminationSteps();
            this.computeResultValidationFailures = metrics.computeResultValidationFailures();
            this.computeProgramNormalizations = metrics.computeProgramNormalizations();
            this.computeDefinitionNormalizations = metrics.computeDefinitionNormalizations();
            this.computeDefinitionMaterializations = metrics.computeDefinitionMaterializations();
            this.computeDefinitionFrozenDirectHits = metrics.computeDefinitionFrozenDirectHits();
            this.computeProgramSourceBuilds = metrics.computeProgramSourceBuilds();
            this.computeDefinitionResolveHits = metrics.computeDefinitionResolveHits();
            this.computeDefinitionResolveMisses = metrics.computeDefinitionResolveMisses();
            this.workflowRunnerNanos = metrics.workflowRunnerNanos();
            this.computeStepNanos = metrics.computeStepNanos();
            this.computeDefinitionResolveNanos = metrics.computeDefinitionResolveNanos();
            this.computeContextBuildNanos = metrics.computeContextBuildNanos();
            this.computeProgramSourceBuildNanos = metrics.computeProgramSourceBuildNanos();
            this.computeCompileExecuteNanos = metrics.computeCompileExecuteNanos();
            this.updateStepNanos = metrics.updateStepNanos();
            this.updateDirectChangesetNanos = metrics.updateDirectChangesetNanos();
            this.updatePatchConversionNanos = metrics.updatePatchConversionNanos();
            this.updatePatchApplyNanos = metrics.updatePatchApplyNanos();
            this.updateBatchPatchApplications = metrics.updateBatchPatchApplications();
            this.updateIndividualPatchApplications = metrics.updateIndividualPatchApplications();
            this.updateStaticTemplatesBuilt = metrics.updateStaticTemplatesBuilt();
            this.updateStaticTemplateHits = metrics.updateStaticTemplateHits();
            this.updateReflectionFallbacks = metrics.updateReflectionFallbacks();
            this.triggerStepNanos = metrics.triggerStepNanos();
            this.triggerEmitEventNanos = metrics.triggerEmitEventNanos();
            this.bexCompileNanos = metrics.bexCompileNanos();
            this.bexExecuteNanos = metrics.bexExecuteNanos();
            this.bexCompileCacheHits = metrics.bexCompileCacheHits();
            this.bexCompileCacheMisses = metrics.bexCompileCacheMisses();
            this.bexCompiledExecutions = metrics.bexCompiledExecutions();
            this.bexNodeWriterNanos = metrics.bexNodeWriterNanos();
            this.directBexPatchEntryConversions = metrics.directBexPatchEntryConversions();
            this.bexPatchFrozenDirectConversions = metrics.bexPatchFrozenDirectConversions();
            this.bexPatchNodeMaterializations = metrics.bexPatchNodeMaterializations();
            this.processDocumentNanos = metrics.processDocumentNanos();
            this.blueProcessDocumentNanos = metrics.blueProcessDocumentNanos();
            this.eventPreprocessNanos = metrics.eventPreprocessNanos();
            this.resultSnapshotAttachNanos = metrics.resultSnapshotAttachNanos();
            this.blueIdCalculationNanos = metrics.blueIdCalculationNanos();
            this.processingSnapshotCacheLookupNanos = metrics.processingSnapshotCacheLookupNanos();
            this.processingSnapshotCacheHits = metrics.processingSnapshotCacheHits();
            this.processingSnapshotCacheMisses = metrics.processingSnapshotCacheMisses();
            this.processingSnapshotFromDocumentNanos = metrics.processingSnapshotFromDocumentNanos();
            this.processingSnapshotFromDocumentBuilds = metrics.processingSnapshotFromDocumentBuilds();
            this.processEventSnapshotAttempts = metrics.processEventSnapshotAttempts();
            this.processEventSnapshotBuilds = metrics.processEventSnapshotBuilds();
            this.processEventSnapshotFailures = metrics.processEventSnapshotFailures();
            this.processEventSnapshotConstructionNanos = metrics.processEventSnapshotConstructionNanos();
            this.bundleLoadNanos = metrics.bundleLoadNanos();
            this.bundleLoadCacheKeyBuildNanos = metrics.bundleLoadCacheKeyBuildNanos();
            this.bundleLoadActualBuildNanos = metrics.bundleLoadActualBuildNanos();
            this.bundleLoadReuseNanos = metrics.bundleLoadReuseNanos();
            this.bundleLoadCacheHits = metrics.bundleLoadCacheHits();
            this.bundleLoadCacheMisses = metrics.bundleLoadCacheMisses();
            this.bundlesBuilt = metrics.bundlesBuilt();
            this.bundlesReused = metrics.bundlesReused();
            this.bundleScopeLoadAttempts = metrics.bundleScopeLoadAttempts();
            this.bundleScopeExecutionCacheHits = metrics.bundleScopeExecutionCacheHits();
            this.bundleScopeRefreshes = metrics.bundleScopeRefreshes();
            this.bundleScopeTerminationCheckNanos = metrics.bundleScopeTerminationCheckNanos();
            this.bundleScopeResolvedLookupNanos = metrics.bundleScopeResolvedLookupNanos();
            this.bundleScopeContractLoadNanos = metrics.bundleScopeContractLoadNanos();
            this.channelDiscoveryNanos = metrics.channelDiscoveryNanos();
            this.channelMatchNanos = metrics.channelMatchNanos();
            this.channelEvaluations = metrics.channelEvaluations();
            this.handlerDiscoveryNanos = metrics.handlerDiscoveryNanos();
            this.handlerMatchNanos = metrics.handlerMatchNanos();
            this.handlerMatchAttempts = metrics.handlerMatchAttempts();
            this.handlerExecutionNanos = metrics.handlerExecutionNanos();
            this.handlersExecuted = metrics.handlersExecuted();
            this.triggeredEventRoutingNanos = metrics.triggeredEventRoutingNanos();
            this.triggeredEventsRouted = metrics.triggeredEventsRouted();
            this.checkpointUpdateNanos = metrics.checkpointUpdateNanos();
            this.checkpointEnsureNanos = metrics.checkpointEnsureNanos();
            this.checkpointFindNanos = metrics.checkpointFindNanos();
            this.checkpointCurrentIdentityNanos = metrics.checkpointCurrentIdentityNanos();
            this.checkpointIsNewerNanos = metrics.checkpointIsNewerNanos();
            this.checkpointDuplicateNanos = metrics.checkpointDuplicateNanos();
            this.checkpointPersistNanos = metrics.checkpointPersistNanos();
            this.checkpointIdentityCacheHits = metrics.checkpointIdentityCacheHits();
            this.checkpointIdentityCacheMisses = metrics.checkpointIdentityCacheMisses();
            this.checkpointStoredIdentityCacheHits = metrics.checkpointStoredIdentityCacheHits();
            this.checkpointStoredIdentityCacheMisses = metrics.checkpointStoredIdentityCacheMisses();
            this.checkpointDirectBlueIdNanos = metrics.checkpointDirectBlueIdNanos();
            this.checkpointContentBlueIdNanos = metrics.checkpointContentBlueIdNanos();
            this.checkpointFallbackNanos = metrics.checkpointFallbackNanos();
            this.snapshotCommitNanos = metrics.snapshotCommitNanos();
            this.postProcessingNanos = metrics.postProcessingNanos();
            this.patchBoundaryNanos = metrics.patchBoundaryNanos();
            this.patchGasNanos = metrics.patchGasNanos();
            this.documentUpdateRoutingNanos = metrics.documentUpdateRoutingNanos();
            this.documentUpdateEventsBuilt = metrics.documentUpdateEventsBuilt();
            this.documentUpdateEventsSkippedNoChannel = metrics.documentUpdateEventsSkippedNoChannel();
            this.batchPatchPlanningNanos = metrics.batchPatchPlanningNanos();
            this.batchPatchConformanceNanos = metrics.batchPatchConformanceNanos();
            this.batchPatchBuildUpdatesNanos = metrics.batchPatchBuildUpdatesNanos();
            this.batchPatchCommitNanos = metrics.batchPatchCommitNanos();
            this.documentUpdateBeforeMaterializations = metrics.documentUpdateBeforeMaterializations();
            this.documentUpdateAfterMaterializations = metrics.documentUpdateAfterMaterializations();
            this.preparedPatchSequences = metrics.preparedPatchSequences();
            this.preparedPatches = metrics.preparedPatches();
            this.languageSequenceTransactions = metrics.languageSequenceTransactions();
            this.languageSingletonTransactions = metrics.languageSingletonTransactions();
            this.sequencePlanningNanos = metrics.sequencePlanningNanos();
            this.sequenceConformanceNanos = metrics.sequenceConformanceNanos();
            this.sequenceCommitNanos = metrics.sequenceCommitNanos();
            this.sequenceFinalCacheCommitNanos = metrics.sequenceFinalCacheCommitNanos();
            this.languageIntermediateSnapshotAdvances = metrics.languageIntermediateSnapshotAdvances();
            this.sequenceSharedSnapshotCacheInserts = metrics.sequenceSharedSnapshotCacheInserts();
            this.languageFinalSnapshotPromotions = metrics.languageFinalSnapshotPromotions();
            this.sequenceFinalSnapshotCacheInserts = metrics.sequenceFinalSnapshotCacheInserts();
            this.languageSuffixRebases = metrics.languageSuffixRebases();
            this.sequenceStalePreviewFallbacks = metrics.sequenceStalePreviewFallbacks();
            this.languageFallbackPatches = metrics.languageFallbackPatches();
            this.parsedPointerCacheHits = metrics.parsedPointerCacheHits();
            this.parsedPointerCacheMisses = metrics.parsedPointerCacheMisses();
            this.frozenPatchValueHits = metrics.frozenPatchValueHits();
            this.languagePatchValueMaterializations = metrics.languagePatchValueMaterializations();
            this.workflowDocumentViewsFromFrozen = metrics.workflowDocumentViewsFromFrozen();
            this.workflowDocumentViewsFromDocument = metrics.workflowDocumentViewsFromDocument();
            this.workflowDocumentViewMisses = metrics.workflowDocumentViewMisses();
            this.bexDocumentViewMaterializedHits = metrics.bexDocumentViewMaterializedHits();
            this.bexDocumentViewFrozenDirectHits = metrics.bexDocumentViewFrozenDirectHits();
            this.bexDocumentViewFrozenRootFallbackHits = metrics.bexDocumentViewFrozenRootFallbackHits();
            this.bexDocumentViewUndefinedHits = metrics.bexDocumentViewUndefinedHits();
            this.languageCounters = metrics.languageCounters();
            this.languageGauges = metrics.languageGauges();
            this.languageHighWaterMarks = metrics.languageHighWaterMarks();
            this.droppedLanguageMetricNames = metrics.droppedLanguageMetricNames();
        }
    }
}
