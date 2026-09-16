package blue.coordination.sdk;

import blue.coordination.internal.ReceiptStorageFixture;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredProcessingReceiptSdkTest {
    @Test void actualNumberedSuccessorAndRepresentationDrainsRetainFullOriginalReceipts() throws Exception {
        ReceiptStorageFixture storage;
        try (var scenario = new RootedTerminalTailSdkScenario(100_000L, false, 1)) {
            storage = new ReceiptStorageFixture(scenario.f.blue.advanced().rawEngine());
            var sources = scenario.sourceState(); int numbered = 0, representations = 0; boolean ready = false;
            for (int step = 0; step < 32; step++) {
                var receipt = storage.next(scenario.consumer.id());
                for (var attempt : receipt.managedEpochApplicationAttempts()) {
                    assertTrue(attempt.published()); assertTrue(attempt.attempt().processResult().commits());
                    if (attempt.work().successorRepresentationCause().isPresent()) numbered++;
                    if (attempt.work().isRepresentationApplication()) representations++;
                }
                assertEquals(sources, scenario.sourceState());
                if (scenario.f.blue.advanced().auditManagedDocumentReadiness(scenario.consumer.id()).orElseThrow().ready()) { ready = true; break; }
            }
            assertTrue(ready); assertEquals(1, numbered); assertEquals(2, representations);
            assertEquals(scenario.expectedConsumerChild, scenario.consumer.snapshot().valueAt("/child").blueId());
            var finalState = scenario.consumerProgress(); storage.reopenAll(); assertEquals(finalState, scenario.consumerProgress());
        }
        storage.reopenAll();
    }

    @Test void actualLocalRetainedDrainKeepsOriginalHistoryOriginAndPublicationFence() throws Exception {
        ReceiptStorageFixture storage;
        try (var f = new RootedSdkFixture()) {
            String template = RootedSdkFixture.resource("node-graph.template.json");
            var roots = new LinkedHashMap<String, DocumentHandle>(); var originals = new HashMap<String, String>();
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "receipt-storage")
                        .replace("<TIMELINE>", "receipt-storage/all");
                originals.put(name, f.blue.values().yaml(yaml).blueId());
                roots.put(name, f.startYaml(yaml, "receipt-storage/all"));
            }
            var a = roots.get("A"); var b = roots.get("B");
            var first = f.append(a, "receipt-storage/all", "attach", 100, "edge: b\nsource: {blueId: " + originals.get("B") + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(a, first).entry(first).disposition()); settle(f, a);
            var second = f.append(b, "receipt-storage/all", "attach", 200, "edge: c\nsource: {blueId: " + originals.get("C") + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(b, second).entry(second).disposition()); settle(f, b);
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(a, second).entry(second).disposition());
            var sources = List.of(b.exact().json(), roots.get("C").exact().json(), f.history(b), f.history(roots.get("C")));
            storage = new ReceiptStorageFixture(f.blue.advanced().rawEngine());
            var receipt = storage.next(a.id()); assertEquals(1, receipt.rootedRetainedAttempts().size());
            assertTrue(receipt.rootedRetainedAttempts().get(0).attempt().published());
            assertTrue(receipt.rootedRetainedAttempts().get(0).attempt().attempt().processResult().totalGas() > 0);
            assertEquals(sources, List.of(b.exact().json(), roots.get("C").exact().json(), f.history(b), f.history(roots.get("C"))));
            var state = List.of(a.exact().json(), f.history(a)); storage.reopenAll(); assertEquals(state, List.of(a.exact().json(), f.history(a)));
        }
        storage.reopenAll();
    }

    private static void settle(RootedSdkFixture f, DocumentHandle root) {
        for (int step = 0; step < 32; step++) { var progress = f.blue.processing().processNext(root); assertFalse(progress.blocked()); if (progress.quiescent()) return; }
        fail("Original 32-step fixture bound exceeded");
    }
}
