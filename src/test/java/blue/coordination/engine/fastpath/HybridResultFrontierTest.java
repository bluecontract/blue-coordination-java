package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class HybridResultFrontierTest {
    @Test
    void stopsAtRetainedReferencesInsteadOfExpandingOldRoot() {
        Node enormousRetained = new Node().properties(
                "one", new Node().value(1),
                "two", new Node().value(2),
                "three", new Node().value(3));
        String retainedId = DirectBlueIdCalculator.calculateBlueId(
                enormousRetained);
        Node hybrid = new Node().properties(
                "old", new Node().blueId(retainedId),
                "changed", new Node().value("new"));

        HybridResultFrontier frontier = HybridResultFrontier.scan(hybrid);

        assertEquals(retainedId,
                frontier.retainedBlueIdByPath().get("/old"));
        assertFalse(frontier.expandedByPath().containsValue(enormousRetained));
        assertEquals(2, frontier.expandedNodeCount(),
                "only Root and changed scalar are traversed");
    }

    @Test
    void indexesEveryPathToAStructurallySharedChangedValue() {
        Node shared = new Node().properties(
                "value", new Node().value("changed"));
        Node hybrid = new Node().properties(
                "left", shared,
                "right", shared);

        HybridResultFrontier frontier = HybridResultFrontier.scan(hybrid);

        assertEquals(shared, frontier.expandedByPath().get("/left"));
        assertEquals(shared, frontier.expandedByPath().get("/right"));
        assertEquals(5, frontier.expandedNodeCount());
    }
}
