package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineAppendReceipt;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public contracts for exact append and environment-selected global drain. */
final class PublicTemporalFeederIntegrationTest {
    private static final DocumentId COUNTER = DocumentId.of("counter");
    private static final DocumentId COUNTER_A = DocumentId.of("counter-a");
    private static final DocumentId COUNTER_B = DocumentId.of("counter-b");
    private static final String ALICE_TIMELINE =
            "examples/clean-counter/alice";
    private static final String BOB_TIMELINE =
            "examples/clean-counter/bob";
    private static final long T0 = 1_700_000_000_000_000L;

    @Test
    void exactNodeAdmissionIsIdempotentAndRejectsClaimedIdentityForgery()
            throws Exception {
        try (CoordinationEngine source = CoordinationEngine.legacyInMemory();
                CoordinationEngine target = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline sourceAlice = source.registerTimeline(
                    ALICE_TIMELINE, "alice");
            TimelineEntry canonical = source.append(
                    sourceAlice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"));

            target.registerTimeline(ALICE_TIMELINE, "alice");
            target.exactValue("amount: 3");
            Node exactEntry = canonical.exactEvent().copyNode();

            // when
            TimelineAppendReceipt admitted =
                    target.appendTimelineEntry(exactEntry);
            assertTrue(admitted.stored());
            assertEquals(canonical.blueId(), admitted.entry().blueId());
            assertEquals(1, admitted.journalEntryCount());
            assertEquals(1, target.metrics().journalEntryCount());
            assertEquals(0L, target.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertTrue(target.metrics().nanos(
                    CoordinationMetrics.Phase.APPEND_TOTAL) > 0L);

            TimelineAppendReceipt duplicate = target.appendTimelineEntry(
                    canonical.exactEvent().copyNode());
            assertFalse(duplicate.stored());
            assertEquals(canonical.blueId(), duplicate.entry().blueId());
            assertEquals(1, duplicate.journalEntryCount());
            assertEquals(1, target.metrics().journalEntryCount());

            Node delegated = canonical.exactEvent().copyNode()
                    .properties("onBehalfOf",
                            new Node().blueId(canonical.blueId()));
            IllegalArgumentException unsupportedMandate = assertThrows(
                    IllegalArgumentException.class,
                    () -> target.appendTimelineEntry(delegated));
            assertTrue(unsupportedMandate.getMessage().contains(
                    "Mandate resolver"));
            assertEquals(1, target.metrics().journalEntryCount());

            Node missingDocument = canonical.exactEvent().copyNode();
            missingDocument.getProperties().get("message").properties(
                    "requireExactDocumentVersion", new Node().value(true));
            assertThrows(IllegalArgumentException.class,
                    () -> target.appendTimelineEntry(missingDocument));
            assertEquals(1, target.metrics().journalEntryCount());

            Node forged = canonical.exactEvent().copyNode();
            forged.getProperties()
                    .get("actor")
                    .getProperties()
                    .get("accountId")
                    .value("mallory");
            forged.blueId(canonical.blueId());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> target.appendTimelineEntry(forged));
            assertEquals(1, target.metrics().journalEntryCount());

            target.drain();
            TimelineAppendReceipt replayAfterDrain =
                    target.appendTimelineEntry(canonical.exactEvent().copyNode());

            // then
            assertFalse(replayAfterDrain.stored());
            assertEquals(1, replayAfterDrain.journalEntryCount());
        }
    }

    @Test
    void oneExactAdmissionBuildsAndStoresOneEntryForSeveralRecipients()
            throws Exception {
        try (CoordinationEngine source = CoordinationEngine.legacyInMemory();
                CoordinationEngine target = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline sourceAlice = source.registerTimeline(
                    ALICE_TIMELINE, "alice");
            TimelineEntry canonical = source.append(
                    sourceAlice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"));

            target.registerTimeline(ALICE_TIMELINE, "alice");
            ExactValue retainedRequest = target.exactValue("amount: 3");
            assertEquals(canonical.exactRequest().blueId(),
                    retainedRequest.blueId());
            target.startDocument(COUNTER_A, counterYaml(COUNTER_A));
            target.startDocument(COUNTER_B, counterYaml(COUNTER_B));
            CoordinationTestControl control =
                    CoordinationTestControl.attach(target);
            CoordinationTestControl.MetricsSnapshot before =
                    control.metricsSnapshot();

            // when
            TimelineAppendReceipt admission = target.appendTimelineEntry(
                    canonical.exactEvent().copyNode());
            CoordinationTestControl.MetricsSnapshot after =
                    control.metricsSnapshot();
            int routeTargetCountAfterAdmission =
                    target.routeTargetCount(admission.entry());
            ProcessingDrainReceipt drained = target.drain();

            // then
            assertTrue(admission.stored());
            assertEquals(canonical.blueId(), admission.entry().blueId());
            assertEquals(1, admission.journalEntryCount());
            assertEquals(1L, diagnosticDelta(
                    before, after, "append.entriesBuilt"));
            assertEquals(1L, diagnosticDelta(
                    before, after, "wholeObjectStore.insertions"));
            assertEquals(1L, diagnosticDelta(
                    before, after, "journal.entriesStoredWhole"));
            assertEquals(2, routeTargetCountAfterAdmission);

            assertEquals(List.of(COUNTER_A, COUNTER_B),
                    drained.outcomesFor(admission.entry().blueId()).stream()
                            .map(outcome -> outcome.documentId())
                            .toList());
            assertEquals(3L, counter(target, COUNTER_A));
            assertEquals(3L, counter(target, COUNTER_B));
            assertEquals(1, target.metrics().journalEntryCount());
        }
    }

    @Test
    void normalReadsFailClosedUntilAuditStateBecomesReady() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline alice = engine.registerTimeline(ALICE_TIMELINE, "alice");
            engine.append(alice, Operation.yaml(
                    "increment", "aliceChannel", "amount: 3"));
            CoordinationTestControl control =
                    CoordinationTestControl.attach(engine);
            control.makeHistoricalUnavailable("provider window pending");

            var admitted = engine.startDocument(
                    COUNTER,
                    resource("examples/clean/counter.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);
            assertEquals(SessionStatus.CATCHING_UP, admitted.status());
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.auditDocument(COUNTER).status());
            CoordinationException notReady = assertThrows(
                    CoordinationException.class,
                    () -> engine.document(COUNTER));
            assertEquals(CoordinationErrorCode.DOCUMENT_NOT_READY,
                    notReady.code());
            assertEquals(1, engine.history(COUNTER).size(),
                    "audit history stays available during catch-up");

            // when
            control.makeHistoricalAvailable();
            boolean quiescent = engine.drain().quiescent();

            // then
            assertTrue(quiescent);
            assertEquals(SessionStatus.READY,
                    engine.document(COUNTER).status());
            assertEquals(3L, counter(engine));
        }
    }

    @Test
    void boundedDrainResumesAnOpenEntryWithoutRepeatingFrozenProcess()
            throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline alice = engine.registerTimeline(ALICE_TIMELINE, "alice");
            engine.startDocument(COUNTER_A, counterYaml(COUNTER_A));
            engine.startDocument(COUNTER_B, counterYaml(COUNTER_B));
            TimelineEntry first = engine.append(alice, Operation.yaml(
                    "increment", "aliceChannel", "amount: 3"));
            TimelineEntry second = engine.append(alice, Operation.yaml(
                    "increment", "aliceChannel", "amount: 3"));
            CoordinationTestControl control =
                    CoordinationTestControl.attach(engine);
            long callsBefore = engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);

