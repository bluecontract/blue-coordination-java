package blue.coordination.api;

import java.util.List;
import java.util.Objects;

/** A staged lifecycle decision. PREPARED becomes durable only with the host publication. */
public record DocumentInstanceRetirement(Status status, DocumentInstancePosition retainedPosition, List<String> blockers) {
    /** Conservative supported lifecycle decisions; blocked decisions perform no state mutation. */
    public enum Status { PREPARED, BLOCKED_POLICY_REQUIRED }
    /** Validates a decision for the exact original archived instance. */
    public DocumentInstanceRetirement {
        Objects.requireNonNull(status); Objects.requireNonNull(retainedPosition); blockers = List.copyOf(blockers);
        if ((status == Status.PREPARED) != blockers.isEmpty())
            throw new IllegalArgumentException("Retirement blockers disagree with decision");
    }
}
