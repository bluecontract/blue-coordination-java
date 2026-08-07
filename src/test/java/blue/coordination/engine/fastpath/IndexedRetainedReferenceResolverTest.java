package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class IndexedRetainedReferenceResolverTest {
    @Test
    void graftsPreparedRetainedSubtreeWithoutCloneOrPriorRootScan() {
        Object owner = new Object();
        Node retainedBody = new Node().properties(
                "stable", new Node().value("large-common-prefix"));
        String retainedId = DirectBlueIdCalculator.calculateBlueId(
                retainedBody);
        RetainedReferenceIndex index = RetainedReferenceIndex.builder(owner)
                .add(retainedId, retainedBody)
                .build();
        Node changedResult = new Node().properties(
                "retained", new Node().blueId(retainedId),
                "changed", new Node().value(8));

        Node resolved = new IndexedRetainedReferenceResolver(index, owner)
                .resolveRequestOwned(changedResult);

        assertSame(changedResult, resolved,
                "changed PROCESS object is rewritten in place");
        assertSame(retainedBody,
                resolved.getProperties().get("retained"),
                "retained subtree must be structurally shared");
        assertEquals(BigInteger.valueOf(8L),
                resolved.getProperties().get("changed").getValue());
    }
}
