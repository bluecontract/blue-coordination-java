package blue.coordination.internal;

import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.output.BexAdmittedValue;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
            assertTrue(absent.request().isEmpty());
            assertNull(NodePathEditor.getOrNull(
                    absent.exactEvent().copyNode(), "/message/request"));
            assertTrue(empty.request().isPresent());
            assertEquals(EMPTY_OBJECT_BLUE_ID,
                    empty.request().orElseThrow().blueId());
            assertEquals(DirectBlueIdCalculator.calculateBlueId(blue.language.model.Nodes.emptyObject()),
                    empty.request().orElseThrow().blueId());
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
    void feederAcceptsAbsentMandateAndRejectsPresentEmptyWithoutResolver() {
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

            assertDoesNotThrow(() -> blue.coordination.processor
                    .TimelineProviderSupport.validateExactEnvelope(
                            entry.exactEvent().copyNode()),
                    "an omitted onBehalfOf field requires no authority "
                            + "resolution");
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

    @Test
    void exactEmptyRequestBodyHasInlineAndVerifiedReferenceParity() {
        // given
        EngineMetrics sourceMetrics = new EngineMetrics();
        WholeObjectStore sourceObjects = new WholeObjectStore(sourceMetrics);
        Timeline timeline = new Timeline("request-reference", "alice");

        try (BlueRuntime sourceRuntime = BlueRuntime.create(
                sourceObjects, sourceMetrics)) {
            WholeRequestEntryFactory sourceEntries =
                    new WholeRequestEntryFactory(
                            sourceRuntime, sourceObjects, sourceMetrics);
            TimelineEntry source = sourceEntries.create(
                    timeline,
                    null,
                    Operation.yaml("touch", "ownerChannel", "{}"),
                    1_800_000_000_000_000L,
                    1L,
                    1L);
            ExactValue exactRequest = source.request().orElseThrow();

            EngineMetrics targetMetrics = new EngineMetrics();
            WholeObjectStore targetObjects = new WholeObjectStore(
                    targetMetrics);
            NodeProvider provider = blueId -> exactRequest.blueId().equals(
                    blueId)
                    ? List.of(exactRequest.copyNode())
                    : List.of();
            try (BlueRuntime targetRuntime = BlueRuntime.create(
                    targetObjects, targetMetrics, provider)) {
                WholeRequestEntryFactory targetEntries =
                        new WholeRequestEntryFactory(
                                targetRuntime,
                                targetObjects,
                                targetMetrics);

                // when
                TimelineEntry referenced = targetEntries.createExact(
                        timeline, source.exactEvent(), 1L, 1L);
                ExactValue inline = targetEntries.retainExactRequest(
                        FrozenNode.fromNode(exactRequest.copyNode()));

                // then
                assertEquals(EMPTY_OBJECT_BLUE_ID,
                        referenced.request().orElseThrow().blueId());
                assertEquals(EMPTY_OBJECT_BLUE_ID,
                        inline.blueId());
                assertTrue(referenced.request().orElseThrow()
                        .sameExactValue(inline));
            }
        }
    }

    @Test
    void unavailableReferencedRequestRemainsIncomplete() {
        // given
        EngineMetrics sourceMetrics = new EngineMetrics();
        WholeObjectStore sourceObjects = new WholeObjectStore(sourceMetrics);
        Timeline timeline = new Timeline("request-unavailable", "alice");

        try (BlueRuntime sourceRuntime = BlueRuntime.create(
                sourceObjects, sourceMetrics)) {
            WholeRequestEntryFactory sourceEntries =
                    new WholeRequestEntryFactory(
                            sourceRuntime, sourceObjects, sourceMetrics);
            TimelineEntry source = sourceEntries.create(
                    timeline,
                    null,
                    Operation.yaml(
                            "touch", "ownerChannel", "kind: provider-only"),
                    1_800_000_000_000_000L,
                    1L,
                    1L);
            String requestBlueId = source.request()
                    .orElseThrow().blueId();
            NodeProvider unavailable = new NodeProvider() {
                @Override
                public List<Node> fetchByBlueId(String blueId) {
                    return List.of();
                }

                @Override
                public NodeProviderResult fetchResultByBlueId(String blueId) {
                    return requestBlueId.equals(blueId)
                            ? NodeProviderResult.unavailable(
                                    "request provider offline")
                            : NodeProviderResult.notFound();
                }
            };
            EngineMetrics targetMetrics = new EngineMetrics();
            WholeObjectStore targetObjects = new WholeObjectStore(
                    targetMetrics);
            try (BlueRuntime targetRuntime = BlueRuntime.create(
                    targetObjects, targetMetrics, unavailable)) {
                WholeRequestEntryFactory targetEntries =
                        new WholeRequestEntryFactory(
                                targetRuntime,
                                targetObjects,
                                targetMetrics);

                // when
                RuntimeException failure = assertThrows(
                        RuntimeException.class,
                        () -> targetEntries.createExact(
                                timeline, source.exactEvent(), 1L, 1L));

                // then
                ExecutionEvidenceUnavailableException incomplete =
                        org.junit.jupiter.api.Assertions.assertInstanceOf(
                                ExecutionEvidenceUnavailableException.class,
                                failure);
                assertEquals(List.of(requestBlueId),
                        incomplete.requiredExactBlueIds());
                assertTrue(incomplete.getMessage().contains(
                        "request provider offline"));
                assertFalse(targetObjects.contains(requestBlueId));
            }
        }
    }

    @Test
    void coordEmpty04And05KeepBexNullAndEmptyRequestDistinct() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        Timeline timeline = new Timeline("bex-request-presence", "alice");

        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                    runtime, objects, metrics);
            TimelineEntry template = entries.create(
                    timeline,
                    null,
                    Operation.withoutRequest("touch", "ownerChannel"),
                    1_800_000_000_000_000L,
                    1L,
                    1L);
            BexValue event = BexValues.transientFrozen(
                    template.exactEvent().frozen());
            BexOutputAdmission admission = new BexOutputAdmission(
                    new BexGasMeter(
                            BexGasSchedule.defaults(), 1_000_000L),
                    null);

            // when
            TimelineEntry absent = entries.createExact(
                    timeline,
                    exact(admission.admit(
                            BexValues.pointerSet(
                                    event,
                                    List.of("message", "request"),
                                    BexValues.nullValue(),
                                    "set"),
                            BexOutputKind.ROOT_RESULT)),
                    2L,
                    2L);
            TimelineEntry empty = entries.createExact(
                    timeline,
                    exact(admission.admit(
                            BexValues.pointerSet(
                                    event,
                                    List.of("message", "request"),
                                    BexValues.fromSimple(Map.of()),
                                    "set"),
                            BexOutputKind.ROOT_RESULT)),
                    3L,
                    3L);

            // then
            assertTrue(absent.request().isEmpty());
            assertNull(NodePathEditor.getOrNull(
                    absent.exactEvent().copyNode(), "/message/request"));
            assertEquals(template.blueId(), absent.blueId(),
                    "BEX request:null must converge with request omission");
            assertTrue(empty.request().isPresent());
            assertEquals(EMPTY_OBJECT_BLUE_ID,
                    empty.request().orElseThrow().blueId());
            assertNotEquals(absent.blueId(), empty.blueId());
            assertFalse(absent.exactEvent().sameExactValue(
                    empty.exactEvent()));
            assertDoesNotThrow(() -> blue.coordination.processor
                    .TimelineProviderSupport.validateExactEnvelope(
                            absent.exactEvent().copyNode()));
            assertDoesNotThrow(() -> blue.coordination.processor
                    .TimelineProviderSupport.validateExactEnvelope(
                            empty.exactEvent().copyNode()));
        }
    }

    private static ExactValue exact(BexAdmittedValue admitted) {
        return ExactValue.verified(
                admitted.nodeBlueId(), admitted.node());
    }
}
