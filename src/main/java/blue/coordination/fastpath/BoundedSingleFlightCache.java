package blue.coordination.fastpath;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;

/**
 * Small dependency-free LRU cache with per-key request coalescing.
 *
 * <p>The loader never runs while the monitor is held. Concurrent callers for
 * one exact key await one computation. Failed computations are removed, so a
 * transient failure cannot poison later retries. Running and retained
 * generations share one hard entry bound. Admission evicts completed LRU
 * values first and fails fast when every slot is running; it never waits for
 * unrelated work while holding capacity. Completed values also obey the
 * caller-defined weight bound. An oversized value is returned to its current
 * flight but is not retained and does not displace valid cached values.</p>
 */
public final class BoundedSingleFlightCache<K, V> {
    private final int maximumEntries;
    private final long maximumWeight;
    private final ToLongBiFunction<? super K, ? super V> weigh;
    private final LinkedHashMap<K, Entry<V>> entries;
    private long currentWeight;
    private int retainedEntries;
    private int peakRetainedEntries;
    private long peakRetainedWeight;
    private int currentInFlight;
    private int peakInFlight;
    private int peakTotalEntries;
    private long hits;
    private long misses;
    private long loads;
    private long coalesced;
    private long failures;
    private long evictions;
    private long rejections;

    public BoundedSingleFlightCache(
            int maximumEntries,
            long maximumWeight,
            ToLongFunction<? super V> weigh) {
        this(
                maximumEntries,
                maximumWeight,
                keyAware(weigh));
    }

    /**
     * Creates a cache whose retained weight may include both key and value.
     * This is useful when immutable planning keys retain material path sets or
     * other evidence that is not reachable from the cached value.
     */
    public BoundedSingleFlightCache(
            int maximumEntries,
            long maximumWeight,
            ToLongBiFunction<? super K, ? super V> weigh) {
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
        return getOrComputeClassified(key, loader).value();
    }

