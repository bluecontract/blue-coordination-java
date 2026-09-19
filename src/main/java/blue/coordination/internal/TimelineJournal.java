package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore.State;
import blue.language.processor.ExternalOrderKey;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Existing journal decisions shared by all physical stores. */
interface TimelineJournal {
    TimelineEntry append(Timeline timeline, Operation operation, long timestampMicros);
    TimelineEntry appendExact(Timeline timeline, ExactValue exactEvent);
    Optional<TimelineEntry> byBlueId(String blueId);
    Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order);
    List<TimelineEntry> entries();
    List<TimelineEntry> entries(String timelineId);
    default List<TimelineEntry> entriesThrough(String timelineId, ExternalOrderKey through) {
        return entries(timelineId).stream().filter(entry -> entry.sourceOrderKey().compareTo(through) <= 0).toList();
    }
    blue.coordination.api.TimelineJournalPosition position(String timelineId);
    TimelineEntry requireCanonical(TimelineEntry supplied);
    Optional<TimelineEntry> nextExternal(ExternalOrderKey after, ExternalOrderKey through);
    boolean containsExternalOrder(ExternalOrderKey frontier);
    void requireBeginningAdmission(boolean requiresProvider);
    HistoricalStep nextHistoricalStep(ExternalOrderKey after, ExternalOrderKey cutoff,
            String excluded, Predicate<TimelineEntry> eligible, long routeGeneration,
            long graphGeneration, Supplier<String> sourceSurfaceIdentity);
    boolean scopedCoverage();
    HistoricalStep sourceCoverage(java.util.Set<String> timelines, ExternalOrderKey cutoff,
            long routeGeneration, long graphGeneration, String surfaceIdentity);
    ExternalOrderKey latestExternalOrder();
    int size();
    long revision();
    void makeHistoricalUnavailable(String diagnostic);
    void makeHistoricalAvailable();
    void invalidateHistoricalEvidence(String diagnostic);
    Mark mark();
    void rollbackTo(Mark mark);

    /** Invocation-local savepoint, never a persisted or caller-forged head. */
    record Mark(State state, Object owner) {
        long globalSequence() { return state.globalSequence(); }
        int appendOrderSize() { return state.entryCount(); }
    }
}
