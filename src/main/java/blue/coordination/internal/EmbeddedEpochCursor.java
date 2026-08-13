package blue.coordination.internal;

import java.util.Objects;

/** Durable parent-occurrence progress kept separately from graph topology. */
record EmbeddedEpochCursor(String bindingId, long appliedChildEpoch) {
    EmbeddedEpochCursor {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        if (bindingId.isBlank()) {
            throw new IllegalArgumentException("bindingId must not be blank");
        }
        if (appliedChildEpoch < -1L) {
            throw new IllegalArgumentException(
                    "appliedChildEpoch must be at least -1");
        }
    }

    EmbeddedEpochCursor advanceTo(long childEpoch) {
        if (childEpoch != appliedChildEpoch + 1L) {
            throw new IllegalStateException(
                    "Child epochs must be applied contiguously: current="
                            + appliedChildEpoch + ", next=" + childEpoch);
        }
        return new EmbeddedEpochCursor(bindingId, childEpoch);
    }
}
