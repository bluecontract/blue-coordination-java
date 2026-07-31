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
    void shouldTreatOnlyAxisFreeMutableAndFrozenNodesAsEmpty() {
        // Given
        Node empty = new Node();
        FrozenNode frozenEmpty = FrozenNode.fromNode(new Node());

        // When
        boolean mutableEmpty = NodeUtil.isEmpty(empty);
        boolean immutableEmpty = FrozenNodeUtil.isEmpty(frozenEmpty);

        // Then
        assertTrue(mutableEmpty);
        assertTrue(immutableEmpty);
        assertRetained(new Node().name("named"));
        assertRetained(new Node().type(
                new Node().blueId(VALID_BLUE_ID)));
        assertRetained(new Node().blueId(VALID_BLUE_ID));
        assertRetained(new Node().items(
                Collections.<Node>emptyList()));
    }

    @Test
    void shouldRejectScalarCoercionAcrossContractTypes() {
        // Given
        Node absentText = new Node();
        Node numericText = new Node().value(1);
        Node textualBoolean = new Node().properties(
                "flag",
                new Node().value("true"));
        FrozenNode oversizedInteger = FrozenNode.fromNode(
                new Node().value(BigInteger.ONE.shiftLeft(80)));

        // When
        String missing = NodeUtil.text(absentText);

        // Then
        assertNull(missing);
        assertThrows(IllegalArgumentException.class,
                () -> NodeUtil.text(numericText));
        assertThrows(IllegalArgumentException.class,
                () -> NodeUtil.booleanProperty(
                        textualBoolean,
                        "flag",
                        false));
        assertThrows(ArithmeticException.class,
                () -> FrozenNodeUtil.integer(oversizedInteger));
    }

    private static void assertRetained(Node node) {
        assertFalse(NodeUtil.isEmpty(node));
        assertFalse(FrozenNodeUtil.isEmpty(
                FrozenNode.fromNode(node)));
    }
}
