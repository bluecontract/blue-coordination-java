package blue.coordination.sdk;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
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
