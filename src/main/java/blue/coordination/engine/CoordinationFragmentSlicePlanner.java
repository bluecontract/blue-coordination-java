package blue.coordination.engine;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentSlicePlan;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Selects the minimal known physical closure below one embedded Root path. */
public final class CoordinationFragmentSlicePlanner {

    public CoordinationFragmentSlicePlan plan(
            CoordinationFragmentInventory inventory,
            String absolutePath) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        String path = JsonPointer.canonicalize(
                Objects.requireNonNull(absolutePath, "absolutePath"));

        List<FragmentRootRecord> exact = new ArrayList<FragmentRootRecord>();
        for (FragmentRootRecord root : checked.fragmentRoots()) {
            if (path.equals(root.absolutePath())) {
                exact.add(root);
            }
        }
        if (exact.size() != 1) {
            throw new IllegalArgumentException(
                    "Slice path must identify exactly one fragment root: "
                            + path + " -> " + exact.size());
        }
        FragmentRootRecord selected = exact.get(0);
        Set<String> inventoryIds = new LinkedHashSet<String>(
                checked.fragmentBlueIds());
        Set<String> selectedIds = new LinkedHashSet<String>();
        selectedIds.add(selected.blueId());

        List<FragmentRootRecord> roots = new ArrayList<FragmentRootRecord>();
        for (FragmentRootRecord root : checked.fragmentRoots()) {
            if (PointerUtils.descendantOrEqual(root.absolutePath(), path)) {
                roots.add(root);
                selectedIds.add(root.blueId());
            }
        }

        boolean changed;
        do {
            changed = false;
            for (FragmentEdgeRecord edge : checked.edges()) {
                boolean pathSelected = PointerUtils.descendantOrEqual(
                        edge.absolutePointer(), path);
                boolean ownerSelected = selectedIds.contains(edge.rootBlueId())
                        || selectedIds.contains(edge.ownerNodeBlueId());
                if (pathSelected && ownerSelected
                        && edge.splitterCreated()
                        && inventoryIds.contains(edge.childBlueId())
                        && selectedIds.add(edge.childBlueId())) {
                    changed = true;
                }
            }
        } while (changed);

        List<FragmentEdgeRecord> edges = new ArrayList<FragmentEdgeRecord>();
        for (FragmentEdgeRecord edge : checked.edges()) {
            if (PointerUtils.descendantOrEqual(edge.absolutePointer(), path)
                    && selectedIds.contains(edge.ownerNodeBlueId())
                    && (!edge.splitterCreated()
                    || selectedIds.contains(edge.childBlueId()))) {
                edges.add(edge);
            }
        }

        List<String> ids = new ArrayList<String>(selectedIds);
        ids.sort(ExternalOrderKey::compareTextCodePoints);
        roots.sort(Comparator
                .comparing((FragmentRootRecord value) -> value.kind().name(),
                        ExternalOrderKey::compareTextCodePoints)
                .thenComparing(FragmentRootRecord::absolutePath,
                        ExternalOrderKey::compareTextCodePoints)
                .thenComparing(FragmentRootRecord::blueId,
                        ExternalOrderKey::compareTextCodePoints));
        edges.sort(FragmentEdgeRecord::compareTo);
        return new CoordinationFragmentSlicePlan(
                checked.fragmentationProfileIdentity(),
                checked.inventoryIdentity(),
                checked.rootBlueId(),
                path,
                selected.blueId(),
                ids,
                roots,
                edges);
    }
}
