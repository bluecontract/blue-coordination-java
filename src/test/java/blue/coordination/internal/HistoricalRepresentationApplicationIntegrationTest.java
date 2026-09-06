package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public SDK sequences for the four existing blocked graph operations. */
final class HistoricalRepresentationApplicationIntegrationTest {
    @Test
    void savedOriginalPairReconnectPublishesEachHistoricalPosition() throws Exception {
        try (Graph graph = new Graph("A", "B")) {
            graph.attach("A", "b", "B");
            graph.attach("B", "a", "A");
            graph.operation("B", "emit", "{to: A, next: stop}");
            graph.operation("A", "detach", "{edge: b}");
            graph.operation("B", "emit", "{to: A, next: stop}");
            long emittedBefore = graph.sourceEvents("B");
            graph.verifyRepresentationPublicationFailures = true;
            graph.attach("A", "b", "B");
            assertTrue(graph.representationApplications > 0, "the actual historical gap must use explicit positional work");
            assertEquals(emittedBefore, graph.sourceEvents("B"), "catch-up must not replay the source emission");
            graph.assertEdge("A", "b", "B");
            graph.assertEdge("B", "a", "A");
            assertEquals(3L, graph.observed("A"));
            graph.emit("B", "A", "stop", Map.of("A", 1L));
            assertEquals(4L, graph.observed("A"));
        }
    }

    @Test
    void savedOriginalFigureEightPublishesBothLoops() throws Exception {
        try (Graph graph = new Graph("A", "B", "C")) {
            graph.attach("A", "b", "B");
            graph.attach("B", "a", "A");
            graph.attach("A", "c", "C");
            graph.attach("C", "a", "A");
            assertTrue(graph.representationApplications > 0);
            graph.assertEdge("A", "b", "B");
            graph.assertEdge("B", "a", "A");
            graph.assertEdge("A", "c", "C");
            graph.assertEdge("C", "a", "A");
            graph.emit("B", "A", "C", Map.of("A", 1L, "C", 1L));
            graph.detach("A", "b"); graph.detach("B", "a");
            graph.emit("B", "A", "stop", Map.of());
            graph.emit("C", "A", "stop", Map.of("A", 1L));
        }
    }

    @Test
    void savedOriginalJoinSplitRetainsIndependentLoops() throws Exception {
        try (Graph g = new Graph("A", "B", "C", "D")) {
            g.attach("A", "b", "B"); g.attach("B", "a", "A");
            g.attach("C", "d", "D"); g.attach("D", "c", "C");
            g.attach("B", "bridge", "C"); g.attach("D", "bridge", "A");
            g.emit("C", "B", "stop", Map.of("B", 1L));
            g.detach("D", "bridge"); g.detach("B", "bridge");
            g.emit("C", "B", "stop", Map.of());
            g.emit("B", "A", "stop", Map.of("A", 1L));
            g.emit("D", "C", "stop", Map.of("C", 1L));
            g.assertEdge("A", "b", "B"); g.assertEdge("B", "a", "A");
            g.assertEdge("C", "d", "D"); g.assertEdge("D", "c", "C");
        }
    }

    @Test
    void savedOriginalRingDuplicateChordAndReconnectPreserveEvents() throws Exception {
        try (Graph g = new Graph("A", "B", "C")) {
            g.budgetFromRetainedHistory = true;
            g.attach("A", "b", "B"); g.attach("B", "c", "C"); g.attach("C", "a", "A");
            g.emit("A", "C", "B", Map.of("C", 1L, "B", 1L));
            g.attach("A", "c", "C"); g.emit("C", "A", "stop", Map.of("A", 1L));
            g.attach("A", "c2", "C"); g.emit("C", "A", "stop", Map.of("A", 2L));
            g.detach("A", "c2"); g.detach("A", "c"); g.detach("C", "a");
            g.emit("A", "C", "stop", Map.of());
            var counts = g.counts();
            var events = new LinkedHashMap<String, Long>();
            g.originals.keySet().forEach(name -> events.put(name, g.sourceEvents(name)));
            g.attach("C", "a", "A");
            assertEquals(counts.get("A"), g.observed("A"));
            assertEquals(counts.get("B") + 1L, g.observed("B"));
            assertEquals(counts.get("C") + 2L, g.observed("C"));
            assertEquals(events.get("A"), g.sourceEvents("A"));
            assertEquals(events.get("B"), g.sourceEvents("B"));
            assertEquals(events.get("C") + 1L, g.sourceEvents("C"));
            g.emit("A", "C", "stop", Map.of("C", 1L));
        }
    }

