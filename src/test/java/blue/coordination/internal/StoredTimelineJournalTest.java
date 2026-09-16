package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.TimelineJournalStorageException;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.NoncommittingExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Same journal machine, immutable external rows, and entirely fresh runtimes. */
final class StoredTimelineJournalTest {
    @TempDir Path directory;
    private static final Timeline A = new Timeline("timeline-a", "alice");
    private static final Timeline B = new Timeline("timeline-b", "bob");
    private static final Operation ABSENT = Operation.withoutRequest("touch", "ownerChannel");
    private static final Operation EMPTY = Operation.exact("touch", "ownerChannel",
            ExactValue.verified(blue.language.model.Nodes.emptyObject()));
    private static final Operation VALUE = Operation.exact("touch", "ownerChannel", ExactValue.verified(new Node().value("value")));

    @Test void reopenedRowsPreserveAppendDuplicatePredecessorOrderWindowsAndRevisions() {
        try (Context reference = new Context(null)) {
            List<TimelineEntry> expected = List.of(
                    reference.journal.append(A, ABSENT, 100),
                    reference.journal.append(B, EMPTY, 50),
                    reference.journal.append(A, VALUE, 200));
            List<Timeline> timelines = List.of(A, B, A);
            List<Operation> operations = List.of(ABSENT, EMPTY, VALUE);
            long[] timestamps = {100, 50, 200};
            for (int i = 0; i < 3; i++) {
                var store = new FileTimelineJournalStore(directory);
                try (Context restarted = new Context(store)) {
                    assertEquals(0, store.bodyReads.get(), "opening must not restore all entries");
                    assertEquals(i, restarted.journal.size());
                    assertEquals(i, restarted.journal.revision());
                    assertEquals(0, store.bodyReads.get(), "state probes must not load bodies");
                    assertEntry(expected.get(i), restarted.journal.append(timelines.get(i), operations.get(i), timestamps[i]));
                }
            }
            try (Context restarted = new Context(new FileTimelineJournalStore(directory))) {
                assertEquals(observe(reference.journal), observe(restarted.journal));
                assertEntry(expected.get(0), restarted.journal.appendExact(A, expected.get(0).exactEvent()));
                assertEquals(3, restarted.journal.size());
                assertEquals(3, restarted.journal.revision());
                assertEntry(expected.get(0), restarted.journal.requireCanonical(expected.get(0)));
                TimelineEntry wrongPrevious = restarted.factory.create(A, null, ABSENT, 300, 4, 3);
                assertThrows(IllegalArgumentException.class,
                        () -> restarted.journal.appendExact(A, wrongPrevious.exactEvent()));
                assertThrows(IllegalArgumentException.class, () -> restarted.journal.append(A, VALUE, 150));
                var referenceMark = reference.journal.mark();
                var storedMark = restarted.journal.mark();
                assertEntry(reference.journal.append(A, ABSENT, 400), restarted.journal.append(A, ABSENT, 400));
                assertEntry(reference.journal.append(B, VALUE, 500), restarted.journal.append(B, VALUE, 500));
                reference.journal.rollbackTo(referenceMark);
                restarted.journal.rollbackTo(storedMark);
                assertEquals(6, restarted.journal.revision(), "rollback advances, never rewinds, revision");
                assertEquals(observe(reference.journal), observe(restarted.journal));
                assertEquals(reference.metrics.snapshot().counters().get("journal.rollbackEntries"),
                        restarted.metrics.snapshot().counters().get("journal.rollbackEntries"));
            }
            try (Context restarted = new Context(new FileTimelineJournalStore(directory))) {
                assertEquals(6, restarted.journal.revision());
                assertEquals(observe(reference.journal), observe(restarted.journal));
            }
        }
    }

