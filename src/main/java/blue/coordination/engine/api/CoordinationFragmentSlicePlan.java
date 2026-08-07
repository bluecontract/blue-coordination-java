package blue.coordination.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Body-free deterministic selection plan for one bounded fragment slice. */
public final class CoordinationFragmentSlicePlan {

    private final String fragmentationProfileIdentity;
    private final String inventoryIdentity;
    private final String inventoryRootBlueId;
    private final String selectedPath;
    private final String selectedRootBlueId;
    private final List<String> fragmentBlueIds;
    private final List<FragmentRootRecord> roots;
    private final List<FragmentEdgeRecord> edges;

    public CoordinationFragmentSlicePlan(
            String fragmentationProfileIdentity,
            String inventoryIdentity,
            String inventoryRootBlueId,
            String selectedPath,
            String selectedRootBlueId,
            List<String> fragmentBlueIds,
            List<FragmentRootRecord> roots,
            List<FragmentEdgeRecord> edges) {
        this.fragmentationProfileIdentity = requireText(
                fragmentationProfileIdentity,
                "fragmentationProfileIdentity");
        this.inventoryIdentity = requireText(
                inventoryIdentity, "inventoryIdentity");
        this.inventoryRootBlueId = requireText(
                inventoryRootBlueId, "inventoryRootBlueId");
        this.selectedPath = Objects.requireNonNull(
                selectedPath, "selectedPath");
        this.selectedRootBlueId = requireText(
                selectedRootBlueId, "selectedRootBlueId");
        this.fragmentBlueIds = immutable(fragmentBlueIds, "fragmentBlueIds");
        this.roots = immutable(roots, "roots");
        this.edges = immutable(edges, "edges");
        if (this.fragmentBlueIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "A physical slice must select at least one fragment");
        }
        if (!this.fragmentBlueIds.contains(this.selectedRootBlueId)) {
            throw new IllegalArgumentException(
                    "selectedRootBlueId must be included in the slice");
        }
    }

    public String fragmentationProfileIdentity() {
        return fragmentationProfileIdentity;
    }
    public String inventoryIdentity() { return inventoryIdentity; }
    public String inventoryRootBlueId() { return inventoryRootBlueId; }
    public String selectedPath() { return selectedPath; }
    public String selectedRootBlueId() { return selectedRootBlueId; }
    public List<String> fragmentBlueIds() { return fragmentBlueIds; }
    public List<FragmentRootRecord> roots() { return roots; }
    public List<FragmentEdgeRecord> edges() { return edges; }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }

    private static <T> List<T> immutable(
            List<T> source,
            String label) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(source, label));
        for (T item : copy) {
            Objects.requireNonNull(item, label + " item");
        }
        return Collections.unmodifiableList(copy);
    }
}
