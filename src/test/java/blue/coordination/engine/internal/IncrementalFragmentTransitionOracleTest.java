package blue.coordination.engine.internal;

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
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full-split differential oracle for every supported transition shape. */
final class IncrementalFragmentTransitionOracleTest {

    @Test
    void shouldMatchTheCanonicalSplitterAcrossTheTransitionMatrix() {
        // given
        String firstReference = blueId(scalar("reference-one"));
        String secondReference = blueId(scalar("reference-two"));
        Map<String, List<String>> workflowBodies = Collections.singletonMap(
                SequentialWorkflowOperation.blueId(),
                Collections.singletonList("steps"));
        List<MutationCase> cases = Arrays.asList(
                new MutationCase(
                        "value-only",
                        valueDocument("before"),
                        valueDocument("after"),
                        noBodies()),
                new MutationCase(
                        "add-embedded-document",
                        embeddedDocument(false),
                        embeddedDocument(true),
                        noBodies()),
                new MutationCase(
                        "remove-embedded-document",
                        embeddedDocument(true),
                        embeddedDocument(false),
                        noBodies()),
                new MutationCase(
                        "list-edit",
                        listDocument("one", "two"),
                        listDocument("one", "inserted", "two"),
                        noBodies()),
                new MutationCase(
                        "contract-header-change",
                        contractDocument("before-header"),
                        contractDocument("after-header"),
                        noBodies()),
                new MutationCase(
                        "executable-body-change",
                        workflowDocument("before-step"),
                        workflowDocument("after-step"),
                        workflowBodies),
                new MutationCase(
                        "reference-substitution",
                        referenceDocument(firstReference),
                        referenceDocument(secondReference),
                        noBodies()),
                new MutationCase(
                        "no-op",
                        valueDocument("same"),
                        valueDocument("same"),
                        noBodies()));
        List<TransitionPair> proofs = new ArrayList<>();

        // when
        for (MutationCase mutation : cases) {
            try {
                proofs.add(verifyDifferential(mutation));
            } catch (RuntimeException failure) {
                throw new AssertionError(
                        "transition oracle failed for " + mutation.name,
                        failure);
            }
        }

        // then
        assertEquals(cases.size(), proofs.size());
        for (TransitionPair proof : proofs) {
            assertEquals(
                    scopeSignatures(proof.canonical.scopeTransitions()),
                    scopeSignatures(proof.incremental.scopeTransitions()),
                    proof.name + " scope transition mismatch");
            assertEquals(
                    proof.canonical.resultingInventory().inventoryIdentity(),
                    proof.incremental.resultingInventory().inventoryIdentity(),
                    proof.name + " inventory mismatch");
        }
        TransitionPair noOp = proofs.get(proofs.size() - 1);
        assertTrue(noOp.incremental.newFragments().isEmpty());
        assertTrue(noOp.incremental.retiredFragmentBlueIds().isEmpty());
        assertEquals(
                new LinkedHashSet<String>(
                        noOp.prior.fragmentBlueIds()),
                noOp.incremental.reusedFragmentBlueIds());
    }

    @Test
    void shouldReuseStablePhysicalBodiesAcrossAValueOnlyChange() {
        // given
        Node stable = new Node().properties(
                "large", scalar("stable-subtree"),
                "nested", new Node().properties(
                        "answer", scalar(42)));
        Node before = new Node().properties(
                "changed", scalar("before"),
                "stable", stable);
        Node after = new Node().properties(
                "changed", scalar("after"),
                "stable", stable.clone());
        String stableBlueId = blueId(stable);
        MutationCase mutation = new MutationCase(
                "stable-sibling", before, after, noBodies());

        // when
        TransitionPair proof = verifyDifferential(mutation);

        // then
        assertTrue(proof.incremental.reusedFragmentBlueIds()
                .contains(stableBlueId));
        assertFalse(proof.incremental.newFragments()
                .containsKey(stableBlueId));
        assertTrue(proof.incremental.retiredFragmentBlueIds().stream()
                .noneMatch(stableBlueId::equals));
    }

