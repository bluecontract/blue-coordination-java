package blue.coordination.fastpath;

import blue.language.processor.ExternalOrderKey;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Measures selected-set locality independently of frozen Contracts cost. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class AdmittedProjectionBenchmark {
    @Param({"100", "1000", "4096"})
    public int occurrences;

    private AdmittedProjection projection;
    private List<String> twoCandidates;
    private PlanningFastPath<String> planCache;
    private PlanCacheKey cacheKey;

    @Setup(Level.Trial)
    public void setup() {
        ProjectionGenerationKey generation = new ProjectionGenerationKey(
                "environment", "session", "root", 7L,
                "inventory", "subscriptions", "runtime");
        List<AdmittedOccurrence> values = new ArrayList<AdmittedOccurrence>();
        for (int index = 0; index < occurrences; index++) {
            String path = "/documents/" + index;
            values.add(new AdmittedOccurrence(
                    "occurrence-" + index, path, "scope-" + index,
                    "channel-" + index, "type", index,
                    "header-" + index, "checkpoint-" + index,
                    Arrays.asList("root", "scope-" + index),
                    Collections.singletonList("source-" + index),
                    Collections.singletonList("dependency-" + index),
                    Collections.singletonList("timeline:" + (index % 16)),
                    Arrays.asList(path, path + "/contracts")));
        }
        projection = new AdmittedProjection(generation, values);
        twoCandidates = Arrays.asList("occurrence-10", "occurrence-11");
        planCache = new PlanningFastPath<String>(16, 4096L, String::length);
        cacheKey = new PlanCacheKey(
                generation,
                "event",
                "event-inventory",
                ExternalOrderKey.of(Arrays.<Object>asList("order")),
                twoCandidates,
                "policy");
        planCache.prepare(cacheKey, projection,
                selected -> selected.publicKeys().toString());
    }

    @Benchmark
    public AdmittedProjection.SelectedSurface selectTwoOccurrences() {
        return projection.select(twoCandidates);
    }

    @Benchmark
    public String warmVerifiedPlan() {
        return planCache.prepare(cacheKey, projection,
                selected -> selected.publicKeys().toString());
    }

    @Benchmark
    public java.util.Set<String> invalidateOneDependencyBranch() {
        return projection.affectedOccurrences(
                Collections.singletonList("/documents/10/contracts"));
    }
}
