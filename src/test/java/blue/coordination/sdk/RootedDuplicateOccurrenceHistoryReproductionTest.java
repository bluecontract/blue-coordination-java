package blue.coordination.sdk;

import blue.coordination.api.ProcessingSelection;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.RootedTerminalSuccessorProbe;
import blue.coordination.internal.RootedImportedReceiptSourceEpochProbe;
import blue.coordination.internal.CoordinationTestControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The literal public ring/chord sequence, preserving all seven captured original input identities. */
final class RootedDuplicateOccurrenceHistoryReproductionTest {
    private static final String NAMESPACE = "graph-65f28857ca11";
    private static final String TIMELINE = NAMESPACE + "/alice";
    private static final List<String> ORIGINALS = List.of(
            "5UsQiTktgm3vFpjWUbTLuXGyA3dewRRxC8h26aTj6mNd",
            "EihmvquzkoM7d3QCcaYVRsYL8NPpjB5NcfvU4WNu82XY",
            "58qDgW6L9Fd23Rd2yrSDwKgU1cAApMFvezSd2JCDP5Nh");
    private static final List<String> ENTRIES = List.of(
            "BXz9PbjB5Wkv18wqYGEKoLZqv9h8P9AjKezDWN9whJZL",
            "DYuqhotPhbQmRmxPY653XD9R87MZdBqNKyBdMCico1s8",
            "ALNm3axXrwuLN2YGa5ruUjvxhXUUiUwh8uBVQtD1ZEKj",
            "98SxfDmEnka9nJpYSYg8xEnmweECLMzRW7DE9hKZYQMU",
            "7686PVmYt8P8wSEhYhmepPCUdEuGk6gx35SSPaGfw3XD",
            "QovoHZnLZtxFFieECbZdU5ACd3P95BMeHoxdrqJrsio",
            "Hy5ZR2DUjvQHe6D5Gu56wHT2NcUkJECYASGMf4AQPVaT");

    @Test void savedOriginalDuplicateOccurrenceKeepsItsFrozenRepresentationHistory() throws Exception {
        // given
        boolean restartBeforeTerminal = false;
        // when
        var completed = run(restartBeforeTerminal);
        // then
        assertEquals(3, completed.heads().size());
    }

