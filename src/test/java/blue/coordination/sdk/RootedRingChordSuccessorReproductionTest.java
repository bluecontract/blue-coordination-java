package blue.coordination.sdk;

import blue.coordination.api.ProcessingSelection;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.RootedTerminalSuccessorProbe;
import blue.coordination.internal.CoordinationTestControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The first five literal public test_graphs.py ring/chord operations, without HTTP or SQL. */
final class RootedRingChordSuccessorReproductionTest {
    private static final String NAMESPACE = "graph-b2615e1b9c0b";
    private static final String TIMELINE = NAMESPACE + "/alice";
    private static final List<String> ORIGINALS = List.of(
            "8iTWnqH7qwyV25XdoU1kjH1i2M41ftMucZQgsrCyKtVD",
            "8j6SpUkdPk5PbXE7YhE5SWtSJ2ByPvDS6JENTEX3k6TH",
            "6Prao1xQfK6n2nZeEVkLAuTsZ2NoCwoiqruXV5JgBg2s");
    private static final List<String> ENTRIES = List.of(
            "B35giqhpA777qLuXrkH3M4eTcwqueePc6Ht3nv8GVeBf",
            "3izRSQtmcdJ6jZgoL1w6opW37LM5zqFCszKsgqstVzvm",
            "AVP8bXhWhthsMRwDaDXt3Cim3yaArBEN1jAJVvuvao1d",
            "DGdRbQKjfw21C7feWRjcuKGQRkSGsqJDPRitRQVbpGSr",
            "A9vSo7SjYooU1nEoK4xA9PKnQALYCULVFWAGgc5Fz2gQ");

    @Test void savedOriginalChordSelectsTheSameTerminalSuccessorItWillCapture() throws Exception {
        // given
        boolean restartBeforeTerminal = false;
        // when
        var completed = run(restartBeforeTerminal);
        // then
        assertEquals(3, completed.heads().size());
    }

    @Test void selectedChordSuccessorSurvivesRestartAndDoesNotChaseItsOwnLaterHeads() throws Exception {
        // given
        boolean restartBeforeTerminal = true;
        // when
        var completed = run(restartBeforeTerminal);
        // then
        assertEquals(3, completed.histories().size());
    }

    private record Outcome(List<List<String>> histories, List<String> heads) { }

    private static Outcome run(boolean restartBeforeTerminal) throws Exception {
        // given: the maintained template is byte-identical to the captured MyOS
        // input after the owner-channel name and literal namespace substitution.
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var authored = new LinkedHashMap<String, String>();
            String template = RootedSdkFixture.resource("node-graph.template.json").replace("\"owner\"", "\"ownerChannel\"");
            for (String name : List.of("A", "B", "C")) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", NAMESPACE)
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(source);
                assertEquals(ORIGINALS.get(authored.size()), exact.blueId());
                f.exact.put(exact.blueId(), exact.json());
                authored.put(name, source);
            }
            authored.forEach((name, source) -> roots.put(name, f.startYaml(source, TIMELINE)));
            assertEquals(ORIGINALS, roots.values().stream().map(root -> root.id().value()).toList());
            var trace = new ArrayList<String>();

            // when: one shared Timeline, original authored child references,
            // exact-version operation calls, and ordinary canonical lane choice.
            invoke(f, roots, trace, 0, "A", "attach", attachment("b", ORIGINALS.get(1)), false);
            invoke(f, roots, trace, 1, "B", "attach", attachment("c", ORIGINALS.get(2)), false);
            invoke(f, roots, trace, 2, "C", "attach", attachment("a", ORIGINALS.get(0)), false);
            invoke(f, roots, trace, 3, "A", "emit", "{\"to\":\"C\",\"next\":\"B\"}", false);
            assertEquals(List.of(0L, 1L, 1L), roots.values().stream()
                    .map(root -> root.snapshot().longAt("/observed")).toList());
            var eventsBeforeChord = events(f, roots);
            invoke(f, roots, trace, 4, "A", "attach", attachment("c", ORIGINALS.get(2)), restartBeforeTerminal);

