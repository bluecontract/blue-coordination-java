package blue.coordination.basic.engine;

import java.util.Objects;

/** One exact committed PROCESS result before embedded follow-up drains. */
public record ProcessOutcome(
        DocumentSession session,
        DocumentRevision revision,
        EmbeddedOnlyLayout beforeLayout,
        EmbeddedOnlyLayout afterLayout,
        long totalNanos) {
    public ProcessOutcome {
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
