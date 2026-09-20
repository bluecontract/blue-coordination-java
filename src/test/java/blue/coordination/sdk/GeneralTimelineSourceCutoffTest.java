package blue.coordination.sdk;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

final class GeneralTimelineSourceCutoffTest {
    @ParameterizedTest @CsvSource({"20,true", "30,true", "30,false", "40,false"})
    void earlierAndFutureGeneralWorkUseFullExclusiveSourceCutoff(long timestamp, boolean before) throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            String parentTimeline = "rcp2/parent";
            String parentExact = f.blue.values().yaml("type: MyOS/MyOS Timeline\ntimelineId: " + parentTimeline).blueId();
            String sourceTimeline = null;
            for (int n = 0; n < 100; n++) {
                String candidate = "general/cutoff-" + n;
                String exact = f.blue.values().yaml("type: MyOS/MyOS Timeline\ntimelineId: " + candidate).blueId();
                if ((exact.compareTo(parentExact) < 0) == before) { sourceTimeline = candidate; break; }
            }
            assertNotNull(sourceTimeline, "Fixture must exercise both equal-timestamp Timeline-order sides");
            var source = f.startYaml(GeneralTimelineScenario.document(sourceTimeline), sourceTimeline);
            var parent = f.start("parent.yaml", parentTimeline, java.util.Map.of());
            String sourceInitial = f.retain(source);
            var general = f.blue.events().from(f.timelines.get(sourceTimeline))
                    .exact(GeneralTimelineScenario.event(f.blue, sourceTimeline, timestamp, 7)).submit();
            var attach = f.append(parent, parentTimeline, "attach", 30, "child: {blueId: " + sourceInitial + "}");
            var raw = (blue.coordination.internal.DefaultCoordinationEngine) f.blue.advanced().rawEngine();
            assertEquals(before, raw.auditTimelineEntry(general.blueId()).orElseThrow().sourceOrderKey()
                    .compareTo(raw.auditTimelineEntry(attach.blueId()).orElseThrow().sourceOrderKey()) < 0);
            // when
            var result = f.blue.processing().processNextStage(parent);
            // then
            assertEquals(before ? EntryDisposition.NEEDS_RESOURCES : EntryDisposition.APPLIED, result.entry(attach).disposition());
            if (before) {
                var needed = f.blue.advanced().sourceHistoryPrerequisites(parent);
                assertEquals(1, needed.size()); assertEquals(general.blueId(), needed.get(0).entryBlueId());
                assertEquals(sourceInitial, source.snapshot().blueId());
            } else {
                assertTrue(f.blue.advanced().sourceHistoryPrerequisites(parent).isEmpty());
                assertEquals(sourceInitial, parent.snapshot().valueAt("/child").blueId());
                assertEquals(sourceInitial, source.snapshot().blueId());
                assertEquals(general.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            }
        }
    }
}
