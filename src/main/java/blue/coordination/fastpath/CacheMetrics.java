package blue.coordination.fastpath;

/** Immutable operational counters for bounded fast-path caches. */
public final class CacheMetrics {
    private final long hits;
    private final long misses;
    private final long loads;
    private final long coalesced;
    private final long failures;
    private final long evictions;
    private final int entries;
    private final long weight;

    CacheMetrics(long hits, long misses, long loads, long coalesced,
            long failures, long evictions, int entries, long weight) {
        this.hits = hits;
        this.misses = misses;
        this.loads = loads;
        this.coalesced = coalesced;
        this.failures = failures;
        this.evictions = evictions;
        this.entries = entries;
        this.weight = weight;
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public long loads() { return loads; }
    public long coalesced() { return coalesced; }
    public long failures() { return failures; }
    public long evictions() { return evictions; }
    public int entries() { return entries; }
    public long weight() { return weight; }
}
