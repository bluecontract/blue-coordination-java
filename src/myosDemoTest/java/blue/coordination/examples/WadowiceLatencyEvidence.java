package blue.coordination.examples;

import blue.coordination.engine.api.CoordinationFragmentTransitionWorkSnapshot;
import blue.coordination.engine.memory.CoordinationEngineWorkSnapshot;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.engine.fastpath.ReferenceCutMetrics;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsLatencyProbe;
import blue.coordination.examples.support.MyOsMeasuredWork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Raw, machine-readable evidence written outside every measured span. */
final class WadowiceLatencyEvidence {

    static final int REQUIRED_SAMPLE_COUNT = 100;
    static final long SLA_NANOS = 1_000_000_000L;
    static final long MAXIMUM_NANOS = 1_500_000_000L;
    static final long ONE_ROOT_P95_NANOS = 500_000_000L;
    static final long ONE_ROOT_MAXIMUM_NANOS = 900_000_000L;
    static final long TWO_ROOT_P95_NANOS = SLA_NANOS;
    static final long TWO_ROOT_MAXIMUM_NANOS = MAXIMUM_NANOS;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String OUTPUT_DIRECTORY_PROPERTY =
            "myos.demo.latencyEvidenceDir";

    private final String campaign;
    private final String sampleKind;
    private final int requiredSamplesPerOperation;
    private final long maximumNanos;
    private final boolean rootAlignedBudgets;
    private final long campaignMaximumNanos;
    private final List<Map<String, Object>> rawSamples = new ArrayList<>();
    private final List<Map<String, Object>> rawCampaignSamples =
            new ArrayList<>();
    private final long processCpuBefore;
    private final long allocatedBytesBefore;
    private final long garbageCollectionsBefore;
    private final long garbageCollectionMillisBefore;
    private final long heapUsedBefore;

    WadowiceLatencyEvidence(
            String campaign,
            String sampleKind,
            int requiredSamplesPerOperation) {
        this(
                campaign,
                sampleKind,
                requiredSamplesPerOperation,
                Long.MAX_VALUE,
                false,
                Long.MAX_VALUE);
    }

    WadowiceLatencyEvidence(
            String campaign,
            String sampleKind,
            int requiredSamplesPerOperation,
            long maximumNanos) {
        this(
                campaign,
                sampleKind,
                requiredSamplesPerOperation,
                maximumNanos,
                false,
                Long.MAX_VALUE);
    }

    private WadowiceLatencyEvidence(
            String campaign,
            String sampleKind,
            int requiredSamplesPerOperation,
            long maximumNanos,
            boolean rootAlignedBudgets,
            long campaignMaximumNanos) {
        this.campaign = requireText(campaign, "campaign");
        this.sampleKind = requireText(sampleKind, "sampleKind");
        if (requiredSamplesPerOperation <= 0) {
            throw new IllegalArgumentException(
                    "requiredSamplesPerOperation must be positive");
        }
        this.requiredSamplesPerOperation = requiredSamplesPerOperation;
        if (maximumNanos <= 0L) {
            throw new IllegalArgumentException(
                    "maximumNanos must be positive");
        }
        this.maximumNanos = maximumNanos;
        this.rootAlignedBudgets = rootAlignedBudgets;
        if (campaignMaximumNanos <= 0L) {
            throw new IllegalArgumentException(
                    "campaignMaximumNanos must be positive");
        }
        this.campaignMaximumNanos = campaignMaximumNanos;
        processCpuBefore = processCpuNanos();
        allocatedBytesBefore = allocatedBytes();
        garbageCollectionsBefore = garbageCollectionCount();
        garbageCollectionMillisBefore = garbageCollectionMillis();
        heapUsedBefore = heapUsedBytes();
    }

    static WadowiceLatencyEvidence rootAlignedCampaign(
            String campaign,
            String sampleKind,
            int requiredSamplesPerOperation,
            long campaignMaximumNanos) {
        return new WadowiceLatencyEvidence(
                campaign,
                sampleKind,
                requiredSamplesPerOperation,
                TWO_ROOT_MAXIMUM_NANOS,
                true,
                campaignMaximumNanos);
    }

