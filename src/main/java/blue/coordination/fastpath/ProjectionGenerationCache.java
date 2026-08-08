package blue.coordination.fastpath;

import java.util.Objects;
import java.util.function.Function;

/**
 * Per-engine metrics facade over a checkpoint-shareable bounded projection
 * kernel. Only immutable {@link AdmittedProjection} values cross engines.
 */
public final class ProjectionGenerationCache {
    private final SharedBacking backing;
    private long hits;
    private long misses;
    private long loads;
    private long coalesced;
    private long failures;
    private long evictions;

    public ProjectionGenerationCache(int maximumEntries, long maximumWeight) {
        this(sharedBacking(maximumEntries, maximumWeight));
    }

    /** Creates one opaque bounded kernel that compatible engines may share. */
    public static SharedBacking sharedBacking(
            int maximumEntries,
            long maximumWeight) {
        return new SharedBacking(maximumEntries, maximumWeight);
    }

    /** Creates one engine-local metrics facade over an opaque shared kernel. */
    public ProjectionGenerationCache(SharedBacking backing) {
        this.backing = Objects.requireNonNull(backing, "backing");
    }

    public AdmittedProjection getOrCompile(
            ProjectionGenerationKey key,
            Function<ProjectionGenerationKey, AdmittedProjection> compiler) {
        ProjectionGenerationKey exact = Objects.requireNonNull(key, "key");
        Function<ProjectionGenerationKey, AdmittedProjection> checkedCompiler =
                Objects.requireNonNull(compiler, "compiler");
        BoundedSingleFlightCache.Computation<AdmittedProjection> computation =
                backing.projections.getOrComputeClassified(
                        exact,
                        ignored -> {
                            AdmittedProjection result =
                                    Objects.requireNonNull(
                                            checkedCompiler.apply(exact),
                                            "compiler result");
                            if (!exact.equals(result.generation())) {
                                throw new IllegalArgumentException(
                                        "compiled projection belongs to "
                                                + "another generation");
                            }
                            return result;
                        });
        BoundedSingleFlightCache.Classification classification =
                computation.classification();
        recordRequest(classification);
        try {
            return computation.value();
        } catch (RuntimeException | Error failure) {
            if (classification
                    == BoundedSingleFlightCache.Classification.LEADER) {
                recordFailure();
            }
            throw failure;
        } finally {
            if (classification
                    == BoundedSingleFlightCache.Classification.LEADER) {
                recordEvictions(computation.evictions());
            }
        }
    }

    public AdmittedProjection find(ProjectionGenerationKey key) {
        AdmittedProjection result = backing.projections.find(
                Objects.requireNonNull(key, "key"));
        synchronized (this) {
            if (result == null) {
                misses++;
            } else {
                hits++;
            }
        }
        return result;
    }

    /**
     * Publishes one already compiled immutable generation through the same
     * bounded single-flight admission path used by cold compilation. A racing
     * equivalent publication coalesces; a divergent value for one exact key is
     * rejected instead of replacing authoritative derived evidence.
     */
    public AdmittedProjection publish(AdmittedProjection candidate) {
        AdmittedProjection supplied = Objects.requireNonNull(
                candidate, "candidate");
        AdmittedProjection admitted = getOrCompile(
                supplied.generation(), ignored -> supplied);
        if (!admitted.projectionIdentity().equals(
                supplied.projectionIdentity())) {
            recordFailure();
            throw new IllegalStateException(
                    "projection generation is already bound to divergent evidence");
        }
        return admitted;
    }

    /**
     * Shared immutable generations are retained by hard LRU/weight limits.
     * Publication in one fixture fork must not evict a sibling's reusable
     * generation merely because both carry the same diagnostic session ID.
     */
    public int retainOnly(ProjectionGenerationKey current) {
        Objects.requireNonNull(current, "current");
        return 0;
    }

    /** Activity is facade-local; occupancy belongs to the shared backing. */
    public synchronized CacheMetrics metrics() {
        CacheMetrics backingMetrics = backing.projections.metrics();
        return new CacheMetrics(
                hits,
                misses,
                loads,
                coalesced,
                failures,
                evictions,
                backingMetrics.entries(),
                backingMetrics.weight(),
                backingMetrics.maximumEntries(),
                backingMetrics.maximumWeight(),
                backingMetrics.peakEntries(),
                backingMetrics.peakWeight(),
                backingMetrics.inFlight(),
                backingMetrics.peakInFlight(),
                backingMetrics.totalEntries(),
                backingMetrics.peakTotalEntries(),
                backingMetrics.rejections());
    }

    private synchronized void recordRequest(
            BoundedSingleFlightCache.Classification classification) {
        if (classification == BoundedSingleFlightCache.Classification.HIT) {
            hits++;
        } else if (classification
                == BoundedSingleFlightCache.Classification.LEADER) {
            misses++;
            loads++;
        } else {
            coalesced++;
        }
    }

    private synchronized void recordFailure() {
        failures++;
    }

    private synchronized void recordEvictions(long count) {
        evictions = Math.addExact(evictions, count);
    }

    /** Opaque mutable cache kernel with no entry or invalidation access. */
    public static final class SharedBacking {
        private final BoundedSingleFlightCache<ProjectionGenerationKey,
                AdmittedProjection> projections;

        private SharedBacking(int maximumEntries, long maximumWeight) {
            this.projections = new BoundedSingleFlightCache<
                    ProjectionGenerationKey, AdmittedProjection>(
                    maximumEntries,
                    maximumWeight,
                    AdmittedProjection::estimatedWeight);
        }
    }
}
