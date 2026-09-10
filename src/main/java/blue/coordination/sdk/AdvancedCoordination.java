package blue.coordination.sdk;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.RetainedHistoryProvider;
import blue.coordination.api.EmbeddedCollectionPlanningAudit;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.ProcessingAvailability;
import blue.coordination.api.ProcessingSelection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit escape hatch for diagnostics and low-level compatibility. */
public final class AdvancedCoordination {
    private final SdkCoordinationRuntime runtime;

    AdvancedCoordination(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Returns the low-level engine owned by this SDK environment. */
    public CoordinationEngine rawEngine() {
        return runtime.engine();
    }

    /**
     * Processes one root input with an explicit low-level execution policy.
     * Ordinary processing retains the configured release policy.
     * @param root selected managed root
     * @param input exact supplied entry
     * @param policy immutable shared and member gas limits
     * @return the complete processing outcome
     */
    public DrainResult process(DocumentHandle root, EntryHandle input,
            blue.coordination.api.ContractsExecutionPolicy policy) {
        return runtime.processRootInput(Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(input, "input"), Objects.requireNonNull(policy, "policy"));
    }

    /**
     * Selects exact source-owned work required by a genuinely suspended root input.
     * Each descriptor represents one source action; provider absence remains an explicit wait.
     * @param root requesting managed root
     * @return individual fenced prerequisites; empty when none are pending
     */
    public List<blue.coordination.api.SourceHistoryPrerequisite> sourceHistoryPrerequisites(DocumentHandle root) {
        return runtime.sourceHistoryPrerequisites(Objects.requireNonNull(root, "root"));
    }

    /**
     * Observes one previously emitted prerequisite without executing its source or retrying its parent.
     * The original root, invocation, demand, source, authored identity and cutoff are immutable authority;
     * physical selection fields may refresh. PENDING includes explicit WAIT, SATISFIED requires current
     * verified source completeness, and missing or terminal requesting authority is STALE.
     * An empty {@link #sourceHistoryPrerequisites(DocumentHandle)} list alone proves no satisfaction.
     * @param expected original descriptor whose frozen logical authority is being observed
     * @return typed observation with a fresh exact descriptor only when pending
     * @throws IllegalArgumentException if retained correlation has different frozen logical operands
     */
    public blue.coordination.api.SourceHistoryPrerequisiteObservation observeSourceHistoryPrerequisite(
            blue.coordination.api.SourceHistoryPrerequisite expected) {
        return runtime.observeSourceHistoryPrerequisite(Objects.requireNonNull(expected, "expected"));
    }

    /**
     * Executes one selected source prerequisite without retrying its waiting parent.
     * @param expected exact descriptor from sourceHistoryPrerequisites
     * @return actual source result and its independent publication/meter evidence
     */
    public blue.coordination.api.SourceHistoryPrerequisiteResult processSourceHistoryPrerequisite(
            blue.coordination.api.SourceHistoryPrerequisite expected) {
        return runtime.processSourceHistoryPrerequisite(Objects.requireNonNull(expected, "expected"));
    }

    /**
     * Reads the SDK view retained by one completed source execution, without executing another obligation.
     * @param expected complete exact descriptor used for that source execution
     * @return actual mapped drain, or empty for an admission, unknown, or mismatched descriptor
     */
    public Optional<DrainResult> sourceHistoryProcessingResult(blue.coordination.api.SourceHistoryPrerequisite expected) {
        return runtime.sourceHistoryProcessingResult(Objects.requireNonNull(expected, "expected"));
    }

    /**
     * Applies only the selected root's exact retained prerequisite, failing before processing on mismatch.
     * @param root authoritative root owning the calculation
     * @param workIdentity exact work identity from the read-only processing selection
     * @return complete local retained-work evidence
     */
    public DrainResult processRetained(DocumentHandle root, String workIdentity) {
        return runtime.processNextRoot(Objects.requireNonNull(root, "root"),
                SdkPreconditions.requireText(workIdentity, "workIdentity"));
    }

    /**
     * Drains one eligible journal selection no later than the supplied exact entry.
     * Repeated calls can finish separate rooted observers of that entry without
     * consuming future inputs. Managed application turns remain separately scheduled.
     * @param inclusiveEntry retained entry defining the full external-order cutoff
     * @param budget deterministic between-invocation limits
     * @return complete evidence for this bounded journal selection
     */
    public DrainResult drainJournalThrough(EntryHandle inclusiveEntry, DrainBudget budget) {
        return runtime.drainJournalThrough(Objects.requireNonNull(inclusiveEntry, "inclusiveEntry"),
                Objects.requireNonNull(budget, "budget"));
    }

    /**
     * Reads the full retained processor evidence for a terminal closure identity.
     * Calculated dependency records are local views; only the rooted projection's
     * owners have authoritative publication rights. Failures carry no such rights.
     * @param publicationIdentity terminal closure identity returned by processing
     * @return exact retained result and gas trace, or empty if no terminal record exists
     */
    public Optional<blue.language.processor.closure.ClosureProcessResult> closureExecution(String publicationIdentity) {
        return runtime.auditClosureExecution(requireIdentity(publicationIdentity, "publicationIdentity"));
    }

    /**
     * Reads the original exact input of a retained closure decision, including rollback.
     * @param publicationIdentity terminal closure identity returned by processing
     * @return immutable original invocation, or empty when no such evidence was retained
     */
    public Optional<blue.language.processor.closure.ClosureInvocationInput> closureInvocation(String publicationIdentity) {
        return runtime.auditClosureInvocation(requireIdentity(publicationIdentity, "publicationIdentity"));
    }

    /**
     * Exports bounded signed pages of already committed source history.
     * The receiving host must pin this producer's public key independently.
     * This evidence transport does not install receipts in another engine.
     */
    public RetainedHistoryProvider retainedHistoryProvider(
            java.security.KeyPair producerKeys) {
        return RetainedHistoryProvider.from(this, producerKeys);
    }

    /** Reads a non-READY snapshot for audit and recovery tooling. */
    public blue.coordination.api.DocumentSnapshot auditDocument(
            DocumentId id) {
        return runtime.engine().auditDocument(
                Objects.requireNonNull(id, "id"));
    }

    /**
     * Reads SDK-only provider evidence for the latest committed state,
     * including a complete authenticated cyclic placeholder set when needed.
     *
     * <p>This deliberately follows {@link #auditDocument(DocumentId)} rather
     * than the ordinary READY document view, so recovery tooling can retain a
     * committed representation while catch-up readiness is still fenced.</p>
     */
    public Optional<ExactNodeEvidence> auditExactNodeEvidence(DocumentId id) {
        return runtime.auditExactNodeEvidence(
                Objects.requireNonNull(id, "id"));
    }

    /** Reads retained lineage state for one managed source occurrence. */
    public Optional<ManagedOccurrenceAudit> auditManagedOccurrence(
            DocumentId sourceDocumentId,
            String sourcePath) {
        return runtime.auditManagedOccurrence(
                Objects.requireNonNull(sourceDocumentId, "sourceDocumentId"),
                SdkPreconditions.requireOccurrencePath(sourcePath));
    }

    /** Reads one complete managed source epoch receipt. */
    public Optional<ManagedEpochReceipt> auditManagedEpoch(
            DocumentId documentId,
            long epoch) {
        return runtime.auditManagedEpoch(
                Objects.requireNonNull(documentId, "documentId"), epoch);
    }

    /** Reads every complete managed source receipt in epoch order. */
    public List<ManagedEpochReceipt> auditManagedEpochs(
            DocumentId documentId) {
        return runtime.auditManagedEpochs(
                Objects.requireNonNull(documentId, "documentId"));
    }

    /** Reads one complete source receipt by its canonical identity. */
    public Optional<ManagedEpochReceipt> auditManagedEpochReceipt(
            String receiptIdentity) {
        return runtime.auditManagedEpochReceipt(
                requireIdentity(receiptIdentity, "receiptIdentity"));
    }

    /** Reads one occurrence-specific catch-up plan by canonical identity. */
    public Optional<ManagedOccurrenceCatchUpPlan> auditManagedCatchUpPlan(
            String planIdentity) {
        return runtime.engine().auditManagedCatchUpPlan(
                requireIdentity(planIdentity, "planIdentity"));
    }

    /** Reads every retained catch-up plan owned by one consumer document. */
    public List<ManagedOccurrenceCatchUpPlan> auditManagedCatchUpPlans(
            DocumentId consumerDocumentId) {
        return runtime.engine().auditManagedCatchUpPlans(
                Objects.requireNonNull(
                        consumerDocumentId, "consumerDocumentId"));
    }

    /** Reads one aggregate catch-up readiness barrier. */
    public Optional<ManagedCatchUpBarrier> auditManagedCatchUpBarrier(
            String barrierIdentity) {
        return runtime.engine().auditManagedCatchUpBarrier(
                requireIdentity(barrierIdentity, "barrierIdentity"));
    }

    /** Reads one canonical selected managed-epoch application work item. */
    public Optional<blue.coordination.api.ManagedEpochApplicationWork>
            auditManagedEpochApplicationWork(String workIdentity) {
        return runtime.engine().auditManagedEpochApplicationWork(
                requireIdentity(workIdentity, "workIdentity"));
    }

    /**
     * Reads the exact next fair bounded lane without executing or reserving it.
     */
    public ProcessingSelection auditNextProcessingSelection() {
        return runtime.engine().auditNextProcessingSelection();
    }

    /**
     * Reads the exact next fair bounded lane while considering whether the
     * host can immediately admit one ordinary journal entry.
     */
    public ProcessingSelection auditNextProcessingSelection(
            ProcessingAvailability availability) {
        return runtime.engine().auditNextProcessingSelection(
                Objects.requireNonNull(availability, "availability"));
    }

    /** Reads one committed managed-epoch application receipt. */
    public Optional<blue.coordination.api.ManagedEpochApplicationReceipt>
            auditManagedEpochApplicationReceipt(
                    String applicationReceiptIdentity) {
        return runtime.engine().auditManagedEpochApplicationReceipt(
                requireIdentity(
                        applicationReceiptIdentity,
                        "applicationReceiptIdentity"));
    }

    /** Reads committed-versus-ready state and active barriers for one document. */
    public Optional<ManagedDocumentReadiness> auditManagedDocumentReadiness(
            DocumentId documentId) {
        return runtime.engine().auditManagedDocumentReadiness(
                Objects.requireNonNull(documentId, "documentId"));
    }

    /** Reads the current processor-compiled external operation routes. */
    public List<OperationRouteSnapshot> auditOperationRoutes(DocumentId id) {
        return runtime.auditOperationRoutes(
                Objects.requireNonNull(id, "id"));
    }

    /** Reads the exact collection-presence plan retained at the current head. */
    public List<EmbeddedCollectionPlanningAudit> auditEmbeddedCollections(
            DocumentId id) {
        return runtime.auditEmbeddedCollections(
                Objects.requireNonNull(id, "id"));
    }

    /** Reads one whole retained Timeline Entry without mutating processing. */
    public Optional<TimelineEntrySnapshot> auditTimelineEntry(
            String entryBlueId) {
        return runtime.auditTimelineEntry(
                SdkPreconditions.requireText(entryBlueId, "entryBlueId"));
    }

    /** Reads every retained Timeline Entry in canonical append order. */
    public List<TimelineEntrySnapshot> auditTimelineEntries() {
        return runtime.auditTimelineEntries();
    }

    /** Reads one Timeline's retained entries in source-local order. */
    public List<TimelineEntrySnapshot> auditTimeline(String timelineId) {
        return runtime.auditTimeline(
                SdkPreconditions.requireText(timelineId, "timelineId"));
    }

    public String blueLanguageSpecificationIdentity() {
        return runtime.languageSpecificationIdentity();
    }

    public String contractsSpecificationIdentity() {
        return runtime.contractsSpecificationIdentity();
    }

    public Optional<String> bundledContractsReleaseIdentity() {
        return runtime.bundledIdentity("release");
    }

    public Optional<String> bundledFixturePackageIdentity() {
        return runtime.bundledIdentity("fixtures");
    }

    public Optional<String> bundledGasManifestIdentity() {
        return runtime.bundledIdentity("gas");
    }

    public Optional<String> bundledCyclicFinalizerIdentity() {
        return runtime.bundledIdentity("finalizer");
    }

    public Optional<String> bundledCyclicProofVerifierIdentity() {
        return runtime.bundledIdentity("verifier");
    }

    private static String requireIdentity(String value, String label) {
        String checked = SdkPreconditions.requireText(value, label);
        if (!checked.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase sha256 identity");
        }
        return checked;
    }
}
