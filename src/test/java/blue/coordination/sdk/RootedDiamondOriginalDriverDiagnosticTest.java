package blue.coordination.sdk;

import blue.coordination.internal.RootedDiamondDriverProbe;
import blue.coordination.internal.RootedJoinPublicationSafetyProbe;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only diagnostic, not a passing diamond-settlement claim or a publication bypass. */
final class RootedDiamondOriginalDriverDiagnosticTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void originalAInputBeforeIndependentSiblingPublication() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var template = RootedSdkFixture.resource("node-graph.template.json");
            for (var name : List.of("A", "B", "D", "C")) {
                var authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "witness-forwarding")
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored);
                f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var d = roots.get("D"); var c = roots.get("C");
            prefix(f, d, "attach", 100, attachment("c", c));
            prefix(f, b, "attach", 125, attachment("c", c));
            prefix(f, a, "attach", 150, attachment("b", b));
            prefix(f, a, "attach", 175, attachment("d", d));
            prefix(f, a, "emit", 200, "to: C\nnext: B");
            prefix(f, a, "emit", 210, "to: C\nnext: D");
            prefix(f, a, "touch", 250, "{}");
            assertEquals(List.of(11L, 2L, 2L, 0L), roots.values().stream()
                    .map(root -> f.blue.advanced().auditDocument(root.id()).epoch()).toList());
            var entry = f.append(c, TIMELINE, "attach", 300, attachment("a", a), true);
            assertEquals("5G1qitMzxCuQ3UpCuJyh2fZimmbdRNwsthMYJUWZBjVL", entry.blueId());
            var originalA = f.control.capture(a.id(), entry.blueId(), null);
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(c, entry).entry(entry).disposition());
            var registered = new RootedJoinPublicationSafetyProbe(f.blue.advanced().rawEngine());
            boolean atTerminal = false;
            for (int step = 0; step < 32; step++) {
                var input = registered.registeredInput(c.id());
                if (input.cause() instanceof ManagedRevisionCause revision && revision.toEpoch() == 11L
                        && revision.successorRepresentationCause().isEmpty()) {
                    assertEquals(10L, revision.fromEpoch());
                    assertEquals(11L, input.snapshot().managedDocument(revision.childDocumentId()).epoch());
                    atTerminal = true; break;
                }
                assertFalse(f.blue.processing().processNext(c).quiescent());
            }
            assertTrue(atTerminal, "Stop before the genuine A10→11 terminal, including its earlier representation work");
            assertEquals(List.of(11L, 2L, 2L, 12L), roots.values().stream()
                    .map(root -> f.blue.advanced().auditDocument(root.id()).epoch()).toList());
            assertEquals(List.of(0L, 0L, 0L, 2L), roots.values().stream()
                    .map(root -> observed(f, root)).toList());
            var plan = f.blue.advanced().auditManagedCatchUpPlans(c.id()).stream()
                    .filter(value -> value.status() == blue.coordination.api.ManagedCatchUpStatus.RUNNING).findFirst().orElseThrow();
            assertEquals(11L, plan.nextSourceEpoch()); assertEquals(11L, plan.requiredThroughSourceEpoch());
            var before = registered.publicationState();
            var histories = roots.values().stream().map(f::history).toList();
            // when
            var probe = new RootedDiamondDriverProbe(f.blue.advanced().rawEngine()).inspect(a.id(), entry.blueId());
            probe.lines().forEach(line -> System.out.println("DIAMOND_A_DRIVER " + line));
            System.out.println("DIAMOND_A_DRIVER frozenPlan=" + plan.snapshotIdentity() + " next=11 through=11");
            // then
            assertEquals(originalA.invocationIdentity(), probe.originalInput().invocationIdentity());
            assertEquals(roots.values().stream().map(root -> root.id().value()).collect(java.util.stream.Collectors.toSet()), probe.activeReach());
            assertTrue(probe.publicationUnchanged()); assertEquals(before, registered.publicationState());
            assertEquals(histories, roots.values().stream().map(f::history).toList());
            assertNull(probe.failure(), String.join("\n", probe.lines()));
            assertNotNull(probe.attempt()); assertTrue(probe.attempt().isComplete(), String.join("\n", probe.lines()));
            var actual = probe.attempt().processResult(); var reference = assertNotNullResult(probe.reference());
            assertEquals(actual.invocationIdentity(), reference.invocationIdentity());
            assertEquals(actual.outputClosureIdentity(), reference.outputClosureIdentity());
            assertEquals(actual.publicEventsIdentity(), reference.publicEventsIdentity());
            assertEquals(actual.managedTransitionReceiptsIdentity(), reference.managedTransitionReceiptsIdentity());
            assertEquals(actual.checkpointWritesIdentity(), reference.checkpointWritesIdentity());
            assertEquals(actual.commitCompanion().companionIdentity(), reference.commitCompanion().companionIdentity());
            assertEquals(actual.totalGas(), reference.totalGas()); assertEquals(actual.gasTraceIdentity(), reference.gasTraceIdentity());
            // Deliberately no A/B/D/C settlement oracle here: this nonpublishing
            // diagnostic distinguishes a valid original driver from a self-return prerequisite.
        }
    }

    private static blue.language.processor.closure.ClosureProcessResult assertNotNullResult(
            blue.language.processor.closure.ClosureProcessResult result) {
        assertNotNull(result); return result;
    }

    private static void prefix(RootedSdkFixture f, DocumentHandle root, String operation, long time, String request) {
        var entry = f.append(root, TIMELINE, operation, time, request, true);
        assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(root, entry).entry(entry).disposition());
        for (int step = 0; step < 16; step++) {
            var next = f.blue.processing().processNext(root);
            assertFalse(next.blocked()); if (next.quiescent()) return;
        }
        fail("Exact prefix did not settle");
    }

    private static String attachment(String edge, DocumentHandle root) {
        return "edge: " + edge + "\nsource: {blueId: " + root.id().value() + "}";
    }

    private static long observed(RootedSdkFixture f, DocumentHandle root) {
        return ((Number) ExactBlueValue.wrap(f.blue.advanced().auditDocument(root.id()).current()).scalarAt("/observed")).longValue();
    }
}
