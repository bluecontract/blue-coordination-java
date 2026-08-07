package blue.coordination.engine;

import blue.coordination.engine.api.CoordinationFragmentSlice;
import blue.coordination.engine.api.CoordinationFragmentSlicePlan;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationFragmentReconstructor;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads, verifies, and reconstructs a slice in one physical store batch. */
public final class CoordinationFragmentSliceLoader {

    public CoordinationFragmentSlice load(
            CoordinationFragmentStore store,
            CoordinationFragmentSlicePlan plan) {
        CoordinationFragmentStore checkedStore = Objects.requireNonNull(
                store, "store");
        CoordinationFragmentSlicePlan checkedPlan = Objects.requireNonNull(
                plan, "plan");
        if (!checkedPlan.fragmentationProfileIdentity().equals(
                checkedStore.fragmentationProfileIdentity())) {
            throw new IllegalArgumentException(
                    "Fragment-store profile differs from the slice plan");
        }

        Map<String, NodeProviderResult> loaded = checkedStore.readAll(
                checkedPlan.fragmentBlueIds());
        Map<String, Node> bodies = new LinkedHashMap<String, Node>();
        for (String blueId : checkedPlan.fragmentBlueIds()) {
            NodeProviderResult result = loaded.get(blueId);
            if (result == null
                    || result.outcome() != NodeProviderOutcome.FOUND
                    || result.nodes().size() != 1) {
                throw new IllegalStateException(
                        "Slice fragment unavailable or ambiguous: " + blueId);
            }
            Node exact = result.nodes().get(0);
            String calculated = DirectBlueIdCalculator.calculateBlueId(
                    exact.clone());
            if (!blueId.equals(calculated)) {
                throw new IllegalStateException(
                        "Slice fragment identity mismatch: " + blueId);
            }
            bodies.put(blueId, exact);
        }

        List<CoordinationDocumentSplitter.EdgeOccurrence> edges =
                new ArrayList<CoordinationDocumentSplitter.EdgeOccurrence>();
        for (FragmentEdgeRecord edge : checkedPlan.edges()) {
            edges.add(edge.toEdgeOccurrence(
                    checkedPlan.fragmentationProfileIdentity()));
        }
        Node selectedRoot = CoordinationFragmentReconstructor
                .reconstructSelectedFragment(
                        checkedPlan.fragmentationProfileIdentity(),
                        checkedPlan.inventoryRootBlueId(),
                        checkedPlan.selectedRootBlueId(),
                        bodies,
                        edges);
        return new CoordinationFragmentSlice(
                checkedPlan.inventoryIdentity(),
                checkedPlan.inventoryRootBlueId(),
                checkedPlan.selectedPath(),
                checkedPlan.selectedRootBlueId(),
                checkedPlan.fragmentBlueIds(),
                checkedPlan.roots(),
                checkedPlan.edges(),
                bodies,
                selectedRoot);
    }
}
