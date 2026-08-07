package blue.coordination.engine.fastpath;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Root-side immutable half of a processing bundle. It is installed with the
 * session epoch and cheaply overlaid with event handles per delivery.
 */
public final class PreparedBundleTemplate {
    private final String inventoryIdentity;
    private final Map<String, ExactNodeHandle> rootHandles;
    private final Map<String, Long> encodedSizes;
    private final long approximateRetainedWeightBytes;

    public PreparedBundleTemplate(
            String inventoryIdentity,
            Map<String, ExactNodeHandle> rootHandles,
            Map<String, Long> encodedSizes) {
        this.inventoryIdentity = requireText(
                inventoryIdentity, "inventoryIdentity");
        this.rootHandles = immutableHandles(rootHandles);
        Map<String, Long> sizes = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> entry
                : Objects.requireNonNull(encodedSizes,
                        "encodedSizes").entrySet()) {
            if (!this.rootHandles.containsKey(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().longValue() < 0L) {
                throw new IllegalArgumentException(
                        "Invalid encoded size for " + entry.getKey());
            }
            sizes.put(entry.getKey(), entry.getValue());
        }
        if (!sizes.keySet().equals(this.rootHandles.keySet())) {
            throw new IllegalArgumentException(
                    "Every prepared Root handle requires exact byte size");
        }
        this.encodedSizes = Collections.unmodifiableMap(sizes);
        this.approximateRetainedWeightBytes = retainedWeight(
                this.rootHandles.size());
    }

    public PreparedProcessInput bindEvent(
            String eventInventoryIdentity,
            Map<String, ExactNodeHandle> eventHandles,
            Map<String, Long> eventEncodedSizes,
            Collection<String> selectedBlueIds) {
        return bindEvent(
                eventInventoryIdentity,
                eventHandles,
                eventEncodedSizes,
                selectedBlueIds,
                Collections.<String>emptySet());
    }

    /**
     * Binds an event while retaining provenance-checked external references
     * as selected, provider-resolved identities.  A missing local handle is
     * accepted only when it is explicitly present in {@code externalBlueIds};
     * admitted inventory members must always have a prepared exact handle.
     */
    public PreparedProcessInput bindEvent(
            String eventInventoryIdentity,
            Map<String, ExactNodeHandle> eventHandles,
            Map<String, Long> eventEncodedSizes,
            Collection<String> selectedBlueIds,
            Collection<String> externalBlueIds) {
        Set<String> selected = new LinkedHashSet<String>(
                Objects.requireNonNull(selectedBlueIds, "selectedBlueIds"));
        Set<String> external = new LinkedHashSet<String>(
                Objects.requireNonNull(externalBlueIds, "externalBlueIds"));
        Map<String, ExactNodeHandle> checkedEventHandles = immutableHandles(
                Objects.requireNonNull(eventHandles, "eventHandles"));
        Map<String, Long> checkedEventSizes = checkedSizes(
                checkedEventHandles,
                Objects.requireNonNull(
                        eventEncodedSizes, "eventEncodedSizes"));
        Map<String, ExactNodeHandle> merged =
                new LinkedHashMap<String, ExactNodeHandle>();
        long bytes = 0L;
        for (String blueId : selected) {
            ExactNodeHandle handle = rootHandles.get(blueId);
            if (handle == null) handle = checkedEventHandles.get(blueId);
            if (handle == null) {
                if (external.contains(blueId)) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "Selected bundle identity is unavailable: " + blueId);
            }
            ExactNodeHandle previous = merged.put(blueId, handle);
            if (previous != null
                    && !previous.blueId().equals(handle.blueId())) {
                throw new IllegalStateException(
                        "Conflicting bundle identity " + blueId);
            }
            Long size = encodedSizes.get(blueId);
            if (size == null) size = checkedEventSizes.get(blueId);
            if (size == null) {
                throw new IllegalArgumentException(
                        "Selected bundle identity lacks byte size: "
                                + blueId);
            }
            bytes = Math.addExact(bytes, size.longValue());
        }
        return new PreparedProcessInput(
                inventoryIdentity,
                requireText(eventInventoryIdentity,
                        "eventInventoryIdentity"),
                merged,
                selected,
                bytes);
    }

    public String inventoryIdentity() { return inventoryIdentity; }
    public long approximateRetainedWeightBytes() {
        return approximateRetainedWeightBytes;
    }

    private static Map<String, ExactNodeHandle> immutableHandles(
            Map<String, ExactNodeHandle> source) {
        Map<String, ExactNodeHandle> result =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, ExactNodeHandle> entry
                : Objects.requireNonNull(source, "source").entrySet()) {
            if (!entry.getKey().equals(entry.getValue().blueId())) {
                throw new IllegalArgumentException(
                        "Handle key mismatch for " + entry.getKey());
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> checkedSizes(
            Map<String, ExactNodeHandle> handles,
            Map<String, Long> source) {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            if (!handles.containsKey(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().longValue() < 0L) {
                throw new IllegalArgumentException(
                        "Invalid encoded size for " + entry.getKey());
            }
            result.put(entry.getKey(), entry.getValue());
        }
        if (!result.keySet().equals(handles.keySet())) {
            throw new IllegalArgumentException(
                    "Every prepared event handle requires exact byte size");
        }
        return Collections.unmodifiableMap(result);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    private static long retainedWeight(int handleCount) {
        /* Exact handle bodies are interned and authoritatively retained by
         * the fragment store. This template owns only immutable map shells,
         * entries, references and encoded-size metadata; charging bodies here
         * would count the same graph once per selected-key template. */
        long weight = 256L;
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                RetainedNodeWeight.saturatedMultiply(
                        128L, handleCount));
        return Math.max(1L, weight);
    }
}
