package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real source emissions, retained application and restart; no synthetic epoch producer. */
final class RootedEventOnlyHistoryTest {
    @Test void eventOnlySourceRetainsItsActualPositionAndDoesNotRepeatAfterRestart() throws Exception {
        try (var f = new RootedSdkFixture()) {
            String yaml = sourceYaml();
            var source = f.startYaml(yaml, "rcp2/source");
            String savedZero = f.retain(source);
            var parent = f.startYaml(RootedSdkFixture.resource("parent.yaml"), "rcp2/parent");
            var event = f.append(source, "rcp2/source", "emitOnly", 100L, "{}");
            var emitted = f.blue.processing().processNext(source).entry(event);
            assertEquals(EntryDisposition.APPLIED, emitted.disposition());
            assertEquals(2, emitted.publicEvents().size());
            assertEquals(0L, source.snapshot().longAt("/counter"));
            String closure = emitted.closures().get(0).closureId();
            var input = f.blue.advanced().closureInvocation(closure).orElseThrow();
            var result = f.blue.advanced().closureExecution(closure).orElseThrow();
            var sourceId = new blue.language.processor.closure.DocumentId(source.id().value());
            assertEquals(input.snapshot().managedDocument(sourceId).epoch(), result.resultingDocuments().stream()
                    .filter(d -> d.documentId().equals(sourceId)).findFirst().orElseThrow().epoch(),
                    "Pure emissions must not invent a processor state epoch");
            var sourceReceipts = f.history(source);
            var sourceHead = source.snapshot().blueId();
            long retainedEpoch = f.blue.advanced().auditDocument(source.id()).epoch();
            assertEquals(1L, retainedEpoch);
            assertDoesNotThrow(() -> f.control.verifyRetainedPublicationHead(source.id(), retainedEpoch, sourceHead));
            assertThrows(IllegalArgumentException.class, () -> f.control.verifyRetainedPublicationHead(source.id(), 0L, sourceHead));
            assertThrows(IllegalArgumentException.class, () -> f.control.verifyRetainedPublicationHead(source.id(), 2L, sourceHead));
            assertThrows(IllegalArgumentException.class, () -> f.control.verifyRetainedPublicationHead(source.id(), retainedEpoch, savedZero));
            assertEquals(sourceReceipts, f.history(source));
            var attached = f.append(parent, "rcp2/parent", "attach", 200L, "child: {blueId: " + savedZero + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(attached).disposition());
            int applications = 0;
            boolean complete = false;
            for (int i = 0; i < 32; i++) {
                var next = f.blue.processing().processNext(parent);
                applications += next.managedEpochApplications().size();
                assertFalse(next.blocked(), next.diagnostic().toString());
                if (next.quiescent()) { complete = true; break; }
            }
            assertTrue(complete);
            assertEquals(1, applications, "The real event receipt needs one retained application");
            assertEquals(List.of(0, 0), parent.snapshot().valueAt("/log").copyNode().getItems().stream().map(n -> ((Number)n.getValue()).intValue()).toList());
            assertEquals(sourceHead, source.snapshot().blueId());
            assertEquals(sourceReceipts, f.history(source));
            var parentHistory = f.history(parent);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().processNext(parent).quiescent());
            assertTrue(f.blue.processing().processNext(source).quiescent());
            assertEquals(sourceReceipts, f.history(source));
            assertEquals(parentHistory, f.history(parent));
        }
    }
    @Test void repeatedEventOnlyPositionsRespectTheFrozenFrontierAndSurviveRestart() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(sourceYaml(), "rcp2/source");
            String original = f.retain(source);
            var parent = f.startYaml(RootedSdkFixture.resource("parent.yaml"), "rcp2/parent");
            EntryHandle future = null;
            for (long time : List.of(100L, 150L, 500L)) {
                var entry = f.append(source, "rcp2/source", "emitOnly", time, "{}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(entry).disposition());
                f.retain(source);
                if (time == 500L) future = entry;
            }
            assertEquals(3L, f.blue.advanced().auditDocument(source.id()).epoch());
            var sourceHistory = f.history(source);
            String sourceHead = source.snapshot().blueId();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            var attach = f.append(parent, "rcp2/parent", "attach", 200L, "child: {blueId: " + original + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(attach).disposition());
            var plan = f.blue.advanced().auditManagedCatchUpPlans(parent.id());
            assertEquals(1, plan.size());
            assertEquals(2L, plan.get(0).requiredThroughSourceEpoch(), "Order500 is outside the frozen interval");
            for (int position = 1; position <= 2; position++) {
                var next = f.blue.processing().processNext(parent);
                assertEquals(1, next.managedEpochApplications().size());
                assertEquals(position, next.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
                assertEquals(position * 2, f.blue.advanced().auditDocument(parent.id()).current()
                        .copyNode().getAsNode("/log").getItems().size());
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                assertEquals(sourceHistory, f.history(source));
                assertEquals(sourceHead, source.snapshot().blueId());
            }
            assertEquals(List.of(0, 0, 0, 0), parent.snapshot().valueAt("/log").copyNode().getItems().stream()
                    .map(n -> ((Number)n.getValue()).intValue()).toList());
            var live = f.blue.processing().processNext(parent);
            assertTrue(live.managedEpochApplications().isEmpty());
            assertEquals(EntryDisposition.APPLIED, live.entry(future).disposition());
            assertEquals(6, parent.snapshot().valueAt("/log").copyNode().getItems().size());
            var parentHistory = f.history(parent);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().processNext(parent).quiescent());
            assertEquals(parentHistory, f.history(parent));
            assertEquals(sourceHistory, f.history(source));
        }
    }

    private static String sourceYaml() throws Exception {
        return RootedSdkFixture.resource("source.yaml") + """
                  emitOnly:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendEvent:
                          type: Coordination/Event
                          kind: RCP2/Tick
                      - $appendEvent:
                          type: Coordination/Event
                          kind: RCP2/Tick
                      - $return: true
                """;
    }
}
