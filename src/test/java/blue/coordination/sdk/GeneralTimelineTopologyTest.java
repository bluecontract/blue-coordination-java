package blue.coordination.sdk;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A general attachment changes the selected forward view between complete processing stages. */
final class GeneralTimelineTopologyTest {
    @Test void generalAttachmentInvalidatesOldSelectionAndExposesEarlierSourceWorkAfterColdReopen() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var z = f.startYaml(GeneralTimelineScenario.document("general/z"), "general/z");
            String originalZ = f.retain(z);
            String parentYaml = RootedSdkFixture.resource("parent.yaml") + """
                      onAttach:
                        type: Coordination/Sequential Workflow
                        channel: owner
                        event: {message: {kind: AttachSource}}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: add
                                  path: /child
                                  val: {$event: /message/child}
                              - $return: true
                    """;
            var a = f.startYaml(parentYaml, "rcp2/parent");
            var attach = f.blue.events().from(f.timelines.get("rcp2/parent")).exact(f.blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/parent}
                    timestamp: 300
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message: {kind: AttachSource, child: {blueId: %s}}
                    """.formatted(originalZ))).submit();
            f.previousEntries.put("rcp2/parent", attach.blueId());
            var future = f.append(a, "rcp2/parent", "attach", 700, "child: {blueId: " + originalZ + "}");
            var sourceInput = f.blue.events().from(f.timelines.get("general/z"))
                    .exact(GeneralTimelineScenario.event(f.blue, "general/z", 400, 7)).submit();
            var oldSuffix = f.control.capture(a.id(), future.blueId(), null);
            var sourceHistory = f.history(z);
            // when
            var changed = f.blue.processing().processStage(a, attach);
            // then
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, changed.disposition());
            assertTrue(changed.selectionInvalidated());
            assertEquals(java.util.List.of(a.id()), changed.resultOwners());
            var committed = f.history(a);
            assertEquals(committed, f.history(a));
            blue.coordination.internal.CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            boolean processedEarlier = false;
            for (int i = 0; i < 4 && !processedEarlier; i++) {
                var stage = f.blue.processing().processNextStage(a);
                assertEquals(ProcessingStageResult.Disposition.COMPLETED, stage.disposition());
                assertTrue(stage.evidence().entries().stream().noneMatch(e -> e.entry().blueId().equals(future.blueId())));
                processedEarlier = stage.evidence().entries().stream().anyMatch(e -> e.entry().blueId().equals(sourceInput.blueId()));
            }
            assertTrue(processedEarlier, "New forward source work at 400 must precede the formerly prefetched 700 input");
            assertEquals(7L, ((Number) f.selected(a, "/child").scalarAt("/counter")).longValue());
            assertNotEquals(oldSuffix.snapshot().closureIdentity(), f.control.capture(a.id(), future.blueId(), null).snapshot().closureIdentity(),
                    "The former prefetched root snapshot cannot describe the new forward view");
            assertEquals(sourceHistory, f.history(z));
            assertEquals(originalZ, z.snapshot().blueId(), "Root-local processing does not publish independent Z");
        }
    }
}
