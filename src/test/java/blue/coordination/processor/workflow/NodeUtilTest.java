package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeUtilTest {
    private static final String VALID_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    new Node().value("identity"));

    @Test
    void mutableAndFrozenEmptinessRetainIdentityBearingAxes() {
        assertTrue(NodeUtil.isEmpty(new Node()));
        assertTrue(FrozenNodeUtil.isEmpty(
                FrozenNode.fromNode(new Node())));

        assertRetained(new Node().name("named"));
        assertRetained(new Node().type(
                new Node().blueId(VALID_BLUE_ID)));
        assertRetained(new Node().blueId(VALID_BLUE_ID));
        assertRetained(new Node().items(
                Collections.<Node>emptyList()));
    }

    @Test
    void scalarReadersDoNotCoerceAcrossContractsTypes() {
        assertNull(NodeUtil.text(new Node()));
        assertThrows(IllegalArgumentException.class,
                () -> NodeUtil.text(new Node().value(1)));
        assertThrows(IllegalArgumentException.class,
                () -> NodeUtil.booleanProperty(
                        new Node().properties(
                                "flag",
                                new Node().value("true")),
                        "flag",
                        false));
        assertThrows(ArithmeticException.class,
                () -> FrozenNodeUtil.integer(
                        FrozenNode.fromNode(
                                new Node().value(
                                        BigInteger.ONE.shiftLeft(80)))));
    }

    private static void assertRetained(Node node) {
        assertFalse(NodeUtil.isEmpty(node));
        assertFalse(FrozenNodeUtil.isEmpty(
                FrozenNode.fromNode(node)));
    }
}