    @Test void closedAvailabilitySurvivesRestartWithoutChangingRevision() {
        try (Context context = new Context(new FileTimelineJournalStore(directory))) {
            context.journal.append(A, ABSENT, 100);
            context.journal.makeHistoricalUnavailable("maintenance");
        }
        try (Context context = new Context(new FileTimelineJournalStore(directory))) {
            assertEquals(1, context.journal.revision());
            assertEquals(new HistoricalStep.Unavailable("maintenance"), probe(context.journal, ignored -> true));
            context.journal.invalidateHistoricalEvidence("broken cursor");
        }
        try (Context context = new Context(new FileTimelineJournalStore(directory))) {
            assertEquals(new HistoricalStep.InvalidEvidence("broken cursor"), probe(context.journal, ignored -> true));
            context.journal.makeHistoricalAvailable();
            assertInstanceOf(HistoricalStep.EligibleEntry.class, probe(context.journal, ignored -> true));
            assertInstanceOf(HistoricalStep.Complete.class, probe(context.journal, ignored -> false));
            assertEquals(1, context.journal.revision());
        }
    }

    @Test void rootedOrderUsesTheCanonicalTimelineIdentityAcrossFreshReopen() {
        TimelineEntry first;
        TimelineEntry second;
        try (Context reference = new Context(null, true);
             Context stored = new Context(new FileTimelineJournalStore(directory), true)) {
            first = reference.journal.append(A, ABSENT, 100);
            second = reference.journal.append(B, VALUE, 100);
            assertNotEquals(A.timelineId(), first.sourceOrderKey().components().get(1));
            assertEquals(first.exactEvent().canonicalAt("/timeline").blueId(),
                    first.sourceOrderKey().components().get(1));
            assertEntry(first, stored.journal.append(A, ABSENT, 100));
            assertEntry(second, stored.journal.append(B, VALUE, 100));
        }
        try (Context reopened = new Context(new FileTimelineJournalStore(directory), true)) {
            assertEntry(first, reopened.journal.byBlueId(first.blueId()).orElseThrow());
            assertEntry(second, reopened.journal.byBlueId(second.blueId()).orElseThrow());
            TimelineEntry earliest = first.sourceOrderKey().compareTo(second.sourceOrderKey()) < 0 ? first : second;
            TimelineEntry later = earliest == first ? second : first;
            assertEntry(earliest, reopened.journal.nextExternal(null, null).orElseThrow());
            assertEntry(later, reopened.journal.nextExternal(earliest.sourceOrderKey(), null).orElseThrow());
            assertEntry(first, reopened.journal.appendExact(A, first.exactEvent()));
            assertEquals(2, reopened.journal.size());
        }
        // The same bytes must not silently acquire legacy timeline-name ordering.
        try (Context wrongProfile = new Context(new FileTimelineJournalStore(directory))) {
            assertThrows(TimelineJournalStorageException.class,
                    () -> wrongProfile.journal.byBlueId(first.blueId()));
        }
    }

    @Test void rootedBeginningKeepsProviderDispositionAndNonemptyAuthorityAcrossReopen() {
        try (Context context = new Context(new FileTimelineJournalStore(directory), true)) {
            assertDoesNotThrow(() -> context.journal.requireBeginningAdmission(true));
            context.journal.makeHistoricalUnavailable("provider unavailable");
        }
        try (Context context = new Context(new FileTimelineJournalStore(directory), true)) {
            var unavailable = assertThrows(blue.coordination.api.CoordinationException.class,
                    () -> context.journal.requireBeginningAdmission(true));
            assertEquals(blue.coordination.api.CoordinationErrorCode.NEEDS_RESOURCES, unavailable.code());
            assertDoesNotThrow(() -> context.journal.requireBeginningAdmission(false));
            context.journal.invalidateHistoricalEvidence("invalid proof");
            var invalid = assertThrows(blue.coordination.api.CoordinationException.class,
                    () -> context.journal.requireBeginningAdmission(true));
            assertEquals(blue.coordination.api.CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE, invalid.code());
            context.journal.makeHistoricalAvailable();
            context.journal.append(A, ABSENT, 100);
        }
        try (Context context = new Context(new FileTimelineJournalStore(directory), true)) {
            for (boolean provider : List.of(false, true)) {
                var nonempty = assertThrows(blue.coordination.api.CoordinationException.class,
                        () -> context.journal.requireBeginningAdmission(provider));
                assertEquals(blue.coordination.api.CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE, nonempty.code());
            }
        }
    }

