package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.TimelineJournalStore.*;
import blue.coordination.api.TimelineJournalStorageException;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The existing journal machine over pinned physical entry indexes. */
class DefaultTimelineJournal implements TimelineJournal {
    private final WholeRequestEntryFactory entryFactory;
    private final EngineMetrics metrics;
    private final TimelineJournalStore store;
    private final Object owner = new Object();
    private State ownedState;

    DefaultTimelineJournal(WholeRequestEntryFactory entryFactory,
            EngineMetrics metrics, TimelineJournalStore store) {
        this.entryFactory = Objects.requireNonNull(entryFactory, "entryFactory");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.store = Objects.requireNonNull(store, "store");
        try (ReadView view = open()) { ownedState = view.state(); }
    }

    @Override public synchronized TimelineEntry append(Timeline timeline,
            Operation operation, long timestampMicros) {
        Objects.requireNonNull(timeline, "timeline");
        State state;
        TimelineEntry entry;
        try (ReadView view = open()) {
            state = view.state();
            requireOwned(state);
            TimelineEntry head = head(view, timeline.timelineId()).orElse(null);
            String previous = head == null ? null : head.blueId();
            entry = entryFactory.create(timeline, previous, operation, timestampMicros,
                    Math.addExact(state.globalSequence(), 1),
                    head == null ? 1 : Math.addExact(head.timelineSequence(), 1));
            TimelineEntry duplicate = validatePublication(view, entry, previous);
            if (duplicate != null) return duplicate;
        }
        return publish(state, entry);
    }

    @Override public synchronized TimelineEntry appendExact(Timeline timeline, ExactValue exactEvent) {
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(exactEvent, "exactEvent");
        State state;
        TimelineEntry entry;
        try (ReadView view = open()) {
            state = view.state();
            requireOwned(state);
            TimelineEntry duplicate = byId(view, exactEvent.blueId()).orElse(null);
            if (duplicate != null) return duplicate(duplicate, exactEvent);
            TimelineEntry head = head(view, timeline.timelineId()).orElse(null);
            entry = entryFactory.createExact(timeline, exactEvent,
                    Math.addExact(state.globalSequence(), 1),
                    head == null ? 1 : Math.addExact(head.timelineSequence(), 1));
            duplicate = validatePublication(view, entry, previousIdentity(exactEvent));
            if (duplicate != null) return duplicate;
        }
        return publish(state, entry);
    }

    private TimelineEntry duplicate(TimelineEntry existing, ExactValue exact) {
        if (!existing.exactEvent().sameExactValue(exact)) {
            throw new IllegalStateException("Conflicting exact Timeline Entry " + exact.blueId());
        }
        metrics.increment("journal.duplicateEntries");
        return existing;
    }

    private TimelineEntry validatePublication(ReadView view, TimelineEntry entry, String previous) {
        TimelineEntry existing = byId(view, entry.blueId()).orElse(null);
        if (existing != null) return duplicate(existing, entry.exactEvent());
        TimelineEntry head = head(view, entry.timeline().timelineId()).orElse(null);
        if (!Objects.equals(head == null ? null : head.blueId(), previous)) {
            throw new IllegalArgumentException(
                    "Timeline Entry predecessor does not match the accepted Timeline head");
        }
        if (head != null && entry.timestampMicros() <= head.timestampMicros()) {
            throw new IllegalArgumentException("Timeline timestamps must increase monotonically");
        }
        if (view.atExternalOrder(entry.sourceOrderKey()).isPresent()) {
            throw new IllegalStateException("Duplicate external source order " + entry.sourceOrderKey());
        }
        return null;
    }

    private TimelineEntry publish(State state, TimelineEntry entry) {
        State next = new State(entry.globalSequence(), Math.addExact(state.entryCount(), 1),
                Math.addExact(state.revision(), 1), state.availability());
        apply(state, new Append(entry, next));
        metrics.increment("journal.entriesStoredWhole");
        metrics.increment("append.journalOperations");
        metrics.increment("requestsStoredWhole");
        return entry;
    }

