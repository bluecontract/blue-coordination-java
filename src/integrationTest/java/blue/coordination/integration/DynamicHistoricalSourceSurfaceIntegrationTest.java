package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Historical contract changes must immediately redefine the source surface. */
final class DynamicHistoricalSourceSurfaceIntegrationTest {
    private static final long T0 = 1_737_000_000_000_000L;
    private static final String DOCUMENT = "dynamic-source-surface";
    private static final String OWNER_TIMELINE =
            "examples/dynamic-source-surface/owner";
    private static final String DYNAMIC_TIMELINE =
            "examples/dynamic-source-surface/dynamic";

    @Test
    void historicalAddAndRemovalRefreshTheSurfaceBeforeNextSelection()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            Timeline owner = engine.timeline(OWNER_TIMELINE, "owner");
            Timeline dynamic = engine.timeline(
                    DYNAMIC_TIMELINE, "dynamic-owner");

            ExactValue dynamicChannel = engine.registerType("""
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: examples/dynamic-source-surface/dynamic
                    actor:
                      type: MyOS/Principal Actor
                      accountId: dynamic-owner
                    """);
            ExactValue dynamicHandler = engine.registerType("""
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
            TimelineEntry beforeActivation = engine.appendAt(
                    dynamic, applyDynamic(100L), T0 + 50L);
            TimelineEntry activation = engine.appendAt(
                    owner,
                    activateDynamic(dynamicChannel, dynamicHandler),
                    T0 + 100L);
            TimelineEntry active = engine.appendAt(
                    dynamic, applyDynamic(2L), T0 + 200L);
            TimelineEntry retirement = engine.appendAt(
                    owner, retireDynamic(), T0 + 300L);
            TimelineEntry afterRetirement = engine.appendAt(
                    dynamic, applyDynamic(1_000L), T0 + 400L);

            // when
            engine.start(
                    DOCUMENT,
                    resource("examples/clean/dynamic-source-surface.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);
            long totalAfterHistoricalStart = integer(
                    engine, DOCUMENT, "/total");
            Object activatedAfterHistoricalStart =
                    engine.value(DOCUMENT, "/activated").getValue();
            Object retiredAfterHistoricalStart =
                    engine.value(DOCUMENT, "/retired").getValue();
            List<String> processedAfterHistoricalStart =
                    processedTimelineEntries(engine);
            Set<String> timelinesAfterHistoricalStart =
                    engine.effectiveTimelineIds(DOCUMENT);
            int beforeActivationTargets =
                    engine.routeTargetCount(beforeActivation);
            int activeTargets = engine.routeTargetCount(active);
            int afterRetirementTargets =
                    engine.routeTargetCount(afterRetirement);
            int activationTargets = engine.routeTargetCount(activation);
            int retirementTargets = engine.routeTargetCount(retirement);
            int historySize = engine.history(DOCUMENT).size();
            TimelineEntry liveAfterRetirement = engine.appendAt(
                    dynamic, applyDynamic(10_000L), T0 + 500L);
            int liveAfterRetirementTargets =
                    engine.routeTargetCount(liveAfterRetirement);
            engine.dispatch(liveAfterRetirement);

            // then
            assertEquals(2L, totalAfterHistoricalStart,
                    "only the entry inside the dynamic active interval runs");
            assertEquals(Boolean.TRUE, activatedAfterHistoricalStart);
            assertEquals(Boolean.TRUE, retiredAfterHistoricalStart);
            assertEquals(
                    List.of(
                            activation.blueId(),
                            active.blueId(),
                            retirement.blueId()),
                    processedAfterHistoricalStart,
                    "the feeder must reselect after both surface changes");
            assertEquals(Set.of(OWNER_TIMELINE),
                    timelinesAfterHistoricalStart,
                    "the retired dynamic Timeline must leave the surface");
            assertEquals(0, beforeActivationTargets);
            assertEquals(0, activeTargets);
            assertEquals(0, afterRetirementTargets);
            assertEquals(1, activationTargets);
            assertEquals(1, retirementTargets);
            assertEquals(0, liveAfterRetirementTargets);
            assertEquals(2L, integer(engine, DOCUMENT, "/total"));
            assertEquals(historySize, engine.history(DOCUMENT).size(),
                    "removed handlers cannot receive later live entries");
            EngineMetrics.MetricsSnapshot metrics = engine.metricsSnapshot();
            assertEquals(2L, metrics.counters().get(
                    "process.incrementalSubscriptionReprojections"));
            assertEquals(0L, metrics.counters().getOrDefault(
                    "process.postProcessFullProjections", 0L));
        }
    }

    private static Operation activateDynamic(
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

    private static Operation retireDynamic() {
        return Operation.yaml(
                "retireDynamic", "ownerChannel", "{}");
    }

    private static Operation applyDynamic(long amount) {
        return Operation.yaml(
                "applyDynamic", "dynamicChannel", "amount: " + amount);
    }

    private static List<String> processedTimelineEntries(TestEngine engine) {
        return engine.history(DOCUMENT).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.TIMELINE_ENTRY)
                .map(revision -> revision.sourceEntry()
                        .orElseThrow().blueId())
                .toList();
    }
}
