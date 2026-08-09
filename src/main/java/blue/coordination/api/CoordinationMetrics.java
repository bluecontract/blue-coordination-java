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
    /** Defensively copies measurements and validates non-negative gauges. */
    public CoordinationMetrics {
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(counters, "counters")));
        phaseNanos = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(phaseNanos, "phaseNanos")));
        if (documentCount < 0 || routeRowCount < 0 || journalEntryCount < 0
                || wholeObjectCount < 0 || logicalClockMicros <= 0L) {
            throw new IllegalArgumentException(
                    "Coordination metric gauges must be non-negative");
        }
    }

    /** Returns a named work counter, or zero when the phase did no work. */
    public long counter(String name) {
        return counters.getOrDefault(
                Objects.requireNonNull(name, "name"), 0L);
    }

    /** Returns accumulated nanoseconds for a named measured phase. */
    public long nanos(String phase) {
        return phaseNanos.getOrDefault(
                Objects.requireNonNull(phase, "phase"), 0L);
    }

    /** Returns accumulated milliseconds for a named measured phase. */
    public double millis(String phase) {
        return nanos(phase) / 1_000_000.0;
    }
}
