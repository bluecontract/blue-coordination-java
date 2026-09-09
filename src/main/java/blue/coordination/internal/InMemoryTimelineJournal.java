package blue.coordination.internal;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Deterministic in-memory journal; each exact entry is retained once. */
final class InMemoryTimelineJournal {
    private final WholeRequestEntryFactory entryFactory;
    private final EngineMetrics metrics;
    private final Map<String, TimelineEntry> byBlueId = new LinkedHashMap<>();
    private final Map<String, List<TimelineEntry>> byTimeline =
            new LinkedHashMap<>();
    private final Map<String, String> previousByTimeline = new LinkedHashMap<>();
    private final Map<String, Long> sequenceByTimeline = new LinkedHashMap<>();
    private final NavigableMap<ExternalOrderKey, TimelineEntry> externalByOrder =
            new TreeMap<>();
    private final List<TimelineEntry> appendOrder = new ArrayList<>();
    private final HistoricalAvailabilityControl historicalAvailability;
    private long globalSequence;
    private long revision;

    public InMemoryTimelineJournal(
            WholeRequestEntryFactory entryFactory,
            EngineMetrics metrics) {
        this(entryFactory, metrics, new HistoricalAvailabilityControl());
    }

    InMemoryTimelineJournal(
            WholeRequestEntryFactory entryFactory,
            EngineMetrics metrics,
            HistoricalAvailabilityControl historicalAvailability) {
        this.entryFactory = Objects.requireNonNull(entryFactory, "entryFactory");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.historicalAvailability = Objects.requireNonNull(
                historicalAvailability, "historicalAvailability");
    }

    public synchronized TimelineEntry append(
            Timeline timeline,
            Operation operation,
            long timestampMicros) {
        return appendInternal(timeline, operation, timestampMicros);
    }

    /** Admits one lossless exact Timeline Entry under provider ordering. */
    public synchronized TimelineEntry appendExact(
            Timeline timeline,
            blue.coordination.api.ExactValue exactEvent) {
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(exactEvent, "exactEvent");
        TimelineEntry duplicate = byBlueId.get(exactEvent.blueId());
        if (duplicate != null) {
            if (!duplicate.exactEvent().sameExactValue(exactEvent)) {
                throw new IllegalStateException(
                        "Conflicting exact Timeline Entry "
                                + exactEvent.blueId());
            }
            metrics.increment("journal.duplicateEntries");
            return duplicate;
        }
        long nextGlobalSequence = Math.addExact(globalSequence, 1L);
        long nextTimelineSequence = Math.addExact(
                sequenceByTimeline.getOrDefault(
                        timeline.timelineId(), 0L), 1L);
        TimelineEntry entry = entryFactory.createExact(
                timeline,
                exactEvent,
                nextGlobalSequence,
                nextTimelineSequence);
        return publish(entry, previousIdentity(exactEvent));
    }

    private TimelineEntry appendInternal(
            Timeline timeline,
            Operation operation,
            long timestampMicros) {
        Objects.requireNonNull(timeline, "timeline");
        long nextGlobalSequence = Math.addExact(globalSequence, 1L);
        long nextTimelineSequence = Math.addExact(
                sequenceByTimeline.getOrDefault(timeline.timelineId(), 0L), 1L);
        TimelineEntry entry = entryFactory.create(
                timeline,
                previousByTimeline.get(timeline.timelineId()),
                operation,
                timestampMicros,
                nextGlobalSequence,
                nextTimelineSequence);
        return publish(entry, previousByTimeline.get(timeline.timelineId()));
    }

    private TimelineEntry publish(
            TimelineEntry entry,
            String declaredPreviousBlueId) {
        TimelineEntry existing = byBlueId.get(entry.blueId());
        if (existing != null) {
            if (!existing.exactEvent().sameExactValue(entry.exactEvent())) {
                throw new IllegalStateException(
                        "Conflicting exact Timeline Entry " + entry.blueId());
            }
            metrics.increment("journal.duplicateEntries");
            return existing;
        }
        String timelineId = entry.timeline().timelineId();
        if (!Objects.equals(
                previousByTimeline.get(timelineId),
                declaredPreviousBlueId)) {
            throw new IllegalArgumentException(
                    "Timeline Entry predecessor does not match the accepted "
                            + "Timeline head");
        }
        List<TimelineEntry> timelineEntries = byTimeline.getOrDefault(
                timelineId, List.of());
        if (!timelineEntries.isEmpty()) {
            TimelineEntry last = timelineEntries.get(timelineEntries.size() - 1);
            if (entry.timestampMicros() <= last.timestampMicros()) {
                throw new IllegalArgumentException(
                        "Timeline timestamps must increase monotonically");
            }
        }
        if (externalByOrder.containsKey(entry.sourceOrderKey())) {
            throw new IllegalStateException(
                    "Duplicate external source order "
                            + entry.sourceOrderKey());
        }
        long nextRevision = Math.addExact(revision, 1L);
        timelineEntries = byTimeline.computeIfAbsent(
                timelineId, ignored -> new ArrayList<>());
        timelineEntries.add(entry);
        byBlueId.put(entry.blueId(), entry);
        externalByOrder.put(entry.sourceOrderKey(), entry);
        previousByTimeline.put(timelineId, entry.blueId());
        globalSequence = entry.globalSequence();
        sequenceByTimeline.put(timelineId, entry.timelineSequence());
        appendOrder.add(entry);
        revision = nextRevision;
        metrics.increment("journal.entriesStoredWhole");
        metrics.increment("append.journalOperations");
        metrics.increment("requestsStoredWhole");
        return entry;
    }

