package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void shouldProjectEmptyOptionalDefinitionWithoutCollapsingStepIdentity() {
        FrozenNode absent = FrozenNode.fromResolvedNode(
                new Node().properties("expr", new Node().value("value")));
        FrozenNode empty = FrozenNode.fromResolvedNode(
                new Node()
                        .properties("expr", new Node().value("value"))
                        .properties(
                                "definition",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap())));
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();

        assertNotEquals(absent.blueId(), empty.blueId());
        assertNotEquals(
                ComputeProgramPlanCache.Key.from(
                        absent,
                        null,
                        null,
                        normalizer.normalizationVersion()),
                ComputeProgramPlanCache.Key.from(
                        empty,
                        null,
                        null,
                        normalizer.normalizationVersion()));
        assertNull(new ComputeDefinitionResolver().resolve(empty, null));
        assertNull(normalizer.program(empty).property("definition"));
    }

    @Test
    void shouldPreservePresentEmptyRootExpression() {
        FrozenNode program = new ComputeProgramNormalizer().program(
                FrozenNode.fromResolvedNode(
                        new Node().properties(
                                "expr",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))));

        FrozenNode expression = program.property("expr");
        assertNotNull(expression);
        assertTrue(NodeUtil.isEmpty(expression.toNode()));
        assertCompilerAccepts(program);
    }

    @Test
    void shouldPreservePresentEmptyFunctionExpression() {
        FrozenNode program = new ComputeProgramNormalizer().program(
                FrozenNode.fromResolvedNode(
                        new Node()
                                .properties("entry", new Node().value("empty"))
                                .properties(
                                        "functions",
                                        new Node().properties(
                                                "empty",
                                                new Node().properties(
                                                        "expr",
                                                        new Node().properties(
                                                                Collections.<String, Node>emptyMap()))))));

        FrozenNode expression = program.property("functions")
                .property("empty")
                .property("expr");
        assertNotNull(expression);
        assertTrue(NodeUtil.isEmpty(expression.toNode()));
        assertCompilerAccepts(program);
    }

    @Test
    void shouldPreservePresentEmptyProgramContainers() {
        Node program = new ComputeProgramNormalizer().program(
                new Node()
                        .properties("do", new Node().items(List.of()))
                        .properties(
                                "constants",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))
                        .properties(
                                "functions",
                                new Node().properties(
                                        "empty",
                                        new Node()
                                                .properties(
                                                        "args",
                                                        new Node().properties(
                                                                Collections.<String, Node>emptyMap()))
                                                .properties(
                                                        "do",
                                                        new Node().items(List.of())))));

        assertNotNull(NodeUtil.property(program, "do"));
        assertTrue(NodeUtil.property(program, "do").getItems().isEmpty());
        assertNotNull(NodeUtil.property(program, "constants"));
        assertTrue(NodeUtil.isEmpty(NodeUtil.property(program, "constants")));
        Node function = NodeUtil.property(
                NodeUtil.property(program, "functions"),
                "empty");
        assertNotNull(NodeUtil.property(function, "args"));
        assertTrue(NodeUtil.isEmpty(NodeUtil.property(function, "args")));
        assertNotNull(NodeUtil.property(function, "do"));
        assertTrue(NodeUtil.property(function, "do").getItems().isEmpty());
    }

    @Test
    void shouldPreserveWrongKindBexFieldsForCompilerRejection() {
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();

        for (String field : List.of("do", "constants", "functions")) {
            FrozenNode program = normalizer.program(
                    FrozenNode.fromResolvedNode(
                            new Node().properties(
                                    field,
                                    new Node().value("wrong-kind"))));

            assertNotNull(program.property(field));
            assertCompilerRejects(program);
        }
    }

    @Test
    void shouldPreserveWrongKindHostControlsForValidationRejection() {
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();
        FrozenNode program = normalizer.program(
                FrozenNode.fromResolvedNode(
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
                                                Collections.<String, Node>emptyMap()))));

        assertNotNull(program.property("entry"));
        assertNotNull(program.property("gasLimit"));
        assertNotNull(program.property("emitEvents"));
        assertNotNull(program.property("returnResult"));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.textProperty(program, "entry"));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.integer(program.property("gasLimit")));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.booleanProperty(
                        program,
                        "emitEvents",
                        true));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNodeUtil.booleanProperty(
                        program,
                        "returnResult",
                        true));
    }

    @Test
    void shouldPreserveWrongKindDefinitionForCompilerRejection() {
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();
        FrozenNode program = normalizer.program(
                FrozenNode.fromResolvedNode(new Node()));
        FrozenNode definition = normalizer.definitionSource(
                FrozenNode.fromResolvedNode(
                        new Node().value("wrong-kind")));

        assertEquals("wrong-kind", FrozenNodeUtil.rawScalar(definition));
        try (BexEngine engine = BexEngine.builder().build()) {
            assertThrows(
                    BexException.class,
                    () -> engine.compile(
                            BexProgramSource.withDefinition(
                                    program,
                                    definition,
                                    null)));
        }
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

    private static void assertCompilerAccepts(FrozenNode program) {
        try (BexEngine engine = BexEngine.builder().build()) {
            engine.compile(BexProgramSource.inline(program));
        }
    }
}
