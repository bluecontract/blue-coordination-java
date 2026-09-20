package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.processor.ExternalOrderKey;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Library-owned journal rows and indexes in the runtime's atomic logical attempt. */
final class LogicalTimelineJournalStore implements TimelineJournalStore {
    private final LogicalPointStorage logical;
    private final StoredInsertionOrderedMap.Limits limits;
    private final Map<String, State> control;
    private final Map<String, TimelineEntry> entries;
    private final Map<String, String> heads;
    private final Map<Long, String> append;
    private final Map<Position, String> positions;
    private final LogicalPointStorage.Scope<ExternalOrderKey, String> orders;
    private final Map<String, ExternalOrderKey> latest;
    private final Map<String, Boolean> indexedTimelines;
    private final Map<String, Boolean> formats;
    private final Map<String, LogicalPointStorage.Scope<ExternalOrderKey, String>> timelineOrders = new HashMap<>();
    private int readers;
    private record Position(String timeline, long sequence) { }

    LogicalTimelineJournalStore(LogicalPointStorage logical, RootedEngineStorage.Limits limits) {
        this.logical = logical; this.limits = limits.pending();
        var values = codec("text", Writer::text, SessionRecordCodec::text);
        var text = OrderedRecordKey.text();
        var rows = new SessionRecordCodec(limits.maximumRecordBytes(), limits.maximumDepth());
        entries = map(Family.JOURNAL_ENTRY, "identity", text, codec("entry", rows::entry, rows::entry))
                .validateRows((key, value) -> require(key.equals(value.blueId()), "Foreign journal entry identity"));
        heads = map(Family.JOURNAL_ENTRY, "head", text, values);
        append = map(Family.JOURNAL_ENTRY, "append", OrderedRecordKey.signedLong(), values);
        positions = map(Family.JOURNAL_ENTRY, "timeline", OrderedRecordKey.pair("journal-position", text,
                OrderedRecordKey.signedLong(), Position::timeline, Position::sequence, Position::new), values);
        orders = map(Family.JOURNAL_ENTRY, "external", OrderedRecordKey.externalOrder(), values);
        latest = map(Family.JOURNAL_COVERAGE, "latest", text, codec("order", SessionStorageWire::order, SessionStorageWire::order));
        formats = map(Family.JOURNAL_COVERAGE, "formats", text, codec("membership", Writer::bool, Reader::bool))
                .validateRows((key, value) -> require(Boolean.TRUE.equals(value), "Invalid journal index format"));
        indexedTimelines = map(Family.JOURNAL_COVERAGE, "indexed-timelines", text, codec("membership", Writer::bool, Reader::bool))
                .validateRows((key, value) -> require(Boolean.TRUE.equals(value), "Invalid journal Timeline index membership"));
        control = map(Family.JOURNAL_COVERAGE, "control", text, codec("state", (w, state) -> {
            w.longValue(state.globalSequence()); w.integer(state.entryCount()); w.longValue(state.revision());
            w.text(state.availability().kind().name()); w.nullableText(state.availability().diagnostic());
        }, r -> new State(r.longValue(), r.integer(), r.longValue(),
                new Availability(AvailabilityKind.valueOf(text(r)), nullableText(r)))));
    }