    private static TransitionPair verifyDifferential(MutationCase mutation) {
        CoordinationDocumentSplitter priorSplitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        mutation.before, mutation.executableBodies);
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                priorSplitter.splitDocument(mutation.before);
        CoordinationFragmentInventory prior =
                CoordinationFragmentInventory.from(priorGraph);
        CoordinationDocumentSplitter resultSplitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        mutation.after, mutation.executableBodies);
        CoordinationFragmentTransitionPlanner planner =
                new CoordinationFragmentTransitionPlanner(resultSplitter);
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.<Object>asList(1L, mutation.name));
        Node event = new Node().properties(
                "kind", scalar("transition-oracle"),
                "case", scalar(mutation.name));
        String eventBlueId = blueId(event);
        CoordinationSubscriptionSnapshot snapshot =
                CoordinationEngineProcessorTestFixtures.emptySnapshot(
                        prior.rootBlueId(), 0L, order);
        CoordinationPreparedDelivery prepared =
                CoordinationEngineProcessorTestFixtures.emptyPreparedDelivery(
                        prior.rootBlueId(),
                        eventBlueId,
                        0L,
                        order,
                        snapshot.digest());
        CoordinationSubscriptionUpdate update =
                CoordinationSubscriptionUpdate.unchanged(snapshot, order);
        CoordinationFragmentTransition incremental = planner.plan(
                prior, mutation.after, prepared, update);
        CoordinationFragmentTransition canonical =
                planner.planCanonicalOracle(prior, mutation.after);
        CoordinationFragmentDifferentialProof.verify(
                mutation.after,
                incremental,
                canonical,
                canonicalProvider(priorGraph.fragments()));
        return new TransitionPair(
                mutation.name, prior, incremental, canonical);
    }

    private static List<String> scopeSignatures(
            List<CoordinationScopeTransition> transitions) {
        List<String> result = new ArrayList<>();
        for (CoordinationScopeTransition transition : transitions) {
            result.add(
                    transition.scopePath()
                            + "|" + transition.kind()
                            + "|" + transition.beforeBlueId()
                            + "|" + transition.afterBlueId()
                            + "|" + transition.origin()
                            + "|" + transition.activationIntervalIdentity());
        }
        return result;
    }

    private static NodeProvider canonicalProvider(
            Map<String, Node> fragments) {
        Map<String, Node> retained = new LinkedHashMap<>(fragments);
        return blueId -> {
            Node node = retained.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : Collections.<Node>emptyList();
        };
    }

    private static Node valueDocument(String value) {
        return new Node().properties(
                "changed", scalar(value),
                "stable", new Node().properties(
                        "payload", scalar("unchanged")));
    }

    private static Node embeddedDocument(boolean embedded) {
        Node root = new Node().properties(
                "state", scalar("root"),
                "child", new Node().properties(
                        "state", scalar("child")));
        if (embedded) {
            root.contracts(new Node().properties(
                    "embedded",
                    new Node()
                            .type(reference(RuntimeBlueIds.PROCESS_EMBEDDED))
                            .properties(
                                    "paths",
                                    new Node().items(scalar("/child")))));
        }
        return root;
    }

    private static Node listDocument(String... values) {
        List<Node> items = new ArrayList<>();
        for (String value : values) items.add(scalar(value));
        return new Node().properties(
                "entries", new Node().items(items));
    }

    private static Node contractDocument(String header) {
        return new Node()
                .properties("state", scalar("root"))
                .contracts(new Node().properties(
                        "marker",
                        new Node()
                                .type(reference(blueId(scalar("marker-type"))))
                                .properties("header", scalar(header))));
    }

    private static Node workflowDocument(String label) {
        Node workflow = new Node()
                .type(reference(SequentialWorkflowOperation.blueId()))
                .properties("channel", scalar("timeline"))
                .properties(
                        "steps",
                        new Node().items(
                                new Node().properties(
                                        "label", scalar(label),
                                        "stable", scalar("retained"))));
        return new Node()
                .properties("state", scalar("root"))
                .contracts(new Node().properties("workflow", workflow));
    }

    private static Node referenceDocument(String referenceBlueId) {
        return new Node().properties(
                "target", reference(referenceBlueId),
                "stable", scalar("unchanged"));
    }

    private static Map<String, List<String>> noBodies() {
        return Collections.emptyMap();
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static final class MutationCase {
        private final String name;
        private final Node before;
        private final Node after;
        private final Map<String, List<String>> executableBodies;

        private MutationCase(
                String name,
                Node before,
                Node after,
                Map<String, List<String>> executableBodies) {
            this.name = name;
            this.before = before;
            this.after = after;
            this.executableBodies = executableBodies;
        }
    }

    private static final class TransitionPair {
        private final String name;
        private final CoordinationFragmentInventory prior;
        private final CoordinationFragmentTransition incremental;
        private final CoordinationFragmentTransition canonical;

        private TransitionPair(
                String name,
                CoordinationFragmentInventory prior,
                CoordinationFragmentTransition incremental,
                CoordinationFragmentTransition canonical) {
            this.name = name;
            this.prior = prior;
            this.incremental = incremental;
            this.canonical = canonical;
        }
    }
}