            // when
            ProcessingDrainReceipt transitionPause = engine.drain(
                    new CoordinationEngine.DrainBudget(1L, 10L));
            assertTrue(transitionPause.paused());
            assertFalse(transitionPause.quiescent());
            assertFalse(transitionPause.blocked());
            assertEquals(1L, transitionPause.committedProcessTransitions());
            assertEquals(List.of(COUNTER_A), transitionPause.outcomes().stream()
                    .map(outcome -> outcome.documentId()).toList());
            assertTrue(transitionPause.processedEntries().isEmpty(),
                    "a partially committed entry is not a completed entry");
            assertTrue(transitionPause.processedThrough().isEmpty());

            control.restartFromStores();
            ProcessingDrainReceipt selectionPause = engine.drain(
                    new CoordinationEngine.DrainBudget(10L, 1L));
            assertTrue(selectionPause.paused());
            assertFalse(selectionPause.blocked());
            assertEquals(1L, selectionPause.committedProcessTransitions());
            assertEquals(List.of(COUNTER_B), selectionPause.outcomes().stream()
                    .map(outcome -> outcome.documentId()).toList(),
                    "resume reports only the PROCESS committed in this call");
            assertEquals(List.of(first.blueId()),
                    entryIds(selectionPause.processedEntries()));