    @Test void staleOwnerCannotAppendOrRollBackForeignRows() {
        var store = new FileTimelineJournalStore(directory);
        try (Context first = new Context(store); Context second = new Context(new FileTimelineJournalStore(directory))) {
            var mark = first.journal.mark();
            TimelineEntry foreign = second.journal.append(A, ABSENT, 100);
            assertInstanceOf(NoncommittingExecutionException.class,
                    assertThrows(TimelineJournalStorageException.class, () -> first.journal.rollbackTo(mark)));
            assertThrows(TimelineJournalStorageException.class, () -> first.journal.append(B, ABSENT, 200));
            assertEquals(1, second.journal.size());
            assertEntry(foreign, second.journal.byBlueId(foreign.blueId()).orElseThrow());
            assertThrows(TimelineJournalStorageException.class,
                    () -> store.apply(TimelineJournalStore.State.empty(),
                            new TimelineJournalStore.Truncate(TimelineJournalStore.State.empty())));
            assertEquals(1, second.journal.size());
        }
    }

    @Test void stateChangeBetweenPinnedReadAndPublishFailsTheAtomicFence() {
        var store = new FileTimelineJournalStore(directory);
        var faults = new FaultStore(store);
        try (Context context = new Context(faults)) {
            faults.phase = "conflict-apply";
            assertThrows(TimelineJournalStorageException.class, () -> context.journal.append(A, ABSENT, 100));
            assertEquals(0, context.journal.size());
            assertEquals(0, context.journal.revision());
        }
        try (Context restarted = new Context(new FileTimelineJournalStore(directory))) {
            assertEquals(new HistoricalStep.Unavailable("foreign state change"),
                    probe(restarted.journal, ignored -> true));
            assertEquals(0, restarted.journal.size());
        }
    }

    @Test void inMemoryPhysicalViewCannotBeMutatedReentrantly() {
        var store = new InMemoryTimelineJournalStore();
        try (var view = store.openRead()) {
            var pinned = view.state();
            var replacement = new TimelineJournalStore.State(0, 0, 0,
                    new TimelineJournalStore.Availability(
                            TimelineJournalStore.AvailabilityKind.UNAVAILABLE, "changed"));
            assertThrows(TimelineJournalStorageException.class,
                    () -> store.apply(pinned, new TimelineJournalStore.SetAvailability(replacement)));
            assertEquals(pinned, view.state());
            assertTrue(view.nextExternal(null).isEmpty());
        }
    }

    @Test void changedStoredMetadataIsNoncommittingEvenWhenExactEventIdentityIsValid() {
        var store = new FileTimelineJournalStore(directory);
        String id;
        try (Context context = new Context(store)) { id = context.journal.append(A, VALUE, 100).blueId(); }
        store.corruptOperation(id);
        try (Context context = new Context(new FileTimelineJournalStore(directory))) {
            assertThrows(TimelineJournalStorageException.class, () -> context.journal.byBlueId(id));
            assertThrows(TimelineJournalStorageException.class, () -> context.journal.nextExternal(null, null));
            assertThrows(TimelineJournalStorageException.class, () -> probe(context.journal, ignored -> true));
            assertThrows(TimelineJournalStorageException.class, () -> context.journal.append(A, ABSENT, 200));
            assertEquals(1, context.journal.revision());
        }
    }

