package blue.coordination.basic.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable result of one external or processor-managed dispatch. */
public final class DispatchResult {
    private final ExactTimelineEntry entry;
    private final List<ProcessOutcome> outcomes;
    private final long elapsedNanos;

    public DispatchResult(
            ExactTimelineEntry entry,
            List<ProcessOutcome> outcomes,
            long elapsedNanos) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.outcomes = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(outcomes, "outcomes")));
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative");
        }
        this.elapsedNanos = elapsedNanos;
    }

    public ExactTimelineEntry entry() { return entry; }
    public List<ProcessOutcome> outcomes() { return outcomes; }
    public long elapsedNanos() { return elapsedNanos; }

    public ProcessOutcome onlyOutcome() {
        if (outcomes.size() != 1) {
            throw new IllegalStateException(
                    "Expected one outcome but got " + outcomes.size());
        }
        return outcomes.get(0);
    }
}
