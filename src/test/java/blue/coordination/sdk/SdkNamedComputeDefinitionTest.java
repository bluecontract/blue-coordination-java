package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public execution parity for named and exact Compute definitions. */
final class SdkNamedComputeDefinitionTest {
    private static final String COFFEE_CODE = """
            type: Coordination/Compute Definition
            functions:
              prepareCoffee:
                do:
                  - $appendEvent:
                      name: Coffee prepared
            """;
    private static final String INLINE_STEP = """
            - type: Coordination/Compute
              do:
                - $appendEvent:
                    name: Coffee prepared
            """;

    @ParameterizedTest
    @EnumSource(DefinitionSelector.class)
    void initializationExecutesTheSelectedDefinition(
            DefinitionSelector selector) {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String source = initialization(namedStep(blue, selector));

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @Test
    void inlineInitializationEmitsTheSameCoffeeEvent() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String source = initialization(INLINE_STEP);

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @ParameterizedTest
    @EnumSource(DefinitionSelector.class)
    void operationExecutesTheSelectedDefinition(
            DefinitionSelector selector) {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle provider = blue.timelines().register(
                    "coffee-shop", "myos-mini:principal:coffee-shop");
            DocumentHandle document = admit(
                    blue, operation(namedStep(blue, selector)));
            assertTrue(document.snapshot().publicEvents().isEmpty());

            // when
            EntryResult result = blue.operations()
                    .on(document)
                    .from(provider)
                    .call("prepareCoffee")
                    .through("providerChannel")
                    .requestYaml("{}")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition(),
                    result.diagnostic().toString());
            assertCoffeeEvent(blue, result.publicEvents());
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    private static DocumentHandle admit(
            BlueCoordination blue, String source) {
        return blue.documents().admit(ManagedDocument.yaml(
                        DocumentId.of("coffee"), source)
                .publicRoot()
                .fromNow());
    }

    @Test
    void shouldExecuteAnInheritedNamedDefinition() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String base = blue.values().yaml("contracts:\n  coffeeCode:\n"
                    + COFFEE_CODE.indent(4)).blueId();
            String source = "type: {blueId: " + base + "}\n"
                    + initialization(namedStep(blue, DefinitionSelector.NAMED))
                            .replace("  coffeeCode:\n" + COFFEE_CODE.indent(4), "");

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @Test
    void shouldExecuteFunctionsInheritedByAPointerDefinition() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String base = blue.values().yaml(COFFEE_CODE).blueId();
            String source = "library:\n  coffeeCode:\n    type: {blueId: " + base + "}\n"
                    + initialization(namedStep(blue, DefinitionSelector.NAMED)
                            .replace("definition: coffeeCode", "definition: /library/coffeeCode"));

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @Test
    void shouldExecuteWithBothOptionalDefinitionMapsInheritedAndEmpty() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String source = initialization("type: Coordination/Compute Definition\n",
                    INLINE_STEP + "  definition: coffeeCode\n");

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @Test
    void shouldReadADefinitionThroughAReferencedParent() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String library = blue.values().yaml(
                    "coffeeCode:\n" + COFFEE_CODE.indent(2)).blueId();
            String source = "library: {blueId: " + library + "}\n"
                    + initialization(namedStep(blue, DefinitionSelector.NAMED)
                            .replace("definition: coffeeCode",
                                    "definition: /library/coffeeCode"));

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @Test
    void shouldResolveAnEscapedDefinitionName() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String source = initialization(namedStep(blue, DefinitionSelector.NAMED))
                    .replace("coffeeCode", "coffee/~code");

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertCoffeeEvent(blue, document.snapshot().publicEvents());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "nestedType", "itemType", "valueType"})
    void shouldValidateAStaticTypeReachedThroughTheNamedDefinition(String field) {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String base = blue.values().yaml("name: Coffee Event").blueId();
            String eventType = blue.values().yaml("type: {blueId: " + base + "}\n").blueId();
            String event = eventWithStaticType(field, eventType);
            String source = initialization(definitionWithEvent(event),
                    namedStep(blue, DefinitionSelector.NAMED));

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertEquals(List.of(blue.values().yaml(event)),
                    document.snapshot().publicEvents().stream()
                            .map(PublicEvent::exact).toList());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidStaticTypes")
    void shouldRejectExpressionsHiddenInANamedDefinitionsStaticType(String field, String type) {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String eventType = blue.values().yaml(type).blueId();
            String event = eventWithStaticType(field, eventType);
            String source = initialization(definitionWithEvent(event),
                    namedStep(blue, DefinitionSelector.NAMED));

            // when
            CoordinationException failure = assertThrows(
                    CoordinationException.class, () -> admit(blue, source));

            // then
            assertTrue(failure.getMessage().contains(
                    "BEX expressions inside Blue " + (field.equals("nestedType") ? "type" : field)
                            + " fields"), failure.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "itemType", "valueType"})
    void shouldRejectInvalidNamedDefinitionOperationsWithoutChangingTheDocument(String field) {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle provider = blue.timelines().register(
                    "coffee-shop", "myos-mini:principal:coffee-shop");
            String eventType = blue.values().yaml("$add: [1, 2]\n").blueId();
            String source = operation(namedStep(blue, DefinitionSelector.NAMED))
                    .replace(COFFEE_CODE.indent(4),
                            definitionWithEvent(eventWithStaticType(field, eventType)).indent(4));
            DocumentHandle document = admit(blue, source);
            var before = document.snapshot();

            // when
            EntryResult result = call(blue, document, provider, "prepareCoffee");

            // then
            assertEquals(EntryDisposition.REJECTED, result.disposition(), result.diagnostic().toString());
            assertTrue(result.diagnostic().message().contains("BEX expressions inside Blue " + field + " fields"),
                    result.diagnostic().toString());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(before.blueId(), document.snapshot().blueId());
            assertEquals(before.epoch(), document.snapshot().epoch());
        }
    }

    @Test
    void shouldValidateBasicDictionaryKeyTypesInANamedDefinition() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String event = "name: Coffee prepared\npayload/~event:\n"
                    + "  type: Dictionary\n  keyType: Text\n  ordinary: value\n";
            String source = initialization(definitionWithEvent(event),
                    namedStep(blue, DefinitionSelector.NAMED));

            // when
            DocumentHandle document = admit(blue, source);

            // then
            assertEquals(List.of(blue.values().yaml(event)), document.snapshot().publicEvents().stream()
                    .map(PublicEvent::exact).toList());
        }
    }

    @Test
    void shouldRejectNonBasicDictionaryKeyTypesBeforeExecution() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String keyType = blue.values().yaml("$add: [1, 2]\n").blueId();
            String source = initialization(definitionWithEvent(eventWithStaticType("keyType", keyType)),
                    namedStep(blue, DefinitionSelector.NAMED));

            // when
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> admit(blue, source));

            // then
            assertEquals("Dictionary key type must be a basic type", failure.getMessage());
        }
    }

    @Test
    void shouldUseAChangedNamedDefinitionAfterWarmingItsPlan() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle provider = blue.timelines().register(
                    "coffee-shop", "myos-mini:principal:coffee-shop");
            String event = "name: Coffee recipe changed\n";
            String replacementDefinition = blue.values().yaml(definitionWithEvent(event)).blueId();
            String source = operation(namedStep(blue, DefinitionSelector.NAMED)) + """
                      replaceCoffee:
                        type: Coordination/Sequential Workflow Operation
                        channel: providerChannel
                        request: {}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: replace
                                  path: /contracts/coffeeCode
                                  val:
                                    $literal: {blueId: %s}
                    """.formatted(replacementDefinition);
            DocumentHandle document = admit(blue, source);
            EntryResult cold = call(blue, document, provider, "prepareCoffee");
            EntryResult warm = call(blue, document, provider, "prepareCoffee");
            var before = document.snapshot();

            // when
            EntryResult replacement = call(blue, document, provider, "replaceCoffee");
            EntryResult changed = call(blue, document, provider, "prepareCoffee");

            // then
            assertEquals(EntryDisposition.APPLIED, cold.disposition(), cold.diagnostic().toString());
            assertEquals(EntryDisposition.APPLIED, warm.disposition(), warm.diagnostic().toString());
            assertEquals(EntryDisposition.APPLIED, replacement.disposition(), replacement.diagnostic().toString());
            assertEquals(EntryDisposition.APPLIED, changed.disposition(), changed.diagnostic().toString());
            assertCoffeeEvent(blue, cold.publicEvents());
            assertCoffeeEvent(blue, warm.publicEvents());
            assertCoffeeEvent(blue, before.publicEvents());
            assertEquals(List.of(blue.values().yaml(event)), changed.publicEvents().stream()
                    .map(PublicEvent::exact).toList());
            assertEquals(changed.publicEvents(), document.snapshot().publicEvents());
        }
    }

    private static EntryResult call(BlueCoordination blue, DocumentHandle document,
                                    TimelineHandle provider, String operation) {
        return blue.operations().on(document).from(provider).call(operation)
                .through("providerChannel").requestYaml("{}").execute();
    }

    @ParameterizedTest
    @MethodSource("rootDefinitionStaticTypes")
    void rootAndNestedDefinitionPointersUseTheSameStaticTypeRules(String pointer, boolean valid) {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String type = blue.values().yaml(valid ? "name: Coffee Event\n" : "$add: [1, 2]\n").blueId();
            String event = eventWithStaticType("type", type);
            String selectedDefinition = definitionWithEvent(event)
                    .replace("type: Coordination/Compute Definition\n", "");
            String workflow = initialization(namedStep(blue, DefinitionSelector.NAMED)
                    .replace("definition: coffeeCode", "definition: " + pointer));
            String source = pointer.equals("/") ? selectedDefinition + workflow
                    : "library:\n" + selectedDefinition.indent(2) + workflow;
            if (valid) {
                DocumentHandle document = admit(blue, source);
                assertEquals(List.of(blue.values().yaml(event)), document.snapshot().publicEvents().stream()
                        .map(PublicEvent::exact).toList());
            } else {
                CoordinationException failure = assertThrows(CoordinationException.class, () -> admit(blue, source));
                assertTrue(failure.getMessage().contains("BEX expressions inside Blue type fields"),
                        "The rejected expression must be reached, not hidden by an unresolved path: " + failure);
            }
        }
    }

    private static Stream<Arguments> rootDefinitionStaticTypes() {
        return Stream.of("/", "/library").flatMap(pointer -> Stream.of(
                Arguments.of(pointer, true), Arguments.of(pointer, false)));
    }

    @ParameterizedTest
    @MethodSource("explicitDefinitionMapForms")
    void exactProviderDefinitionKeepsContainerMeaningAcrossNamedAndExactSelection(
            String field, String form, boolean named) {
        String definition = "type: Coordination/Compute Definition\n" + switch (form) {
            case "absent" -> "";
            case "empty" -> field + ": {}\n";
            case "declaration" -> field + ": {type: Dictionary}\n";
            default -> throw new IllegalArgumentException(form);
        };
        ExactBlueValue exact;
        try (BlueCoordination preparer = BlueCoordination.inMemory()) {
            // Preserve authored exact content; do not minimize an invalid field out of the fixture.
            exact = preparer.values().providerContentYaml(definition);
            System.out.println("NAMED_COMPUTE_DECLARATION_INPUT field=" + field + " form=" + form
                    + " named=" + named + " exact=" + exact.blueId() + " body=" + exact.json()
                    + " sourceCanonical=" + preparer.values().yaml(definition).json());
        }
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(ExactNodeProvider.of(exact)).build()) {
            String reference = "{blueId: " + exact.blueId() + "}";
            String step = INLINE_STEP + "  definition: " + (named ? "coffeeCode" : reference) + "\n";
            String source = initialization(reference + "\n", step);
            if (form.equals("declaration")) {
                CoordinationException failure = assertThrows(CoordinationException.class, () -> admit(blue, source),
                        "BEX plain-name containers cannot reinterpret an explicit type declaration as absence");
                assertTrue(failure.getMessage().contains(field), failure.getMessage());
            } else {
                assertCoffeeEvent(blue, admit(blue, source).snapshot().publicEvents());
            }
        }
    }

    private static Stream<Arguments> explicitDefinitionMapForms() {
        return Stream.of("constants", "functions").flatMap(field ->
                Stream.of("absent", "empty", "declaration").flatMap(form -> Stream.of(
                        Arguments.of(field, form, true), Arguments.of(field, form, false))));
    }

    @ParameterizedTest
    @MethodSource("definitionContributionPaths")
    void exactDefinitionMapMeaningSurvivesInheritedAndReferencedContainers(
            String field, String form, String location) {
        ExactBlueValue definition;
        ExactBlueValue library;
        ExactBlueValue base;
        try (BlueCoordination preparer = BlueCoordination.inMemory()) {
            definition = preparer.values().providerContentYaml("type: Coordination/Compute Definition\n"
                    + field + (form.equals("empty") ? ": {}\n" : ": {type: Dictionary}\n"));
            library = preparer.values().providerContentYaml("coffeeCode: {blueId: " + definition.blueId() + "}\n");
            base = preparer.values().providerContentYaml("library: {blueId: " + library.blueId() + "}\n");
        }
        List<ExactBlueValue> retained = List.of(definition, library, base);
        try (BlueCoordination blue = BlueCoordination.builder().exactNodeProvider(requested -> retained.stream()
                .filter(value -> value.blueId().equals(requested)).map(ExactBlueValue::json).findFirst()).build()) {
            String topology = switch (location) {
                case "referenced-parent" -> "library: {blueId: " + library.blueId() + "}\n";
                case "inherited" -> "type: {blueId: " + base.blueId() + "}\n";
                case "partial-overlay" -> "type: {blueId: " + base.blueId()
                        + "}\nlibrary:\n  local: separate overlay\n";
                default -> throw new IllegalArgumentException(location);
            };
            String source = topology + initialization(INLINE_STEP + "  definition: /library/coffeeCode\n");
            if (form.equals("declaration")) {
                CoordinationException failure = assertThrows(CoordinationException.class, () -> admit(blue, source));
                assertTrue(failure.getMessage().contains(field), failure.getMessage());
            } else {
                assertCoffeeEvent(blue, admit(blue, source).snapshot().publicEvents());
            }
        }
    }

    private static Stream<Arguments> definitionContributionPaths() {
        return Stream.of("constants", "functions").flatMap(field -> Stream.of("empty", "declaration")
                .flatMap(form -> Stream.of("referenced-parent", "inherited", "partial-overlay")
                        .map(location -> Arguments.of(field, form, location))));
    }

    private static Stream<Arguments> invalidStaticTypes() {
        return Stream.of("type", "nestedType", "itemType", "valueType").flatMap(field -> Stream.of(
                Arguments.of(field, "name: Invalid Coffee Event\npayload:\n  $add: [1, 2]\n"),
                Arguments.of(field, "$add: [1, 2]\n")));
    }

    private static String eventWithStaticType(String field, String blueId) {
        String reference = ": {blueId: " + blueId + "}\n";
        String staticField = switch (field) {
            case "nestedType" -> "type" + reference;
            case "itemType" -> "type: List\nitemType" + reference;
            case "keyType", "valueType" -> "type: Dictionary\n" + field + reference;
            default -> "type" + reference;
        };
        return "name: Coffee prepared\n" + (field.equals("type")
                ? staticField : "payload/~event:\n" + staticField.indent(2));
    }

    private static String definitionWithEvent(String event) {
        return """
                type: Coordination/Compute Definition
                functions:
                  prepareCoffee:
                    do:
                      - $appendEvent:
                """ + event.indent(10);
    }

    private static String namedStep(
            BlueCoordination blue, DefinitionSelector selector) {
        String definition = switch (selector) {
            case NAMED -> "coffeeCode";
            case ABSOLUTE_POINTER -> "'  /contracts/coffeeCode  '";
            case EXACT_REFERENCE -> "{blueId: " + blue.values().yaml(COFFEE_CODE).blueId() + "}";
            case INLINE_DEFINITION -> "\n" + COFFEE_CODE
                    .replace("type: Coordination/Compute Definition\n", "").indent(4).stripTrailing();
        };
        return """
                - type: Coordination/Compute
                  definition: %s
                  entry: prepareCoffee
                """.formatted(definition);
    }

    private static String initialization(String step) {
        return initialization(COFFEE_CODE, step);
    }

    private static String initialization(String definition, String step) {
        return """
                name: Coffee preparation
                contracts:
                  lifecycle:
                    type: Lifecycle Event Channel
                  coffeeCode:
                %s  initialize:
                    type: Coordination/Sequential Workflow
                    channel: lifecycle
                    event:
                      type: Document Processing Initiated
                    steps:
                %s""".formatted(definition.indent(4), step.indent(6));
    }

    private static String operation(String step) {
        return """
                name: Coffee preparation
                contracts:
                  providerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: coffee-shop
                    actor:
                      type: MyOS/Principal Actor
                      accountId: myos-mini:principal:coffee-shop
                  coffeeCode:
                %s  prepareCoffee:
                    type: Coordination/Sequential Workflow Operation
                    channel: providerChannel
                    request: {}
                    steps:
                %s""".formatted(COFFEE_CODE.indent(4), step.indent(6));
    }

    private static void assertCoffeeEvent(
            BlueCoordination blue, List<PublicEvent> events) {
        ExactBlueValue expected = blue.values().yaml("name: Coffee prepared");
        assertEquals(List.of(expected), events.stream()
                .map(PublicEvent::exact).toList());
    }

    private enum DefinitionSelector {
        NAMED,
        ABSOLUTE_POINTER,
        EXACT_REFERENCE,
        INLINE_DEFINITION
    }
}
