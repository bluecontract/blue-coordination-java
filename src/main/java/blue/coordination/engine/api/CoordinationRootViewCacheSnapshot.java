package blue.coordination.engine.api;

/**
 * Immutable live-work snapshot for one engine's bounded Root-view cache.
 *
 * <p>The counters describe process-local acceleration only. They are not
 * persisted and never participate in Coordination identities.</p>
 */
public final class CoordinationRootViewCacheSnapshot {

    private final int maximumSize;
    private final int currentSize;
    private final long hitCount;
    private final long missCount;
    private final long installationCount;
    private final long evictionCount;
    private final long maximumWeightBytes;
    private final long currentWeightBytes;

    public CoordinationRootViewCacheSnapshot(
            int maximumSize,
            int currentSize,
            long hitCount,
            long missCount,
            long installationCount,
            long evictionCount) {
        this(
                maximumSize,
                currentSize,
                hitCount,
                missCount,
                installationCount,
                evictionCount,
                Long.MAX_VALUE,
                0L);
    }

    public CoordinationRootViewCacheSnapshot(
            int maximumSize,
            int currentSize,
            long hitCount,
            long missCount,
            long installationCount,
            long evictionCount,
            long maximumWeightBytes,
            long currentWeightBytes) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException(
                    "maximumSize must be positive");
        }
        if (currentSize < 0 || currentSize > maximumSize) {
            throw new IllegalArgumentException(
                    "currentSize is outside the cache bound");
        }
        this.maximumSize = maximumSize;
        this.currentSize = currentSize;
        this.hitCount = nonNegative(hitCount, "hitCount");
        this.missCount = nonNegative(missCount, "missCount");
        this.installationCount = nonNegative(
                installationCount, "installationCount");
        this.evictionCount = nonNegative(
                evictionCount, "evictionCount");
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeightBytes must be positive");
        }
        if (currentWeightBytes < 0L
                || currentWeightBytes > maximumWeightBytes) {
            throw new IllegalArgumentException(
                    "currentWeightBytes is outside the cache bound");
        }
        this.maximumWeightBytes = maximumWeightBytes;
        this.currentWeightBytes = currentWeightBytes;
    }

    public int maximumSize() { return maximumSize; }
    public int currentSize() { return currentSize; }
    public long hitCount() { return hitCount; }
    public long missCount() { return missCount; }
    public long installationCount() { return installationCount; }
    public long evictionCount() { return evictionCount; }
    public long maximumWeightBytes() { return maximumWeightBytes; }
    public long currentWeightBytes() { return currentWeightBytes; }

    private static long nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
        return value;
    }
}
