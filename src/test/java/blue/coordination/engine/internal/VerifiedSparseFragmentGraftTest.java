package blue.coordination.engine.internal;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.provider.NodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VerifiedSparseFragmentGraftTest {

    @Test
    void shouldMatchCanonicalInventoryWithoutOpeningRetainedSibling() {
        Node stable = new Node().properties(
                "payload", new Node().value("unchanged"));
        String stableBlueId = DirectBlueIdCalculator.calculateBlueId(stable);
        Node before = new Node().properties(
                "changed", new Node().value("before"),
                "stable", stable);
        Node after = new Node().properties(
                "changed", new Node().value("after"),
                "stable", stable.clone());
        CoordinationDocumentSplitter priorSplitter = splitter(before);
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                priorSplitter.splitDocument(before);
        CoordinationFragmentInventory prior =
                CoordinationFragmentInventory.from(priorGraph);
        CoordinationDocumentSplitter resultSplitter = splitter(after);
        CoordinationFragmentInventory canonical =
                CoordinationFragmentInventory.from(
                        resultSplitter.splitDocument(after));
        Node sparse = new Node().properties(
                "changed", new Node().value("after"),
                "stable", new Node().blueId(stableBlueId));
        CoordinationDocumentSplitter.DocumentFragmentationBlueprint blueprint =
                resultSplitter.verifiedFrontierFragmentationBlueprint(
                        sparse,
                        DirectBlueIdCalculator.calculateBlueId(after),
                        null);
        Map<String, String> retained = Collections.singletonMap(
                "/stable", stableBlueId);

        CoordinationIncrementalFragmentAssembler.AssembledDocument assembled =
                new CoordinationIncrementalFragmentAssembler(
                        resultSplitter,
                        provider(priorGraph.fragments()),
                        null)
                        .assemble(
                                prior,
                                blueprint,
                                Collections.<String>emptyList(),
                                retained);

        assertEquals(
                canonical.inventoryIdentity(),
                assembled.inventory().inventoryIdentity());
        assertTrue(assembled.reusedFragmentCount() >= 1L);
        assertEquals(0L,
                resultSplitter.completeBlueprintCanonicalCopyCount()
                        - 1L,
                "only the explicit canonical oracle may clone the full Root");
    }

    @Test
    void shouldRequireColdFallbackWhenValidBlueIdMovesToAnotherPath() {
        Node stable = new Node().properties(
                "payload", new Node().value("same-blue-id"));
        String stableBlueId = DirectBlueIdCalculator.calculateBlueId(stable);
        Node before = new Node().properties(
                "left", stable,
                "marker", new Node().value("before"));
        Node after = new Node().properties(
                "right", stable.clone(),
                "marker", new Node().value("after"));
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                splitter(before).splitDocument(before);
        CoordinationFragmentInventory prior =
                CoordinationFragmentInventory.from(priorGraph);
        CoordinationDocumentSplitter resultSplitter = splitter(after);
        Node sparse = new Node().properties(
                "right", new Node().blueId(stableBlueId),
                "marker", new Node().value("after"));
        CoordinationDocumentSplitter.DocumentFragmentationBlueprint blueprint =
                resultSplitter.verifiedFrontierFragmentationBlueprint(
                        sparse,
                        DirectBlueIdCalculator.calculateBlueId(after),
                        null);

        CoordinationIncrementalFragmentAssembler
                .ColdFragmentGraftRequiredException cold = assertThrows(
                CoordinationIncrementalFragmentAssembler
                        .ColdFragmentGraftRequiredException.class,
                () -> new CoordinationIncrementalFragmentAssembler(
                        resultSplitter,
                        provider(priorGraph.fragments()),
                        null)
                        .assemble(
                                prior,
                                blueprint,
                                Collections.<String>emptyList(),
                                Collections.singletonMap(
                                        "/right", stableBlueId)));

        assertEquals(
                CoordinationIncrementalFragmentAssembler
                        .ColdGraftReason.PRIOR_OCCURRENCE_MISSING,
                cold.reason());
    }

    private static CoordinationDocumentSplitter splitter(Node root) {
        return CoordinationFragmentationCatalogHarness.splitter(
                root,
                Collections.<String, java.util.List<String>>emptyMap());
    }

    private static NodeProvider provider(Map<String, Node> supplied) {
        Map<String, Node> retained = new LinkedHashMap<String, Node>(supplied);
        return blueId -> {
            Node value = retained.get(blueId);
            return value == null
                    ? Collections.<Node>emptyList()
                    : Collections.singletonList(value.clone());
        };
    }
}
