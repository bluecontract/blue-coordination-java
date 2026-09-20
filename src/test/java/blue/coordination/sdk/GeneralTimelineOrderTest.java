package blue.coordination.sdk;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GeneralTimelineOrderTest {
    @Test void mixedEntriesAtOneTimestampUseExactTimelineOrderAndExclusiveBounds() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var names = List.of("general/order-a", "general/order-b", "general/order-c", "general/order-d");
            var ids = new LinkedHashMap<String, String>();
            for (String name : names) ids.put(name, fixture.blue.values().yaml(
                    "type: MyOS/MyOS Timeline\ntimelineId: " + name).blueId());
            var ordered = names.stream().sorted(Comparator.comparing(ids::get)).toList();
            var entries = new ArrayList<EntryHandle>();
            var documents = new ArrayList<DocumentHandle>();
            for (int n = ordered.size() - 1; n >= 0; n--) {
                String timeline = ordered.get(n);
                String authored = n % 2 == 0 ? GeneralTimelineScenario.document(timeline)
                        : RootedSdkFixture.resource("source.yaml").replace("rcp2/source", timeline);
                var document = fixture.startYaml(authored, timeline);
                documents.add(document);
                entries.add(n % 2 == 0 ? fixture.blue.events().from(fixture.timelines.get(timeline))
                        .exact(GeneralTimelineScenario.event(fixture.blue, timeline, 100, 1)).submit()
                        : fixture.append(document, timeline, "tick", 100, "{}"));
            }
            var expected = new ArrayList<>(entries); Collections.reverse(expected);
            var lowerRoot = documents.get(3);
            var upperRoot = documents.get(0);
            // when
            var earlier = fixture.control.nextLiveInputBefore(lowerRoot.id(), entries.get(0).blueId());
            var equal = fixture.control.nextLiveInputBefore(lowerRoot.id(), entries.get(3).blueId());
            var later = fixture.control.nextLiveInputBefore(upperRoot.id(), entries.get(3).blueId());
            // then
            assertEquals(entries.get(3).blueId(), earlier.orElseThrow());
            assertTrue(equal.isEmpty()); assertTrue(later.isEmpty());
            assertEquals(0, lowerRoot.snapshot().longAt("/counter"));
            var result = fixture.blue.processing().drain();
            assertTrue(result.quiescent());
            assertEquals(expected, result.entries().stream().map(EntryResult::entry).toList());
            var histories = documents.stream().map(fixture::history).toList();
            for (var document : documents) assertEquals(1, document.snapshot().longAt("/counter"));
            blue.coordination.internal.CoordinationTestControl.attach(fixture.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(histories, documents.stream().map(fixture::history).toList());
            for (var document : documents) assertTrue(fixture.control.nextLiveInput(document.id()).isEmpty());
        }
    }
}
