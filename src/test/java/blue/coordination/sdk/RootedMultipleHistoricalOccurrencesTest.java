package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Production SDK sequences for the RCP-RUN-006 and RCP-RUN-008 obligations. */
final class RootedMultipleHistoricalOccurrencesTest {
    @Test void twoSavedStatesOfOneSourceKeepTheirDistinctSuccessorSuffixes() throws IOException {
        // given
        try (var f = new RootedSdkFixture()) {
            // when
            var a = f.start("historical-a.yaml", "rcp2/a", Map.of());
            String a5 = null, a8 = null;
            for (int n = 1; n <= 10; n++) {
                tick(f, a, "rcp2/a", 100 + n);
                if (n == 5) a5 = f.retain(a);
                if (n == 8) a8 = f.retain(a);
            }
            String a10 = a.snapshot().blueId();
            var sourceHistory = f.history(a);
            var parent = f.start("multi-history-parent.yaml", "rcp2/history-parent", Map.of());
            attach(f, parent, a5, a8);
            var left = new ArrayList<Long>();
            var right = new ArrayList<Long>();
            for (int step = 0; step < 7; step++) {
                var result = f.blue.processing().processNext(parent);
                // then
                assertEquals(1, result.managedEpochApplications().size(), result.managedEpochApplicationAttempts().toString());
                var work = result.managedEpochApplicationAttempts().get(0).work();
                assertEquals(a.id(), work.sourceDocumentId());
                assertTrue(List.of("/left", "/right").contains(work.targetPath()));
                (work.targetPath().equals("/left") ? left : right).add(work.sourceEpoch());
                assertEquals(sourceHistory, f.history(a));
                assertEquals(a10, a.snapshot().blueId());
                assertEquals(step == 6, result.quiescent());
            }
            assertEquals(List.of(6L, 7L, 8L, 9L, 10L), left);
            assertEquals(List.of(9L, 10L), right);
            for (int n = 0; n < 5; n++) assertEquals(n + 6L, parent.snapshot().longAt("/leftLog/" + n));
            for (int n = 0; n < 2; n++) assertEquals(n + 9L, parent.snapshot().longAt("/rightLog/" + n));
            assertEquals(5, new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(parent.snapshot().valueAt("/leftLog").json()).path("items").size());
            assertEquals(2, new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(parent.snapshot().valueAt("/rightLog").json()).path("items").size());
            assertEquals(a10, parent.snapshot().valueAt("/left").blueId());
            assertEquals(a10, parent.snapshot().valueAt("/right").blueId());
            verifyRestart(f, parent, List.of(a));
        }
    }

    @Test void retainedSourcesInterleaveBeforeTheNextDependentLiveInput() throws IOException {
        // given
        try (var f = new RootedSdkFixture()) {
            // when
            var a = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("Historical A", "Historical C").replace("rcp2/a", "rcp2/c"), "rcp2/c");
            String a0 = f.retain(a), c0 = f.retain(c);
            tick(f, a, "rcp2/a", 60); tick(f, a, "rcp2/a", 90);
            tick(f, c, "rcp2/c", 70); tick(f, c, "rcp2/c", 80);
            var sourceHistories = List.of(f.history(a), f.history(c));
            var parent = f.start("multi-history-parent.yaml", "rcp2/history-parent", Map.of());
            attach(f, parent, a0, c0);
            var mark = f.append(parent, "rcp2/history-parent", "mark", 300, "{}");
            var order = new ArrayList<String>();
            for (int step = 0; step < 4; step++) {
                var result = f.blue.processing().processNext(parent);
                // then
                assertEquals(1, result.managedEpochApplications().size(), result.managedEpochApplicationAttempts().toString());
                var work = result.managedEpochApplicationAttempts().get(0).work();
                var source = f.blue.advanced().auditManagedEpoch(work.sourceDocumentId(), work.sourceEpoch()).orElseThrow();
                order.add((work.sourceDocumentId().equals(a.id()) ? "A" : "C") + source.sourceOrder().orElseThrow().components().get(0));
                assertEquals(false, f.blue.advanced().auditDocument(parent.id()).current().copyNode().get("/marked"));
                assertEquals(sourceHistories, List.of(f.history(a), f.history(c)));
                assertFalse(result.quiescent(), "The parent still has its later mark input");
            }
            assertEquals(List.of("A60", "C70", "C80", "A90"), order);
            var later = f.blue.processing().processNext(parent);
            assertEquals(EntryDisposition.APPLIED, later.entry(mark).disposition());
            assertTrue(later.managedEpochApplications().isEmpty());
            assertEquals(true, f.blue.advanced().auditDocument(parent.id()).current().copyNode().get("/marked"));
            for (int n = 0; n < 4; n++) assertEquals(List.of("left", "right", "right", "left").get(n),
                    parent.snapshot().textAt("/order/" + n));
            verifyRestart(f, parent, List.of(a, c));
        }
    }

    private static void tick(RootedSdkFixture f, DocumentHandle source, String timeline, long time) {
        var entry = f.append(source, timeline, "tick", time, "{}");
        assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, entry).entry(entry).disposition());
        f.retain(source);
    }

    private static void attach(RootedSdkFixture f, DocumentHandle parent, String left, String right) {
        assertNotNull(left); assertNotNull(right);
        var entry = f.append(parent, "rcp2/history-parent", "attach", 200,
                "left:\n  blueId: " + left + "\nright:\n  blueId: " + right);
        assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(parent, entry).entry(entry).disposition());
    }

    private static void verifyRestart(RootedSdkFixture f, DocumentHandle parent, List<DocumentHandle> sources) {
        var all = new ArrayList<>(sources); all.add(parent);
        var heads = all.stream().map(d -> d.snapshot().blueId()).toList();
        var histories = all.stream().map(f::history).toList();
        CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
        assertEquals(heads, all.stream().map(d -> d.snapshot().blueId()).toList());
        assertEquals(histories, all.stream().map(f::history).toList());
        assertTrue(f.blue.processing().processNext(parent).quiescent());
        assertEquals(histories, all.stream().map(f::history).toList());
    }
}
