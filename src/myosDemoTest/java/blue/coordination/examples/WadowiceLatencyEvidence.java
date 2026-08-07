package blue.coordination.examples;

import blue.coordination.engine.memory.CoordinationEngineWorkSnapshot;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
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

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String OUTPUT_DIRECTORY_PROPERTY =
            "myos.demo.latencyEvidenceDir";

    private final String campaign;
    private final String sampleKind;
    private final int requiredSamplesPerOperation;
    private final List<Map<String, Object>> rawSamples = new ArrayList<>();
    private final long processCpuBefore;
    private final long allocatedBytesBefore;
    private final long garbageCollectionsBefore;
    private final long garbageCollectionMillisBefore;
    private final long heapUsedBefore;

    WadowiceLatencyEvidence(
            String campaign,
            String sampleKind,
            int requiredSamplesPerOperation) {
        this.campaign = requireText(campaign, "campaign");
        this.sampleKind = requireText(sampleKind, "sampleKind");
        if (requiredSamplesPerOperation <= 0) {
            throw new IllegalArgumentException(
                    "requiredSamplesPerOperation must be positive");
        }
        this.requiredSamplesPerOperation = requiredSamplesPerOperation;
        processCpuBefore = processCpuNanos();
        allocatedBytesBefore = allocatedBytes();
        garbageCollectionsBefore = garbageCollectionCount();
        garbageCollectionMillisBefore = garbageCollectionMillis();
        heapUsedBefore = heapUsedBytes();
    }

    void add(
            String operation,
            int iteration,
            long elapsedNanos,
            OperationObservation observation) {
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
        rawSamples.add(sample);
    }

    Path write(
            boolean semanticEquivalent,
            Map<String, Object> correctnessReference) {
        Map<String, List<Long>> byOperation = new LinkedHashMap<>();
        boolean noFallbacks = true;
        for (Map<String, Object> sample : rawSamples) {
            String operation = (String) sample.get("operation");
            long elapsed = ((Number) sample.get("elapsedNanos"))
                    .longValue();
            byOperation.computeIfAbsent(
                    operation, ignored -> new ArrayList<>()).add(elapsed);
            noFallbacks &= zero(sample, "localityFallbackReadCount")
                    && zero(sample, "forbiddenReadCount")
                    && zero(sample,
                            "subscriptionProjectionColdFallbackCount");
        }

        Map<String, Object> summaries = new LinkedHashMap<>();
        boolean completeSampleSet = !byOperation.isEmpty();
        boolean latencyPassed = true;
        for (Map.Entry<String, List<Long>> entry : byOperation.entrySet()) {
            List<Long> samples = Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue()));
            long p95 = MyOsLatencyProbe.percentile(samples, 0.95d);
            long maximum = Collections.max(samples);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sampleCount", samples.size());
            summary.put("p95Nanos", p95);
            summary.put("p95Seconds", p95 / 1_000_000_000.0d);
            summary.put("maximumNanos", maximum);
            summary.put("maximumSeconds", maximum / 1_000_000_000.0d);
            summary.put("slaNanos", SLA_NANOS);
            summary.put("passed", p95 <= SLA_NANOS);
            summaries.put(entry.getKey(), summary);
            completeSampleSet &= samples.size()
                    >= requiredSamplesPerOperation;
            latencyPassed &= p95 <= SLA_NANOS;
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema",
                "blue.coordination/wadowice-latency-campaign/1.0");
        evidence.put("campaign", campaign);
        evidence.put("requiredSamplesPerOperation",
                requiredSamplesPerOperation);
        evidence.put("slaNanos", SLA_NANOS);
        evidence.put("sampleKind", sampleKind);
        evidence.put("workingReady", completeSampleSet
                && latencyPassed
                && semanticEquivalent
                && noFallbacks);
        evidence.put("completeSampleSet", completeSampleSet);
        evidence.put("latencyPassed", latencyPassed);
        evidence.put("semanticEquivalent", semanticEquivalent);
        evidence.put("noFallbacks", noFallbacks);
        evidence.put("operationTimingStageEvidence",
                System.getProperty("myos.demo.operationTiming"));
        evidence.put("environment", environment());
        evidence.put("resourceDeltas", resourceDeltas());
        evidence.put("correctnessReference",
                new LinkedHashMap<>(Objects.requireNonNull(
                        correctnessReference, "correctnessReference")));
        evidence.put("operationSummaries", summaries);
        evidence.put("rawSamples", new ArrayList<>(rawSamples));

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

    record OperationObservation(
            int affectedRootCount,
            long totalGas,
            int outboxEventCount,
            long localityFallbackReadCount,
            long forbiddenReadCount,
            long subscriptionProjectionColdFallbackCount,
            MyOsMeasuredWork work,
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
}
