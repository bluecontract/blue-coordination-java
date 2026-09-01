package blue.coordination.sdk;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
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