    private static String previousIdentity(
            blue.coordination.api.ExactValue exactEvent) {
        blue.language.snapshot.FrozenNode previous =
                exactEvent.canonicalAt("/prevEntry");
        return previous == null
                ? null
                : previous.isReferenceOnly()
                        ? previous.getReferenceBlueId()
                        : previous.blueId();
    }

    public synchronized Optional<TimelineEntry> byBlueId(String blueId) {
        return Optional.ofNullable(byBlueId.get(
                Objects.requireNonNull(blueId, "blueId")));
    }

    /** Immutable canonical append-order view used by read-only audit adapters. */
    synchronized List<TimelineEntry> entries() {
        return List.copyOf(appendOrder);
    }

    /** Immutable canonical append-order view for one Timeline. */
    synchronized List<TimelineEntry> entries(String timelineId) {
        return List.copyOf(byTimeline.getOrDefault(
                Objects.requireNonNull(timelineId, "timelineId"),
                List.of()));
    }

    /**
     * Returns the canonical journal object and rejects caller-forged metadata
     * even when the supplied value reuses a known BlueId.
     */
    public synchronized TimelineEntry requireCanonical(TimelineEntry supplied) {
        TimelineEntry canonical = byBlueId.get(Objects.requireNonNull(
                supplied, "supplied").blueId());
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "Timeline Entry is not journaled: " + supplied.blueId());
        }
        if (!canonical.equals(supplied)
                || !canonical.exactEvent().sameExactValue(
                        supplied.exactEvent())) {
            throw new IllegalArgumentException(
                    "Timeline Entry metadata does not match canonical journal "
                            + "evidence: " + supplied.blueId());
        }
        return canonical;
    }

    /** Canonical next external entry after a completed source-order cursor. */
    public synchronized Optional<TimelineEntry> nextExternal(
            ExternalOrderKey afterExclusive,
            ExternalOrderKey throughInclusive) {
        Map.Entry<ExternalOrderKey, TimelineEntry> candidate =
                afterExclusive == null
                        ? externalByOrder.firstEntry()
                        : externalByOrder.higherEntry(afterExclusive);
        if (candidate == null || throughInclusive != null
                && candidate.getKey().compareTo(throughInclusive) > 0) {
            return Optional.empty();
        }
        metrics.increment("journal.orderedCursorReads");
        return Optional.of(candidate.getValue());
    }

    /** True only for a canonical frontier retained by this journal. */
    synchronized boolean containsExternalOrder(ExternalOrderKey frontier) {
        return externalByOrder.containsKey(Objects.requireNonNull(
                frontier, "frontier"));
    }

    /**
     * Returns a closed result for one dynamically evaluated historical probe.
     * Completion is bound to the exact journal, route, graph, cutoff, and
     * active source-surface identities used for this probe.
     */
    synchronized HistoricalStep nextHistoricalStep(
            ExternalOrderKey afterExclusive,
            ExternalOrderKey cutoffExclusive,
            String excludedEntryBlueId,
            Predicate<TimelineEntry> eligible,
            long routeIndexGeneration,
            long graphGeneration,
            Supplier<String> sourceSurfaceIdentity) {
        Objects.requireNonNull(cutoffExclusive, "cutoffExclusive");
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(sourceSurfaceIdentity, "sourceSurfaceIdentity");
        if (routeIndexGeneration < 0L || graphGeneration < 0L) {
            throw new IllegalArgumentException(
                    "historical generations must be non-negative");
        }
        metrics.increment("journal.historicalWindowsOpened");
        Optional<HistoricalStep> blocked = historicalAvailability.blockedStep();
        if (blocked.isPresent()) {
            return blocked.orElseThrow();
        }
        NavigableMap<ExternalOrderKey, TimelineEntry> tail =
                afterExclusive == null
                        ? externalByOrder
                        : externalByOrder.tailMap(afterExclusive, false);
        boolean sawEntryInWindow = false;
        for (Map.Entry<ExternalOrderKey, TimelineEntry> item
                : tail.entrySet()) {
            metrics.increment("journal.historicalCursorProbes");
            if (item.getKey().compareTo(cutoffExclusive) >= 0) {
                break;
            }
            sawEntryInWindow = true;
            TimelineEntry entry = item.getValue();
            if (entry.blueId().equals(excludedEntryBlueId)
                    || !eligible.test(entry)) {
                continue;
            }
            return new HistoricalStep.EligibleEntry(
                    entry, entry.sourceOrderKey());
        }
        metrics.increment("journal.sourceSurfaceIdentitiesResolved");
        CompletenessEvidence evidence = new CompletenessEvidence(
                revision,
                routeIndexGeneration,
                graphGeneration,
                cutoffExclusive,
                requireText(sourceSurfaceIdentity.get(),
                        "sourceSurfaceIdentity"));
        return sawEntryInWindow
                ? new HistoricalStep.Complete(evidence)
                : new HistoricalStep.CompleteEmpty(evidence);
    }

    /** Authenticates the provider-owned empty journal at a frozen admission boundary. */
    synchronized void requireBeginningAdmission(boolean requiresProvider) {
        if (requiresProvider) {
            var blocked = historicalAvailability.blockedStep();
            if (blocked.orElse(null) instanceof HistoricalStep.Unavailable unavailable) {
                throw new blue.coordination.api.CoordinationException(
                        blue.coordination.api.CoordinationErrorCode.NEEDS_RESOURCES,
                        unavailable.diagnostic(), null, Map.of("reason", "DEFERRED_UNAVAILABLE"));
            }
            if (blocked.orElse(null) instanceof HistoricalStep.InvalidEvidence invalid) {
                throw new blue.coordination.api.CoordinationException(
                        blue.coordination.api.CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE,
                        invalid.diagnostic(), null, Map.of("reason", "INVALID_EVIDENCE"));
            }
            if (blocked.isPresent()) throw new IllegalStateException("Unknown historical provider disposition");
        }
        if (!byBlueId.isEmpty() || !externalByOrder.isEmpty()) {
            throw new blue.coordination.api.CoordinationException(
                    blue.coordination.api.CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE,
                    "BEGINNING admission does not match the authoritative journal", null,
                    Map.of("reason", "NONEMPTY_HISTORY"));
        }
    }

    public synchronized ExternalOrderKey latestExternalOrder() {
        return externalByOrder.isEmpty() ? null : externalByOrder.lastKey();
    }

    public synchronized int size() { return byBlueId.size(); }

    /** Monotonic identity of the exact currently published journal content. */
    synchronized long revision() { return revision; }

    synchronized void makeHistoricalUnavailable(String diagnostic) {
        historicalAvailability.makeUnavailable(diagnostic);
    }

    synchronized void makeHistoricalAvailable() {
        historicalAvailability.makeAvailable();
    }

    synchronized void invalidateHistoricalEvidence(String diagnostic) {
        historicalAvailability.invalidateEvidence(diagnostic);
    }

    /** Opens an O(1) append savepoint without copying Timeline indexes. */
    public synchronized Mark mark() {
        return new Mark(globalSequence, appendOrder.size());
    }
    /** Removes only entries appended after the exact savepoint. */
    public synchronized void rollbackTo(Mark mark) {
        Objects.requireNonNull(mark, "mark");
        if (mark.appendOrderSize() > appendOrder.size()
                || mark.globalSequence() > globalSequence) {
            throw new IllegalStateException(
                    "Journal mark is ahead of the append frontier");
        }
        long removedCount = 0L;
        while (appendOrder.size() > mark.appendOrderSize()) {
            TimelineEntry removed = appendOrder.remove(
                    appendOrder.size() - 1);
            String timelineId = removed.timeline().timelineId();
            List<TimelineEntry> entries = byTimeline.get(timelineId);
            if (entries == null || entries.isEmpty()
                    || entries.get(entries.size() - 1) != removed) {
                throw new IllegalStateException(
                        "Timeline append index is inconsistent for "
                                + timelineId);
            }
            entries.remove(entries.size() - 1);
            byBlueId.remove(removed.blueId());
            externalByOrder.remove(removed.sourceOrderKey());
            if (entries.isEmpty()) {
                byTimeline.remove(timelineId);
                sequenceByTimeline.remove(timelineId);
                previousByTimeline.remove(timelineId);
            } else {
                TimelineEntry previous = entries.get(entries.size() - 1);
                sequenceByTimeline.put(
                        timelineId, previous.timelineSequence());
                previousByTimeline.put(timelineId, previous.blueId());
            }
            removedCount++;
        }
        globalSequence = mark.globalSequence();
        if (removedCount > 0L) {
            revision = Math.addExact(revision, 1L);
        }
        metrics.increment("journal.rollbacks");
        metrics.add("journal.rollbackEntries", removedCount);
    }
    /** Compact append frontier; no Timeline index or body is copied. */
    public record Mark(
            long globalSequence,
            int appendOrderSize) {
        public Mark {
            if (globalSequence < 0L || appendOrderSize < 0) {
                throw new IllegalArgumentException(
                        "journal frontier must be non-negative");
            }
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
