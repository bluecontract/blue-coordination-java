package blue.coordination.basic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Lightweight wall-clock diagnostics shared by the focused basic tests. */
final class BasicTestMetrics implements AutoCloseable {
    private static final String DIRECTORY_PROPERTY =
            "basic.test.metrics.dir";
    private static final ObjectWriter JSON = new ObjectMapper()
            .writerWithDefaultPrettyPrinter();

    private final String reportId;
    private final String title;
    private final List<StepTiming> timings = new ArrayList<>();
    private final List<DetailSection> details = new ArrayList<>();
    private final long totalStartedNanos = System.nanoTime();
    private boolean published;

    private BasicTestMetrics(String reportId, String title) {
        if (!reportId.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "Invalid metrics report id: " + reportId);
        }
        this.reportId = reportId;
        this.title = Objects.requireNonNull(title, "title");
    }

    static BasicTestMetrics start(String reportId, String title) {
        return new BasicTestMetrics(reportId, title);
    }

    <T> T measure(String step, CheckedSupplier<T> action) throws Exception {
        String checkedStep = Objects.requireNonNull(step, "step");
        Objects.requireNonNull(action, "action");
        long startedNanos = System.nanoTime();
        try {
            return action.get();
        } finally {
            timings.add(new StepTiming(
                    checkedStep,
                    System.nanoTime() - startedNanos));
        }
    }

    void measure(String step, CheckedRunnable action) throws Exception {
        measure(step, () -> {
            action.run();
            return null;
        });
    }

    <T extends AutoCloseable> MeasuredResource<T> manage(
            String closeStep,
            T resource) {
        return new MeasuredResource<>(this, closeStep, resource);
    }

    DetailSection detail(String id, String parentStep) {
        String checkedId = requireText(id, "detail id");
        if (!checkedId.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "Invalid detail id: " + checkedId);
        }
        if (details.stream().anyMatch(detail -> detail.id.equals(checkedId))) {
            throw new IllegalArgumentException(
                    "Duplicate detail id: " + checkedId);
        }
        DetailSection detail = new DetailSection(
                checkedId, requireText(parentStep, "parentStep"));
        details.add(detail);
        return detail;
    }

    @Override
    public void close() {
        if (published) {
            return;
        }
        published = true;
        long totalNanos = System.nanoTime() - totalStartedNanos;
        List<DetailReport> detailReports = detailReports();
        print(totalNanos, detailReports);

        String directory = System.getProperty(DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path report = Path.of(directory)
                .resolve(reportId + "-timings.json");
        try {
            Files.createDirectories(report.getParent());
            writeAtomically(report, json(totalNanos, detailReports));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot write timing report " + report, failure);
        }
    }

    private void print(
            long totalNanos,
            List<DetailReport> detailReports) {
        System.out.println();
        System.out.println(title + " step timings (single diagnostic run)");
        System.out.printf(Locale.ROOT, "%-44s %12s %9s%n",
                "Step", "milliseconds", "% total");
        for (StepTiming timing : timings) {
            System.out.printf(Locale.ROOT, "%-44s %12.3f %8.2f%%%n",
                    timing.step(),
                    timing.nanos() / 1_000_000.0,
                    timing.nanos() * 100.0 / totalNanos);
        }
        System.out.printf(Locale.ROOT, "%-44s %12.3f %8.2f%%%n%n",
                "TOTAL", totalNanos / 1_000_000.0, 100.0);

        for (DetailReport detail : detailReports) {
            System.out.println("Detailed timing: " + detail.id()
                    + " inside " + detail.parentStep());
            System.out.printf(Locale.ROOT, "%-52s %12s %10s%n",
                    "Phase", "milliseconds", "% parent");
            for (PhaseTiming phase : detail.phases()) {
                System.out.printf(Locale.ROOT, "%-52s %12.3f %9.2f%%%n",
                        phase.phase(),
                        phase.nanos() / 1_000_000.0,
                        phase.nanos() * 100.0 / detail.parentNanos());
            }
            System.out.printf(Locale.ROOT, "%-52s %12.3f %9.2f%%%n",
                    "unattributed outer-call overhead",
                    detail.unattributedNanos() / 1_000_000.0,
                    detail.unattributedNanos() * 100.0
                            / detail.parentNanos());
            System.out.printf(Locale.ROOT, "%-52s %12.3f %9.2f%%%n%n",
                    "PARENT STEP",
                    detail.parentNanos() / 1_000_000.0,
                    100.0);
            if (!detail.counters().isEmpty()) {
                System.out.println("Observed startup work");
                System.out.printf(Locale.ROOT, "%-52s %12s%n",
                        "Counter", "value");
                for (CounterObservation counter : detail.counters()) {
                    System.out.printf(Locale.ROOT, "%-52s %12d%n",
                            counter.counter(), counter.value());
                }
                System.out.println();
            }
        }
    }

    private String json(
            long totalNanos,
            List<DetailReport> detailReports) throws IOException {
        return JSON.writeValueAsString(new TimingReport(
                "blue.coordination/basic-test-timings/2.0",
                reportId,
                title,
                "nanoseconds",
                List.copyOf(timings),
                totalNanos,
                detailReports)) + '\n';
    }

    private List<DetailReport> detailReports() {
        List<DetailReport> result = new ArrayList<>(details.size());
        for (DetailSection detail : details) {
            result.add(detail.snapshot(timings));
        }
        return List.copyOf(result);
    }

    private static void writeAtomically(
            Path report,
            String content) throws IOException {
        if (Files.exists(report)) {
            throw new FileAlreadyExistsException(report.toString());
        }
        Path temporary = Files.createTempFile(
                report.getParent(),
                "." + report.getFileName(),
                ".tmp");
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        report,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, report);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static final class MeasuredResource<T extends AutoCloseable>
            implements AutoCloseable {
        private final BasicTestMetrics metrics;
        private final String closeStep;
        private final T resource;
        private boolean closed;

        private MeasuredResource(
                BasicTestMetrics metrics,
                String closeStep,
                T resource) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.closeStep = Objects.requireNonNull(
                    closeStep, "closeStep");
            this.resource = Objects.requireNonNull(resource, "resource");
        }

        T value() {
            return resource;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    metrics.measure(closeStep, resource::close);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while closing measured resource",
                            interrupted);
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException(
                            "Cannot close measured resource", failure);
                }
            }
        }
    }

    static final class DetailSection {
        private final String id;
        private final String parentStep;
        private final Map<String, Long> phases = new LinkedHashMap<>();
        private final Map<String, Long> counters = new LinkedHashMap<>();

        private DetailSection(String id, String parentStep) {
            this.id = id;
            this.parentStep = parentStep;
        }

        DetailSection phase(String phase, long nanos) {
            putUnique(phases, requireText(phase, "phase"), nanos, "phase");
            return this;
        }

        DetailSection counter(String counter, long value) {
            putUnique(
                    counters,
                    requireText(counter, "counter"),
                    value,
                    "counter");
            return this;
        }

        private DetailReport snapshot(List<StepTiming> timings) {
            List<StepTiming> parents = timings.stream()
                    .filter(timing -> timing.step().equals(parentStep))
                    .toList();
            if (parents.size() != 1) {
                throw new IllegalStateException(
                        "Detail " + id + " requires exactly one parent step "
                                + parentStep + "; matches=" + parents.size());
            }
            long parentNanos = parents.get(0).nanos();
            long attributedNanos = 0L;
            List<PhaseTiming> frozenPhases = new ArrayList<>(phases.size());
            for (Map.Entry<String, Long> phase : phases.entrySet()) {
                attributedNanos = Math.addExact(
                        attributedNanos, phase.getValue());
                frozenPhases.add(new PhaseTiming(
                        phase.getKey(), phase.getValue()));
            }
            if (attributedNanos > parentNanos) {
                throw new IllegalStateException(
                        "Detail phases exceed parent step " + parentStep);
            }
            List<CounterObservation> frozenCounters = counters.entrySet()
                    .stream()
                    .map(counter -> new CounterObservation(
                            counter.getKey(), counter.getValue()))
                    .toList();
            return new DetailReport(
                    id,
                    parentStep,
                    parentNanos,
                    List.copyOf(frozenPhases),
                    attributedNanos,
                    parentNanos - attributedNanos,
                    frozenCounters);
        }

        private static void putUnique(
                Map<String, Long> destination,
                String name,
                long value,
                String kind) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        kind + " value must be non-negative: " + name);
            }
            if (destination.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate " + kind + ": " + name);
            }
        }
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }

    private record StepTiming(String step, long nanos) {
    }

    private record PhaseTiming(String phase, long nanos) {
    }

    private record CounterObservation(String counter, long value) {
    }

    private record DetailReport(
            String id,
            String parentStep,
            long parentNanos,
            List<PhaseTiming> phases,
            long attributedNanos,
            long unattributedNanos,
            List<CounterObservation> counters) {
    }

    private record TimingReport(
            String schema,
            String scenario,
            String title,
            String unit,
            List<StepTiming> steps,
            long totalNanos,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<DetailReport> details) {
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(label + " must be exact text");
        }
        return checked;
    }
}
