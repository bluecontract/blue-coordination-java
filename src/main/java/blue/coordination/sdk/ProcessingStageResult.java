package blue.coordination.sdk;

import java.util.List;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingStageContext;
import java.util.Objects;
import java.util.Optional;

/**
 * Evidence from one root stage, before durable host publication. Unlike a drain,
 * this result makes no statement about later eligible work or command completion.
 * Consume/encode this scope's result before retiring the owning runtime.
 */
public final class ProcessingStageResult {
    /** Separates completed work, prerequisite waits, no selection and physical rejection. */
    public enum Disposition {
        /** Current selected stage completed; its state/evidence still requires host preparation/publication. */
        COMPLETED,
        /** Selection or execution requires exact prerequisite evidence; this is not command completion. */
        WAITING,
        /** No runnable stage was selected in this coherent view; a host wake guard is still required. */
        NO_WORK,
        /** Current publication failed; discard the mutable attempt rather than preparing its state. */
        NONCOMMITTING
    }

    private final Disposition disposition;
    private final DrainResult evidence;
    private final ProcessingStageContext selection;
    private final List<DocumentId> resultOwners;
    private final boolean selectionInvalidated;
    ProcessingStageResult(Disposition disposition, DrainResult evidence, ProcessingStageContext selection,
            List<DocumentId> resultOwners, boolean selectionInvalidated) {
        this.disposition = Objects.requireNonNull(disposition); this.evidence = Objects.requireNonNull(evidence);
        this.selection = Objects.requireNonNull(selection); this.resultOwners = List.copyOf(resultOwners);
        this.selectionInvalidated = selectionInvalidated;
        if (!this.resultOwners.equals(this.resultOwners.stream().distinct().sorted(java.util.Comparator.comparing(DocumentId::value)).toList())
                || !this.resultOwners.containsAll(selection.entryOwners().stream().map(ProcessingStageContext.Owner::documentId).toList()))
            throw new IllegalArgumentException("Result owners must contain the complete ordered entry-owner union");
    }
    /** Exact pre-execution cause and authority; does not inspect the successor selection. */
    public ProcessingStageContext selection() { return selection; }
    /** Complete publication owner union, retaining split members through this publication. */
    public List<DocumentId> resultOwners() { return resultOwners; }
    /** Whether committed graph changes invalidate any prefetched aggregate selection. */
    public boolean selectionInvalidated() { return selectionInvalidated; }
    DrainResult evidence() { return evidence; }
    /** Current-stage disposition, not a READY-head or command-terminal indicator. */
    public Disposition disposition() { return disposition; }
    /** Exact entry outcomes materialized by this stage. */
    public List<EntryResult> entries() { return evidence.entries(); }
    /** Requires this stage's exact result for an entry belonging to the same owner. */
    public EntryResult entry(EntryHandle handle) { return evidence.entry(handle); }
    /** Looks up an entry without conflating no result with NO_MATCH. */
    public Optional<EntryResult> find(EntryHandle handle) { return evidence.find(handle); }
    /** Exact gas and work measurements for this stage only. */
    public ProcessingStats stats() { return evidence.stats(); }
    /** Current-stage diagnostic, never an optional-readiness prediction. */
    public Diagnostic diagnostic() { return evidence.diagnostic(); }
    /** Exact source/local occurrence work and its current result. */
    public List<DrainResult.RootedRetainedApplication> rootedRetainedApplications() { return evidence.rootedRetainedApplications(); }
    /** Materialized managed application receipts in this stage. */
    public List<ManagedEpochApplicationReceipt> managedEpochApplications() { return evidence.managedEpochApplications(); }
    /** Actual managed attempts, including noncommitting publication failures. */
    public List<ManagedEpochApplicationAttempt> managedEpochApplicationAttempts() { return evidence.managedEpochApplicationAttempts(); }
    /** Pre-execution exact-source evidence failures. */
    public List<ManagedEpochEvidenceFailure> managedEpochEvidenceFailures() { return evidence.managedEpochEvidenceFailures(); }
}
