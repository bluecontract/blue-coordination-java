package blue.coordination.engine.fastpath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Entry- and retained-byte-bounded LRU for immutable Root bundle templates.
 *
 * <p>The complete key contains the inventory identity and the deterministic
 * selected identity set. Exact handles are BlueId-bound immutable ownership
 * capabilities, so encoded sizes and handle object identities are derived
 * evidence rather than additional semantic key fields. Values larger than
 * the byte budget are returned but never retained.</p>
 */
public final class PreparedBundleTemplateCache {
    public static final long DEFAULT_MAXIMUM_WEIGHT_BYTES =
            64L * 1024L * 1024L;

    private final int maximumSize;
    private final long maximumWeightBytes;
    private final LinkedHashMap<Key, PreparedBundleTemplate> values;
    private long retainedWeightBytes;
    private long hits;
    private long misses;
    private long builds;
    private long evictions;

    public PreparedBundleTemplateCache(int maximumSize) {
        this(maximumSize, DEFAULT_MAXIMUM_WEIGHT_BYTES);
    }

    public PreparedBundleTemplateCache(
            int maximumSize, long maximumWeightBytes) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException(
                    "maximumSize must be positive");
        }
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeightBytes must be positive");
        }
        this.maximumSize = maximumSize;
        this.maximumWeightBytes = maximumWeightBytes;
        this.values = new LinkedHashMap<Key, PreparedBundleTemplate>(
                Math.min(16, maximumSize), 0.75f, true);
    }

    public synchronized PreparedBundleTemplate require(
            String inventoryIdentity,
            Map<String, ExactNodeHandle> handles,
            Map<String, Long> encodedSizes) {
        Map<String, ExactNodeHandle> checkedHandles =
                Objects.requireNonNull(handles, "handles");
        Key key = new Key(inventoryIdentity, checkedHandles.keySet());
        PreparedBundleTemplate ready = values.get(key);
        if (ready != null) {
            hits++;
            return ready;
        }
        misses++;
        builds++;
        PreparedBundleTemplate built = new PreparedBundleTemplate(
                inventoryIdentity,
                checkedHandles,
                Objects.requireNonNull(encodedSizes, "encodedSizes"));
        long weight = built.approximateRetainedWeightBytes();
        if (weight > maximumWeightBytes) return built;
        while (!values.isEmpty()
                && (values.size() >= maximumSize
                || retainedWeightBytes
                > maximumWeightBytes - weight)) {
            Map.Entry<Key, PreparedBundleTemplate> eldest =
                    values.entrySet().iterator().next();
            retainedWeightBytes -= eldest.getValue()
                    .approximateRetainedWeightBytes();
            values.remove(eldest.getKey());
            evictions++;
        }
        values.put(key, built);
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

    private static final class Key {
        private final String inventoryIdentity;
        private final List<String> selectedBlueIds;

        private Key(
                String inventoryIdentity,
                Collection<String> selectedBlueIds) {
            this.inventoryIdentity = requireText(
                    inventoryIdentity, "inventoryIdentity");
            List<String> ordered = new ArrayList<String>(
                    Objects.requireNonNull(
                            selectedBlueIds, "selectedBlueIds"));
            Collections.sort(ordered);
            this.selectedBlueIds = Collections.unmodifiableList(ordered);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return inventoryIdentity.equals(that.inventoryIdentity)
                    && selectedBlueIds.equals(that.selectedBlueIds);
        }

        @Override
        public int hashCode() {
            return 31 * inventoryIdentity.hashCode()
                    + selectedBlueIds.hashCode();
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must not be empty");
        }
        return checked;
    }
}
