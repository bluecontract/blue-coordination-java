package blue.coordination.internal;

import blue.coordination.api.DocumentRevision;

import java.util.Objects;

/** One exact committed PROCESS result before embedded follow-up drains. */
record InternalProcessOutcome(
        DocumentSession session,
        DocumentRevision revision,
        EmbeddedOnlyLayout beforeLayout,
        EmbeddedOnlyLayout afterLayout,
        long totalNanos) {
    public InternalProcessOutcome {
        session = Objects.requireNonNull(session, "session");
        revision = Objects.requireNonNull(revision, "revision");
        beforeLayout = Objects.requireNonNull(beforeLayout, "beforeLayout");
        afterLayout = Objects.requireNonNull(afterLayout, "afterLayout");
        if (totalNanos < 0L) {
            throw new IllegalArgumentException(
                    "totalNanos must be non-negative");
        }
    }
}
