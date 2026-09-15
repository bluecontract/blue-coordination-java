package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @ParameterizedTest
    @ValueSource(strings = {"constants", "functions"})
    void shouldOmitUnchangedInheritedEmptyDefinitionDeclarations(String field) {
        // given
        Node inherited = new Node().type(new Node().name("Dictionary"));
        FrozenNode definition = resolvedDefinition(field, inherited, inherited.clone());
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();

        // when
        FrozenNode source = normalizer.definitionSource(definition, true);

        // then
        assertNull(source.property(field));
        assertNotNull(normalizer.definitionSource(definition).property(field));
        assertNotNull(definition.property(field));
        assertSame(definition, normalizer.definition(definition));
    }

    @ParameterizedTest
    @ValueSource(strings = {"constants", "functions"})
    void exactPresencePreventsDiscardingADeclarationEqualToTheInheritedDefault(String field) {
        Node inherited = new Node().type(new Node().name("Dictionary"));
        FrozenNode definition = resolvedDefinition(field, inherited, inherited.clone());
        ComputeProgramNormalizer normalizer = new ComputeProgramNormalizer();
        int mask = field.equals("constants") ? 1 : 2;
        FrozenNode preserved = normalizer.definitionSource(definition, true, mask);
        assertNotNull(preserved.property(field));
        assertNull(normalizer.definitionSource(definition, true, 0).property(field));
        try (BexEngine engine = BexEngine.builder().build()) {
            assertThrows(BexException.class, () -> engine.compile(BexProgramSource.withDefinition(
                    FrozenNode.fromNode(new Node().properties("expr", new Node().value(true))),
                    preserved, null)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"constants", "functions"})
    void shouldPreserveExplicitEmptyDefinitionMaps(String field) {
        // given
        Node inherited = new Node().type(new Node().name("Dictionary"));
        FrozenNode definition = resolvedDefinition(field, inherited,
                inherited.clone().properties(Collections.emptyMap()));

        // when
        FrozenNode source = new ComputeProgramNormalizer().definitionSource(definition, true);

        // then
        assertNotNull(source.property(field));
        assertEquals(Collections.emptyMap(), source.property(field).getProperties());
        try (BexEngine engine = BexEngine.builder().build()) {
            engine.compile(BexProgramSource.withDefinition(
                    FrozenNode.fromNode(new Node().properties("expr", new Node().value(true))),
                    source, null));
        }
    }

    @ParameterizedTest
    @MethodSource("malformedDefinitionMaps")
    void shouldPreserveMalformedResolvedDefinitionMapsForCompilerRejection(
            String field, Node inherited, Node authored) {
        // given
        FrozenNode definition = resolvedDefinition(field, inherited, authored);

        // when
        FrozenNode source = new ComputeProgramNormalizer().definitionSource(definition, true);

        // then
        assertNotNull(source.property(field));
        try (BexEngine engine = BexEngine.builder().build()) {
            assertThrows(BexException.class, () -> engine.compile(BexProgramSource.withDefinition(
                    FrozenNode.fromNode(new Node().properties("expr", new Node().value(true))),
                    source, null)));
        }
    }

    @Test
    void shouldRetainPopulatedInheritedDefinitionMaps() {
        // given
        Node fields = new Node()
                .properties("constants", new Node().properties("message", new Node().value("coffee")))
                .properties("functions", new Node().properties("run",
                        new Node().properties("expr", new Node().value("coffee"))));
        FrozenNode definition = FrozenNode.fromResolvedNode(fields.clone().type(fields));

        // when
        FrozenNode source = new ComputeProgramNormalizer().definitionSource(definition, true);

        // then
        assertEquals("coffee", source.property("constants").property("message").getValue());
        assertEquals("coffee", source.property("functions").property("run").property("expr").getValue());
    }

    private static Stream<Arguments> malformedDefinitionMaps() {
        return Stream.of("constants", "functions").flatMap(field -> {
            Node declaration = new Node().type(new Node().name("Dictionary"));
            Node scalar = new Node().value("wrong-kind");
            Node list = new Node().items(List.of());
            return Stream.of(
                    Arguments.of(field, declaration, scalar),
                    Arguments.of(field, scalar, scalar.clone()),
                    Arguments.of(field, declaration, list),
                    Arguments.of(field, list, list.clone()),
                    Arguments.of(field, declaration, declaration.clone().description("Authored declaration")),
                    Arguments.of(field, null, declaration));
        });
    }

    private static FrozenNode resolvedDefinition(String field, Node inherited, Node authored) {
        Node definition = new Node().properties(field, authored);
        if (inherited != null) {
            definition.type(new Node().properties(field, inherited));
        }
        return FrozenNode.fromResolvedNode(definition);
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
