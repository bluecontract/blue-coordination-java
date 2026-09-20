package blue.coordination.sdk;

import blue.coordination.api.SourceHistoryPrerequisite;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The source cutoff uses both kinds of real accepted entry, in the same full order. */
final class GeneralTimelineSourceHistoryTest {
    @Test void earlierGeneralWorkMustFinishBeforeAttachmentWhileLaterOperationStaysPending() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            String sourceYaml = RootedSdkFixture.resource("source.yaml") + """
                  onCredit:
                    type: Coordination/Sequential Workflow
                    channel: owner
                    event:
                      message:
                        kind: CreditRecorded
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$event: /message/amount}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Tick
                          - $return: true
                """;
            var source = fixture.startYaml(sourceYaml, "rcp2/source");
            String originalSource = source.snapshot().blueId();
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            var earlier = fixture.blue.events().from(fixture.timelines.get("rcp2/source"))
                    .exact(GeneralTimelineScenario.event(fixture.blue, "rcp2/source", 20, 7)).submit();
            fixture.previousEntries.put("rcp2/source", earlier.blueId());
            var later = fixture.append(source, "rcp2/source", "setCounter", 40, "counterValue: 9");
            var attach = fixture.append(parent, "rcp2/parent", "attach", 30, "child:\n  blueId: " + originalSource);
            // when
            var blocked = fixture.blue.processing().processNext(parent).entry(attach);
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, blocked.disposition());
            var prerequisites = fixture.blue.advanced().sourceHistoryPrerequisites(parent);
            assertEquals(1, prerequisites.size());
            var required = prerequisites.get(0);
            assertEquals(SourceHistoryPrerequisite.Kind.LIVE, required.kind());
            assertEquals(earlier.blueId(), required.entryBlueId());
            assertNotEquals(later.blueId(), required.entryBlueId());
            var completed = fixture.blue.advanced().processSourceHistoryPrerequisite(required);
            assertEquals(1, completed.processing().orElseThrow().processedEntries().size());
            assertEquals(7, source.snapshot().longAt("/counter"));
            var retried = fixture.blue.processing().processNext(parent).entry(attach);
            assertEquals(EntryDisposition.APPLIED, retried.disposition(), retried.diagnostic().toString());
            assertEquals(1, fixture.blue.processing().processNext(parent).managedEpochApplications().size());
            assertEquals(7L, ((Number) fixture.selected(parent, "/child").scalarAt("/counter")).longValue());
            assertEquals(7, source.snapshot().longAt("/counter"), "The timestamp-40 operation is outside the attachment requirement");
            assertEquals(2, fixture.history(source).size());
            assertTrue(fixture.blue.advanced().auditTimelineEntry(earlier.blueId()).orElseThrow().operationDetails().isEmpty());
            assertTrue(fixture.blue.advanced().auditTimelineEntry(later.blueId()).orElseThrow().operationDetails().isPresent());
        }
    }
}
