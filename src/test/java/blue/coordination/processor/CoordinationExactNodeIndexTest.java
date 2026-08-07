package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.provider.ExactNodeGraphFragments;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract and structural-work proof for the bottom-up exact-node index. */
class CoordinationExactNodeIndexTest {

    @Test
    void shouldMatchCanonicalDirectFragmentAcrossEveryNodeShape() {
        // given
        Node sharedType = new Node()
                .name("Shared type")
                .properties("kind", new Node().value("type"));
        Node root = new Node()
                .name("Root")
                .description("all direct child forms")
                .type(sharedType)
                .itemType(new Node().value("item type"))
                .keyType(new Node().value("key type"))
                .valueType(new Node().value("value type"))
                .contracts(new Node().properties(
                        "channel",
                        new Node().type(sharedType)))
                .items(Arrays.asList(
                        new Node().value("first"),
                        new Node().properties(
                                "nested",
                                new Node().value("second"))))
                .schema(new Schema()
                        .required(new Node().value(true))
                        .minimum(new Node()
                                .name("decorated")
                                .value(1L)));
        CoordinationExactNodeIndex index =
                new CoordinationExactNodeIndex();
        ExactNodeGraphFragments canonical =
                new ExactNodeGraphFragments(root);

        // when
        String indexedBlueId = index.blueId(root);
        Node indexedDirect = index.directFragment(root);

        // then
        assertEquals(canonical.roots().get(0).blueId(), indexedBlueId);
        assertEquals(
                NodeWireForm.get(
                        canonical.roots().get(0).directFragment()),
                NodeWireForm.get(indexedDirect));
        assertEquals(
                indexedBlueId,
                DirectBlueIdCalculator.calculateBlueId(indexedDirect));
        Map<String, Node> canonicalFragments = canonical.fragments();
        assertEquals(
                canonicalFragments.size(),
                index.nodesByBlueId().size());
        for (Map.Entry<String, Node> exact
                : index.nodesByBlueId().entrySet()) {
            assertEquals(
                    NodeWireForm.get(canonicalFragments.get(exact.getKey())),
                    NodeWireForm.get(
                            index.directFragment(exact.getValue())),
                    exact.getKey());
        }
    }

    @Test
    void shouldHashEachInlineOccurrenceOnlyOnceForADeepDocument() {
        // given
        int depth = 400;
        Node root = new Node().name("leaf");
        for (int index = depth - 1; index >= 0; index--) {
            root = new Node()
                    .name("level-" + index)
                    .properties("next", root);
        }
        CoordinationExactNodeIndex index =
                new CoordinationExactNodeIndex();
        String expectedBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);

        // when
        String first = index.blueId(root);
        String second = index.blueId(root);

        // then
        assertEquals(expectedBlueId, first);
        assertEquals(first, second);
        assertEquals(depth + 1L, index.identityCalculationCount());
        assertEquals(depth + 1, index.nodesByBlueId().size());
    }
}
