package blue.coordination.api;

import java.util.Objects;

/** One append-only Timeline and the actor whose entries it authenticates. */
public record Timeline(String timelineId, String actorId) {
    /** Validates the Timeline and actor identities. */
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
