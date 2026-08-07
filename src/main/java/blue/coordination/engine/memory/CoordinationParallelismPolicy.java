package blue.coordination.engine.memory;

/** Immutable bounds for one event's Root fan-out. */
public final class CoordinationParallelismPolicy {

    private final int maximumConcurrentPreparations;
    private final boolean stopAfterFirstCanonicalFailure;

    public CoordinationParallelismPolicy(
            int maximumConcurrentPreparations,
            boolean stopAfterFirstCanonicalFailure) {
        if (maximumConcurrentPreparations < 1) {
            throw new IllegalArgumentException(
                    "maximumConcurrentPreparations must be positive");
        }
        this.maximumConcurrentPreparations = maximumConcurrentPreparations;
        this.stopAfterFirstCanonicalFailure = stopAfterFirstCanonicalFailure;
    }

    public static CoordinationParallelismPolicy lowLatencyDefault() {
        int processors = Runtime.getRuntime().availableProcessors();
        return new CoordinationParallelismPolicy(
                Math.max(1, Math.min(4, processors)), true);
    }

    public int maximumConcurrentPreparations() {
        return maximumConcurrentPreparations;
    }

    public boolean stopAfterFirstCanonicalFailure() {
        return stopAfterFirstCanonicalFailure;
    }
}