    private State current() { return control.getOrDefault("state", State.empty()); }
    Availability availability() {
        var selected = control.get("availability");
        // Older logical journals remain readable with conservative global validation until their next write.
        return selected == null ? current().availability() : selected.availability();
    }
    private Optional<TimelineEntry> row(String id) {
        if (id == null) return Optional.empty();
        var entry = entries.get(id); require(entry != null, "Missing indexed journal entry"); return Optional.of(entry);
    }
    private LogicalPointStorage.Scope<ExternalOrderKey, String> timelineOrders(String timeline) {
        return timelineOrders.computeIfAbsent(timeline, id -> map(Family.JOURNAL_ENTRY, "timeline-external/"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(OrderedRecordKey.text().encode(id)),
                OrderedRecordKey.externalOrder(), codec("text", Writer::text, SessionRecordCodec::text)));
    }
    Optional<List<TimelineEntry>> prefix(String timeline, ExternalOrderKey cutoff) {
        return logical.context().protect(() -> {
            if (!Boolean.TRUE.equals(formats.get("timeline-external/1")) && !indexedTimelines.containsKey(timeline)) {
                // No rows means a complete empty prefix. Existing older journals use conservative point reads.
                if (!heads.containsKey(timeline)) return Optional.of(List.of());
                return Optional.empty();
            }
            return Optional.of(timelineOrders(timeline).entriesBefore(cutoff).stream().map(index -> {
                var entry = row(index.getValue()).orElseThrow();
                require(entry.timeline().timelineId().equals(timeline) && entry.sourceOrderKey().equals(index.getKey()),
                        "Foreign source prefix index"); return entry;
            }).toList());
        });
    }
    @Override public ReadView openRead() {
        return logical.context().protect(() -> {
            readers++;
            return new ReadView() {
                private boolean closed;
                private void check() { logical.context().checkOpen(); require(!closed, "Journal view is closed"); }
                public State state() { check(); return current(); }
                public Optional<TimelineEntry> byBlueId(String id) { check(); return Optional.ofNullable(entries.get(id)); }
                public Optional<TimelineEntry> timelineHead(String id) { check(); return row(heads.get(id)); }
                public Optional<TimelineEntry> atAppendPosition(int position) {
                    check(); return position < 0 ? Optional.empty() : row(append.get((long) position));
                }
                public Optional<TimelineEntry> atTimelineSequence(String id, long sequence) {
                    check(); return sequence < 1 ? Optional.empty() : row(positions.get(new Position(id, sequence)));
                }
                public Optional<TimelineEntry> nextExternal(ExternalOrderKey after) {
                    check(); var selected = orders.firstAfter(after); return row(selected == null ? null : selected.getValue());
                }
                public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order) { check(); return row(orders.get(order)); }
                public Optional<ExternalOrderKey> latestExternalOrder() { check(); return Optional.ofNullable(latest.get("order")); }
                public void close() { if (!closed) { closed = true; readers--; } }
            };
        });
    }
    @Override public void apply(State expected, Mutation mutation) {
        logical.context().protect(() -> {
            require(readers == 0, "Close journal views before mutation");
            require(current().equals(expected), "Journal state changed before publication");
            if (mutation instanceof Append added) {
                var entry = added.entry(); var next = added.next();
                require(entry.globalSequence() == Math.addExact(expected.globalSequence(), 1)
                        && next.globalSequence() == entry.globalSequence()
                        && next.entryCount() == Math.addExact(expected.entryCount(), 1)
                        && next.revision() == Math.addExact(expected.revision(), 1)
                        && next.availability().equals(expected.availability()), "Invalid journal append state");
                require(!entries.containsKey(entry.blueId()) && !orders.containsKey(entry.sourceOrderKey())
                        && !append.containsKey((long) expected.entryCount()), "Journal index already occupied");
                var prior = row(heads.get(entry.timeline().timelineId())).orElse(null);
                require(entry.timelineSequence() == (prior == null ? 1 : Math.addExact(prior.timelineSequence(), 1)),
                        "Invalid journal Timeline sequence");
                var position = new Position(entry.timeline().timelineId(), entry.timelineSequence());
                require(!positions.containsKey(position), "Timeline position already occupied");
                if (expected.entryCount() == 0) formats.put("timeline-external/1", true);
                var timelineOrders = timelineOrders(entry.timeline().timelineId());
                if (!indexedTimelines.containsKey(entry.timeline().timelineId())) {
                    for (long sequence = 1; sequence < entry.timelineSequence(); sequence++) {
                        var retained = row(positions.get(new Position(entry.timeline().timelineId(), sequence))).orElseThrow();
                        timelineOrders.put(retained.sourceOrderKey(), retained.blueId());
                    }
                    indexedTimelines.put(entry.timeline().timelineId(), true);
                }
                require(!timelineOrders.containsKey(entry.sourceOrderKey()), "Timeline external position already occupied");
                timelineOrders.put(entry.sourceOrderKey(), entry.blueId());
                entries.put(entry.blueId(), entry); append.put((long) expected.entryCount(), entry.blueId());
                positions.put(position, entry.blueId()); orders.put(entry.sourceOrderKey(), entry.blueId());
                heads.put(entry.timeline().timelineId(), entry.blueId());
                var maximum = latest.get("order");
                if (maximum == null || entry.sourceOrderKey().compareTo(maximum) > 0) latest.put("order", entry.sourceOrderKey());
            } else if (mutation instanceof Truncate truncated) {
                var next = truncated.next();
                require(next.entryCount() <= expected.entryCount() && next.globalSequence() == next.entryCount()
                        && next.revision() == Math.addExact(expected.revision(), 1)
                        && next.availability().equals(expected.availability()), "Invalid journal truncation state");
                for (int i = expected.entryCount() - 1; i >= next.entryCount(); i--) {
                    var entry = row(append.remove((long) i)).orElseThrow();
                    entries.remove(entry.blueId()); orders.remove(entry.sourceOrderKey());
                    timelineOrders(entry.timeline().timelineId()).remove(entry.sourceOrderKey());
                    positions.remove(new Position(entry.timeline().timelineId(), entry.timelineSequence()));
                    if (entry.timelineSequence() == 1) heads.remove(entry.timeline().timelineId());
                    else heads.put(entry.timeline().timelineId(), Objects.requireNonNull(
                            positions.get(new Position(entry.timeline().timelineId(), entry.timelineSequence() - 1))));
                }
                var maximum = orders.keySet().stream().max(ExternalOrderKey::compareTo);
                if (maximum.isEmpty()) latest.remove("order"); else latest.put("order", maximum.orElseThrow());
            } else {
                require(mutation instanceof SetAvailability && mutation.next().globalSequence() == expected.globalSequence()
                        && mutation.next().entryCount() == expected.entryCount()
                        && mutation.next().revision() == expected.revision(), "Invalid availability transition");
            }
            control.put("state", mutation.next());
            control.put("availability", new State(0, 0, 0, mutation.next().availability())); return null;
        });
    }
    private <K, V> LogicalPointStorage.Scope<K, V> map(Family family, String name, PersistentMapCodec<K> keys,
            PersistentMapCodec<V> values) { return logical.open(family, "engine/journal/1/" + name, keys, values, limits); }
    private <T> PersistentMapCodec<T> codec(String name, BiConsumer<Writer, T> write, Function<Reader, T> read) {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/journal-record/2/" + name; }
            public byte[] encode(T value) { return SessionStorageWire.encode(limits.maximumRecordBytes(), w -> {
                w.text(identity()); write.accept(w, value);
            }); }
            public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, limits.maximumRecordBytes(), r -> {
                require(identity().equals(text(r)), "Wrong logical journal codec"); return read.apply(r);
            }); }
        };
    }
}