    /**
     * Performs one lookup while retaining its exact per-call classification.
     *
     * <p>The returned handle is useful to domain facades that need production
     * metrics without inferring a call's outcome from racy before/after
     * snapshots. A leader executes its loader before this method returns;
     * waiters receive a handle immediately and block only in
     * {@link Computation#value()}.</p>
     */
    public Computation<V> getOrComputeClassified(
            K key,
            Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Entry<V> entry;
        boolean owner = false;
        Classification classification;
        synchronized (this) {
            entry = entries.get(key);
            if (entry != null) {
                if (entry.future.isDone()) {
                    hits++;
                    classification = Classification.HIT;
                } else {
                    coalesced++;
                    classification = Classification.WAITER;
                }
            } else {
                misses++;
                entry = new Entry<V>();
                entry.evictions = admitFlightLocked();
                entries.put(key, entry);
                currentInFlight++;
                peakInFlight = Math.max(peakInFlight, currentInFlight);
                peakTotalEntries = Math.max(
                        peakTotalEntries, totalEntriesLocked());
                loads++;
                owner = true;
                classification = Classification.LEADER;
            }
        }
        if (owner) {
            long startedNanos = System.nanoTime();
            try {
                V value = Objects.requireNonNull(loader.apply(key), "loader result");
                long weight = positiveWeight(key, value);
                synchronized (this) {
                    finishFlightLocked(entry);
                    entry.weight = weight;
                    entry.completed = true;
                    int callEvictions = entry.evictions;
                    if (entry.invalidated || entries.get(key) != entry) {
                        entries.remove(key, entry);
                    } else if (weight > maximumWeight) {
                        /* Return an oversized value to this flight without
                         * evicting otherwise valid retained entries. */
                        entries.remove(key, entry);
                        evictions++;
                        callEvictions++;
                    } else {
                        callEvictions += evictUntilWeightFitsLocked(weight);
                        currentWeight = Math.addExact(currentWeight, weight);
                        entry.retained = true;
                        retainedEntries++;
                        peakRetainedEntries = Math.max(
                                peakRetainedEntries, retainedEntries);
                        peakRetainedWeight = Math.max(
                                peakRetainedWeight, currentWeight);
                    }
                    entry.evictions = callEvictions;
                    entry.retainedAfterLoad = entry.retained;
                    entry.loadNanos = elapsedNanos(startedNanos);
                }
                entry.future.complete(value);
            } catch (Throwable failure) {
                synchronized (this) {
                    failures++;
                    entries.remove(key, entry);
                    finishFlightLocked(entry);
                    if (entry.retained) {
                        currentWeight -= entry.weight;
                        retainedEntries--;
                    }
                    entry.completed = true;
                    entry.retained = false;
                    entry.loadNanos = elapsedNanos(startedNanos);
                }
                entry.future.completeExceptionally(failure);
            }
        }
        return new Computation<V>(entry, classification);
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
     * Invalidates all discoverable generations rejected by the caller in one
     * pass. An in-flight computation remains available to callers that already
     * hold its computation handle, but is detached immediately so a new caller
     * can never discover the invalidated generation. Physical work continues
     * to occupy capacity until it completes.
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
            iterator.remove();
            if (entry.completed) {
                if (entry.retained) {
                    currentWeight -= entry.weight;
                    retainedEntries--;
                    entry.retained = false;
                }
            } else {
                entry.invalidated = true;
            }
        }
        return removed;
    }

    /**
     * Clears retained values only when all physical flights are complete.
     * Rejecting an active clear preserves the compatibility facade's historic
     * one-compiler guarantee.
     */
    public synchronized void clear() {
        if (currentInFlight != 0) {
            throw new IllegalStateException(
                    "Cannot clear a cache with in-flight work");
        }
        for (Entry<V> entry : entries.values()) {
            entry.retained = false;
        }
        entries.clear();
        currentWeight = 0L;
        retainedEntries = 0;
    }

    public synchronized CacheMetrics metrics() {
        return new CacheMetrics(hits, misses, loads, coalesced, failures,
                evictions, retainedEntries, currentWeight,
                maximumEntries, maximumWeight,
                peakRetainedEntries, peakRetainedWeight,
                currentInFlight, peakInFlight,
                totalEntriesLocked(), peakTotalEntries,
                rejections);
    }

    /** Number of completed values retained for future hits. */
    public synchronized int retainedSize() {
        return retainedEntries;
    }

    /** Exact completed-value weight retained by this cache. */
    public synchronized long currentWeight() {
        return currentWeight;
    }

    /** Includes invalidated flights that are still physically executing. */
    public synchronized int inFlightSize() {
        return currentInFlight;
    }

    private long positiveWeight(K key, V value) {
        long result = weigh.applyAsLong(key, value);
        if (result <= 0L) {
            throw new IllegalArgumentException("cache weight must be positive");
        }
        return result;
    }

    private int admitFlightLocked() {
        int removed = 0;
        while (totalEntriesLocked() >= maximumEntries) {
            if (!evictOneRetainedLocked()) {
                rejections++;
                throw new RejectedExecutionException(
                        "cache single-flight capacity exhausted");
            }
            removed++;
        }
        return removed;
    }

    private int evictUntilWeightFitsLocked(long incomingWeight) {
        int removed = 0;
        while (currentWeight > maximumWeight - incomingWeight) {
            if (!evictOneRetainedLocked()) {
                throw new IllegalStateException(
                        "retained cache weight accounting is inconsistent");
            }
            removed++;
        }
        return removed;
    }

    private boolean evictOneRetainedLocked() {
        Iterator<Map.Entry<K, Entry<V>>> iterator =
                entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<V> entry = iterator.next().getValue();
            if (!entry.completed || !entry.retained) continue;
            iterator.remove();
            currentWeight -= entry.weight;
            entry.retained = false;
            retainedEntries--;
            evictions++;
            return true;
        }
        return false;
    }

    private void finishFlightLocked(Entry<V> entry) {
        if (entry.flightFinished) return;
        entry.flightFinished = true;
        currentInFlight--;
        if (currentInFlight < 0) {
            throw new IllegalStateException(
                    "cache in-flight accounting became negative");
        }
    }

    private int totalEntriesLocked() {
        return Math.addExact(retainedEntries, currentInFlight);
    }

    private static <K, V> ToLongBiFunction<K, V> keyAware(
            ToLongFunction<? super V> weigh) {
        final ToLongFunction<? super V> checked =
                Objects.requireNonNull(weigh, "weigh");
        return (key, value) -> checked.applyAsLong(value);
    }

    private static long elapsedNanos(long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos);
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

    /** Exact role played by one cache request. */
    public enum Classification {
        HIT,
        LEADER,
        WAITER
    }

    /**
     * One classified request and its eventual value.
     *
     * <p>Call {@link #value()} before reading leader load evidence. Eviction
     * and load-time values are deliberately zero for hits and waiters so one
     * physical load can never be counted more than once.</p>
     */
    public static final class Computation<V> {
        private final Entry<V> entry;
        private final Classification classification;

        private Computation(
                Entry<V> entry,
                Classification classification) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.classification = Objects.requireNonNull(
                    classification, "classification");
        }

        public Classification classification() {
            return classification;
        }

        public V value() {
            return await(entry.future);
        }

        public long loadNanos() {
            return classification == Classification.LEADER
                    ? entry.loadNanos
                    : 0L;
        }

        public int evictions() {
            return classification == Classification.LEADER
                    ? entry.evictions
                    : 0;
        }

        /** Whether this leader's value survived admission and eviction. */
        public boolean retainedAfterLoad() {
            return classification == Classification.LEADER
                    && entry.retainedAfterLoad;
        }
    }

    private static final class Entry<V> {
        private final CompletableFuture<V> future = new CompletableFuture<V>();
        private volatile long weight;
        private volatile long loadNanos;
        private volatile int evictions;
        private volatile boolean completed;
        private volatile boolean retained;
        private volatile boolean retainedAfterLoad;
        private boolean invalidated;
        private boolean flightFinished;
    }
}
