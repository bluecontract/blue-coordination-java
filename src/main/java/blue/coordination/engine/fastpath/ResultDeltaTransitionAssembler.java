package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts the splitter's raw one-pass result to verified interned handles.
 * Set differences are computed once. Body identity verification occurs once
 * in the interner and is not repeated by DTO accessors or commit admission.
 */
public final class ResultDeltaTransitionAssembler {
    private final ContentAddressedNodeInterner interner;

    public ResultDeltaTransitionAssembler(
            ContentAddressedNodeInterner interner) {
        this.interner = Objects.requireNonNull(interner, "interner");
    }

    public FastFragmentDelta assemble(
            CoordinationFragmentInventory prior,
            AssembledInventoryDelta assembled,
            RequestDigestMemo digests) {
        CoordinationFragmentInventory before = Objects.requireNonNull(
                prior, "prior");
        AssembledInventoryDelta after = Objects.requireNonNull(
                assembled, "assembled");
        CoordinationFragmentInventory resulting = after.inventory();

        Set<String> priorIds = new HashSet<String>(before.fragmentBlueIds());
        Set<String> resultIds = new HashSet<String>(
                resulting.fragmentBlueIds());
        Set<String> expectedNew = new LinkedHashSet<String>(resultIds);
        expectedNew.removeAll(priorIds);
        if (!expectedNew.equals(after.newFragmentBodies().keySet())) {
            throw new IllegalArgumentException(
                    "One-pass assembler returned an incomplete body delta");
        }

        RequestDigestMemo memo = Objects.requireNonNull(digests, "digests");
        Map<String, ExactNodeHandle> newHandles = intern(
                ContentAddressedNodeInterner.PHYSICAL,
                after.newFragmentBodies(), memo);
        Map<String, ExactNodeHandle> viewHandles = intern(
                "processing:" + after.inventory().inventoryIdentity(),
                after.changedProcessingViews(), memo);
        Set<String> reused = new LinkedHashSet<String>();
        for (String blueId : resulting.fragmentBlueIds()) {
            if (priorIds.contains(blueId)) reused.add(blueId);
        }
        Set<String> retired = new LinkedHashSet<String>(
                before.fragmentBlueIds());
        retired.removeAll(resultIds);

        Set<FragmentEdgeRecord> priorEdges = new HashSet<FragmentEdgeRecord>(
                before.edges());
        Set<FragmentEdgeRecord> resultingEdges =
                new HashSet<FragmentEdgeRecord>(resulting.edges());
        List<FragmentEdgeRecord> addedEdges =
                new ArrayList<FragmentEdgeRecord>();
        for (FragmentEdgeRecord edge : resulting.edges()) {
            if (!priorEdges.contains(edge)) addedEdges.add(edge);
        }
        List<FragmentEdgeRecord> retiredEdges =
                new ArrayList<FragmentEdgeRecord>();
        for (FragmentEdgeRecord edge : before.edges()) {
            if (!resultingEdges.contains(edge)) retiredEdges.add(edge);
        }

        return new FastFragmentDelta(
                resulting,
                newHandles,
                viewHandles,
                reused,
                retired,
                addedEdges,
                retiredEdges,
                after.scopeTransitions());
    }

    private Map<String, ExactNodeHandle> intern(
            String namespace,
            Map<String, Node> bodies,
            RequestDigestMemo digests) {
        Map<String, ExactNodeHandle> result =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, Node> entry : bodies.entrySet()) {
            result.put(
                    entry.getKey(),
                    interner.internBound(
                            namespace,
                            entry.getKey(),
                            entry.getValue(),
                            digests));
        }
        return result;
    }
}