    static String previousIdentity(ExactValue event) {
        FrozenNode previous = event.canonicalAt("/prevEntry");
        return previous == null ? null : previous.isReferenceOnly()
                ? previous.getReferenceBlueId() : previous.blueId();
    }

    @Override public synchronized Optional<TimelineEntry> byBlueId(String blueId) {
        try (ReadView view = open()) { return byId(view, Objects.requireNonNull(blueId, "blueId")); }
    }

    @Override public synchronized List<TimelineEntry> entries() {
        try (ReadView view = open()) {
            List<TimelineEntry> result = new ArrayList<>();
            for (int i = 0; i < view.state().entryCount(); i++) {
                TimelineEntry entry = checked(view, view.atAppendPosition(i).orElseThrow(
                        () -> corrupt("Missing append row")));
                if (entry.globalSequence() != (long) i + 1) throw corrupt("Wrong append position");
                result.add(entry);
            }
            return List.copyOf(result);
        }
    }

    @Override public synchronized List<TimelineEntry> entries(String timelineId) {
        Objects.requireNonNull(timelineId, "timelineId");
        try (ReadView view = open()) {
            List<TimelineEntry> result = new ArrayList<>();
            TimelineEntry head = head(view, timelineId).orElse(null);
            long count = head == null ? 0 : head.timelineSequence();
            for (long i = 1; i <= count; i++) {
                TimelineEntry entry = checked(view, view.atTimelineSequence(timelineId, i)
                        .orElseThrow(() -> corrupt("Missing Timeline row")));
                if (entry.timelineSequence() != i || !entry.timeline().timelineId().equals(timelineId)) {
                    throw corrupt("Wrong Timeline position");
                }
                result.add(entry);
            }
            return List.copyOf(result);
        }
    }

