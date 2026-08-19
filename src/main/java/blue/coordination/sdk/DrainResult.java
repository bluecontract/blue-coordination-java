package blue.coordination.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable result of one canonical processing drain. */
public final class DrainResult {
    private final List<EntryResult> entries;
    private final Map<EntryHandle, EntryResult> byEntry;
    private final ProcessingStats stats;
    private final boolean quiescent;
    private final boolean paused;
    private final Diagnostic diagnostic;

    /** Creates a complete drain result in canonical entry order. */
    public DrainResult(
            List<EntryResult> entries,
            ProcessingStats stats,
            boolean quiescent,
            boolean paused,
            Diagnostic diagnostic) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        Map<EntryHandle, EntryResult> indexed = new LinkedHashMap<>();
        for (EntryResult result : this.entries) {
            EntryResult prior = indexed.put(
                    Objects.requireNonNull(result, "entry result").entry(),
                    result);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Drain contains a duplicate entry result: "
                                + result.entry().blueId());
            }
        }
        this.byEntry = Map.copyOf(indexed);
        this.stats = Objects.requireNonNull(stats, "stats");
        this.quiescent = quiescent;
        this.paused = paused;
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        if (quiescent && paused) {
            throw new IllegalArgumentException(
                    "A drain cannot be quiescent and paused");
        }
    }

    /** Terminal entry results in canonical processing order. */
    public List<EntryResult> entries() {
        return entries;
    }

    /** Requires the terminal result for a previously submitted entry. */
    public EntryResult entry(EntryHandle handle) {
        EntryHandle checked = Objects.requireNonNull(handle, "handle");
        EntryResult result = byEntry.get(checked);
        if (result == null) {
            throw new IllegalArgumentException(
                    "This drain has no result for entry " + checked.blueId());
        }
        return result;
    }

    /** Finds a terminal result without conflating absence with NO_MATCH. */
    public Optional<EntryResult> find(EntryHandle handle) {
        return Optional.ofNullable(byEntry.get(
                Objects.requireNonNull(handle, "handle")));
    }

    /** Aggregate semantic work performed by this drain call. */
    public ProcessingStats stats() {
        return stats;
    }

    /** Whether no eligible work remains at the requested frontier. */
    public boolean quiescent() {
        return quiescent;
    }

    /** Whether deterministic work remains because the call hit its budget. */
    public boolean paused() {
        return paused;
    }

    /** Whether required work is waiting on unavailable exact evidence. */
    public boolean blocked() {
        return !quiescent && !paused;
    }

    /** Drain-wide precise diagnostic, or {@link Diagnostic#none()}. */
    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
