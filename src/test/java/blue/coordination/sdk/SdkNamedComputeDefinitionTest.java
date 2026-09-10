package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

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
                      name: Kawa przygotowana
            """;
    private static final String INLINE_STEP = """
            - type: Coordination/Compute
              do:
                - $appendEvent:
                    name: Kawa przygotowana
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
    void shouldValidateAStaticTypeReachedThroughTheNamedDefinition() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String eventType = blue.values().yaml("name: Coffee Event").blueId();
            String event = "type: {blueId: " + eventType + "}\n"
                    + "name: Kawa przygotowana\n";
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
    @ValueSource(strings = {
            "name: Invalid Coffee Event\npayload:\n  $add: [1, 2]\n",
            "$add: [1, 2]\n"
    })
    void shouldRejectExpressionsHiddenInANamedDefinitionsStaticType(String type) {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            String eventType = blue.values().yaml(type).blueId();
            String event = "type: {blueId: " + eventType + "}\n"
                    + "name: Kawa przygotowana\n";
            String source = initialization(definitionWithEvent(event),
                    namedStep(blue, DefinitionSelector.NAMED));

            // when
            CoordinationException failure = assertThrows(
                    CoordinationException.class, () -> admit(blue, source));

            // then
            assertTrue(failure.getMessage().contains(
                    "BEX expressions inside Blue type fields"), failure.getMessage());
        }
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
        String definition = selector == DefinitionSelector.NAMED
                ? "coffeeCode"
                : "{blueId: " + blue.values().yaml(COFFEE_CODE).blueId() + "}";
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
                name: Przygotowanie kawy
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
                name: Przygotowanie kawy
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
        ExactBlueValue expected = blue.values().yaml("name: Kawa przygotowana");
        assertEquals(List.of(expected), events.stream()
                .map(PublicEvent::exact).toList());
    }

    private enum DefinitionSelector {
        NAMED,
        EXACT_REFERENCE
    }
}
