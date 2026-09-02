package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeProgramNormalizerTest {
    @Test
    void shouldPreserveEmptyObjectStatementForCompilerRejection() {
        FrozenNode program = normalizeStatement(
                new Node().properties(Collections.<String, Node>emptyMap()));

        FrozenNode statement = statement(program);
        assertTrue(NodeUtil.isEmpty(statement.toNode()));
        assertFalse(hasReturn(statement));
        assertCompilerRejects(program);
    }

    @Test
    void shouldPreserveEmptyPlaceholderStatementForCompilerRejection() {
        FrozenNode program = normalizeStatement(Nodes.emptyPlaceholder());

        FrozenNode statement = statement(program);
        assertTrue(Nodes.isEmptyPlaceholder(statement.toNode()));
        assertFalse(hasReturn(statement));
        assertCompilerRejects(program);
    }

    private static FrozenNode normalizeStatement(Node statement) {
        return new ComputeProgramNormalizer().program(
                FrozenNode.fromResolvedNode(
                        new Node().properties(
                                "do",
                                new Node().items(statement))));
    }

    private static FrozenNode statement(FrozenNode program) {
        return program.property("do").getItems().get(0);
    }

    private static boolean hasReturn(FrozenNode statement) {
        return statement.getProperties() != null
                && statement.getProperties().containsKey("$return");
    }

    private static void assertCompilerRejects(FrozenNode program) {
        try (BexEngine engine = BexEngine.builder().build()) {
            assertThrows(
                    BexException.class,
                    () -> engine.compile(BexProgramSource.inline(program)));
        }
    }
}
