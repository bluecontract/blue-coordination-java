package blue.coordination.internal;

import blue.coordination.api.CoordinationMetrics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Honest work counters and phase timers for the temporal engine. */
final class EngineMetrics {
    private static final Map<CoordinationMetrics.Counter, List<String>>
            CANONICAL_SOURCES = canonicalSources();

    private final Map<String, LongAdder> counters = new LinkedHashMap<>();
    private final Map<String, LongAdder> phaseNanos = new LinkedHashMap<>();

    public <T> T timed(String phase, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        long started = System.nanoTime();
        try {
            return work.get();
        } finally {
            addNanos(phase, System.nanoTime() - started);
        }
    }

    public void timed(String phase, Runnable work) {
        timed(phase, () -> {
            work.run();
            return null;
        });
    }

    public synchronized void increment(String counter) {
        add(counter, 1L);
    }

    public synchronized void add(String counter, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("counter delta must be non-negative");
        }
        counters.computeIfAbsent(requireText(counter), ignored -> new LongAdder())
                .add(value);
    }

    public synchronized void addNanos(String phase, long nanos) {
        if (nanos < 0L) {
            throw new IllegalArgumentException("nanos must be non-negative");
        }
        phaseNanos.computeIfAbsent(requireText(phase), ignored -> new LongAdder())
                .add(nanos);
    }

    public synchronized long counter(String name) {
        LongAdder value = counters.get(name);
        return value == null ? 0L : value.sum();
    }

    public synchronized long phaseNanos(String name) {
        LongAdder value = phaseNanos.get(name);
        return value == null ? 0L : value.sum();
    }

    public synchronized MetricsSnapshot snapshot() {
        Map<String, Long> counterCopy = new LinkedHashMap<>();
        counters.forEach((name, value) -> counterCopy.put(name, value.sum()));
        addCanonicalCounters(counterCopy);
        Map<String, Long> phaseCopy = new LinkedHashMap<>();
        phaseNanos.forEach((name, value) -> phaseCopy.put(name, value.sum()));
        return new MetricsSnapshot(counterCopy, phaseCopy);
    }

    /** Closed public projection; raw diagnostics remain test-fixture-only. */
    public synchronized MetricsSnapshot publicSnapshot() {
        MetricsSnapshot diagnostic = snapshot();
        Map<String, Long> publicCounters = new LinkedHashMap<>();
        for (CoordinationMetrics.Counter counter
                : CoordinationMetrics.Counter.values()) {
            publicCounters.put(
                    counter.name(), diagnostic.counters().get(counter.name()));
        }
        Map<String, Long> publicPhases = new LinkedHashMap<>();
        for (CoordinationMetrics.Phase phase
                : CoordinationMetrics.Phase.values()) {
            publicPhases.put(
                    phase.metricName(),
                    diagnostic.phaseNanos().getOrDefault(
                            phase.metricName(), 0L));
        }
        return new MetricsSnapshot(publicCounters, publicPhases);
    }

    private static void addCanonicalCounters(Map<String, Long> snapshot) {
        for (CoordinationMetrics.Counter counter
                : CoordinationMetrics.Counter.values()) {
            long value = 0L;
            for (String source : CANONICAL_SOURCES.getOrDefault(
                    counter, List.of())) {
                value = Math.addExact(
                        value, snapshot.getOrDefault(source, 0L));
            }
            snapshot.put(counter.name(), value);
        }
    }

    private static Map<CoordinationMetrics.Counter, List<String>>
            canonicalSources() {
        Map<CoordinationMetrics.Counter, List<String>> result =
                new EnumMap<>(CoordinationMetrics.Counter.class);
        result.put(CoordinationMetrics.Counter.ENTRIES_STORED_WHOLE,
                List.of("journal.entriesStoredWhole"));
        result.put(CoordinationMetrics.Counter.ROUTE_INDEX_LOOKUPS,
                List.of("routing.lookups"));
        result.put(CoordinationMetrics.Counter.GRAPH_SNAPSHOTS_REUSED,
                List.of("temporal.graphSnapshotsReused"));
        result.put(CoordinationMetrics.Counter.GRAPH_RECONCILIATIONS,
                List.of("temporal.graphReconciliations"));
        result.put(CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS,
                List.of("documentStart.sessionsInitialized"));
        result.put(CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS,
                List.of("deliveryReceiptsCommitted"));
        result.put(CoordinationMetrics.Counter.EMBEDDED_EPOCH_PROCESS_CALLS,
                List.of("process.embeddedEpochProcessCalls"));
        result.put(CoordinationMetrics.Counter.CHILD_EPOCHS_COMMITTED,
                List.of("temporal.childEpochsCommitted"));
        result.put(CoordinationMetrics.Counter.PARENT_EPOCH_APPLICATIONS,
                List.of("embedding.parentEpochApplications"));
        result.put(CoordinationMetrics.Counter.HISTORICAL_WINDOWS_OPENED,
                List.of("journal.historicalWindowsOpened"));
        result.put(CoordinationMetrics.Counter.HISTORICAL_ENTRIES_REPLAYED,
                List.of("temporal.historicalEntriesReplayed"));
        result.put(CoordinationMetrics.Counter.CATCH_UP_BARRIERS_CREATED,
                List.of("temporal.catchUpBarriersCreated"));
        result.put(CoordinationMetrics.Counter.CATCH_UP_BARRIERS_COMPLETED,
                List.of("temporal.catchUpBarriersCompleted"));
        result.put(CoordinationMetrics.Counter.UNRELATED_DOCUMENT_READS,
                List.of("temporal.unrelatedDocumentReads"));
        result.put(CoordinationMetrics.Counter.REQUEST_FRAGMENTS,
                List.of("append.requestFragments"));
        result.put(CoordinationMetrics.Counter.TIMELINE_ENTRY_FRAGMENTS,
                List.of("append.eventFragments"));
        result.put(CoordinationMetrics.Counter.ORDINARY_NODE_FRAGMENTS,
                List.of("layout.ordinaryNodeFragments"));
        result.put(CoordinationMetrics.Counter.FULL_ENVIRONMENT_SCANS,
                List.of("temporal.fullEnvironmentScans"));
        result.put(CoordinationMetrics.Counter.SOURCE_REPLAYS_PER_PARENT,
                List.of("temporal.sourceReplaysPerParent"));
        result.put(CoordinationMetrics.Counter.POST_PROCESS_FULL_PROJECTIONS,
                List.of("process.postProcessFullProjections"));
        if (result.size() != CoordinationMetrics.Counter.values().length) {
            throw new IllegalStateException(
                    "Every public counter requires an explicit work source");
        }
        return Collections.unmodifiableMap(result);
    }

    private static String requireText(String value) {
        String checked = Objects.requireNonNull(value, "name");
        if (checked.isBlank()) {
            throw new IllegalArgumentException("metric name must not be blank");
        }
        return checked;
    }

    public record MetricsSnapshot(
            Map<String, Long> counters,
            Map<String, Long> phaseNanos) {
        public MetricsSnapshot {
            counters = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(counters, "counters")));
            phaseNanos = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(phaseNanos, "phaseNanos")));
        }
    }
}
