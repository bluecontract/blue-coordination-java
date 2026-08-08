package blue.coordination.engine.memory;

import java.util.Objects;

/** Immutable live evidence for the bounded Root-preparation executor. */
public final class CoordinationRootPreparationPoolSnapshot {
    private final int configuredParallelism;
    private final int activeThreads;
    private final int poolSize;
    private final int queuedTasks;
    private final long completedTasks;
    private final int largestPoolSize;

    CoordinationRootPreparationPoolSnapshot(
            int configuredParallelism,
            int activeThreads,
            int poolSize,
            int queuedTasks,
            long completedTasks,
            int largestPoolSize) {
        if (configuredParallelism <= 0
                || activeThreads < 0
                || poolSize < 0
                || queuedTasks < 0
                || completedTasks < 0L
                || largestPoolSize < 0) {
            throw new IllegalArgumentException(
                    "Root-preparation pool counters are invalid");
        }
        this.configuredParallelism = configuredParallelism;
        this.activeThreads = activeThreads;
        this.poolSize = poolSize;
        this.queuedTasks = queuedTasks;
        this.completedTasks = completedTasks;
        this.largestPoolSize = largestPoolSize;
    }

    public int configuredParallelism() { return configuredParallelism; }
    public int activeThreads() { return activeThreads; }
    public int poolSize() { return poolSize; }
    public int queuedTasks() { return queuedTasks; }
    public long completedTasks() { return completedTasks; }
    public int largestPoolSize() { return largestPoolSize; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof CoordinationRootPreparationPoolSnapshot)) {
            return false;
        }
        CoordinationRootPreparationPoolSnapshot other =
                (CoordinationRootPreparationPoolSnapshot) value;
        return configuredParallelism == other.configuredParallelism
                && activeThreads == other.activeThreads
                && poolSize == other.poolSize
                && queuedTasks == other.queuedTasks
                && completedTasks == other.completedTasks
                && largestPoolSize == other.largestPoolSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Integer.valueOf(configuredParallelism),
                Integer.valueOf(activeThreads),
                Integer.valueOf(poolSize),
                Integer.valueOf(queuedTasks),
                Long.valueOf(completedTasks),
                Integer.valueOf(largestPoolSize));
    }
}