    void addCampaignTotal(int iteration, long elapsedNanos) {
        if (iteration < 0 || elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "iteration and elapsedNanos must be non-negative");
        }
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("iteration", iteration);
        sample.put("elapsedNanos", elapsedNanos);
        sample.put("elapsedSeconds", elapsedNanos / 1_000_000_000.0d);
        rawCampaignSamples.add(sample);
    }

    void add(
            String operation,
            int iteration,
            long elapsedNanos,
            OperationObservation observation) {
        add(
                operation,
                iteration,
                elapsedNanos,
                observation,
                null,
                null);
    }

    void addFirstSeen(
            String operation,
            int iteration,
            long elapsedNanos,
            OperationObservation observation,
            MyOsDemoEntry exactEntry,
            String previousEntryBlueId) {
        add(
                operation,
                iteration,
                elapsedNanos,
                observation,
                Objects.requireNonNull(exactEntry, "exactEntry"),
                requireText(previousEntryBlueId, "previousEntryBlueId"));
    }

    private void add(
            String operation,
            int iteration,
            long elapsedNanos,
            OperationObservation observation,
            MyOsDemoEntry exactEntry,
            String previousEntryBlueId) {
        if (iteration < 0 || elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "iteration and elapsedNanos must be non-negative");
        }
        OperationObservation checked = Objects.requireNonNull(
                observation, "observation");
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("operation", requireText(operation, "operation"));
        sample.put("iteration", iteration);
        sample.put("elapsedNanos", elapsedNanos);
        sample.put("elapsedSeconds", elapsedNanos / 1_000_000_000.0d);
        sample.put("affectedRootCount", checked.affectedRootCount());
        sample.put("processCallCount", checked.work().engine()
                .processCompletions());
        sample.put("totalGas", checked.totalGas());
        sample.put("outboxEventCount", checked.outboxEventCount());
        sample.put("localityFallbackReadCount",
                checked.localityFallbackReadCount());
        sample.put("forbiddenReadCount", checked.forbiddenReadCount());
        sample.put("subscriptionProjectionColdFallbackCount",
                checked.subscriptionProjectionColdFallbackCount());
        sample.put("work", work(checked.work()));
        sample.put("eventAdmission", admission(
                checked.eventAdmission()));
        if (checked.orderRootSparseProof() != null) {
            sample.put("orderRootSparseProof",
                    checked.orderRootSparseProof().evidence());
        }
        if (exactEntry != null) {
            sample.put("exactEventBlueId", exactEntry.blueId());
            sample.put("exactTimestampMicros",
                    exactEntry.timestampMicros());
            sample.put("previousEntryBlueId", previousEntryBlueId);
        }
        rawSamples.add(sample);
    }

    Path write(
            boolean semanticEquivalent,
            Map<String, Object> correctnessReference) {
        Map<String, List<Long>> byOperation = new LinkedHashMap<>();
        Map<String, Integer> rootsByOperation = new LinkedHashMap<>();
        boolean noFallbacks = true;
        boolean orderRootSparsePassed = true;
        int orderRootSparseProofCount = 0;
        for (Map<String, Object> sample : rawSamples) {
            String operation = (String) sample.get("operation");
            long elapsed = ((Number) sample.get("elapsedNanos"))
                    .longValue();
            int affectedRoots = ((Number) sample.get("affectedRootCount"))
                    .intValue();
            byOperation.computeIfAbsent(
                    operation, ignored -> new ArrayList<>()).add(elapsed);
            Integer previousRoots = rootsByOperation.putIfAbsent(
                    operation, affectedRoots);
            if (previousRoots != null
                    && previousRoots.intValue() != affectedRoots) {
                throw new IllegalStateException(
                        "Operation changed affected Root count: "
                                + operation);
            }
            noFallbacks &= zero(sample, "localityFallbackReadCount")
                    && zero(sample, "forbiddenReadCount")
                    && zero(sample,
                            "subscriptionProjectionColdFallbackCount")
                    && zeroWork(sample, "projection",
                            "coldProjectionFallbacks")
                    && zeroWork(sample, "projection",
                            "fullProjectorFallbacks")
                    && zeroWork(sample, "projection",
                            "catalogFallbacks")
                    && zeroWork(sample, "fragmentTransition",
                            "typedFallbackCount")
                    && zeroWork(sample, "fragmentTransition",
                            "fullBlueprintAttempts")
                    && zeroWork(sample, "fragmentTransition",
                            "fullResultClones")
                    && zeroWork(sample, "fragmentTransition",
                            "fullRootMaterializations");
            Object sparseProof = sample.get("orderRootSparseProof");
            if (sparseProof instanceof Map) {
                orderRootSparseProofCount++;
                orderRootSparsePassed &= Boolean.TRUE.equals(
                        ((Map<?, ?>) sparseProof).get("passed"));
            }
        }
        if ("firstSeenExactEvent".equals(sampleKind)) {
            orderRootSparsePassed &= !rawSamples.isEmpty()
                    && orderRootSparseProofCount == rawSamples.size();
        }

        Map<String, Object> summaries = new LinkedHashMap<>();
        boolean completeSampleSet = !byOperation.isEmpty();
        boolean latencyPassed = true;
        for (Map.Entry<String, List<Long>> entry : byOperation.entrySet()) {
            List<Long> samples = Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue()));
            long p95 = MyOsLatencyProbe.percentile(samples, 0.95d);
            long maximum = Collections.max(samples);
            int affectedRoots = rootsByOperation.get(entry.getKey());
            LatencyBudget budget = latencyBudget(affectedRoots);
            boolean operationPassed = budget.supported
                    && p95 <= budget.p95Nanos
                    && maximum <= budget.maximumNanos;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.putAll(distribution(samples));
            summary.put("affectedRootCount", affectedRoots);
            summary.put("slaNanos", budget.p95Nanos);
            summary.put("maximumSlaNanos", budget.maximumNanos);
            summary.put("passed", operationPassed);
            summaries.put(entry.getKey(), summary);
            completeSampleSet &= samples.size()
                    >= requiredSamplesPerOperation;
            latencyPassed &= operationPassed;
        }
        Map<String, Object> campaignSummary = campaignSummary();
        boolean campaignComplete = !rootAlignedBudgets
                || rawCampaignSamples.size() >= requiredSamplesPerOperation;
        boolean campaignLatencyPassed = !rootAlignedBudgets
                || Boolean.TRUE.equals(campaignSummary.get("passed"));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema",
                "blue.coordination/wadowice-latency-campaign/1.0");
        evidence.put("campaign", campaign);
        evidence.put("requiredSamplesPerOperation",
                requiredSamplesPerOperation);
        evidence.put("slaNanos", SLA_NANOS);
        evidence.put("maximumSlaNanos", maximumNanos);
        evidence.put("latencyBudgetPolicy",
                rootAlignedBudgets
                        ? "affected-root-count/1.0"
                        : "uniform/1.0");
        evidence.put("sampleKind", sampleKind);
        evidence.put("workingReady", completeSampleSet
                && latencyPassed
                && semanticEquivalent
                && noFallbacks
                && orderRootSparsePassed
                && campaignComplete
                && campaignLatencyPassed);
        evidence.put("completeSampleSet", completeSampleSet);
        evidence.put("latencyPassed", latencyPassed);
        evidence.put("semanticEquivalent", semanticEquivalent);
        evidence.put("noFallbacks", noFallbacks);
        evidence.put("orderRootSparsePassed", orderRootSparsePassed);
        evidence.put("orderRootSparseProofCount",
                orderRootSparseProofCount);
        evidence.put("campaignComplete", campaignComplete);
        evidence.put("campaignLatencyPassed", campaignLatencyPassed);
        evidence.put("operationTimingStageEvidence",
                System.getProperty("myos.demo.operationTiming"));
        evidence.put("environment", environment());
        evidence.put("resourceDeltas", resourceDeltas());
        evidence.put("correctnessReference",
                new LinkedHashMap<>(Objects.requireNonNull(
                        correctnessReference, "correctnessReference")));
        evidence.put("operationSummaries", summaries);
        evidence.put("rawSamples", new ArrayList<>(rawSamples));
        evidence.put("campaignSummary", campaignSummary);
        evidence.put("rawCampaignSamples",
                new ArrayList<>(rawCampaignSamples));

        Path destination = destination(campaign);
        try {
            Files.createDirectories(destination.getParent());
            JSON.writeValue(destination.toFile(), evidence);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not write latency evidence to " + destination,
                    failure);
        }
        return destination;
    }

    private LatencyBudget latencyBudget(int affectedRoots) {
        if (!rootAlignedBudgets) {
            return new LatencyBudget(true, SLA_NANOS, maximumNanos);
        }
        if (affectedRoots == 1) {
            return new LatencyBudget(
                    true,
                    ONE_ROOT_P95_NANOS,
                    ONE_ROOT_MAXIMUM_NANOS);
        }
        if (affectedRoots == 2) {
            return new LatencyBudget(
                    true,
                    TWO_ROOT_P95_NANOS,
                    TWO_ROOT_MAXIMUM_NANOS);
        }
        return new LatencyBudget(false, 0L, 0L);
    }

    private Map<String, Object> campaignSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", rawCampaignSamples.size());
        result.put("maximumSlaNanos", campaignMaximumNanos);
        if (rawCampaignSamples.isEmpty()) {
            result.put("passed", !rootAlignedBudgets);
            return result;
        }
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> sample : rawCampaignSamples) {
            values.add(((Number) sample.get("elapsedNanos")).longValue());
        }
        long maximum = Collections.max(values);
        result.putAll(distribution(values));
        result.put("passed", maximum <= campaignMaximumNanos);
        return result;
    }

    private static Map<String, Object> distribution(List<Long> values) {
        List<Long> samples = Objects.requireNonNull(values, "values");
        if (samples.isEmpty()) {
            throw new IllegalArgumentException(
                    "distribution requires at least one sample");
        }
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        double mean = 0.0d;
        double sumSquaredDifferences = 0.0d;
        int count = 0;
        for (Long sample : samples) {
            long value = Objects.requireNonNull(sample, "sample");
            if (value < 0L) {
                throw new IllegalArgumentException(
                        "latency samples must be non-negative");
            }
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
            count++;
            double delta = value - mean;
            mean += delta / count;
            sumSquaredDifferences += delta * (value - mean);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", count);
        result.put("minimumNanos", minimum);
        result.put("p50Nanos", MyOsLatencyProbe.percentile(
                samples, 0.50d));
        result.put("p90Nanos", MyOsLatencyProbe.percentile(
                samples, 0.90d));
        result.put("p95Nanos", MyOsLatencyProbe.percentile(
                samples, 0.95d));
        result.put("p99Nanos", MyOsLatencyProbe.percentile(
                samples, 0.99d));
        result.put("maximumNanos", maximum);
        result.put("meanNanos", mean);
        result.put("standardDeviationNanos",
                Math.sqrt(sumSquaredDifferences / count));
        result.put("p95Seconds",
                ((Number) result.get("p95Nanos")).longValue()
                        / 1_000_000_000.0d);
        result.put("maximumSeconds", maximum / 1_000_000_000.0d);
        return result;
    }

    private static Map<String, Object> work(MyOsMeasuredWork measured) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceParses", measured.sourceParses());
        result.put("documentInitializations",
                measured.documentInitializations());
        result.put("eventPreparations", measured.eventPreparations());
        result.put("eventSplits", measured.eventSplits());
        result.put("routeIndexProbes", measured.routeIndexProbes());
        result.put("fanoutPages", measured.fanoutPages());
        result.put("storeSingleReads", measured.storeSingleReads());
        result.put("storeBatchReads", measured.storeBatchReads());
        result.put("storeRequestedIdentities",
                measured.storeRequestedIdentities());
        CoordinationEngineWorkSnapshot engine = measured.engine();
        Map<String, Long> engineWork = new LinkedHashMap<>();
        engineWork.put("plans", engine.plans());
        engineWork.put("bundleLoads", engine.bundleLoads());
        engineWork.put("bundleBatches", engine.bundleBatches());
        engineWork.put("loadedFragmentIdentities",
                engine.loadedFragmentIdentities());
        engineWork.put("loadedBytes", engine.loadedBytes());
        engineWork.put("processCompletions", engine.processCompletions());
        engineWork.put("commitAttempts", engine.commitAttempts());
        engineWork.put("committed", engine.committed());
        engineWork.put("alreadyCommitted", engine.alreadyCommitted());
        engineWork.put("conflicts", engine.conflicts());
        result.put("engine", engineWork);
        ReferenceCutMetrics.Snapshot sparse = measured.referenceCuts();
        Map<String, Object> sparseWork = new LinkedHashMap<>();
        sparseWork.put("compilations", sparse.compilations());
        sparseWork.put("inventoryCompilations",
                sparse.inventoryCompilations());
        sparseWork.put("cacheHits", sparse.cacheHits());
        sparseWork.put("sparseUses", sparse.sparseUses());
        sparseWork.put("fullRootUses", sparse.fullRootUses());
        sparseWork.put("plannedArtifactReuses",
                sparse.plannedArtifactReuses());
        sparseWork.put("plannedArtifactFallbacks",
                sparse.plannedArtifactFallbacks());
        sparseWork.put("plannedArtifactNotApplicable",
                sparse.plannedArtifactNotApplicable());
        sparseWork.put("processRootSelections",
                sparse.processRootSelections());
        sparseWork.put("processActivePaths",
                sparse.processActivePaths());
        sparseWork.put("processInventoryFragments",
                sparse.processInventoryFragments());
        sparseWork.put("processMaterializedFragments",
                sparse.processMaterializedFragments());
        sparseWork.put("processMaterializationFraction",
                sparse.processFragmentMaterializationFraction());
        sparseWork.put("processSparseNodes",
                sparse.processSparseNodes());
        sparseWork.put("cutEdges", sparse.cutEdges());
        sparseWork.put("inventoryFragments", sparse.inventoryFragments());
        sparseWork.put("materializedFragments",
                sparse.materializedFragments());
        sparseWork.put("materializationFraction",
                sparse.fragmentMaterializationFraction());
        sparseWork.put("canonicalFragmentsRead",
                sparse.canonicalFragmentsRead());
        sparseWork.put("fullRootMaterializationsAvoided",
                sparse.fullRootMaterializationsAvoided());
        sparseWork.put("identityChecks", sparse.identityChecks());
        sparseWork.put("identityFailures", sparse.identityFailures());
        sparseWork.put("canonicalBatchReads",
                sparse.canonicalBatchReads());
        sparseWork.put("canonicalSingleReads",
                sparse.canonicalSingleReads());
        sparseWork.put("verifiedHandleBatches",
                sparse.verifiedHandleBatches());
        sparseWork.put("portableCanonicalBatches",
                sparse.portableCanonicalBatches());
        sparseWork.put("cacheMisses", sparse.cacheMisses());
        sparseWork.put("cacheFlightLeaders",
                sparse.cacheFlightLeaders());
        sparseWork.put("cacheFlightWaiters",
                sparse.cacheFlightWaiters());
        sparseWork.put("cacheFailures", sparse.cacheFailures());
        sparseWork.put("cacheEvictions", sparse.cacheEvictions());
        sparseWork.put("cacheLoadNanos", sparse.cacheLoadNanos());
        result.put("referenceCut", sparseWork);

        FastPathWorkMetrics.Snapshot projection = measured.projection();
        Map<String, Long> projectionWork = new LinkedHashMap<>();
        projectionWork.put("admittedProjectionBuilds",
                projection.admittedProjectionBuilds());
        projectionWork.put("admittedOccurrences",
                projection.admittedOccurrences());
        projectionWork.put("candidateLookups",
                projection.candidateLookups());
        projectionWork.put("scopeTraversals",
                projection.scopeTraversals());
        projectionWork.put("rootIdentityCalculations",
                projection.rootIdentityCalculations());
        projectionWork.put("coldProjectionFallbacks",
                projection.coldProjectionFallbacks());
        projectionWork.put("deltaProjectionUpdates",
                projection.deltaProjectionUpdates());
        projectionWork.put("affectedOccurrences",
                projection.affectedOccurrences());
        projectionWork.put("refreshedOccurrences",
                projection.refreshedOccurrences());
        projectionWork.put("unrelatedOccurrences",
                projection.unrelatedOccurrences());
        projectionWork.put("snapshotSerializations",
                projection.snapshotSerializations());
        projectionWork.put("snapshotSerializedOccurrences",
                projection.snapshotSerializedOccurrences());
        projectionWork.put("fullProjectorFallbacks",
                projection.fullProjectorFallbacks());
        projectionWork.put("catalogFallbacks",
                projection.catalogFallbacks());
        projectionWork.put("merkleOccurrenceUpdates",
                projection.merkleOccurrenceUpdates());
        result.put("projection", projectionWork);

        CoordinationFragmentTransitionWorkSnapshot transition =
                measured.fragmentTransition();
        Map<String, Object> transitionWork = new LinkedHashMap<>();
        transitionWork.put("deltaHits", transition.deltaHits());
        transitionWork.put("typedFallbackCount",
                transition.typedFallbackCount());
        transitionWork.put("typedFallbacksByReason",
                transition.typedFallbacksByReason());
        transitionWork.put("fullBlueprintAttempts",
                transition.fullBlueprintAttempts());
        transitionWork.put("sparseFrontierNodes",
                transition.sparseFrontierNodes());
        transitionWork.put("changedFragmentsHashed",
                transition.changedFragmentsHashed());
        transitionWork.put("unchangedFragmentsShared",
                transition.unchangedFragmentsShared());
        transitionWork.put("unchangedFragmentShareRatio",
                transition.unchangedFragmentShareRatio());
        transitionWork.put("fullResultClones",
                transition.fullResultClones());
        transitionWork.put("fullRootMaterializations",
                transition.fullRootMaterializations());
        transitionWork.put("frontierBoundaryGrafts",
                transition.frontierBoundaryGrafts());
        transitionWork.put("expandedNodesVisited",
                transition.expandedNodesVisited());
        transitionWork.put("retainedIndexFullScans",
                transition.retainedIndexFullScans());
        transitionWork.put("inventoryRecordsReused",
                transition.inventoryRecordsReused());
        transitionWork.put("inventoryRecordsRebuilt",
                transition.inventoryRecordsRebuilt());
        transitionWork.put("edgeRecordsReused",
                transition.edgeRecordsReused());
        transitionWork.put("edgeRecordsRebuilt",
                transition.edgeRecordsRebuilt());
        result.put("fragmentTransition", transitionWork);
        return result;
    }

    private static Map<String, Long> admission(
            CoordinationEventAdmissionMetrics.Snapshot snapshot) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("templateHits", snapshot.templateHits());
        result.put("templateMisses", snapshot.templateMisses());
        result.put("templateCompilations", snapshot.templateCompilations());
        result.put("fullEventSplits", snapshot.fullEventSplits());
        result.put("admittedFragments", snapshot.admittedFragments());
        result.put("reusedFragments", snapshot.reusedFragments());
        result.put("wireFingerprints", snapshot.wireFingerprints());
        result.put("fragmentEvidenceHits", snapshot.fragmentEvidenceHits());
        result.put("fragmentEvidenceMisses",
                snapshot.fragmentEvidenceMisses());
        result.put("blueIdCalculations", snapshot.blueIdCalculations());
        result.put("winnerReadBacks", snapshot.winnerReadBacks());
        result.put("nodeMaterializations", snapshot.nodeMaterializations());
        return result;
    }

    private Map<String, Object> environment() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("vmName", System.getProperty("java.vm.name"));
        result.put("osName", System.getProperty("os.name"));
        result.put("osVersion", System.getProperty("os.version"));
        result.put("osArch", System.getProperty("os.arch"));
        result.put("availableProcessors",
                Runtime.getRuntime().availableProcessors());
        result.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("junitParallelEnabled", Boolean.parseBoolean(
                System.getProperty(
                        "junit.jupiter.execution.parallel.enabled",
                        "false")));
        result.put("performanceGatesEnabled", Boolean.parseBoolean(
                System.getProperty(
                        "coordination.performance.gates", "false")));
        return result;
    }

    private Map<String, Long> resourceDeltas() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("processCpuNanos", nonNegativeDifference(
                processCpuNanos(), processCpuBefore));
        result.put("threadAllocatedBytes", nonNegativeDifference(
                allocatedBytes(), allocatedBytesBefore));
        result.put("garbageCollectionCount", nonNegativeDifference(
                garbageCollectionCount(), garbageCollectionsBefore));
        result.put("garbageCollectionMillis", nonNegativeDifference(
                garbageCollectionMillis(), garbageCollectionMillisBefore));
        result.put("heapUsedBytesDelta",
                heapUsedBytes() - heapUsedBefore);
        return result;
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean)
                    .getProcessCpuTime();
        }
        return -1L;
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean =
                ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) {
            return -1L;
        }
        com.sun.management.ThreadMXBean allocation =
                (com.sun.management.ThreadMXBean) bean;
        if (!allocation.isThreadAllocatedMemorySupported()) {
            return -1L;
        }
        if (!allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        long total = 0L;
        long[] values = allocation.getThreadAllocatedBytes(
                allocation.getAllThreadIds());
        for (long value : values) {
            if (value > 0L) total = Math.addExact(total, value);
        }
        return total;
    }

    private static long garbageCollectionCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0L) {
                total = Math.addExact(total, bean.getCollectionCount());
            }
        }
        return total;
    }

    private static long garbageCollectionMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionTime() >= 0L) {
                total = Math.addExact(total, bean.getCollectionTime());
            }
        }
        return total;
    }

    private static long heapUsedBytes() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return memory.getHeapMemoryUsage().getUsed();
    }

    private static long nonNegativeDifference(long after, long before) {
        return after < 0L || before < 0L ? -1L : Math.max(0L, after - before);
    }

    private static boolean zero(Map<String, Object> sample, String name) {
        return ((Number) sample.get(name)).longValue() == 0L;
    }

    private static boolean zeroWork(
            Map<String, Object> sample,
            String component,
            String field) {
        Object suppliedWork = sample.get("work");
        if (!(suppliedWork instanceof Map)) {
            return false;
        }
        Object suppliedComponent = ((Map<?, ?>) suppliedWork).get(component);
        if (!(suppliedComponent instanceof Map)) {
            return false;
        }
        Object value = ((Map<?, ?>) suppliedComponent).get(field);
        return value instanceof Number
                && ((Number) value).longValue() == 0L;
    }

    private static Path destination(String campaign) {
        String configured = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
        Path directory = configured == null || configured.isBlank()
                ? Paths.get("build", "reports", "myos-demo-examples")
                : Paths.get(configured);
        String file = campaign.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                + ".json";
        return directory.toAbsolutePath().normalize().resolve(file);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static final class LatencyBudget {
        private final boolean supported;
        private final long p95Nanos;
        private final long maximumNanos;

        private LatencyBudget(
                boolean supported,
                long p95Nanos,
                long maximumNanos) {
            this.supported = supported;
            this.p95Nanos = p95Nanos;
            this.maximumNanos = maximumNanos;
        }
    }

    record OperationObservation(
            int affectedRootCount,
            long totalGas,
            int outboxEventCount,
            long localityFallbackReadCount,
            long forbiddenReadCount,
            long subscriptionProjectionColdFallbackCount,
            MyOsMeasuredWork work,
            OrderRootSparseProof orderRootSparseProof,
            CoordinationEventAdmissionMetrics.Snapshot eventAdmission) {

        OperationObservation {
            if (affectedRootCount < 0
                    || totalGas < 0L
                    || outboxEventCount < 0
                    || localityFallbackReadCount < 0L
                    || forbiddenReadCount < 0L
                    || subscriptionProjectionColdFallbackCount < 0L) {
                throw new IllegalArgumentException(
                        "operation observations must be non-negative");
            }
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(eventAdmission, "eventAdmission");
        }
    }

    /**
     * Conservative per-Order proof that cannot be diluted by another Root.
     *
     * <p>The numerator is all materialized PROCESS fragments across the
     * affected Roots. The Order Root's count cannot exceed that value, so a
     * passing upper bound proves the Order-only limit even if the paired
     * PayNote Root contributes a large unused inventory.</p>
     */
    record OrderRootSparseProof(
            String documentKey,
            String sessionId,
            String rootBlueId,
            String inventoryIdentity,
            long inventoryFragmentCount,
            long allRootMaterializedFragmentUpperBound) {

        private static final double MAXIMUM_FRACTION = 0.20d;

        OrderRootSparseProof {
            requireText(documentKey, "documentKey");
            requireText(sessionId, "sessionId");
            requireText(rootBlueId, "rootBlueId");
            requireText(inventoryIdentity, "inventoryIdentity");
            if (inventoryFragmentCount <= 0L
                    || allRootMaterializedFragmentUpperBound < 0L) {
                throw new IllegalArgumentException(
                        "Order sparse proof counts are invalid");
            }
        }

        double maximumPossibleMaterializationFraction() {
            return allRootMaterializedFragmentUpperBound
                    / (double) inventoryFragmentCount;
        }

        private Map<String, Object> evidence() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentKey", documentKey);
            result.put("sessionId", sessionId);
            result.put("rootBlueId", rootBlueId);
            result.put("inventoryIdentity", inventoryIdentity);
            result.put("inventoryFragmentCount", inventoryFragmentCount);
            result.put("allRootMaterializedFragmentUpperBound",
                    allRootMaterializedFragmentUpperBound);
            result.put("maximumPossibleMaterializationFraction",
                    maximumPossibleMaterializationFraction());
            result.put("maximumAllowedFraction", MAXIMUM_FRACTION);
            result.put("passed",
                    maximumPossibleMaterializationFraction()
                            <= MAXIMUM_FRACTION);
            return result;
        }
    }
}
