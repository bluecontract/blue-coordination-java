package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable one-pass proof produced from one canonical event split.
 *
 * <p>The current BlueIds and inventory remain authoritative. This artifact
 * carries already-established physical evidence across adjacent internal
 * boundaries so it is not cloned, hashed, canonicalized, and read back for
 * every admission step.</p>
 */
public final class CoordinationVerifiedEventAdmission {

    private final CoordinationEventAdmissionCacheKey key;
    private final CoordinationFragmentInventory inventory;
    private final FrozenNode exactEvent;
    private final Map<String, CoordinationCanonicalFragment> fragments;
    private final Map<String, CoordinationCanonicalFragment> processingViews;
    private final List<String> orderedFragmentBlueIds;

    CoordinationVerifiedEventAdmission(
            CoordinationEventAdmissionCacheKey key,
            CoordinationFragmentInventory inventory,
            Node exactEvent,
            Map<String, CoordinationCanonicalFragment> fragments,
            Map<String, Node> processingViews) {
        this(
                key,
                inventory,
                FrozenNode.fromNode(
                        Objects.requireNonNull(exactEvent, "exactEvent")),
                fragments,
                processingViews);
    }

    CoordinationVerifiedEventAdmission(
            CoordinationEventAdmissionCacheKey key,
            CoordinationFragmentInventory inventory,
            FrozenNode exactEvent,
            Map<String, CoordinationCanonicalFragment> fragments,
            Map<String, Node> processingViews) {
        this.key = Objects.requireNonNull(key, "key");
        this.inventory = Objects.requireNonNull(
                inventory, "inventory").retainedCopy();
        if (!key.eventBlueId().equals(this.inventory.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Cache key and inventory event Root disagree");
        }
        this.exactEvent = Objects.requireNonNull(
                exactEvent, "exactEvent");

        Map<String, CoordinationCanonicalFragment> fragmentCopy =
                new LinkedHashMap<String, CoordinationCanonicalFragment>();
        for (Map.Entry<String, CoordinationCanonicalFragment> item
                : Objects.requireNonNull(
                        fragments, "fragments").entrySet()) {
            String blueId = requireText(item.getKey(), "fragmentBlueId");
            CoordinationCanonicalFragment fragment = Objects.requireNonNull(
                    item.getValue(), "fragment");
            if (!blueId.equals(fragment.blueId())) {
                throw new IllegalArgumentException(
                        "Fragment map key differs from its BlueId");
            }
            fragmentCopy.put(blueId, fragment);
        }
        if (!fragmentCopy.keySet().equals(new LinkedHashSet<String>(
                this.inventory.fragmentBlueIds()))) {
            throw new IllegalArgumentException(
                    "Verified fragments do not equal inventory membership");
        }
        this.fragments = Collections.unmodifiableMap(fragmentCopy);
        this.orderedFragmentBlueIds = Collections.unmodifiableList(
                new ArrayList<String>(this.inventory.fragmentBlueIds()));

        Map<String, CoordinationCanonicalFragment> views =
                new LinkedHashMap<String, CoordinationCanonicalFragment>();
        for (Map.Entry<String, Node> item : Objects.requireNonNull(
                processingViews, "processingViews").entrySet()) {
            String blueId = requireText(
                    item.getKey(), "processingViewBlueId");
            if (!fragmentCopy.containsKey(blueId)) {
                throw new IllegalArgumentException(
                        "PROCESS view is outside event inventory: " + blueId);
            }
            Node view = Objects.requireNonNull(
                    item.getValue(), "processingView");
            views.put(blueId, new CoordinationCanonicalFragment(
                    blueId,
                    CoordinationFragmentAdmissionVerifier
                            .physicalFragmentIdentity(view),
                    view));
        }
        this.processingViews = Collections.unmodifiableMap(views);
    }

    public CoordinationEventAdmissionCacheKey key() {
        return key;
    }

    public CoordinationFragmentInventory inventory() {
        return inventory;
    }

    public Node exactEvent() {
        return exactEvent.toNode();
    }

    /** Immutable exact-event handle for trusted in-process adapters. */
    public FrozenNode frozenExactEvent() {
        return exactEvent;
    }

    public Map<String, CoordinationCanonicalFragment> fragments() {
        return fragments;
    }

    public List<String> orderedFragmentBlueIds() {
        return orderedFragmentBlueIds;
    }

    public Map<String, Node> materializeProcessingViews() {
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, CoordinationCanonicalFragment> item
                : processingViews.entrySet()) {
            result.put(item.getKey(), item.getValue().materialize());
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, CoordinationCanonicalFragment> processingViews() {
        return processingViews;
    }

    /**
     * Estimates the complete immutable graph retained by this artifact while
     * counting structurally shared frozen objects only once.
     */
    long approximateRetainedWeightBytes() {
        FrozenNode[] roots = new FrozenNode[
                1 + fragments.size() + processingViews.size()];
        int index = 0;
        roots[index++] = exactEvent;
        for (CoordinationCanonicalFragment fragment : fragments.values()) {
            roots[index++] = fragment.frozen();
        }
        for (CoordinationCanonicalFragment view : processingViews.values()) {
            roots[index++] = view.frozen();
        }
        return FrozenNode.approximateRetainedWeightBytesOf(roots);
    }

    public StoredCoordinationEvent storedEvent(ExternalOrderKey orderKey) {
        return new StoredCoordinationEvent(
                key.eventBlueId(),
                inventory.inventoryIdentity(),
                Objects.requireNonNull(orderKey, "orderKey"));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
