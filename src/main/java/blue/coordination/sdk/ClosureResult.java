package blue.coordination.sdk;

import java.util.List;
import java.util.Objects;

/** Independent terminal result for one affected disconnected closure. */
public record ClosureResult(
        String closureId,
        EntryDisposition disposition,
        List<DocumentChange> changes,
        List<PublicEvent> publicEvents,
        ProcessingStats stats,
        Diagnostic diagnostic) {
    /** Defensively copies result collections. */
    public ClosureResult {
        closureId = SdkPreconditions.requireText(closureId, "closureId");
        disposition = Objects.requireNonNull(disposition, "disposition");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        publicEvents = List.copyOf(Objects.requireNonNull(
                publicEvents, "publicEvents"));
        stats = Objects.requireNonNull(stats, "stats");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Whether this closure published all of its exact state changes. */
    public boolean applied() {
        return disposition == EntryDisposition.APPLIED;
    }
}
