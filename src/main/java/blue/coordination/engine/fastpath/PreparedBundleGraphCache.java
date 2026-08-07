package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded access-ordered cache for immutable per-inventory graph indexes. */
public final class PreparedBundleGraphCache {
    public static final long DEFAULT_MAXIMUM_WEIGHT_BYTES =
            64L * 1024L * 1024L;

    private final int maximumSize;
    private final long maximumWeightBytes;
    private final LinkedHashMap<String, FragmentGraphIndex> values;
    private long retainedWeightBytes;
    private long hits;
    private long misses;
    private long builds;
    private long evictions;

    public PreparedBundleGraphCache(int maximumSize) {
        this(maximumSize, DEFAULT_MAXIMUM_WEIGHT_BYTES);
    }

    public PreparedBundleGraphCache(
            int maximumSize, long maximumWeightBytes) {
        if (maximumSize <= 0) throw new IllegalArgumentException(
                "maximumSize must be positive");
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeightBytes must be positive");
        }
        this.maximumSize = maximumSize;
        this.maximumWeightBytes = maximumWeightBytes;
        this.values = new LinkedHashMap<String, FragmentGraphIndex>(
                Math.min(16, maximumSize), 0.75f, true);
    }

    public synchronized FragmentGraphIndex require(
            CoordinationFragmentInventory inventory) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        FragmentGraphIndex ready = values.get(checked.inventoryIdentity());
        if (ready != null) {
            hits++;
            return ready;
        }
        misses++;
        FragmentGraphIndex built = new FragmentGraphIndex(checked);
        builds++;
        long weight = built.approximateRetainedWeightBytes();
        if (weight > maximumWeightBytes) return built;
        while (!values.isEmpty()
                && (values.size() >= maximumSize
                || retainedWeightBytes
                > maximumWeightBytes - weight)) {
            Map.Entry<String, FragmentGraphIndex> eldest =
                    values.entrySet().iterator().next();
            retainedWeightBytes -= eldest.getValue()
                    .approximateRetainedWeightBytes();
            values.remove(eldest.getKey());
            evictions++;
        }
        values.put(checked.inventoryIdentity(), built);
        retainedWeightBytes += weight;
        return built;
    }

    public synchronized int size() { return values.size(); }
    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
    public synchronized long builds() { return builds; }
    public synchronized long evictions() { return evictions; }
    public synchronized long retainedWeightBytes() {
        return retainedWeightBytes;
    }
    public long maximumWeightBytes() { return maximumWeightBytes; }
}
