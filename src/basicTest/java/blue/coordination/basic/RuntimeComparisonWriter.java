package blue.coordination.basic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Writes same-source runtime evidence in human and machine-readable forms. */
final class RuntimeComparisonWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RuntimeComparisonWriter() {
    }

    static void write(Path markdown, List<Row> rows) throws IOException {
        Objects.requireNonNull(markdown, "markdown");
        List<Row> evidence = List.copyOf(Objects.requireNonNull(rows, "rows"));
        Path json = markdown.resolveSibling("runtime-comparison.json");
        Files.createDirectories(markdown.toAbsolutePath().getParent());
        Files.writeString(
                markdown,
                markdown(evidence),
                StandardCharsets.UTF_8);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "blue.coordination/basic-runtime-comparison/1.0");
        report.put("source", "same JVM/source tree as basicRuntimeCampaign");
        report.put("unit", "defined per row");
        report.put("rows", evidence);
        Files.writeString(
                json,
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    static Row row(
            String scenario,
            String current,
            LatencySeries samples,
            String target,
            boolean passed,
            Map<String, Long> counters,
            String note) {
        return new Row(
                requireText(scenario, "scenario"),
                requireText(current, "current"),
                samples.size(),
                "ms",
                millis(samples.medianNanos()),
                millis(samples.p95Nanos()),
                millis(samples.p99Nanos()),
                millis(samples.maxNanos()),
                null,
                requireText(target, "target"),
                passed ? "PASS" : "FAIL",
                Map.copyOf(Objects.requireNonNull(counters, "counters")),
                requireText(note, "note"));
    }

    static Row unavailable(
            String scenario,
            String current,
            String target,
            String note) {
        return new Row(
                requireText(scenario, "scenario"),
                requireText(current, "current"),
                0,
                "ms",
                null,
                null,
                null,
                null,
                null,
                requireText(target, "target"),
                "NOT_MEASURED",
                Map.of(),
                requireText(note, "note"));
    }

    private static String markdown(List<Row> rows) {
        StringBuilder text = new StringBuilder(8_192);
        text.append("# `basicTest` runtime comparison\n\n")
                .append("Generated from the same source tree by `basicRuntimeCampaign`. ")
                .append("The supplied archive contained no authoritative current-run timing artifacts, ")
                .append("so `not measured` is retained instead of inventing a baseline. ")
                .append("Allocation is reported as unavailable unless JFR allocation events were captured.\n\n")
                .append("| Scenario | Current measured | n | Optimized p50 | p95 | p99 | max | Allocation | Target | Gate |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---|---:|\n");
        for (Row row : rows) {
            text.append("| ").append(escape(row.scenario()))
                    .append(" | ").append(escape(row.currentMeasured()))
                    .append(" | ").append(row.sampleCount())
                    .append(" | ").append(format(row.p50(), row.valueUnit()))
                    .append(" | ").append(format(row.p95(), row.valueUnit()))
                    .append(" | ").append(format(row.p99(), row.valueUnit()))
                    .append(" | ").append(format(row.max(), row.valueUnit()))
                    .append(" | ").append(row.allocationBytes() == null
                            ? "unavailable"
                            : row.allocationBytes() + " B")
                    .append(" | ").append(escape(row.target()))
                    .append(" | ").append(row.gate())
                    .append(" |\n");
        }
        text.append("\n## Work-shape evidence\n\n");
        for (Row row : rows) {
            text.append("### ").append(row.scenario()).append("\n\n")
                    .append(row.note()).append("\n\n")
                    .append("Counters: ");
            if (row.counters().isEmpty()) {
                text.append("not available");
            } else {
                List<String> values = new ArrayList<>();
                row.counters().forEach((name, value) ->
                        values.add('`' + name + "=" + value + '`'));
                text.append(String.join(", ", values));
            }
            text.append(".\n\n");
        }
        return text.toString();
    }

    static Row scalar(
            String scenario,
            String current,
            double value,
            String unit,
            String target,
            boolean passed,
            Map<String, Long> counters,
            String note) {
        return new Row(
                requireText(scenario, "scenario"),
                requireText(current, "current"),
                1,
                requireText(unit, "unit"),
                value,
                value,
                value,
                value,
                null,
                requireText(target, "target"),
                passed ? "PASS" : "FAIL",
                counters,
                requireText(note, "note"));
    }

    private static String format(Double value, String unit) {
        return value == null
                ? "not measured"
                : String.format(Locale.ROOT, "%.3f %s", value, unit);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    record Row(
            String scenario,
            String currentMeasured,
            int sampleCount,
            String valueUnit,
            Double p50,
            Double p95,
            Double p99,
            Double max,
            Long allocationBytes,
            String target,
            String gate,
            Map<String, Long> counters,
            String note) {
        Row {
            scenario = requireText(scenario, "scenario");
            currentMeasured = requireText(currentMeasured, "currentMeasured");
            valueUnit = requireText(valueUnit, "valueUnit");
            target = requireText(target, "target");
            gate = requireText(gate, "gate");
            counters = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(counters, "counters")));
            note = requireText(note, "note");
            if (sampleCount < 0) {
                throw new IllegalArgumentException("sampleCount must be non-negative");
            }
        }
    }
}
