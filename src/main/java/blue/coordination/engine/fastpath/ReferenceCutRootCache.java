package blue.coordination.engine.fastpath;

import blue.coordination.fastpath.BoundedSingleFlightCache;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Domain facade over the shared bounded single-flight fast-path cache.
 *
 * <p>The shared cache owns coalescing, retry cleanup, entry/weight bounds and
 * LRU eviction. This facade contributes the complete sparse-Root key and maps
 * each classified request to {@link ReferenceCutMetrics} exactly once.</p>
 */
public final class ReferenceCutRootCache {
    private static final int DEFAULT_MAXIMUM_ENTRIES = 1_024;
    /** Map node, flight entry, future, and LRU bookkeeping. */
    private static final long ENTRY_OVERHEAD_BYTES = 192L;

    private final ReferenceCutMetrics metrics;
    private final SharedBacking backing;

    public ReferenceCutRootCache(
            long maximumWeightBytes,
            ReferenceCutMetrics metrics) {
        this(DEFAULT_MAXIMUM_ENTRIES, maximumWeightBytes, metrics);
    }

    public ReferenceCutRootCache(
            int maximumEntries,
            long maximumWeightBytes,
            ReferenceCutMetrics metrics) {
        this(sharedBacking(maximumEntries, maximumWeightBytes), metrics);
    }

    /** Creates one opaque bounded kernel that compatible engines may share. */
    public static SharedBacking sharedBacking(long maximumWeightBytes) {
        return sharedBacking(DEFAULT_MAXIMUM_ENTRIES, maximumWeightBytes);
    }

    /** Creates one opaque bounded kernel that compatible engines may share. */
    public static SharedBacking sharedBacking(
            int maximumEntries,
            long maximumWeightBytes) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException(
                    "maximumEntries must be positive");
        }
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeightBytes must be positive");
        }
        return new SharedBacking(maximumEntries, maximumWeightBytes);
    }

    /** Creates one metrics facade over an already bounded opaque kernel. */
    public ReferenceCutRootCache(
            SharedBacking backing,
            ReferenceCutMetrics metrics) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ReferenceCutRootArtifact getOrBuild(
            ReferenceCutRootCacheKey key,
            Supplier<ReferenceCutRootArtifact> builder) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(builder, "builder");
        BoundedSingleFlightCache.Computation<ReferenceCutRootArtifact>
                computation = backing.cache.getOrComputeClassified(
                        key,
                        ignored -> builder.get());
        BoundedSingleFlightCache.Classification classification =
                computation.classification();
        if (classification
                == BoundedSingleFlightCache.Classification.HIT) {
            metrics.cacheHit();
        } else {
            metrics.cacheMiss();
            if (classification
                    == BoundedSingleFlightCache.Classification.LEADER) {
                metrics.cacheFlightLeader();
            } else {
                metrics.cacheFlightWaiter();
            }
        }
        try {
            return computation.value();
        } catch (RuntimeException | Error failure) {
            if (classification
                    == BoundedSingleFlightCache.Classification.LEADER) {
                metrics.cacheFailure();
            }
            throw failure;
        } finally {
            if (classification
                    == BoundedSingleFlightCache.Classification.LEADER) {
                metrics.cacheEvictions(computation.evictions());
                metrics.cacheLoadNanos(computation.loadNanos());
            }
        }
    }

    /**
     * Returns an already retained immutable artifact without recording a miss.
     * A hit is attributed to this facade exactly once; callers may perform
     * expensive preflight work only after a null result.
     */
    public ReferenceCutRootArtifact peek(ReferenceCutRootCacheKey key) {
        ReferenceCutRootArtifact retained = backing.cache.find(
                Objects.requireNonNull(key, "key"));
        if (retained != null) {
            metrics.cacheHit();
        }
        return retained;
    }

    public int size() { return backing.cache.retainedSize(); }

    public long currentWeightBytes() {
        return backing.cache.currentWeight();
    }

    /** Exact weigher used for admission, exposed for capacity planning. */
    public static long estimatedRetainedWeightBytes(
            ReferenceCutRootCacheKey key,
            ReferenceCutRootArtifact artifact) {
        long weight = ReferenceCutRootCacheKey.addWeight(
                ENTRY_OVERHEAD_BYTES,
                Objects.requireNonNull(
                        key, "key").approximateRetainedWeightBytes());
        return ReferenceCutRootCacheKey.addWeight(
                weight,
                Objects.requireNonNull(
                        artifact, "artifact")
                        .approximateRetainedWeightBytes());
    }

    /**
     * Opaque mutable cache kernel. Values are immutable sparse artifacts;
     * callers receive no entry, key, Node, or invalidation access.
     */
    public static final class SharedBacking {
        private final BoundedSingleFlightCache<ReferenceCutRootCacheKey,
                ReferenceCutRootArtifact> cache;

        private SharedBacking(
                int maximumEntries,
                long maximumWeightBytes) {
            this.cache = new BoundedSingleFlightCache<
                    ReferenceCutRootCacheKey, ReferenceCutRootArtifact>(
                    maximumEntries,
                    maximumWeightBytes,
                    ReferenceCutRootCache
                            ::estimatedRetainedWeightBytes);
        }
    }
}
