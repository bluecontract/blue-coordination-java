package blue.coordination.basic.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable journal visibility captured when a Timeline Entry is appended. */
public record EnvironmentFrontier(
        long globalSequence,
        Map<String, Long> timelineSequences) {
    public EnvironmentFrontier {
        if (globalSequence < 0L) {
            throw new IllegalArgumentException(
                    "globalSequence must be non-negative");
        }
        Map<String, Long> checked = new LinkedHashMap<>();
        Objects.requireNonNull(timelineSequences, "timelineSequences")
                .forEach((timeline, sequence) -> {
                    if (timeline == null || timeline.isBlank()) {
                        throw new IllegalArgumentException(
                                "timeline id must not be blank");
                    }
                    if (sequence == null || sequence < 0L) {
                        throw new IllegalArgumentException(
                                "timeline sequence must be non-negative");
                    }
                    checked.put(timeline, sequence);
                });
        timelineSequences = Collections.unmodifiableMap(checked);
    }

    public long sequenceFor(String timelineId) {
        return timelineSequences.getOrDefault(
                Objects.requireNonNull(timelineId, "timelineId"), 0L);
    }

    public boolean includes(ExactTimelineEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return includesEntry(
                entry.timeline().timelineId(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    boolean includesEntry(
            String timelineId,
            long entryGlobalSequence,
            long entryTimelineSequence) {
        return entryGlobalSequence <= globalSequence
                && entryTimelineSequence <= sequenceFor(timelineId);
    }
}
