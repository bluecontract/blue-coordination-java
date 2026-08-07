package blue.coordination.examples.support;

import blue.language.model.NodeWireForm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Canonical Timeline entries with monotonic O(1)-addressable positions. */
public final class MyOsPositionedTimelineJournal {

    public record Stored(
            MyOsDemoEntry entry,
            MyOsTimelineBinding binding,
            String eventInventoryIdentity,
            MyOsJournalPosition position) {
        public Stored {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(binding, "binding");
            if (Objects.requireNonNull(
                    eventInventoryIdentity,
                    "eventInventoryIdentity").isBlank()) {
                throw new IllegalArgumentException("event inventory is blank");
            }
            Objects.requireNonNull(position, "position");
            if (!position.entryBlueId().equals(entry.blueId())
                    || !position.orderKey().equals(entry.orderKey())) {
                throw new IllegalArgumentException(
                        "Journal position does not describe its entry");
            }
        }
    }

    private final Map<String, Stored> byEntryBlueId = new LinkedHashMap<>();
    private final NavigableMap<Long, Stored> bySequence = new TreeMap<>();
    private long highWater;
    private Stored highWaterEntry;
    private long publicationVersion;

    public synchronized Stored append(
            MyOsDemoEntry entry,
            MyOsTimelineBinding binding,
            String eventInventoryIdentity) {
        PreparedAppend prepared = prepareAppend(
                entry, binding, eventInventoryIdentity);
        publish(prepared);
        return prepared.stored;
    }

    synchronized PreparedAppend prepareAppend(
            MyOsDemoEntry entry,
            MyOsTimelineBinding binding,
            String eventInventoryIdentity) {
        MyOsDemoEntry checked = Objects.requireNonNull(entry, "entry");
        Stored existing = byEntryBlueId.get(checked.blueId());
        if (existing != null) {
            requireEquivalent(existing, checked, binding,
                    eventInventoryIdentity);
            return new PreparedAppend(
                    this,
                    publicationVersion,
                    publicationVersion,
                    existing,
                    false);
        }
        long sequence = Math.addExact(highWater, 1L);
        Stored created = new Stored(
                checked,
                Objects.requireNonNull(binding, "binding"),
                Objects.requireNonNull(
                        eventInventoryIdentity, "eventInventoryIdentity"),
                new MyOsJournalPosition(sequence, checked.blueId(),
                        checked.orderKey()));
        return new PreparedAppend(
                this,
                publicationVersion,
                Math.addExact(publicationVersion, 1L),
                created,
                true);
    }

    synchronized void validate(PreparedAppend append) {
        PreparedAppend checked = Objects.requireNonNull(append, "append");
        if (checked.owner != this) {
            throw new IllegalArgumentException(
                    "Prepared journal append belongs to another journal");
        }
        if (checked.basePublicationVersion != publicationVersion) {
            throw new IllegalStateException(
                    "Prepared journal append is stale");
        }
    }

    synchronized void publish(PreparedAppend append) {
        validate(append);
        publishPreparedUnchecked(append);
    }

    synchronized void publishPreparedUnchecked(PreparedAppend append) {
        if (!append.insert) {
            return;
        }
        Stored stored = append.stored;
        byEntryBlueId.put(stored.entry().blueId(), stored);
        bySequence.put(stored.position().sequence(), stored);
        highWater = stored.position().sequence();
        highWaterEntry = stored;
        publicationVersion = append.resultingPublicationVersion;
    }

    public synchronized Stored require(String entryBlueId) {
        Stored value = byEntryBlueId.get(Objects.requireNonNull(
                entryBlueId, "entryBlueId"));
        if (value == null) {
            throw new IllegalArgumentException("Unknown Timeline Entry");
        }
        return value;
    }

    public synchronized long highWaterSequence() { return highWater; }

    /** Returns the exact last journal position without scanning the journal. */
    public synchronized Optional<MyOsJournalPosition> highWaterPosition() {
        return highWaterEntry == null
                ? Optional.empty()
                : Optional.of(highWaterEntry.position());
    }

    public synchronized int size() { return byEntryBlueId.size(); }

    public synchronized NavigableMap<Long, Stored> after(long exclusive) {
        if (exclusive < 0L || exclusive > highWater) {
            throw new IllegalArgumentException("Invalid journal cursor");
        }
        return Collections.unmodifiableNavigableMap(new TreeMap<>(
                bySequence.tailMap(exclusive, false)));
    }

    public synchronized MyOsPositionedTimelineJournal copy() {
        MyOsPositionedTimelineJournal result =
                new MyOsPositionedTimelineJournal();
        result.byEntryBlueId.putAll(byEntryBlueId);
        result.bySequence.putAll(bySequence);
        result.highWater = highWater;
        result.highWaterEntry = highWaterEntry;
        result.publicationVersion = publicationVersion;
        return result;
    }

    static final class PreparedAppend {
        private final MyOsPositionedTimelineJournal owner;
        private final long basePublicationVersion;
        private final long resultingPublicationVersion;
        private final Stored stored;
        private final boolean insert;

        private PreparedAppend(
                MyOsPositionedTimelineJournal owner,
                long basePublicationVersion,
                long resultingPublicationVersion,
                Stored stored,
                boolean insert) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.basePublicationVersion = basePublicationVersion;
            this.resultingPublicationVersion = resultingPublicationVersion;
            this.stored = Objects.requireNonNull(stored, "stored");
            this.insert = insert;
        }
    }

    private static void requireEquivalent(
            Stored stored,
            MyOsDemoEntry entry,
            MyOsTimelineBinding binding,
            String eventInventoryIdentity) {
        if (!stored.binding().equals(binding)
                || !stored.eventInventoryIdentity().equals(
                        eventInventoryIdentity)
                || !stored.entry().orderKey().equals(entry.orderKey())
                || !NodeWireForm.get(stored.entry().exactEntry()).equals(
                        NodeWireForm.get(entry.exactEntry()))) {
            throw new IllegalStateException(
                    "Conflicting Timeline Entry " + entry.blueId());
        }
    }
}
