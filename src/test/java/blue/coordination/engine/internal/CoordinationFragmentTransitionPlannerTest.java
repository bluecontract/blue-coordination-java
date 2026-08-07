package blue.coordination.engine.internal;

import blue.coordination.engine.api.ChangeKind;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationEngineProcessorTestFixtures;
import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.coordination.processor.CoordinationSubscriptionSnapshot;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identity-delta tests independent of physical fragment bodies. */
final class CoordinationFragmentTransitionPlannerTest {

    @Test
    void shouldProjectTheChangedRootScopeWithoutInventingAnInterval() {
        // given
        CoordinationDocumentSplitter splitter =
                CoordinationDocumentSplitter.forEventSplitting();
        CoordinationFragmentInventory before =
                CoordinationFragmentInventory.from(splitter.splitEvent(
                        new Node().properties(
                                "beforeField",
                                new Node().value("before"))));
        CoordinationFragmentInventory after =
                CoordinationFragmentInventory.from(splitter.splitEvent(
                        new Node().properties(
                                "afterField",
                                new Node().value("after"))));

        // when
        List<CoordinationScopeTransition> transitions =
                CoordinationFragmentTransitionPlanner.scopeTransitions(
                        before, after);

        // then
        assertEquals(1, transitions.size());
        CoordinationScopeTransition root = transitions.get(0);
        assertEquals("/", root.scopePath());
        assertEquals(ChangeKind.CHANGED, root.kind());
        assertEquals(before.rootBlueId(), root.beforeBlueId());
        assertEquals(after.rootBlueId(), root.afterBlueId());
        assertEquals(
                CoordinationDocumentSplitter.EmbeddedEdgeOrigin.NONE,
                root.origin());
        assertNull(root.activationIntervalIdentity());
    }

    @Test
    void shouldReuseTheClosedInventoryForAnUnchangedRoot() {
        // given
        Node root = new Node().properties(
                "state", scalar("unchanged"),
                "nested", new Node().properties(
                        "payload", scalar("seven")));

        // when
        DifferentialCase proof = verifyDifferential(
                root,
                root.clone(),
                Collections.<String, List<String>>emptyMap());

        // then
        assertTrue(proof.incremental.newFragments().isEmpty());
        assertTrue(proof.incremental.addedEdges().isEmpty());
        assertEquals(
                new LinkedHashSet<String>(
                        proof.prior.fragmentBlueIds()),
                proof.incremental.reusedFragmentBlueIds());
        assertEquals(
                proof.prior.inventoryIdentity(),
                proof.incremental.resultingInventory()
                        .inventoryIdentity());
    }

    @Test
    void shouldCutOnlyNewContentIdentitiesForADirectChange() {
        // given
        Node stable = new Node().properties(
                "payload", scalar("stable"));
        Node before = new Node().properties(
                "state", scalar("before"),
                "stable", stable);
        Node after = new Node().properties(
                "state", scalar("after"),
                "stable", stable.clone());
        String stableBlueId = DirectBlueIdCalculator.calculateBlueId(stable);

        // when
        DifferentialCase proof = verifyDifferential(
                before,
                after,
                Collections.<String, List<String>>emptyMap());

        // then
        assertTrue(proof.incremental.reusedFragmentBlueIds()
                .contains(stableBlueId));
        assertFalse(proof.incremental.newFragments()
                .containsKey(stableBlueId));
        assertTrue(proof.incremental.newFragments()
                .containsKey(DirectBlueIdCalculator.calculateBlueId(after)));
    }

    @Test
    void shouldCutTheAncestorSpineAndReuseSiblingsForADeepChange() {
        // given
        Node stableLeaf = scalar("stable-leaf");
        Node stableBranch = new Node().properties(
                "payload", scalar("stable-branch"));
        Node before = new Node().properties(
                "branch", new Node().properties(
                        "changed", scalar("before"),
                        "stable", stableLeaf),
                "sibling", stableBranch);
        Node after = new Node().properties(
                "branch", new Node().properties(
                        "changed", scalar("after"),
                        "stable", stableLeaf.clone()),
                "sibling", stableBranch.clone());
        String stableLeafBlueId =
                DirectBlueIdCalculator.calculateBlueId(stableLeaf);
        String stableBranchBlueId =
                DirectBlueIdCalculator.calculateBlueId(stableBranch);

        // when
        DifferentialCase proof = verifyDifferential(
                before,
                after,
                Collections.<String, List<String>>emptyMap());

        // then
        assertTrue(proof.incremental.reusedFragmentBlueIds()
                .contains(stableLeafBlueId));
        assertTrue(proof.incremental.reusedFragmentBlueIds()
                .contains(stableBranchBlueId));
        assertFalse(proof.incremental.newFragments()
                .containsKey(stableLeafBlueId));
        assertFalse(proof.incremental.newFragments()
                .containsKey(stableBranchBlueId));
        assertTrue(proof.incremental.newFragments().size() >= 3,
                "the changed leaf, branch, and Root form the new spine");
    }

