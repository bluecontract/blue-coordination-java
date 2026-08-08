package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VerifiedFragmentTransitionFrontierTest {

    @Test
    void shouldFreezeSparseFrontierAndTranslateListPathsExactly() {
        Node retained = new Node().properties(
                "payload", new Node().value("stable"));
        String retainedBlueId = DirectBlueIdCalculator.calculateBlueId(
                retained);
        Node hybrid = new Node().items(
                new Node().blueId(retainedBlueId),
                new Node().value("changed"));
        HybridResultFrontier scanned = HybridResultFrontier.scan(hybrid);
        Map<String, Node> retainedNodes = new LinkedHashMap<String, Node>();
        retainedNodes.put("/0", retained);
        VerifiedHybridResultFrontier proof = new VerifiedHybridResultFrontier(
                "session",
                1L,
                "prior-root",
                "prior-inventory",
                new Node().items(retained, new Node().value("before")),
                hybrid,
                new LinkedHashSet<String>(scanned.expandedByPath().keySet()),
                Collections.<String, Node>emptyMap(),
                scanned.retainedBlueIdByPath(),
                retainedNodes,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<String, Node>emptyMap());
        String resultBlueId = DirectBlueIdCalculator.calculateBlueId(hybrid);

        VerifiedFragmentTransitionFrontier frontier =
                proof.snapshotForFragmentTransition(hybrid, resultBlueId);
        hybrid.items(
                new Node().value("mutated"),
                new Node().value("changed"));

        assertEquals(
                retainedBlueId,
                frontier.retainedBlueIdByPhysicalPath().get("/items/0"));
        assertEquals(
                resultBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        frontier.sparseResultRoot()));
    }
}
