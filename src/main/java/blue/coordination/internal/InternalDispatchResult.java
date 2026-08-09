package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable result of one external or processor-managed dispatch. */
final class InternalDispatchResult {
    private final TimelineEntry entry;
    private final List<InternalProcessOutcome> outcomes;
    private final long elapsedNanos;

    public InternalDispatchResult(
            TimelineEntry entry,
            List<InternalProcessOutcome> outcomes,
            long elapsedNanos) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.outcomes = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(outcomes, "outcomes")));
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative");
        }
        this.elapsedNanos = elapsedNanos;
    }

    public TimelineEntry entry() { return entry; }
    public List<InternalProcessOutcome> outcomes() { return outcomes; }
    public long elapsedNanos() { return elapsedNanos; }

    public InternalProcessOutcome onlyOutcome() {
        if (outcomes.size() != 1) {
            throw new IllegalStateException(
                    "Expected one outcome but got " + outcomes.size());
        }
        return outcomes.get(0);
    }
}