    private record Outcome(List<List<String>> histories, List<String> heads) { }
    private static final class Trace extends ArrayList<String> {
        private final List<String> entries = new ArrayList<>();
        private String sourceAnchor;
        private int duplicateCarrierCount;
    }

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
            var trace = new Trace();

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
            // The first five calls above retain their existing positive controls.
            // There is no store restart between them and the two original follow-ups.
            var observedBeforeSix = roots.values().stream().map(root -> root.snapshot().longAt("/observed")).toList();
            invoke(f, roots, trace, 5, "C", "emit", "{\"to\":\"A\",\"next\":\"stop\"}", false);
            assertAllReady(f, roots);
            assertEquals(List.of(observedBeforeSix.get(0) + 1L, observedBeforeSix.get(1), observedBeforeSix.get(2)),
                    roots.values().stream().map(root -> root.snapshot().longAt("/observed")).toList());
            var eventsBeforeDuplicate = events(f, roots);
            assertEquals(eventsBeforeChord.subList(0, 2), eventsBeforeDuplicate.subList(0, 2));
            assertEquals(eventsBeforeChord.get(2), eventsBeforeDuplicate.get(2).subList(0, eventsBeforeChord.get(2).size()));
            assertEquals(eventsBeforeChord.get(2).size() + 1, eventsBeforeDuplicate.get(2).size());
            invoke(f, roots, trace, 6, "A", "attach", attachment("c2", ORIGINALS.get(2)), false);
            assertEquals(1, trace.duplicateCarrierCount, "Exactly one original C8 numbered carrier must be checked");
            assertTrue(trace.stream().anyMatch(line -> line.startsWith("DUPLICATE_C8 ")),
                    "The actual C8 duplicate-occurrence publication must be reached: " + trace);
            assertTrue(f.blue.advanced().auditManagedOccurrence(roots.get("A").id(), "/peers/c").orElseThrow().active());
            assertTrue(f.blue.advanced().auditManagedOccurrence(roots.get("A").id(), "/peers/c2").orElseThrow().active());
            assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
            assertAllReady(f, roots);
            assertEquals(eventsBeforeDuplicate, events(f, roots), "Attachment cannot duplicate or invent source events");
            assertNotNull(trace.sourceAnchor, "The actual C8 carrier must be checked at its original publication");
            assertEquals(8L, f.blue.advanced().auditDocument(roots.get("C").id()).epoch(),
                    "Imported receipt-event reference reencoding must not invent C9 before another real source input");
            completeOriginalSequence(f, roots, trace);
            var histories = roots.values().stream().map(f::history).toList();
            var heads = roots.values().stream().map(root -> root.snapshot().blueId()).toList();
            var finalEvents = events(f, roots);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(ProcessingSelection.Kind.NONE, f.blue.advanced().auditNextProcessingSelection().kind());
            assertAllReady(f, roots);
            assertEquals(histories, roots.values().stream().map(f::history).toList());
            assertEquals(heads, roots.values().stream().map(root -> root.snapshot().blueId()).toList());
            assertEquals(finalEvents, events(f, roots));
            assertTrue(f.history(roots.get("C")).contains(trace.sourceAnchor), "Original C8 receipt survives restart");
            return new Outcome(histories, heads);
        }
    }

    /** Exact business expectations from the maintained Python owner, not observed replacement goldens. */
    private static void completeOriginalSequence(RootedSdkFixture f, Map<String, DocumentHandle> roots, Trace trace) {
        var edges = new LinkedHashMap<String, Map<String, String>>();
        edges.put("A", new LinkedHashMap<>(Map.of("b", "B", "c", "C", "c2", "C")));
        edges.put("B", new LinkedHashMap<>(Map.of("c", "C")));
        edges.put("C", new LinkedHashMap<>(Map.of("a", "A")));
        assertEdges(f, roots, edges);
        emit(f, roots, trace, 7, "C", "A", List.of(2L, 0L, 0L));
        assertEdges(f, roots, edges);
        for (var removal : List.of(List.of("A", "c2"), List.of("A", "c"), List.of("C", "a"))) {
            var before = events(f, roots);
            invoke(f, roots, trace, trace.entries.size(), removal.get(0), "detach",
                    "{\"edge\":\"" + removal.get(1) + "\"}", false);
            edges.get(removal.get(0)).remove(removal.get(1));
            assertEquals(before, events(f, roots), "Detach cannot republish source events");
            assertAllReady(f, roots);
            assertEdges(f, roots, edges);
        }
        emit(f, roots, trace, 11, "A", "C", List.of(0L, 0L, 0L));
        assertEdges(f, roots, edges);
        var beforeEvents = events(f, roots);
        var beforeCounts = counts(roots);
        invoke(f, roots, trace, 12, "C", "attach", attachment("a", ORIGINALS.get(0)), false);
        edges.get("C").put("a", "A");
        assertAllReady(f, roots);
        assertEdges(f, roots, edges);
        assertEquals(List.of(beforeCounts.get(0), beforeCounts.get(1) + 1L, beforeCounts.get(2) + 2L), counts(roots));
        var afterEvents = events(f, roots);
        assertEquals(beforeEvents.subList(0, 2), afterEvents.subList(0, 2));
        assertEquals(beforeEvents.get(2), afterEvents.get(2).subList(0, beforeEvents.get(2).size()));
        assertEquals(beforeEvents.get(2).size() + 1, afterEvents.get(2).size());
        var forwarded = f.blue.advanced().auditManagedEpochs(roots.get("C").id()).stream()
                .flatMap(receipt -> receipt.emittedEvents().stream()).reduce((first, last) -> last).orElseThrow();
        assertEquals(roots.get("C").id(), forwarded.sourceDocumentId());
        assertEquals("Tutorial/Graph Token", forwarded.exactEvent().scalarAt("/kind"));
        assertEquals("B", forwarded.exactEvent().scalarAt("/to"));
        assertEquals("stop", forwarded.exactEvent().scalarAt("/next"));
        emit(f, roots, trace, 13, "A", "C", List.of(0L, 0L, 1L));
        assertEdges(f, roots, edges);
        assertEquals(14, trace.entries.size());
        assertEquals(2L, f.blue.advanced().auditManagedOccurrence(roots.get("C").id(), "/peers/a")
                .orElseThrow().activationGeneration());
    }

    private static List<Long> counts(Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> root.snapshot().longAt("/observed")).toList();
    }

    private static void emit(RootedSdkFixture f, Map<String, DocumentHandle> roots, Trace trace,
            int ordinal, String source, String to, List<Long> expectedDelta) {
        var beforeCounts = counts(roots);
        var beforeEvents = events(f, roots);
        invoke(f, roots, trace, ordinal, source, "emit", "{\"to\":\"" + to + "\",\"next\":\"stop\"}", false);
        assertAllReady(f, roots);
        assertEquals(java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> beforeCounts.get(index) + expectedDelta.get(index)).toList(), counts(roots));
        var afterEvents = events(f, roots);
        int index = 0;
        for (String name : roots.keySet()) {
            var before = beforeEvents.get(index);
            var after = afterEvents.get(index++);
            assertEquals(before, after.subList(0, before.size()));
            assertEquals(before.size() + (name.equals(source) ? 1 : 0), after.size(),
                    "Only the real source emits a new event");
        }
    }

    private static void assertEdges(RootedSdkFixture f, Map<String, DocumentHandle> roots,
            Map<String, Map<String, String>> edges) {
        roots.forEach((name, root) -> {
            var properties = root.snapshot().valueAt("/peers").copyNode().getProperties();
            assertEquals(edges.get(name).keySet(), properties == null ? java.util.Set.of() : properties.keySet());
            edges.get(name).forEach((path, source) -> {
                var occurrence = f.blue.advanced().auditManagedOccurrence(root.id(), "/peers/" + path).orElseThrow();
                assertTrue(occurrence.active());
                assertEquals(roots.get(source).id(), occurrence.targetDocumentId());
            });
        });
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

    private static void invoke(RootedSdkFixture f, Map<String, DocumentHandle> roots, Trace trace,
            int ordinal, String target, String operation, String request, boolean restartBeforeTerminal) {
        var entry = f.blue.operations().on(roots.get(target)).from(f.timelines.get(TIMELINE))
                .call(operation).through("ownerChannel").requestYaml(request).submit();
        var exact = f.blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow();
        assertEquals(ordinal, trace.entries.size());
        if (ordinal < ENTRIES.size()) assertEquals(ENTRIES.get(ordinal), entry.blueId(),
                "Must reproduce the actual submitted input before diagnosing SDK selection");
        assertEquals(1_800_000_000_000_001L + ordinal, exact.timestampMicros());
        assertEquals(ordinal == 0 ? java.util.Optional.empty() : java.util.Optional.of(trace.entries.get(ordinal - 1)),
                exact.previousEntryBlueId());
        trace.entries.add(entry.blueId());
        EntryResult firstApplied = null;
        String frozenTail = null;
        boolean tailApplied = false;
        // Reconnect deliberately imports the longer retained history (the public
        // owner previously needed 94 publications); this is not a gas/deadline change.
        int maximumTurns = ordinal < 7 ? 64 : 256;
        for (int step = 0; step < maximumTurns; step++) {
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
            boolean duplicateCarrier = ordinal == 6 && work != null && work.representationCause().isEmpty()
                    && work.sourceDocumentId().equals(roots.get("C").id())
                    && work.consumerDocumentId().equals(roots.get("A").id())
                    && work.targetPath().equals("/peers/c2") && work.sourceEpoch() == 8L
                    && work.successorRepresentationCause().isPresent();
            if (duplicateCarrier) {
                assertEquals(1, ++trace.duplicateCarrierCount, "The exact numbered C8 carrier cannot be processed twice");
                assertEquals(49L, f.blue.advanced().auditDocument(roots.get("A").id()).epoch());
                assertEquals(49L, work.expectedConsumerCommittedEpoch());
                trace.add("DUPLICATE_C8 " + work.workIdentity());
                System.out.println(trace.get(trace.size() - 1));
            }
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
            if (duplicateCarrier) {
                trace.sourceAnchor = RootedImportedReceiptSourceEpochProbe.verify(
                        f.blue.advanced().rawEngine(), work);
            }
            if (trace.sourceAnchor != null) assertTrue(f.history(roots.get("C")).contains(trace.sourceAnchor),
                    "An imported event must not replace or remove its original C8 receipt");
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
        fail("Fixture exceeded " + maximumTurns + " exact selected turns, not a gas or wall-clock budget: " + trace);
    }
}
