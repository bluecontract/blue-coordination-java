package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A managed child's authored contracts invalidate only that child's plan. */
final class NestedOwnedScopePlanInvalidationIntegrationTest {
    private static final long T0 = 1_738_000_000_000_000L;
    private static final String ROOT = "nested-owned-surface-root";
    private static final String CHILD = "nested-owned-surface-child";
    private static final String DYNAMIC_TIMELINE =
            "examples/nested-owned-surface/dynamic";

    @Test
    void nestedChannelAdditionDoesNotInvalidateItsContainingRootPlan()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            Timeline owner = engine.timeline(
                    "examples/nested-owned-surface/owner", "nested-owner");
            Timeline dynamic = engine.timeline(
                    DYNAMIC_TIMELINE, "dynamic-owner");
            engine.start(
                    ROOT,
                    resource("examples/clean/"
                            + "nested-owned-dynamic-surface.yaml"));

            ExactValue channel = engine.registerType("""
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: examples/nested-owned-surface/dynamic
                    actor:
                      type: MyOS/Principal Actor
                      accountId: dynamic-owner
                    """);
            ExactValue handler = engine.registerType("""
                    type: Coordination/Sequential Workflow Operation
                    channel: dynamicChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /total
                              val: {$add: [$document: /total, $binding: event/message/request/amount]}
                          - $return: true
                    """);
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            TimelineEntry activation = engine.appendAt(
                    owner, activate(channel, handler), T0 + 100L);
            TimelineEntry dynamicEntry = engine.appendAt(
                    dynamic,
                    Operation.yaml(
                            "applyDynamic", "dynamicChannel", "amount: 2"),
                    T0 + 200L);

            // when
            engine.dispatch(dynamicEntry);

            // then
            assertEquals(2L, integer(engine, CHILD, "/total"));
            assertEquals(2L, integer(engine, ROOT, "/child/total"));
            assertEquals(
                    List.of(activation.blueId(), dynamicEntry.blueId()),
                    engine.history(CHILD).stream()
                            .filter(revision -> revision.kind()
                                    == DocumentRevision.Kind.TIMELINE_ENTRY)
                            .map(revision -> revision.sourceEntry()
                                    .orElseThrow().blueId())
                            .toList());
            assertTrue(engine.effectiveTimelineIds(ROOT)
                    .contains(DYNAMIC_TIMELINE));
            assertEquals(1L, delta(before, engine.metricsSnapshot()).counter(
                    "layout.plansRecompiledAfterContractChange"),
                    "only the managed child's own plan may refresh");
        }
    }

    private static Operation activate(
            ExactValue channel,
            ExactValue handler) {
        Map<String, Node> fields = new LinkedHashMap<>();
        fields.put("dynamicChannel", channel.referenceNode());
        fields.put("dynamicHandler", handler.referenceNode());
        return Operation.exact(
                "activateDynamic",
                "ownerChannel",
                ExactValue.verified(new Node().properties(fields)));
    }
}
