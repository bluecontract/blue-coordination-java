package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Closed historical-step and exact completeness-identity tests. */
final class InMemoryTimelineJournalHistoricalStepTest {
    private static final int LARGE_ENTRY_COUNT = 512;
    private static final int TIMELINE_COUNT = 16;

    @Test
    void returnsEveryClosedOutcomeWithoutConflatingAbsence() {
        // given

        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        HistoricalAvailabilityControl availability =
                new HistoricalAvailabilityControl();

        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            InMemoryTimelineJournal journal = new InMemoryTimelineJournal(
                    new WholeRequestEntryFactory(runtime, objects, metrics),
                    metrics,
                    availability);
            Timeline timeline = new Timeline("timeline-a", "alice");
            ExactValue request = objects.put(
                    new Node().value("increment"), "historical-test");
            Operation operation = Operation.exact(
                    "increment", "ownerChannel", request);
            TimelineEntry first = journal.append(timeline, operation, 100L);
            TimelineEntry second = journal.append(timeline, operation, 200L);
            TimelineEntry cutoff = journal.append(timeline, operation, 300L);
            AtomicInteger surfaceResolutions = new AtomicInteger();

            // when
            Supplier<String> sourceSurface = () -> {
                surfaceResolutions.incrementAndGet();
                return "alice-owner-surface";
            };

            // then
            HistoricalStep.EligibleEntry eligible = assertInstanceOf(
                    HistoricalStep.EligibleEntry.class,
                    journal.nextHistoricalStep(
                            null,
                            cutoff.sourceOrderKey(),
                            null,
                            ignored -> true,
                            7L,
                            11L,
                            sourceSurface));
            assertEquals(first, eligible.entry());
            assertEquals(first.sourceOrderKey(), eligible.nextExclusive());
            assertEquals(0, surfaceResolutions.get(),
                    "eligible rows must not calculate completeness identity");

            HistoricalStep.Complete complete = assertInstanceOf(
                    HistoricalStep.Complete.class,
                    journal.nextHistoricalStep(
                            null,
                            cutoff.sourceOrderKey(),
                            null,
                            ignored -> false,
                            7L,
                            11L,
                            sourceSurface));
            assertEquals(3L, complete.evidence().journalRevision());
            assertEquals(1, surfaceResolutions.get());

            HistoricalStep.CompleteEmpty empty = assertInstanceOf(
                    HistoricalStep.CompleteEmpty.class,
                    journal.nextHistoricalStep(
                            second.sourceOrderKey(),
                            cutoff.sourceOrderKey(),
                            null,
                            ignored -> true,
                            7L,
                            11L,
                            sourceSurface));
            assertEquals(cutoff.sourceOrderKey(),
                    empty.evidence().cutoffExclusive());
            assertEquals(2, surfaceResolutions.get(),
                    "each completeness proof resolves identity exactly once");

            availability.makeUnavailable("provider maintenance");
            HistoricalStep.Unavailable unavailable = assertInstanceOf(
                    HistoricalStep.Unavailable.class,
                    journal.nextHistoricalStep(
                            null,
                            cutoff.sourceOrderKey(),
                            null,
                            ignored -> true,
                            7L,
                            11L,
                            sourceSurface));
            assertEquals("provider maintenance", unavailable.diagnostic());
            assertEquals(2, surfaceResolutions.get());

            availability.invalidateEvidence("provider cursor mismatch");
            assertInstanceOf(
                    HistoricalStep.InvalidEvidence.class,
                    journal.nextHistoricalStep(
                            null,
                            cutoff.sourceOrderKey(),
                            null,
                            ignored -> true,
                            7L,
                            11L,
                            sourceSurface));
            assertEquals(2, surfaceResolutions.get());
            availability.makeAvailable();
            assertInstanceOf(
                    HistoricalStep.EligibleEntry.class,
                    journal.nextHistoricalStep(
                            null,
                            cutoff.sourceOrderKey(),
                            null,
                            ignored -> true,
                            7L,
                            11L,
                            sourceSurface));
            assertEquals(2, surfaceResolutions.get());
            assertEquals(2L, metrics.snapshot().counters().getOrDefault(
                    "journal.sourceSurfaceIdentitiesResolved", 0L));
        }
    }

    @Test
    void completenessEvidenceFailsClosedForEveryStaleIdentity() {
        // given

        ExternalOrderKey cutoff = ExternalOrderKey.of(List.of(
                100L, "timeline", "cutoff"));

        // when
        CompletenessEvidence evidence = new CompletenessEvidence(
                5L, 7L, 11L, cutoff, "surface-v1");

        // then
        assertTrue(evidence.isCurrentFor(
                5L, 7L, 11L, cutoff, "surface-v1"));
        assertFalse(evidence.isCurrentFor(
                6L, 7L, 11L, cutoff, "surface-v1"));
        assertFalse(evidence.isCurrentFor(
                5L, 8L, 11L, cutoff, "surface-v1"));
        assertFalse(evidence.isCurrentFor(
                5L, 7L, 12L, cutoff, "surface-v1"));
        assertFalse(evidence.isCurrentFor(
                5L, 7L, 11L,
                ExternalOrderKey.of(List.of(101L, "timeline", "cutoff")),
                "surface-v1"));
        assertFalse(evidence.isCurrentFor(
                5L, 7L, 11L, cutoff, "surface-v2"));
        assertThrows(IllegalArgumentException.class,
                () -> new CompletenessEvidence(
                        -1L, 0L, 0L, cutoff, "surface"));
    }

    @Test
    void shuffledSparseHistoryAdvancesByCursorWithoutRestartScanning() {
        // given

        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);

        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            InMemoryTimelineJournal journal = new InMemoryTimelineJournal(
                    new WholeRequestEntryFactory(runtime, objects, metrics),
                    metrics);
            ExactValue request = objects.put(
                    new Node().value("observe"), "historical-scale-test");
            Operation operation = Operation.exact(
                    "observe", "ownerChannel", request);
            List<TimelineEntry> insertionOrder = new ArrayList<>();

            for (int batch = 0; batch < TIMELINE_COUNT; batch++) {
                int timelineNumber = batch * 5 % TIMELINE_COUNT;
                Timeline timeline = new Timeline(
                        "large-history-" + timelineNumber,
                        "actor-" + timelineNumber);
                for (int offset = timelineNumber;
                        offset < LARGE_ENTRY_COUNT;
                        offset += TIMELINE_COUNT) {
                    insertionOrder.add(journal.append(
                            timeline, operation, offset + 1L));
                }
            }

            // when
            List<TimelineEntry> canonicalOrder = insertionOrder.stream()
                    .sorted(Comparator.comparing(
                            TimelineEntry::sourceOrderKey))
                    .toList();

            // then
            assertFalse(insertionOrder.equals(canonicalOrder),
                    "fixture must not accidentally use source order");

            EngineMetrics.MetricsSnapshot beforeOrdered = metrics.snapshot();
            List<TimelineEntry> orderedRead = new ArrayList<>();
            ExternalOrderKey orderedCursor = null;
            while (true) {
                var next = journal.nextExternal(
                        orderedCursor,
                        canonicalOrder.get(canonicalOrder.size() - 1)
                                .sourceOrderKey());
                if (next.isEmpty()) {
                    break;
                }
                TimelineEntry entry = next.orElseThrow();
                orderedRead.add(entry);
                orderedCursor = entry.sourceOrderKey();
            }
            EngineMetrics.MetricsSnapshot afterOrdered = metrics.snapshot();

            assertEquals(canonicalOrder, orderedRead);
            assertEquals(LARGE_ENTRY_COUNT, deltaCounter(
                    beforeOrdered, afterOrdered,
                    "journal.orderedCursorReads"));

            ExternalOrderKey cutoff = ExternalOrderKey.of(List.of(
                    BigInteger.valueOf(LARGE_ENTRY_COUNT + 1L),
                    "cutoff",
                    "cutoff"));
            List<TimelineEntry> expectedSparse = canonicalOrder.stream()
                    .filter(entry -> entry.timestampMicros() % 31L == 0L)
                    .toList();
            EngineMetrics.MetricsSnapshot beforeSparse = metrics.snapshot();
            List<TimelineEntry> actualSparse = new ArrayList<>();
            ExternalOrderKey sparseCursor = null;
            while (true) {
                HistoricalStep step = journal.nextHistoricalStep(
                        sparseCursor,
                        cutoff,
                        null,
                        entry -> entry.timestampMicros() % 31L == 0L,
                        7L,
                        11L,
                        () -> "large-sparse-surface");
                if (step instanceof HistoricalStep.EligibleEntry eligible) {
                    if (sparseCursor != null) {
                        assertTrue(eligible.nextExclusive().compareTo(
                                sparseCursor) > 0);
                    }
                    actualSparse.add(eligible.entry());
                    sparseCursor = eligible.nextExclusive();
                    continue;
                }
                HistoricalStep.Complete complete = assertInstanceOf(
                        HistoricalStep.Complete.class, step);
                assertEquals(cutoff, complete.evidence().cutoffExclusive());
                break;
            }
            EngineMetrics.MetricsSnapshot afterSparse = metrics.snapshot();

            assertEquals(expectedSparse, actualSparse);
            assertEquals(expectedSparse.size() + 1L, deltaCounter(
                    beforeSparse, afterSparse,
                    "journal.historicalWindowsOpened"));
            assertEquals(LARGE_ENTRY_COUNT, deltaCounter(
                    beforeSparse, afterSparse,
                    "journal.historicalCursorProbes"),
                    "each journal row is probed once across the sparse walk");
            assertEquals(0L, deltaCounter(
                    beforeSparse, afterSparse,
                    "journal.orderedCursorReads"));
        }
    }

    private static long deltaCounter(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            String name) {
        return after.counters().getOrDefault(name, 0L)
                - before.counters().getOrDefault(name, 0L);
    }
}
