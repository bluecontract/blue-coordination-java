package blue.coordination.basic.engine;

import java.util.Objects;

/** One append-only Timeline and the actor whose entries it authenticates. */
public record Timeline(String timelineId, String actorId) {
    public Timeline {
        timelineId = requireText(timelineId, "timelineId");
        actorId = requireText(actorId, "actorId");
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
