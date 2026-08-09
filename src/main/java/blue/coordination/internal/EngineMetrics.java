package blue.coordination.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Honest work counters and phase timers for the clean basic engine. */
final class EngineMetrics {
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
        Map<String, Long> phaseCopy = new LinkedHashMap<>();
        phaseNanos.forEach((name, value) -> phaseCopy.put(name, value.sum()));
        return new MetricsSnapshot(counterCopy, phaseCopy);
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
