package blue.coordination.examples.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Monotonic raw-sample helper used only by tagged performance tests. */
public final class MyOsLatencyProbe {

    private MyOsLatencyProbe() {
    }

    public static long measureNanos(Runnable operation) {
        Runnable checked = Objects.requireNonNull(operation, "operation");
        long startedNanos = System.nanoTime();
        checked.run();
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    public static List<Long> measureNanos(int sampleCount, Runnable operation) {
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
        Runnable checked = Objects.requireNonNull(operation, "operation");
        List<Long> samples = new ArrayList<>(sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            long startedNanos = System.nanoTime();
            checked.run();
            long elapsedNanos = Math.max(
                    0L, System.nanoTime() - startedNanos);
            samples.add(elapsedNanos);
        }
        return Collections.unmodifiableList(samples);
    }

    public static long percentile(List<Long> rawSamples, double quantile) {
        Objects.requireNonNull(rawSamples, "rawSamples");
        if (rawSamples.isEmpty()) {
            throw new IllegalArgumentException("rawSamples must not be empty");
        }
        if (!(quantile > 0.0d && quantile <= 1.0d)) {
            throw new IllegalArgumentException(
                    "quantile must be in the interval (0, 1]");
        }
        List<Long> sorted = new ArrayList<>(rawSamples.size());
        for (Long sample : rawSamples) {
            Long checked = Objects.requireNonNull(sample, "sample");
            if (checked < 0L) {
                throw new IllegalArgumentException(
                        "samples must be non-negative");
            }
            sorted.add(checked);
        }
        Collections.sort(sorted);
        int index = Math.max(
                0,
                (int) Math.ceil(sorted.size() * quantile) - 1);
        return sorted.get(index);
    }
}
