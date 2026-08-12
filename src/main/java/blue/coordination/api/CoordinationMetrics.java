package blue.coordination.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable work counters, phase timers, and in-memory engine gauges. */
public record CoordinationMetrics(
        Map<String, Long> counters,
        Map<String, Long> phaseNanos,
        int documentCount,
        int routeRowCount,
        int journalEntryCount,
        int wholeObjectCount,
        long logicalClockMicros) {
    /** Closed, release-gated structural counter vocabulary. */
    public enum Counter {
        ENTRIES_STORED_WHOLE,
        ROUTE_INDEX_LOOKUPS,
        GRAPH_SNAPSHOTS_REUSED,
        GRAPH_RECONCILIATIONS,
        DOCUMENT_INITIALIZATIONS,
        EXTERNAL_PROCESS_CALLS,
        EMBEDDED_EPOCH_PROCESS_CALLS,
        CHILD_EPOCHS_COMMITTED,
        PARENT_EPOCH_APPLICATIONS,
        HISTORICAL_WINDOWS_OPENED,
        HISTORICAL_ENTRIES_REPLAYED,
        CATCH_UP_BARRIERS_CREATED,
        CATCH_UP_BARRIERS_COMPLETED,
        UNRELATED_DOCUMENT_READS,
        REQUEST_FRAGMENTS,
        TIMELINE_ENTRY_FRAGMENTS,
        ORDINARY_NODE_FRAGMENTS,
        FULL_ENVIRONMENT_SCANS,
        SOURCE_REPLAYS_PER_PARENT,
        POST_PROCESS_FULL_PROJECTIONS,
        PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY,
        CHILD_PROCESS_RERUNS_ON_PARENT_RETRY
    }

    /** Closed, documented phase-timer vocabulary. */
    public enum Phase {
        APPEND_TOTAL("append.total"),
        TEMPORAL_DRAIN("temporal.drain"),
        PROCESS_ROUTE_LOOKUP("process.routeLookup"),
        PROCESS_HOST_BEFORE_FROZEN("process.hostBeforeFrozen"),
        PROCESS_FROZEN_CONTRACTS_ONCE("process.frozenContractsOnce"),
        PROCESS_FROZEN("process.frozen"),
        PROCESS_EMBEDDED_FROZEN("process.embeddedFrozen"),
        PROCESS_HOST_AFTER_FROZEN("process.hostAfterFrozen"),
        LAYOUT_COMPILE_FROZEN_CATALOG("layout.compileFrozenCatalog"),
        LAYOUT_RETAIN_EMBEDDED_ONLY("layout.retainEmbeddedOnly");

        private final String metricName;

        Phase(String metricName) {
            this.metricName = metricName;
        }

        /** Stable public metric name. */
        public String metricName() {
            return metricName;
        }

        private static Phase fromMetricName(String name) {
            for (Phase phase : values()) {
                if (phase.metricName.equals(name)) {
                    return phase;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown Coordination phase " + name);
        }
    }

    /** Defensively copies measurements and validates non-negative gauges. */
    public CoordinationMetrics {
        Map<String, Long> counterCopy = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) {
            counterCopy.put(counter.name(), 0L);
        }
        Objects.requireNonNull(counters, "counters").forEach(
                (name, value) -> counterCopy.put(requireCounter(name).name(),
                        requireNonNegative(value, "counter " + name)));
        counters = Collections.unmodifiableMap(counterCopy);
        Map<String, Long> phaseCopy = new LinkedHashMap<>();
        for (Phase phase : Phase.values()) {
            phaseCopy.put(phase.metricName(), 0L);
        }
        Objects.requireNonNull(phaseNanos, "phaseNanos").forEach(
                (name, value) -> phaseCopy.put(requirePhase(name).metricName(),
                        requireNonNegative(value, "phase " + name)));
        phaseNanos = Collections.unmodifiableMap(phaseCopy);
        if (documentCount < 0 || routeRowCount < 0 || journalEntryCount < 0
                || wholeObjectCount < 0 || logicalClockMicros <= 0L) {
            throw new IllegalArgumentException(
                    "Coordination metric gauges must be non-negative");
        }
    }

    /** Returns a known work counter and rejects misspelled/unknown names. */
    public long counter(String name) {
        return counter(requireCounter(name));
    }

    /** Type-safe lookup for one canonical structural counter. */
    public long counter(Counter counter) {
        return counters.get(Objects.requireNonNull(
                counter, "counter").name());
    }

    /** Returns accumulated nanoseconds for a named measured phase. */
    public long nanos(String phase) {
        return nanos(requirePhase(phase));
    }

    /** Type-safe lookup for one documented phase timer. */
    public long nanos(Phase phase) {
        return phaseNanos.get(Objects.requireNonNull(
                phase, "phase").metricName());
    }

    /** Returns accumulated milliseconds for a named measured phase. */
    public double millis(String phase) {
        return nanos(phase) / 1_000_000.0;
    }

    /** Type-safe millisecond conversion for one documented phase timer. */
    public double millis(Phase phase) {
        return nanos(phase) / 1_000_000.0;
    }

    private static Counter requireCounter(String name) {
        String checked = requireMetricName(name, "counter");
        try {
            return Counter.valueOf(checked);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Unknown Coordination counter " + checked,
                    failure);
        }
    }

    private static Phase requirePhase(String name) {
        return Phase.fromMetricName(requireMetricName(name, "phase"));
    }

    private static String requireMetricName(String name, String kind) {
        String checked = Objects.requireNonNull(name, kind + " name");
        if (checked.isBlank()) {
            throw new IllegalArgumentException(
                    kind + " name must not be blank");
        }
        return checked;
    }

    private static long requireNonNegative(Long value, String label) {
        long checked = Objects.requireNonNull(value, label);
        if (checked < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return checked;
    }
}
