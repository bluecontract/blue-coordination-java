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
}
