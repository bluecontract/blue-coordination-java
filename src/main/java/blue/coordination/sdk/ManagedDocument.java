package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;

/** Immutable ordinary managed-document admission definition. */
public final class ManagedDocument {
    private final DocumentId id;
    private final String authoredYaml;
    private final boolean publicRoot;
    private final ActivationPolicy activationPolicy;

    private ManagedDocument(
            DocumentId id,
            String authoredYaml,
            boolean publicRoot,
            ActivationPolicy activationPolicy) {
        this.id = Objects.requireNonNull(id, "id");
        this.authoredYaml = SdkPreconditions.requireText(
                authoredYaml, "authoredYaml");
        this.publicRoot = publicRoot;
        this.activationPolicy = activationPolicy;
    }

    /** Creates an authored YAML definition with the supplied stable lineage. */
    public static ManagedDocument yaml(String documentId, String authoredYaml) {
        return yaml(DocumentId.of(documentId), authoredYaml);
    }

    /** Creates an authored YAML definition with the supplied stable lineage. */
    public static ManagedDocument yaml(DocumentId id, String authoredYaml) {
        return new ManagedDocument(id, authoredYaml, false, null);
    }

    /** Marks this document as an externally authorized public Root. */
    public ManagedDocument publicRoot() {
        return new ManagedDocument(id, authoredYaml, true, activationPolicy);
    }

    /** Selects birth-at-admission temporal semantics. */
    public ManagedDocument fromNow() {
        return activation(ActivationPolicy.fromNow());
    }

    /** Selects an explicit supported temporal admission policy. */
    public ManagedDocument activation(ActivationPolicy policy) {
        return new ManagedDocument(id, authoredYaml, publicRoot,
                Objects.requireNonNull(policy, "policy"));
    }

    /** Stable managed lineage identity. */
    public DocumentId id() {
        return id;
    }

    /** Original authored YAML supplied for exact resolution by the engine. */
    public String authoredYaml() {
        return authoredYaml;
    }

    /** Whether this definition authorizes an externally visible Root. */
    public boolean isPublicRoot() {
        return publicRoot;
    }

    /** Selected temporal policy; admission fails when one was not selected. */
    public ActivationPolicy activationPolicy() {
        if (activationPolicy == null) {
            throw new IllegalStateException(
                    "Select an activation policy before admission");
        }
        return activationPolicy;
    }
}
