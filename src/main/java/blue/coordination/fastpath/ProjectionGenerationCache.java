package blue.coordination.fastpath;

import java.util.Objects;
import java.util.function.Function;

/** Bounded admission-time cache for compiled subscription projections. */
public final class ProjectionGenerationCache {
    private final BoundedSingleFlightCache<ProjectionGenerationKey,
            AdmittedProjection> projections;

    public ProjectionGenerationCache(int maximumEntries, long maximumWeight) {
        this.projections = new BoundedSingleFlightCache<ProjectionGenerationKey,
                AdmittedProjection>(maximumEntries, maximumWeight,
                AdmittedProjection::estimatedWeight);
    }

    public AdmittedProjection getOrCompile(
            ProjectionGenerationKey key,
            Function<ProjectionGenerationKey, AdmittedProjection> compiler) {
        ProjectionGenerationKey exact = Objects.requireNonNull(key, "key");
        return projections.getOrCompute(exact, ignored -> {
            AdmittedProjection result = Objects.requireNonNull(
                    compiler.apply(exact), "compiler result");
            if (!exact.equals(result.generation())) {
                throw new IllegalArgumentException(
                        "compiled projection belongs to another generation");
            }
            return result;
        });
    }

    public AdmittedProjection find(ProjectionGenerationKey key) {
        return projections.find(key);
    }

    public int retainOnly(ProjectionGenerationKey current) {
        ProjectionGenerationKey exact = Objects.requireNonNull(current, "current");
        return projections.invalidateIf(key -> key.sessionId().equals(exact.sessionId())
                && !key.equals(exact));
    }

    public CacheMetrics metrics() { return projections.metrics(); }
}
