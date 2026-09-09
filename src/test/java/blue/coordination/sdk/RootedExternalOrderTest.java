package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RootedExternalOrderTest {
    @Test void equalTimestampsUseExactTimelineBlueIdsRatherThanNamesOrAppendOrder() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            List<String> names = List.of("rcp2/order-a", "rcp2/order-b", "rcp2/order-c", "rcp2/order-d");
            var ids = new java.util.LinkedHashMap<String, String>();
            for (String name : names) ids.put(name, blue.values().yaml(
                    "type: MyOS/MyOS Timeline\ntimelineId: " + name).blueId());
            List<String> ordered = names.stream().sorted(Comparator.comparing(ids::get)).toList();
            assertNotEquals(names, ordered, "The counterexample must distinguish names from exact identities");
            String source;
            try (var in = getClass().getResourceAsStream("/rooted/source.yaml")) {
                source = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
            List<EntryHandle> reversedEntries = new ArrayList<>();
            List<DocumentHandle> reversedDocuments = new ArrayList<>();
            for (int n = ordered.size() - 1; n >= 0; n--) {
                String name = ordered.get(n);
                var doc = fixture.startYaml(source.replace("rcp2/source", name), name);
                reversedDocuments.add(doc);
                reversedEntries.add(fixture.append(doc, name, "tick", 100, "{}"));
            }
            var result = blue.processing().drain();
            assertTrue(result.quiescent());
            List<EntryHandle> expected = new ArrayList<>(reversedEntries);
            java.util.Collections.reverse(expected);
            assertEquals(expected, result.entries().stream().map(EntryResult::entry).toList());
            for (var doc : reversedDocuments) {
                assertEquals(1L, doc.snapshot().longAt("/counter"));
                var receipt = blue.advanced().auditManagedEpoch(doc.id(), 1).orElseThrow();
                assertTrue(ids.containsValue(receipt.sourceOrder().orElseThrow().components().get(1)),
                        "Retained receipt ordering must bind the same exact Timeline identity");
            }
        }
    }
}
