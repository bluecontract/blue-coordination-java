package blue.coordination.processor.compute;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.processor.DocumentProcessingResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic JSON/CSV serialization for representative rc15-adoption metrics. */
final class LanguageRc15MetricsArtifactWriter {
    static final String JSON_FILE_NAME = "scenario-metrics.json";
    static final String CSV_FILE_NAME = "scenario-metrics.csv";
    private static final String CAPTURE_PHASE =
            "after-scenario-before-runtime-and-runner-close";
    private static final String CUMULATIVE_METRIC_SCOPE =
            "runtime-construction-through-scenario";
    private static final String PROOF_COUNTER_DELTA_BASELINE =
            "after-document-initialization-before-scenario";
    private static final String[] REQUIRED_PROOF_COUNTERS = {
            "frozenPatchesHandedToLanguage",
            "frozenPatchValuesHandedToLanguage",
            "mutablePatchesHandedToLanguage",
            "frozenPatchValuesAccepted",
            "mutablePatchValuesFrozen",
            "frozenPatchValuesMaterialized"
    };

    private LanguageRc15MetricsArtifactWriter() {
    }

    static Scenario capture(String scenarioId,
                            String scenarioKind,
                            String fixture,
                            DocumentProcessingResult result,
                            BexProcessingMetrics metrics,
                            BexProcessingMetrics.Snapshot postInitializationBaseline) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (postInitializationBaseline == null) {
            throw new IllegalArgumentException("postInitializationBaseline must not be null");
        }

