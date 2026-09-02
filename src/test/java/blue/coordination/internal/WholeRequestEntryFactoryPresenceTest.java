package blue.coordination.internal;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact request-presence coverage at the retained Timeline Entry boundary. */
final class WholeRequestEntryFactoryPresenceTest {
    private static final String EMPTY_OBJECT_BLUE_ID =
            "5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK";

    @Test
    void absentAndExactEmptyRequestsRemainDifferentExactEntries() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        Timeline timeline = new Timeline("request-presence", "alice");

        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                    runtime, objects, metrics);

            // when
            TimelineEntry absent = entries.create(
                    timeline,
                    null,
                    Operation.withoutRequest("touch", "ownerChannel"),
                    1_800_000_000_000_000L,
                    1L,
                    1L);
            TimelineEntry empty = entries.create(
                    timeline,
                    null,
                    Operation.yaml("touch", "ownerChannel", "{}"),
                    1_800_000_000_000_000L,
                    1L,
                    1L);

            // then
            assertTrue(absent.exactRequest().isEmpty());
            assertNull(NodePathEditor.getOrNull(
                    absent.exactEvent().copyNode(), "/message/request"));
            assertTrue(empty.exactRequest().isPresent());
            assertEquals(EMPTY_OBJECT_BLUE_ID,
                    empty.exactRequest().orElseThrow().blueId());
            assertEquals(DirectBlueIdCalculator.calculateBlueId(new Node()),
                    empty.exactRequest().orElseThrow().blueId());
            assertEquals(EMPTY_OBJECT_BLUE_ID,
                    NodePathEditor.getOrNull(
                            empty.exactEvent().copyNode(),
                            "/message/request").getBlueId());
            assertNotEquals(absent.blueId(), empty.blueId());
            assertNotEquals(absent.sourceOrderKey(), empty.sourceOrderKey());
            assertDoesNotThrow(() -> blue.coordination.processor
                    .TimelineProviderSupport.validateExactEnvelope(
                            absent.exactEvent().copyNode()));
            assertDoesNotThrow(() -> blue.coordination.processor
                    .TimelineProviderSupport.validateExactEnvelope(
                            empty.exactEvent().copyNode()));
            assertEquals(1L, metrics.counter("append.absentRequests"));
            assertFalse(absent.exactEvent().sameExactValue(
                    empty.exactEvent()));
        }
    }

    @Test
    void exactEmptyMandateConstraintIsPresentAndFailsClosed() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        Timeline timeline = new Timeline("mandate-presence", "alice");

        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                    runtime, objects, metrics);
            TimelineEntry entry = entries.create(
                    timeline,
                    null,
                    Operation.withoutRequest("touch", "ownerChannel"),
                    1_800_000_000_000_000L,
                    1L,
                    1L);
            Node constrained = entry.exactEvent().copyNode()
                    .properties("onBehalfOf", new Node());

            // when
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> blue.coordination.processor
                            .TimelineProviderSupport.validateExactEnvelope(
                                    constrained));

            // then
            assertTrue(failure.getMessage().contains("Mandate resolver"));
        }
    }
}
