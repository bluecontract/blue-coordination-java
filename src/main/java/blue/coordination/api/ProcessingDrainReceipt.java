package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable result of one environment-selected drain to a safe frontier. */
public final class ProcessingDrainReceipt {
    private final List<TimelineEntry> processedEntries;
    private final Map<String, List<DocumentDispatchOutcome>> outcomesByEntry;
    private final Map<String, List<ContractsClosureDispatchAttempt>>
            contractsAttemptsByEntry;
    private final List<ManagedEpochApplicationReceipt>
            managedEpochApplications;
    private final List<ManagedEpochApplicationAttempt>
            managedEpochApplicationAttempts;
    private final List<ManagedEpochEvidenceFailure>
            managedEpochEvidenceFailures;
    private final ExternalOrderKey processedThrough;
    private final boolean quiescent;
    private final boolean paused;
    private final long committedProcessTransitions;
    private final long elapsedNanos;

    /** Creates bounded-drain evidence, including exact committed work. */
    public ProcessingDrainReceipt(
            List<TimelineEntry> processedEntries,
            Map<String, List<DocumentDispatchOutcome>> outcomesByEntry,
            ExternalOrderKey processedThrough,
            boolean quiescent,
            boolean paused,
            long committedProcessTransitions,
            long elapsedNanos) {
        this(processedEntries, outcomesByEntry, Map.of(), processedThrough,
                quiescent, paused, committedProcessTransitions, elapsedNanos,
                List.of(), List.of());
    }

    /**
     * Creates bounded-drain evidence including advanced Contracts attempts.
     */
    public ProcessingDrainReceipt(
            List<TimelineEntry> processedEntries,
            Map<String, List<DocumentDispatchOutcome>> outcomesByEntry,
            Map<String, List<ContractsClosureDispatchAttempt>>
                    contractsAttemptsByEntry,
            ExternalOrderKey processedThrough,
            boolean quiescent,
            boolean paused,
            long committedProcessTransitions,
            long elapsedNanos) {
        this(
                processedEntries,
                outcomesByEntry,
                contractsAttemptsByEntry,
                processedThrough,
                quiescent,
                paused,
                committedProcessTransitions,
                elapsedNanos,
                List.of(), List.of());
    }

    /**
     * Creates bounded-drain evidence including committed managed applications.
     */
    public ProcessingDrainReceipt(
            List<TimelineEntry> processedEntries,
            Map<String, List<DocumentDispatchOutcome>> outcomesByEntry,
            Map<String, List<ContractsClosureDispatchAttempt>>
                    contractsAttemptsByEntry,
            ExternalOrderKey processedThrough,
            boolean quiescent,
            boolean paused,
            long committedProcessTransitions,
            long elapsedNanos,
            List<ManagedEpochApplicationReceipt>
                    managedEpochApplications) {
        this(
                processedEntries,
                outcomesByEntry,
                contractsAttemptsByEntry,
                processedThrough,
                quiescent,
                paused,
                committedProcessTransitions,
                elapsedNanos,
                managedEpochApplications,
                List.of());
    }

    /**
     * Creates bounded-drain evidence including every managed processor
     * attempt, whether it committed or rolled back.
     */
    public ProcessingDrainReceipt(
            List<TimelineEntry> processedEntries,
            Map<String, List<DocumentDispatchOutcome>> outcomesByEntry,
            Map<String, List<ContractsClosureDispatchAttempt>>
                    contractsAttemptsByEntry,
            ExternalOrderKey processedThrough,
            boolean quiescent,
            boolean paused,
            long committedProcessTransitions,
            long elapsedNanos,
            List<ManagedEpochApplicationReceipt>
                    managedEpochApplications,
            List<ManagedEpochApplicationAttempt>
                    managedEpochApplicationAttempts) {
        this(
                processedEntries,
                outcomesByEntry,
                contractsAttemptsByEntry,
                processedThrough,
                quiescent,
                paused,
                committedProcessTransitions,
                elapsedNanos,
                managedEpochApplications,
                managedEpochApplicationAttempts,
                List.of());
    }

