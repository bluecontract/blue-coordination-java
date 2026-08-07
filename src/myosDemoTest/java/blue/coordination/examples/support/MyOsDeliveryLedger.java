package blue.coordination.examples.support;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-logical-document journal progress with idempotent claim/commit.
 *
 * <p>This is the late-attachment high-water primitive. It does not replace the
 * environment's per-entry/per-session dispatch ledger.</p>
 */
public final class MyOsDeliveryLedger {

    public enum Outcome {
        ACQUIRED,
        BEFORE_ADMISSION,
        ALREADY_COMMITTED,
        IN_FLIGHT
    }

    public record StreamKey(
            MyOsDocumentIdentity document,
            String timelineId) {
        public StreamKey {
            Objects.requireNonNull(document, "document");
            if (Objects.requireNonNull(timelineId, "timelineId").isBlank()) {
                throw new IllegalArgumentException("timelineId is blank");
            }
        }
    }

    public record Claim(
            Outcome outcome,
            StreamKey stream,
            long sequence,
            String entryBlueId,
            long token) {
        public Claim {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(stream, "stream");
            Objects.requireNonNull(entryBlueId, "entryBlueId");
        }

        public boolean acquired() { return outcome == Outcome.ACQUIRED; }
    }

    private final Map<StreamKey, Progress> progress = new LinkedHashMap<>();
    private long nextClaimToken;

    public synchronized void admit(StreamKey stream, long journalHighWater) {
        Objects.requireNonNull(stream, "stream");
        if (journalHighWater < 0L) {
            throw new IllegalArgumentException("journalHighWater is negative");
        }
        Progress prior = progress.putIfAbsent(
                stream, new Progress(journalHighWater));
        if (prior != null && prior.admissionHighWater != journalHighWater) {
            throw new IllegalStateException(
                    "Delivery stream was admitted with another high-water");
        }
    }

    public synchronized Claim claim(
            StreamKey stream,
            MyOsJournalPosition entry) {
        Progress state = require(stream);
        MyOsJournalPosition checked = Objects.requireNonNull(entry, "entry");
        if (checked.sequence() <= state.admissionHighWater) {
            return claim(Outcome.BEFORE_ADMISSION, stream, checked, 0L);
        }
        Receipt committed = state.committed.get(checked.sequence());
        if (committed != null) {
            requireSameEntry(committed.entryBlueId, checked);
            return claim(Outcome.ALREADY_COMMITTED, stream, checked, 0L);
        }
        Receipt active = state.inFlight.get(checked.sequence());
        if (active != null) {
            requireSameEntry(active.entryBlueId, checked);
            return claim(Outcome.IN_FLIGHT, stream, checked, 0L);
        }
        long token = nextClaimToken = Math.addExact(nextClaimToken, 1L);
        state.inFlight.put(
                checked.sequence(), new Receipt(checked.entryBlueId(), token));
        return claim(Outcome.ACQUIRED, stream, checked, token);
    }

    public synchronized void commit(Claim claim) {
        Claim checked = acquired(claim);
        Progress state = require(checked.stream());
        Receipt active = state.inFlight.get(checked.sequence());
        if (active == null || active.token != checked.token()
                || !active.entryBlueId.equals(checked.entryBlueId())) {
            throw new IllegalStateException("Stale or foreign delivery claim");
        }
        state.inFlight.remove(checked.sequence());
        state.committed.put(checked.sequence(), active);
        state.committedHighWater = Math.max(
                state.committedHighWater, checked.sequence());
    }

    public synchronized void abandon(Claim claim) {
        Claim checked = acquired(claim);
        Progress state = require(checked.stream());
        Receipt active = state.inFlight.get(checked.sequence());
        if (active != null && active.token == checked.token()) {
            state.inFlight.remove(checked.sequence());
        }
    }

    public synchronized long admissionHighWater(StreamKey stream) {
        return require(stream).admissionHighWater;
    }

    public synchronized long contiguousHighWater(StreamKey stream) {
        return committedHighWater(stream);
    }

    /**
     * Highest relevant global-journal position committed for this stream.
     * Unrelated Timeline entries may legitimately create sequence gaps.
     */
    public synchronized long committedHighWater(StreamKey stream) {
        return require(stream).committedHighWater;
    }

    public synchronized Set<Long> committedSequences(StreamKey stream) {
        return Set.copyOf(new LinkedHashSet<>(require(stream).committed.keySet()));
    }

    public synchronized MyOsDeliveryLedger copy() {
        MyOsDeliveryLedger result = new MyOsDeliveryLedger();
        for (Map.Entry<StreamKey, Progress> entry : progress.entrySet()) {
            result.progress.put(entry.getKey(), entry.getValue().copy());
        }
        result.nextClaimToken = nextClaimToken;
        return result;
    }

    private Progress require(StreamKey stream) {
        Progress state = progress.get(Objects.requireNonNull(stream, "stream"));
        if (state == null) throw new IllegalArgumentException("Unknown stream");
        return state;
    }

    private static Claim acquired(Claim claim) {
        Claim checked = Objects.requireNonNull(claim, "claim");
        if (!checked.acquired()) {
            throw new IllegalArgumentException("Claim was not acquired");
        }
        return checked;
    }

    private static Claim claim(
            Outcome outcome,
            StreamKey stream,
            MyOsJournalPosition position,
            long token) {
        return new Claim(outcome, stream, position.sequence(),
                position.entryBlueId(), token);
    }

    private static void requireSameEntry(
            String storedEntryBlueId,
            MyOsJournalPosition supplied) {
        if (!storedEntryBlueId.equals(supplied.entryBlueId())) {
            throw new IllegalStateException(
                    "Journal sequence names conflicting entries");
        }
    }

    private static final class Progress {
        private final long admissionHighWater;
        private long committedHighWater;
        private final Map<Long, Receipt> inFlight = new LinkedHashMap<>();
        private final Map<Long, Receipt> committed = new LinkedHashMap<>();

        private Progress(long admissionHighWater) {
            this.admissionHighWater = admissionHighWater;
            this.committedHighWater = admissionHighWater;
        }

        private Progress copy() {
            Progress result = new Progress(admissionHighWater);
            result.committedHighWater = committedHighWater;
            result.inFlight.putAll(inFlight);
            result.committed.putAll(committed);
            return result;
        }
    }

    private record Receipt(String entryBlueId, long token) {
        private Receipt {
            Objects.requireNonNull(entryBlueId, "entryBlueId");
            if (token <= 0L) throw new IllegalArgumentException("token");
        }
    }
}
