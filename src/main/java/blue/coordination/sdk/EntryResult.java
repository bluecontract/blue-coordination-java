package blue.coordination.sdk;

import java.util.List;
import java.util.Objects;

/** Immutable terminal SDK result for one append-once entry. */
public record EntryResult(
        EntryHandle entry,
        EntryDisposition disposition,
        List<ClosureResult> closures,
        List<PublicEvent> publicEvents,
        ProcessingStats stats,
        Diagnostic diagnostic) {
    /** Defensively copies independent closure and event results. */
    public EntryResult {
        entry = Objects.requireNonNull(entry, "entry");
        disposition = Objects.requireNonNull(disposition, "disposition");
        closures = List.copyOf(Objects.requireNonNull(closures, "closures"));
        publicEvents = List.copyOf(Objects.requireNonNull(
                publicEvents, "publicEvents"));
        stats = Objects.requireNonNull(stats, "stats");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Whether every affected closure committed successfully. */
    public boolean applied() {
        return disposition == EntryDisposition.APPLIED;
    }
}
