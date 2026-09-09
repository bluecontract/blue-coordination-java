package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ExecutionPolicy;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable host policy used to construct one Contracts 1.0 environment. */
final class ContractsClosureProfile {
    /** Exact reviewed specification selecting the draft.2 execution path. */
    static final String ROOTED_CONTRACTS_SPECIFICATION =
            "sha256:c7c4e4d72ffb2c0da608c7ac2d8e7da920d0a193b84c732e5731c9bf1a2ee15a";
    private final String blueLanguageSpecificationIdentity;
    private final String contractsSpecificationIdentity;
    private final String managedDocumentPolicyLabel;
    private final String managedBindingPolicyLabel;
    private final String exactNodeProviderDomainLabel;
    private final String externalOrderPolicyLabel;
    private final String portableLimitPolicyLabel;
    private final Map<String, Long> portableLimits;
    private final long sharedGasLimit;
    private final String executionPolicyLabel;
    private final TreeSet<DocumentId> publicRoots;

    ContractsClosureProfile(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            String managedDocumentPolicyLabel,
            String managedBindingPolicyLabel,
            String exactNodeProviderDomainLabel,
            String externalOrderPolicyLabel,
            String portableLimitPolicyLabel,
            Map<String, Long> portableLimits,
            long sharedGasLimit,
            String executionPolicyLabel,
            Collection<DocumentId> publicRoots) {
        this.blueLanguageSpecificationIdentity = Objects.requireNonNull(
                blueLanguageSpecificationIdentity,
                "blueLanguageSpecificationIdentity");
        this.contractsSpecificationIdentity = Objects.requireNonNull(
                contractsSpecificationIdentity,
                "contractsSpecificationIdentity");
        this.managedDocumentPolicyLabel = requireText(
                managedDocumentPolicyLabel, "managedDocumentPolicyLabel");
        this.managedBindingPolicyLabel = requireText(
                managedBindingPolicyLabel, "managedBindingPolicyLabel");
        this.exactNodeProviderDomainLabel = requireText(
                exactNodeProviderDomainLabel, "exactNodeProviderDomainLabel");
        this.externalOrderPolicyLabel = requireText(
                externalOrderPolicyLabel, "externalOrderPolicyLabel");
        this.portableLimitPolicyLabel = requireText(
                portableLimitPolicyLabel, "portableLimitPolicyLabel");
        this.portableLimits = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        portableLimits, "portableLimits")));
        this.sharedGasLimit = sharedGasLimit;
        this.executionPolicyLabel = requireText(
                executionPolicyLabel, "executionPolicyLabel");
        TreeSet<DocumentId> roots = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Objects.requireNonNull(publicRoots, "publicRoots").forEach(root ->
                roots.add(Objects.requireNonNull(root, "publicRoot")));
        this.publicRoots = roots;
    }

    /** Constructs the release policy while keeping artifact digests explicit. */
    static ContractsClosureProfile release10(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            Collection<DocumentId> publicRoots) {
        return release10(
                blueLanguageSpecificationIdentity,
                contractsSpecificationIdentity,
                ContractsExecutionPolicy.releaseDefault(),
                publicRoots);
    }

    /** Constructs the release profile with one explicit host gas policy. */
    static ContractsClosureProfile release10(
            String blueLanguageSpecificationIdentity,
            String contractsSpecificationIdentity,
            ContractsExecutionPolicy executionPolicy,
            Collection<DocumentId> publicRoots) {
        ContractsExecutionPolicy selected = Objects.requireNonNull(
                executionPolicy, "executionPolicy");
        return new ContractsClosureProfile(
                blueLanguageSpecificationIdentity,
                contractsSpecificationIdentity,
                "nfc-document-lineage-v1",
                "exact-document-lineage",
                "coordination-whole-object-store-v1",
                "canonical-source-order-v1",
                "blue-contracts-1.0-portable-limits",
                release10PortableLimits(),
                selected.sharedGasLimit(),
                selected.label(),
                publicRoots);
    }

    ClosureEnvironment environment(DocumentProcessor processor) {
        return ClosureEvidenceFactory.environment(
                Objects.requireNonNull(processor, "processor"),
                blueLanguageSpecificationIdentity,
                contractsSpecificationIdentity,
                managedDocumentPolicyLabel,
                managedBindingPolicyLabel,
                exactNodeProviderDomainLabel,
                externalOrderPolicyLabel,
                portableLimitPolicyLabel,
                portableLimits);
    }

    ExecutionPolicy executionPolicy() {
        return ClosureEvidenceFactory.executionPolicy(
                sharedGasLimit,
                Map.of(),
                executionPolicyLabel);
    }

    boolean rootedCheckpoint() {
        return ROOTED_CONTRACTS_SPECIFICATION.equals(
                contractsSpecificationIdentity);
    }

    String rootedRuntimeSemanticsIdentity() {
        BundledContracts10Release.Manifest release = BundledContracts10Release.manifest();
        if (!rootedCheckpoint() || !contractsSpecificationIdentity.equals(release.contractsSpecification())) {
            throw new IllegalStateException("Rooted history requires the matching immutable runtime release binding");
        }
        return release.contractsRelease();
    }

    synchronized boolean isPublicRoot(DocumentId documentId) {
        return publicRoots.contains(Objects.requireNonNull(
                documentId, "documentId"));
    }

    synchronized Set<DocumentId> publicRoots() {
        return Collections.unmodifiableSet(new TreeSet<>(publicRoots));
    }

    synchronized void addPublicRoots(Collection<DocumentId> roots) {
        Objects.requireNonNull(roots, "roots").forEach(root ->
                publicRoots.add(Objects.requireNonNull(root, "publicRoot")));
    }

    private static Map<String, Long> release10PortableLimits() {
        return Map.ofEntries(
                Map.entry("closureExpansionsPerInvocation", 4_096L),
                Map.entry("closureGraphChangesPerInvocation", 4_096L),
                Map.entry("closureTentativeFinalizationsPerInvocation", 8_192L),
                Map.entry("closureWorkOccurrencesPerInvocation", 8_192L),
                Map.entry("contractKeyCodePoints", 256L),
                Map.entry("contractKeyUtf8Bytes", 1_024L),
                Map.entry("cyclicCanonicalBytesPerComponent", 16_777_216L),
                Map.entry("cyclicEdgesPerComponent", 1_024L),
                Map.entry("cyclicMembersPerComponent", 128L),
                Map.entry("directCanonicalIdentityInputBytes", 1_048_576L),
                Map.entry("directInlineIdentityTextCodePoints", 262_144L),
                Map.entry("directListItemsMaterializedOrRebuilt", 16_384L),
                Map.entry("directObjectEntriesMaterializedOrRebuilt", 16_384L),
                Map.entry("directObjectKeyCodePoints", 4_096L),
                Map.entry("effectiveContractsPerParticipatingScope", 8_192L),
                Map.entry("embeddedDepth", 256L),
                Map.entry("eventsPerContractExecutionResult", 1_024L),
                Map.entry("externalChannelsPerScope", 2_048L),
                Map.entry("handlersBoundToOneDelivery", 4_096L),
                Map.entry("internalEventOccurrencesPerInvocation", 8_192L),
                Map.entry("managedDocumentsPerClosure", 4_096L),
                Map.entry("nestedDocumentUpdateCascadeDepth", 256L),
                Map.entry("normalizedRuntimePointerUtf8Bytes", 4_096L),
                Map.entry("participatingScopesPerEvent", 4_096L),
                Map.entry("patchesPerContractExecutionResult", 1_024L),
                Map.entry("preselectedExternalOccurrencesPerEvent", 1_024L),
                Map.entry("processEmbeddedEdgesPerClosure", 16_384L),
                Map.entry("processEmbeddedPathsPerScope", 4_096L),
                Map.entry("rootEventsReturned", 4_096L),
                Map.entry("runtimeChildLedgerCounterKinds", 256L),
                Map.entry("runtimePointerSegments", 256L),
                Map.entry("subscriptionKeysPerChannel", 256L),
                Map.entry("typeChainEdges", 256L));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
