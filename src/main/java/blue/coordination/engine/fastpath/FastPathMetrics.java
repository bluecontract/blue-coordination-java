package blue.coordination.engine.fastpath;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Low-contention nanosecond and work counters for the warm path. */
public final class FastPathMetrics {
    public enum Phase {
        CONTEXT_LOOKUP,
        BUNDLE_BIND,
        CONTRACTS_PROCESS,
        RETAINED_RESOLUTION,
        PROJECTION,
        TRANSITION,
        COMMIT
    }

    private final EnumMap<Phase, LongAdder> nanos =
            new EnumMap<Phase, LongAdder>(Phase.class);
    private final EnumMap<Phase, LongAdder> calls =
            new EnumMap<Phase, LongAdder>(Phase.class);

    public FastPathMetrics() {
        for (Phase phase : Phase.values()) {
            nanos.put(phase, new LongAdder());
            calls.put(phase, new LongAdder());
        }
    }

    public <T> T measure(Phase phase, Work<T> work) {
        Phase checked = Objects.requireNonNull(phase, "phase");
        long started = System.nanoTime();
        try {
            return Objects.requireNonNull(work, "work").run();
        } finally {
            nanos.get(checked).add(System.nanoTime() - started);
            calls.get(checked).increment();
        }
    }

    public void measure(Phase phase, Action action) {
        measure(phase, () -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    public Snapshot snapshot() {
        EnumMap<Phase, Long> time = new EnumMap<Phase, Long>(Phase.class);
        EnumMap<Phase, Long> count = new EnumMap<Phase, Long>(Phase.class);
        for (Phase phase : Phase.values()) {
            time.put(phase, nanos.get(phase).sum());
            count.put(phase, calls.get(phase).sum());
        }
        return new Snapshot(time, count);
    }

    @FunctionalInterface
    public interface Work<T> { T run(); }

    @FunctionalInterface
    public interface Action { void run(); }

    public static final class Snapshot {
        private final Map<Phase, Long> nanos;
        private final Map<Phase, Long> calls;

        private Snapshot(Map<Phase, Long> nanos, Map<Phase, Long> calls) {
            this.nanos = Collections.unmodifiableMap(nanos);
            this.calls = Collections.unmodifiableMap(calls);
        }

        public long nanos(Phase phase) { return nanos.get(phase); }
        public long calls(Phase phase) { return calls.get(phase); }
        public long totalNanos() {
            long result = 0L;
            for (Long value : nanos.values()) result += value.longValue();
            return result;
        }
        public Map<Phase, Long> allNanos() { return nanos; }
    }
}
