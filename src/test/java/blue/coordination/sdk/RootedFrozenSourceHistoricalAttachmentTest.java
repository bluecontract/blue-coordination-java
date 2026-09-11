package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Saved-state attachment and active replacement preserve an already-frozen newer source. */
final class RootedFrozenSourceHistoricalAttachmentTest {
    @Test void freshSavedC0AttachmentAlongsideFrozenC1() throws Exception {
        // given
        boolean retarget = false;
        // when
        org.junit.jupiter.api.function.Executable scenario = () -> check(retarget);
        // then
        assertDoesNotThrow(scenario);
    }
    @Test void activeBRetargetToSavedC0AlongsideFrozenC1() throws Exception {
        // given
        boolean retarget = true;
        // when
        org.junit.jupiter.api.function.Executable scenario = () -> check(retarget);
        // then
        assertDoesNotThrow(scenario);
    }

    private static void check(boolean retarget) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Retarget source C")
                    .replace("rcp2/a", "rcp2/c"), "rcp2/c");
            String savedC0 = c.snapshot().blueId();
            var parent = f.startYaml(RootedRetargetInputTest.consumer()
                    + operation("attachOther", "/orders/other")
                    + operation("attachFresh", "/orders/fresh"), "rcp2/b");
            if (retarget) applied(f, parent, f.append(parent, "rcp2/b", "attach", 100,
                    reference(b.snapshot().blueId())));
            applied(f, c, f.append(c, "rcp2/c", "tick", 200, "{}"));
            String currentC1 = f.retain(c);
            applied(f, parent, f.append(parent, "rcp2/b", "attachOther", 300, reference(currentC1)));
            var other = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow();
            assertTrue(other.active());
            assertEquals(c.id(), other.targetDocumentId());
            assertEquals(currentC1, parent.snapshot().valueAt("/orders/other").blueId());
            var sourceHeads = List.of(b.snapshot().blueId(), c.snapshot().blueId());
            var sourceHistories = List.of(f.history(b), f.history(c));
            String path = retarget ? "/orders/same" : "/orders/fresh";
            var entry = f.blue.operations().on(parent).from(f.timelines.get("rcp2/b"))
                    .call(retarget ? "replace" : "attachFresh").through("owner").requestYaml(reference(savedC0))
                    .selectManagedEpoch(ManagedEpochSelector.exact(c.id(), 0L, savedC0, path)).submit();

            var admitted = f.blue.processing().processNext(parent).entry(entry);

            assertEquals(sourceHeads, List.of(b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));
            assertEquals(other, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow());
            assertEquals(EntryDisposition.APPLIED, admitted.disposition(), admitted.diagnostic().toString());
            var invocation = f.blue.advanced().closureInvocation(admitted.closures().get(0).closureId()).orElseThrow();
            var frozenC = invocation.snapshot().managedDocument(
                    new blue.language.processor.closure.DocumentId(c.id().value()));
            assertNotNull(frozenC);
            assertEquals(currentC1, frozenC.blueId());
            assertEquals(1L, frozenC.epoch());
            var output = f.blue.advanced().closureExecution(admitted.closures().get(0).closureId()).orElseThrow();
            var pending = output.occurrenceBindings().stream().filter(row ->
                    row.sourceDocumentId().value().equals(parent.id().value()) && row.sourcePath().equals(path))
                    .findFirst().orElseThrow();
            assertEquals(savedC0, pending.expectedTargetBlueId());
            assertEquals(Long.valueOf(0L), pending.pendingHistoricalEpoch());
            assertEquals(currentC1, parent.snapshot().valueAt("/orders/other").blueId());
            var selected = f.blue.advanced().auditManagedOccurrence(parent.id(), path).orElseThrow();
            assertEquals(c.id(), selected.targetDocumentId());
            assertEquals(retarget ? 2L : 1L, selected.activationGeneration());
            assertFalse(selected.active());

            var caughtUp = f.blue.processing().processNext(parent);
            assertEquals(1, caughtUp.managedEpochApplications().size(), caughtUp.managedEpochApplicationAttempts().toString());
            assertTrue(caughtUp.quiescent());
            assertEquals(currentC1, parent.snapshot().valueAt(path).blueId());
            assertEquals(other, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow());
            assertEquals(sourceHeads, List.of(b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));
            var finalHead = parent.snapshot().blueId();
            var finalHistory = f.history(parent);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(finalHead, parent.snapshot().blueId());
            assertEquals(finalHistory, f.history(parent));
            assertTrue(f.blue.processing().processNext(parent).quiescent());
        }
    }

    private static String operation(String name, String path) {
        return """
                  %s:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {child: {}}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: add
                          path: %s
                          val: {$binding: event/message/request/child}
                      - $return: true
                """.formatted(name, path);
    }
    private static String reference(String id) { return "child:\n  blueId: " + id; }
    private static void applied(RootedSdkFixture f, DocumentHandle root, EntryHandle entry) {
        var result = f.blue.processing().processNext(root).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
    }
}
