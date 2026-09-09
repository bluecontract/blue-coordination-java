package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.api.ContractsExecutionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RootedExternalOrderTest {
    @Test void anIndependentRootCanAdmitOlderHistoryWithoutRewindingTheProcessedRoot() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", java.util.Map.of());
            var first = fixture.append(source, "rcp2/source", "tick", 200, "{}");
            assertEquals(EntryDisposition.APPLIED, blue.processing().drain().entry(first).disposition());
            var history = fixture.history(source);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            var other = fixture.startYaml(resource("source.yaml").replace("rcp2/source", "rcp2/independent"), "rcp2/independent");
            var older = fixture.append(other, "rcp2/independent", "tick", 100, "{}");
            var result = blue.processing().drain();
            assertTrue(result.quiescent());
            assertEquals(EntryDisposition.APPLIED, result.entry(older).disposition());
            assertEquals(1L, other.snapshot().longAt("/counter"));
            assertEquals(history, fixture.history(source));
            assertTrue(blue.processing().drain().entries().isEmpty());
        }
    }

    @Test void aTimelineAlreadyRequiredByAProcessedRootStillRejectsAnOlderAppend() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", java.util.Map.of());
            var other = fixture.startYaml(resource("source.yaml").replace("rcp2/source", "rcp2/other"), "rcp2/other");
            var parent = fixture.startYaml(resource("parent.yaml").replace("    - /child", "    - /child\n    - /other")
                    + "\nchild:\n  blueId: " + source.snapshot().blueId() + "\nother:\n  blueId: " + other.snapshot().blueId(), "rcp2/parent");
            fixture.append(source, "rcp2/source", "tick", 200, "{}");
            assertTrue(blue.processing().drain().quiescent());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            var before = List.of(fixture.history(source), fixture.history(parent), fixture.history(other));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertThrows(RuntimeException.class, () -> fixture.append(other, "rcp2/other", "tick", 100, "{}"));
            assertThrows(RuntimeException.class, () -> fixture.append(other, "rcp2/other", "tick", 200, "{}"));
            assertEquals(before, List.of(fixture.history(source), fixture.history(parent), fixture.history(other)));
            var later = fixture.append(other, "rcp2/other", "tick", 300, "{}");
            assertEquals(EntryDisposition.APPLIED, blue.processing().drain().entry(later).disposition());
            assertEquals(1L, other.snapshot().longAt("/counter"));
            assertEquals(1L, parent.snapshot().longAt("/seen"));
        }
    }

    @Test void aGasTerminalRetainsRequiredProviderFrontiersWithoutPublishingState() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var source = fixture.start("source.yaml", "rcp2/source", java.util.Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", java.util.Map.of("child", source.snapshot().blueId()));
            var histories = List.of(fixture.history(source), fixture.history(parent));
            var entry = fixture.append(source, "rcp2/source", "tick", 200, "{}");
            var result = blue.advanced().process(parent, entry,
                    ContractsExecutionPolicy.exactSharedGas(1, "provider-frontier-negative"));
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, result.entry(entry).disposition());
            assertEquals(histories, List.of(fixture.history(source), fixture.history(parent)));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertThrows(RuntimeException.class, () -> fixture.append(parent, "rcp2/parent", "touch", 100, "{}"));
            assertThrows(RuntimeException.class, () -> fixture.append(source, "rcp2/source", "tick", 200, "{}"));
            assertEquals(histories, List.of(fixture.history(source), fixture.history(parent)));
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(source, entry).entry(entry).disposition());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(histories.get(1), fixture.history(parent));
        }
    }

    private static String resource(String name) throws Exception {
        try (var in = RootedExternalOrderTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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
