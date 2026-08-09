package blue.coordination.basic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small deterministic latency summary; not a replacement for JMH. */
final class LatencySeries {
    private final List<Long> nanos = new ArrayList<>();

    void add(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("latency must be non-negative");
        }
        nanos.add(value);
    }

    long medianNanos() {
        return percentileNanos(0.50);
    }

    long p95Nanos() {
        return percentileNanos(0.95);
    }

    long p99Nanos() {
        return percentileNanos(0.99);
    }

    long maxNanos() {
        if (nanos.isEmpty()) {
            throw new IllegalStateException("No samples");
        }
        return Collections.max(nanos);
    }

    int size() {
        return nanos.size();
    }

    private long percentileNanos(double quantile) {
        if (nanos.isEmpty()) {
            throw new IllegalStateException("No samples");
        }
        List<Long> ordered = new ArrayList<>(nanos);
        Collections.sort(ordered);
        int index = (int) Math.ceil(quantile * ordered.size()) - 1;
        return ordered.get(Math.max(0, Math.min(index, ordered.size() - 1)));
    }
}
