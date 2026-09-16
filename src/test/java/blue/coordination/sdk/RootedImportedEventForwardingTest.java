package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Acyclic reduction of the authored Graph Token forwarding rule; no source evidence is fabricated. */
final class RootedImportedEventForwardingTest {
    private static final String TIMELINE = "import-forwarding/alice";

    @Test void aPublishedHistoricalReactionRemainsReceivableInTheIncomingRootsOwnCalculation() throws Exception {
        // given
        boolean sourceFirst = true;
        // when
        var observed = run(sourceFirst);
        // then
        assertEquals(List.of(0L, 1L, 1L), observed);
    }

    @Test void aBorrowedHistoricalReactionRoutesLocallyWithoutPublishingTheBorrowedSource() throws Exception {
        // given
        boolean sourceFirst = false;
        // when
        var observed = run(sourceFirst);
        // then
        assertEquals(List.of(0L, 1L, 1L), observed);
    }

    private static List<Long> run(boolean sourceFirst) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var originals = new ArrayList<ExactValue>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "C")) {
                String authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "import-forwarding")
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored);
                originals.add(exact.unwrap());
                f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var c = roots.get("C");
            var checked = new LinkedHashSet<String>();
            var attachC = f.append(b, TIMELINE, "attach", 100L,
                    "edge: c\nsource: {blueId: " + c.id().value() + "}", true);
            processAndSettle(f, b, attachC, roots, originals, checked);
            assertEquals(c.id(), f.blue.advanced().auditManagedOccurrence(b.id(), "/peers/c").orElseThrow().targetDocumentId());
            assertTrue(f.blue.advanced().auditManagedOccurrence(b.id(), "/peers/c").orElseThrow().active());
            assertEquals(List.of(0L, 0L, 0L), counts(roots));

            // A has one committed event, but neither observer could receive it before the new edge.
            var emit = f.append(a, TIMELINE, "emit", 200L, "to: C\nnext: B", true);
            processAndSettle(f, a, emit, roots, originals, checked);
            assertEquals(List.of(0L, 0L, 0L), counts(roots));
            assertTokens(f, a, "C", "B");
            assertTrue(events(f, b).isEmpty()); assertTrue(events(f, c).isEmpty());
            var aBefore = state(f, a);
            var bBefore = state(f, b);
            var cBefore = state(f, c);

            // Only B -> C -> A exists. There is no return path or shared publication owner.
            var attachA = f.append(c, TIMELINE, "attach", 300L,
                    "edge: a\nsource: {blueId: " + a.id().value() + "}", true);
            System.out.println("IMPORT_FORWARDING sourceFirst=" + sourceFirst + " entries="
                    + List.of(attachC.blueId(), emit.blueId(), attachA.blueId()));
            if (sourceFirst) {
                processAndSettle(f, c, attachA, roots, originals, checked);
                assertEquals(bBefore, state(f, b), "C-only historical processing cannot publish its incoming observer B");
                assertEquals(1L, c.snapshot().longAt("/observed"));
                assertTokens(f, c, "B", "stop");
                var publishedC = state(f, c);
                processAndSettle(f, b, attachA, roots, originals, checked);
                assertEquals(publishedC, state(f, c), "B's local dependency calculation cannot republish authoritative C");
            } else {
                processAndSettle(f, b, attachA, roots, originals, checked);
                assertEquals(cBefore, state(f, c), "Borrowed C work must remain local to B's calculation");
                assertEquals(1L, b.snapshot().longAt("/observed"), "The authored B reaction must occur in B's own view");
                var publishedB = state(f, b);
                processAndSettle(f, c, attachA, roots, originals, checked);
                assertEquals(publishedB, state(f, b), "Publishing C later must not replay B's already handled reaction");
            }
            assertEquals(aBefore, state(f, a), "Historical import never reruns or republishes A's source event");
            assertTokens(f, a, "C", "B"); assertTokens(f, c, "B", "stop");
            assertTrue(events(f, b).isEmpty(), "B receives next=stop and emits nothing");
            System.out.println("IMPORT_FORWARDING complete sourceFirst=" + sourceFirst + " observed=" + counts(roots)
                    + " Cevents=" + eventEvidence(f, c) + " Bcursor="
                    + f.blue.advanced().auditManagedOccurrence(b.id(), "/peers/c"));
            assertEquals(List.of(0L, 1L, 1L), counts(roots),
                    "The maintained handler increments the addressed receiver exactly once, including B's own reaction");
            roots.values().forEach(root -> assertReady(f, root));
            var beforeRestart = roots.values().stream().map(root -> state(f, root)).toList();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(beforeRestart, roots.values().stream().map(root -> state(f, root)).toList());
            var settled = f.blue.processing().processNext(b);
            assertFalse(settled.blocked(), String.valueOf(settled.diagnostic()));
            assertEquals(beforeRestart, roots.values().stream().map(root -> state(f, root)).toList(),
                    "Reopened independent B must not duplicate its completed reaction");
            return counts(roots);
        }
    }

    private static void processAndSettle(RootedSdkFixture f, DocumentHandle root, EntryHandle entry,
            Map<String, DocumentHandle> roots, List<ExactValue> originals, Set<String> checked) {
        var first = f.blue.processing().process(root, entry);
        assertEquals(EntryDisposition.APPLIED, first.entry(entry).disposition());
        assertFalse(first.blocked(), String.valueOf(first.diagnostic()));
        verifyNewCalculations(f, root, roots, originals, checked);
        for (int step = 0; step < 32; step++) {
            var next = f.blue.processing().processNext(root);
            assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
            verifyNewCalculations(f, root, roots, originals, checked);
            if (next.quiescent()) { assertReady(f, root); return; }
        }
        fail("Selected root did not settle within 32 canonical follow-up selections: " + root.id()
                + " local=" + f.control.localHistoryDescription(root.id()));
    }

    private static void verifyNewCalculations(RootedSdkFixture f, DocumentHandle root,
            Map<String, DocumentHandle> roots, List<ExactValue> originals, Set<String> checked) {
        for (var terminal : f.control.retainedTerminals(root.id())) {
            if (!checked.add(terminal.result().invocationIdentity())) continue;
            // Exact authored requests and genuinely published source receipts supply missing cause values.
            // The fresh reference has no access to this host's provider, sessions, or cached calculation.
            var resources = new ArrayList<>(originals);
            for (var source : roots.values()) for (var receipt : f.blue.advanced().auditManagedEpochs(source.id())) {
                resources.add(receipt.afterDocument().unwrap());
                receipt.sourceEntry().flatMap(TimelineEntrySnapshot::request).ifPresent(value -> resources.add(value.unwrap()));
                receipt.emittedEvents().forEach(event -> resources.add(event.exactEvent().unwrap()));
            }
            ClosureProcessResult reference = RootedCalculationFixture.materializedReference(terminal.input(), resources);
            var actual = terminal.result();
            System.out.println("IMPORT_FORWARDING_CALC root=" + root.id() + " cause=" + terminal.input().cause().kind()
                    + " invocation=" + actual.invocationIdentity() + " gas=" + actual.totalGas()
                    + " owners=" + actual.rootedProjection().ownedDocumentIds()
                    + " observed=" + actual.resultingDocuments().stream().map(row -> java.util.Arrays.asList(row.documentId(),
                            row.document().get("/observed"))).toList()
                    + " events=" + actual.publicEvents().stream().map(event -> java.util.Arrays.asList(event.publicRootDocumentId(),
                            event.event().get("/to"), event.event().get("/next"))).toList());
            assertTrue(reference.commits(), String.valueOf(reference.diagnostic()));
            assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
            assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
            assertEquals(reference.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
            assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
            assertEquals(reference.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
            assertEquals(reference.totalGas(), actual.totalGas());
            assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
            assertEquals(Set.of(new blue.language.processor.closure.DocumentId(root.id().value())),
                    Set.copyOf(actual.rootedProjection().ownedDocumentIds()), "One-way descendants are not owned publications");
        }
    }

    private static List<Long> counts(Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> root.snapshot().longAt("/observed")).toList();
    }

    private static void assertReady(RootedSdkFixture f, DocumentHandle root) {
        var actual = f.blue.advanced().auditDocument(root.id());
        assertEquals(SessionStatus.READY, actual.status());
        assertEquals(actual.epoch(), root.snapshot().epoch());
        assertEquals(actual.blueId(), root.snapshot().blueId());
        assertEquals(ExactBlueValue.wrap(actual.current()).json(), root.snapshot().exact().json());
    }

    private static List<ManagedEventOccurrence> events(RootedSdkFixture f, DocumentHandle source) {
        return f.blue.advanced().auditManagedEpochs(source.id()).stream().flatMap(row -> row.emittedEvents().stream()).toList();
    }

    private static void assertTokens(RootedSdkFixture f, DocumentHandle source, String to, String next) {
        var events = events(f, source);
        assertEquals(1, events.size(), source.id().toString());
        var event = events.get(0);
        assertEquals(source.id(), event.sourceDocumentId()); assertTrue(event.publicAtSource());
        assertEquals("Tutorial/Graph Token", event.exactEvent().scalarAt("/kind"));
        assertEquals(to, event.exactEvent().scalarAt("/to"));
        assertEquals(next, event.exactEvent().scalarAt("/next"));
    }

    private static List<List<Object>> eventEvidence(RootedSdkFixture f, DocumentHandle source) {
        return events(f, source).stream().map(event -> List.<Object>of(event.managedEventIdentity(),
                event.eventOccurrenceIdentity(), event.eventBlueId(), event.sourceDocumentId(), event.ordinal(),
                event.eventOccurrenceOrdinal(), event.publicAtSource(), event.exactEvent().json())).toList();
    }

    private static List<Object> state(RootedSdkFixture f, DocumentHandle root) {
        var current = f.blue.advanced().auditDocument(root.id());
        return List.of(current.epoch(), current.blueId(), ExactBlueValue.wrap(current.current()).json(),
                f.history(root), eventEvidence(f, root), f.control.selectedView(root.id()).closureIdentity());
    }
}
