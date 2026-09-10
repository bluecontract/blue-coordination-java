package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** RUN019's exact saved inputs and timestamps, using the maintained public SDK. */
final class RootedDetachedPairReconnectTest {
    @Test
    void reconnectFromSavedAuthoredBeforeFutureEntries() throws Exception {
        // given
        boolean retained = false;
        long expectedAtReconnect = 3L;
        // when
        long afterFutureEntry = run(retained, expectedAtReconnect);
        // then
        assertEquals(expectedAtReconnect + 1L, afterFutureEntry);
    }
    @Test
    void reconnectFromSavedRetainedBeforeFutureEntries() throws Exception {
        // given
        boolean retained = true;
        long expectedAtReconnect = 2L;
        // when
        long afterFutureEntry = run(retained, expectedAtReconnect);
        // then
        assertEquals(expectedAtReconnect + 1L, afterFutureEntry);
    }

    @Test
    void reconnectUsesTheDetachedSourcesNewChildAtTheAttachmentBoundary() throws Exception {
        // given
        boolean laterTopology = false;
        // when
        boolean ready = reconnectWithNewChild(laterTopology);
        // then
        assertTrue(ready);
    }

    @Test
    void reconnectCannotImportTheDetachedSourcesLaterTopology() throws Exception {
        // given
        boolean laterTopology = true;
        // when
        boolean ready = reconnectWithNewChild(laterTopology);
        // then
        assertTrue(ready);
    }

