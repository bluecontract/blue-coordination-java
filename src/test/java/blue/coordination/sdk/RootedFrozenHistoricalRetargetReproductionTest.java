package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedRetargetAttemptProbe;
import blue.language.processor.closure.ClosureProcessRetryInput;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.processor.closure.ManagedOccurrenceEvidenceResolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A new historical target must not overwrite another occurrence's newer frozen source. */
final class RootedFrozenHistoricalRetargetReproductionTest {
    @Test
    void selectedC0CatchesUpWithoutReplacingAlreadyFrozenC1() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Retarget source C")
                    .replace("rcp2/a", "rcp2/c"), "rcp2/c");
            String savedC0 = c.snapshot().blueId();
            var parent = f.startYaml(RootedRetargetInputTest.consumer() + """
                  attachOther:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {child: {}}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: add
                          path: /orders/other
                          val: {$binding: event/message/request/child}
                      - $return: true
                """, "rcp2/b");
            applied(f, parent, f.append(parent, "rcp2/b", "attach", 100, reference(b.snapshot().blueId())));
            applied(f, c, f.append(c, "rcp2/c", "tick", 200, "{}"));
            f.retain(c);
            applied(f, parent, f.append(parent, "rcp2/b", "attachOther", 300, reference(c.snapshot().blueId())));
            applied(f, parent, f.append(parent, "rcp2/b", "remove", 400, "{}"));
            var reserved = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            var other = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow();
            assertFalse(reserved.active());
            assertEquals(b.id(), reserved.targetDocumentId());
            assertTrue(other.active());
            assertEquals(c.id(), other.targetDocumentId());
            var heads = List.of(parent.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId());
            var histories = List.of(f.history(parent), f.history(b), f.history(c));
            var entry = f.blue.operations().on(parent).from(f.timelines.get("rcp2/b"))
                    .call("attach").through("owner").requestYaml(reference(savedC0))
                    .selectManagedEpoch(ManagedEpochSelector.exact(c.id(), 0L, savedC0, "/orders/same")).submit();
            var captured = f.control.capture(parent.id(), entry.blueId(), null);
            var first = RootedRetargetAttemptProbe.firstAttempt(f.blue.advanced().rawEngine(), captured);
            assertFalse(first.isComplete());
            assertEquals(1, first.resourceDemands().size());
            var demand = assertInstanceOf(ManagedOccurrenceEvidenceDemand.class, first.resourceDemands().get(0));
            assertEquals(savedC0, demand.suppliedValueBlueId());
            var exactC0 = ManagedOccurrenceEvidenceResolution.derived(demand,
                    new blue.language.processor.closure.DocumentId(c.id().value()), 0L);
            var expectedRetry = ClosureProcessRetryInput.derived(captured, List.of(exactC0));

            // when
            var result = f.blue.processing().processNext(parent).entry(entry);

            // then
            assertEquals(heads.subList(1, 3), List.of(b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(histories.subList(1, 3), List.of(f.history(b), f.history(c)));
            assertEquals(other, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow());
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            var closure = result.closures().get(0);
            var input = f.blue.advanced().closureInvocation(closure.closureId()).orElseThrow();
            var output = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
            assertEquals(captured.invocationIdentity(), input.invocationIdentity());
            assertEquals(expectedRetry.retryInvocationIdentity(), output.invocationIdentity(),
                    "The retained result binds the actual demand resolved to C epoch zero, not the C1 primary");
            var frozenC = input.snapshot().managedDocument(exactC0.targetDocumentId());
            assertEquals(c.snapshot().blueId(), frozenC.blueId());
            assertEquals(1L, frozenC.epoch());
            assertFalse(output.rollbackToInput());
            assertTrue(output.totalGas() > 0L);
            assertNotNull(output.commitCompanion());
            var original = input.snapshot().occurrences().stream().filter(row -> row.sourcePath().equals("/orders/same"))
                    .findFirst().orElseThrow();
            var capturedReservation = captured.snapshot().occurrences().stream()
                    .filter(row -> row.sourcePath().equals("/orders/same")).findFirst().orElseThrow();
            assertEquals(capturedReservation.bindingIdentity(), original.bindingIdentity());
            var pending = output.occurrenceBindings().stream().filter(row -> row.sourcePath().equals("/orders/same"))
                    .findFirst().orElseThrow();
            assertEquals(c.id().value(), pending.targetDocumentId().value());
            assertEquals(reserved.activationGeneration(), pending.activationGeneration());
            assertNotEquals(original.occurrenceIdentity(), pending.occurrenceIdentity());
            assertEquals(savedC0, pending.expectedTargetBlueId());
            assertEquals(Long.valueOf(0L), pending.pendingHistoricalEpoch());
            var caughtUp = f.blue.processing().processNext(parent);
            assertEquals(1, caughtUp.managedEpochApplications().size());
            assertTrue(caughtUp.quiescent());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(other, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow());
            var finalHeads = List.of(parent.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId());
            var finalHistories = List.of(f.history(parent), f.history(b), f.history(c));
            var repeated = f.blue.processing().process(parent, entry).entry(entry);
            assertEquals(EntryDisposition.STALE, repeated.disposition());
            assertEquals("STALE_TARGET_DOCUMENT", repeated.diagnostic().code());
            var retained = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
            assertEquals(output.invocationIdentity(), retained.invocationIdentity());
            assertEquals(output.gasTraceIdentity(), retained.gasTraceIdentity());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(finalHeads, List.of(parent.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(finalHistories, List.of(f.history(parent), f.history(b), f.history(c)));
            assertTrue(f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow().active());
            assertEquals(other, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow());
        }
    }

    private static String reference(String id) { return "child:\n  blueId: " + id; }
    private static void applied(RootedSdkFixture f, DocumentHandle root, EntryHandle entry) {
        var result = f.blue.processing().processNext(root).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
    }
}
