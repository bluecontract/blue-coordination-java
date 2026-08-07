package blue.coordination.fastpath;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Small dependency-free LRU cache with per-key request coalescing.
 *
 * <p>The loader never runs while the monitor is held. Concurrent callers for
 * one exact key await one computation. Failed computations are removed, so a
 * transient failure cannot poison later retries. Eviction never removes an
 * in-flight entry and considers both entry and caller-defined weight bounds.</p>
 */
public final class BoundedSingleFlightCache<K, V> {
    private final int maximumEntries;
    private final long maximumWeight;
    private final ToLongFunction<V> weigh;
    private final LinkedHashMap<K, Entry<V>> entries;
    private long currentWeight;
    private long hits;
    private long misses;
    private long loads;
    private long coalesced;
    private long failures;
    private long evictions;

    public BoundedSingleFlightCache(
            int maximumEntries,
            long maximumWeight,
            ToLongFunction<V> weigh) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        if (maximumWeight <= 0L) {
            throw new IllegalArgumentException("maximumWeight must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
        this.weigh = Objects.requireNonNull(weigh, "weigh");
        this.entries = new LinkedHashMap<K, Entry<V>>(
                Math.min(maximumEntries, 16), 0.75f, true);
    }

    public V getOrCompute(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Entry<V> entry;
        boolean owner = false;
        synchronized (this) {
            entry = entries.get(key);
            if (entry != null) {
                if (entry.future.isDone()) hits++;
                else coalesced++;
            } else {
                misses++;
                loads++;
                entry = new Entry<V>();
                entries.put(key, entry);
                owner = true;
            }
        }
        if (owner) {
            try {
                V value = Objects.requireNonNull(loader.apply(key), "loader result");
                long weight = positiveWeight(value);
                synchronized (this) {
                    entry.weight = weight;
                    if (entry.invalidated) {
                        entries.remove(key, entry);
                    } else {
                        currentWeight = Math.addExact(currentWeight, weight);
                    }
                    entry.future.complete(value);
                    if (!entry.invalidated) {
                        evictCompletedEldest();
                    }
                }
            } catch (Throwable failure) {
                entry.future.completeExceptionally(failure);
                synchronized (this) {
                    failures++;
                    entries.remove(key, entry);
                }
            }
        }
        return await(entry.future);
    }

    public synchronized V find(K key) {
        Entry<V> entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null || !entry.future.isDone()
                || entry.future.isCompletedExceptionally()) {
            misses++;
            return null;
        }
        hits++;
        return await(entry.future);
    }

    /**
     * Invalidates all generations rejected by the caller in one pass.
     * An in-flight computation remains available to its current waiters, but
     * is marked for removal as soon as it completes.
     */
    public synchronized int invalidateIf(Predicate<? super K> remove) {
        Objects.requireNonNull(remove, "remove");
        int removed = 0;
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> candidate = iterator.next();
            Entry<V> entry = candidate.getValue();
            if (!remove.test(candidate.getKey())) {
                continue;
            }
            removed++;
            if (entry.future.isDone()) {
                iterator.remove();
                currentWeight -= entry.weight;
            } else {
                entry.invalidated = true;
            }
        }
        return removed;
    }

    public synchronized void clear() {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<V> entry = iterator.next().getValue();
            if (entry.future.isDone()) {
                iterator.remove();
                currentWeight -= entry.weight;
            } else {
                entry.invalidated = true;
            }
        }
    }

    public synchronized CacheMetrics metrics() {
        return new CacheMetrics(hits, misses, loads, coalesced, failures,
                evictions, entries.size(), currentWeight);
    }

    private long positiveWeight(V value) {
        long result = weigh.applyAsLong(value);
        if (result <= 0L) {
            throw new IllegalArgumentException("cache weight must be positive");
        }
        return result;
    }

    private void evictCompletedEldest() {
        boolean over = entries.size() > maximumEntries
                || currentWeight > maximumWeight;
        while (over) {
            boolean removed = false;
            Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<K, Entry<V>> candidate = iterator.next();
                Entry<V> entry = candidate.getValue();
                if (!entry.future.isDone()) continue;
                iterator.remove();
                currentWeight -= entry.weight;
                evictions++;
                removed = true;
                break;
            }
            if (!removed) return;
            over = entries.size() > maximumEntries
                    || currentWeight > maximumWeight;
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("cache loader failed", cause);
        }
    }

    private static final class Entry<V> {
        private final CompletableFuture<V> future = new CompletableFuture<V>();
        private volatile long weight;
        private boolean invalidated;
    }
}
