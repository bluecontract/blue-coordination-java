package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.TimelineJournalStorageException;
import blue.language.processor.ExternalOrderKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Test-only exact journal byte store. Snapshots contain named metadata and body
 * bytes, never live TimelineEntry/ExactValue objects. Lightweight index decoding
 * is deliberately eager in this fixture; production locality is tested by its
 * own adapter. Opening/reading never appends or invokes an engine/provider.
 */
public final class ColdStorageJournalFixture implements TimelineJournalStore {
    private static final String FORMAT = "test/rooted-cold-journal/1";
    private static final int MAX = 16 * 1024 * 1024;
    private static final SessionRecordCodec ROWS = new SessionRecordCodec(MAX, 256);
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, byte[]> bodies = new LinkedHashMap<>();
    private final Map<String, Index> byId = new LinkedHashMap<>();
    private final Map<String, List<Index>> byTimeline = new LinkedHashMap<>();
    private final TreeMap<ExternalOrderKey, Index> byOrder = new TreeMap<>();
    private final List<Index> appendOrder = new ArrayList<>();
    private State state;
    private int bodyReads, mutations;
    private record Index(String id, String timeline, long timelineSequence, long globalSequence,
            ExternalOrderKey order, String address) { }

    /** Fully detached named physical bytes, suitable for another JVM/process. */
    public record Snapshot(Map<String, byte[]> bytes) {
        public Snapshot { bytes = copy(bytes); }
        @Override public Map<String, byte[]> bytes() { return copy(bytes); }
    }

    private ColdStorageJournalFixture() { state = State.empty(); }

    /** Creates a real empty physical journal for initial empty-runtime installation. */
    public static ColdStorageJournalFixture empty() { return new ColdStorageJournalFixture(); }