    @Test void physicalReadWriteAndCloseFailuresDoNotBecomeCompleteHistoryOrSemanticRejection() {
        var backing = new FileTimelineJournalStore(directory);
        var faults = new FaultStore(backing);
        try (Context context = new Context(faults)) {
            context.journal.append(A, ABSENT, 100);
            for (String phase : List.of("open", "read", "close", "state")) {
                faults.phase = phase;
                TimelineJournalStorageException error = assertThrows(TimelineJournalStorageException.class,
                        () -> probe(context.journal, ignored -> false), phase);
                assertInstanceOf(IllegalArgumentException.class, error.getCause());
                faults.phase = "";
            }
            faults.phase = "apply";
            TimelineJournalStorageException error = assertThrows(TimelineJournalStorageException.class,
                    () -> context.journal.append(A, ABSENT, 200));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            faults.phase = "";
            assertEquals(1, context.journal.size());
            assertEquals(1, context.journal.revision());
        }
    }

    @Test void journalOnlyEngineDoesNotClaimRestoredWholeObjectProviderEvidence() {
        var backing = new FileTimelineJournalStore(directory);
        try (var writer = DefaultCoordinationEngine.createWithJournalStore(backing)) {
            Timeline timeline = writer.registerTimeline(A.timelineId(), A.actorId());
            writer.appendAt(timeline, ABSENT, 100);
            writer.appendAt(timeline, ABSENT, 200);
        }
        String counter = """
                name: Journal counter
                count: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: timeline-a}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  touch:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /count
                              val: {$add: [{$document: /count}, 1]}
                          - $return: true
                """;
        var faults = new FaultStore(new FileTimelineJournalStore(directory));
        try (var reference = DefaultCoordinationEngine.create();
             var restarted = DefaultCoordinationEngine.createWithJournalStore(faults)) {
            Timeline timeline = reference.registerTimeline(A.timelineId(), A.actorId());
            restarted.registerTimeline(A.timelineId(), A.actorId());
            reference.appendAt(timeline, ABSENT, 100);
            reference.appendAt(timeline, ABSENT, 200);
            var id = blue.coordination.api.DocumentId.of("journal-counter");
            reference.startDocument(id, counter, blue.coordination.api.CoordinationEngine.AdmissionPolicy.FULL_HISTORY, null);
            reference.drain();
            assertEquals(new java.math.BigInteger("2"), reference.document(id).valueAt("/count").copyNode().getValue());
            var unavailable = assertThrows(blue.coordination.api.CoordinationException.class,
                    () -> restarted.startDocument(id, counter,
                            blue.coordination.api.CoordinationEngine.AdmissionPolicy.FULL_HISTORY, null));
            assertEquals(blue.coordination.api.CoordinationErrorCode.NEEDS_RESOURCES, unavailable.code());
            var evidence = assertInstanceOf(blue.language.processor.ExecutionEvidenceUnavailableException.class,
                    unavailable.getCause());
            assertTrue(evidence.requiredExactBlueIds().contains(restarted.auditTimeline(A.timelineId()).get(0).blueId()),
                    "journal rows alone do not rehydrate the separate exact-object provider");
        }
    }

    @Test void existingCoordinatorDrainsReopenedJournalAndPreservesPhysicalControlExits() {
        try (var writer = DefaultCoordinationEngine.createWithJournalStore(new FileTimelineJournalStore(directory))) {
            Timeline timeline = writer.registerTimeline(A.timelineId(), A.actorId());
            writer.appendAt(timeline, ABSENT, 100);
            writer.appendAt(timeline, ABSENT, 200);
        }
        var faults = new FaultStore(new FileTimelineJournalStore(directory));
        try (var reference = DefaultCoordinationEngine.create();
             var restarted = DefaultCoordinationEngine.createWithJournalStore(faults)) {
            Timeline timeline = reference.registerTimeline(A.timelineId(), A.actorId());
            reference.appendAt(timeline, ABSENT, 100);
            reference.appendAt(timeline, ABSENT, 200);
            faults.phase = "read";
            assertThrows(TimelineJournalStorageException.class, restarted::drain);
            assertThrows(TimelineJournalStorageException.class, () -> restarted.startDocument(
                    blue.coordination.api.DocumentId.of("not-admitted"), "name: Not admitted"));
            faults.phase = "";
            var expected = reference.drain();
            var actual = restarted.drain();
            assertEquals(expected.processedEntries().stream().map(TimelineEntry::blueId).toList(),
                    actual.processedEntries().stream().map(TimelineEntry::blueId).toList());
            assertEquals(2, actual.processedEntries().size());
            assertEquals(expected.processedThrough(), actual.processedThrough());
            assertEquals(expected.committedProcessTransitions(), actual.committedProcessTransitions());
            assertEquals(expected.quiescent(), actual.quiescent());
        }
    }

