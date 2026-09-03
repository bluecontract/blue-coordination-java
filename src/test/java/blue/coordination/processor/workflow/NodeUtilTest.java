package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeUtilTest {
    private static final String VALID_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().value("identity"));

    @Test
    void shouldTreatOnlyAxisFreeMutableAndFrozenNodesAsEmpty() {
        // given
        Node empty = new Node();
        FrozenNode frozenEmpty = FrozenNode.fromNode(new Node());

        // when
        boolean mutableEmpty = NodeUtil.isEmpty(empty);
        boolean immutableEmpty = FrozenNodeUtil.isEmpty(frozenEmpty);

        // then
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
        // given
        Node absentText = new Node();
        Node numericText = new Node().value(1);
        Node textualBoolean = new Node().properties(
                "flag",
                new Node().value("true"));
        FrozenNode oversizedInteger = FrozenNode.fromNode(
                new Node().value(BigInteger.ONE.shiftLeft(80)));

        // when
        String missing = NodeUtil.text(absentText);

        // then
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

    @Test
    void shouldDefaultOnlyAbsentFrozenComputeControls() {
        FrozenNode absent = FrozenNode.fromResolvedNode(new Node());
        FrozenNode emptyControls = FrozenNode.fromResolvedNode(
                new Node()
                        .properties(
                                "entry",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))
                        .properties(
                                "gasLimit",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))
                        .properties(
                                "emitEvents",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))
                        .properties(
                                "returnResult",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap())));

        assertNull(FrozenNodeUtil.textProperty(absent, "entry"));
        assertNull(FrozenNodeUtil.integer(
                FrozenNodeUtil.property(absent, "gasLimit")));
        assertTrue(FrozenNodeUtil.booleanProperty(
                absent, "emitEvents", true));
        assertFalse(FrozenNodeUtil.booleanProperty(
                absent, "returnResult", false));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.textProperty(emptyControls, "entry"));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.integer(
                        FrozenNodeUtil.property(
                                emptyControls,
                                "gasLimit")));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.booleanProperty(
                        emptyControls,
                        "emitEvents",
                        true));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.booleanProperty(
                        emptyControls,
                        "returnResult",
                        true));
    }

    private static void assertRetained(Node node) {
        assertFalse(NodeUtil.isEmpty(node));
        assertFalse(FrozenNodeUtil.isEmpty(
                FrozenNode.fromNode(node)));
    }
}
