package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable owner-safe identity of one append-once Timeline Entry. */
public final class EntryHandle {
    private final Object owner;
    private final TimelineHandle timeline;
    private final String blueId;
    private final Long globalSequence;
    private final Long timelineSequence;

    EntryHandle(Object owner, String blueId) {
        this(owner, null, blueId, null, null);
    }

    EntryHandle(
            Object owner,
            TimelineHandle timeline,
            String blueId,
            long globalSequence,
            long timelineSequence) {
        this(owner, Objects.requireNonNull(timeline, "timeline"), blueId,
                requirePositive(globalSequence, "globalSequence"),
                requirePositive(timelineSequence, "timelineSequence"));
        if (timeline.owner() != owner) {
            throw new IllegalArgumentException(
                    "Timeline handle belongs to another Coordination instance");
        }
    }

    private EntryHandle(
            Object owner,
            TimelineHandle timeline,
            String blueId,
            Long globalSequence,
            Long timelineSequence) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.timeline = timeline;
        this.blueId = SdkPreconditions.requireText(blueId, "blueId");
        this.globalSequence = globalSequence;
        this.timelineSequence = timelineSequence;
        if ((timeline == null) != (globalSequence == null)
                || (timeline == null) != (timelineSequence == null)) {
            throw new IllegalArgumentException(
                    "Timeline and sequence evidence must be complete");
        }
    }

    /** Exact content identity of the appended entry. */
    public String blueId() {
        return blueId;
    }

    /** Source Timeline when append evidence is available. */
    public Optional<TimelineHandle> timeline() {
        return Optional.ofNullable(timeline);
    }

    /** Global canonical append sequence when available. */
    public OptionalLong globalSequence() {
        return globalSequence == null
                ? OptionalLong.empty()
                : OptionalLong.of(globalSequence);
    }

    /** Source-local append sequence when available. */
    public OptionalLong timelineSequence() {
        return timelineSequence == null
                ? OptionalLong.empty()
                : OptionalLong.of(timelineSequence);
    }

    Object owner() {
        return owner;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EntryHandle handle
                && owner == handle.owner
                && blueId.equals(handle.blueId);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(owner) + blueId.hashCode();
    }

    @Override
    public String toString() {
        return blueId;
    }

    private static Long requirePositive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }
}
