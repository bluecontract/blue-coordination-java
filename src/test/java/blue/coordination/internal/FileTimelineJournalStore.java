package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.TimelineJournalStorageException;
import blue.language.processor.ExternalOrderKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Restart fixture: immutable body rows and an atomically replaced metadata
 * manifest, never a serialized journal/engine or replayed append log.
 * Only lightweight indexes are materialized; this is not a production DB adapter.
 */
final class FileTimelineJournalStore implements TimelineJournalStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JournalFixtureBodyCodec BODIES = new JournalFixtureBodyCodec(16 * 1024 * 1024);
    private final Path directory;
    final AtomicInteger bodyReads = new AtomicInteger();

    FileTimelineJournalStore(Path directory) {
        this.directory = directory;
        io(() -> {
            Files.createDirectories(directory);
            if (!Files.exists(directory.resolve("manifest.json"))) {
                replace(new Manifest(State.empty(), List.of()));
            }
            return null;
        });
    }

    @Override public ReadView openRead() {
        Manifest pinned = manifest();
        return new ReadView() {
            private boolean closed;
            private void check() { if (closed) throw new IllegalStateException("closed view"); }
            private Optional<TimelineEntry> read(Optional<Index> index) {
                check(); return index.map(FileTimelineJournalStore.this::readRow);
            }
            @Override public State state() { check(); return pinned.state(); }
            @Override public Optional<TimelineEntry> byBlueId(String id) {
                return read(pinned.rows().stream().filter(row -> row.id().equals(id)).findFirst());
            }
            @Override public Optional<TimelineEntry> timelineHead(String id) {
                return read(pinned.rows().stream().filter(row -> row.timeline().equals(id))
                        .max(Comparator.comparingLong(Index::timelineSequence)));
            }
            @Override public Optional<TimelineEntry> atAppendPosition(int position) {
                return read(position < 0 || position >= pinned.rows().size()
                        ? Optional.empty() : Optional.of(pinned.rows().get(position)));
            }
            @Override public Optional<TimelineEntry> atTimelineSequence(String id, long sequence) {
                return read(pinned.rows().stream().filter(row -> row.timeline().equals(id)
                        && row.timelineSequence() == sequence).findFirst());
            }
            @Override public Optional<TimelineEntry> nextExternal(ExternalOrderKey after) {
                return read(pinned.rows().stream().filter(row -> after == null || row.order().compareTo(after) > 0)
                        .min(Comparator.comparing(Index::order)));
            }
            @Override public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order) {
                return read(pinned.rows().stream().filter(row -> row.order().equals(order)).findFirst());
            }
            @Override public Optional<ExternalOrderKey> latestExternalOrder() {
                check(); return pinned.rows().stream().map(Index::order).max(Comparator.naturalOrder());
            }
            @Override public void close() { closed = true; }
        };
    }

    @Override public void apply(State expected, Mutation mutation) {
        io(() -> {
            try (FileChannel channel = FileChannel.open(directory.resolve("writer.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var writerLock = channel.lock()) {
                if (!writerLock.isValid()) throw new IOException("Fixture writer lock is invalid");
                Manifest current = manifest();
                if (!current.state().equals(expected)) {
                    throw new TimelineJournalStorageException("Journal state conflict");
                }
                List<Index> rows = new ArrayList<>(current.rows());
                if (mutation instanceof Append append) {
                    TimelineEntry entry = append.entry();
                    Row row = Row.encode(entry);
                    Index index = new Index(entry.blueId(), entry.timeline().timelineId(),
                            (String) entry.sourceOrderKey().components().get(1),
                            entry.timestampMicros(), entry.timelineSequence(), entry.globalSequence());
                    Path file = directory.resolve(index.rowName());
                    byte[] bytes = JSON.writeValueAsBytes(row);
                    if (Files.exists(file)) {
                        if (!java.util.Arrays.equals(bytes, Files.readAllBytes(file))) {
                            throw new TimelineJournalStorageException("Immutable fixture row collision");
                        }
                    } else Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
                    rows.add(index);
                } else if (mutation instanceof Truncate truncate) {
                    rows = new ArrayList<>(rows.subList(0, truncate.next().entryCount()));
                }
                replace(new Manifest(mutation.next(), rows));
            }
            return null;
        });
    }

    void corruptOperation(String id) {
        io(() -> {
            Path file = directory.resolve(manifest().rows().stream()
                    .filter(row -> row.id().equals(id)).findFirst().orElseThrow().rowName());
            Row row = JSON.readValue(Files.readAllBytes(file), Row.class);
            JSON.writeValue(file.toFile(), new Row(row.eventId(), row.eventJson(), row.requestId(),
                    row.requestJson(), row.timeline(), row.orderTimeline(), row.actor(), "forged", row.channel(),
                    row.timestamp(), row.globalSequence(), row.timelineSequence()));
            return null;
        });
    }

    private Manifest manifest() {
        return io(() -> JSON.readValue(Files.readAllBytes(directory.resolve("manifest.json")), Manifest.class));
    }
    private void replace(Manifest manifest) throws IOException {
        Path staged = Files.createTempFile(directory, "manifest-", ".tmp");
        Files.write(staged, JSON.writeValueAsBytes(manifest));
        Files.move(staged, directory.resolve("manifest.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private TimelineEntry readRow(Index index) {
        bodyReads.incrementAndGet();
        return io(() -> JSON.readValue(Files.readAllBytes(
                directory.resolve(index.rowName())), Row.class).decode());
    }
    private static <T> T io(IoSupplier<T> operation) {
        try { return operation.get(); }
        catch (IOException failure) { throw new IllegalStateException("Fixture I/O failure", failure); }
    }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }

    record Manifest(State state, List<Index> rows) { }
    record Index(String id, String timeline, String orderTimeline, long timestamp, long timelineSequence, long globalSequence) {
        String rowName() { return id + "-" + globalSequence + "-" + timelineSequence + ".row.json"; }
        ExternalOrderKey order() {
            return ExternalOrderKey.of(List.of(BigInteger.valueOf(timestamp), orderTimeline, id));
        }
    }
    record Row(String eventId, String eventJson, String requestId, String requestJson,
               String timeline, String orderTimeline, String actor, String operation, String channel,
               long timestamp, long globalSequence, long timelineSequence) {
        static Row encode(TimelineEntry entry) {
            String event = json(entry.exactEvent());
            String request = entry.request().map(Row::json).orElse(null);
            Row row = new Row(entry.blueId(), event, entry.request().map(ExactValue::blueId).orElse(null),
                    request, entry.timeline().timelineId(), (String) entry.sourceOrderKey().components().get(1),
                    entry.timeline().actorId(), entry.operation(),
                    entry.channel(), entry.timestampMicros(), entry.globalSequence(), entry.timelineSequence());
            TimelineEntry restored = row.decode();
            if (!entry.exactEvent().sameExactValue(restored.exactEvent())
                    || !entry.exactEvent().frozen().resolvedStructuralKey()
                            .equals(restored.exactEvent().frozen().resolvedStructuralKey())
                    || entry.request().isPresent() && (!entry.exactRequest().sameExactValue(restored.exactRequest())
                    || !entry.exactRequest().frozen().resolvedStructuralKey()
                            .equals(restored.exactRequest().frozen().resolvedStructuralKey()))) {
                throw new IllegalArgumentException("Fixture codec cannot preserve this exact representation");
            }
            return row;
        }
        TimelineEntry decode() {
            ExactValue event = exact(eventId, eventJson);
            Optional<ExactValue> request = requestId == null ? Optional.empty()
                    : Optional.of(exact(requestId, requestJson));
            ExternalOrderKey order = ExternalOrderKey.of(List.of(BigInteger.valueOf(timestamp), orderTimeline, eventId));
            return new TimelineEntry(event, request, order, order, new Timeline(timeline, actor),
                    operation, channel, timestamp, globalSequence, timelineSequence);
        }
        private static String json(ExactValue value) {
            return java.util.Base64.getEncoder().encodeToString(BODIES.encode(value.frozen()));
        }
        private static ExactValue exact(String id, String json) {
            ExactValue exact = ExactValue.fromFrozen(BODIES.decode(java.util.Base64.getDecoder().decode(json)));
            if (!id.equals(exact.blueId())) throw new IllegalArgumentException("Fixture exact identity mismatch");
            return exact;
        }
    }
}
