package blue.coordination.examples.support;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.SubscriptionDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Optional exact monotonic timing evidence for one MyOS test JVM. */
final class MyOsOperationTimingRecorder
        implements CoordinationProcessingEngineObserver {

    static final String OUTPUT_PROPERTY = "myos.demo.operationTiming";

    private static final Object MONITOR = new Object();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Map<Path, List<Map<String, Object>>>
            OPERATIONS_BY_DESTINATION = new LinkedHashMap<>();
    private static boolean shutdownHookRegistered;
    private static long runtimeSequence;

    private final boolean enabled;
    private final Path destination;
    private final String exampleId;
    private final String caseId;
    private final String runtimeId;
    private final Map<String, Map<String, Object>> byEntryBlueId =
            new LinkedHashMap<>();
    private final ThreadLocal<DeliveryTiming> activeDelivery =
            new ThreadLocal<>();
    private final LongAdder subscriptionProjectionColdFallbacks =
            new LongAdder();
    private long operationSequence;
    private String nextSampleKind;
    private boolean flushed;

    private MyOsOperationTimingRecorder(
            boolean enabled,
            Path destination,
            String exampleId,
            String caseId,
            String runtimeId) {
        this.enabled = enabled;
        this.destination = destination;
        this.exampleId = exampleId;
        this.caseId = caseId;
        this.runtimeId = runtimeId;
    }

    static MyOsOperationTimingRecorder begin(
            String exampleId,
            String caseId) {
        String output = System.getProperty(OUTPUT_PROPERTY);
        boolean enabled = output != null && !output.trim().isEmpty();
        Path destination = enabled
                ? Paths.get(output).toAbsolutePath().normalize()
                : null;
        synchronized (MONITOR) {
            runtimeSequence++;
            if (enabled) {
                if (!OPERATIONS_BY_DESTINATION.containsKey(destination)) {
                    prepareDestination(destination);
                    OPERATIONS_BY_DESTINATION.put(
                            destination, new ArrayList<>());
                }
                registerShutdownWriter();
            }
            return new MyOsOperationTimingRecorder(
                    enabled,
                    destination,
                    Objects.requireNonNull(exampleId, "exampleId"),
                    Objects.requireNonNull(caseId, "caseId"),
                    exampleId + "/" + caseId + "#timing-"
                            + runtimeSequence);
        }
    }

    void recordAppend(
            MyOsDemoEntry entry,
            long totalNanos,
            long entryBuildNanos,
            long duplicateCheckNanos,
            long eventPrepareSplitAdmissionNanos,
            long journalPublishNanos) {
        if (!enabled) return;
        Map<String, Object> operation = new LinkedHashMap<>();
        operationSequence++;
        operation.put("exampleId", exampleId);
        operation.put("caseId", caseId);
        operation.put("runtimeId", runtimeId);
        operation.put("operationOrdinal", operationSequence);
        operation.put("operation", entry.operation());
        operation.put("entryBlueId", entry.blueId());
        operation.put("timelineId", entry.timelineId());
        if (nextSampleKind != null) {
            operation.put("sampleKind", nextSampleKind);
            nextSampleKind = null;
        }
        operation.put("processObserved", false);
        operation.put("appendTotalNanos", totalNanos);
        Map<String, Long> phases = new LinkedHashMap<>();
        phases.put("entryBuild", entryBuildNanos);
        phases.put("duplicateValidation", duplicateCheckNanos);
        phases.put(
                "eventPrepareSplitAdmission",
                eventPrepareSplitAdmissionNanos);
        phases.put("journalPublish", journalPublishNanos);
        operation.put("appendPhasesNanos", phases);
        operation.put("deliveries", new ArrayList<Map<String, Object>>());
        byEntryBlueId.put(entry.blueId(), operation);
    }

    void recordRouting(
            MyOsDemoEntry entry,
            long validationNanos,
            long routeLookupAndGroupingNanos,
            int affectedRoots) {
        if (!enabled) return;
        Map<String, Object> operation = requireOperation(entry);
        operation.put("processValidationNanos", validationNanos);
        operation.put(
                "routeLookupAndGroupingNanos",
                routeLookupAndGroupingNanos);
        operation.put("affectedRootCount", affectedRoots);
    }

    void beginDelivery(
            MyOsDemoEntry entry,
            String documentKey,
            int occurrenceCount) {
        if (!enabled) return;
        if (activeDelivery.get() != null) {
            throw new IllegalStateException("A timed delivery is already active");
        }
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("documentKey", documentKey);
        delivery.put("occurrenceCount", occurrenceCount);
        delivery.put("preparationThread", Thread.currentThread().getName());
        delivery.put("deliveryStartedNanos", System.nanoTime());
        DeliveryTiming timing = new DeliveryTiming(delivery);
        activeDelivery.set(timing);
        synchronized (this) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deliveries =
                    (List<Map<String, Object>>) requireOperation(entry)
                            .get("deliveries");
            deliveries.add(delivery);
        }
    }

    void endDelivery(long totalNanos) {
        if (!enabled) return;
        DeliveryTiming timing = requireActiveTiming();
        Map<String, Object> delivery = timing.delivery;
        delivery.put("deliveryTotalNanos", totalNanos);
        delivery.put(
                "enginePhasesNanos",
                new LinkedHashMap<>(timing.enginePhases));
        long attributed = 0L;
        for (long phase : timing.enginePhases.values()) {
            attributed = Math.addExact(attributed, phase);
        }
        delivery.put(
                "deliveryUnattributedNanos",
                Math.max(0L, totalNanos - attributed));
        delivery.put("deliveryEndedNanos", System.nanoTime());
        activeDelivery.remove();
    }

    /** Detaches one prepared delivery so its ordered commit may run elsewhere. */
    DeliveryTiming detachDelivery() {
        if (!enabled) return null;
        DeliveryTiming timing = requireActiveTiming();
        timing.delivery.put("preparationEndedNanos", System.nanoTime());
        activeDelivery.remove();
        return timing;
    }

    /** Reattaches a prepared delivery on the deterministic commit thread. */
    void attachDelivery(DeliveryTiming timing) {
        if (!enabled) return;
        if (activeDelivery.get() != null) {
            throw new IllegalStateException("A timed delivery is already active");
        }
        DeliveryTiming checked = Objects.requireNonNull(timing, "timing");
        checked.delivery.put("commitThread", Thread.currentThread().getName());
        checked.delivery.put("commitHostStartedNanos", System.nanoTime());
        activeDelivery.set(checked);
    }

    /** Marks the exact call boundary of authoritative Root publication. */
    void beginCommitPublication() {
        if (!enabled) return;
        DeliveryTiming timing = requireActiveTiming();
        timing.delivery.put("commitStartedNanos", System.nanoTime());
    }

    /** Marks completion of session, route-index, and derived-cache publish. */
    void endCommitPublication() {
        if (!enabled) return;
        DeliveryTiming timing = requireActiveTiming();
        timing.delivery.put("commitEndedNanos", System.nanoTime());
    }

    void endProcess(
            MyOsDemoEntry entry,
            long totalNanos,
            long hostBookkeepingNanos) {
        if (!enabled) return;
        Map<String, Object> operation = requireOperation(entry);
        operation.put("processTotalNanos", totalNanos);
        operation.put("hostBookkeepingNanos", hostBookkeepingNanos);
        long append = ((Number) operation.get("appendTotalNanos"))
                .longValue();
        operation.put(
                "appendAndProcessTotalNanos",
                Math.addExact(append, totalNanos));
        operation.put("processObserved", true);
    }

    void labelNextOperation(String sampleKind) {
        if (!enabled) return;
        String checked = Objects.requireNonNull(
                sampleKind, "sampleKind").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("sampleKind must not be blank");
        }
        if (nextSampleKind != null) {
            throw new IllegalStateException(
                    "The next operation already has timing label "
                            + nextSampleKind);
        }
        nextSampleKind = checked;
    }

    long subscriptionProjectionColdFallbackCount() {
        return subscriptionProjectionColdFallbacks.sum();
    }

    @Override
    public void onIndexedPlanTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("indexedPlan", elapsedNanos);
    }

    @Override
    public void onBundleLoadTiming(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle,
            long elapsedNanos) {
        recordEnginePhase("bundleLoad", elapsedNanos);
        DeliveryTiming timing = activeDelivery.get();
        if (enabled && timing != null) {
            timing.delivery.put("backendBatchCount", bundle.batchCount());
            timing.delivery.put(
                    "backendLoadedIdentityCount",
                    bundle.backendLoadedBlueIds().size());
            timing.delivery.put("loadedBytes", bundle.loadedBytes());
        }
    }

    @Override
    public void onPlatformProcessTiming(
            CoordinationProcessingPlan plan,
            PlatformProcessingResult result,
            long elapsedNanos) {
        recordEnginePhase("contractsProcess", elapsedNanos);
        DeliveryTiming timing = activeDelivery.get();
        if (enabled && timing != null) {
            SubscriptionDelta delta = result.commitCompanion()
                    .subscriptionDelta();
            Map<String, Object> membership = new LinkedHashMap<>();
            membership.put("addedCount", delta.added().size());
            membership.put("removedCount", delta.removed().size());
            membership.put("added", describeMembership(delta.added()));
            membership.put("removed", describeMembership(delta.removed()));
            timing.delivery.put("subscriptionMembershipDelta", membership);
        }
    }

    private static List<Map<String, Object>> describeMembership(
            List<SubscriptionDelta.Entry> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SubscriptionDelta.Entry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scopePath", entry.scopePath());
            row.put("channelKey", entry.channelKey());
            row.put("effectiveTypeBlueId", entry.effectiveTypeBlueId());
            row.put("order", entry.order());
            row.put("subscriptionKeys", entry.subscriptionKeys());
            row.put("activationRootRevision",
                    entry.activationRootRevision());
            row.put("endAtRootRevision", entry.endAtRootRevision());
            result.add(row);
        }
        return result;
    }

    @Override
    public void onProcessInputMaterializationTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("processInputMaterialization", elapsedNanos);
    }

    @Override
    public void onHybridFrontierProofTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("hybridFrontierProof", elapsedNanos);
    }

    @Override
    public void onRetainedReferenceMaterializationTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("retainedReferenceMaterialization", elapsedNanos);
    }

    @Override
    public void onSubscriptionProjectionTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("subscriptionProjection", elapsedNanos);
    }

    @Override
    public void onSubscriptionProjectionColdFallback(
            CoordinationProcessingPlan plan,
            String reason) {
        subscriptionProjectionColdFallbacks.increment();
        DeliveryTiming timing = activeDelivery.get();
        if (enabled && timing != null) {
            timing.delivery.put(
                    "subscriptionProjectionColdFallbackReason",
                    Objects.requireNonNull(reason, "reason"));
        }
    }

    @Override
    public void onFragmentTransitionPlanningTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("fragmentTransitionPlanning", elapsedNanos);
    }

    @Override
    public void onPreparedResultContextTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) {
        recordEnginePhase("preparedResultContext", elapsedNanos);
    }

    @Override
    public void onSubscriptionAndFragmentTransitionTiming(
            CoordinationProcessingPlan plan,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            CoordinationFragmentTransition fragmentTransition,
            long elapsedNanos) {
        DeliveryTiming timing = activeDelivery.get();
        if (enabled && timing != null) {
            timing.delivery.put(
                    "subscriptionAndFragmentTransitionCombinedNanos",
                    elapsedNanos);
        }
    }

    @Override
    public void onProcessComplete(CoordinationTransition transition) {
        DeliveryTiming timing = activeDelivery.get();
        if (!enabled || timing == null) return;
        timing.delivery.put(
                "fallbackReadCount",
                transition.locality().fallbackReadCount());
        timing.delivery.put(
                "forbiddenReadCount",
                transition.locality().forbiddenReadCount());
    }

    @Override
    public void onCommitTiming(
            CoordinationTransition transition,
            CommitOutcome outcome,
            long elapsedNanos) {
        recordEnginePhase("commit", elapsedNanos);
        DeliveryTiming timing = activeDelivery.get();
        if (enabled && timing != null) {
            timing.delivery.put("engineCommitEndedNanos", System.nanoTime());
        }
    }

    synchronized void flush() {
        if (!enabled || flushed) return;
        flushed = true;
        List<Map<String, Object>> completed = new ArrayList<>();
        for (Map<String, Object> operation : byEntryBlueId.values()) {
            completed.add(deepCopy(operation));
        }
        synchronized (MONITOR) {
            OPERATIONS_BY_DESTINATION.get(destination).addAll(completed);
        }
    }

    private static void prepareDestination(Path destination) {
        try {
            Path parent = destination.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.deleteIfExists(destination);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not prepare MyOS operation timing report at "
                            + destination,
                    failure);
        }
    }

    private static void registerShutdownWriter() {
        if (shutdownHookRegistered) return;
        Runtime.getRuntime().addShutdownHook(new Thread(
                MyOsOperationTimingRecorder::writePendingReports,
                "myos-operation-timing-writer"));
        shutdownHookRegistered = true;
    }

    private static void writePendingReports() {
        Map<Path, List<Map<String, Object>>> pending = new LinkedHashMap<>();
        synchronized (MONITOR) {
            OPERATIONS_BY_DESTINATION.forEach((destination, operations) ->
                    pending.put(destination, new ArrayList<>(operations)));
        }
        for (Map.Entry<Path, List<Map<String, Object>>> entry
                : pending.entrySet()) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put(
                    "schema",
                    "blue.coordination/myos-operation-timing/1.1");
            report.put("environment", environmentMetadata());
            List<Map<String, Object>> operations = entry.getValue();
            for (Map<String, Object> operation : operations) {
                enrichOperation(operation);
            }
            report.put("operations", operations);
            try {
                JSON.writeValue(entry.getKey().toFile(), report);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Could not write MyOS operation timing report to "
                                + entry.getKey(),
                        failure);
            }
        }
    }

    private static Map<String, Object> environmentMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("javaVendor", System.getProperty("java.vendor"));
        metadata.put("vmName", System.getProperty("java.vm.name"));
        metadata.put("vmVersion", System.getProperty("java.vm.version"));
        metadata.put("osName", System.getProperty("os.name"));
        metadata.put("osVersion", System.getProperty("os.version"));
        metadata.put("osArch", System.getProperty("os.arch"));
        metadata.put("availableProcessors",
                Runtime.getRuntime().availableProcessors());
        metadata.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("gcCollectors",
                ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .map(bean -> bean.getName())
                        .sorted()
                        .toList());
        metadata.put("junitParallelEnabled", Boolean.parseBoolean(
                System.getProperty(
                        "junit.jupiter.execution.parallel.enabled",
                        "false")));
        metadata.put("performanceGatesEnabled", Boolean.parseBoolean(
                System.getProperty(
                        "coordination.performance.gates", "false")));
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static void enrichOperation(Map<String, Object> operation) {
        Map<String, Long> highLevel = new LinkedHashMap<>();
        copyNanos(operation, highLevel,
                "appendTotalNanos", "append");
        copyNanos(operation, highLevel,
                "processTotalNanos", "processThroughObservableCommits");
        operation.put("highLevelPhasesNanos", highLevel);
        operation.put("highLevelPhasesSeconds", seconds(highLevel));

        Map<String, Long> processDiagnostics = new LinkedHashMap<>();
        copyNanos(operation, processDiagnostics,
                "processValidationNanos", "processValidation");
        copyNanos(operation, processDiagnostics,
                "routeLookupAndGroupingNanos", "routeLookupAndGrouping");
        copyNanos(operation, processDiagnostics,
                "hostBookkeepingNanos", "hostBookkeeping");
        operation.put("processDiagnosticPhasesNanos", processDiagnostics);
        operation.put(
                "processDiagnosticPhasesSeconds",
                seconds(processDiagnostics));

        Map<String, Long> totals = new LinkedHashMap<>();
        copyNanos(operation, totals,
                "processTotalNanos", "processThroughObservableCommits");
        copyNanos(operation, totals,
                "appendAndProcessTotalNanos",
                "appendThroughObservableCommits");
        operation.put("operationTotalsNanos", totals);
        operation.put("operationTotalsSeconds", seconds(totals));

        Object rawDeliveries = operation.get("deliveries");
        if (!(rawDeliveries instanceof List)) return;
        Map<String, Long> phaseTotals = new LinkedHashMap<>();
        for (Object rawDelivery : (List<?>) rawDeliveries) {
            if (!(rawDelivery instanceof Map)) continue;
            Map<String, Object> delivery = (Map<String, Object>) rawDelivery;
            long started = number(delivery, "deliveryStartedNanos");
            long prepared = number(delivery, "preparationEndedNanos");
            long commitHostStarted = number(
                    delivery, "commitHostStartedNanos");
            long commitStarted = number(delivery, "commitStartedNanos");
            long commitEnded = number(delivery, "commitEndedNanos");
            long ended = number(delivery, "deliveryEndedNanos");
            Map<String, Long> wall = new LinkedHashMap<>();
            wall.put("preparation", difference(prepared, started));
            wall.put("canonicalCommitQueueWait",
                    difference(commitHostStarted, prepared));
            wall.put("preCommitBookkeeping",
                    difference(commitStarted, commitHostStarted));
            wall.put("commitPublication",
                    difference(commitEnded, commitStarted));
            wall.put("postCommitBookkeeping",
                    difference(ended, commitEnded));
            delivery.put("wallPhasesNanos", wall);
            delivery.put("wallPhasesSeconds", seconds(wall));
            Object rawPhases = delivery.get("enginePhasesNanos");
            if (rawPhases instanceof Map) {
                ((Map<?, ?>) rawPhases).forEach((phase, nanos) -> {
                    if (phase instanceof String && nanos instanceof Number) {
                        phaseTotals.merge((String) phase,
                                ((Number) nanos).longValue(), Math::addExact);
                    }
                });
            }
        }
        operation.put("rootEnginePhaseTotalsNanos", phaseTotals);
        operation.put("rootEnginePhaseTotalsSeconds", seconds(phaseTotals));
        operation.put(
                "rootEnginePhaseTotalsAccounting",
                "sum-across-roots; parallel root phases may overlap "
                        + "in wall time");
    }

    private static void copyNanos(
            Map<String, Object> source,
            Map<String, Long> destination,
            String sourceName,
            String destinationName) {
        Object value = source.get(sourceName);
        if (value instanceof Number) {
            destination.put(destinationName, ((Number) value).longValue());
        }
    }

    private static Map<String, Double> seconds(Map<String, Long> nanos) {
        Map<String, Double> result = new LinkedHashMap<>();
        nanos.forEach((name, value) ->
                result.put(name, value / 1_000_000_000.0d));
        return result;
    }

    private static long number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static long difference(long after, long before) {
        return after > 0L && before > 0L
                ? Math.max(0L, after - before)
                : 0L;
    }

    private void recordEnginePhase(String phase, long elapsedNanos) {
        DeliveryTiming timing = activeDelivery.get();
        if (!enabled || timing == null) return;
        timing.enginePhases.merge(phase, elapsedNanos, Math::addExact);
    }

    private Map<String, Object> requireOperation(MyOsDemoEntry entry) {
        Map<String, Object> operation = byEntryBlueId.get(entry.blueId());
        if (operation == null) {
            throw new IllegalStateException(
                    "No timing record for entry " + entry.blueId());
        }
        return operation;
    }

    private DeliveryTiming requireActiveTiming() {
        DeliveryTiming timing = activeDelivery.get();
        if (timing == null) {
            throw new IllegalStateException("No timed delivery is active");
        }
        return timing;
    }

    /** Invocation-local phase state transferable from worker to commit thread. */
    static final class DeliveryTiming {
        private final Map<String, Object> delivery;
        private final Map<String, Long> enginePhases = new LinkedHashMap<>();

        private DeliveryTiming(Map<String, Object> delivery) {
            this.delivery = Objects.requireNonNull(delivery, "delivery");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        return JSON.convertValue(source, LinkedHashMap.class);
    }
}
