package blue.coordination.examples.support;

import java.util.List;
import java.util.Objects;

/** Immutable append-head and binding state for one demo Timeline. */
public record MyOsTimelineCheckpoint(
        String timelineId,
        MyOsDemoActor actor,
        List<String> entryBlueIds,
        String previousEntryBlueId,
        MyOsTimelineBinding binding) {

    public MyOsTimelineCheckpoint {
        timelineId = requireText(timelineId, "timelineId");
        actor = Objects.requireNonNull(actor, "actor");
        entryBlueIds = List.copyOf(
                Objects.requireNonNull(entryBlueIds, "entryBlueIds"));
        for (String entryBlueId : entryBlueIds) {
            requireText(entryBlueId, "entryBlueId");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        return checked;
    }
}
