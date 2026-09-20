package blue.coordination.sdk;

import blue.coordination.api.Operation;
import blue.coordination.internal.CoordinationTestControl;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

final class GeneralTimelineRoutingTest {
    @ParameterizedTest @ValueSource(ints = {1, 10, 100})
    void selectionReadsOnlyRelevantSubscriptions(int unrelated) {
        // given
        try (var fixture = new RootedSdkFixture()) {
            String operation = """
                      touch:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        steps: []
                    """;
            var root = fixture.startYaml(GeneralTimelineScenario.document("general/relevant") + operation, "general/relevant");
            for (int i = 0; i < unrelated; i++) {
                String timeline = "general/unrelated/" + i;
                fixture.startYaml(GeneralTimelineScenario.document(timeline) + operation, timeline);
            }
            var engine = (blue.coordination.internal.DefaultCoordinationEngine) fixture.blue.advanced().rawEngine();
            var control = CoordinationTestControl.attach(engine);
            var accepted = fixture.blue.events().from(fixture.timelines.get("general/relevant"))
                    .exact(GeneralTimelineScenario.event(fixture.blue, "general/relevant", 20, 7)).submit();
            var general = engine.auditTimelineEntry(accepted.blueId()).orElseThrow();
            var before = control.metricsSnapshot().counters();
            // when
            int receivers = engine.routeTargetCount(general);
            var after = control.metricsSnapshot().counters();
            var original = engine.append(engine.registerTimeline("general/relevant", "alice"), Operation.withoutRequest("touch", "owner"));
            int operationReceivers = engine.routeTargetCount(original);
            // then
            assertEquals(1, receivers); assertEquals(1, operationReceivers);
            assertEquals(1, delta(before, after, "routing.generalRowsInspected"));
            assertEquals(1, delta(before, after, "routing.generalRegisteredComparisons"));
            assertEquals(0, root.snapshot().longAt("/counter"), "Selection must not execute a receiver");
            var deltas = new TreeMap<String, Long>();
            after.forEach((key, value) -> { long change = value - before.getOrDefault(key, 0L); if (change != 0) deltas.put(key, change); });
            System.out.println("GENERAL_ROUTING_COUNTS unrelated=" + unrelated + " deltas=" + deltas);
        }
    }
    private static long delta(Map<String, Long> before, Map<String, Long> after, String key) {
        return after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }
}
