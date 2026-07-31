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
    void shouldRecordConcurrentLanguageMetricAdditionsSafely() throws Exception {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        int workers = 8;
        int additionsPerWorker = 2_000;

        // When
        recordConcurrentAdditions(metrics, workers, additionsPerWorker);

        // Then
        assertEquals((long) workers * additionsPerWorker,
                metrics.snapshot().languageCounters.get("concurrent.additions"));
    }

    @Test
    void shouldExposeLanguageMetricsInSortedImmutableSnapshots() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.addMetric("zulu", 3L);
        metrics.addMetric("alpha", 2L);
        metrics.setMetric("cache.plan.entries", 7L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 11L);

        // When
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // Then
        assertEquals(Arrays.asList("alpha", "zulu"),
                new ArrayList<>(snapshot.languageCounters.keySet()));
        assertEquals(7L, snapshot.languageGauges.get("cache.plan.entries"));
        assertEquals(11L,
                snapshot.languageHighWaterMarks.get("cache.plan.highWaterBytes"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languageCounters.put("later", 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languageGauges.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.languageHighWaterMarks.remove("cache.plan.highWaterBytes"));
    }

    @Test
    void shouldKeepLanguageMetricSnapshotsStableAfterLaterUpdates() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.addMetric("alpha", 2L);
        metrics.setMetric("cache.plan.entries", 7L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 11L);
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // When
        metrics.addMetric("alpha", 5L);
        metrics.setMetric("cache.plan.entries", 9L);
        metrics.recordMetricHighWater("cache.plan.highWaterBytes", 13L);

        // Then
        assertEquals(2L, snapshot.languageCounters.get("alpha"));
        assertEquals(7L, snapshot.languageGauges.get("cache.plan.entries"));
        assertEquals(11L,
                snapshot.languageHighWaterMarks.get("cache.plan.highWaterBytes"));
    }

    private static void recordConcurrentAdditions(BexProcessingMetrics metrics,
                                                  int workers,
                                                  int additionsPerWorker) throws Exception {
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
    }

    @Test
    void shouldRetainLanguageMetricSuffixesAndCacheMetricKinds() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
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

        // Then
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
    void shouldCapLanguageMetricNamesAcrossMetricKinds() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        for (int index = 0; index < BexProcessingMetrics.MAX_LANGUAGE_METRIC_NAMES; index++) {
            metrics.addMetric("bounded." + index, 1L);
        }

        // When
        metrics.setMetric("bounded.0", 7L);
        metrics.setMetric("overflow.gauge", 9L);
        metrics.recordMetricHighWater("overflow.highWater", 11L);
        metrics.addMetric("overflow.counter", 1L);
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // Then
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
    void shouldExposeProcessEventSnapshotCounters() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.incrementProcessEventSnapshotFailures();
        metrics.addProcessEventSnapshotConstructionNanos(-1L);

        // Then
        assertEquals(1L, metrics.processEventSnapshotAttempts());
        assertEquals(1L, metrics.processEventSnapshotBuilds());
        assertEquals(1L, metrics.processEventSnapshotFailures());
        assertEquals(0L, metrics.processEventSnapshotConstructionNanos(),
                "host-provided timing samples must be bounded at zero");
    }

    @Test
    void shouldAccumulateProcessEventMetricsWithoutMutatingEarlierSnapshots() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.addProcessEventSnapshotConstructionNanos(11L);
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        // When
        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.incrementProcessEventSnapshotFailures();
        metrics.addProcessEventSnapshotConstructionNanos(13L);
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        // Then
        assertSnapshot(first, 1L, 1L, 0L, 11L);
        assertSnapshot(second, 2L, 2L, 1L, 24L);
    }

    @Test
    void shouldAccumulateTerminationCountersWithoutMutatingEarlierSnapshots() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementSuccessfulComputeTerminationRequests();
        metrics.incrementDeclarativeTerminationSteps();
        metrics.incrementComputeResultValidationFailures();
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        // When
        metrics.incrementSuccessfulComputeTerminationRequests();
        metrics.incrementDeclarativeTerminationSteps();
        metrics.incrementComputeResultValidationFailures();
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        // Then
        assertTerminationSnapshot(first, 1L);
        assertTerminationSnapshot(second, 2L);
    }

    @Test
    void shouldExposeLanguageSequenceAliasesWithoutMutatingEarlierSnapshots() {
        // Given
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

        // When
        metrics.incrementPatchSequencesPrepared();
        metrics.addPatchesPrepared(2L);
        metrics.incrementSequenceFinalSnapshotCacheInserts();
        metrics.incrementPatchValueMaterializations();
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        // Then
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
    void shouldExposeWorkflowPlanMetrics() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementWorkflowPlansBuilt();
        metrics.incrementWorkflowPlanCacheHits();
        metrics.incrementWorkflowPlanCacheMisses();
        metrics.incrementWorkflowPlanCacheEvictions();
        metrics.addWorkflowPlanWeightBytes(100L);
        metrics.incrementWorkflowExecutorLookups();
        metrics.incrementWorkflowStepResultSnapshotsCreated();
        metrics.incrementWorkflowStepResultViewHits();

        // When
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // Then
        assertEquals(1L, snapshot.workflowPlansBuilt);
        assertEquals(1L, snapshot.workflowPlanCacheHits);
        assertEquals(1L, snapshot.workflowPlanCacheMisses);
        assertEquals(1L, snapshot.workflowPlanCacheEvictions);
        assertEquals(100L, snapshot.workflowPlanWeightBytes);
        assertEquals(1L, snapshot.workflowExecutorLookups);
        assertEquals(1L, snapshot.workflowStepResultSnapshotsCreated);
        assertEquals(1L, snapshot.workflowStepResultViewHits);
    }

    @Test
    void shouldExposeComputePlanMetrics() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementComputePlansBuilt();
        metrics.incrementComputePlanCacheHits();
        metrics.incrementComputePlanCacheMisses();
        metrics.incrementComputePlanCacheEvictions();
        metrics.addComputePlanWeightBytes(200L);
        metrics.incrementComputeDefinitionMaterializations();
        metrics.incrementComputeDefinitionFrozenDirectHits();
        metrics.incrementComputeProgramSourceBuilds();

        // When
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // Then
        assertEquals(1L, snapshot.computePlansBuilt);
        assertEquals(1L, snapshot.computePlanCacheHits);
        assertEquals(1L, snapshot.computePlanCacheMisses);
        assertEquals(1L, snapshot.computePlanCacheEvictions);
        assertEquals(200L, snapshot.computePlanWeightBytes);
        assertEquals(1L, snapshot.computeDefinitionMaterializations);
        assertEquals(1L, snapshot.computeDefinitionFrozenDirectHits);
        assertEquals(1L, snapshot.computeProgramSourceBuilds);
    }

    @Test
    void shouldExposeConversionAndStaticUpdateMetrics() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementBexPatchFrozenDirectConversions();
        metrics.incrementBexPatchNodeMaterializations();
        metrics.incrementUpdateStaticTemplatesBuilt();
        metrics.incrementUpdateStaticTemplateHits();
        metrics.incrementUpdateReflectionFallbacks();

        // When
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // Then
        assertEquals(1L, snapshot.bexPatchFrozenDirectConversions);
        assertEquals(1L, snapshot.bexPatchNodeMaterializations);
        assertEquals(1L, snapshot.updateStaticTemplatesBuilt);
        assertEquals(1L, snapshot.updateStaticTemplateHits);
        assertEquals(1L, snapshot.updateReflectionFallbacks);
    }

    @Test
    void shouldClampPlanWeightGaugesAtZero() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.addWorkflowPlanWeightBytes(100L);
        metrics.addComputePlanWeightBytes(200L);

        // When
        metrics.addWorkflowPlanWeightBytes(-150L);
        metrics.addComputePlanWeightBytes(-250L);
        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        // Then
        assertEquals(0L, snapshot.workflowPlanWeightBytes);
        assertEquals(0L, snapshot.computePlanWeightBytes);
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
