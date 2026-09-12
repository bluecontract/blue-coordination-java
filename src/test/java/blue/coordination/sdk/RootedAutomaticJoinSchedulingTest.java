package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Same authored requirements as the forwarding reduction, driven by the maintained global selector. */
final class RootedAutomaticJoinSchedulingTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void nonterminalForwardingUsesOriginalReceiverBeforeJointPublication() throws Exception { run(true); }

    @Test void terminalForwardingStillDeliversExactlyOneReaction() throws Exception { run(false); }

    @Test void descendantTokenDoesNotBubbleThroughADirectMemberChannel() throws Exception { run(true, Shape.CHAIN); }

    @Test void bothInteriorReceiversMustFinishTheirOriginalReactions() throws Exception { run(true, Shape.DIAMOND); }

    private enum Shape { THREE_NODE, CHAIN, DIAMOND }

    private void run(boolean nonterminal) throws Exception { run(nonterminal, Shape.THREE_NODE); }

    private void run(boolean nonterminal, Shape shape) throws Exception {
        boolean multiple = shape != Shape.THREE_NODE;
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var originals = new ArrayList<ExactValue>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : multiple ? List.of("A", "B", "D", "C") : List.of("A", "B", "C")) {
                String authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "witness-forwarding")
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored); originals.add(exact.unwrap());
                f.exact.put(exact.blueId(), exact.json()); roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var c = roots.get("C");
            var entries = new ArrayList<String>();
            if (multiple) {
                prefix(f, roots, roots.get("D"), "attach", 100, attachment("c", c), entries);
                prefix(f, roots, b, "attach", 125, shape == Shape.CHAIN
                        ? attachment("d", roots.get("D")) : attachment("c", c), entries);
            } else prefix(f, roots, b, "attach", 100, attachment("c", c), entries);
            prefix(f, roots, a, "attach", 150, attachment("b", b), entries);
            if (shape == Shape.DIAMOND) prefix(f, roots, a, "attach", 175, attachment("d", roots.get("D")), entries);
            prefix(f, roots, a, "emit", 200, "to: C\nnext: B", entries);
            if (multiple) prefix(f, roots, a, "emit", 210, "to: C\nnext: D", entries);
            if (nonterminal) prefix(f, roots, a, "touch", 250, "{}", entries);
            var beforeHistories = roots.values().stream().map(f::history).toList();
            var entry = f.append(c, TIMELINE, "attach", 300, attachment("a", a), true); entries.add(entry.blueId());
            if (nonterminal && !multiple) assertEquals(List.of("qCiBGwwixGUbYPrcGyAVapBmXZWxvpeDU9yjKmVjC6f",
                    "8UeTdMdjMkzjwSVwprqZijmD6r1Wr6mPEc1cDfG8YuS", "WH5QBGYkM7iMEuFVsPKJpnXNo1dk5BpArcTNoANQ8Da",
                    "ABgTJyzKMFhC5XAynikZFkuiWq5EStphvb18k5cgYa2N", "HgWUG5gsFmWCS84v3C7KE995QVACy6P5oNM8J4h8fTE5"), entries);
            var originalB = f.control.capture(b.id(), entry.blueId(), null);
            var originalD = multiple ? f.control.capture(roots.get("D").id(), entry.blueId(), null) : null;
            assertEquals(EntryDisposition.APPLIED,
                    measured(f, roots, () -> f.blue.processing().process(c, entry)).entry(entry).disposition());
            boolean stopped = false;
            for (int step = 0; step < 32; step++) {
                var before = state(f, roots);
                var next = measured(f, roots, () -> f.blue.processing().processNext(c));
                if (next.blocked()) {
                    var after = state(f, roots);
                    assertEquals(before.subList(0, roots.size() - 1), after.subList(0, roots.size() - 1), "C's own last prefix step cannot execute an independent receiver");
                    if (next.stats().committedTransitions() == 0L) assertEquals(before, after);
                    else assertEquals(1L, next.stats().committedTransitions(), "Readiness can report the next prerequisite after one real prefix commit");
                    var blocked = measured(f, roots, () -> f.blue.processing().processNext(c));
                    assertTrue(blocked.blocked());
                    assertEquals(0L, blocked.stats().committedTransitions()); assertEquals(0L, blocked.stats().gas());
                    assertEquals(after, state(f, roots), "The zero-progress blocked C call must change no head, history or plan");
                    stopped = true; break;
                }
                assertFalse(next.quiescent(), "The source terminal must preserve the unexecuted receiving-root obligation");
            }
            assertTrue(stopped);
            assertEquals(originalB.invocationIdentity(), f.control.capture(b.id(), entry.blueId(), null).invocationIdentity());
            if (multiple) {
                assertEquals(originalD.invocationIdentity(), f.control.capture(roots.get("D").id(), entry.blueId(), null).invocationIdentity());
                assertEquals(0L, observed(f, roots.get("D")));
            }
            assertEquals(0L, observed(f, b));
            assertEquals(multiple ? 2L : nonterminal ? 1L : 0L, observed(f, c));
            var pending = f.blue.advanced().auditManagedCatchUpPlans(c.id()).stream()
                    .filter(plan -> plan.status() == ManagedCatchUpStatus.RUNNING).findFirst().orElseThrow();
            assertEquals(pending.requiredThroughSourceEpoch(), pending.nextSourceEpoch());
            var checked = new LinkedHashSet<String>();
            boolean done = false;
            for (int step = 0; step < 128; step++) {
                var selected = f.blue.advanced().auditNextProcessingSelection();
                System.out.println("AUTOMATIC_JOIN shape=" + shape + " nonterminal=" + nonterminal + " step=" + step + " selected=" + selected
                        + " observed=" + roots.values().stream().map(root -> observed(f, root)).toList());
                var next = measured(f, roots, () -> f.blue.processing().drain(new DrainBudget(1, 1)));
                verify(f, roots, originals, checked);
                if (next.blocked() && shape == Shape.DIAMOND) {
                    var beforeDiagnostic = state(f, roots);
                    new blue.coordination.internal.RootedJoinPrerequisiteProbe(f.blue.advanced().rawEngine())
                            .describe(roots.values().stream().map(DocumentHandle::id).toList())
                            .forEach(line -> System.out.println("DIAMOND_JOIN " + line));
                    assertEquals(beforeDiagnostic, state(f, roots), "Diagnostic inspection must preserve every publication and plan");
                }
                assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
                if (next.quiescent()) { done = true; break; }
            }
            assertTrue(done, "The canonical selector must settle the same-cause prefix and joint application");
            assertEquals(1L, tokens(f, a, "C", "B")); assertEquals(1L, tokens(f, c, "B", "stop"));
            if (multiple) {
                assertEquals(1L, tokens(f, a, "C", "D")); assertEquals(1L, tokens(f, c, "D", "stop"));
                assertEquals(0L, f.blue.advanced().auditManagedEpochs(roots.get("D").id()).stream().flatMap(row -> row.emittedEvents().stream()).count());
            }
            assertEquals(0L, f.blue.advanced().auditManagedEpochs(b.id()).stream().flatMap(row -> row.emittedEvents().stream()).count());
            // The template omits includeDescendants: /peers/d/peers/c is not a
            // direct member of B's /peers channel. Only the diamond has two
            // direct receivers of C's newly emitted tokens.
            var expectedCounts = shape == Shape.CHAIN ? List.of(0L, 0L, 1L, 2L)
                    : multiple ? List.of(0L, 1L, 1L, 2L) : List.of(0L, 1L, 1L);
            assertEquals(expectedCounts, roots.values().stream().map(root -> observed(f, root)).toList());
            int index = 0;
            for (var root : roots.values()) {
                var prefix = beforeHistories.get(index++);
                assertEquals(prefix, f.history(root).subList(0, prefix.size()));
                assertTrue(f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream().allMatch(plan -> plan.status() == ManagedCatchUpStatus.COMPLETE));
                assertTrue(f.blue.advanced().auditManagedDocumentReadiness(root.id()).orElseThrow().ready());
            }
            var settled = state(f, roots);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(settled, state(f, roots));
            assertTrue(measured(f, roots, () -> f.blue.processing().drain(new DrainBudget(1, 1))).quiescent());
            assertEquals(settled, state(f, roots));
        }
    }

    private static void prefix(RootedSdkFixture f, Map<String, DocumentHandle> roots, DocumentHandle root,
            String operation, long time, String request, List<String> entries) {
        var entry = f.append(root, TIMELINE, operation, time, request, true); entries.add(entry.blueId());
        assertEquals(EntryDisposition.APPLIED,
                measured(f, roots, () -> f.blue.processing().process(root, entry)).entry(entry).disposition());
        for (int i = 0; i < 16; i++) {
            var next = measured(f, roots, () -> f.blue.processing().processNext(root));
            assertFalse(next.blocked()); if (next.quiescent()) return;
        }
        fail("Unchanged prefix did not settle");
    }

    /** Count newly retained owned revision receipts, not owners, attempts, old prefixes or response replays. */
    private static DrainResult measured(RootedSdkFixture f, Map<String, DocumentHandle> roots,
            java.util.function.Supplier<DrainResult> call) {
        var before = retained(f, roots);
        var drained = call.get();
        var after = retained(f, roots);
        assertTrue(after.keySet().containsAll(before.keySet()), "Processing cannot discard prior retained terminal evidence");
        long revisions = 0L;
        var evidence = new ArrayList<String>();
        for (var terminal : after.values()) {
            if (before.containsKey(terminal.identity())) continue;
            var result = terminal.result();
            assertEquals(terminal.input().invocationIdentity(), result.invocationIdentity());
            var owners = java.util.Objects.requireNonNull(result.rootedProjection()).ownedDocumentIds();
            var committed = result.managedTransitionReceipts().stream()
                    .filter(receipt -> owners.contains(receipt.documentId())).toList();
            if (!result.commits()) assertTrue(committed.isEmpty(), "A noncommitting terminal cannot publish owned PROCESS transitions");
            revisions = Math.addExact(revisions, committed.size());
            evidence.add("publication=" + terminal.identity() + " invocation=" + result.invocationIdentity()
                    + " ownedRevisionDocuments=" + committed.stream().map(receipt -> receipt.documentId().value()).toList());
        }
        assertEquals(revisions, drained.stats().committedTransitions(),
                "This-call transition count must equal new owned PROCESS transition receipts: " + evidence);
        return drained;
    }

    private static Map<String, RootedCalculationFixture.RetainedTerminal> retained(
            RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        var result = new LinkedHashMap<String, RootedCalculationFixture.RetainedTerminal>();
        for (var root : roots.values()) for (var terminal : f.control.retainedTerminals(root.id())) {
            var duplicate = result.putIfAbsent(terminal.identity(), terminal);
            if (duplicate != null) {
                assertEquals(duplicate.input().invocationIdentity(), terminal.input().invocationIdentity());
                assertEquals(duplicate.result().managedTransitionReceiptsIdentity(), terminal.result().managedTransitionReceiptsIdentity());
            }
        }
        return result;
    }

    private static String attachment(String edge, DocumentHandle source) { return "edge: " + edge + "\nsource: {blueId: " + source.id().value() + "}"; }
    private static long observed(RootedSdkFixture f, DocumentHandle root) {
        return ((Number) ExactBlueValue.wrap(f.blue.advanced().auditDocument(root.id()).current()).scalarAt("/observed")).longValue();
    }
    private static List<Object> state(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> (Object) List.of(f.blue.advanced().auditDocument(root.id()).epoch(),
                f.blue.advanced().auditDocument(root.id()).blueId(), f.history(root),
                f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream().map(plan -> plan.snapshotIdentity()).toList())).toList();
    }
    private static long tokens(RootedSdkFixture f, DocumentHandle root, String to, String next) {
        return f.blue.advanced().auditManagedEpochs(root.id()).stream().flatMap(row -> row.emittedEvents().stream())
                .filter(event -> to.equals(event.exactEvent().scalarAt("/to")) && next.equals(event.exactEvent().scalarAt("/next"))).count();
    }
    private static void verify(RootedSdkFixture f, Map<String, DocumentHandle> roots, List<ExactValue> originals, Set<String> checked) {
        for (var root : roots.values()) for (var terminal : f.control.retainedTerminals(root.id())) {
            var actual = terminal.result(); if (!checked.add(actual.invocationIdentity())) continue;
            var resources = new ArrayList<>(originals);
            for (var source : roots.values()) for (var receipt : f.blue.advanced().auditManagedEpochs(source.id())) {
                resources.add(receipt.afterDocument().unwrap()); receipt.emittedEvents().forEach(event -> resources.add(event.exactEvent().unwrap()));
                receipt.sourceEntry().flatMap(TimelineEntrySnapshot::request).ifPresent(value -> resources.add(value.unwrap()));
            }
            assertEquals(terminal.input().invocationIdentity(), actual.invocationIdentity());
            var reference = RootedCalculationFixture.freshRootedReference(terminal.input(), resources);
            assertTrue(reference.commits()); assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
            assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
            assertEquals(reference.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
            assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
            assertEquals(reference.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
            assertEquals(reference.totalGas(), actual.totalGas()); assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
        }
    }
}
