package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;
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
     * <p>Discovery follows only the authoritative effective Contracts
     * {@code paths}/{@code collectionPaths} catalog. An embedded exact value
     * may create an all-new content-identified lineage or reuse an
     * unambiguous current, authored-initial, epoch-zero, or retained state of
     * an existing lineage. Historical reuse creates occurrence-specific
     * catch-up work; current reuse does not. Pure references are read through
     * the configured read-only exact-node provider.</p>
     */
    public ClosureHandle admitStaticProcessEmbedded(String authoredYaml) {
        return admitStaticProcessEmbedded(
                authoredYaml, ActivationPolicy.fromNow());
    }

    /**
     * Resolves a static authored closure with a policy for its new members.
     *
     * <p>The policy does not select an existing lineage or retained epoch;
     * exact retained-state matching remains automatic and ambiguous matches
     * fail closed.</p>
     */
    public ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            ActivationPolicy activationPolicy) {
        return runtime.admitStaticProcessEmbedded(
                Objects.requireNonNull(authoredYaml, "authoredYaml"),
                Objects.requireNonNull(activationPolicy,
                        "activationPolicy"));
    }

    /**
     * Resolves one static authored closure with typed selectors for otherwise
     * ambiguous retained occurrences.
     *
     * <p>Every selector path is relative to the newly authored Root and must
     * be consumed exactly once. Epoch {@code -1} selects an existing
     * lineage's authored initial position; it never creates an epoch
     * {@code -1} receipt.</p>
     */
    public ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            List<ManagedEpochSelector> selectors) {
        return admitStaticProcessEmbedded(
                authoredYaml, ActivationPolicy.fromNow(), selectors);
    }

    /**
     * Resolves one static authored closure with both temporal admission policy
     * and occurrence-specific retained selectors.
     */
    public ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            ActivationPolicy activationPolicy,
            List<ManagedEpochSelector> selectors) {
        return runtime.admitStaticProcessEmbedded(
                Objects.requireNonNull(authoredYaml, "authoredYaml"),
                Objects.requireNonNull(
                        activationPolicy, "activationPolicy"),
                List.copyOf(Objects.requireNonNull(
                        selectors, "selectors")));
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