    private static final class Graph implements AutoCloseable {
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build();
        final Map<String, ExactBlueValue> originals = new LinkedHashMap<>();
        final Map<String, DocumentHandle> handles = new LinkedHashMap<>();
        final TimelineHandle timeline;
        int representationApplications;
        boolean budgetFromRetainedHistory;
        boolean verifyRepresentationPublicationFailures;

        Graph(String... names) throws Exception {
            String template;
            try (var stream = HistoricalRepresentationApplicationIntegrationTest.class.getResourceAsStream(
                    "/historical-representation/reconnect.template.json")) {
                template = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
            }
            String owner = "review/representation-gap/alice";
            Map<String, String> sources = new LinkedHashMap<>();
            for (String name : names) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", "review-gap").replace("<TIMELINE>", owner);
                sources.put(name, source);
                originals.put(name, blue.values().yaml(source));
            }
            assertEquals("EvDZ5SpjRrcYNbikhKvtPctuwoFXCMZvK9kpfc52owWy", originals.get("A").blueId());
            assertEquals("HbhBw4c8AU5fUSeEgqMVm2eZoH1j1FSSrMUr7rFNn61z", originals.get("B").blueId());
            timeline = blue.timelines().register(owner, "alice");
            for (String name : names) handles.put(name, blue.documents().admit(ManagedDocument.yaml(id(name), sources.get(name))
                    .publicRoot().fromNow()));
        }

        DocumentId id(String name) { return DocumentId.of(originals.get(name).blueId()); }
        boolean ready() { return originals.keySet().stream().allMatch(name -> blue.advanced().auditManagedDocumentReadiness(id(name)).orElseThrow().ready()); }
        long sourceEvents(String name) { return blue.advanced().auditManagedEpochs(id(name)).stream().mapToLong(r -> r.emittedEvents().size()).sum(); }
        long observed(String name) { return handles.get(name).snapshot().longAt("/observed"); }
        Map<String, Long> counts() {
            var result = new LinkedHashMap<String, Long>();
            originals.keySet().forEach(name -> result.put(name, observed(name)));
            return result;
        }
        void detach(String parent, String edge) { operation(parent, "detach", "{edge: " + edge + "}"); }
        void emit(String source, String to, String next, Map<String, Long> deltas) {
            var before = counts();
            operation(source, "emit", "{to: " + to + ", next: " + next + "}");
            before.forEach((name, count) -> assertEquals(count + deltas.getOrDefault(name, 0L), observed(name), name));
        }

        void operation(String name, String operation, String request) {
            assertTrue(blue.operations().on(handles.get(name)).from(timeline).call(operation).through("ownerChannel").requestYaml(request).execute().applied());
        }

