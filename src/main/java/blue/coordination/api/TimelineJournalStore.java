package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;
import java.util.Optional;

/**
 * Host-owned physical storage for the existing Timeline journal machine.
 *
 * <p>Rows are immutable exact values, not instructions to replay. All indexes
 * and state must be consistent, complete, and atomically published. Opening a
 * view pins one coherent state without materializing all entries. A view may
 * use a database snapshot or a store-owned lock; callers always close it.
 * Implementations must not return empty results for unavailable/corrupt data.
 * The caller owns the store lifecycle.</p>
 */
public interface TimelineJournalStore {
    ReadView openRead();

    /**
     * Atomically applies a physical mutation only if current state equals
     * {@code expected}. A conflict or failure throws; it never selects a newer
     * head or partially updates indexes. The journal owns mutation semantics.
     * The journal closes its read view before calling this method; adapters may
     * reject a same-thread mutation while that thread still owns a read view.
     */
    void apply(State expected, Mutation mutation);

    interface ReadView extends AutoCloseable {
        State state();
        Optional<TimelineEntry> byBlueId(String blueId);
        Optional<TimelineEntry> timelineHead(String timelineId);
        /** Zero-based canonical append position. */
        Optional<TimelineEntry> atAppendPosition(int position);
        /** One-based position within a Timeline. */
        Optional<TimelineEntry> atTimelineSequence(String timelineId, long sequence);
        /** First entry when the cursor is null; otherwise strictly greater. */
        Optional<TimelineEntry> nextExternal(ExternalOrderKey afterExclusive);
        Optional<TimelineEntry> atExternalOrder(ExternalOrderKey order);
        Optional<ExternalOrderKey> latestExternalOrder();
        @Override void close();
    }

    record State(long globalSequence, int entryCount, long revision,
                 Availability availability) {
        public State {
            if (globalSequence < 0 || entryCount < 0 || revision < 0) {
                throw new IllegalArgumentException("journal state must be non-negative");
            }
            Objects.requireNonNull(availability, "availability");
        }
        public static State empty() {
            return new State(0, 0, 0, Availability.available());
        }
    }

    enum AvailabilityKind { AVAILABLE, UNAVAILABLE, INVALID_EVIDENCE }

    record Availability(AvailabilityKind kind, String diagnostic) {
        public Availability {
            Objects.requireNonNull(kind, "kind");
            if (kind == AvailabilityKind.AVAILABLE) {
                if (diagnostic != null) {
                    throw new IllegalArgumentException("available has no diagnostic");
                }
            } else if (diagnostic == null || diagnostic.isBlank()) {
                throw new IllegalArgumentException("blocked availability needs diagnostic");
            }
        }
        public static Availability available() {
            return new Availability(AvailabilityKind.AVAILABLE, null);
        }
    }

    sealed interface Mutation permits Append, Truncate, SetAvailability {
        State next();
    }

    /** Adds one immutable row and all its indexes. */
    record Append(TimelineEntry entry, State next) implements Mutation {
        public Append {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(next, "next");
        }
    }

    /** Retains the first {@code next.entryCount()} rows and repairs heads. */
    record Truncate(State next) implements Mutation {
        public Truncate { Objects.requireNonNull(next, "next"); }
    }

    /** Changes only availability; does not change the semantic revision. */
    record SetAvailability(State next) implements Mutation {
        public SetAvailability { Objects.requireNonNull(next, "next"); }
    }
}
