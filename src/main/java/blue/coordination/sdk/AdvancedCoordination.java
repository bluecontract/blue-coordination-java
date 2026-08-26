package blue.coordination.sdk;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
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

    /** Reads retained lineage state for one managed source occurrence. */
    public Optional<ManagedOccurrenceAudit> auditManagedOccurrence(
            DocumentId sourceDocumentId,
            String sourcePath) {
        return runtime.auditManagedOccurrence(
                Objects.requireNonNull(sourceDocumentId, "sourceDocumentId"),
                SdkPreconditions.requireOccurrencePath(sourcePath));
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
}
