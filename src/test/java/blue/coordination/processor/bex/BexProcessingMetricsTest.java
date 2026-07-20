package blue.coordination.processor.bex;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BexProcessingMetricsTest {

    @Test
    void genericLanguageMetricsAreThreadSafeSortedAndImmutable() throws Exception {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        int workers = 8;
        int additionsPerWorker = 2_000;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int addition = 0; addition < additionsPerWorker; addition++) {
                        metrics.addMetric("concurrent.additions", 1L);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        metrics.addMetric("alpha", 2L);
        metrics.addMetric("zulu", 3L);
        metrics.setMetric("cache.plan.entries", 1L);
        metrics.setMetric("cache.plan.entries", 7L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 5L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 11L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 9L);

        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals((long) workers * additionsPerWorker,
                snapshot.languageCounters.get("concurrent.additions"));
        assertEquals(7L, snapshot.languageGauges.get("cache.plan.entries"));
        assertEquals(11L,
                snapshot.languageHighWaterMarks.get("cache.plan.highWaterBytes"));
        assertEquals(Arrays.asList("alpha", "concurrent.additions", "zulu"),
                new ArrayList<>(snapshot.languageCounters.keySet()));

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languageCounters.put("later", 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languageGauges.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languageHighWaterMarks.remove("cache.plan.highWaterBytes"));

        metrics.addMetric("alpha", 5L);
        metrics.setMetric("cache.plan.entries", 9L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 13L);
        assertEquals(2L, snapshot.languageCounters.get("alpha"));
        assertEquals(7L, snapshot.languageGauges.get("cache.plan.entries"));
        assertEquals(11L,
                snapshot.languageHighWaterMarks.get("cache.plan.highWaterBytes"));
    }

    @Test
    void genericLanguageMetricsRetainSuffixesAndCacheMetricKinds() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        metrics.incrementFullSnapshotFallback("stalePreview");
        metrics.incrementNodeCloneCalls("patchValue");
        metrics.incrementNodeCloneCalls("patchValue");
        metrics.incrementCacheHits("processingSnapshot");
        metrics.setCacheCurrentWeightBytes("processingSnapshot", 40L);
        metrics.setCacheEntries("processingSnapshot", 3L);
        metrics.recordCacheHighWaterBytes("processingSnapshot", 40L);
        metrics.recordCacheHighWaterBytes("processingSnapshot", 35L);
        metrics.recordCacheHighWaterBytes("processingSnapshot", 52L);

        Map<String, Long> counters = metrics.languageCounters();
        assertEquals(1L, counters.get("fullSnapshotFallbacks"));
        assertEquals(1L, counters.get("fullSnapshotFallbackReason.stalePreview"));
        assertEquals(2L, counters.get("nodeCloneCallsByPurpose.patchValue"));
        assertEquals(1L, counters.get("cache.processingSnapshot.hits"));
        assertEquals(40L,
                metrics.languageGauges().get("cache.processingSnapshot.currentWeightBytes"));
        assertEquals(3L,
                metrics.languageGauges().get("cache.processingSnapshot.entries"));
        assertEquals(52L, metrics.languageHighWaterMarks()
                .get("cache.processingSnapshot.highWaterBytes"));
    }

    @Test
    void genericLanguageMetricNamesAreCappedAcrossMetricKinds() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        for (int index = 0; index < BexProcessingMetrics.MAX_LANGUAGE_METRIC_NAMES; index++) {
            metrics.addMetric("bounded." + index, 1L);
        }

        metrics.setMetric("bounded.0", 7L);
        metrics.setMetric("overflow.gauge", 9L);
        metrics.recordMetricHighWater("overflow.highWater", 11L);
        metrics.addMetric("overflow.counter", 1L);
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        assertEquals(BexProcessingMetrics.MAX_LANGUAGE_METRIC_NAMES,
                snapshot.languageCounters.size());
        assertEquals(7L, snapshot.languageGauges.get("bounded.0"));
        assertFalse(snapshot.languageGauges.containsKey("overflow.gauge"));
        assertFalse(snapshot.languageHighWaterMarks.containsKey("overflow.highWater"));
        assertFalse(snapshot.languageCounters.containsKey("overflow.counter"));
        assertEquals(3L, snapshot.droppedLanguageMetricNames);
        assertEquals(3L, metrics.droppedLanguageMetricNames());
    }

    @Test
    void bexProcessingMetricsExposeProcessEventSnapshotCounters() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.incrementProcessEventSnapshotFailures();
        metrics.addProcessEventSnapshotConstructionNanos(-1L);

        assertEquals(1L, metrics.processEventSnapshotAttempts());
        assertEquals(1L, metrics.processEventSnapshotBuilds());
        assertEquals(1L, metrics.processEventSnapshotFailures());
        assertEquals(0L, metrics.processEventSnapshotConstructionNanos(),
                "host-provided timing samples must be bounded at zero");
    }

    @Test
    void bexProcessingMetricsSnapshotIsImmutableAndAccumulates() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.addProcessEventSnapshotConstructionNanos(11L);
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.incrementProcessEventSnapshotFailures();
        metrics.addProcessEventSnapshotConstructionNanos(13L);
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        assertSnapshot(first, 1L, 1L, 0L, 11L);
        assertSnapshot(second, 2L, 2L, 1L, 24L);
    }

    @Test
    void terminationMetricsAreBoundedCountersAndSnapshotsAreImmutable() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementSuccessfulComputeTerminationRequests();
        metrics.incrementDeclarativeTerminationSteps();
        metrics.incrementComputeResultValidationFailures();
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        metrics.incrementSuccessfulComputeTerminationRequests();
        metrics.incrementDeclarativeTerminationSteps();
        metrics.incrementComputeResultValidationFailures();
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        assertTerminationSnapshot(first, 1L);
        assertTerminationSnapshot(second, 2L);
    }

    @Test
    void languageSequenceCallbacksExposeProofAliasesAndImmutableSnapshots() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementPatchSequencesPrepared();
        metrics.addPatchesPrepared(3L);
        metrics.incrementSingletonPatchTransactions();
        metrics.addSequencePlanningNanos(-1L);
        metrics.addSequenceConformanceNanos(2L);
        metrics.addSequenceCommitNanos(3L);
        metrics.addSequenceFinalCacheCommitNanos(4L);
        metrics.incrementSequenceIntermediateSnapshotAdvances();
        metrics.incrementSequenceSharedSnapshotCacheInserts();
        metrics.incrementSequenceFinalSnapshotCacheInserts();
        metrics.incrementSequenceSuffixRebases();
        metrics.incrementSequenceStalePreviewFallbacks();
        metrics.incrementSequenceFallbackPatches();
        metrics.incrementParsedPointerCacheHits();
        metrics.incrementParsedPointerCacheMisses();
        metrics.incrementFrozenPatchValueHits();
        metrics.incrementPatchValueMaterializations();
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        metrics.incrementPatchSequencesPrepared();
        metrics.addPatchesPrepared(2L);
        metrics.incrementSequenceFinalSnapshotCacheInserts();
        metrics.incrementPatchValueMaterializations();
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        assertLanguageSnapshot(first, 1L, 3L, 1L, 1L);
        assertEquals(0L, first.sequencePlanningNanos);
        assertEquals(2L, first.sequenceConformanceNanos);
        assertEquals(3L, first.sequenceCommitNanos);
        assertEquals(4L, first.sequenceFinalCacheCommitNanos);
        assertEquals(1L, first.languageIntermediateSnapshotAdvances);
        assertEquals(1L, first.sequenceSharedSnapshotCacheInserts);
        assertEquals(1L, first.languageSuffixRebases);
        assertEquals(1L, first.sequenceStalePreviewFallbacks);
        assertEquals(1L, first.languageFallbackPatches);
        assertEquals(1L, first.parsedPointerCacheHits);
        assertEquals(1L, first.parsedPointerCacheMisses);
        assertEquals(1L, first.frozenPatchValueHits);
        assertLanguageSnapshot(second, 2L, 5L, 2L, 2L);
    }

    @Test
    void planAndConversionMetricsUseImmutableSnapshotsAndNonNegativeWeightGauges() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementWorkflowPlansBuilt();
        metrics.incrementWorkflowPlanCacheHits();
        metrics.incrementWorkflowPlanCacheMisses();
        metrics.incrementWorkflowPlanCacheEvictions();
        metrics.addWorkflowPlanWeightBytes(100L);
        metrics.incrementWorkflowExecutorLookups();
        metrics.incrementWorkflowStepResultSnapshotsCreated();
        metrics.incrementWorkflowStepResultViewHits();
        metrics.incrementComputePlansBuilt();
        metrics.incrementComputePlanCacheHits();
        metrics.incrementComputePlanCacheMisses();
        metrics.incrementComputePlanCacheEvictions();
        metrics.addComputePlanWeightBytes(200L);
        metrics.incrementComputeDefinitionMaterializations();
        metrics.incrementComputeDefinitionFrozenDirectHits();
        metrics.incrementComputeProgramSourceBuilds();
        metrics.incrementBexPatchFrozenDirectConversions();
        metrics.incrementBexPatchNodeMaterializations();
        metrics.incrementUpdateStaticTemplatesBuilt();
        metrics.incrementUpdateStaticTemplateHits();
        metrics.incrementUpdateReflectionFallbacks();
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        metrics.addWorkflowPlanWeightBytes(-150L);
        metrics.addComputePlanWeightBytes(-50L);
        metrics.incrementWorkflowPlansBuilt();
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        assertEquals(1L, first.workflowPlansBuilt);
        assertEquals(1L, first.workflowPlanCacheHits);
        assertEquals(1L, first.workflowPlanCacheMisses);
        assertEquals(1L, first.workflowPlanCacheEvictions);
        assertEquals(100L, first.workflowPlanWeightBytes);
        assertEquals(1L, first.workflowExecutorLookups);
        assertEquals(1L, first.workflowStepResultSnapshotsCreated);
        assertEquals(1L, first.workflowStepResultViewHits);
        assertEquals(1L, first.computePlansBuilt);
        assertEquals(1L, first.computePlanCacheHits);
        assertEquals(1L, first.computePlanCacheMisses);
        assertEquals(1L, first.computePlanCacheEvictions);
        assertEquals(200L, first.computePlanWeightBytes);
        assertEquals(1L, first.computeDefinitionMaterializations);
        assertEquals(1L, first.computeDefinitionFrozenDirectHits);
        assertEquals(1L, first.computeProgramSourceBuilds);
        assertEquals(1L, first.bexPatchFrozenDirectConversions);
        assertEquals(1L, first.bexPatchNodeMaterializations);
        assertEquals(1L, first.updateStaticTemplatesBuilt);
        assertEquals(1L, first.updateStaticTemplateHits);
        assertEquals(1L, first.updateReflectionFallbacks);
        assertEquals(2L, second.workflowPlansBuilt);
        assertEquals(0L, second.workflowPlanWeightBytes);
        assertEquals(150L, second.computePlanWeightBytes);
    }

    private static void assertSnapshot(BexProcessingMetrics.Snapshot snapshot,
                                       long attempts,
                                       long builds,
                                       long failures,
                                       long constructionNanos) {
        assertEquals(attempts, snapshot.processEventSnapshotAttempts);
        assertEquals(builds, snapshot.processEventSnapshotBuilds);
        assertEquals(failures, snapshot.processEventSnapshotFailures);
        assertEquals(constructionNanos, snapshot.processEventSnapshotConstructionNanos);
    }

    private static void assertTerminationSnapshot(BexProcessingMetrics.Snapshot snapshot,
                                                  long expected) {
        assertEquals(expected, snapshot.successfulComputeTerminationRequests);
        assertEquals(expected, snapshot.declarativeTerminationSteps);
        assertEquals(expected, snapshot.computeResultValidationFailures);
    }

    private static void assertLanguageSnapshot(BexProcessingMetrics.Snapshot snapshot,
                                               long sequences,
                                               long patches,
                                               long finalPromotions,
                                               long materializations) {
        assertEquals(sequences, snapshot.preparedPatchSequences);
        assertEquals(patches, snapshot.preparedPatches);
        assertEquals(sequences, snapshot.languageSequenceTransactions,
                "the prepared-session count is the closest public transaction proxy");
        assertEquals(1L, snapshot.languageSingletonTransactions);
        assertEquals(finalPromotions, snapshot.languageFinalSnapshotPromotions);
        assertEquals(finalPromotions, snapshot.sequenceFinalSnapshotCacheInserts);
        assertEquals(materializations, snapshot.languagePatchValueMaterializations);
    }
}