    /** Test-only access to the actual physical state; no guessed revision or runtime API extension. */
    public static Snapshot retain(DefaultCoordinationEngine engine) {
        synchronized (engine) {
            try {
                var journalField = DefaultCoordinationEngine.class.getDeclaredField("journal");
                journalField.setAccessible(true);
                var storeField = DefaultTimelineJournal.class.getDeclaredField("store");
                storeField.setAccessible(true);
                return retain((TimelineJournalStore) storeField.get(journalField.get(engine)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Test journal access no longer matches the actual engine", failure);
            }
        }
    }

    /** Captures exact state and rows while the original physical view is pinned. */
    public static Snapshot retain(TimelineJournalStore original) {
        try (var view = original.openRead()) {
            var state = view.state(); var entries = new ArrayList<TimelineEntry>();
            for (int i = 0; i < state.entryCount(); i++)
                entries.add(view.atAppendPosition(i).orElseThrow(() -> new TimelineJournalStorageException("Missing retained journal row")));
            return retain(entries, state);
        }
    }

    /**
     * Explicit original state is mandatory: revision/availability must not be
     * guessed from the number of entries after rollback or availability changes.
     */
    public static Snapshot retain(List<TimelineEntry> entries, State originalState) {
        var store = empty();
        check(entries.size() == originalState.entryCount(), "Journal state/row count differs");
        for (var row : entries) {
            var bytes = encodeRow(row); var index = index(row, digest(bytes));
            store.validateNew(index); store.bodies.put(index.address(), bytes); store.add(index);
        }
        store.state = originalState; store.validateState(); return store.snapshot();
    }

    /** Opens only byte metadata/indexes; no body is hydrated until selected. */
    public static ColdStorageJournalFixture open(Snapshot snapshot) {
        var store = empty(); var bytes = snapshot.bytes();
        byte[] metadata = bytes.get("metadata");
        check(metadata != null, "Missing journal metadata");
        decode(metadata, MAX, in -> {
            check((FORMAT + "/metadata").equals(in.text(in.remaining())), "Wrong journal metadata format");
            long global = in.longValue(); int count = in.integer(); long revision = in.longValue();
            var availability = new Availability(AvailabilityKind.valueOf(in.text(in.remaining())), in.nullableText(in.remaining()));
            store.state = new State(global, count, revision, availability);
            int rows = in.count(Integer.MAX_VALUE, 1);
            for (int i = 0; i < rows; i++) {
                var index = new Index(in.text(in.remaining()), in.text(in.remaining()), in.longValue(), in.longValue(),
                        order(in), in.text(in.remaining()));
                store.validateNew(index); store.add(index);
            }
            return store;
        });
        bytes.forEach((name, value) -> {
            if (!name.equals("metadata")) {
                check(name.startsWith("body/sha256:"), "Unknown journal byte row");
                store.bodies.put(name.substring(5), value.clone());
            }
        });
        store.validateState(); return store;
    }

    /** Captures current metadata and immutable body bytes; does not publish elsewhere. */
    public Snapshot snapshot() {
        lock.lock();
        try {
            var bytes = new LinkedHashMap<String, byte[]>();
            bytes.put("metadata", encode(MAX, out -> {
                out.text(FORMAT + "/metadata"); out.longValue(state.globalSequence()); out.integer(state.entryCount());
                out.longValue(state.revision()); out.text(state.availability().kind().name()); out.nullableText(state.availability().diagnostic());
                out.integer(appendOrder.size());
                for (var index : appendOrder) {
                    out.text(index.id()); out.text(index.timeline()); out.longValue(index.timelineSequence());
                    out.longValue(index.globalSequence()); order(out, index.order()); out.text(index.address());
                }
            }));
            bodies.forEach((address, value) -> bytes.put("body/" + address, value));
            return new Snapshot(bytes);
        } finally { lock.unlock(); }
    }
    public int bodyReads() { lock.lock(); try { return bodyReads; } finally { lock.unlock(); } }
    public int mutations() { lock.lock(); try { return mutations; } finally { lock.unlock(); } }

    @Override public ReadView openRead() {
        lock.lock(); var pinned = state;
        return new ReadView() {
            private boolean closed;
            private void guard() { check(!closed, "Closed fixture journal view"); }
            private Optional<TimelineEntry> read(Index index) { guard(); return Optional.ofNullable(index).map(ColdStorageJournalFixture.this::read); }
            @Override public State state() { guard(); return pinned; }
            @Override public Optional<TimelineEntry> byBlueId(String id) { return read(byId.get(id)); }
            @Override public Optional<TimelineEntry> timelineHead(String id) {
                guard(); var rows = byTimeline.getOrDefault(id, List.of()); return read(rows.isEmpty() ? null : rows.get(rows.size() - 1));
            }
            @Override public Optional<TimelineEntry> atAppendPosition(int position) {
                guard(); return read(position < 0 || position >= appendOrder.size() ? null : appendOrder.get(position));
            }
            @Override public Optional<TimelineEntry> atTimelineSequence(String id, long sequence) {
                guard(); var rows = byTimeline.getOrDefault(id, List.of());
                return read(sequence < 1 || sequence > rows.size() ? null : rows.get(Math.toIntExact(sequence - 1)));
            }
            @Override public Optional<TimelineEntry> nextExternal(ExternalOrderKey after) {
                guard(); var row = after == null ? byOrder.firstEntry() : byOrder.higherEntry(after); return read(row == null ? null : row.getValue());
            }
            @Override public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey key) { return read(byOrder.get(key)); }
            @Override public Optional<ExternalOrderKey> latestExternalOrder() { guard(); return byOrder.isEmpty() ? Optional.empty() : Optional.of(byOrder.lastKey()); }
            @Override public void close() { if (!closed) { closed = true; lock.unlock(); } }
        };
    }

    @Override public void apply(State expected, Mutation mutation) {
        check(!lock.isHeldByCurrentThread(), "Close fixture journal read view before mutation");
        lock.lock();
        try {
            check(state.equals(expected), "Fixture journal state conflict"); var next = mutation.next();
            if (mutation instanceof Append append) {
                var bytes = encodeRow(append.entry()); var index = index(append.entry(), digest(bytes)); validateNew(index);
                check(next.entryCount() == state.entryCount() + 1 && next.globalSequence() == state.globalSequence() + 1
                        && index.globalSequence() == next.globalSequence() && next.revision() == state.revision() + 1
                        && next.availability().equals(state.availability()), "Invalid append state");
                var existing = bodies.get(index.address()); check(existing == null || Arrays.equals(existing, bytes), "Immutable journal collision");
                bodies.put(index.address(), bytes); add(index);
            } else if (mutation instanceof Truncate) {
                check(next.entryCount() <= state.entryCount() && next.revision() == state.revision() + 1
                        && next.availability().equals(state.availability()), "Invalid truncate state");
                long last = next.entryCount() == 0 ? 0 : appendOrder.get(next.entryCount() - 1).globalSequence();
                check(next.globalSequence() == last, "Wrong truncated global sequence");
                while (appendOrder.size() > next.entryCount()) {
                    var row = appendOrder.remove(appendOrder.size() - 1); byId.remove(row.id()); byOrder.remove(row.order());
                    var timeline = byTimeline.get(row.timeline()); timeline.remove(timeline.size() - 1);
                    if (timeline.isEmpty()) byTimeline.remove(row.timeline());
                }
            } else {
                check(mutation instanceof SetAvailability && next.entryCount() == state.entryCount()
                        && next.globalSequence() == state.globalSequence() && next.revision() == state.revision(), "Invalid availability state");
            }
            state = next; mutations++;
        } finally { lock.unlock(); }
    }

    private TimelineEntry read(Index index) {
        bodyReads++; var bytes = bodies.get(index.address());
        check(bytes != null && index.address().equals(digest(bytes)), "Missing/corrupt journal body");
        var row = decode(bytes, MAX, in -> { check((FORMAT + "/entry").equals(in.text(in.remaining())), "Wrong journal row format"); return ROWS.entry(in); });
        check(index.equals(index(row, index.address())) && Arrays.equals(bytes, encodeRow(row)), "Journal row/index mismatch"); return row;
    }
    private void validateNew(Index row) {
        check(!byId.containsKey(row.id()) && !byOrder.containsKey(row.order()), "Duplicate journal index");
        check(row.timelineSequence() == byTimeline.getOrDefault(row.timeline(), List.of()).size() + 1L,
                "Noncontiguous Timeline sequence");
        check(row.globalSequence() > (appendOrder.isEmpty() ? 0 : appendOrder.get(appendOrder.size() - 1).globalSequence()),
                "Nonincreasing journal append sequence");
        check(row.address().matches("sha256:[0-9a-f]{64}"), "Invalid journal body address");
    }
    private void add(Index row) {
        byId.put(row.id(), row); byOrder.put(row.order(), row); appendOrder.add(row);
        byTimeline.computeIfAbsent(row.timeline(), ignored -> new ArrayList<>()).add(row);
    }
    private void validateState() {
        check(state.entryCount() == appendOrder.size(), "Journal metadata count mismatch");
        check(state.globalSequence() == (appendOrder.isEmpty() ? 0 : appendOrder.get(appendOrder.size() - 1).globalSequence()),
                "Journal metadata append frontier mismatch");
    }
    private static Index index(TimelineEntry row, String address) {
        return new Index(row.blueId(), row.timeline().timelineId(), row.timelineSequence(), row.globalSequence(), row.sourceOrderKey(), address);
    }
    private static byte[] encodeRow(TimelineEntry row) { return encode(MAX, out -> { out.text(FORMAT + "/entry"); ROWS.entry(out, row); }); }
    private static String digest(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static Map<String, byte[]> copy(Map<String, byte[]> source) {
        var owned = new LinkedHashMap<String, byte[]>(); Objects.requireNonNull(source).forEach((key, bytes) -> owned.put(Objects.requireNonNull(key), bytes.clone()));
        return Collections.unmodifiableMap(owned);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new TimelineJournalStorageException(message); }
}