    private static boolean reconnectWithNewChild(boolean laterTopology) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var a = f.startYaml(RootedSdkFixture.resource("graph-019-a.yaml"), "rcp/reconnect/A");
            var b = f.startYaml(RootedSdkFixture.resource("graph-019-b.yaml"), "rcp/reconnect/B");
            String originalB = b.id().value();
            String cYaml = RootedSdkFixture.resource("graph-019-a.yaml")
                    .replace("\"Tutorial graph node A\"", "\"Tutorial graph node C\"")
                    .replace("\"rooted-reconnect-019-A\"", "\"rooted-reconnect-019-C\"")
                    .replace("\"rcp/reconnect/A\"", "\"rcp/reconnect/C\"")
                    .replace("\"A\"", "\"C\"");
            var c = f.startYaml(cYaml, "rcp/reconnect/C");
            drain(f, f.append(a, "rcp/reconnect/A", "attach", 100, attach("b", originalB)));
            drain(f, f.append(a, "rcp/reconnect/A", "detach", 200, "edge: b"));
            drain(f, f.append(b, "rcp/reconnect/B", "attach", 300, attach("c", c.id().value())));
            String sourceHead = b.snapshot().blueId();
            EntryHandle future = null;
            if (laterTopology) {
                String dYaml = cYaml.replace("\"Tutorial graph node C\"", "\"Tutorial graph node D\"")
                        .replace("\"rooted-reconnect-019-C\"", "\"rooted-reconnect-019-D\"")
                        .replace("\"rcp/reconnect/C\"", "\"rcp/reconnect/D\"").replace("\"C\"", "\"D\"");
                var d = f.startYaml(dYaml, "rcp/reconnect/D");
                future = f.append(b, "rcp/reconnect/B", "attach", 500, attach("d", d.id().value()));
                drain(f, future);
            }
            var sourceHistory = f.history(b);
            String currentHead = b.snapshot().blueId();
            drain(f, f.append(a, "rcp/reconnect/A", "attach", 400, attach("b", originalB)));
            assertEquals(sourceHead, a.snapshot().valueAt("/peers/b").blueId());
            assertEquals(currentHead, b.snapshot().blueId());
            assertEquals(sourceHistory, f.history(b));
            assertTrue(f.blue.advanced().auditManagedDocumentReadiness(a.id()).orElseThrow().ready());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(sourceHead, a.snapshot().valueAt("/peers/b").blueId());
            assertEquals(sourceHistory, f.history(b));
            if (laterTopology) {
                drain(f, future);
                assertEquals(currentHead, a.snapshot().valueAt("/peers/b").blueId());
                assertEquals(sourceHistory, f.history(b));
            }
            assertTrue(f.blue.processing().processNext(a).quiescent());
            return f.blue.advanced().auditManagedDocumentReadiness(a.id()).orElseThrow().ready();
        }
    }

    @Test
    void detachedSourceTopologyCannotBlockTheFormerParentsOwnEntry() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            // when
            var a = f.startYaml(RootedSdkFixture.resource("graph-019-a.yaml"), "rcp/reconnect/A");
            var b = f.startYaml(RootedSdkFixture.resource("graph-019-b.yaml"), "rcp/reconnect/B");
            drain(f, f.append(a, "rcp/reconnect/A", "attach", 100, attach("b", b.id().value())));
            drain(f, f.append(b, "rcp/reconnect/B", "attach", 200, attach("a", a.id().value())));
            drain(f, f.append(a, "rcp/reconnect/A", "detach", 300, "edge: b"));
            drain(f, f.append(b, "rcp/reconnect/B", "detach", 350, "edge: a"));
            var sourceHistory = f.history(b);
            String sourceHead = b.snapshot().blueId();
            drain(f, f.append(a, "rcp/reconnect/A", "touch", 400, "{}"));
            // then
            assertEquals(sourceHead, b.snapshot().blueId());
            assertEquals(sourceHistory, f.history(b));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().processNext(a).quiescent());
            assertEquals(sourceHistory, f.history(b));
            drain(f, f.append(a, "rcp/reconnect/A", "attach", 450, attach("b", b.id().value())));
            assertTrue(f.blue.advanced().auditManagedOccurrence(a.id(), "/peers/b").orElseThrow().active());
            assertFalse(f.blue.advanced().auditManagedOccurrence(b.id(), "/peers/a").orElseThrow().active());
            assertEquals(sourceHead, b.snapshot().blueId());
            assertEquals(sourceHistory, f.history(b));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().processNext(a).quiescent());
        }
    }

    private static long run(boolean retained, long expectedObserved) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var a = f.startYaml(RootedSdkFixture.resource("graph-019-a.yaml"), "rcp/reconnect/A");
            var b = f.startYaml(RootedSdkFixture.resource("graph-019-b.yaml"), "rcp/reconnect/B");
            String originalA = a.id().value(), originalB = b.id().value();
            drain(f, f.append(a, "rcp/reconnect/A", "attach", 100, attach("b", originalB)));
            drain(f, f.append(b, "rcp/reconnect/B", "attach", 200, attach("a", originalA)));
            var old = f.append(b, "rcp/reconnect/B", "emit", 250, "to: A\nnext: stop");
            drain(f, old);
            String savedB250 = b.snapshot().blueId();
            var originalHistory = f.history(b);
            drain(f, f.append(a, "rcp/reconnect/A", "detach", 300, "edge: b"));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            drain(f, f.append(b, "rcp/reconnect/B", "emit", 350, "to: A\nnext: stop"));
            var future = f.append(b, "rcp/reconnect/B", "emit", 500, "to: A\nnext: stop");
            var reconnect = f.append(a, "rcp/reconnect/A", "attach", 400, attach("b", retained ? savedB250 : originalB));
            var ownNext = f.append(a, "rcp/reconnect/A", "touch", 450, "{}");
            drain(f, reconnect);
            assertEquals(expectedObserved, a.snapshot().longAt("/observed"));
            assertEquals(2L, f.blue.advanced().auditManagedEpochs(b.id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream()).count());
            assertTrue(f.blue.advanced().auditManagedOccurrence(a.id(), "/peers/b").orElseThrow().active());
            assertTrue(f.blue.advanced().auditManagedDocumentReadiness(a.id()).orElseThrow().ready());
            assertTrue(f.blue.advanced().auditManagedDocumentReadiness(b.id()).orElseThrow().ready());
            assertEquals(originalHistory, f.history(b).subList(0, originalHistory.size()));
            var heads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            drain(f, ownNext);
            assertEquals(expectedObserved, a.snapshot().longAt("/observed"));
            drain(f, future);
            assertEquals(expectedObserved + 1, a.snapshot().longAt("/observed"));
            var histories = List.of(f.history(a), f.history(b));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().processNext(a).quiescent());
            assertEquals(histories, List.of(f.history(a), f.history(b)));
            return a.snapshot().longAt("/observed");
        }
    }

    private static String attach(String edge, String id) {
        return "edge: " + edge + "\nsource:\n  blueId: " + id;
    }

    private static void drain(RootedSdkFixture f, EntryHandle through) {
        boolean applied = false;
        var observations = new java.util.ArrayList<String>();
        long cutoff = f.blue.advanced().auditTimelineEntry(through.blueId()).orElseThrow().timestampMicros();
        for (int step = 0; step < 32; step++) {
            var selected = f.blue.advanced().auditNextProcessingSelection();
            DrainResult result;
            if (selected.kind().name().equals("MANAGED_EPOCH_APPLICATION")) {
                var work = selected.managedEpochApplicationWork().orElseThrow();
                var receipt = f.blue.advanced().auditManagedEpoch(work.sourceDocumentId(), work.sourceEpoch()).orElseThrow();
                assertTrue(((Number) receipt.sourceOrder().orElseThrow().components().get(0)).longValue() <= cutoff);
                result = f.blue.processing().drainManagedEpochApplication(work.workIdentity());
            } else result = f.blue.advanced().drainJournalThrough(through, new DrainBudget(1, 1));
            assertFalse(result.blocked(), result.diagnostic().toString());
            for (var entry : result.entries()) {
                observations.add(entry.entry().blueId() + " " + entry.disposition() + " " + entry.diagnostic()
                        + " " + entry.closures().stream().map(c -> c.disposition() + " " + c.diagnostic()).toList());
                assertTrue(f.blue.advanced().auditTimelineEntry(entry.entry().blueId()).orElseThrow().timestampMicros() <= cutoff);
                if (entry.entry().blueId().equals(through.blueId())
                        && entry.disposition() == EntryDisposition.APPLIED) applied = true;
            }
            if (result.quiescent()) { assertTrue(applied, observations.toString()); return; }
        }
        fail("The exact RUN019 sequence did not settle within its unchanged 32-selection bound");
    }
}
