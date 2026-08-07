package blue.coordination.engine.memory;

/** Immutable exact work measured at live engine observer call sites. */
public final class CoordinationEngineWorkSnapshot {

    private final long plans;
    private final long bundleLoads;
    private final long bundleBatches;
    private final long loadedFragmentIdentities;
    private final long loadedBytes;
    private final long processCompletions;
    private final long commitAttempts;
    private final long committed;
    private final long alreadyCommitted;
    private final long conflicts;

    public CoordinationEngineWorkSnapshot(
            long plans,
            long bundleLoads,
            long bundleBatches,
            long loadedFragmentIdentities,
            long loadedBytes,
            long processCompletions,
            long commitAttempts,
            long committed,
            long alreadyCommitted,
            long conflicts) {
        this.plans = nonNegative(plans, "plans");
        this.bundleLoads = nonNegative(bundleLoads, "bundleLoads");
        this.bundleBatches = nonNegative(bundleBatches, "bundleBatches");
        this.loadedFragmentIdentities = nonNegative(
                loadedFragmentIdentities, "loadedFragmentIdentities");
        this.loadedBytes = nonNegative(loadedBytes, "loadedBytes");
        this.processCompletions = nonNegative(
                processCompletions, "processCompletions");
        this.commitAttempts = nonNegative(
                commitAttempts, "commitAttempts");
        this.committed = nonNegative(committed, "committed");
        this.alreadyCommitted = nonNegative(
                alreadyCommitted, "alreadyCommitted");
        this.conflicts = nonNegative(conflicts, "conflicts");
    }

    /** Subtracts an earlier monotonic snapshot. */
    public CoordinationEngineWorkSnapshot minus(
            CoordinationEngineWorkSnapshot before) {
        if (before == null) {
            throw new NullPointerException("before");
        }
        return new CoordinationEngineWorkSnapshot(
                plans - before.plans,
                bundleLoads - before.bundleLoads,
                bundleBatches - before.bundleBatches,
                loadedFragmentIdentities - before.loadedFragmentIdentities,
                loadedBytes - before.loadedBytes,
                processCompletions - before.processCompletions,
                commitAttempts - before.commitAttempts,
                committed - before.committed,
                alreadyCommitted - before.alreadyCommitted,
                conflicts - before.conflicts);
    }

    public long plans() { return plans; }
    public long bundleLoads() { return bundleLoads; }
    public long bundleBatches() { return bundleBatches; }
    public long loadedFragmentIdentities() { return loadedFragmentIdentities; }
    public long loadedBytes() { return loadedBytes; }
    public long processCompletions() { return processCompletions; }
    public long commitAttempts() { return commitAttempts; }
    public long committed() { return committed; }
    public long alreadyCommitted() { return alreadyCommitted; }
    public long conflicts() { return conflicts; }

    private static long nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
        return value;
    }
}