    @Test void pointReadLoadsOnlySelectedBodiesAndReturnsDetachedExactValues() {
        TimelineEntry last = null;
        try (Context context = new Context(new FileTimelineJournalStore(directory))) {
            for (int i = 1; i <= 16; i++) last = context.journal.append(A, VALUE, i * 100L);
        }
        var store = new FileTimelineJournalStore(directory);
        try (Context context = new Context(store)) {
            assertEquals(0, store.bodyReads.get());
            TimelineEntry restored = context.journal.byBlueId(last.blueId()).orElseThrow();
            assertTrue(store.bodyReads.get() <= 6, "point read must not load the full history");
            restored.exactEvent().copyNode().properties("mutated", new Node().value(true));
            assertEntry(last, context.journal.byBlueId(last.blueId()).orElseThrow());
        }
    }

    @Test void indexAndPinnedStateTamperingNeverProduceCompleteHistory() {
        var faults = new FaultStore(new FileTimelineJournalStore(directory));
        try (Context context = new Context(faults)) {
            context.journal.append(A, ABSENT, 100);
            context.journal.append(A, VALUE, 200);
            for (String phase : List.of("wrong-index", "missing-index", "null-read", "changed-state")) {
                faults.phase = phase;
                assertThrows(TimelineJournalStorageException.class,
                        () -> probe(context.journal, ignored -> false), phase);
                faults.phase = "";
            }
            for (String phase : List.of("wrong-head", "missing-head")) {
                faults.phase = phase;
                assertThrows(TimelineJournalStorageException.class,
                        () -> context.journal.append(A, ABSENT, 300), phase);
                faults.phase = "";
            }
            assertEquals(2, context.journal.size());
            assertEquals(2, context.journal.revision());
        }
    }

    private static List<Object> observe(TimelineJournal journal) {
        List<Object> result = new ArrayList<>();
        result.add(journal.size());
        result.add(journal.revision());
        result.add(journal.entries().stream().map(StoredTimelineJournalTest::entryEvidence).toList());
        result.add(journal.entries(A.timelineId()).stream().map(StoredTimelineJournalTest::entryEvidence).toList());
        ExternalOrderKey cursor = null;
        while (true) {
            Optional<TimelineEntry> next = journal.nextExternal(cursor, null);
            if (next.isEmpty()) break;
            result.add(entryEvidence(next.orElseThrow()));
            cursor = next.orElseThrow().sourceOrderKey();
        }
        result.add(journal.containsExternalOrder(cursor));
        result.add(journal.latestExternalOrder());
        result.add(probe(journal, ignored -> false));
        var eligible = (HistoricalStep.EligibleEntry) probe(journal, ignored -> true);
        result.add(entryEvidence(eligible.entry()));
        result.add(journal.nextHistoricalStep(cursor, afterAll(), null, ignored -> true, 7, 11, () -> "surface"));
        result.add(journal.nextExternal(null, ExternalOrderKey.of(List.of(1, "before", "before"))).isEmpty());
        return result;
    }
    private static HistoricalStep probe(TimelineJournal journal, java.util.function.Predicate<TimelineEntry> eligible) {
        return journal.nextHistoricalStep(null, afterAll(), null, eligible, 7, 11, () -> "surface");
    }
    private static ExternalOrderKey afterAll() { return ExternalOrderKey.of(List.of(10_000, "cutoff", "cutoff")); }
    private static List<Object> entryEvidence(TimelineEntry entry) {
        return List.of(entry.blueId(), entry.request().map(ExactValue::blueId), entry.timeline(), entry.operation(),
                entry.channel(), entry.timestampMicros(), entry.globalSequence(), entry.timelineSequence(),
                entry.journalOrderKey(), entry.sourceOrderKey());
    }
    private static void assertEntry(TimelineEntry expected, TimelineEntry actual) {
        assertEquals(entryEvidence(expected), entryEvidence(actual));
        assertTrue(expected.exactEvent().sameExactValue(actual.exactEvent()));
        if (expected.request().isPresent()) assertTrue(expected.exactRequest().sameExactValue(actual.exactRequest()));
    }

