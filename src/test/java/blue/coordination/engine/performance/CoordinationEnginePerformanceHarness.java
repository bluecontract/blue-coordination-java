package blue.coordination.engine.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Strict bounded collector for engine performance evidence.
 *
 * <p>The collector never estimates a missing phase or physical metric. Every
 * required matrix cell must either provide an authoritative value or retain
 * an explicit unavailable reason. Semantic equality is checked before any
 * comparison can become eligible.</p>
 */
final class CoordinationEnginePerformanceHarness {

    static final String SCHEMA =
            "blue.coordination/engine-performance-evidence/1.0";
    static final int REQUIRED_CELL_COUNT = 54;

    enum Scenario {
        SIMPLE_ROOT_EVENT("simple-root-event"),
        SELECTED_DEPTH_TWO("selected-depth-2"),
        DEEP_A25_EVENT("deep-a25-event"),
        COMPOSITE_CHANNEL_EVENT("composite-channel-event"),
        ALL_TIMELINES_CHANNEL_EVENT("all-timelines-channel-event"),
        DOCUMENT_UPDATE_CASCADE("document-update-cascade"),
        TRIGGERED_EVENT_CASCADE("triggered-event-cascade"),
        COLLECTION_MEMBER_LIFECYCLE("collection-member-add-remove-readd"),
        TEN_CONSECUTIVE_DEEP_EVENTS("10-consecutive-deep-events");

        private final String id;

