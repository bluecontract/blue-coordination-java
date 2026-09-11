package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Retarget effects must not rewrite the processor's original binding evidence. */
final class RootedRetargetInputTest {
    @Test
    void inactiveHistoricalRetargetRejectsWithoutRewritingTheReservedLineage() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Retarget source C")
                    .replace("rcp2/a", "rcp2/c"), "rcp2/c");
            String savedC0 = c.snapshot().blueId();
            var parent = f.startYaml(consumer(), "rcp2/b");
            applied(f, parent, f.append(parent, "rcp2/b", "attach", 100,
                    reference(b.snapshot().blueId())));
            applied(f, c, f.append(c, "rcp2/c", "tick", 200, "{}"));
            f.retain(c);
            applied(f, parent, f.append(parent, "rcp2/b", "remove", 300, "{}"));
            var reserved = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            assertFalse(reserved.active());
            assertEquals(2L, reserved.activationGeneration());
            var heads = heads(parent, b, c);
            var histories = List.of(f.history(parent), f.history(b), f.history(c));
            var retarget = f.blue.operations().on(parent).from(f.timelines.get("rcp2/b"))
                    .call("attach").through("owner").requestYaml(reference(savedC0))
                    .selectManagedEpoch(ManagedEpochSelector.exact(c.id(), 0L, savedC0, "/orders/same"))
                    .submit();

            // when
            var result = f.blue.processing().processNext(parent).entry(retarget);

            // then
            assertEquals(EntryDisposition.REJECTED, result.disposition(), result.diagnostic().toString());
            assertEquals("MANAGED_OCCURRENCE_BINDING_MISSING", result.diagnostic().code());
            assertTrue(result.stats().gas() > 0L);
            assertEquals(0L, result.stats().committedTransitions());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(reserved, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow());
            var replayed = f.blue.processing().process(parent, retarget).entry(retarget);
            assertEquals(result.disposition(), replayed.disposition());
            assertEquals(result.stats(), replayed.stats());
            assertEquals(result.diagnostic(), replayed.diagnostic());
            assertEquals(heads, heads(parent, b, c));
            assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
            var closure = result.closures().get(0);
            var input = f.blue.advanced().closureInvocation(closure.closureId()).orElseThrow();
            var output = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
            var before = row(input.snapshot().occurrences(), parent);
            var after = row(output.occurrenceBindings(), parent);
            assertEquals(b.id().value(), before.targetDocumentId().value());
            assertEquals(before.bindingIdentity(), after.bindingIdentity());
            assertNull(before.pendingHistoricalEpoch());
            assertTrue(output.rollbackToInput());
            assertNull(output.commitCompanion());
            assertTrue(output.checkpointWrites().isEmpty());
            assertTrue(output.managedTransitionReceipts().isEmpty());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, heads(parent, b, c));
            assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
            assertEquals(reserved, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow());
        }
    }

    @Test
    void activeRetargetChangesOnlyOutputAndReceivesTheNextLiveSourceEvent() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Retarget source C")
                    .replace("rcp2/a", "rcp2/c"), "rcp2/c");
            var parent = f.startYaml(consumer(), "rcp2/b");
            var original = applied(f, parent, f.append(parent, "rcp2/b", "attach", 100,
                    reference(b.snapshot().blueId())));
            applied(f, c, f.append(c, "rcp2/c", "tick", 200, "{}"));
            f.retain(c);
            var sourceHeads = heads(b, c);
            var sourceHistories = List.of(f.history(b), f.history(c));
            var retarget = f.append(parent, "rcp2/b", "replace", 300,
                    reference(c.snapshot().blueId()));

            // when
            var result = applied(f, parent, retarget);

            // then
            var closure = result.closures().get(0);
            var input = f.blue.advanced().closureInvocation(closure.closureId()).orElseThrow();
            var output = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
            var before = row(input.snapshot().occurrences(), parent);
            var after = row(output.occurrenceBindings(), parent);
            var originalRow = row(f.blue.advanced().closureExecution(
                    original.closures().get(0).closureId()).orElseThrow().occurrenceBindings(), parent);
            assertEquals(originalRow.bindingIdentity(), before.bindingIdentity());
            assertEquals(b.id().value(), before.targetDocumentId().value());
            assertTrue(before.active());
            assertEquals(c.id().value(), after.targetDocumentId().value());
            assertTrue(after.active());
            assertEquals(before.activationGeneration() + 1L, after.activationGeneration());
            assertNotEquals(before.occurrenceIdentity(), after.occurrenceIdentity());
            assertNotEquals(before.bindingIdentity(), after.bindingIdentity());
            assertNull(after.pendingHistoricalEpoch());
            assertEquals(1, output.graphChanges().size());
            assertEquals("REBIND", output.graphChanges().get(0).changeKind().name());
            assertEquals(sourceHeads, heads(b, c));
            assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));
            assertTrue(result.stats().gas() > 0L);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            var live = f.append(c, "rcp2/c", "tick", 400, "{}");
            applied(f, c, live);
            var cHistory = f.history(c);
            var cHead = c.snapshot().blueId();
            applied(f, parent, live);
            assertEquals(2L, parent.snapshot().longAt("/seen"));
            assertEquals(cHead, c.snapshot().blueId());
            assertEquals(cHistory, f.history(c));
            assertEquals(sourceHistories.get(0), f.history(b));
        }
    }

    private static EntryResult applied(RootedSdkFixture f, DocumentHandle root, EntryHandle entry) {
        var result = f.blue.processing().processNext(root).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
        return result;
    }

    @Test
    void activeRetargetKeepsItsExactGasBoundaryAndRollsBackAtOneLess() throws Exception {
        // given
        long required = activeRetargetAtGas(100_000L, true);
        assertTrue(required > 1L);
        // when
        activeRetargetAtGas(required - 1L, false);
        // then
        assertEquals(required, activeRetargetAtGas(required, true));
    }

    private static long activeRetargetAtGas(long budget, boolean succeeds) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Retarget source C")
                    .replace("rcp2/a", "rcp2/c"), "rcp2/c");
            var parent = f.startYaml(consumer(), "rcp2/b");
            applied(f, parent, f.append(parent, "rcp2/b", "attach", 100, reference(b.snapshot().blueId())));
            applied(f, c, f.append(c, "rcp2/c", "tick", 200, "{}"));
            f.retain(c);
            var before = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            var heads = heads(parent, b, c);
            var histories = List.of(f.history(parent), f.history(b), f.history(c));
            var entry = f.append(parent, "rcp2/b", "replace", 300, reference(c.snapshot().blueId()));
            var result = f.blue.advanced().process(parent, entry,
                    ContractsExecutionPolicy.exactSharedGas(budget, "rooted-retarget-gas")).entry(entry);
            assertEquals(succeeds ? EntryDisposition.APPLIED : EntryDisposition.GAS_LIMIT_EXCEEDED,
                    result.disposition(), result.diagnostic().toString());
            var output = f.blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
            if (succeeds) {
                var after = row(output.occurrenceBindings(), parent);
                assertTrue(after.active());
                assertEquals(c.id().value(), after.targetDocumentId().value());
                assertEquals(before.activationGeneration() + 1L, after.activationGeneration());
            } else {
                assertTrue(output.rollbackToInput());
                assertNull(output.commitCompanion());
                assertTrue(output.checkpointWrites().isEmpty());
                assertTrue(output.managedTransitionReceipts().isEmpty());
                assertNotNull(output.rejectedCharge());
                assertTrue(result.publicEvents().isEmpty());
                var input = f.blue.advanced().closureInvocation(result.closures().get(0).closureId()).orElseThrow();
                assertEquals(row(input.snapshot().occurrences(), parent).bindingIdentity(),
                        row(output.occurrenceBindings(), parent).bindingIdentity());
                assertEquals(heads, heads(parent, b, c));
                assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                assertEquals(before, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow());
                assertEquals(heads, heads(parent, b, c));
                assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
            }
            return output.totalGas();
        }
    }

    private static ManagedOccurrenceBinding row(List<ManagedOccurrenceBinding> rows, DocumentHandle parent) {
        return rows.stream().filter(row -> row.sourceDocumentId().value().equals(parent.id().value())
                && row.sourcePath().equals("/orders/same")).findFirst().orElseThrow();
    }

    private static List<String> heads(DocumentHandle... documents) {
        return java.util.Arrays.stream(documents).map(document -> document.snapshot().blueId()).toList();
    }

    private static String reference(String id) { return "child:\n  blueId: " + id; }

    static String consumer() throws Exception {
        return RootedSdkFixture.resource("historical-b.yaml")
                .replace("name: RCP2 Historical B", "name: Rooted retarget consumer\norders: {}")
                .replace("    paths:\n    - /child", "    collectionPaths:\n    - /orders")
                .replace("/child", "/orders/same")
                .replace("event/message/request/orders/same", "event/message/request/child") + """
                  replace:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {child: {}}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: replace
                          path: /orders/same
                          val: {$binding: event/message/request/child}
                      - $return: true
                  remove:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: remove
                          path: /orders/same
                      - $return: true
                """;
    }
}
