package blue.coordination.engine.internal;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic full-oracle proof for the incremental fragmentation path. */
final class CoordinationFragmentDifferentialProof {

    private CoordinationFragmentDifferentialProof() {
    }

    static void verify(
            Node resultingExactRoot,
            CoordinationFragmentTransition incremental,
            CoordinationFragmentTransition canonical,
            NodeProvider priorBodies) {
        Node expected = Objects.requireNonNull(
                resultingExactRoot, "resultingExactRoot").clone();
        CoordinationFragmentTransition actual = Objects.requireNonNull(
                incremental, "incremental");
        CoordinationFragmentTransition oracle = Objects.requireNonNull(
                canonical, "canonical");
        NodeProvider prior = Objects.requireNonNull(
                priorBodies, "priorBodies");
        CoordinationFragmentInventory actualInventory =
                actual.resultingInventory();
        CoordinationFragmentInventory oracleInventory =
                oracle.resultingInventory();

        requireEqual(
                "root identity",
                oracleInventory.rootBlueId(),
                actualInventory.rootBlueId());
        requireEqual(
                "fragment roots",
                oracleInventory.fragmentRoots(),
                actualInventory.fragmentRoots());
        requireEqual(
                "fragment identity set",
                new TreeSet<String>(oracleInventory.fragmentBlueIds()),
                new TreeSet<String>(actualInventory.fragmentBlueIds()));
        requireEqual(
                "edge occurrence/provenance",
                oracleInventory.edges(),
                actualInventory.edges());
        requireEqual(
                "fragment metadata",
                oracleInventory.metadata(),
                actualInventory.metadata());
        requireEqual(
                "inventory identity",
                oracleInventory.inventoryIdentity(),
                actualInventory.inventoryIdentity());
        requireNodeMapsEqual(
                "new fragment body",
                oracle.newFragments(),
                actual.newFragments());
        requireProcessingViewsEquivalent(
                oracle.processingViews(),
                actual.processingViews(),
                provider(oracle.newFragments(), prior),
                provider(actual.newFragments(), prior));
        requireEqual(
                "reused identities",
                oracle.reusedFragmentBlueIds(),
                actual.reusedFragmentBlueIds());
        requireEqual(
                "retired identities",
                oracle.retiredFragmentBlueIds(),
                actual.retiredFragmentBlueIds());
        requireEqual(
                "added edge delta",
                oracle.addedEdges(),
                actual.addedEdges());
        requireEqual(
                "retired edge delta",
                oracle.retiredEdges(),
                actual.retiredEdges());

        Node actualReconstruction = actualInventory.reconstruct(
                provider(actual.newFragments(), prior));
        Node oracleReconstruction = oracleInventory.reconstruct(
                provider(oracle.newFragments(), prior));
        requireNodeEqual(
                "incremental reconstruction",
                expected,
                actualReconstruction);
        requireNodeEqual(
                "canonical reconstruction",
                expected,
                oracleReconstruction);
        requireNodeEqual(
                "differential reconstruction",
                oracleReconstruction,
                actualReconstruction);
    }

    private static NodeProvider provider(
            Map<String, Node> newBodies,
            NodeProvider prior) {
        NodeProvider changed = blueId -> {
            Node node = newBodies.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : null;
        };
        return new SequentialNodeProvider(changed, prior);
    }

    private static void requireNodeMapsEqual(
            String label,
            Map<String, Node> expected,
            Map<String, Node> actual) {
        Set<String> expectedIds = new TreeSet<String>(expected.keySet());
        Set<String> actualIds = new TreeSet<String>(actual.keySet());
        requireEqual(label + " identities", expectedIds, actualIds);
        for (String blueId : expectedIds) {
            requireNodeEqual(
                    label + " " + blueId,
                    expected.get(blueId),
                    actual.get(blueId));
        }
    }

    private static void requireProcessingViewsEquivalent(
            Map<String, Node> expected,
            Map<String, Node> actual,
            NodeProvider expectedPhysical,
            NodeProvider actualPhysical) {
        Set<String> identities = new TreeSet<String>(expected.keySet());
        identities.addAll(actual.keySet());
        for (String blueId : identities) {
            Node expectedView = expected.get(blueId);
            Node actualView = actual.get(blueId);
            if (expectedView == null) {
                expectedView = requirePhysical(actualPhysical, blueId);
            }
            if (actualView == null) {
                actualView = requirePhysical(expectedPhysical, blueId);
            }
            requireNodeEqual(
                    "PROCESS view " + blueId,
                    expectedView,
                    actualView);
        }
    }

    private static Node requirePhysical(
            NodeProvider provider,
            String blueId) {
        NodeProviderResult result = provider.fetchResultByBlueId(blueId);
        if (result == null || result.nodes().size() != 1) {
            throw new IllegalStateException(
                    "Physical fragment is unavailable for PROCESS-view "
                            + "differential proof: " + blueId);
        }
        return result.nodes().get(0);
    }

    private static void requireNodeEqual(
            String label,
            Node expected,
            Node actual) {
        if (!NodeWireForm.get(expected).equals(NodeWireForm.get(actual))) {
            throw new IllegalStateException(
                    "Incremental fragmentation differs from canonical "
                            + label);
        }
    }

    private static void requireEqual(
            String label,
            Object expected,
            Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Incremental fragmentation differs from canonical "
                            + label + ": expected=" + expected
                            + ", actual=" + actual);
        }
    }
}
