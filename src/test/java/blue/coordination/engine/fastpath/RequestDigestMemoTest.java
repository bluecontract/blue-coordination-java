package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class RequestDigestMemoTest {
    @Test
    void calculatesOneDigestForRepeatedEngineLayers() {
        Node exact = new Node().properties(
                "counter", new Node().value(7),
                "label", new Node().value("hotel"));
        RequestDigestMemo memo = new RequestDigestMemo();

        String first = memo.blueId(exact);
        String second = memo.blueId(exact);
        String third = memo.blueId(exact);

        assertEquals(DirectBlueIdCalculator.calculateBlueId(exact), first);
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(1L, memo.calculations());
        assertEquals(2L, memo.hits());
    }

    @Test
    void doesNotReuseDigestAcrossDistinctMutableObjects() {
        Node first = new Node().value("first");
        Node second = new Node().value("second");
        RequestDigestMemo memo = new RequestDigestMemo();

        assertNotEquals(memo.blueId(first), memo.blueId(second));
        assertEquals(2L, memo.calculations());
    }
}
