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
        // given
        FrozenNode program = normalizeStatement(
                new Node().properties(Collections.<String, Node>emptyMap()));

        // when
        FrozenNode statement = statement(program);

        // then
        assertEquals(Collections.emptyMap(), statement.toNode().getProperties());
        assertFalse(hasReturn(statement));
        assertCompilerRejects(program);
    }

    @Test
    void shouldPreserveEmptyPlaceholderStatementForCompilerRejection() {
        // given
        FrozenNode program = normalizeStatement(Nodes.emptyPlaceholder());

        // when
        FrozenNode statement = statement(program);

        // then
        assertTrue(Nodes.isEmptyPlaceholder(statement.toNode()));
        assertFalse(hasReturn(statement));
        assertCompilerRejects(program);
    }

    @Test
    void shouldProjectEmptyOptionalDefinitionWithoutCollapsingStepIdentity() {
        // given
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

        // when
        FrozenNode normalized = normalizer.program(empty);

        // then
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
        assertEquals(empty.property("definition").blueId(),
                new ComputeDefinitionResolver().resolve(empty, null).blueId());
        assertEquals(Collections.emptyMap(), normalized.property("definition").getProperties());
        assertCompilerAccepts(normalized);
    }

    @Test
    void shouldPreservePresentEmptyRootExpression() {
        // given
        FrozenNode program = new ComputeProgramNormalizer().program(
                FrozenNode.fromResolvedNode(
                        new Node().properties(
                                "expr",
                                new Node().properties(
                                        Collections.<String, Node>emptyMap()))));

        // when
        FrozenNode expression = program.property("expr");

        // then
        assertNotNull(expression);
        assertEquals(Collections.emptyMap(), expression.toNode().getProperties());
        assertCompilerAccepts(program);
    }

    @Test
    void shouldPreservePresentEmptyFunctionExpression() {
        // given
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

        // when
        FrozenNode expression = program.property("functions")
                .property("empty")
                .property("expr");

        // then
        assertNotNull(expression);
        assertEquals(Collections.emptyMap(), expression.toNode().getProperties());
        assertCompilerAccepts(program);
    }

    @Test
    void shouldPreservePresentEmptyProgramContainers() {
        // given
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

        // when
        Node statements = NodeUtil.property(program, "do");

        // then
        assertNotNull(statements);
        assertTrue(NodeUtil.property(program, "do").getItems().isEmpty());
        assertNotNull(NodeUtil.property(program, "constants"));
        assertEquals(Collections.emptyMap(), NodeUtil.property(program, "constants").getProperties());
        Node function = NodeUtil.property(
                NodeUtil.property(program, "functions"),
                "empty");
        assertNotNull(NodeUtil.property(function, "args"));
        assertEquals(Collections.emptyMap(), NodeUtil.property(function, "args").getProperties());
        assertNotNull(NodeUtil.property(function, "do"));
        assertTrue(NodeUtil.property(function, "do").getItems().isEmpty());
    }

    @Test
    void shouldPreserveWrongKindBexFieldsForCompilerRejection() {
        // given
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();

        // when
        for (String field : List.of("do", "constants", "functions")) {
            FrozenNode program = normalizer.program(
                    FrozenNode.fromResolvedNode(
                            new Node().properties(
                                    field,
                                    new Node().value("wrong-kind"))));

            // then

            assertNotNull(program.property(field));
            assertCompilerRejects(program);
        }
    }

    @Test
    void shouldPreserveWrongKindHostControlsForValidationRejection() {
        // given
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();

        // when
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

        // then
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
        // given
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();

        // when
        FrozenNode program = normalizer.program(
                FrozenNode.fromResolvedNode(Nodes.emptyObject()));
        FrozenNode definition = normalizer.definitionSource(
                FrozenNode.fromResolvedNode(
                        new Node().value("wrong-kind")));

        // then
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