        void attach(String parent, String edge, String child) {
            Map<DocumentId, List<String>> history = new LinkedHashMap<>();
            for (String name : originals.keySet()) history.put(id(name), blue.advanced().auditManagedEpochs(id(name)).stream().map(r -> r.receiptIdentity()).toList());
            var entry = blue.operations().on(handles.get(parent)).from(timeline).call("attach").through("ownerChannel")
                    .request(r -> r.exact("edge", blue.values().yaml(edge)).exact("source", originals.get(child))).submit();
            assertTrue(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(entry).applied());
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            // This newly added complex owner already retains more than 32 epochs
            // before reconnect. Freeze its work budget from existing history;
            // never extend it in response to new heads. The original ring keeps
            // its independent, unchanged 32-step regression.
            int bound = budgetFromRetainedHistory ? Math.max(32, originals.keySet().stream()
                    .mapToInt(node -> blue.advanced().auditManagedEpochs(id(node)).size()).sum()) : 32;
            for (int step = 0; step < bound && !ready(); step++) {
                var selected = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                var work = engine.documents().nextCatchUpWork().orElseThrow();
                assertEquals(selected.workIdentity(), work.workIdentity());
                long beforeCursor = engine.documents().catchUpPlan(work.planIdentity()).orElseThrow().nextSourceEpoch();
                System.out.println("POSITIONAL_PUBLIC_WORK edge=" + parent + "->" + child + " step=" + step
                        + " sourceEpoch=" + work.sourceEpoch() + " representation=" + work.isRepresentationApplication());
                System.out.println("CAPTURED_BOUND=" + bound + " CURRENT_SOURCE_EPOCH=" + engine.documents().publicationSnapshot().requireHead(work.sourceDocumentId()).epoch());
                if (work.isRepresentationApplication() && verifyRepresentationPublicationFailures) {
                    verifyRepresentationPublicationFailures = false;
                    var adapter = engine.contractsClosureAdapter();
                    var publication = engine.documents().publicationSnapshot();
                    var plans = engine.documents().catchUpPlansSnapshot();
                    for (var point : MultiDocumentPublicationTransaction.FailurePoint.values()) {
                        adapter.onStoreFailurePoint(selectedPoint -> {
                            if (selectedPoint == point) throw new IllegalStateException("representation rollback " + point);
                        });
                        try {
                            assertEquals("representation rollback " + point,
                                    assertThrows(IllegalStateException.class, adapter::processNextManagedEpochApplication).getMessage());
                        } finally { adapter.onStoreFailurePoint(ignored -> { }); }
                        assertEquals(publication, engine.documents().publicationSnapshot());
                        assertSame(plans, engine.documents().catchUpPlansSnapshot());
                        assertEquals(work.workIdentity(), engine.documents().nextCatchUpWork().orElseThrow().workIdentity());
                    }
                    adapter.onPublicationFailurePoint(point -> {
                        if (point == ContractsClosureAdapter.PublicationFailurePoint.AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH)
                            throw new IllegalStateException("representation committed response lost");
                    });
                    try {
                        assertEquals("representation committed response lost",
                                assertThrows(IllegalStateException.class, adapter::processNextManagedEpochApplication).getMessage());
                    } finally { adapter.onPublicationFailurePoint(ignored -> { }); }
                    var committed = engine.documents().catchUpApplicationByWork(work.workIdentity()).orElseThrow();
                    var repair = adapter.processNextManagedEpochApplication().orElseThrow();
                    assertTrue(repair.replayed());
                    assertEquals(committed.applicationReceiptIdentity(), repair.receipt().orElseThrow().applicationReceiptIdentity());
                    assertEquals(beforeCursor, committed.resultingSourceCursor());
                    engine.restartFromStores();
                    assertEquals(committed.applicationReceiptIdentity(), engine.documents().catchUpApplicationByWork(work.workIdentity()).orElseThrow().applicationReceiptIdentity());
                    representationApplications++;
                    continue;
                }
                assertEquals(1, blue.processing().drainManagedEpochApplication(work.workIdentity()).managedEpochApplications().size());
                var applied = engine.documents().catchUpApplicationByWork(work.workIdentity()).orElseThrow();
                if (work.isRepresentationApplication()) {
                    representationApplications++;
                    assertEquals(beforeCursor, applied.resultingSourceCursor(), "a representation step must not advance the numbered cursor");
                    assertEquals(work.representationCause().orElseThrow().causeIdentity(), applied.representationCauseIdentity().orElseThrow());
                    var pending = engine.documents().nextCatchUpWork().map(w -> w.workIdentity());
                    engine.restartFromStores();
                    assertEquals(applied.applicationReceiptIdentity(), engine.documents().catchUpApplicationByWork(work.workIdentity()).orElseThrow().applicationReceiptIdentity());
                    assertEquals(pending, engine.documents().nextCatchUpWork().map(w -> w.workIdentity()), "restart must preserve the exact next position");
                } else assertEquals(beforeCursor + 1L, applied.resultingSourceCursor());
            }
            assertTrue(ready(), "saved-original graph must finish within its fixed bound " + bound + ": " + parent + "->" + child);
            for (var prior : history.entrySet()) {
                var retained = blue.advanced().auditManagedEpochs(prior.getKey()).stream().map(r -> r.receiptIdentity()).toList();
                assertEquals(prior.getValue(), retained.subList(0, prior.getValue().size()), "immutable receipt prefix changed");
            }
        }

        void assertEdge(String parent, String edge, String child) {
            var row = blue.advanced().auditManagedOccurrence(id(parent), "/peers/" + edge).orElseThrow();
            assertTrue(row.active());
            assertEquals(id(child), row.targetDocumentId());
        }
        public void close() { blue.close(); }
    }
}