        Scenario(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    enum ComparisonMode {
        FRAGMENT_NATIVE_INDEXED("fragment-native-indexed"),
        CURRENT_ROOT_COMPATIBILITY("current-root-compatibility"),
        FULL_INLINE_CONTROL("full-inline-control");

        private final String id;

        ComparisonMode(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    enum CacheState {
        COLD("cold"),
        WARM("warm");

        private final String id;

        CacheState(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    enum Phase {
        PLAN("plan"),
        BUNDLE_LOAD("bundle-load"),
        PROCESS("process"),
        FRAGMENT_TRANSITION("fragment-transition"),
        COMMIT("commit"),
        END_TO_END("end-to-end");

        private final String id;

        Phase(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    enum Metric {
        PROVIDER_REQUEST_COUNT("provider-request-count"),
        BATCH_COUNT("batch-count"),
        FALLBACK_COUNT("fallback-count"),
        LOADED_BYTES("loaded-bytes"),
        MATERIALIZED_NODE_COUNT("materialized-node-count"),
        SELECTED_BODY_COUNT("selected-body-count"),
        ALLOCATION_BYTES("allocation-bytes"),
        RETAINED_HEAP_BYTES("retained-heap-bytes");

        private final String id;

        Metric(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    interface ScenarioAdapter extends AutoCloseable {
        void warmUp(CellKey cell, int iteration) throws Exception;

        Sample measure(CellKey cell, int iteration) throws Exception;

        @Override
        default void close() throws Exception {
        }
    }

    static final class CellKey implements Comparable<CellKey> {
        private final Scenario scenario;
        private final ComparisonMode mode;
        private final CacheState cache;

        CellKey(
                Scenario scenario,
                ComparisonMode mode,
                CacheState cache) {
            this.scenario = Objects.requireNonNull(scenario, "scenario");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.cache = Objects.requireNonNull(cache, "cache");
        }

        Scenario scenario() {
            return scenario;
        }

        ComparisonMode mode() {
            return mode;
        }

        CacheState cache() {
            return cache;
        }

        String id() {
            return scenario.id() + "/" + mode.id() + "/" + cache.id();
        }

        @Override
        public int compareTo(CellKey other) {
            return id().compareTo(Objects.requireNonNull(other, "other").id());
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || (other instanceof CellKey
                    && id().equals(((CellKey) other).id()));
        }

        @Override
        public int hashCode() {
            return id().hashCode();
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("id", id());
            result.put("scenario", scenario.id());
            result.put("comparisonMode", mode.id());
            result.put("cache", cache.id());
            return result;
        }
    }

    static final class SemanticFingerprint {
        private final String status;
        private final String finalRootBlueId;
        private final String finalRootValueSha256;
        private final String rootEventsSha256;
        private final long gas;
        private final String namedTraceSha256;
        private final String checkpointsSha256;
        private final String subscriptionDeltaSha256;

        SemanticFingerprint(
                String status,
                String finalRootBlueId,
                String finalRootValueSha256,
                String rootEventsSha256,
                long gas,
                String namedTraceSha256,
                String checkpointsSha256,
                String subscriptionDeltaSha256) {
            this.status = requireText(status, "status");
            this.finalRootBlueId = requireText(
                    finalRootBlueId, "finalRootBlueId");
            this.finalRootValueSha256 = requireText(
                    finalRootValueSha256, "finalRootValueSha256");
            this.rootEventsSha256 = requireText(
                    rootEventsSha256, "rootEventsSha256");
            if (gas < 0L) {
                throw new IllegalArgumentException("gas must be non-negative");
            }
            this.gas = gas;
            this.namedTraceSha256 = requireText(
                    namedTraceSha256, "namedTraceSha256");
            this.checkpointsSha256 = requireText(
                    checkpointsSha256, "checkpointsSha256");
            this.subscriptionDeltaSha256 = requireText(
                    subscriptionDeltaSha256,
                    "subscriptionDeltaSha256");
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", status);
            result.put("finalRootBlueId", finalRootBlueId);
            result.put("finalRootValueSha256", finalRootValueSha256);
            result.put("rootEventsSha256", rootEventsSha256);
            result.put("gas", gas);
            result.put("namedTraceSha256", namedTraceSha256);
            result.put("checkpointsSha256", checkpointsSha256);
            result.put(
                    "subscriptionDeltaSha256",
                    subscriptionDeltaSha256);
            return result;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || (other instanceof SemanticFingerprint
                    && toMap().equals(
                    ((SemanticFingerprint) other).toMap()));
        }

        @Override
        public int hashCode() {
            return toMap().hashCode();
        }
    }

    static final class Sample {
        private final String datasetSha256;
        private final SemanticFingerprint semantics;
        private final Map<Phase, Long> phaseNanos;
        private final Map<Phase, String> unavailablePhases;
        private final Map<Metric, Long> metrics;
        private final Map<Metric, String> unavailableMetrics;

        private Sample(Builder builder) {
            datasetSha256 = requireText(
                    builder.datasetSha256, "datasetSha256");
            semantics = Objects.requireNonNull(
                    builder.semantics, "semantics");
            phaseNanos = immutableValues(
                    builder.phaseNanos, "phaseNanos");
            unavailablePhases = immutableReasons(
                    builder.unavailablePhases,
                    "unavailablePhases");
            metrics = immutableValues(builder.metrics, "metrics");
            unavailableMetrics = immutableReasons(
                    builder.unavailableMetrics,
                    "unavailableMetrics");
            requireCoverage(
                    Phase.values(),
                    phaseNanos,
                    unavailablePhases,
                    "phase");
            requireCoverage(
                    Metric.values(),
                    metrics,
                    unavailableMetrics,
                    "metric");
        }

        static Builder builder(
                String datasetSha256,
                SemanticFingerprint semantics) {
            return new Builder(datasetSha256, semantics);
        }

        String datasetSha256() {
            return datasetSha256;
        }

        SemanticFingerprint semantics() {
            return semantics;
        }

        Map<Phase, Long> phaseNanos() {
            return phaseNanos;
        }

        Map<Phase, String> unavailablePhases() {
            return unavailablePhases;
        }

        Map<Metric, Long> metrics() {
            return metrics;
        }

        Map<Metric, String> unavailableMetrics() {
            return unavailableMetrics;
        }

        static final class Builder {
            private final String datasetSha256;
            private final SemanticFingerprint semantics;
            private final Map<Phase, Long> phaseNanos =
                    new EnumMap<Phase, Long>(Phase.class);
            private final Map<Phase, String> unavailablePhases =
                    new EnumMap<Phase, String>(Phase.class);
            private final Map<Metric, Long> metrics =
                    new EnumMap<Metric, Long>(Metric.class);
            private final Map<Metric, String> unavailableMetrics =
                    new EnumMap<Metric, String>(Metric.class);

            private Builder(
                    String datasetSha256,
                    SemanticFingerprint semantics) {
                this.datasetSha256 = datasetSha256;
                this.semantics = semantics;
            }

            Builder phase(Phase phase, long nanos) {
                putAvailable(
                        phaseNanos,
                        unavailablePhases,
                        phase,
                        nanos,
                        "phase");
                return this;
            }

            Builder unavailable(Phase phase, String reason) {
                putUnavailable(
                        phaseNanos,
                        unavailablePhases,
                        phase,
                        reason,
                        "phase");
                return this;
            }

            Builder metric(Metric metric, long value) {
                putAvailable(
                        metrics,
                        unavailableMetrics,
                        metric,
                        value,
                        "metric");
                return this;
            }

            Builder unavailable(Metric metric, String reason) {
                putUnavailable(
                        metrics,
                        unavailableMetrics,
                        metric,
                        reason,
                        "metric");
                return this;
            }

            Sample build() {
                return new Sample(this);
            }
        }
    }

    static final class Profile {
        private final String runId;
        private final String coordinationCommit;
        private final String languageCommit;
        private final String bexCommit;
        private final String coordinationSourceSha256;
        private final String dependencyLockSha256;
        private final String datasetGeneratorIdentity;
        private final String semanticEnvironmentIdentity;
        private final int warmupIterations;
        private final int measurementIterations;
        private final Map<String, Object> machine;

        Profile(
                String runId,
                String coordinationCommit,
                String languageCommit,
                String bexCommit,
                String coordinationSourceSha256,
                String dependencyLockSha256,
                String datasetGeneratorIdentity,
                String semanticEnvironmentIdentity,
                int warmupIterations,
                int measurementIterations,
                Map<String, Object> machine) {
            this.runId = requireText(runId, "runId");
            this.coordinationCommit = requireText(
                    coordinationCommit, "coordinationCommit");
            this.languageCommit = requireText(
                    languageCommit, "languageCommit");
            this.bexCommit = requireText(bexCommit, "bexCommit");
            this.coordinationSourceSha256 = requireText(
                    coordinationSourceSha256,
                    "coordinationSourceSha256");
            this.dependencyLockSha256 = requireText(
                    dependencyLockSha256,
                    "dependencyLockSha256");
            this.datasetGeneratorIdentity = requireText(
                    datasetGeneratorIdentity,
                    "datasetGeneratorIdentity");
            this.semanticEnvironmentIdentity = requireText(
                    semanticEnvironmentIdentity,
                    "semanticEnvironmentIdentity");
            if (warmupIterations < 0 || warmupIterations > 20
                    || measurementIterations < 1
                    || measurementIterations > 100) {
                throw new IllegalArgumentException(
                        "warmups must be 0..20 and measurements 1..100");
            }
            this.warmupIterations = warmupIterations;
            this.measurementIterations = measurementIterations;
            this.machine = Collections.unmodifiableMap(
                    new TreeMap<String, Object>(Objects.requireNonNull(
                            machine, "machine")));
        }

        static Profile fromSystemProperties() {
            Map<String, Object> machine = new TreeMap<String, Object>();
            machine.put("javaVendor", System.getProperty("java.vendor"));
            machine.put("javaVersion", System.getProperty("java.version"));
            machine.put("vmName", System.getProperty("java.vm.name"));
            machine.put("vmVersion", System.getProperty("java.vm.version"));
            machine.put("osName", System.getProperty("os.name"));
            machine.put("osVersion", System.getProperty("os.version"));
            machine.put("osArch", System.getProperty("os.arch"));
            machine.put(
                    "availableProcessors",
                    Runtime.getRuntime().availableProcessors());
            machine.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
            machine.put(
                    "jvmArguments",
                    java.lang.management.ManagementFactory
                            .getRuntimeMXBean().getInputArguments());
            return new Profile(
                    property("coordination.performance.runId"),
                    property("coordination.performance.coordinationCommit"),
                    property("coordination.performance.languageCommit"),
                    property("coordination.performance.bexCommit"),
                    property(
                            "coordination.performance.coordinationSourceSha256"),
                    property(
                            "coordination.performance.dependencyLockSha256"),
                    property("coordination.performance.datasetIdentity"),
                    property("coordination.performance.environmentIdentity"),
                    integerProperty(
                            "coordination.performance.warmupIterations", 1),
                    integerProperty(
                            "coordination.performance.measurementIterations",
                            5),
                    machine);
        }

        int warmupIterations() {
            return warmupIterations;
        }

        int measurementIterations() {
            return measurementIterations;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runId", runId);
            result.put("coordinationCommit", coordinationCommit);
            result.put("languageCommit", languageCommit);
            result.put("bexCommit", bexCommit);
            result.put(
                    "coordinationSourceSha256",
                    coordinationSourceSha256);
            result.put("dependencyLockSha256", dependencyLockSha256);
            result.put(
                    "datasetGeneratorIdentity",
                    datasetGeneratorIdentity);
            result.put(
                    "semanticEnvironmentIdentity",
                    semanticEnvironmentIdentity);
            result.put("warmupIterations", warmupIterations);
            result.put("measurementIterations", measurementIterations);
            result.put("concurrency", 1L);
            result.put("clock", "System.nanoTime");
            Map<String, String> cacheProtocol =
                    new LinkedHashMap<String, String>();
            cacheProtocol.put(
                    "cold",
                    "no prior PROCESS in the measured immutable generation");
            cacheProtocol.put(
                    "warm",
                    "one complete identical scenario on an independent "
                            + "session in the same generation and store");
            result.put("cacheProtocol", cacheProtocol);
            result.put("machine", machine);
            return result;
        }
    }

    static List<CellKey> requiredCells() {
        List<CellKey> result = new ArrayList<CellKey>();
        for (Scenario scenario : Scenario.values()) {
            for (ComparisonMode mode : ComparisonMode.values()) {
                for (CacheState cache : CacheState.values()) {
                    result.add(new CellKey(scenario, mode, cache));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, Object> capture(
            Profile profile,
            ScenarioAdapter adapter) throws Exception {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(adapter, "adapter");
        Collector collector = new Collector(
                profile, adapter.getClass().getName());
        try (ScenarioAdapter closeable = adapter) {
            for (CellKey cell : requiredCells()) {
                for (int iteration = 0;
                        iteration < profile.warmupIterations();
                        iteration++) {
                    closeable.warmUp(cell, iteration);
                }
                for (int iteration = 0;
                        iteration < profile.measurementIterations();
                        iteration++) {
                    collector.add(cell, closeable.measure(cell, iteration));
                }
            }
        }
        return collector.receipt();
    }

    static Map<String, Object> unavailableReceipt(
            Profile profile,
            String reason) {
        String unavailableReason = requireText(reason, "reason");
        List<Map<String, Object>> cells =
                new ArrayList<Map<String, Object>>();
        for (CellKey cell : requiredCells()) {
            Map<String, Object> value = cell.toMap();
            value.put("status", "not-executed");
            value.put("reason", unavailableReason);
            value.put("phases", unavailableInventory(
                    Arrays.asList(Phase.values()), unavailableReason));
            value.put("metrics", unavailableInventory(
                    Arrays.asList(Metric.values()), unavailableReason));
            cells.add(value);
        }
        Map<String, Object> result = baseReceipt(profile);
        result.put("status", "unavailable");
        result.put("performanceReady", false);
        result.put("matrix", matrixSummary(0L));
        result.put("cells", cells);
        result.put("semanticEquivalence", "not-executed");
        result.put("comparisonEligible", false);
        result.put("speedupClaims", Collections.emptyList());
        result.put("unavailableReason", unavailableReason);
        return result;
    }

    static void write(Path target, Map<String, Object> receipt)
            throws IOException {
        Path checked = Objects.requireNonNull(target, "target");
        Path parent = checked.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.writeValue(checked.toFile(), receipt);
    }

    static long percentile(List<Long> samples, double percentile) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        if (!(percentile > 0.0d && percentile <= 100.0d)) {
            throw new IllegalArgumentException(
                    "percentile must be within (0, 100]");
        }
        List<Long> ordered = new ArrayList<Long>(samples);
        Collections.sort(ordered);
        int rank = (int) Math.ceil(percentile * ordered.size() / 100.0d);
        return ordered.get(Math.max(1, rank) - 1).longValue();
    }

    static ScenarioAdapter loadAdapter(String className) {
        String checked = requireText(className, "className");
        try {
            Class<?> type = Class.forName(checked);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof ScenarioAdapter)) {
                throw new IllegalArgumentException(
                        checked + " does not implement ScenarioAdapter");
            }
            return (ScenarioAdapter) instance;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException(
                    "Cannot create performance scenario adapter " + checked,
                    failure);
        }
    }

    private static final class Collector {
        private final Profile profile;
        private final String adapterClass;
        private final Map<CellKey, List<Sample>> samples =
                new TreeMap<CellKey, List<Sample>>();

        private Collector(Profile profile, String adapterClass) {
            this.profile = profile;
            this.adapterClass = requireText(
                    adapterClass, "adapterClass");
        }

        private void add(CellKey cell, Sample sample) {
            CellKey checkedCell = Objects.requireNonNull(cell, "cell");
            Sample checkedSample = Objects.requireNonNull(sample, "sample");
            List<Sample> values = samples.get(checkedCell);
            if (values == null) {
                values = new ArrayList<Sample>();
                samples.put(checkedCell, values);
            }
            values.add(checkedSample);
        }

        private Map<String, Object> receipt() {
            requireCompleteMatrix();
            requireSemanticEquivalence();
            List<Map<String, Object>> cellReports =
                    new ArrayList<Map<String, Object>>();
            boolean requiredMeasurementsAvailable = true;
            for (CellKey cell : requiredCells()) {
                Map<String, Object> report = summarize(
                        cell, samples.get(cell));
                cellReports.add(report);
                requiredMeasurementsAvailable &=
                        report.get("requiredMeasurementsAvailable")
                                .equals(Boolean.TRUE);
            }
            Map<String, Object> result = baseReceipt(profile);
            result.put("scenarioAdapter", adapterClass);
            result.put("status", requiredMeasurementsAvailable
                    ? "verified"
                    : "complete-with-unavailable-metrics");
            result.put(
                    "performanceReady",
                    requiredMeasurementsAvailable);
            result.put("matrix", matrixSummary(REQUIRED_CELL_COUNT));
            result.put("cells", cellReports);
            result.put("semanticEquivalence", "verified");
            result.put("comparisonEligible", true);
            result.put("speedupClaims", Collections.emptyList());
            result.put(
                    "qualification",
                    "No speedup is claimed; the receipt proves only exact "
                            + "same-profile samples and semantic equality.");
            return result;
        }

        private void requireCompleteMatrix() {
            TreeSet<CellKey> expected = new TreeSet<CellKey>(requiredCells());
            if (!samples.keySet().equals(expected)) {
                throw new IllegalStateException(
                        "Performance matrix is incomplete: expected "
                                + expected + " but observed "
                                + samples.keySet());
            }
            for (Map.Entry<CellKey, List<Sample>> entry
                    : samples.entrySet()) {
                if (entry.getValue().size()
                        != profile.measurementIterations()) {
                    throw new IllegalStateException(
                            entry.getKey().id()
                                    + " has " + entry.getValue().size()
                                    + " samples; expected "
                                    + profile.measurementIterations());
                }
            }
        }

        private void requireSemanticEquivalence() {
            for (Scenario scenario : Scenario.values()) {
                String dataset = null;
                SemanticFingerprint semantics = null;
                for (CellKey cell : requiredCells()) {
                    if (cell.scenario() != scenario) {
                        continue;
                    }
                    for (Sample sample : samples.get(cell)) {
                        if (dataset == null) {
                            dataset = sample.datasetSha256();
                            semantics = sample.semantics();
                        } else if (!dataset.equals(sample.datasetSha256())
                                || !semantics.equals(sample.semantics())) {
                            throw new IllegalStateException(
                                    "Semantic or dataset drift for "
                                            + scenario.id() + " at "
                                            + cell.id());
                        }
                    }
                }
            }
        }

        private Map<String, Object> summarize(
                CellKey cell,
                List<Sample> values) {
            Map<String, Object> result = cell.toMap();
            result.put("status", "completed");
            result.put("sampleCount", (long) values.size());
            result.put("datasetSha256", values.get(0).datasetSha256());
            result.put("semantics", values.get(0).semantics().toMap());
            Map<String, Object> phases =
                    new LinkedHashMap<String, Object>();
            Map<String, Object> metrics =
                    new LinkedHashMap<String, Object>();
            boolean requiredAvailable = true;
            for (Phase phase : Phase.values()) {
                Map<String, Object> distribution = distribution(
                        values, phase);
                phases.put(phase.id(), distribution);
                if (requiredPhases(cell.mode()).contains(phase)
                        && !distribution.get("status").equals("available")) {
                    requiredAvailable = false;
                }
            }
            for (Metric metric : Metric.values()) {
                Map<String, Object> distribution = distribution(
                        values, metric);
                metrics.put(metric.id(), distribution);
                if (requiredMetrics(cell.mode()).contains(metric)
                        && !distribution.get("status").equals("available")) {
                    requiredAvailable = false;
                }
            }
            result.put("phases", phases);
            result.put("metrics", metrics);
            result.put(
                    "requiredMeasurementsAvailable",
                    requiredAvailable);
            return result;
        }

        private Map<String, Object> distribution(
                List<Sample> values,
                Phase phase) {
            List<Long> available = new ArrayList<Long>();
            TreeSet<String> reasons = new TreeSet<String>();
            for (Sample sample : values) {
                if (sample.phaseNanos().containsKey(phase)) {
                    available.add(sample.phaseNanos().get(phase));
                } else {
                    reasons.add(sample.unavailablePhases().get(phase));
                }
            }
            return distribution(available, reasons);
        }

        private Map<String, Object> distribution(
                List<Sample> values,
                Metric metric) {
            List<Long> available = new ArrayList<Long>();
            TreeSet<String> reasons = new TreeSet<String>();
            for (Sample sample : values) {
                if (sample.metrics().containsKey(metric)) {
                    available.add(sample.metrics().get(metric));
                } else {
                    reasons.add(sample.unavailableMetrics().get(metric));
                }
            }
            return distribution(available, reasons);
        }

        private Map<String, Object> distribution(
                List<Long> values,
                TreeSet<String> reasons) {
            if (!values.isEmpty() && !reasons.isEmpty()) {
                throw new IllegalStateException(
                        "A metric cannot mix available and unavailable "
                                + "samples in one cell");
            }
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            if (values.isEmpty()) {
                if (reasons.size() != 1) {
                    throw new IllegalStateException(
                            "Unavailable samples require one stable reason");
                }
                result.put("status", "unavailable");
                result.put("reason", reasons.first());
                result.put("samples", Collections.emptyList());
                return result;
            }
            List<Long> ordered = new ArrayList<Long>(values);
            Collections.sort(ordered);
            result.put("status", "available");
            result.put("count", (long) ordered.size());
            result.put("samples", new ArrayList<Long>(values));
            result.put("minimum", ordered.get(0));
            result.put("p50", percentile(ordered, 50.0d));
            result.put("p95", percentile(ordered, 95.0d));
            result.put("p99", percentile(ordered, 99.0d));
            result.put("maximum", ordered.get(ordered.size() - 1));
            return result;
        }
    }

    private static Map<String, Object> baseReceipt(Profile profile) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schema", SCHEMA);
        result.put("profile", profile.toMap());
        result.put("requiredScenarios", enumIds(Scenario.values()));
        result.put("comparisonModes", enumIds(ComparisonMode.values()));
        result.put("cacheStates", enumIds(CacheState.values()));
        result.put("phaseInventory", enumIds(Phase.values()));
        result.put("metricInventory", enumIds(Metric.values()));
        result.put("metricSemantics", metricSemantics());
        return result;
    }

    private static Map<String, String> metricSemantics() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put(
                Metric.PROVIDER_REQUEST_COUNT.id(),
                "request-local provider demand occurrences");
        result.put(
                Metric.BATCH_COUNT.id(),
                "request-local backend batch operations");
        result.put(
                Metric.FALLBACK_COUNT.id(),
                "request-local dynamic fallback operations");
        result.put(
                Metric.LOADED_BYTES.id(),
                "canonical bytes reported by the request-local loader");
        result.put(
                Metric.MATERIALIZED_NODE_COUNT.id(),
                "exact materialized-node count; unavailable when no "
                        + "non-perturbing authoritative counter is attached");
        result.put(
                Metric.SELECTED_BODY_COUNT.id(),
                "executable handler-body execution occurrences from the "
                        + "Language HANDLERS_EXECUTED counter captured by "
                        + "BexProcessingMetrics; repeated execution is "
                        + "counted repeatedly");
        result.put(
                Metric.ALLOCATION_BYTES.id(),
                "bytes allocated on the synchronous measurement thread "
                        + "from the HotSpot ThreadMXBean when supported");
        result.put(
                Metric.RETAINED_HEAP_BYTES.id(),
                "exact retained heap bytes; unavailable without isolated "
                        + "heap-dump and dominator analysis");
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> matrixSummary(long completed) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requiredCells", (long) REQUIRED_CELL_COUNT);
        result.put("completedCells", completed);
        result.put("scenarioCount", (long) Scenario.values().length);
        result.put("comparisonModeCount",
                (long) ComparisonMode.values().length);
        result.put("cacheStateCount", (long) CacheState.values().length);
        return result;
    }

    private static List<String> enumIds(Object[] values) {
        List<String> result = new ArrayList<String>();
        for (Object value : values) {
            if (value instanceof Scenario) {
                result.add(((Scenario) value).id());
            } else if (value instanceof ComparisonMode) {
                result.add(((ComparisonMode) value).id());
            } else if (value instanceof CacheState) {
                result.add(((CacheState) value).id());
            } else if (value instanceof Phase) {
                result.add(((Phase) value).id());
            } else if (value instanceof Metric) {
                result.add(((Metric) value).id());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported inventory value " + value);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static <T> Map<String, Object> unavailableInventory(
            List<T> values,
            String reason) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (T value : values) {
            String id;
            if (value instanceof Phase) {
                id = ((Phase) value).id();
            } else if (value instanceof Metric) {
                id = ((Metric) value).id();
            } else {
                throw new IllegalArgumentException(
                        "Unsupported unavailable value " + value);
            }
            Map<String, Object> unavailable =
                    new LinkedHashMap<String, Object>();
            unavailable.put("status", "unavailable");
            unavailable.put("reason", reason);
            unavailable.put("samples", Collections.emptyList());
            result.put(id, unavailable);
        }
        return result;
    }

    private static List<Phase> requiredPhases(ComparisonMode mode) {
        if (mode == ComparisonMode.FULL_INLINE_CONTROL) {
            return Arrays.asList(Phase.PROCESS, Phase.END_TO_END);
        }
        return Arrays.asList(Phase.values());
    }

    private static List<Metric> requiredMetrics(ComparisonMode mode) {
        if (mode == ComparisonMode.FULL_INLINE_CONTROL) {
            return Collections.singletonList(Metric.SELECTED_BODY_COUNT);
        }
        return Arrays.asList(
                Metric.PROVIDER_REQUEST_COUNT,
                Metric.BATCH_COUNT,
                Metric.FALLBACK_COUNT,
                Metric.LOADED_BYTES,
                Metric.SELECTED_BODY_COUNT);
    }

    private static <K extends Enum<K>> Map<K, Long> immutableValues(
            Map<K, Long> source,
            String label) {
        Map<K, Long> copy = new LinkedHashMap<K, Long>(source);
        for (Map.Entry<K, Long> entry : copy.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0L) {
                throw new IllegalArgumentException(
                        label + " values must be non-negative");
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <K extends Enum<K>> Map<K, String> immutableReasons(
            Map<K, String> source,
            String label) {
        Map<K, String> copy = new LinkedHashMap<K, String>(source);
        for (Map.Entry<K, String> entry : copy.entrySet()) {
            requireText(entry.getValue(), label + " reason");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <K extends Enum<K>> void requireCoverage(
            K[] inventory,
            Map<K, Long> values,
            Map<K, String> unavailable,
            String label) {
        for (K key : inventory) {
            boolean available = values.containsKey(key);
            boolean absent = unavailable.containsKey(key);
            if (available == absent) {
                throw new IllegalArgumentException(
                        label + " " + key
                                + " must be available or unavailable exactly "
                                + "once");
            }
        }
    }

    private static <K extends Enum<K>> void putAvailable(
            Map<K, Long> values,
            Map<K, String> unavailable,
            K key,
            long value,
            String label) {
        Objects.requireNonNull(key, label);
        if (value < 0L || values.containsKey(key)
                || unavailable.containsKey(key)) {
            throw new IllegalArgumentException(
                    label + " must be unique and non-negative: " + key);
        }
        values.put(key, value);
    }

    private static <K extends Enum<K>> void putUnavailable(
            Map<K, Long> values,
            Map<K, String> unavailable,
            K key,
            String reason,
            String label) {
        Objects.requireNonNull(key, label);
        if (values.containsKey(key) || unavailable.containsKey(key)) {
            throw new IllegalArgumentException(
                    label + " must be unique: " + key);
        }
        unavailable.put(key, requireText(reason, label + " reason"));
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null || value.trim().isEmpty()
                ? "unavailable:" + name
                : value.trim();
    }

    private static int integerProperty(String name, int fallback) {
        String value = System.getProperty(name);
        return value == null
                ? fallback
                : Integer.parseInt(value);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private CoordinationEnginePerformanceHarness() {
    }
}