    @Test
    void shouldKeepEmbeddedScopeAddRemoveAndReaddCanonical() {
        // given
        Node plain = embeddedDocument(false);
        Node embedded = embeddedDocument(true);

        // when
        DifferentialCase added = verifyDifferential(
                plain,
                embedded,
                Collections.<String, List<String>>emptyMap());
        DifferentialCase removed = verifyDifferential(
                embedded,
                plain,
                Collections.<String, List<String>>emptyMap());
        DifferentialCase readded = verifyDifferential(
                plain,
                embedded.clone(),
                Collections.<String, List<String>>emptyMap());

        // then
        assertTrue(hasEmbeddedEdge(
                added.incremental.resultingInventory()));
        assertFalse(hasEmbeddedEdge(
                removed.incremental.resultingInventory()));
        assertTrue(hasEmbeddedEdge(
                readded.incremental.resultingInventory()));
        assertEquals(
                added.incremental.resultingInventory()
                        .inventoryIdentity(),
                readded.incremental.resultingInventory()
                        .inventoryIdentity());
    }

    @Test
    void shouldRetainExactProvenanceForAnExecutableBodyChange() {
        // given
        Node before = workflowDocument("before-step");
        Node after = workflowDocument("after-step");
        Map<String, List<String>> bodyFields =
                Collections.singletonMap(
                        SequentialWorkflowOperation.blueId(),
                        Collections.singletonList("steps"));

        // when
        DifferentialCase proof = verifyDifferential(
                before,
                after,
                bodyFields);

        // then
        assertTrue(proof.incremental.resultingInventory().edges()
                .stream()
                .anyMatch(edge -> edge.edgeKind()
                        == CoordinationDocumentSplitter.EdgeKind
                        .EXECUTABLE_BODY
                        && "/contracts/workflow/steps".equals(
                        edge.absolutePointer())
                        && "steps".equals(
                        edge.executableBodyField())
                        && SequentialWorkflowOperation.blueId().equals(
                        edge.handlerEffectiveTypeBlueId())));
        assertTrue(proof.incremental.resultingInventory().metadata()
                .stream()
                .anyMatch(item -> item.kind()
                        == CoordinationDocumentSplitter.FragmentKind
                        .EXECUTABLE_BODY));
    }