    private static final class Context implements AutoCloseable {
        final EngineMetrics metrics = new EngineMetrics();
        final WholeObjectStore objects = new WholeObjectStore(metrics);
        final BlueRuntime runtime = BlueRuntime.create(objects);
        final WholeRequestEntryFactory factory;
        final TimelineJournal journal;
        Context(TimelineJournalStore store) {
            this(store, false);
        }
        Context(TimelineJournalStore store, boolean rootedOrder) {
            factory = new WholeRequestEntryFactory(runtime, objects, metrics,
                    ignored -> "MyOS/Principal Actor", rootedOrder);
            journal = store == null ? new InMemoryTimelineJournal(factory, metrics)
                    : new DefaultTimelineJournal(factory, metrics, store);
        }
        @Override public void close() { runtime.close(); }
    }

    private static final class FaultStore implements TimelineJournalStore {
        final TimelineJournalStore delegate;
        String phase = "";
        FaultStore(TimelineJournalStore delegate) { this.delegate = delegate; }
        void fail(String at) { if (phase.equals(at)) throw new IllegalArgumentException("backend " + at); }
        @Override public void apply(State expected, Mutation mutation) {
            fail("apply");
            if (phase.equals("conflict-apply")) {
                phase = "";
                delegate.apply(expected, new SetAvailability(new State(expected.globalSequence(),
                        expected.entryCount(), expected.revision(),
                        new Availability(AvailabilityKind.UNAVAILABLE, "foreign state change"))));
            }
            delegate.apply(expected, mutation);
        }
        @Override public ReadView openRead() {
            fail("open");
            ReadView view = delegate.openRead();
            return new ReadView() {
                private int stateReads;
                @Override public State state() {
                    fail("state");
                    State state = view.state();
                    return phase.equals("changed-state") && stateReads++ > 0
                            ? new State(state.globalSequence(), state.entryCount(), state.revision() + 1, state.availability())
                            : state;
                }
                @Override public Optional<TimelineEntry> byBlueId(String id) {
                    fail("read");
                    return phase.equals("missing-index") ? Optional.empty() : view.byBlueId(id);
                }
                @Override public Optional<TimelineEntry> timelineHead(String id) {
                    fail("read");
                    if (phase.equals("missing-head")) return Optional.empty();
                    return phase.equals("wrong-head") ? view.atTimelineSequence(id, 1) : view.timelineHead(id);
                }
                @Override public Optional<TimelineEntry> atAppendPosition(int p) {
                    fail("read"); return view.atAppendPosition(phase.equals("wrong-index") ? 1 : p);
                }
                @Override public Optional<TimelineEntry> atTimelineSequence(String id, long s) { fail("read"); return view.atTimelineSequence(id, s); }
                @Override public Optional<TimelineEntry> nextExternal(ExternalOrderKey after) {
                    fail("read"); return phase.equals("null-read") ? null : view.nextExternal(after);
                }
                @Override public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order) { fail("read"); return view.atExternalOrder(order); }
                @Override public Optional<ExternalOrderKey> latestExternalOrder() { fail("read"); return view.latestExternalOrder(); }
                @Override public void close() { view.close(); fail("close"); }
            };
        }
    }
}
