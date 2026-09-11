package blue.coordination.sdk;

import blue.coordination.api.CoordinationException;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.DocumentId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Four genuine inputs introduce historical B inside A before C's late cycle-closing attachment. */
final class RootedHistoricalWitnessForwardingTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void sourceFirstCycleJoinDoesNotLoseTheHistoricalForwardingReaction() throws Exception {
        // given
        String firstRoot = "C";
        // when
        var observed = run(firstRoot);
        // then
        assertEquals(List.of(0L, 1L, 1L), observed);
    }

    @Test void incomingRootCalculationDistinguishesItsOwnerFromHistoricalWitnessB() throws Exception {
        // given
        String firstRoot = "B";
        // when
        var observed = run(firstRoot);
        // then
        assertEquals(List.of(0L, 1L, 1L), observed);
    }

    @Test void nonterminalSourceEventStillRequiresTheForwardingReaction() throws Exception {
        // given
        String firstRoot = "C";
        // when
        var observed = run(firstRoot, true);
        // then
        assertEquals(List.of(0L, 1L, 1L), observed);
    }

    private static List<Long> run(String firstRoot) throws Exception {
        return run(firstRoot, false);
    }

    private static List<Long> run(String firstRoot, boolean laterSourceStep) throws Exception {
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
            var checked = new LinkedHashSet<String>();
            var entries = new ArrayList<String>();
            var bToC = f.append(b, TIMELINE, "attach", 100L, attachment("c", c), true);
            entries.add(bToC.blueId());
            execute(f, b, bToC, roots, originals, checked, false);
            var independentB = state(f, b); var independentC = state(f, c);
            var aToB = f.append(a, TIMELINE, "attach", 150L, attachment("b", b), true);
            entries.add(aToB.blueId());
            execute(f, a, aToB, roots, originals, checked, false);
            assertEquals(independentB, state(f, b)); assertEquals(independentC, state(f, c));
            var selectedA = f.control.selectedView(a.id());
            assertTrue(selectedA.occurrences().stream().anyMatch(row -> row.sourceDocumentId().value().equals(a.id().value())
                    && row.sourcePath().equals("/peers/b") && row.active()));
            assertTrue(selectedA.occurrences().stream().anyMatch(row -> row.sourceDocumentId().value().equals(b.id().value())
                    && row.sourcePath().equals("/peers/c") && row.active()), "A must retain actual B -> C history");

            var emit = f.append(a, TIMELINE, "emit", 200L, "to: C\nnext: B", true);
            entries.add(emit.blueId());
            execute(f, a, emit, roots, originals, checked, false);
            assertEquals(independentB, state(f, b)); assertEquals(independentC, state(f, c));
            assertEquals(List.of(0L, 0L, 0L), counts(roots));
            assertToken(f, a, "C", "B");
            assertTrue(tokens(f, b).isEmpty()); assertTrue(tokens(f, c).isEmpty());
            long eventSourceEpoch = a.snapshot().epoch();
            var eventReceipt = f.blue.advanced().auditManagedEpochs(a.id()).stream()
                    .filter(row -> !row.emittedEvents().isEmpty()).findFirst().orElseThrow();
            assertEquals(eventSourceEpoch, eventReceipt.epoch());
            if (laterSourceStep) {
                var touch = f.append(a, TIMELINE, "touch", 250L, "{}", true);
                entries.add(touch.blueId());
                execute(f, a, touch, roots, originals, checked, false);
                assertEquals(eventSourceEpoch + 1L, a.snapshot().epoch(), "A250 is a genuine later numbered source step");
                assertEquals(1L, a.snapshot().longAt("/touches")); assertToken(f, a, "C", "B");
                assertEquals(independentB, state(f, b)); assertEquals(independentC, state(f, c));
                assertEquals(List.of(0L, 0L, 0L), counts(roots));
            }
            var prefixes = new LinkedHashMap<String, List<String>>();
            roots.forEach((name, root) -> prefixes.put(name, f.history(root)));

            var cToA = f.append(c, TIMELINE, "attach", 300L, attachment("a", a), true);
            entries.add(cToA.blueId());
            System.out.println("WITNESS_FORWARDING first=" + firstRoot + " eventSourceEpoch=" + eventSourceEpoch
                    + " frozenSourceEndpoint=" + a.snapshot().epoch() + " entries=" + entries
                    + " before=" + roots.entrySet().stream().map(row -> List.of(row.getKey(), compactState(f, row.getValue()))).toList());
            System.out.println("WITNESS_FORWARDING_B_BEFORE_JOIN " + diagnostic(() -> f.control.rootSelectionDescription(b.id())));
            execute(f, roots.get(firstRoot), cToA, roots, originals, checked, true);
            // Do not resubmit a consumed input after a legitimate SCC join. Ask each actual root for its next obligation.
            for (var root : roots.values()) settle(f, root, roots, originals, checked, true);
            roots.forEach((name, root) -> {
                var history = f.history(root); var prefix = prefixes.get(name);
                assertTrue(history.size() >= prefix.size());
                assertEquals(prefix, history.subList(0, prefix.size()), "Previously published source receipts remain immutable");
                assertReady(f, root);
            });
            assertActive(f, a, "/peers/b", b); assertActive(f, b, "/peers/c", c); assertActive(f, c, "/peers/a", a);
            System.out.println("WITNESS_FORWARDING complete first=" + firstRoot + " observed=" + counts(roots)
                    + " tokens=" + roots.entrySet().stream().map(row -> List.of(row.getKey(), eventEvidence(f, row.getValue()))).toList()
                    + " next=" + f.blue.advanced().auditNextProcessingSelection());
            System.out.println("WITNESS_FORWARDING_B_AFTER_JOIN " + diagnostic(() -> f.control.rootSelectionDescription(b.id())));
            assertToken(f, a, "C", "B"); assertToken(f, c, "B", "stop");
            assertTrue(tokens(f, b).isEmpty(), "B's authored next=stop reaction emits no token");
            assertEquals(List.of(0L, 1L, 1L), counts(roots),
                    "Historical B is not an excuse to drop the real B receiver's one required reaction");
            return counts(roots);
        }
    }

    private static String attachment(String edge, DocumentHandle child) {
        return "edge: " + edge + "\nsource: {blueId: " + child.id().value() + "}";
    }

    private static void execute(RootedSdkFixture f, DocumentHandle root, EntryHandle entry,
            Map<String, DocumentHandle> roots, List<ExactValue> originals, Set<String> checked, boolean closing) {
        var first = f.blue.processing().process(root, entry);
        assertEquals(EntryDisposition.APPLIED, first.entry(entry).disposition());
        assertFalse(first.blocked(), String.valueOf(first.diagnostic()));
        verifyCalculations(f, root, roots, originals, checked, closing);
        settle(f, root, roots, originals, checked, closing);
    }

    private static void settle(RootedSdkFixture f, DocumentHandle root, Map<String, DocumentHandle> roots,
            List<ExactValue> originals, Set<String> checked, boolean closing) {
        for (int step = 0; step < 64; step++) {
            var before = headsAndHistory(f, roots);
            String selected = closing ? diagnostic(() -> f.control.rootSelectionDescription(root.id())) : "prefix";
            DrainResult next;
            try {
                next = f.blue.processing().processNext(root);
            } catch (CoordinationException failure) {
                var after = headsAndHistory(f, roots);
                System.out.println("WITNESS_FORWARDING_PUBLICATION_FAILURE code=" + failure.code()
                        + " cause=" + (failure.getCause() == null ? "none" : failure.getCause().getClass().getSimpleName())
                        + " before=" + before + " after=" + after + " selectedBefore=" + selected
                        + " selectedAfter=" + diagnostic(() -> f.control.rootSelectionDescription(root.id()))
                        + " explicitC=" + diagnostic(() -> f.control.rootSelectionDescription(roots.get("C").id()))
                        + " canonical=" + diagnostic(() -> f.blue.advanced().auditNextProcessingSelection()));
                assertEquals(before, after, "Failed publication must not mutate any independently published head/history");
                throw failure;
            }
            assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
            verifyCalculations(f, root, roots, originals, checked, closing);
            if (next.quiescent()) { assertReady(f, root); return; }
        }
        fail("Four-input witness cycle did not settle within64 selections: " + root.id()
                + " local=" + f.control.localHistoryDescription(root.id()));
    }

    private static void verifyCalculations(RootedSdkFixture f, DocumentHandle root,
            Map<String, DocumentHandle> roots, List<ExactValue> originals, Set<String> checked, boolean closing) {
        for (var terminal : f.control.retainedTerminals(root.id())) {
            if (!checked.add(terminal.result().invocationIdentity())) continue;
            var resources = new ArrayList<>(originals);
            for (var source : roots.values()) for (var receipt : f.blue.advanced().auditManagedEpochs(source.id())) {
                resources.add(receipt.afterDocument().unwrap());
                receipt.sourceEntry().flatMap(TimelineEntrySnapshot::request).ifPresent(value -> resources.add(value.unwrap()));
                receipt.emittedEvents().forEach(event -> resources.add(event.exactEvent().unwrap()));
            }
            var actual = terminal.result();
            assertEquals(terminal.input().invocationIdentity(), actual.invocationIdentity(),
                    "Fresh input-only parity requires the exact executed input; a retry needs its retained closed envelope");
            var reference = RootedCalculationFixture.freshRootedReference(terminal.input(), resources);
            var ownerIds = Set.copyOf(actual.rootedProjection().ownedDocumentIds());
            var allIds = roots.values().stream().map(value -> new DocumentId(value.id().value())).collect(java.util.stream.Collectors.toSet());
            Set<DocumentId> entryOwners = Set.copyOf(actual.rootedProjection().context().entryOwners());
            System.out.println("WITNESS_FORWARDING_CALC selected=" + root.id() + " cause=" + terminal.input().cause().kind()
                    + " invocation=" + actual.invocationIdentity() + " inputInvocation=" + terminal.input().invocationIdentity()
                    + " entryOwners=" + entryOwners + " owners=" + ownerIds
                    + " gas=" + actual.totalGas() + " observed=" + actual.resultingDocuments().stream().map(row ->
                            java.util.Arrays.asList(row.documentId(), row.document().get("/observed"))).toList()
                    + " events=" + actual.publicEvents().stream().map(event -> java.util.Arrays.asList(event.publicRootDocumentId(),
                            event.event().get("/to"), event.event().get("/next"))).toList());
            if (terminal.input().cause() instanceof blue.language.processor.closure.ManagedRevisionCause revision) {
                var occurrence = terminal.input().snapshot().occurrences().stream()
                        .filter(row -> row.occurrenceIdentity().equals(revision.targetOccurrenceIdentity())).findFirst().orElseThrow();
                System.out.println("WITNESS_FORWARDING_IMPORT child=" + revision.childDocumentId()
                        + " from=" + revision.fromEpoch() + " to=" + revision.toEpoch()
                        + " appliedCursor=" + occurrence.pendingHistoricalEpoch()
                        + " selectedSourceEpoch=" + terminal.input().snapshot().managedDocument(revision.childDocumentId()).epoch()
                        + " successor=" + revision.successorRepresentationCause().isPresent());
            }
            assertTrue(reference.commits(), String.valueOf(reference.diagnostic()));
            if (!reference.outputClosureIdentity().equals(actual.outputClosureIdentity())) {
                System.out.println("WITNESS_FORWARDING_REFERENCE_DIFF actual=" + compactResult(actual)
                        + " freshRooted=" + compactResult(reference));
            }
            assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
            assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
            assertEquals(reference.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
            assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
            assertEquals(reference.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
            assertEquals(reference.totalGas(), actual.totalGas()); assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
            if (!closing) assertEquals(Set.of(new DocumentId(root.id().value())), ownerIds);
            else {
                assertTrue(ownerIds.containsAll(entryOwners), "Original entry ownership cannot shrink within a step");
                assertTrue(ownerIds.equals(Set.of(new DocumentId(root.id().value()))) || ownerIds.equals(allIds),
                        "The only possible live SCCs in this three-edge graph are a singleton or the complete ring");
            }
        }
    }

    private static List<Long> counts(Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> root.snapshot().longAt("/observed")).toList();
    }

    private static void assertReady(RootedSdkFixture f, DocumentHandle root) {
        var actual = f.blue.advanced().auditDocument(root.id());
        assertEquals(SessionStatus.READY, actual.status());
        assertEquals(actual.epoch(), root.snapshot().epoch()); assertEquals(actual.blueId(), root.snapshot().blueId());
        assertEquals(ExactBlueValue.wrap(actual.current()).json(), root.snapshot().exact().json());
    }

    private static void assertActive(RootedSdkFixture f, DocumentHandle owner, String path, DocumentHandle source) {
        var row = f.blue.advanced().auditManagedOccurrence(owner.id(), path).orElseThrow();
        assertTrue(row.active()); assertEquals(source.id(), row.targetDocumentId());
    }

    private static List<ManagedEventOccurrence> tokens(RootedSdkFixture f, DocumentHandle source) {
        return f.blue.advanced().auditManagedEpochs(source.id()).stream().flatMap(row -> row.emittedEvents().stream())
                .filter(event -> "Tutorial/Graph Token".equals(event.exactEvent().scalarAt("/kind"))).toList();
    }

    private static void assertToken(RootedSdkFixture f, DocumentHandle source, String to, String next) {
        var tokens = tokens(f, source); assertEquals(1, tokens.size(), source.id().toString());
        var token = tokens.get(0); assertEquals(source.id(), token.sourceDocumentId()); assertTrue(token.publicAtSource());
        assertEquals(to, token.exactEvent().scalarAt("/to")); assertEquals(next, token.exactEvent().scalarAt("/next"));
    }

    private static List<List<Object>> eventEvidence(RootedSdkFixture f, DocumentHandle source) {
        return f.blue.advanced().auditManagedEpochs(source.id()).stream().flatMap(row -> row.emittedEvents().stream())
                .map(event -> List.<Object>of(event.managedEventIdentity(), event.eventOccurrenceIdentity(), event.eventBlueId(),
                        event.sourceDocumentId(), event.ordinal(), event.eventOccurrenceOrdinal(), event.publicAtSource(), event.exactEvent().json())).toList();
    }

    private static List<Object> state(RootedSdkFixture f, DocumentHandle root) {
        var current = f.blue.advanced().auditDocument(root.id());
        return List.of(current.epoch(), current.blueId(), ExactBlueValue.wrap(current.current()).json(),
                f.history(root), eventEvidence(f, root), f.control.selectedView(root.id()).closureIdentity());
    }

    private static List<Object> compactState(RootedSdkFixture f, DocumentHandle root) {
        var current = f.blue.advanced().auditDocument(root.id());
        var history = f.history(root);
        return List.of(current.epoch(), current.blueId(), history.size(), history.get(history.size() - 1),
                root.snapshot().longAt("/observed"), f.control.selectedView(root.id()).closureIdentity());
    }

    private static Map<String, List<Object>> headsAndHistory(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        var states = new LinkedHashMap<String, List<Object>>();
        roots.forEach((name, root) -> {
            var current = f.blue.advanced().auditDocument(root.id());
            states.put(name, List.of(current.epoch(), current.blueId(), f.history(root)));
        });
        return states;
    }

    private static String diagnostic(java.util.function.Supplier<?> value) {
        try { return String.valueOf(value.get()); }
        catch (RuntimeException failure) { return failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
    }

    private static List<Object> compactResult(ClosureProcessResult result) {
        return List.of(result.invocationIdentity(), result.outputClosureIdentity(), result.totalGas(),
                result.rootedProjection().ownedDocumentIds(), result.resultingDocuments().stream().map(row ->
                        java.util.Arrays.asList(row.documentId(), row.epoch(), row.afterBlueId(), row.componentStateIdentity(),
                                row.document().get("/observed"))).toList(),
                result.occurrenceBindings().stream().map(row -> java.util.Arrays.asList(row.sourceDocumentId(),
                        row.sourcePath(), row.targetDocumentId(), row.expectedTargetBlueId(), row.active(),
                        row.activationGeneration(), row.pendingHistoricalEpoch(), row.pendingRepresentationCursor())).toList());
    }
}