    @Override public synchronized TimelineEntry requireCanonical(TimelineEntry supplied) {
        TimelineEntry canonical = byBlueId(Objects.requireNonNull(supplied, "supplied").blueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Timeline Entry is not journaled: " + supplied.blueId()));
        if (!sameRow(canonical, supplied)) {
            throw new IllegalArgumentException(
                    "Timeline Entry metadata does not match canonical journal evidence: " + supplied.blueId());
        }
        return canonical;
    }

    @Override public synchronized Optional<TimelineEntry> nextExternal(
            ExternalOrderKey after, ExternalOrderKey through) {
        try (ReadView view = open()) {
            Optional<TimelineEntry> candidate = next(view, after);
            if (candidate.isEmpty() || through != null
                    && candidate.orElseThrow().sourceOrderKey().compareTo(through) > 0) {
                return Optional.empty();
            }
            metrics.increment("journal.orderedCursorReads");
            return candidate;
        }
    }

    @Override public synchronized boolean containsExternalOrder(ExternalOrderKey frontier) {
        try (ReadView view = open()) {
            Optional<TimelineEntry> entry = view.atExternalOrder(Objects.requireNonNull(frontier, "frontier"));
            if (entry.isEmpty()) return false;
            if (!checked(view, entry.orElseThrow()).sourceOrderKey().equals(frontier)) {
                throw corrupt("Wrong external order index");
            }
            return true;
        }
    }

    @Override public synchronized HistoricalStep nextHistoricalStep(ExternalOrderKey after,
            ExternalOrderKey cutoff, String excluded, Predicate<TimelineEntry> eligible,
            long routeGeneration, long graphGeneration, Supplier<String> sourceSurfaceIdentity) {
        Objects.requireNonNull(cutoff, "cutoffExclusive");
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(sourceSurfaceIdentity, "sourceSurfaceIdentity");
        if (routeGeneration < 0 || graphGeneration < 0) {
            throw new IllegalArgumentException("historical generations must be non-negative");
        }
        metrics.increment("journal.historicalWindowsOpened");
        try (ReadView view = open()) {
            State state = view.state();
            Availability availability = state.availability();
            if (availability.kind() == AvailabilityKind.UNAVAILABLE) {
                return new HistoricalStep.Unavailable(availability.diagnostic());
            }
            if (availability.kind() == AvailabilityKind.INVALID_EVIDENCE) {
                return new HistoricalStep.InvalidEvidence(availability.diagnostic());
            }
            boolean sawEntry = false;
            ExternalOrderKey cursor = after;
            while (true) {
                Optional<TimelineEntry> candidate = next(view, cursor);
                if (candidate.isEmpty()) break;
                TimelineEntry entry = candidate.orElseThrow();
                metrics.increment("journal.historicalCursorProbes");
                if (entry.sourceOrderKey().compareTo(cutoff) >= 0) break;
                sawEntry = true;
                cursor = entry.sourceOrderKey();
                if (entry.blueId().equals(excluded) || !eligible.test(entry)) continue;
                return new HistoricalStep.EligibleEntry(entry, entry.sourceOrderKey());
            }
            metrics.increment("journal.sourceSurfaceIdentitiesResolved");
            CompletenessEvidence evidence = new CompletenessEvidence(state.revision(),
                    routeGeneration, graphGeneration, cutoff,
                    requireText(sourceSurfaceIdentity.get(), "sourceSurfaceIdentity"));
            return sawEntry ? new HistoricalStep.Complete(evidence) : new HistoricalStep.CompleteEmpty(evidence);
        }
    }

    @Override public synchronized ExternalOrderKey latestExternalOrder() {
        try (ReadView view = open()) {
            ExternalOrderKey order = view.latestExternalOrder().orElse(null);
            if (order != null) {
                TimelineEntry entry = checked(view, view.atExternalOrder(order)
                        .orElseThrow(() -> corrupt("Missing latest source row")));
                if (!entry.sourceOrderKey().equals(order)) throw corrupt("Wrong latest order");
            } else if (view.state().entryCount() != 0) throw corrupt("Missing latest order");
            return order;
        }
    }

    /** The current rooted BEGINNING rule, evaluated in one pinned store view. */
    @Override public synchronized void requireBeginningAdmission(boolean requiresProvider) {
        try (ReadView view = open()) {
            State state = view.state();
            if (requiresProvider) {
                Availability availability = state.availability();
                if (availability.kind() == AvailabilityKind.UNAVAILABLE) {
                    throw new blue.coordination.api.CoordinationException(
                            blue.coordination.api.CoordinationErrorCode.NEEDS_RESOURCES,
                            availability.diagnostic(), null,
                            java.util.Map.of("reason", "DEFERRED_UNAVAILABLE"));
                }
                if (availability.kind() == AvailabilityKind.INVALID_EVIDENCE) {
                    throw new blue.coordination.api.CoordinationException(
                            blue.coordination.api.CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE,
                            availability.diagnostic(), null,
                            java.util.Map.of("reason", "INVALID_EVIDENCE"));
                }
            }
            if (state.entryCount() != 0 || view.latestExternalOrder().isPresent()) {
                throw new blue.coordination.api.CoordinationException(
                        blue.coordination.api.CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE,
                        "BEGINNING admission does not match the authoritative journal", null,
                        java.util.Map.of("reason", "NONEMPTY_HISTORY"));
            }
        }
    }

    @Override public synchronized int size() {
        try (ReadView view = open()) { return view.state().entryCount(); }
    }
    @Override public synchronized long revision() {
        try (ReadView view = open()) { return view.state().revision(); }
    }
    @Override public synchronized void makeHistoricalUnavailable(String diagnostic) {
        setAvailability(new Availability(AvailabilityKind.UNAVAILABLE, diagnostic));
    }
    @Override public synchronized void makeHistoricalAvailable() {
        setAvailability(Availability.available());
    }
    @Override public synchronized void invalidateHistoricalEvidence(String diagnostic) {
        setAvailability(new Availability(AvailabilityKind.INVALID_EVIDENCE, diagnostic));
    }

    private void setAvailability(Availability availability) {
        State state;
        try (ReadView view = open()) { state = view.state(); requireOwned(state); }
        apply(state, new SetAvailability(new State(state.globalSequence(), state.entryCount(),
                state.revision(), availability)));
    }

    @Override public synchronized Mark mark() {
        try (ReadView view = open()) {
            State state = view.state();
            requireOwned(state);
            return new Mark(state, owner);
        }
    }

    @Override public synchronized void rollbackTo(Mark mark) {
        Objects.requireNonNull(mark, "mark");
        if (mark.owner() != owner) throw new IllegalArgumentException("Journal mark belongs to another journal");
        State state;
        try (ReadView view = open()) {
            state = view.state();
            requireOwned(state);
            if (mark.appendOrderSize() > state.entryCount() || mark.globalSequence() > state.globalSequence()) {
                throw new IllegalStateException("Journal mark is ahead of the append frontier");
            }
            for (int i = state.entryCount() - 1; i >= mark.appendOrderSize(); i--) {
                TimelineEntry removed = checked(view, view.atAppendPosition(i)
                        .orElseThrow(() -> corrupt("Missing rollback row")));
                if (removed.globalSequence() != (long) i + 1) throw corrupt("Wrong rollback append index");
            }
        }
        long removed = state.entryCount() - mark.appendOrderSize();
        if (removed > 0) {
            apply(state, new Truncate(new State(mark.globalSequence(), mark.appendOrderSize(),
                    Math.addExact(state.revision(), 1), state.availability())));
        }
        metrics.increment("journal.rollbacks");
        metrics.add("journal.rollbackEntries", removed);
    }

    private Optional<TimelineEntry> byId(ReadView view, String id) {
        return view.byBlueId(id).map(entry -> {
            if (!entry.blueId().equals(id)) throw corrupt("Wrong exact entry identity");
            return checked(view, entry);
        });
    }
    private Optional<TimelineEntry> head(ReadView view, String id) {
        Optional<TimelineEntry> selected = view.timelineHead(id);
        if (selected.isEmpty() && view.atTimelineSequence(id, 1).isPresent()) {
            throw corrupt("Missing persisted Timeline head");
        }
        return selected.map(entry -> {
            if (!entry.timeline().timelineId().equals(id)) throw corrupt("Wrong Timeline head");
            TimelineEntry result = checked(view, entry);
            if (view.atTimelineSequence(id, Math.addExact(entry.timelineSequence(), 1)).isPresent()) {
                throw corrupt("Stale Timeline head");
            }
            return result;
        });
    }
    private Optional<TimelineEntry> next(ReadView view, ExternalOrderKey after) {
        return view.nextExternal(after).map(entry -> {
            if (after != null && entry.sourceOrderKey().compareTo(after) <= 0) {
                throw corrupt("External cursor did not advance");
            }
            return checked(view, entry);
        });
    }

    private TimelineEntry checked(ReadView view, TimelineEntry entry) {
        try {
            entryFactory.verifyStoredEntry(entry);
            if (entry.globalSequence() > view.state().globalSequence()
                    || entry.globalSequence() > view.state().entryCount()) {
                throw corrupt("Entry is beyond the pinned journal state");
            }
            requireSame(entry, view.byBlueId(entry.blueId()), "identity");
            requireSame(entry, view.atAppendPosition(Math.toIntExact(entry.globalSequence() - 1)), "append");
            requireSame(entry, view.atTimelineSequence(entry.timeline().timelineId(), entry.timelineSequence()), "Timeline");
            requireSame(entry, view.atExternalOrder(entry.sourceOrderKey()), "source order");
            TimelineEntry predecessor = entry.timelineSequence() == 1 ? null
                    : view.atTimelineSequence(entry.timeline().timelineId(), entry.timelineSequence() - 1)
                            .orElseThrow(() -> corrupt("Missing Timeline predecessor"));
            if (predecessor != null) {
                entryFactory.verifyStoredEntry(predecessor);
                if (!predecessor.timeline().equals(entry.timeline())
                        || predecessor.timelineSequence() != entry.timelineSequence() - 1) {
                    throw corrupt("Wrong persisted predecessor position");
                }
            }
            if (!Objects.equals(previousIdentity(entry.exactEvent()),
                    predecessor == null ? null : predecessor.blueId())
                    || predecessor != null && (predecessor.timestampMicros() >= entry.timestampMicros()
                    || predecessor.globalSequence() >= entry.globalSequence())) {
                throw corrupt("Invalid persisted Timeline predecessor");
            }
            return entry;
        } catch (TimelineJournalStorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new TimelineJournalStorageException("Invalid persisted Timeline Entry", failure);
        }
    }

    private static void requireSame(TimelineEntry entry, Optional<TimelineEntry> indexed, String index) {
        if (indexed.isEmpty() || !sameRow(entry, indexed.orElseThrow())) {
            throw corrupt("Inconsistent " + index + " entry index");
        }
    }
    private static boolean sameRow(TimelineEntry left, TimelineEntry right) {
        return left.journalOrderKey().equals(right.journalOrderKey())
                && left.sourceOrderKey().equals(right.sourceOrderKey())
                && left.timeline().equals(right.timeline())
                && left.operation().equals(right.operation())
                && left.channel().equals(right.channel())
                && left.timestampMicros() == right.timestampMicros()
                && left.globalSequence() == right.globalSequence()
                && left.timelineSequence() == right.timelineSequence()
                && left.request().isPresent() == right.request().isPresent()
                && left.exactEvent().sameExactValue(right.exactEvent())
                && (left.request().isEmpty() || left.request().orElseThrow()
                        .sameExactValue(right.request().orElseThrow()));
    }
    private void requireOwned(State state) {
        if (!state.equals(ownedState)) throw corrupt("Journal state changed outside this owner; reopen required");
    }
    private void apply(State expected, Mutation mutation) {
        physical(() -> { store.apply(expected, mutation); return null; });
        ownedState = mutation.next();
    }
    private ReadView open() {
        ReadView view = physical(() -> Objects.requireNonNull(store.openRead(), "read view"));
        try {
            return new SafeReadView(view, physical(() -> Objects.requireNonNull(view.state(), "state")));
        } catch (RuntimeException failure) {
            try { physical(() -> { view.close(); return null; }); }
            catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    private static <T> T physical(Supplier<T> operation) {
        try { return operation.get(); }
        catch (TimelineJournalStorageException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new TimelineJournalStorageException("Timeline journal storage operation failed", failure);
        }
    }
    private static <T> T physicalRequired(Supplier<T> operation) {
        return physical(() -> Objects.requireNonNull(operation.get(), "store result"));
    }
    private static TimelineJournalStorageException corrupt(String message) {
        return new TimelineJournalStorageException(message);
    }
    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }

    /** Only callback failures are wrapped; semantic predicates stay outside this boundary. */
    private record SafeReadView(ReadView delegate, State pinned) implements ReadView {
        @Override public State state() {
            State current = physical(() -> Objects.requireNonNull(delegate.state(), "state"));
            if (!pinned.equals(current)) throw corrupt("Read view changed its pinned journal state");
            return pinned;
        }
        @Override public Optional<TimelineEntry> byBlueId(String id) { return physicalRequired(() -> delegate.byBlueId(id)); }
        @Override public Optional<TimelineEntry> timelineHead(String id) { return physicalRequired(() -> delegate.timelineHead(id)); }
        @Override public Optional<TimelineEntry> atAppendPosition(int position) { return physicalRequired(() -> delegate.atAppendPosition(position)); }
        @Override public Optional<TimelineEntry> atTimelineSequence(String id, long sequence) { return physicalRequired(() -> delegate.atTimelineSequence(id, sequence)); }
        @Override public Optional<TimelineEntry> nextExternal(ExternalOrderKey after) { return physicalRequired(() -> delegate.nextExternal(after)); }
        @Override public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order) { return physicalRequired(() -> delegate.atExternalOrder(order)); }
        @Override public Optional<ExternalOrderKey> latestExternalOrder() { return physicalRequired(delegate::latestExternalOrder); }
        @Override public void close() { physical(() -> { delegate.close(); return null; }); }
    }
}
