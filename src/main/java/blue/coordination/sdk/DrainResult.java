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
    private final List<ManagedEpochApplicationReceipt>
            managedEpochApplications;
    private final List<ManagedEpochApplicationAttempt>
            managedEpochApplicationAttempts;
    private final List<ManagedEpochEvidenceFailure>
            managedEpochEvidenceFailures;

    /** Creates a complete drain result in canonical entry order. */
    public DrainResult(
            List<EntryResult> entries,
            ProcessingStats stats,
            boolean quiescent,
            boolean paused,
            Diagnostic diagnostic) {
        this(entries, stats, quiescent, paused, diagnostic, List.of(),
                List.of());
    }

    /** Creates a drain result with typed retained catch-up evidence. */
    public DrainResult(
            List<EntryResult> entries,
            ProcessingStats stats,
            boolean quiescent,
            boolean paused,
            Diagnostic diagnostic,
            List<ManagedEpochApplicationReceipt>
                    managedEpochApplications) {
        this(entries, stats, quiescent, paused, diagnostic,
                managedEpochApplications, List.of());
    }

    /** Creates a drain result with committed and attempted catch-up evidence. */
    public DrainResult(
            List<EntryResult> entries,
            ProcessingStats stats,
            boolean quiescent,
            boolean paused,
            Diagnostic diagnostic,
            List<ManagedEpochApplicationReceipt>
                    managedEpochApplications,
            List<ManagedEpochApplicationAttempt>
                    managedEpochApplicationAttempts) {
        this(
                entries,
                stats,
                quiescent,
                paused,
                diagnostic,
                managedEpochApplications,
                managedEpochApplicationAttempts,
                List.of());
    }

    /** Creates a result including typed pre-PROCESS source-evidence failures. */
    public DrainResult(
            List<EntryResult> entries,
            ProcessingStats stats,
            boolean quiescent,
            boolean paused,
            Diagnostic diagnostic,
            List<ManagedEpochApplicationReceipt>
                    managedEpochApplications,
            List<ManagedEpochApplicationAttempt>
                    managedEpochApplicationAttempts,
            List<ManagedEpochEvidenceFailure>
                    managedEpochEvidenceFailures) {
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
        this.managedEpochApplications = List.copyOf(Objects.requireNonNull(
                managedEpochApplications, "managedEpochApplications"));
        this.managedEpochApplicationAttempts = List.copyOf(
                Objects.requireNonNull(
                        managedEpochApplicationAttempts,
                        "managedEpochApplicationAttempts"));
        this.managedEpochEvidenceFailures = List.copyOf(
                Objects.requireNonNull(
                        managedEpochEvidenceFailures,
                        "managedEpochEvidenceFailures"));
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

    /** Exact occurrence-specific managed epochs committed in this drain. */
    public List<ManagedEpochApplicationReceipt> managedEpochApplications() {
        return managedEpochApplications;
    }

    /** Exact managed processor attempts, including atomic rollbacks. */
    public List<ManagedEpochApplicationAttempt>
            managedEpochApplicationAttempts() {
        return managedEpochApplicationAttempts;
    }

    /** Exact immutable-evidence failures selected before Contracts PROCESS. */
    public List<ManagedEpochEvidenceFailure> managedEpochEvidenceFailures() {
        return managedEpochEvidenceFailures;
    }
}
