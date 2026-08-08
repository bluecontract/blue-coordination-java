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
    private final int maximumEntries;
    private final long maximumWeight;
    private final int peakEntries;
    private final long peakWeight;
    private final int inFlight;
    private final int peakInFlight;
    private final int totalEntries;
    private final int peakTotalEntries;
    private final long rejections;

    CacheMetrics(long hits, long misses, long loads, long coalesced,
            long failures, long evictions, int entries, long weight,
            int maximumEntries, long maximumWeight,
            int peakEntries, long peakWeight) {
        this(hits, misses, loads, coalesced, failures, evictions,
                entries, weight, maximumEntries, maximumWeight,
                peakEntries, peakWeight,
                0, 0, entries, peakEntries, 0L);
    }

    CacheMetrics(long hits, long misses, long loads, long coalesced,
            long failures, long evictions, int entries, long weight,
            int maximumEntries, long maximumWeight,
            int peakEntries, long peakWeight,
            int inFlight, int peakInFlight,
            int totalEntries, int peakTotalEntries,
            long rejections) {
        this.hits = hits;
        this.misses = misses;
        this.loads = loads;
        this.coalesced = coalesced;
        this.failures = failures;
        this.evictions = evictions;
        this.entries = entries;
        this.weight = weight;
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
        this.peakEntries = peakEntries;
        this.peakWeight = peakWeight;
        this.inFlight = inFlight;
        this.peakInFlight = peakInFlight;
        this.totalEntries = totalEntries;
        this.peakTotalEntries = peakTotalEntries;
        this.rejections = rejections;
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public long loads() { return loads; }
    public long coalesced() { return coalesced; }
    public long failures() { return failures; }
    public long evictions() { return evictions; }
    public int entries() { return entries; }
    public long weight() { return weight; }
    public int maximumEntries() { return maximumEntries; }
    public long maximumWeight() { return maximumWeight; }
    public int peakEntries() { return peakEntries; }
    public long peakWeight() { return peakWeight; }
    /** Physical flights, including invalidated generations still running. */
    public int inFlight() { return inFlight; }
    public int inFlightEntries() { return inFlight; }
    public int peakInFlight() { return peakInFlight; }
    public int peakInFlightEntries() { return peakInFlight; }
    /** Retained values plus all physical flights. */
    public int totalEntries() { return totalEntries; }
    public int peakTotalEntries() { return peakTotalEntries; }
    public long rejections() { return rejections; }
}
