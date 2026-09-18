package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.TimelineJournalStorageException;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/** Default physical indexes; opening a view does not copy any rows. */
final class InMemoryTimelineJournalStore implements TimelineJournalStore {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, TimelineEntry> byId = new LinkedHashMap<>();
    private final Map<String, List<TimelineEntry>> byTimeline = new LinkedHashMap<>();
    private final TreeMap<ExternalOrderKey, TimelineEntry> byOrder = new TreeMap<>();
    private final List<TimelineEntry> appendOrder = new ArrayList<>();
    private final HistoricalAvailabilityControl availability;
    private State state = State.empty();

    InMemoryTimelineJournalStore() { this(new HistoricalAvailabilityControl()); }

    InMemoryTimelineJournalStore(HistoricalAvailabilityControl availability) {
        this.availability = Objects.requireNonNull(availability, "availability");
    }

    private State current() {
        Availability current = availability.blockedStep().map(blocked -> {
            if (blocked instanceof HistoricalStep.Unavailable unavailable) {
                return new Availability(AvailabilityKind.UNAVAILABLE, unavailable.diagnostic());
            }
            return new Availability(AvailabilityKind.INVALID_EVIDENCE,
                    ((HistoricalStep.InvalidEvidence) blocked).diagnostic());
        }).orElse(Availability.available());
        return new State(state.globalSequence(), state.entryCount(), state.revision(), current);
    }

    @Override public ReadView openRead() {
        lock.lock();
        State pinned = current();
        return new ReadView() {
            private boolean closed;
            private void check() {
                if (closed) throw new IllegalStateException("journal view is closed");
            }
            @Override public State state() { check(); return pinned; }
            @Override public Optional<TimelineEntry> byBlueId(String id) {
                check(); return Optional.ofNullable(byId.get(id));
            }
            @Override public Optional<TimelineEntry> timelineHead(String id) {
                check();
                List<TimelineEntry> entries = byTimeline.getOrDefault(id, List.of());
                return entries.isEmpty() ? Optional.empty()
                        : Optional.of(entries.get(entries.size() - 1));
            }
            @Override public Optional<TimelineEntry> atAppendPosition(int position) {
                check();
                return position < 0 || position >= appendOrder.size() ? Optional.empty()
                        : Optional.of(appendOrder.get(position));
            }
            @Override public Optional<TimelineEntry> atTimelineSequence(String id, long sequence) {
                check();
                List<TimelineEntry> entries = byTimeline.getOrDefault(id, List.of());
                return sequence < 1 || sequence > entries.size() ? Optional.empty()
                        : Optional.of(entries.get(Math.toIntExact(sequence - 1)));
            }
            @Override public Optional<TimelineEntry> nextExternal(ExternalOrderKey after) {
                check();
                Map.Entry<ExternalOrderKey, TimelineEntry> row = after == null
                        ? byOrder.firstEntry() : byOrder.higherEntry(after);
                return row == null ? Optional.empty() : Optional.of(row.getValue());
            }
            @Override public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order) {
                check(); return Optional.ofNullable(byOrder.get(order));
            }
            @Override public Optional<ExternalOrderKey> latestExternalOrder() {
                check(); return byOrder.isEmpty() ? Optional.empty() : Optional.of(byOrder.lastKey());
            }
            @Override public void close() {
                if (!closed) { closed = true; lock.unlock(); }
            }
        };
    }

    @Override public void apply(State expected, Mutation mutation) {
        if (lock.isHeldByCurrentThread()) {
            throw new TimelineJournalStorageException("Close the pinned read view before mutating this store");
        }
        lock.lock();
        try {
            if (!current().equals(expected)) {
                throw new TimelineJournalStorageException("Journal state changed before publication");
            }
            if (mutation instanceof Append append) {
                TimelineEntry entry = append.entry();
                if (byId.containsKey(entry.blueId()) || byOrder.containsKey(entry.sourceOrderKey())) {
                    throw new TimelineJournalStorageException("Journal row/index already exists");
                }
                byId.put(entry.blueId(), entry);
                byOrder.put(entry.sourceOrderKey(), entry);
                byTimeline.computeIfAbsent(entry.timeline().timelineId(), ignored -> new ArrayList<>())
                        .add(entry);
                appendOrder.add(entry);
            } else if (mutation instanceof Truncate truncate) {
                while (appendOrder.size() > truncate.next().entryCount()) {
                    TimelineEntry removed = appendOrder.remove(appendOrder.size() - 1);
                    List<TimelineEntry> entries = byTimeline.get(removed.timeline().timelineId());
                    entries.remove(entries.size() - 1);
                    if (entries.isEmpty()) byTimeline.remove(removed.timeline().timelineId());
                    byId.remove(removed.blueId());
                    byOrder.remove(removed.sourceOrderKey());
                }
            }
            state = mutation.next();
            switch (state.availability().kind()) {
                case AVAILABLE -> availability.makeAvailable();
                case UNAVAILABLE -> availability.makeUnavailable(state.availability().diagnostic());
                case INVALID_EVIDENCE -> availability.invalidateEvidence(state.availability().diagnostic());
            }
        } finally {
            lock.unlock();
        }
    }
}
