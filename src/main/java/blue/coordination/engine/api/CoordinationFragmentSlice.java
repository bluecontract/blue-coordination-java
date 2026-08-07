package blue.coordination.engine.api;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, bounded, physically verified view of one embedded fragment Root.
 *
 * <p>The selected exact Root is reconstructed only from the selected physical
 * closure. The owning document Root is never reconstructed by this value.</p>
 */
public final class CoordinationFragmentSlice {

    private final String inventoryIdentity;
    private final String inventoryRootBlueId;
    private final String selectedPath;
    private final String selectedRootBlueId;
    private final List<String> fragmentBlueIds;
    private final List<FragmentRootRecord> roots;
    private final List<FragmentEdgeRecord> edges;
    private final Map<String, Node> exactFragments;
    private final Node exactSelectedRoot;

    public CoordinationFragmentSlice(
            String inventoryIdentity,
            String inventoryRootBlueId,
            String selectedPath,
            String selectedRootBlueId,
            List<String> fragmentBlueIds,
            List<FragmentRootRecord> roots,
            List<FragmentEdgeRecord> edges,
            Map<String, Node> exactFragments,
            Node exactSelectedRoot) {
        this.inventoryIdentity = text(inventoryIdentity, "inventoryIdentity");
        this.inventoryRootBlueId = text(
                inventoryRootBlueId, "inventoryRootBlueId");
        this.selectedPath = Objects.requireNonNull(
                selectedPath, "selectedPath");
        this.selectedRootBlueId = text(
                selectedRootBlueId, "selectedRootBlueId");
        this.fragmentBlueIds = immutableList(
                fragmentBlueIds, "fragmentBlueIds");
        this.roots = immutableList(roots, "roots");
        this.edges = immutableList(edges, "edges");
        if (this.fragmentBlueIds.isEmpty()
                || !this.fragmentBlueIds.contains(this.selectedRootBlueId)) {
            throw new IllegalArgumentException(
                    "Slice must contain its selected physical Root");
        }

        Map<String, Node> copied = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> item : Objects.requireNonNull(
                exactFragments, "exactFragments").entrySet()) {
            if (!this.fragmentBlueIds.contains(item.getKey())) {
                throw new IllegalArgumentException(
                        "Slice body is outside selected identities: "
                                + item.getKey());
            }
            copied.put(item.getKey(), Objects.requireNonNull(
                    item.getValue(), "exactFragment").clone());
        }
        if (!copied.keySet().containsAll(this.fragmentBlueIds)) {
            throw new IllegalArgumentException(
                    "Every selected fragment must have one exact body");
        }
        this.exactFragments = Collections.unmodifiableMap(copied);

        Node selected = Objects.requireNonNull(
                exactSelectedRoot, "exactSelectedRoot").clone();
        if (selected.isReferenceOnly()
                || !this.selectedRootBlueId.equals(
                        DirectBlueIdCalculator.calculateBlueId(
                                selected.clone()))) {
            throw new IllegalArgumentException(
                    "Selected exact Root does not match selectedRootBlueId");
        }
        this.exactSelectedRoot = selected;
    }

    public String inventoryIdentity() { return inventoryIdentity; }
    public String inventoryRootBlueId() { return inventoryRootBlueId; }
    public String selectedPath() { return selectedPath; }
    public String selectedRootBlueId() { return selectedRootBlueId; }
    public List<String> fragmentBlueIds() { return fragmentBlueIds; }
    public List<FragmentRootRecord> roots() { return roots; }
    public List<FragmentEdgeRecord> edges() { return edges; }

    public Map<String, Node> exactFragments() {
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> item : exactFragments.entrySet()) {
            result.put(item.getKey(), item.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }

    public Node exactSelectedRoot() {
        return exactSelectedRoot.clone();
    }

    public int fragmentCount() { return fragmentBlueIds.size(); }

    private static String text(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }

    private static <T> List<T> immutableList(
            List<T> values,
            String label) {
        ArrayList<T> result = new ArrayList<T>(
                Objects.requireNonNull(values, label));
        for (T value : result) {
            Objects.requireNonNull(value, label + " item");
        }
        return Collections.unmodifiableList(result);
    }
}
