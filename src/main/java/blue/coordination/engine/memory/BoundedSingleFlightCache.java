package blue.coordination.engine.memory;

import blue.coordination.fastpath.CacheMetrics;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Compatibility facade over the sole bounded single-flight implementation in
 * {@code blue.coordination.fastpath}. New code should use that implementation
 * directly; this type remains for source compatibility with public metrics.
 */
@Deprecated
public final class BoundedSingleFlightCache<K, V> {
    private final blue.coordination.fastpath.BoundedSingleFlightCache<K, V>
            delegate;

    public BoundedSingleFlightCache(int maximumEntries) {
        this(maximumEntries, Long.MAX_VALUE, ignored -> 1L);
    }

    public BoundedSingleFlightCache(
            int maximumEntries,
            long maximumWeight,
            ToLongFunction<? super V> weigher) {
        ToLongFunction<? super V> checked = Objects.requireNonNull(
                weigher, "weigher");
        this.delegate =
                new blue.coordination.fastpath.BoundedSingleFlightCache<K, V>(
                maximumEntries,
                maximumWeight,
                value -> checked.applyAsLong(value));
    }

    public V compute(
            K key,
            Function<? super K, ? extends V> compiler) {
        return delegate.getOrCompute(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(compiler, "compiler"));
    }

    public int size() {
        return delegate.metrics().entries();
    }

    public long retainedWeight() {
        return delegate.currentWeight();
    }

    public Snapshot metrics() {
        return new Snapshot(delegate.metrics());
    }

    public void clear() {
        delegate.clear();
    }

    /** Immutable compatibility view over the canonical cache metrics. */
    public static final class Snapshot {
        private final CacheMetrics metrics;

        private Snapshot(CacheMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
        }

        public long hits() { return metrics.hits(); }
        public long misses() { return metrics.misses(); }
        public long loads() { return metrics.loads(); }
        public long coalesced() { return metrics.coalesced(); }
        public long failures() { return metrics.failures(); }
        public long evictions() { return metrics.evictions(); }
        public int entries() { return metrics.entries(); }
        public long retainedWeight() { return metrics.weight(); }
        public int maximumEntries() { return metrics.maximumEntries(); }
        public long maximumWeight() { return metrics.maximumWeight(); }
        public int peakEntries() { return metrics.peakEntries(); }
        public long peakRetainedWeight() { return metrics.peakWeight(); }
        public int inFlight() { return metrics.inFlight(); }
        public int peakInFlight() { return metrics.peakInFlight(); }
        public int totalEntries() { return metrics.totalEntries(); }
        public int peakTotalEntries() {
            return metrics.peakTotalEntries();
        }
        public long rejections() { return metrics.rejections(); }
    }
}