    /**
     * Creates bounded-drain evidence including pre-PROCESS immutable-evidence
     * failures with their exact selected work.
     */
    public ProcessingDrainReceipt(
            List<TimelineEntry> processedEntries,
            Map<String, List<DocumentDispatchOutcome>> outcomesByEntry,
            Map<String, List<ContractsClosureDispatchAttempt>>
                    contractsAttemptsByEntry,
            ExternalOrderKey processedThrough,
            boolean quiescent,
            boolean paused,
            long committedProcessTransitions,
            long elapsedNanos,
            List<ManagedEpochApplicationReceipt>
                    managedEpochApplications,
            List<ManagedEpochApplicationAttempt>
                    managedEpochApplicationAttempts,
            List<ManagedEpochEvidenceFailure>
                    managedEpochEvidenceFailures) {
        this.processedEntries = List.copyOf(Objects.requireNonNull(
                processedEntries, "processedEntries"));
        Map<String, List<DocumentDispatchOutcome>> copied =
                new LinkedHashMap<>();
        Objects.requireNonNull(outcomesByEntry, "outcomesByEntry")
                .forEach((entryBlueId, outcomes) -> copied.put(
                        requireText(entryBlueId, "entryBlueId"),
                        List.copyOf(Objects.requireNonNull(
                                outcomes, "outcomes"))));
        this.outcomesByEntry = Collections.unmodifiableMap(copied);
        Map<String, List<ContractsClosureDispatchAttempt>> attempts =
                new LinkedHashMap<>();
        Objects.requireNonNull(
                contractsAttemptsByEntry, "contractsAttemptsByEntry")
                .forEach((entryBlueId, values) -> attempts.put(
                        requireText(entryBlueId, "entryBlueId"),
                        List.copyOf(Objects.requireNonNull(
                                values, "contractsAttempts"))));
        this.contractsAttemptsByEntry = Collections.unmodifiableMap(attempts);
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
        this.processedThrough = processedThrough;
        this.quiescent = quiescent;
        this.paused = paused;
        if (quiescent && paused) {
            throw new IllegalArgumentException(
                    "A drain cannot be quiescent and paused");
        }
        if (committedProcessTransitions < 0L || elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "Drain measurements must be non-negative");
        }
        this.committedProcessTransitions = committedProcessTransitions;
        this.elapsedNanos = elapsedNanos;
    }

    /** Entries selected by the environment in exact canonical order. */
    public List<TimelineEntry> processedEntries() {
        return processedEntries;
    }

    /** Document transitions committed during this drain call. */
    public List<DocumentDispatchOutcome> outcomes() {
        List<DocumentDispatchOutcome> result = new ArrayList<>();
        outcomesByEntry.values().forEach(result::addAll);
        return Collections.unmodifiableList(result);
    }

    /** Returns the only committed document outcome or fails explicitly. */
    public DocumentDispatchOutcome onlyOutcome() {
        List<DocumentDispatchOutcome> all = outcomes();
        if (all.size() != 1) {
            throw new CoordinationException(
                    CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                    "Expected one outcome but got " + all.size());
        }
        return all.get(0);
    }

    /** Exact document transitions committed for one entry identity. */
    public List<DocumentDispatchOutcome> outcomesFor(String entryBlueId) {
        return outcomesByEntry.getOrDefault(
                requireText(entryBlueId, "entryBlueId"), List.of());
    }

    /** Immutable outcomes indexed by exact Timeline Entry BlueId. */
    public Map<String, List<DocumentDispatchOutcome>> outcomesByEntry() {
        return outcomesByEntry;
    }

    /** Advanced exact Contracts cohort attempts for one Timeline Entry. */
    public List<ContractsClosureDispatchAttempt> contractsAttemptsFor(
            String entryBlueId) {
        return contractsAttemptsByEntry.getOrDefault(
                requireText(entryBlueId, "entryBlueId"), List.of());
    }

    /** Advanced immutable Contracts attempts indexed by Timeline Entry. */
    public Map<String, List<ContractsClosureDispatchAttempt>>
            contractsAttemptsByEntry() {
        return contractsAttemptsByEntry;
    }

    /** Occurrence-specific source epochs committed by this drain call. */
    public List<ManagedEpochApplicationReceipt> managedEpochApplications() {
        return managedEpochApplications;
    }

    /** Exact processor attempts, including deterministic rollback evidence. */
    public List<ManagedEpochApplicationAttempt>
            managedEpochApplicationAttempts() {
        return managedEpochApplicationAttempts;
    }

    /** Immutable source-evidence failures selected before Contracts PROCESS. */
    public List<ManagedEpochEvidenceFailure> managedEpochEvidenceFailures() {
        return managedEpochEvidenceFailures;
    }

    /** Highest canonical external order completed by this environment. */
    public Optional<ExternalOrderKey> processedThrough() {
        return Optional.ofNullable(processedThrough);
    }

    /** Whether no eligible work remains at the requested cutoff. */
    public boolean quiescent() {
        return quiescent;
    }

    /** Whether deterministic work remains because this call hit its budget. */
    public boolean paused() {
        return paused;
    }

    /** Whether required work is waiting on unavailable prerequisite evidence. */
    public boolean blocked() {
        return !quiescent && !paused;
    }

    /** Frozen PROCESS revisions committed during this call. */
    public long committedProcessTransitions() {
        return committedProcessTransitions;
    }

    /** Total host and frozen elapsed time observed by this drain call. */
    public long elapsedNanos() {
        return elapsedNanos;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