        BexProcessingMetrics.Snapshot snapshot = metrics.snapshot();
        return new Scenario(scenarioId,
                scenarioKind,
                fixture,
                result.status().name(),
                result.errorCategory() != null ? result.errorCategory().name() : null,
                result.failureReason(),
                result.totalGas(),
                result.triggeredEvents().size(),
                result.blueId(),
                strongMetrics(snapshot),
                deterministicGenericMetrics(snapshot.languageCounters),
                deterministicGenericMetrics(snapshot.languageGauges),
                deterministicGenericMetrics(snapshot.languageHighWaterMarks),
                proofCounterDeltas(postInitializationBaseline, snapshot));
    }

    static void write(Path reportDirectory, List<Scenario> scenarios) throws IOException {
        if (reportDirectory == null) {
            throw new IllegalArgumentException("reportDirectory must not be null");
        }
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios must not be empty");
        }

        List<Scenario> ordered = new ArrayList<Scenario>(scenarios);
        Collections.sort(ordered, new Comparator<Scenario>() {
            @Override
            public int compare(Scenario left, Scenario right) {
                return left.scenarioId.compareTo(right.scenarioId);
            }
        });
        rejectDuplicateScenarioIds(ordered);

        Files.createDirectories(reportDirectory);
        writeAtomically(reportDirectory.resolve(JSON_FILE_NAME), json(ordered));
        writeAtomically(reportDirectory.resolve(CSV_FILE_NAME), csv(ordered));
    }

    private static Map<String, Long> strongMetrics(BexProcessingMetrics.Snapshot snapshot) {
        Map<String, Long> values = new TreeMap<String, Long>();
        for (Field field : BexProcessingMetrics.Snapshot.class.getFields()) {
            if (field.getType() != Long.TYPE
                    || !Modifier.isPublic(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers())
                    || field.getName().endsWith("Nanos")) {
                continue;
            }
            try {
                values.put(field.getName(), Long.valueOf(field.getLong(snapshot)));
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Cannot read metrics field " + field.getName(), ex);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Long> deterministicGenericMetrics(Map<String, Long> source) {
        Map<String, Long> values = new TreeMap<String, Long>();
        for (Map.Entry<String, Long> metric : source.entrySet()) {
            if (!metric.getKey().endsWith("Nanos")) {
                values.put(metric.getKey(), metric.getValue());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Long> proofCounterDeltas(
            BexProcessingMetrics.Snapshot baseline,
            BexProcessingMetrics.Snapshot current) {
        Map<String, Long> values = new TreeMap<String, Long>();
        for (String name : REQUIRED_PROOF_COUNTERS) {
            long delta = metric(current.languageCounters, name)
                    - metric(baseline.languageCounters, name);
            if (delta < 0L) {
                throw new IllegalStateException("Counter decreased after initialization: " + name);
            }
            values.put(name, Long.valueOf(delta));
        }
        return Collections.unmodifiableMap(values);
    }

    private static long metric(Map<String, Long> metrics, String name) {
        Long value = metrics.get(name);
        return value != null ? value.longValue() : 0L;
    }

    private static byte[] json(List<Scenario> scenarios) throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(2));
        root.put("capturePhase", CAPTURE_PHASE);
        root.put("cumulativeMetricScope", CUMULATIVE_METRIC_SCOPE);
        root.put("proofCounterDeltaBaseline", PROOF_COUNTER_DELTA_BASELINE);
        root.put("coordinationElapsedTimingMetricsExcluded", Boolean.TRUE);
        root.put("genericElapsedTimingMetricsExcluded", Boolean.TRUE);

        List<Map<String, Object>> serializedScenarios =
                new ArrayList<Map<String, Object>>(scenarios.size());
        for (Scenario scenario : scenarios) {
            serializedScenarios.add(scenario.toJson());
        }
        root.put("scenarios", serializedScenarios);

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return (mapper.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] csv(List<Scenario> scenarios) {
        StringBuilder csv = new StringBuilder();
        csv.append("scenario_id,scenario_kind,fixture,status,error_category,failure_reason,")
                .append("total_gas,triggered_event_count,final_blue_id,")
                .append("metric_scope,metric_kind,metric_name,metric_value\n");
        for (Scenario scenario : scenarios) {
            appendMetrics(csv, scenario, "coordination-cumulative", "strong",
                    scenario.coordination);
            appendMetrics(csv, scenario, "language-cumulative", "counter",
                    scenario.languageCounters);
            appendMetrics(csv, scenario, "language-cumulative", "gauge",
                    scenario.languageGauges);
            appendMetrics(csv, scenario, "language-cumulative", "high_water",
                    scenario.languageHighWaterMarks);
            appendMetrics(csv, scenario, "workflow-since-initialization", "counter_delta",
                    scenario.proofCounterDeltasSinceInitialization);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendMetrics(StringBuilder csv,
                                      Scenario scenario,
                                      String scope,
                                      String kind,
                                      Map<String, Long> metrics) {
        for (Map.Entry<String, Long> metric : metrics.entrySet()) {
            appendCsvCell(csv, scenario.scenarioId);
            appendCsvCell(csv, scenario.scenarioKind);
            appendCsvCell(csv, scenario.fixture);
            appendCsvCell(csv, scenario.status);
            appendCsvCell(csv, scenario.errorCategory);
            appendCsvCell(csv, scenario.failureReason);
            appendCsvCell(csv, Long.toString(scenario.totalGas));
            appendCsvCell(csv, Integer.toString(scenario.triggeredEventCount));
            appendCsvCell(csv, scenario.finalBlueId);
            appendCsvCell(csv, scope);
            appendCsvCell(csv, kind);
            appendCsvCell(csv, metric.getKey());
            appendCsvCell(csv, Long.toString(metric.getValue().longValue()), true);
        }
    }

    private static void appendCsvCell(StringBuilder target, String value) {
        appendCsvCell(target, value, false);
    }

    private static void appendCsvCell(StringBuilder target, String value, boolean last) {
        target.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character == '"') {
                    target.append("\"\"");
                } else {
                    target.append(character);
                }
            }
        }
        target.append('"').append(last ? '\n' : ',');
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rejectDuplicateScenarioIds(List<Scenario> scenarios) {
        String previous = null;
        for (Scenario scenario : scenarios) {
            if (scenario.scenarioId.equals(previous)) {
                throw new IllegalArgumentException("Duplicate scenario id: " + previous);
            }
            previous = scenario.scenarioId;
        }
    }

    static final class Scenario {
        private final String scenarioId;
        private final String scenarioKind;
        private final String fixture;
        private final String status;
        private final String errorCategory;
        private final String failureReason;
        private final long totalGas;
        private final int triggeredEventCount;
        private final String finalBlueId;
        private final Map<String, Long> coordination;
        private final Map<String, Long> languageCounters;
        private final Map<String, Long> languageGauges;
        private final Map<String, Long> languageHighWaterMarks;
        private final Map<String, Long> proofCounterDeltasSinceInitialization;

        private Scenario(String scenarioId,
                         String scenarioKind,
                         String fixture,
                         String status,
                         String errorCategory,
                         String failureReason,
                         long totalGas,
                         int triggeredEventCount,
                         String finalBlueId,
                         Map<String, Long> coordination,
                         Map<String, Long> languageCounters,
                         Map<String, Long> languageGauges,
                         Map<String, Long> languageHighWaterMarks,
                         Map<String, Long> proofCounterDeltasSinceInitialization) {
            this.scenarioId = required(scenarioId, "scenarioId");
            this.scenarioKind = required(scenarioKind, "scenarioKind");
            this.fixture = required(fixture, "fixture");
            this.status = required(status, "status");
            this.errorCategory = errorCategory;
            this.failureReason = failureReason;
            this.totalGas = totalGas;
            this.triggeredEventCount = triggeredEventCount;
            this.finalBlueId = finalBlueId;
            this.coordination = immutableSortedCopy(coordination);
            this.languageCounters = immutableSortedCopy(languageCounters);
            this.languageGauges = immutableSortedCopy(languageGauges);
            this.languageHighWaterMarks = immutableSortedCopy(languageHighWaterMarks);
            this.proofCounterDeltasSinceInitialization =
                    immutableSortedCopy(proofCounterDeltasSinceInitialization);
        }

        private Map<String, Object> toJson() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", status);
            result.put("errorCategory", errorCategory);
            result.put("failureReason", failureReason);
            result.put("totalGas", Long.valueOf(totalGas));
            result.put("triggeredEventCount", Integer.valueOf(triggeredEventCount));
            result.put("finalBlueId", finalBlueId);

            Map<String, Object> language = new LinkedHashMap<String, Object>();
            language.put("counters", languageCounters);
            language.put("gauges", languageGauges);
            language.put("highWaterMarks", languageHighWaterMarks);

            Map<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("coordinationStrong", coordination);
            metrics.put("language", language);
            metrics.put("proofCounterDeltasSinceInitialization",
                    proofCounterDeltasSinceInitialization);

            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("scenarioId", scenarioId);
            json.put("scenarioKind", scenarioKind);
            json.put("fixture", fixture);
            json.put("result", result);
            json.put("metrics", metrics);
            return json;
        }

        private static String required(String value, String label) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be empty");
            }
            return value;
        }

        private static Map<String, Long> immutableSortedCopy(Map<String, Long> source) {
            return Collections.unmodifiableMap(new TreeMap<String, Long>(source));
        }
    }
}
