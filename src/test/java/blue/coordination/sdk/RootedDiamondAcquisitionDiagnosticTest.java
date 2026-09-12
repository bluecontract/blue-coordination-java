package blue.coordination.sdk;

import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedDiamondAcquisitionProbe;
import blue.coordination.internal.RootedJoinPublicationSafetyProbe;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** D-before-B is one explicit diagnostic schedule, not yet a production scheduling rule. */
final class RootedDiamondAcquisitionDiagnosticTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void completedPeerPrefixCanBeAddedWithoutReplacingItsHistoricalWitness() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "D", "C")) {
                var authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "witness-forwarding").replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored); f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var d = roots.get("D"); var c = roots.get("C");
            prefix(f, d, "attach", 100, attachment("c", c)); prefix(f, b, "attach", 125, attachment("c", c));
            prefix(f, a, "attach", 150, attachment("b", b)); prefix(f, a, "attach", 175, attachment("d", d));
            prefix(f, a, "emit", 200, "to: C\nnext: B"); prefix(f, a, "emit", 210, "to: C\nnext: D");
            prefix(f, a, "touch", 250, "{}");
            assertEquals(List.of(11L, 2L, 2L, 0L), roots.values().stream().map(root -> f.blue.advanced().auditDocument(root.id()).epoch()).toList());
            var histories = roots.values().stream().map(f::history).toList();
            var entry = f.append(c, TIMELINE, "attach", 300, attachment("a", a), true);
            assertEquals("5G1qitMzxCuQ3UpCuJyh2fZimmbdRNwsthMYJUWZBjVL", entry.blueId());
            var originalB = f.control.capture(b.id(), entry.blueId(), null);
            assertEquals(java.util.Set.of(b.id().value(), c.id().value()), originalB.snapshot().managedDocuments().stream()
                    .map(value -> value.documentId().value()).collect(java.util.stream.Collectors.toSet()));
            var safety = new RootedJoinPublicationSafetyProbe(f.blue.advanced().rawEngine());
            var probe = new RootedDiamondAcquisitionProbe(f.blue.advanced().rawEngine());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(c, entry).entry(entry).disposition());
            boolean cAtTerminal = false;
            for (int i = 0; i < 32; i++) {
                var cause = safety.registeredInput(c.id()).cause();
                if (cause instanceof ManagedRevisionCause revision && revision.fromEpoch() == 10L
                        && revision.toEpoch() == 11L && revision.successorRepresentationCause().isEmpty()) { cAtTerminal = true; break; }
                assertFalse(f.blue.processing().processNext(c).quiescent());
            }
            assertTrue(cAtTerminal);
            var frozen = safety.registeredInput(c.id());
            var sourceId = ((ManagedRevisionCause) frozen.cause()).childDocumentId();
            var frozenSource = frozen.snapshot().managedDocument(sourceId);
            var plan = f.blue.advanced().auditManagedCatchUpPlans(c.id()).stream()
                    .filter(value -> value.status() == ManagedCatchUpStatus.RUNNING).findFirst().orElseThrow();
            assertEquals(11L, plan.requiredThroughSourceEpoch()); assertEquals(11L, plan.nextSourceEpoch());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(d, entry).entry(entry).disposition());
            boolean dAtTerminal = false;
            for (int i = 0; i < 64; i++) {
                if (probe.atRegisteredTerminal(d.id(), c.id())) { dAtTerminal = true; break; }
                var before = safety.publicationState();
                var result = f.blue.processing().processNext(d);
                assertFalse(result.quiescent());
                assertNotEquals(before, safety.publicationState(), "Every D-prefix step must retain a real publication");
            }
            assertTrue(dAtTerminal);
            assertEquals(List.of(11L, 2L, 14L, 12L), roots.values().stream().map(root -> f.blue.advanced().auditDocument(root.id()).epoch()).toList());
            assertEquals(List.of(0L, 0L, 1L, 2L), roots.values().stream().map(root -> observed(f, root)).toList());
            assertEquals(originalB.invocationIdentity(), f.control.capture(b.id(), entry.blueId(), null).invocationIdentity());
            // when
            var first = probe.publishExpandedOriginal(b.id(), d.id(), c.id(), a.id(), entry.blueId(), originalB.invocationIdentity());
            verify(first);
            boolean joined = false;
            for (int i = 0; i < 64; i++) {
                var next = probe.publishNext(b.id(), c.id()); verify(next);
                System.out.println("DIAMOND_ACQUISITION step=" + i + " cause=" + next.input().cause().getClass().getSimpleName()
                        + " gas=" + next.actual().totalGas() + " owners=" + next.actual().rootedProjection().ownedDocumentIds()
                        + " observed=" + roots.values().stream().map(root -> observed(f, root)).toList());
                if (f.blue.advanced().auditManagedCatchUpPlans(c.id()).stream().allMatch(value -> value.status() == ManagedCatchUpStatus.COMPLETE)) {
                    joined = true; break;
                }
                var stillFrozen = safety.registeredInput(c.id()).snapshot().managedDocument(sourceId);
                assertEquals(frozenSource.epoch(), stillFrozen.epoch()); assertEquals(frozenSource.blueId(), stillFrozen.blueId());
                assertEquals(plan.snapshotIdentity(), f.blue.advanced().auditManagedCatchUpPlans(c.id()).stream()
                        .filter(value -> value.planIdentity().equals(plan.planIdentity())).findFirst().orElseThrow().snapshotIdentity());
            }
            // then
            assertTrue(joined, "The original terminal must settle without executing a later source frontier");
            assertEquals(List.of(0L, 1L, 1L, 2L), roots.values().stream().map(root -> observed(f, root)).toList());
            // A's original LIVE has not been independently executed. The maintained
            // public selector must settle/reconcile it without repeating C.attach.
            boolean quiet = false;
            for (int i = 0; i < 32; i++) {
                var result = f.blue.processing().drain(new DrainBudget(1, 1));
                assertFalse(result.blocked(), String.valueOf(result.diagnostic()));
                if (result.quiescent()) { quiet = true; break; }
            }
            assertTrue(quiet);
            assertEquals(List.of(0L, 1L, 1L, 2L), roots.values().stream().map(root -> observed(f, root)).toList());
            assertEquals(1L, tokens(f, a, "C", "B")); assertEquals(1L, tokens(f, a, "C", "D"));
            assertEquals(1L, tokens(f, c, "B", "stop")); assertEquals(1L, tokens(f, c, "D", "stop"));
            int index = 0;
            for (var root : roots.values()) {
                var prefix = histories.get(index++); assertEquals(prefix, f.history(root).subList(0, prefix.size()));
                assertTrue(f.blue.advanced().auditManagedDocumentReadiness(root.id()).orElseThrow().ready());
                assertTrue(f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream().allMatch(value -> value.status() == ManagedCatchUpStatus.COMPLETE));
            }
            var settled = safety.publicationState();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(settled, safety.publicationState());
            assertTrue(f.blue.processing().drain(new DrainBudget(1, 1)).quiescent()); assertEquals(settled, safety.publicationState());
        }
    }

    private static void verify(RootedDiamondAcquisitionProbe.Calculation calculation) {
        assertTrue(calculation.published()); assertFalse(calculation.replayed());
        var actual = calculation.actual(); var reference = calculation.reference();
        assertTrue(actual.commits()); assertEquals(calculation.input().invocationIdentity(), actual.invocationIdentity());
        assertEquals(reference.invocationIdentity(), actual.invocationIdentity()); assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
        assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
        assertEquals(reference.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
        assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
        assertEquals(reference.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
        assertEquals(reference.totalGas(), actual.totalGas()); assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
    }
    private static void prefix(RootedSdkFixture f, DocumentHandle root, String operation, long time, String request) {
        var entry = f.append(root, TIMELINE, operation, time, request, true);
        assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(root, entry).entry(entry).disposition());
        for (int i = 0; i < 16; i++) { var next = f.blue.processing().processNext(root); assertFalse(next.blocked()); if (next.quiescent()) return; }
        fail("Exact prefix did not settle");
    }
    private static String attachment(String edge, DocumentHandle root) { return "edge: " + edge + "\nsource: {blueId: " + root.id().value() + "}"; }
    private static long observed(RootedSdkFixture f, DocumentHandle root) {
        return ((Number) ExactBlueValue.wrap(f.blue.advanced().auditDocument(root.id()).current()).scalarAt("/observed")).longValue();
    }
    private static long tokens(RootedSdkFixture f, DocumentHandle root, String to, String next) {
        return f.blue.advanced().auditManagedEpochs(root.id()).stream().flatMap(row -> row.emittedEvents().stream())
                .filter(event -> to.equals(event.exactEvent().scalarAt("/to")) && next.equals(event.exactEvent().scalarAt("/next"))).count();
    }
}
