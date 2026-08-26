package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/** Admits and reads independently managed Contracts documents. */
public final class DocumentCatalog {
    private final SdkCoordinationRuntime runtime;

    DocumentCatalog(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Compiles and atomically admits one authored public Root. */
    public DocumentHandle admit(ManagedDocument definition) {
        return runtime.admit(Objects.requireNonNull(definition, "definition"));
    }

    /** Compiles, proves, and atomically admits one complete authored closure. */
    public ClosureHandle admit(ManagedClosure definition) {
        return runtime.admit(Objects.requireNonNull(definition, "definition"));
    }

    /**
     * Automatically resolves and atomically admits one static authored
     * Process Embedded closure.
     *
     * <p>Every all-new managed lineage is its exact authored
     * pre-initialization BlueId. Discovery follows only the authoritative
     * effective Contracts {@code paths}/{@code collectionPaths} catalog.
     * Pure references are read through the configured read-only exact-node
     * provider.</p>
     */
    public ClosureHandle admitStaticProcessEmbedded(String authoredYaml) {
        return admitStaticProcessEmbedded(
                authoredYaml, ActivationPolicy.fromNow());
    }

    /**
     * Automatically resolves a static authored closure using an existing
     * supported temporal admission policy.
     */
    public ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            ActivationPolicy activationPolicy) {
        return runtime.admitStaticProcessEmbedded(
                Objects.requireNonNull(authoredYaml, "authoredYaml"),
                Objects.requireNonNull(activationPolicy,
                        "activationPolicy"));
    }

    /** Creates invocation evidence for a future managed occurrence. */
    public ManagedDocumentDraft draft(
            DocumentId id,
            ExactBlueValue initial) {
        return runtime.draft(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(initial, "initial"));
    }

    /** Requires an admitted managed lineage. */
    public DocumentHandle require(DocumentId id) {
        return runtime.requireDocument(Objects.requireNonNull(id, "id"));
    }

    /** Exposes an admitted lineage as a Root without changing document state. */
    public DocumentHandle promotePublicRoot(DocumentId id) {
        return runtime.promotePublicRoot(Objects.requireNonNull(id, "id"));
    }
}