            ProcessingDrainReceipt completed = engine.drain(
                    new CoordinationEngine.DrainBudget(10L, 1L));

            // then
            assertFalse(completed.paused());
            assertFalse(completed.blocked());
            assertTrue(completed.quiescent());
            assertEquals(2L, completed.committedProcessTransitions());
            assertEquals(List.of(COUNTER_A, COUNTER_B),
                    completed.outcomes().stream()
                            .map(outcome -> outcome.documentId()).toList());
            assertEquals(List.of(second.blueId()),
                    entryIds(completed.processedEntries()));
            assertEquals(6L, counter(engine, COUNTER_A));
            assertEquals(6L, counter(engine, COUNTER_B));
            assertEquals(4L, engine.metrics().counter(
                            CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS)
                            - callsBefore,
                    "resumption cannot rerun a committed frozen PROCESS");
            assertTrue(transitionPause.elapsedNanos() > 0L);
            assertTrue(selectionPause.elapsedNanos() > 0L);
            assertTrue(completed.elapsedNanos() > 0L);
        }
    }

    @Test
    void appendStoresWorkWithoutInvokingProcess() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline alice = engine.registerTimeline(
                    ALICE_TIMELINE, "alice");
            engine.startDocument(
                    COUNTER, resource("examples/clean/counter.yaml"));
            long processBefore = engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);

            // when
            TimelineEntry entry = engine.append(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"));
            int journalEntriesAfterAppend = engine.metrics().journalEntryCount();
            long counterAfterAppend = counter(engine);
            long epochAfterAppend = engine.document(COUNTER).epoch();
            long processAfterAppend = engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);
            ProcessingDrainReceipt drained = engine.drain();

            // then
            assertEquals(1, journalEntriesAfterAppend);
            assertEquals(0L, counterAfterAppend);
            assertEquals(0L, epochAfterAppend);
            assertEquals(processBefore, processAfterAppend);
            assertEquals(List.of(entry.blueId()), entryIds(
                    drained.processedEntries()));
            assertEquals(1, drained.outcomesFor(entry.blueId()).size());
            assertTrue(drained.quiescent());
            assertEquals(3L, counter(engine));
            assertEquals(1L, engine.document(COUNTER).epoch());
            assertTrue(engine.metrics().nanos(
                    CoordinationMetrics.Phase.PROCESS_ROUTE_LOOKUP) > 0L);
        }
    }

    @Test
    void exactDocumentTargetUsesCurrentOrAnyRetainedEpochWithoutProcessingOnAppend()
            throws Exception {
        try (CoordinationEngine source = CoordinationEngine.legacyInMemory();
                CoordinationEngine target = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline sourceAlice = source.registerTimeline(
                    ALICE_TIMELINE, "alice");
            TimelineEntry template1 = source.appendAt(sourceAlice,
                    Operation.yaml("increment", "aliceChannel", "amount: 3"),
                    T0 + 100L);
            TimelineEntry template2 = source.appendAt(sourceAlice,
                    Operation.yaml("increment", "aliceChannel", "amount: 3"),
                    T0 + 200L);
            TimelineEntry template3 = source.appendAt(sourceAlice,
                    Operation.yaml("increment", "aliceChannel", "amount: 3"),
                    T0 + 300L);
            TimelineEntry template4 = source.appendAt(sourceAlice,
                    Operation.yaml("increment", "aliceChannel", "amount: 3"),
                    T0 + 400L);

            target.registerTimeline(ALICE_TIMELINE, "alice");
            target.exactValue("amount: 3");
            target.startDocument(COUNTER_A, counterYaml(COUNTER_A));
            target.startDocument(COUNTER_B, counterYaml(COUNTER_B));
            ExactValue initialA = target.document(COUNTER_A).current();
            long processBefore = target.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);

            // when
            TimelineAppendReceipt current = target.appendTimelineEntry(
                    targeted(template1, initialA, true, null));
            TimelineAppendReceipt staleExact = target.appendTimelineEntry(
                    targeted(template2, initialA, true,
                            current.entry().blueId()));
            TimelineAppendReceipt retained = target.appendTimelineEntry(
                    targeted(template3, initialA, false,
                            staleExact.entry().blueId()));
            TimelineAppendReceipt implicitRetained = target.appendTimelineEntry(
                    targeted(template4, initialA, null,
                            retained.entry().blueId()));
            long processAfterAppend = target.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);
            ProcessingDrainReceipt drained = target.drain();

            // then
            assertEquals(processBefore, processAfterAppend);
            assertEquals("external-provider", current.entry().exactEvent()
                    .canonicalAt("/source").getValue());
            assertEquals(initialA.blueId(), current.entry().exactEvent()
                    .canonicalAt("/message/document").getReferenceBlueId());
            assertEquals(Boolean.TRUE, current.entry().exactEvent()
                    .canonicalAt("/message/requireExactDocumentVersion")
                    .getValue());

            assertEquals(List.of(current.entry().blueId(),
                            staleExact.entry().blueId(),
                            retained.entry().blueId(),
                            implicitRetained.entry().blueId()),
                    entryIds(drained.processedEntries()));
            assertEquals(List.of(COUNTER_A), drained.outcomesFor(
                    current.entry().blueId()).stream()
                    .map(outcome -> outcome.documentId()).toList());
            assertTrue(drained.outcomesFor(
                    staleExact.entry().blueId()).isEmpty());
            assertEquals(List.of(COUNTER_A), drained.outcomesFor(
                    retained.entry().blueId()).stream()
                    .map(outcome -> outcome.documentId()).toList());
            assertEquals(List.of(COUNTER_A), drained.outcomesFor(
                    implicitRetained.entry().blueId()).stream()
                    .map(outcome -> outcome.documentId()).toList());
            assertEquals(9L, counter(target, COUNTER_A));
            assertEquals(0L, counter(target, COUNTER_B));
        }
    }

    @Test
    void drainSelectsShuffledCrossTimelineEntriesByExternalOrder()
            throws Exception {
        try (CoordinationEngine engine = counterEngine()) {
            // given
            Timeline alice = engine.registerTimeline(
                    ALICE_TIMELINE, "alice");
            Timeline bob = engine.registerTimeline(BOB_TIMELINE, "bob");

            TimelineEntry middle = engine.appendAt(
                    bob,
                    Operation.yaml(
                            "decrement", "bobChannel", "amount: 1"),
                    T0 + 200L);
            TimelineEntry early = engine.appendAt(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"),
                    T0 + 100L);
            TimelineEntry late = engine.appendAt(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 10"),
                    T0 + 300L);

            assertEquals(
                    List.of(1L, 2L, 3L),
                    List.of(
                            middle.globalSequence(),
                            early.globalSequence(),
                            late.globalSequence()));

            // when
            ProcessingDrainReceipt drained = engine.drain();

            // then
            assertEquals(
                    List.of(early.blueId(), middle.blueId(), late.blueId()),
                    entryIds(drained.processedEntries()));
            assertEquals(
                    List.of(early.blueId(), middle.blueId(), late.blueId()),
                    committedSourceEntryIds(engine));
            assertEquals(12L, counter(engine));
            assertTrue(drained.quiescent());
        }
    }

    @Test
    void drainThroughProcessesEveryEarlierEntryAndIsIdempotent()
            throws Exception {
        try (CoordinationEngine engine = counterEngine()) {
            // given
            Timeline alice = engine.registerTimeline(
                    ALICE_TIMELINE, "alice");
            Timeline bob = engine.registerTimeline(BOB_TIMELINE, "bob");

            TimelineEntry middle = engine.appendAt(
                    bob,
                    Operation.yaml(
                            "decrement", "bobChannel", "amount: 1"),
                    T0 + 200L);
            TimelineEntry early = engine.appendAt(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"),
                    T0 + 100L);
            TimelineEntry late = engine.appendAt(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 10"),
                    T0 + 300L);

            // when
            ProcessingDrainReceipt first = engine.drainThrough(
                    middle.sourceOrderKey());
            assertEquals(
                    List.of(early.blueId(), middle.blueId()),
                    entryIds(first.processedEntries()));
            assertEquals(2L, counter(engine));
            assertEquals(2L, engine.document(COUNTER).epoch());
            assertTrue(first.quiescent());

            long processBeforeRetry = engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);
            ProcessingDrainReceipt duplicate = engine.drainThrough(
                    middle.sourceOrderKey());
            assertTrue(duplicate.processedEntries().isEmpty());
            assertTrue(duplicate.outcomes().isEmpty());
            assertTrue(duplicate.quiescent());
            assertEquals(processBeforeRetry, engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertEquals(2L, counter(engine));

            ProcessingDrainReceipt remainder = engine.drainThrough(
                    late.sourceOrderKey());

            // then
            assertEquals(
                    List.of(late.blueId()),
                    entryIds(remainder.processedEntries()));
            assertEquals(12L, counter(engine));
            assertEquals(3L, engine.document(COUNTER).epoch());
            assertTrue(remainder.quiescent());
        }
    }

    private static CoordinationEngine counterEngine() throws Exception {
        CoordinationEngine engine = CoordinationEngine.legacyInMemory();
        try {
            engine.startDocument(
                    COUNTER, resource("examples/clean/counter.yaml"));
            return engine;
        } catch (RuntimeException | Error failure) {
            engine.close();
            throw failure;
        }
    }

    private static List<String> entryIds(List<TimelineEntry> entries) {
        return entries.stream().map(TimelineEntry::blueId).toList();
    }

    private static List<String> committedSourceEntryIds(
            CoordinationEngine engine) {
        return engine.history(COUNTER).stream()
                .flatMap(revision -> revision.sourceEntry().stream())
                .map(TimelineEntry::blueId)
                .toList();
    }

    private static long counter(CoordinationEngine engine) {
        return counter(engine, COUNTER);
    }

    private static long counter(
            CoordinationEngine engine,
            DocumentId documentId) {
        Object value = engine.document(documentId)
                .valueAt("/counter")
                .copyNode()
                .getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected numeric /counter, got " + value);
    }

    private static String counterYaml(DocumentId documentId)
            throws Exception {
        return resource("examples/clean/counter.yaml").replace(
                "documentId: counter", "documentId: " + documentId.value());
    }

    private static Node targeted(
            TimelineEntry template,
            ExactValue document,
            Boolean exact,
            String previousBlueId) {
        Node entry = template.exactEvent().copyNode();
        Node message = entry.getProperties().get("message");
        message.properties("document", document.referenceNode());
        if (exact == null) {
            message.getProperties().remove("requireExactDocumentVersion");
        } else {
            message.properties("requireExactDocumentVersion",
                    new Node().value(exact));
        }
        entry.properties("source", new Node().value("external-provider"));
        if (previousBlueId == null) {
            entry.getProperties().remove("prevEntry");
        } else {
            entry.properties("prevEntry", new Node().blueId(previousBlueId));
        }
        return entry;
    }

    private static long diagnosticDelta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            String name) {
        return after.counters().getOrDefault(name, 0L)
                - before.counters().getOrDefault(name, 0L);
    }
}