    @Test
    void shouldReuseTheCommittedPhysicalShapeOnASecondPlan() {
        // given
        Node implicitText = scalar("representation-equivalent");
        Node explicitText = Nodes.textNode(
                "representation-equivalent");
        String reusedBlueId =
                DirectBlueIdCalculator.calculateBlueId(implicitText);
        assertEquals(
                reusedBlueId,
                DirectBlueIdCalculator.calculateBlueId(explicitText),
                "implicit and explicit core scalar types share identity");
        Node initial = representationDocument(
                "initial",
                implicitText.clone());
        Node firstResult = representationDocument(
                "first-commit",
                implicitText.clone());
        Node secondResult = representationDocument(
                "second-commit",
                explicitText);
        Map<String, List<String>> noBodies =
                Collections.<String, List<String>>emptyMap();
        CoordinationDocumentSplitter.SplitGraph initialGraph =
                CoordinationFragmentationCatalogHarness
                        .splitter(initial, noBodies)
                        .splitDocument(initial);
        CoordinationFragmentInventory initialInventory =
                CoordinationFragmentInventory.from(initialGraph);

        // when
        CoordinationFragmentTransition first = plannedTransition(
                initialInventory,
                firstResult,
                noBodies,
                1L);
        Map<String, Node> committedBodies =
                new LinkedHashMap<String, Node>(
                        initialGraph.fragments());
        committedBodies.putAll(first.newFragments());
        Node committed = first.resultingInventory().reconstruct(
                canonicalProvider(committedBodies));
        CoordinationFragmentTransition second = plannedTransition(
                first.resultingInventory(),
                secondResult,
                noBodies,
                2L);
        committedBodies.putAll(second.newFragments());
        Node reconstructed = second.resultingInventory().reconstruct(
                canonicalProvider(committedBodies));

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(firstResult),
                DirectBlueIdCalculator.calculateBlueId(committed));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(secondResult),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));
        assertTrue(second.reusedFragmentBlueIds().contains(reusedBlueId));
        assertFalse(second.newFragments().containsKey(reusedBlueId));
        assertFalse(second.resultingInventory().edges().stream()
                .anyMatch(edge -> reusedBlueId.equals(
                        edge.ownerNodeBlueId())
                        && "/type".equals(
                        edge.ownerRelativePointer())),
                "the prior implicit scalar body has no physical /type edge");
        assertNull(reconstructed.getProperties()
                .get("retained").getType(),
                "reconstruction keeps the committed canonical body shape");
    }

    @Test
    void shouldBindAnAlreadyAdmittedPhysicalBodyForANewFragment() {
        // given
        Node implicitInteger = new Node().value(BigInteger.valueOf(20L));
        Node explicitInteger = Nodes.integerNode(BigInteger.valueOf(20L));
        String integerBlueId = DirectBlueIdCalculator.calculateBlueId(
                implicitInteger);
        assertEquals(
                integerBlueId,
                DirectBlueIdCalculator.calculateBlueId(explicitInteger),
                "inferred and explicit Integer types share identity");
        Node before = representationDocument(
                "before-global-collision",
                scalar("stable"));
        Node after = representationDocument(
                "after-global-collision",
                explicitInteger);
        Map<String, List<String>> noBodies =
                Collections.<String, List<String>>emptyMap();
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                CoordinationFragmentationCatalogHarness
                        .splitter(before, noBodies)
                        .splitDocument(before);
        CoordinationFragmentInventory prior =
                CoordinationFragmentInventory.from(priorGraph);
        CoordinationDocumentSplitter.SplitGraph admittedEventGraph =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(new Node().properties(
                                "timestamp",
                                implicitInteger));
        Node admittedInteger = admittedEventGraph.fragments().get(
                integerBlueId);
        NodeProvider admittedPhysical = canonicalProvider(
                admittedEventGraph.fragments());

        // when
        CoordinationFragmentTransition transition = plannedTransition(
                prior,
                after,
                noBodies,
                1L,
                admittedPhysical);
        Map<String, Node> available = new LinkedHashMap<String, Node>(
                priorGraph.fragments());
        available.putAll(transition.newFragments());
        Node reconstructed = transition.resultingInventory().reconstruct(
                canonicalProvider(available));

        // then
        assertNull(admittedInteger.getType(),
                "the Event stored the inferred scalar representation");
        assertNull(transition.newFragments().get(integerBlueId).getType(),
                "the already admitted implicit physical body wins");
        assertFalse(transition.resultingInventory().edges().stream()
                .anyMatch(edge -> integerBlueId.equals(
                        edge.ownerNodeBlueId())
                        && "/type".equals(
                        edge.ownerRelativePointer())),
                "inferred wire type is not a physical reference edge");
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(after),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));
    }

    private static DifferentialCase verifyDifferential(
            Node before,
            Node after,
            Map<String, List<String>> executableBodyFields) {
        CoordinationDocumentSplitter priorSplitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        before,
                        executableBodyFields);
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                priorSplitter.splitDocument(before);
        CoordinationFragmentInventory prior =
                CoordinationFragmentInventory.from(priorGraph);
        CoordinationDocumentSplitter resultSplitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        after,
                        executableBodyFields);
        CoordinationFragmentTransitionPlanner planner =
                new CoordinationFragmentTransitionPlanner(resultSplitter);
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.<Object>asList(1L, "incremental-proof"));
        Node event = new Node().properties(
                "kind", scalar("proof-event"));
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        CoordinationSubscriptionSnapshot snapshot =
                CoordinationEngineProcessorTestFixtures.emptySnapshot(
                        prior.rootBlueId(),
                        0L,
                        order);
        CoordinationPreparedDelivery prepared =
                CoordinationEngineProcessorTestFixtures
                        .emptyPreparedDelivery(
                                prior.rootBlueId(),
                                eventBlueId,
                                0L,
                                order,
                                snapshot.digest());
        CoordinationSubscriptionUpdate update =
                CoordinationSubscriptionUpdate.unchanged(
                        snapshot,
                        order);

        CoordinationFragmentTransition incremental = planner.plan(
                prior,
                after,
                prepared,
                update);
        CoordinationFragmentTransition canonical =
                planner.planCanonicalOracle(prior, after);
        CoordinationFragmentDifferentialProof.verify(
                after,
                incremental,
                canonical,
                canonicalProvider(priorGraph.fragments()));
        return new DifferentialCase(prior, incremental);
    }

    private static CoordinationFragmentTransition plannedTransition(
            CoordinationFragmentInventory prior,
            Node result,
            Map<String, List<String>> executableBodyFields,
            long sequence) {
        return plannedTransition(
                prior,
                result,
                executableBodyFields,
                sequence,
                null);
    }

    private static CoordinationFragmentTransition plannedTransition(
            CoordinationFragmentInventory prior,
            Node result,
            Map<String, List<String>> executableBodyFields,
            long sequence,
            NodeProvider canonicalPhysicalProvider) {
        CoordinationDocumentSplitter resultSplitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        result,
                        executableBodyFields);
        CoordinationFragmentTransitionPlanner planner =
                canonicalPhysicalProvider != null
                        ? new CoordinationFragmentTransitionPlanner(
                                resultSplitter,
                                canonicalPhysicalProvider)
                        : new CoordinationFragmentTransitionPlanner(
                                resultSplitter);
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.<Object>asList(
                        sequence,
                        "representation-reuse"));
        Node event = new Node().properties(
                "kind", scalar("representation-event-" + sequence));
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        CoordinationSubscriptionSnapshot snapshot =
                CoordinationEngineProcessorTestFixtures.emptySnapshot(
                        prior.rootBlueId(),
                        sequence - 1L,
                        order);
        CoordinationPreparedDelivery prepared =
                CoordinationEngineProcessorTestFixtures
                        .emptyPreparedDelivery(
                                prior.rootBlueId(),
                                eventBlueId,
                                sequence - 1L,
                                order,
                                snapshot.digest());
        return planner.plan(
                prior,
                result,
                prepared,
                CoordinationSubscriptionUpdate.unchanged(
                        snapshot,
                        order));
    }

    private static NodeProvider canonicalProvider(
            Map<String, Node> fragments) {
        Map<String, Node> retained = new LinkedHashMap<String, Node>(
                fragments);
        return blueId -> {
            Node node = retained.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : null;
        };
    }

    private static Node embeddedDocument(
            boolean declareEmbedded) {
        Node child = new Node().properties(
                "state", scalar("child"));
        Node root = new Node().properties(
                "state", scalar("root"),
                "child", child);
        if (declareEmbedded) {
            root.contracts(new Node().properties(
                    "embedded",
                    new Node()
                            .type(reference(
                                    RuntimeBlueIds.PROCESS_EMBEDDED))
                            .properties(
                                    "paths",
                                    new Node().items(
                                            scalar("/child")))));
        }
        return root;
    }

    private static Node workflowDocument(
            String bodyLabel) {
        Node body = new Node().items(
                new Node().properties(
                        "label", scalar(bodyLabel),
                        "stable", scalar("retained")));
        Node workflow = new Node()
                .type(reference(
                        SequentialWorkflowOperation.blueId()))
                .properties(
                        "channel", scalar("timeline"),
                        "steps", body);
        return new Node()
                .properties("state", scalar("root"))
                .contracts(new Node().properties(
                        "workflow", workflow));
    }

    private static Node representationDocument(
            String revision,
            Node retained) {
        return new Node().properties(
                "revision", scalar(revision),
                "retained", retained);
    }

    private static boolean hasEmbeddedEdge(
            CoordinationFragmentInventory inventory) {
        return inventory.edges().stream()
                .anyMatch(edge -> edge.edgeKind()
                        == CoordinationDocumentSplitter.EdgeKind
                        .EMBEDDED_ROOT);
    }

    private static Node scalar(
            Object value) {
        return new Node().value(value);
    }

    private static Node reference(
            String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class DifferentialCase {

        private final CoordinationFragmentInventory prior;
        private final CoordinationFragmentTransition incremental;

        private DifferentialCase(
                CoordinationFragmentInventory prior,
                CoordinationFragmentTransition incremental) {
            this.prior = prior;
            this.incremental = incremental;
        }
    }
}
