package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.Timeline;

import blue.coordination.api.Operation;

import blue.coordination.api.EnvironmentFrontier;

import blue.coordination.api.DocumentId;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic in-memory journal; each exact entry is retained once. */
final class InMemoryTimelineJournal {
    private final WholeRequestEntryFactory entryFactory;
    private final EngineMetrics metrics;
    private final Map<String, TimelineEntry> byBlueId = new LinkedHashMap<>();
    private final Map<String, List<TimelineEntry>> byTimeline =
            new LinkedHashMap<>();
    private final Map<String, String> previousByTimeline = new LinkedHashMap<>();
    private final Map<String, Long> sequenceByTimeline = new LinkedHashMap<>();
    private long globalSequence;

    public InMemoryTimelineJournal(
            WholeRequestEntryFactory entryFactory,
            EngineMetrics metrics) {
        this.entryFactory = Objects.requireNonNull(entryFactory, "entryFactory");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public synchronized TimelineEntry append(
            Timeline timeline,
            Operation operation,
            long timestampMicros) {
        return appendInternal(
                timeline, operation, timestampMicros, false, null, null, null);
    }

    public synchronized TimelineEntry appendProcessorManaged(
            Timeline timeline,
            Operation operation,
            long timestampMicros,
            DocumentId target,
            TimelineEntry.CatchUpCause cause,
            ExternalOrderKey originalSourceOrder) {
        return appendInternal(
                timeline,
                operation,
                timestampMicros,
                true,
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(cause, "cause"),
                Objects.requireNonNull(originalSourceOrder, "originalSourceOrder"));
    }

    private TimelineEntry appendInternal(
            Timeline timeline,
            Operation operation,
            long timestampMicros,
            boolean processorManaged,
            DocumentId target,
            TimelineEntry.CatchUpCause cause,
            ExternalOrderKey originalSourceOrder) {
        Objects.requireNonNull(timeline, "timeline");
        long nextGlobalSequence = Math.addExact(globalSequence, 1L);
        long nextTimelineSequence = Math.addExact(
                sequenceByTimeline.getOrDefault(timeline.timelineId(), 0L), 1L);
        Map<String, Long> frontierSequences = new LinkedHashMap<>(
                sequenceByTimeline);
        frontierSequences.put(timeline.timelineId(), nextTimelineSequence);
        EnvironmentFrontier appendFrontier = new EnvironmentFrontier(
                nextGlobalSequence, frontierSequences);
        TimelineEntry entry = entryFactory.create(
                timeline,
                previousByTimeline.get(timeline.timelineId()),
                operation,
                timestampMicros,
                nextGlobalSequence,
                nextTimelineSequence,
                appendFrontier,
                processorManaged,
                target,
                cause,
                originalSourceOrder);
        TimelineEntry existing = byBlueId.get(entry.blueId());
        if (existing != null) {
            if (!existing.exactEvent().sameExactValue(entry.exactEvent())) {
                throw new IllegalStateException(
                        "Conflicting exact Timeline Entry " + entry.blueId());
            }
            metrics.increment("journal.duplicateEntries");
            return existing;
        }
        List<TimelineEntry> timelineEntries = byTimeline.computeIfAbsent(
                timeline.timelineId(), ignored -> new ArrayList<>());
        if (!timelineEntries.isEmpty()) {
            TimelineEntry last = timelineEntries.get(timelineEntries.size() - 1);
            if (entry.journalOrderKey().compareTo(last.journalOrderKey()) <= 0) {
                throw new IllegalArgumentException(
                        "Timeline append order must increase monotonically");
            }
        }
        timelineEntries.add(entry);
        byBlueId.put(entry.blueId(), entry);
        previousByTimeline.put(timeline.timelineId(), entry.blueId());
        globalSequence = nextGlobalSequence;
        sequenceByTimeline.put(timeline.timelineId(), nextTimelineSequence);
        metrics.increment("journal.entriesStoredWhole");
        metrics.increment("append.journalOperations");
        metrics.increment("requestsStoredWhole");
        return entry;
    }

    public synchronized Optional<TimelineEntry> byBlueId(String blueId) {
        return Optional.ofNullable(byBlueId.get(
                Objects.requireNonNull(blueId, "blueId")));
    }

    public synchronized List<TimelineEntry> entries(
            String timelineId,
            ExternalOrderKey afterExclusive,
            ExternalOrderKey throughInclusive) {
        List<TimelineEntry> source = byTimeline.getOrDefault(
                Objects.requireNonNull(timelineId, "timelineId"), List.of());
        List<TimelineEntry> result = new ArrayList<>();
        for (TimelineEntry entry : source) {
            if (afterExclusive != null
                    && entry.sourceOrderKey().compareTo(afterExclusive) <= 0) {
                continue;
            }
            if (throughInclusive != null
                    && entry.sourceOrderKey().compareTo(throughInclusive) > 0) {
                continue;
            }
            result.add(entry);
        }
        result.sort(Comparator.comparing(TimelineEntry::sourceOrderKey));
        metrics.add("journal.windowEntriesRead", result.size());
        return Collections.unmodifiableList(result);
    }

    public synchronized List<TimelineEntry> allEntries() {
        List<TimelineEntry> result = new ArrayList<>(byBlueId.values());
        result.sort(Comparator.comparing(TimelineEntry::journalOrderKey));
        return Collections.unmodifiableList(result);
    }

    public synchronized EnvironmentFrontier frontier() {
        return new EnvironmentFrontier(globalSequence, sequenceByTimeline);
    }

    public synchronized List<TimelineEntry> entriesThrough(
            String timelineId,
            EnvironmentFrontier frontier) {
        Objects.requireNonNull(timelineId, "timelineId");
        Objects.requireNonNull(frontier, "frontier");
        List<TimelineEntry> source = byTimeline.getOrDefault(
                timelineId, List.of());
        long throughSequence = frontier.sequenceFor(timelineId);
        List<TimelineEntry> result = new ArrayList<>();
        for (TimelineEntry entry : source) {
            if (entry.timelineSequence() <= throughSequence
                    && entry.globalSequence() <= frontier.globalSequence()) {
                result.add(entry);
            }
        }
        metrics.add("childHistoricalEntriesRead", result.size());
        return Collections.unmodifiableList(result);
    }

    public synchronized int size() {
        return byBlueId.size();
    }

    /** Append frontier used to roll back processor-managed entries. */
    public synchronized Mark mark() {
        return new Mark(globalSequence, sequenceByTimeline);
    }
    /** Restores the exact journal frontier captured before one dispatch. */
    public synchronized void rollbackTo(Mark mark) {
        Objects.requireNonNull(mark, "mark");
        List<String> timelines = new ArrayList<>(byTimeline.keySet());
        for (String timelineId : timelines) {
            List<TimelineEntry> entries = byTimeline.get(timelineId);
            long retained = mark.sequenceByTimeline().getOrDefault(
                    timelineId, 0L);
            if (retained > entries.size()) {
                throw new IllegalStateException(
                        "Journal mark is ahead of Timeline " + timelineId);
            }
            while (entries.size() > retained) {
                TimelineEntry removed = entries.remove(entries.size() - 1);
                byBlueId.remove(removed.blueId());
            }
            if (entries.isEmpty()) {
                byTimeline.remove(timelineId);
            }
        }
        sequenceByTimeline.clear();
        sequenceByTimeline.putAll(mark.sequenceByTimeline());
        previousByTimeline.clear();
        for (Map.Entry<String, List<TimelineEntry>> timeline
                : byTimeline.entrySet()) {
            List<TimelineEntry> entries = timeline.getValue();
            previousByTimeline.put(
                    timeline.getKey(),
                    entries.get(entries.size() - 1).blueId());
        }
        globalSequence = mark.globalSequence();
        metrics.increment("journal.rollbacks");
    }
    /** Compact append frontier; no event or request body is copied. */
    public record Mark(
            long globalSequence,
            Map<String, Long> sequenceByTimeline) {
        public Mark {
            if (globalSequence < 0L) {
                throw new IllegalArgumentException(
                        "globalSequence must be non-negative");
            }
            sequenceByTimeline = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            sequenceByTimeline, "sequenceByTimeline")));
        }
    }
}
