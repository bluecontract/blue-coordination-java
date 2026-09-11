package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.coordination.internal.RootedCalculationFixture;
import blue.coordination.internal.RootedJoinPrefixProbe;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Diagnostic interleaving only: preserve B's original LIVE input and test pre-terminal owner alignment. */
final class RootedJoinPrefixInterleavingTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void originalLivePrefixMustAcquireExactOwnersBeforeTheTerminalJoin() throws Exception {
        // given: exactly the existing five-input nonterminal forwarding example
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var originals = new ArrayList<ExactValue>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "C")) {
                String authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "witness-forwarding")
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored);
                originals.add(exact.unwrap()); f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var c = roots.get("C");
            var entries = new ArrayList<String>();
            prefix(f, b, "attach", 100, attachment("c", c), entries);
            prefix(f, a, "attach", 150, attachment("b", b), entries);
            prefix(f, a, "emit", 200, "to: C\nnext: B", entries);
            prefix(f, a, "touch", 250, "{}", entries);
            assertEquals(6L, a.snapshot().epoch());
            var entry = f.append(c, TIMELINE, "attach", 300, attachment("a", a), true);
            entries.add(entry.blueId());
            assertEquals(List.of("qCiBGwwixGUbYPrcGyAVapBmXZWxvpeDU9yjKmVjC6f",
                    "8UeTdMdjMkzjwSVwprqZijmD6r1Wr6mPEc1cDfG8YuS", "WH5QBGYkM7iMEuFVsPKJpnXNo1dk5BpArcTNoANQ8Da",
                    "ABgTJyzKMFhC5XAynikZFkuiWq5EStphvb18k5cgYa2N", "HgWUG5gsFmWCS84v3C7KE995QVACy6P5oNM8J4h8fTE5"), entries);
            var originalB = f.control.capture(b.id(), entry.blueId(), null);
            apply(f, c, entry);
            for (int step = 0; step < 16; step++) {
                var next = f.control.registeredOwnedHistory(c.id());
                var input = f.control.captureRegisteredOwnedHistory(c.id());
                printPosition(f, c, input, next.workIdentity());
                if (terminalPosition(input)) break;
                assertTrue(next.sourceEpoch() <= 6L);
                assertFalse(f.blue.processing().processNext(c).blocked());
            }
            var terminalC = f.control.registeredOwnedHistory(c.id());
            assertEquals(6L, terminalC.sourceEpoch());
            assertEquals(a.id().value(), terminalC.sourceDocumentId().value());
            var cInput = f.control.captureRegisteredOwnedHistory(c.id());
            assertTrue(terminalPosition(cInput));
            var cCause = assertInstanceOf(ManagedRevisionCause.class, cInput.cause());
            assertEquals(5L, cCause.fromEpoch()); assertEquals(6L, cCause.toEpoch());
            assertEquals(1L, committedObserved(f, c)); assertEquals(0L, committedObserved(f, b));
            assertEquals(1L, f.blue.advanced().auditManagedEpochs(c.id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream())
                    .filter(event -> "B".equals(event.exactEvent().scalarAt("/to"))
                            && "stop".equals(event.exactEvent().scalarAt("/next"))).count());
            assertEquals(originalB.invocationIdentity(), f.control.capture(b.id(), entry.blueId(), null).invocationIdentity(),
                    "C's independent prefix must not replace B's original LIVE input");
            var sourceHeads = state(f, List.of(a, c));
            var sourcePlans = plans(f, roots);
            System.out.println("JOIN_PREFIX beforeB original=" + originalB.invocationIdentity() + " Cwork="
                    + terminalC.workIdentity() + " heads=" + sourceHeads + " plans=" + sourcePlans
                    + " selection=" + f.control.rootSelectionDescription(b.id()));

            // when: public exact-input processing, then ordinary local steps; no direct store/fence mutation
            apply(f, b, entry);
            for (int step = 0; step < 16; step++) {
                var next = f.control.localHistoryWork(b.id());
                var input = f.control.captureLocalHistory(b.id());
                printPosition(f, b, input, next.workIdentity());
                if (terminalPosition(input)) break;
                assertTrue(next.sourceEpoch() <= 6L);
                var before = state(f, new ArrayList<>(roots.values()));
                try {
                    var result = f.blue.processing().processNext(b);
                    assertFalse(result.blocked(), String.valueOf(result.diagnostic()));
                } catch (RuntimeException failure) {
                    System.out.println("JOIN_PREFIX blockedAt=" + f.control.rootSelectionDescription(b.id())
                            + " failure=" + failure + " plans=" + plans(f, roots));
                    assertEquals(before, state(f, new ArrayList<>(roots.values())), "Failed prefix publication is atomic");
                    throw failure;
                }
            }
            assertEquals(6L, f.control.localHistoryWork(b.id()).sourceEpoch());
            var beforeProbe = state(f, new ArrayList<>(roots.values()));
            var probe = new RootedJoinPrefixProbe(f.blue.advanced().rawEngine()).inspect(b.id());
            System.out.println("JOIN_PREFIX terminal " + probe.detail() + " plans=" + plans(f, roots)
                    + " committedObserved=" + roots.values().stream().map(root -> committedObserved(f, root)).toList());

            // then: diagnose genuine calculated ownership against exact current sources, not a rewritten result
            var bCause = assertInstanceOf(ManagedRevisionCause.class, probe.input().cause());
            assertTrue(terminalPosition(probe.input()));
            assertEquals(5L, bCause.fromEpoch()); assertEquals(6L, bCause.toEpoch());
            assertEquals(a.id().value(), bCause.childDocumentId().value());
            assertEquals(roots.values().stream().map(root -> root.id().value()).collect(java.util.stream.Collectors.toSet()),
                    probe.result().rootedProjection().ownedDocumentIds().stream().map(id -> id.value())
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(beforeProbe, state(f, new ArrayList<>(roots.values())), "Inspection never publishes");
            assertEquals(sourceHeads, state(f, List.of(a, c)), "B's nonterminal local prefix cannot publish A or C");
            assertEquals(sourcePlans, plans(f, roots), "This probe cannot settle C's separately registered work");
            var resources = new ArrayList<>(originals);
            for (var root : roots.values()) for (var receipt : f.blue.advanced().auditManagedEpochs(root.id())) {
                resources.add(receipt.afterDocument().unwrap());
                receipt.emittedEvents().forEach(event -> resources.add(event.exactEvent().unwrap()));
                receipt.sourceEntry().flatMap(TimelineEntrySnapshot::request).ifPresent(value -> resources.add(value.unwrap()));
            }
            var reference = RootedCalculationFixture.freshRootedReference(probe.input(), resources);
            assertTrue(reference.commits());
            assertEquals(reference.outputClosureIdentity(), probe.result().outputClosureIdentity());
            assertEquals(reference.publicEventsIdentity(), probe.result().publicEventsIdentity());
            assertEquals(reference.managedTransitionReceiptsIdentity(), probe.result().managedTransitionReceiptsIdentity());
            assertEquals(reference.checkpointWritesIdentity(), probe.result().checkpointWritesIdentity());
            assertEquals(reference.commitCompanion().companionIdentity(), probe.result().commitCompanion().companionIdentity());
            assertEquals(reference.gasTraceIdentity(), probe.result().gasTraceIdentity());
            assertEquals(reference.totalGas(), probe.result().totalGas());
            assertEquals(1L, committedObserved(f, b), "The original nonterminal forwarding work must reach primary B");
            assertTrue(probe.ownersCurrent(), probe.detail());
            // Deliberately do not publish the terminal: registered-plan reconciliation is a separate missing proof.
        }
    }

    private static void prefix(RootedSdkFixture f, DocumentHandle root, String operation, long time,
            String request, List<String> entries) {
        var entry = f.append(root, TIMELINE, operation, time, request, true); entries.add(entry.blueId());
        apply(f, root, entry);
        for (int step = 0; step < 16; step++) {
            var next = f.blue.processing().processNext(root); assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
            if (next.quiescent()) return;
        }
        fail("Prefix did not settle within16 real selections");
    }

    private static void apply(RootedSdkFixture f, DocumentHandle root, EntryHandle entry) {
        var result = f.blue.processing().process(root, entry);
        System.out.println("JOIN_PREFIX apply root=" + root.id() + " entry=" + entry.blueId()
                + " disposition=" + result.entry(entry).disposition() + " blocked=" + result.blocked()
                + " diagnostic=" + result.diagnostic());
        assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition());
        assertFalse(result.blocked(), String.valueOf(result.diagnostic()));
    }

    private static String attachment(String edge, DocumentHandle child) {
        return "edge: " + edge + "\nsource: {blueId: " + child.id().value() + "}";
    }

    private static List<Object> state(RootedSdkFixture f, List<DocumentHandle> roots) {
        return roots.stream().map(root -> {
            var current = f.blue.advanced().auditDocument(root.id());
            return (Object) List.of(root.id(), current.epoch(), current.blueId(),
                    f.history(root), f.control.selectedView(root.id()).closureIdentity());
        }).toList();
    }

    private static long committedObserved(RootedSdkFixture f, DocumentHandle root) {
        var exact = ExactBlueValue.wrap(f.blue.advanced().auditDocument(root.id()).current());
        return ((Number) exact.scalarAt("/observed")).longValue();
    }

    private static boolean terminalPosition(ClosureInvocationInput input) {
        if (input.cause() instanceof ManagedRepresentationCause representation) return representation.terminalPositionReached();
        var revision = assertInstanceOf(ManagedRevisionCause.class, input.cause());
        return revision.successorRepresentationCause().isEmpty()
                && revision.toEpoch() == input.snapshot().managedDocument(revision.childDocumentId()).epoch();
    }

    private static void printPosition(RootedSdkFixture f, DocumentHandle root, ClosureInvocationInput input, String work) {
        var current = f.blue.advanced().auditDocument(root.id());
        String occurrence = input.cause() instanceof ManagedRevisionCause revision ? revision.targetOccurrenceIdentity()
                : ((ManagedRepresentationCause) input.cause()).targetOccurrenceIdentity();
        var row = input.snapshot().occurrences().stream().filter(value -> value.occurrenceIdentity().equals(occurrence))
                .findFirst().orElseThrow();
        System.out.println("JOIN_PREFIX position root=" + root.id() + " work=" + work + " cause=" + input.cause().kind()
                + " terminal=" + terminalPosition(input) + " appliedCursor=" + row.pendingHistoricalEpoch()
                + " representationCursor=" + row.pendingRepresentationCursor()
                + " selectedSourceEpoch=" + input.snapshot().managedDocument(row.targetDocumentId()).epoch()
                + " committed=" + current.epoch() + ":" + current.blueId() + ":" + committedObserved(f, root)
                + " ready=" + root.snapshot().epoch() + ":" + root.snapshot().longAt("/observed"));
    }

    private static List<Object> plans(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        return roots.values().stream().flatMap(root -> f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream())
                .map(plan -> (Object) List.of(plan.planIdentity(), plan.snapshotIdentity(), plan.status(),
                        plan.nextSourceEpoch(), plan.requiredThroughSourceEpoch())).toList();
    }
}
