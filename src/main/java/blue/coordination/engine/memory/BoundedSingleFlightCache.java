package blue.coordination.engine.memory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Entry- and retained-weight-bounded access-order cache with exactly one
 * compilation per key.
 *
 * <p>No caller work runs while the cache monitor is held. Failed
 * compilations are evicted so a later caller can retry. Eviction considers
 * completed entries only; removing an in-flight entry would permit a second
 * compiler to run for the same key. A value larger than the complete weight
 * budget is returned to its current callers but is not retained.</p>
 */
public final class BoundedSingleFlightCache<K, V> {

    private final int maximumEntries;
    private final long maximumWeight;
    private final ToLongFunction<? super V> weigher;
    private final Map<K, Entry<V>> entries;
    private long retainedWeight;
    private long hits;
    private long misses;
    private long loads;
    private long coalesced;
    private long failures;
    private long evictions;

    public BoundedSingleFlightCache(int maximumEntries) {
        this(
                maximumEntries,
                Long.MAX_VALUE,
                ignored -> 1L);
    }

    public BoundedSingleFlightCache(
            int maximumEntries,
            long maximumWeight,
            ToLongFunction<? super V> weigher) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException(
                    "maximumEntries must be positive");
        }
        if (maximumWeight <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeight must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        this.entries = new LinkedHashMap<K, Entry<V>>(
                16, 0.75f, true);
    }

    public V compute(
            K key,
            Function<? super K, ? extends V> compiler) {
        K checkedKey = Objects.requireNonNull(key, "key");
        Function<? super K, ? extends V> checkedCompiler =
                Objects.requireNonNull(compiler, "compiler");
        Entry<V> entry;
        boolean owner;
        synchronized (entries) {
            entry = entries.get(checkedKey);
            owner = entry == null;
            if (owner) {
                misses++;
                loads++;
                entry = new Entry<V>();
                entries.put(checkedKey, entry);
            } else if (entry.future.isDone()) {
                hits++;
            } else {
                coalesced++;
            }
        }

        if (owner) {
            try {
                V value = Objects.requireNonNull(
                        checkedCompiler.apply(checkedKey),
                        "compiler result");
                long weight = weigher.applyAsLong(value);
                if (weight <= 0L) {
                    throw new IllegalArgumentException(
                            "cache weight must be positive");
                }
                synchronized (entries) {
                    entry.weight = weight;
                    retainedWeight = Math.addExact(
                            retainedWeight, weight);
                    entry.future.complete(value);
                    evictCompletedEldest();
                }
            } catch (Throwable failure) {
                synchronized (entries) {
                    failures++;
                    entries.remove(checkedKey, entry);
                    entry.future.completeExceptionally(failure);
                }
                throw propagate(failure);
            }
        }

        try {
            return entry.future.join();
        } catch (CompletionException failure) {
            throw propagate(failure.getCause());
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public long retainedWeight() {
        synchronized (entries) {
            return retainedWeight;
        }
    }

    public Snapshot metrics() {
        synchronized (entries) {
            return new Snapshot(
                    hits,
                    misses,
                    loads,
                    coalesced,
                    failures,
                    evictions,
                    entries.size(),
                    retainedWeight,
                    maximumEntries,
                    maximumWeight);
        }
    }

    /**
     * Clears completed evidence. Clearing while compilation is active is
     * rejected because doing so would violate the one-compiler guarantee.
     */
    public void clear() {
        synchronized (entries) {
            for (Entry<V> entry : entries.values()) {
                if (!entry.future.isDone()) {
                    throw new IllegalStateException(
                            "Cannot clear a cache with in-flight work");
                }
            }
            entries.clear();
            retainedWeight = 0L;
        }
    }

    private void evictCompletedEldest() {
        while (entries.size() > maximumEntries
                || retainedWeight > maximumWeight) {
            boolean removed = false;
            Iterator<Map.Entry<K, Entry<V>>> iterator =
                    entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<V> candidate = iterator.next().getValue();
                if (candidate.future.isDone()) {
                    iterator.remove();
                    retainedWeight -= candidate.weight;
                    evictions++;
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return;
            }
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new IllegalStateException("Cache compilation failed", failure);
    }

    /** Immutable operational sample for one cache generation. */
    public static final class Snapshot {
        private final long hits;
        private final long misses;
        private final long loads;
        private final long coalesced;
        private final long failures;
        private final long evictions;
        private final int entries;
        private final long retainedWeight;
        private final int maximumEntries;
        private final long maximumWeight;

        private Snapshot(
                long hits,
                long misses,
                long loads,
                long coalesced,
                long failures,
                long evictions,
                int entries,
                long retainedWeight,
                int maximumEntries,
                long maximumWeight) {
            this.hits = hits;
            this.misses = misses;
            this.loads = loads;
            this.coalesced = coalesced;
            this.failures = failures;
            this.evictions = evictions;
            this.entries = entries;
            this.retainedWeight = retainedWeight;
            this.maximumEntries = maximumEntries;
            this.maximumWeight = maximumWeight;
        }

        public long hits() { return hits; }
        public long misses() { return misses; }
        public long loads() { return loads; }
        public long coalesced() { return coalesced; }
        public long failures() { return failures; }
        public long evictions() { return evictions; }
        public int entries() { return entries; }
        public long retainedWeight() { return retainedWeight; }
        public int maximumEntries() { return maximumEntries; }
        public long maximumWeight() { return maximumWeight; }
    }

    private static final class Entry<T> {
        private final CompletableFuture<T> future =
                new CompletableFuture<T>();
        private long weight;
    }
}