            // then: attachment imports retained reactions, never re-emits source
            // events as new source events; the new occurrence actually settles.
            assertTrue(f.blue.advanced().auditManagedOccurrence(roots.get("A").id(), "/peers/c").orElseThrow().active());
            assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
            assertAllReady(f, roots);
            assertEquals(eventsBeforeChord, events(f, roots));
            assertTrue(trace.stream().anyMatch(line -> line.startsWith("CHORD_C7 ")),
                    "The focused reproduction must reach the actual failing terminal source work: " + trace);
            var histories = roots.values().stream().map(f::history).toList();
            var heads = roots.values().stream().map(root -> root.snapshot().blueId()).toList();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
            assertAllReady(f, roots);
            assertEquals(histories, roots.values().stream().map(f::history).toList());
            assertEquals(heads, roots.values().stream().map(root -> root.snapshot().blueId()).toList());
            assertEquals(eventsBeforeChord, events(f, roots));
            return new Outcome(histories, heads);
        }
    }

    private static String attachment(String edge, String source) {
        return "{\"edge\":\"" + edge + "\",\"source\":{\"blueId\":\"" + source + "\"}}";
    }

    private static void assertAllReady(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        assertEquals(3, roots.size());
        roots.forEach((name, root) -> {
            var current = f.blue.advanced().auditDocument(root.id());
            var ready = root.snapshot();
            assertEquals(SessionStatus.READY, current.status(), name + " must actually be READY");
            assertTrue(ready.ready());
            assertEquals(current.epoch(), ready.epoch(), name + " must not expose an older READY epoch");
            assertEquals(current.blueId(), ready.blueId(), name + " current and READY heads must agree");
            assertEquals(ExactBlueValue.wrap(current.current()).json(), ready.exact().json(),
                    name + " current and READY exact bodies must agree");
        });
    }

    private static List<List<List<Object>>> events(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> f.blue.advanced().auditManagedEpochs(root.id()).stream()
                .flatMap(receipt -> receipt.emittedEvents().stream()).map(event -> List.<Object>of(
                        event.managedEventIdentity(), event.eventOccurrenceIdentity(), event.eventBlueId(),
                        event.sourceDocumentId(), event.ordinal(), event.eventOccurrenceOrdinal(),
                        event.publicAtSource(), event.exactEvent().json())).toList()).toList();
    }

    private static void invoke(RootedSdkFixture f, Map<String, DocumentHandle> roots, List<String> trace,
            int ordinal, String target, String operation, String request, boolean restartBeforeTerminal) {
        var entry = f.blue.operations().on(roots.get(target)).from(f.timelines.get(TIMELINE))
                .call(operation).through("ownerChannel").requestYaml(request).submit();
        var exact = f.blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow();
        assertEquals(ENTRIES.get(ordinal), entry.blueId(), "Must reproduce the actual submitted input before diagnosing SDK selection");
        assertEquals(1_800_000_000_000_001L + ordinal, exact.timestampMicros());
        assertEquals(ordinal == 0 ? java.util.Optional.empty() : java.util.Optional.of(ENTRIES.get(ordinal - 1)),
                exact.previousEntryBlueId());
        EntryResult firstApplied = null;
        String frozenTail = null;
        boolean tailApplied = false;
        for (int step = 0; step < 64; step++) {
            var selected = f.blue.advanced().auditNextProcessingSelection();
            if (selected.kind() == ProcessingSelection.Kind.NONE) {
                assertNotNull(firstApplied, "Original exact entry must have one APPLIED result: " + trace);
                if (frozenTail != null) assertTrue(tailApplied, "The captured representation tail must actually be consumed");
                return;
            }
            var histories = roots.values().stream().map(f::history).toList();
            var heads = roots.values().stream().map(root -> {
                var snapshot = f.blue.advanced().auditDocument(root.id());
                return List.<Object>of(snapshot.epoch(), snapshot.blueId(), ExactBlueValue.wrap(snapshot.current()).json());
            }).toList();
            var work = selected.managedEpochApplicationWork().orElse(null);
            Runnable obsoleteCapture = null;
            if (ordinal == 4 && work != null && work.consumerDocumentId().equals(roots.get("A").id())
                    && work.sourceDocumentId().equals(roots.get("C").id()) && work.sourceEpoch() == 7L
                    && work.targetPath().equals("/peers/c") && work.representationCause().isEmpty()) {
                var observation = RootedTerminalSuccessorProbe.inspect(f.blue.advanced().rawEngine(), work);
                trace.add("CHORD_C7 " + observation);
                System.out.println(trace.get(trace.size() - 1));
                assertEquals(observation.workSuccessor(), observation.consumerSuccessor());
                assertEquals(observation.workSuccessor(), observation.capturedSuccessor());
                for (var invalid : RootedTerminalSuccessorProbe.invalidStagedBindings(f.blue.advanced().rawEngine(), work))
                    assertThrows(IllegalArgumentException.class, invalid::run,
                            "A different result, missing/extra owner or wrong source head is not a staged publication view");
                frozenTail = work.successorRepresentationCause().orElseThrow().targetPositionIdentity();
                if (restartBeforeTerminal) {
                    CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                    var reopened = f.blue.advanced().auditNextProcessingSelection();
                    assertEquals(selected.kind(), reopened.kind());
                    assertEquals(selected.rootedRetainedRoot(), reopened.rootedRetainedRoot());
                    assertEquals(work.workIdentity(), reopened.managedEpochApplicationWork().orElseThrow().workIdentity());
                    assertEquals(observation, RootedTerminalSuccessorProbe.inspect(f.blue.advanced().rawEngine(),
                            reopened.managedEpochApplicationWork().orElseThrow()));
                    assertEquals(histories, roots.values().stream().map(f::history).toList());
                }
                obsoleteCapture = RootedTerminalSuccessorProbe.capturedFence(f.blue.advanced().rawEngine(), roots.get("A").id());
                // Keep the production capturer and exact-work guard intact. The
                // observed selectors are diagnostic only, never replacement work.
            }
            if (frozenTail != null && work != null && work.consumerDocumentId().equals(roots.get("A").id())
                    && work.sourceDocumentId().equals(roots.get("C").id()) && work.targetPath().equals("/peers/c")
                    && work.representationCause().isPresent()) {
                assertEquals(frozenTail, work.representationCause().orElseThrow().targetPositionIdentity(),
                        "Reconciliation publications must not extend the already captured target");
                tailApplied = true;
            }
            String label = ordinal + ":" + step + " " + selected.kind() + " " + (work == null ? "" : work.workIdentity());
            trace.add(label);
            System.out.println("RING_CHORD " + label);
            DrainResult drained = assertDoesNotThrow(() -> {
                if (selected.kind() == ProcessingSelection.Kind.JOURNAL)
                    return f.blue.processing().drainJournal(new DrainBudget(1L, 1L));
                if (selected.rootedRetainedRoot().isPresent()) {
                    var id = selected.rootedRetainedRoot().orElseThrow();
                    var root = roots.values().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
                    return f.blue.processing().processNext(root);
                }
                return f.blue.processing().drainManagedEpochApplication(work.workIdentity());
            }, () -> "Canonical selected work must remain capturable: " + trace);
            assertFalse(drained.blocked(), () -> drained.diagnostic() + " " + trace);
            if (obsoleteCapture != null) assertThrows(IllegalArgumentException.class, obsoleteCapture::run,
                    "Publishing a successor must invalidate the prior selected-root fence");
            var actual = drained.find(entry);
            if (actual.isPresent()) {
                var result = actual.orElseThrow();
                if (result.disposition() == EntryDisposition.APPLIED) {
                    assertFalse(result.closures().isEmpty(), "APPLIED must retain its actual PROCESS receipt");
                    if (firstApplied == null) firstApplied = result;
                }
                else {
                    assertNotNull(firstApplied, () -> "Unexpected original disposition: " + result.disposition() + " " + trace);
                    assertEquals(entry, firstApplied.entry());
                    assertEquals(EntryDisposition.APPLIED, firstApplied.disposition());
                    assertEquals(ProcessingSelection.Kind.JOURNAL, selected.kind());
                    assertTrue(result.disposition() == EntryDisposition.NO_MATCH || result.disposition() == EntryDisposition.STALE,
                            () -> "Only an empty terminal transport acknowledgement is allowed: " + result);
                    if (result.disposition() == EntryDisposition.STALE)
                        assertEquals("STALE_TARGET_DOCUMENT", result.diagnostic().code());
                    assertTrue(result.closures().isEmpty(), "Later transport acknowledgement performs no repeated invocation");
                    assertTrue(result.publicEvents().isEmpty());
                    assertEquals(ProcessingStats.zero(), result.stats());
                    assertEquals(0L, drained.stats().gas());
                    assertEquals(0L, drained.stats().committedTransitions());
                    assertTrue(drained.stats().documentStepOrder().isEmpty());
                    assertTrue(drained.managedEpochApplications().isEmpty());
                    assertTrue(drained.managedEpochApplicationAttempts().isEmpty());
                    assertTrue(drained.managedEpochEvidenceFailures().isEmpty());
                    assertTrue(drained.rootedRetainedResults().isEmpty());
                    assertEquals(histories, roots.values().stream().map(f::history).toList());
                    assertEquals(heads, roots.values().stream().map(root -> {
                        var snapshot = f.blue.advanced().auditDocument(root.id());
                        return List.<Object>of(snapshot.epoch(), snapshot.blueId(), ExactBlueValue.wrap(snapshot.current()).json());
                    }).toList());
                    // The acknowledgement is a separate result, never a replacement
                    // for the first APPLIED closure's retained successful evidence.
                    for (var closure : firstApplied.closures()) {
                        var retained = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
                        assertTrue(retained.commits());
                        assertEquals(closure.stats().gas(), retained.totalGas());
                    }
                }
            }
            int index = 0;
            for (var root : roots.values()) {
                var prefix = histories.get(index++);
                assertEquals(prefix, f.history(root).subList(0, prefix.size()), "An immutable source receipt prefix must not change");
            }
        }
        fail("Fixture exceeded 64 exact selected turns, not a gas or wall-clock budget: " + trace);
    }
}
