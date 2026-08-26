package blue.coordination.sdk;

import java.util.Objects;

/** Stable application-facing owner of one in-memory Coordination runtime. */
public final class BlueCoordination implements AutoCloseable {
    private final SdkCoordinationRuntime runtime;
    private final TimelineCatalog timelines;
    private final DocumentCatalog documents;
    private final OperationGateway operations;
    private final EventGateway events;
    private final ProcessingGateway processing;
    private final ExactValues values;
    private final AdvancedCoordination advanced;

    private BlueCoordination(
            String languageIdentity,
            String contractsIdentity,
            ExactNodeProvider exactNodeProvider,
            boolean contentDerivedDocumentIds) {
        runtime = SdkCoordinationRuntime.create(
                this,
                languageIdentity,
                contractsIdentity,
                exactNodeProvider,
                contentDerivedDocumentIds);
        timelines = new TimelineCatalog(runtime);
        documents = new DocumentCatalog(runtime);
        operations = new OperationGateway(runtime);
        events = new EventGateway(runtime);
        processing = new ProcessingGateway(runtime);
        values = new ExactValues(runtime);
        advanced = new AdvancedCoordination(runtime);
    }

    /** Creates the supported in-memory runtime pinned to the bundled release. */
    public static BlueCoordination inMemory() {
        return builder().build();
    }

    /** Starts advanced runtime configuration. */
    public static Builder builder() {
        return new Builder();
    }

    public TimelineCatalog timelines() { return timelines; }

    public DocumentCatalog documents() { return documents; }

    public OperationGateway operations() { return operations; }

    public EventGateway events() { return events; }

    public ProcessingGateway processing() { return processing; }

    public ExactValues values() { return values; }

    public AdvancedCoordination advanced() { return advanced; }

    @Override
    public void close() {
        runtime.close();
    }

    /** Advanced custom-release builder; ordinary callers use {@link #inMemory()}. */
    public static final class Builder {
        private String languageIdentity;
        private String contractsIdentity;
        private ExactNodeProvider exactNodeProvider = ExactNodeProvider.empty();
        private boolean contentDerivedDocumentIds;

        /** Selects an explicit exact Contracts release identity pair. */
        public Builder release(
                String blueLanguageSpecificationIdentity,
                String contractsSpecificationIdentity) {
            languageIdentity = requireIdentity(
                    blueLanguageSpecificationIdentity,
                    "blueLanguageSpecificationIdentity");
            contractsIdentity = requireIdentity(
                    contractsSpecificationIdentity,
                    "contractsSpecificationIdentity");
            return this;
        }

        /**
         * Supplies read-only serialized application exact values and type
         * definitions to ordinary runtime resolution and Process Embedded
         * admission.
         *
         * <p>Every returned whole value is parsed defensively and must
         * establish the exact requested BlueId before it can participate in
         * resolution. Use
         * {@link ExactValues#providerContentYaml(String)} to prepare authored
         * provider YAML that contains aliases or schema-bearing type
         * definitions.</p>
         */
        public Builder exactNodeProvider(ExactNodeProvider provider) {
            exactNodeProvider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        /**
         * Selects exact authored pre-initialization BlueIds as managed
         * lineages for explicit SDK admissions.
         *
         * <p>Any authored {@code /documentId} remains ordinary untouched
         * content. It contributes to the exact BlueId but never selects the
         * managed lineage.</p>
         */
        public Builder contentDerivedDocumentIds() {
            contentDerivedDocumentIds = true;
            return this;
        }

        /** Builds an in-memory runtime, using the bundled release by default. */
        public BlueCoordination build() {
            if ((languageIdentity == null) != (contractsIdentity == null)) {
                throw new IllegalStateException(
                        "Both custom release identities are required");
            }
            return new BlueCoordination(
                    languageIdentity,
                    contractsIdentity,
                    exactNodeProvider,
                    contentDerivedDocumentIds);
        }

        private static String requireIdentity(String value, String label) {
            String checked = Objects.requireNonNull(value, label);
            if (!checked.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        label + " must be a lowercase sha256 identity");
            }
            return checked;
        }
    }
}
