package blue.coordination.fastpath;

import java.util.Objects;
import java.util.function.Function;

/**
 * Generation-bound plan cache. It caches only complete semantically verified
 * preparations, coalesces concurrent duplicate delivery, and invalidates an
 * old Root generation immediately after successful CAS publication.
 */
public final class PlanningFastPath<P> {
    private final BoundedSingleFlightCache<PlanCacheKey, P> plans;

    public PlanningFastPath(int maximumEntries, long maximumWeight,
            java.util.function.ToLongFunction<P> weigh) {
        this.plans = new BoundedSingleFlightCache<PlanCacheKey, P>(
                maximumEntries, maximumWeight, weigh);
    }

    public P prepare(
            PlanCacheKey key,
            AdmittedProjection projection,
            Function<AdmittedProjection.SelectedSurface, P> semanticPlanner) {
        PlanCacheKey exactKey = Objects.requireNonNull(key, "key");
        AdmittedProjection exactProjection = Objects.requireNonNull(
                projection, "projection");
        if (!exactKey.generation().equals(exactProjection.generation())) {
            throw new IllegalArgumentException(
                    "plan key and admitted projection generations differ");
        }
        return plans.getOrCompute(exactKey, ignored -> {
            AdmittedProjection.SelectedSurface selected =
                    exactProjection.select(exactKey.orderedCandidates());
            return Objects.requireNonNull(
                    semanticPlanner.apply(selected), "semanticPlanner result");
        });
    }

    /** Must be called only after the new session generation wins host CAS. */
    public int generationCommitted(
            String sessionId,
            ProjectionGenerationKey obsolete) {
        String exactSession = Objects.requireNonNull(sessionId, "sessionId");
        ProjectionGenerationKey old = Objects.requireNonNull(
                obsolete, "obsolete");
        return plans.invalidateIf(key -> key.sessionId().equals(exactSession)
                && key.generation().equals(old));
    }

    public CacheMetrics metrics() { return plans.metrics(); }
}
