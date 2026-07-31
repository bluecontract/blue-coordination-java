package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeContentHandler;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import blue.repo.RepositoryDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Internal adapter that verifies authored fixed-Repository resources through
 * Language's bound source-content boundary.
 *
 * <p>The adapter never trusts a generated class, manifest key, or root
 * {@code blueId}. Plain definitions are admitted only by
 * {@link ProviderEvidenceVerifier}. Cyclic definition sets use the released
 * {@link NodeContentHandler} source-content path and retain a complete
 * {@link CyclicSetProof} for independent verification by Language. Every
 * lookup preserves the typed not-found, unavailable, and invalid-evidence
 * outcomes.</p>
 *
 * <p>This class is deliberately package-private. It is release evidence and a
 * runtime assembly primitive, not application storage API.</p>
 */
final class FixedRepositoryBoundSourceProvider
        implements NodeProvider, CyclicAwareNodeProvider {

    static final String PROFILE =
            "blue.coordination/fixed-repository-bound-source/1.0";
    private static final String RELEASE_REPOSITORY_BASE_COORDINATE =
            "blue.repo:blue-repo-java:3.0.0-rc.17";
    private static final String RELEASE_REPOSITORY_COMMIT =
            "63be6b7d8d2752b5a8c90f38e672859e9b3949a1";
    private static final String NO_HISTORICAL_ROLE_EVIDENCE_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb924"
                    + "27ae41e4649b934ca495991b7852b855";

    private static final Comparator<RepositoryDefinition>
            DEFINITION_ORDER =
            new Comparator<RepositoryDefinition>() {
                @Override
                public int compare(
                        RepositoryDefinition left,
                        RepositoryDefinition right) {
                    return left.blueId().compareTo(
                            right.blueId());
                }
            };
    private static final Comparator<RepositoryDefinition>
            CYCLIC_MEMBER_ORDER =
            new Comparator<RepositoryDefinition>() {
                @Override
                public int compare(
                        RepositoryDefinition left,
                        RepositoryDefinition right) {
                    return Integer.compare(
                            cyclicMemberIndex(
                                    left.blueId()),
                            cyclicMemberIndex(
                                    right.blueId()));
                }
            };

    private final BlueRepository repository;
    private final Blue verificationRuntime;
    private final ClassLoader classLoader;
    private final Binding binding;
    private final String providerDomainIdentity;
    private final Map<String, RepositoryDefinition> definitionByBlueId =
            new LinkedHashMap<String, RepositoryDefinition>();
    private final Map<String, List<RepositoryDefinition>>
            definitionsByMasterBlueId;
    private final Map<String, NodeProviderResult> resultByBlueId =
            new LinkedHashMap<String, NodeProviderResult>();
    private final Map<String, CyclicSetProofResult> proofByMasterBlueId =
            new LinkedHashMap<String, CyclicSetProofResult>();
    private volatile CatalogAudit audit;
    private String cachedMasterBlueId;

    FixedRepositoryBoundSourceProvider(
            BlueRepository repository,
            Blue verificationRuntime,
            ClassLoader classLoader,
            Binding binding) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.verificationRuntime = Objects.requireNonNull(
                verificationRuntime, "verificationRuntime");
        this.classLoader = classLoader != null
                ? classLoader
                : FixedRepositoryBoundSourceProvider.class
                .getClassLoader();
        this.binding = Objects.requireNonNull(
                binding, "binding");
        if (!repository.repositoryVersion().equals(
                binding.repositoryVersion())) {
            throw new IllegalArgumentException(
                    "Repository version does not match its evidence binding");
        }
        if (!repository.repositoryVersionBlueId().equals(
                binding.repositoryManifestBlueId())) {
            throw new IllegalArgumentException(
                    "Repository manifest identity does not match its "
                            + "evidence binding");
        }
        this.providerDomainIdentity =
                binding.providerDomainIdentity(
                        verificationRuntime);
        this.definitionsByMasterBlueId =
                indexCatalog();
    }

    CatalogAudit audit() {
        CatalogAudit snapshot =
                audit;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (audit == null) {
                audit =
                        inspectCatalog();
            }
            return audit;
        }
    }

    /**
     * Preserves Repository type resolution while replacing its direct
     * provider with this independently verified fixed-resource adapter.
     *
     * @param repository immutable fixed Repository inventory
     * @param runtime ordinary Language runtime to configure
     * @param classLoader loader of the bound Repository resources
     * @param binding exact release and artifact evidence
     * @return the installed adapter, including its on-demand audit
     */
    static FixedRepositoryBoundSourceProvider configure(
            BlueRepository repository,
            Blue runtime,
            ClassLoader classLoader,
            Binding binding) {
        repository.configure(runtime);
        FixedRepositoryBoundSourceProvider provider =
                new FixedRepositoryBoundSourceProvider(
                        repository,
                        runtime,
                        classLoader,
                        binding);
        runtime.nodeProvider(
                new VerifyingNodeProvider(
                        provider));
        return provider;
    }

    static FixedRepositoryBoundSourceProvider configureReleaseRuntime(
            BlueRepository repository,
            Blue runtime) {
        return configure(
                repository,
                runtime,
                BlueRepository.class
                        .getClassLoader(),
                releaseBinding(
                        repository));
    }

    static Binding releaseBinding(
            BlueRepository repository) {
        return new Binding(
                releaseRepositoryCoordinate(),
                repository.repositoryVersion(),
                repository.repositoryVersionBlueId(),
                RELEASE_REPOSITORY_COMMIT,
                loadedRepositoryArtifactSha256(),
                SourceProviderEnvironment
                        .LANGUAGE_1_0_RELEASE_IDENTITY,
                BlueCoreTypeRegistry.INSTANCE
                        .packageIdentity(),
                NO_HISTORICAL_ROLE_EVIDENCE_SHA256);
    }

    private static String releaseRepositoryCoordinate() {
        return RELEASE_REPOSITORY_BASE_COORDINATE
                + (System.getenv("CI") == null
                ? "-SNAPSHOT"
                : "");
    }

    String providerDomainIdentity() {
        return providerDomainIdentity;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        NodeProviderResult result =
                fetchResultByBlueId(blueId);
        if (result.outcome()
                == NodeProviderOutcome.FOUND) {
            return result.nodes();
        }
        return null;
    }

    @Override
    public synchronized NodeProviderResult fetchResultByBlueId(
            String blueId) {
        ensureLoaded(
                blueId);
        NodeProviderResult result =
                resultByBlueId.get(blueId);
        return result != null
                ? copy(result)
                : NodeProviderResult.notFound();
    }

    @Override
    public synchronized boolean hasVerifiedContentForBlueId(
            String blueId) {
        ensureLoaded(
                blueId);
        NodeProviderResult result =
                resultByBlueId.get(blueId);
        return result != null
                && result.outcome()
                == NodeProviderOutcome.FOUND;
    }

    @Override
    public synchronized CyclicSetProofResult cyclicSetProofFor(
            String blueId) {
        ensureLoaded(
                blueId);
        String master = masterBlueId(blueId);
        CyclicSetProofResult result =
                proofByMasterBlueId.get(master);
        return result != null
                ? result
                : CyclicSetProofResult.notFound();
    }

    private Map<String, List<RepositoryDefinition>>
    indexCatalog() {
        Map<String, List<RepositoryDefinition>> groups =
                new TreeMap<String, List<RepositoryDefinition>>();
        for (RepositoryDefinition definition
                : repository.manifest().definitions()) {
            definitionByBlueId.put(
                    definition.blueId(),
                    definition);
            String master =
                    masterBlueId(definition.blueId());
            List<RepositoryDefinition> members =
                    groups.get(master);
            if (members == null) {
                members =
                        new ArrayList<RepositoryDefinition>();
                groups.put(master, members);
            }
            members.add(definition);
        }
        Map<String, List<RepositoryDefinition>> indexed =
                new TreeMap<String, List<RepositoryDefinition>>();
        for (Map.Entry<String, List<RepositoryDefinition>> group
                : groups.entrySet()) {
            List<RepositoryDefinition> definitions =
                    group.getValue();
            Collections.sort(
                    definitions,
                    definitions.size() > 1
                            || definitions.get(0).blueId()
                            .indexOf('#') >= 0
                            ? CYCLIC_MEMBER_ORDER
                            : DEFINITION_ORDER);
            indexed.put(
                    group.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<RepositoryDefinition>(
                                    definitions)));
        }
        return Collections.unmodifiableMap(
                indexed);
    }

    private CatalogAudit inspectCatalog() {
        List<AuditEntry> entries =
                new ArrayList<AuditEntry>();
        int cyclicSetCount = 0;
        for (Map.Entry<String, List<RepositoryDefinition>> group
                : definitionsByMasterBlueId.entrySet()) {
            List<RepositoryDefinition> definitions =
                    group.getValue();
            boolean cyclic =
                    definitions.size() > 1
                            || definitions.get(0).blueId()
                            .indexOf('#') >= 0;
            if (cyclic) {
                cyclicSetCount++;
                entries.addAll(
                        inspectCyclicSet(
                                group.getKey(),
                                definitions,
                                false));
            } else {
                entries.add(
                        inspectPlainDefinition(
                                definitions.get(0),
                                false));
            }
        }
        Collections.sort(
                entries,
                AuditEntry.CANONICAL_ORDER);
        return new CatalogAudit(
                repository.repositoryVersion(),
                repository.repositoryVersionBlueId(),
                providerDomainIdentity,
                cyclicSetCount,
                entries);
    }

    private void ensureLoaded(
            String blueId) {
        RepositoryDefinition definition =
                definitionByBlueId.get(
                        blueId);
        if (definition == null) {
            return;
        }
        String master =
                masterBlueId(
                        definition.blueId());
        if (master.equals(
                cachedMasterBlueId)) {
            return;
        }
        resultByBlueId.clear();
        proofByMasterBlueId.clear();
        List<RepositoryDefinition> definitions =
                definitionsByMasterBlueId.get(
                        master);
        boolean cyclic =
                definitions.size() > 1
                        || definitions.get(0).blueId()
                        .indexOf('#') >= 0;
        if (cyclic) {
            inspectCyclicSet(
                    master,
                    definitions,
                    true);
        } else {
            inspectPlainDefinition(
                    definitions.get(0),
                    true);
        }
        cachedMasterBlueId =
                master;
    }

    private AuditEntry inspectPlainDefinition(
            RepositoryDefinition definition,
            boolean retainContent) {
        List<Node> source;
        try {
            source = readSource(
                    definition.resourcePath());
        } catch (RuntimeException unavailable) {
            NodeProviderResult result =
                    NodeProviderResult.unavailable(
                            unavailable.getMessage());
            retainResult(
                    retainContent,
                    definition.blueId(),
                    result);
            return AuditEntry.from(
                    definition,
                    result,
                    null,
                    false);
        }

        String environmentIdentity = null;
        NodeProviderResult result;
        try {
            SourceProviderEnvironment environment =
                    environment(
                            definition.blueId(),
                            source);
            environmentIdentity =
                    ProviderEvidenceVerifier
                            .sourceEnvironmentIdentity(
                                    environment);
            List<Node> verified;
            if (source.size() == 1) {
                verified =
                        Collections.singletonList(
                                ProviderEvidenceVerifier
                                        .verify(
                                                definition.blueId(),
                                                source.get(0),
                                                ProviderMode
                                                        .BOUND_SOURCE_CONTENT,
                                                verificationRuntime,
                                                environment));
            } else {
                verified =
                        ProviderEvidenceVerifier
                                .verifySourceContent(
                                        definition.blueId(),
                                        source,
                                        verificationRuntime,
                                        environment);
            }
            result =
                    NodeProviderResult.found(
                            verified);
        } catch (RuntimeException invalid) {
            result =
                    NodeProviderResult.invalidEvidence(
                            diagnostic(invalid));
        }
        retainResult(
                retainContent,
                definition.blueId(),
                result);
        return AuditEntry.from(
                definition,
                result,
                environmentIdentity,
                false);
    }

    private List<AuditEntry> inspectCyclicSet(
            String masterBlueId,
            List<RepositoryDefinition> definitions,
            boolean retainContent) {
        List<AuditEntry> entries =
                new ArrayList<AuditEntry>();
        List<Node> exactSource =
                new ArrayList<Node>();
        try {
            requireCompleteMemberOrder(
                    masterBlueId,
                    definitions);
            for (RepositoryDefinition definition
                    : definitions) {
                List<Node> member =
                        readSource(
                                definition.resourcePath());
                if (member.size() != 1) {
                    throw new IllegalArgumentException(
                            "Cyclic member resource must contain exactly "
                                    + "one authored node: "
                                    + definition.resourcePath());
                }
                exactSource.add(member.get(0));
            }
        } catch (RuntimeException unavailable) {
            CyclicSetProofResult proof =
                    CyclicSetProofResult.unavailable(
                            diagnostic(unavailable));
            retainProof(
                    retainContent,
                    masterBlueId,
                    proof);
            for (RepositoryDefinition definition
                    : definitions) {
                NodeProviderResult result =
                        NodeProviderResult.unavailable(
                                diagnostic(unavailable));
                retainResult(
                        retainContent,
                        definition.blueId(),
                        result);
                entries.add(AuditEntry.from(
                        definition,
                        result,
                        null,
                        true));
            }
            return entries;
        }

        String environmentIdentity = null;
        try {
            SourceProviderEnvironment environment =
                    environment(
                            masterBlueId,
                            exactSource);
            environmentIdentity =
                    ProviderEvidenceVerifier
                            .sourceEnvironmentIdentity(
                                    environment);
            /*
             * NodeContentHandler is Language's released source-content
             * equivalent for cyclic sets.  It preprocesses the exact source,
             * canonicalizes member order, retains authored placeholders, and
             * independently derives the master identity.
             */
            NodeContentHandler.ParsedContent parsed =
                    NodeContentHandler
                            .parseAndCalculateBlueId(
                                    exactSource,
                                    verificationRuntime::preprocess);
            if (!masterBlueId.equals(
                    parsed.blueId)) {
                throw new IllegalArgumentException(
                        "Bound cyclic source calculated master BlueId "
                                + parsed.blueId + " instead of "
                                + masterBlueId);
            }
            List<Node> canonicalPlaceholders =
                    nodes(parsed.content);
            List<String> calculatedMembers =
                    CircularBlueIdCalculator
                            .calculateCircularSetBlueIds(
                                    canonicalPlaceholders);
            List<String> expectedMembers =
                    new ArrayList<String>();
            for (RepositoryDefinition definition
                    : definitions) {
                expectedMembers.add(
                        definition.blueId());
            }
            if (!expectedMembers.equals(
                    calculatedMembers)) {
                throw new IllegalArgumentException(
                        "Bound cyclic source member identities differ "
                                + "from the fixed manifest: expected="
                                + expectedMembers + ", calculated="
                                + calculatedMembers);
            }
            CyclicSetProof proof =
                    CyclicSetProof
                            .fromDeclaredPlaceholderSet(
                                    canonicalPlaceholders);
            retainProof(
                    retainContent,
                    masterBlueId,
                    CyclicSetProofResult.found(proof));
            JsonNode resolved =
                    NodeContentHandler
                            .resolveThisReferences(
                                    parsed.content,
                                    masterBlueId,
                                    true);
            List<Node> resolvedMembers =
                    nodes(resolved);
            if (resolvedMembers.size()
                    != definitions.size()) {
                throw new IllegalArgumentException(
                        "Resolved cyclic source member count changed");
            }
            for (int index = 0;
                 index < definitions.size();
                 index++) {
                RepositoryDefinition definition =
                        definitions.get(index);
                NodeProviderResult result =
                        NodeProviderResult.found(
                                Collections.singletonList(
                                        resolvedMembers.get(index)));
                retainResult(
                        retainContent,
                        definition.blueId(),
                        result);
                entries.add(AuditEntry.from(
                        definition,
                        result,
                        environmentIdentity,
                        true));
            }
            return entries;
        } catch (RuntimeException invalid) {
            String failure =
                    diagnostic(invalid);
            retainProof(
                    retainContent,
                    masterBlueId,
                    CyclicSetProofResult
                            .invalidEvidence(
                                    failure));
            for (RepositoryDefinition definition
                    : definitions) {
                NodeProviderResult result =
                        NodeProviderResult
                                .invalidEvidence(
                                        failure);
                retainResult(
                        retainContent,
                        definition.blueId(),
                        result);
                entries.add(AuditEntry.from(
                        definition,
                        result,
                        environmentIdentity,
                        true));
            }
            return entries;
        }
    }

    private void retainResult(
            boolean retainContent,
            String blueId,
            NodeProviderResult result) {
        if (retainContent) {
            resultByBlueId.put(
                    blueId,
                    result);
        }
    }

    private void retainProof(
            boolean retainContent,
            String masterBlueId,
            CyclicSetProofResult result) {
        if (retainContent) {
            proofByMasterBlueId.put(
                    masterBlueId,
                    result);
        }
    }

    private SourceProviderEnvironment environment(
            String requestedBlueId,
            List<Node> exactSource) {
        String sourceEvidenceIdentity =
                sourceEvidenceIdentity(
                        requestedBlueId,
                        exactSource);
        return new SourceProviderEnvironment(
                verificationRuntime.languageVersion(),
                binding.languageReleaseIdentity(),
                ProviderEvidenceVerifier
                        .preprocessingEnvironmentIdentity(
                                verificationRuntime),
                BlueCoreTypeRegistry.INSTANCE
                        .packageIdentity(),
                providerDomainIdentity,
                ProviderMode.BOUND_SOURCE_CONTENT,
                SourceProviderEnvironment
                        .LANGUAGE_CONTENT_STRATEGY_IDENTITY,
                sourceEvidenceIdentity);
    }

    private static String sourceEvidenceIdentity(
            String requestedBlueId,
            List<Node> exactSource) {
        if (exactSource.size() != 1) {
            return ProviderEvidenceVerifier
                    .normalizedSourceEvidenceIdentity(
                            requestedBlueId,
                            exactSource);
        }
        Node importedSnapshot =
                exactSource.get(0).clone();
        if (importedSnapshot.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Bound source provider candidate is a pure reference "
                            + "and supplies no content evidence.");
        }
        String informationalRootBlueId =
                importedSnapshot.getBlueId();
        if (informationalRootBlueId != null) {
            if (!requestedBlueId.equals(
                    informationalRootBlueId)) {
                throw new IllegalArgumentException(
                        "Bound source provider candidate has root BlueId "
                                + informationalRootBlueId
                                + " instead of requested BlueId "
                                + requestedBlueId + ".");
            }
            importedSnapshot.blueId(null);
        }
        return ProviderEvidenceVerifier
                .sourceEvidenceIdentity(
                        importedSnapshot);
    }

    private List<Node> readSource(
            String resourcePath) {
        try (InputStream input =
                     classLoader
                             .getResourceAsStream(
                                     resourcePath)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Repository definition resource is unavailable: "
                                + resourcePath);
            }
            JsonNode value =
                    UncheckedObjectMapper.JSON_MAPPER
                            .readTree(input);
            return nodes(value);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Repository definition resource cannot be read: "
                            + resourcePath,
                    failure);
        }
    }

    private static List<Node> nodes(
            JsonNode value) {
        List<Node> nodes =
                new ArrayList<Node>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                nodes.add(
                        UncheckedObjectMapper
                                .JSON_MAPPER
                                .convertValue(
                                        item,
                                        Node.class));
            }
        } else {
            nodes.add(
                    UncheckedObjectMapper
                            .JSON_MAPPER
                            .convertValue(
                                    value,
                                    Node.class));
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Repository source content must not be empty");
        }
        return nodes;
    }

    private static void requireCompleteMemberOrder(
            String masterBlueId,
            List<RepositoryDefinition> definitions) {
        for (int index = 0;
             index < definitions.size();
             index++) {
            String expected =
                    masterBlueId + "#" + index;
            if (!expected.equals(
                    definitions.get(index).blueId())) {
                throw new IllegalArgumentException(
                        "Cyclic source inventory is incomplete at "
                                + expected);
            }
        }
    }

    private static String masterBlueId(
            String blueId) {
        int separator =
                blueId == null
                        ? -1
                        : blueId.indexOf('#');
        return separator < 0
                ? blueId
                : blueId.substring(0, separator);
    }

    private static int cyclicMemberIndex(
            String blueId) {
        int separator =
                blueId == null
                        ? -1
                        : blueId.indexOf('#');
        if (separator < 0
                || separator == blueId.length() - 1) {
            throw new IllegalArgumentException(
                    "Cyclic member identity has no numeric suffix: "
                            + blueId);
        }
        try {
            return Integer.parseInt(
                    blueId.substring(
                            separator + 1));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Cyclic member identity has a non-numeric suffix: "
                            + blueId,
                    invalid);
        }
    }

    private static NodeProviderResult copy(
            NodeProviderResult source) {
        switch (source.outcome()) {
            case FOUND:
                return NodeProviderResult.found(
                        source.nodes());
            case UNAVAILABLE:
                return NodeProviderResult.unavailable(
                        source.diagnostic()
                                .orElse(null));
            case INVALID_EVIDENCE:
                return NodeProviderResult.invalidEvidence(
                        source.diagnostic()
                                .orElse(null));
            case NOT_FOUND:
            default:
                return NodeProviderResult.notFound();
        }
    }

    private static String diagnostic(
            RuntimeException failure) {
        String message = failure.getMessage();
        return message == null
                || message.trim().isEmpty()
                ? failure.getClass().getName()
                : message;
    }

    static final class Binding {
        private final String repositoryCoordinate;
        private final String repositoryVersion;
        private final String repositoryManifestBlueId;
        private final String repositoryCommit;
        private final String repositoryArtifactSha256;
        private final String languageReleaseIdentity;
        private final String contractsRuntimeRegistryIdentity;
        private final String historicalRoleEvidenceSha256;

        Binding(
                String repositoryCoordinate,
                String repositoryVersion,
                String repositoryManifestBlueId,
                String repositoryCommit,
                String repositoryArtifactSha256,
                String languageReleaseIdentity,
                String contractsRuntimeRegistryIdentity,
                String historicalRoleEvidenceSha256) {
            this.repositoryCoordinate =
                    text(
                            repositoryCoordinate,
                            "repositoryCoordinate");
            this.repositoryVersion =
                    text(
                            repositoryVersion,
                            "repositoryVersion");
            this.repositoryManifestBlueId =
                    text(
                            repositoryManifestBlueId,
                            "repositoryManifestBlueId");
            this.repositoryCommit =
                    text(
                            repositoryCommit,
                            "repositoryCommit");
            this.repositoryArtifactSha256 =
                    text(
                            repositoryArtifactSha256,
                            "repositoryArtifactSha256");
            this.languageReleaseIdentity =
                    text(
                            languageReleaseIdentity,
                            "languageReleaseIdentity");
            this.contractsRuntimeRegistryIdentity =
                    text(
                            contractsRuntimeRegistryIdentity,
                            "contractsRuntimeRegistryIdentity");
            this.historicalRoleEvidenceSha256 =
                    text(
                            historicalRoleEvidenceSha256,
                            "historicalRoleEvidenceSha256");
        }

        String repositoryVersion() {
            return repositoryVersion;
        }

        String repositoryManifestBlueId() {
            return repositoryManifestBlueId;
        }

        String repositoryArtifactSha256() {
            return repositoryArtifactSha256;
        }

        String languageReleaseIdentity() {
            return languageReleaseIdentity;
        }

        String contractsRuntimeRegistryIdentity() {
            return contractsRuntimeRegistryIdentity;
        }

        Binding withRepositoryManifestBlueId(
                String replacement) {
            return new Binding(
                    repositoryCoordinate,
                    repositoryVersion,
                    replacement,
                    repositoryCommit,
                    repositoryArtifactSha256,
                    languageReleaseIdentity,
                    contractsRuntimeRegistryIdentity,
                    historicalRoleEvidenceSha256);
        }

        String providerDomainIdentity(
                Blue blue) {
            List<String> fields =
                    new ArrayList<String>();
            fields.add(PROFILE);
            fields.add(repositoryCoordinate);
            fields.add(repositoryVersion);
            fields.add(repositoryManifestBlueId);
            fields.add(repositoryCommit);
            fields.add(repositoryArtifactSha256);
            fields.add(languageReleaseIdentity);
            fields.add(contractsRuntimeRegistryIdentity);
            fields.add(historicalRoleEvidenceSha256);
            fields.add(blue.languageVersion());
            fields.add(
                    ProviderEvidenceVerifier
                            .preprocessingEnvironmentIdentity(
                                    blue));
            fields.add(
                    BlueCoreTypeRegistry.INSTANCE
                            .packageIdentity());
            return "sha256:" + sha256(fields);
        }

        private static String text(
                String value,
                String field) {
            Objects.requireNonNull(value, field);
            if (value.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        field + " must not be blank");
            }
            return value;
        }
    }

    static final class CatalogAudit {
        private final String repositoryVersion;
        private final String repositoryManifestBlueId;
        private final String providerDomainIdentity;
        private final int cyclicSetCount;
        private final List<AuditEntry> entries;

        private CatalogAudit(
                String repositoryVersion,
                String repositoryManifestBlueId,
                String providerDomainIdentity,
                int cyclicSetCount,
                List<AuditEntry> entries) {
            this.repositoryVersion =
                    repositoryVersion;
            this.repositoryManifestBlueId =
                    repositoryManifestBlueId;
            this.providerDomainIdentity =
                    providerDomainIdentity;
            this.cyclicSetCount =
                    cyclicSetCount;
            this.entries =
                    Collections.unmodifiableList(
                            new ArrayList<AuditEntry>(
                                    entries));
        }

        String repositoryVersion() {
            return repositoryVersion;
        }

        String repositoryManifestBlueId() {
            return repositoryManifestBlueId;
        }

        String providerDomainIdentity() {
            return providerDomainIdentity;
        }

        int cyclicSetCount() {
            return cyclicSetCount;
        }

        List<AuditEntry> entries() {
            return entries;
        }

        int total() {
            return entries.size();
        }

        int verified() {
            int count = 0;
            for (AuditEntry entry : entries) {
                if (entry.outcome()
                        == NodeProviderOutcome.FOUND) {
                    count++;
                }
            }
            return count;
        }

        int failed() {
            return total() - verified();
        }
    }

    static final class AuditEntry {
        private static final Comparator<AuditEntry>
                CANONICAL_ORDER =
                new Comparator<AuditEntry>() {
                    @Override
                    public int compare(
                            AuditEntry left,
                            AuditEntry right) {
                        return left.blueId.compareTo(
                                right.blueId);
                    }
                };

        private final String qualifiedName;
        private final String blueId;
        private final String resourcePath;
        private final NodeProviderOutcome outcome;
        private final String diagnostic;
        private final String sourceEnvironmentIdentity;
        private final boolean cyclicMember;

        private AuditEntry(
                String qualifiedName,
                String blueId,
                String resourcePath,
                NodeProviderOutcome outcome,
                String diagnostic,
                String sourceEnvironmentIdentity,
                boolean cyclicMember) {
            this.qualifiedName =
                    qualifiedName;
            this.blueId = blueId;
            this.resourcePath =
                    resourcePath;
            this.outcome = outcome;
            this.diagnostic =
                    diagnostic;
            this.sourceEnvironmentIdentity =
                    sourceEnvironmentIdentity;
            this.cyclicMember =
                    cyclicMember;
        }

        static AuditEntry from(
                RepositoryDefinition definition,
                NodeProviderResult result,
                String sourceEnvironmentIdentity,
                boolean cyclicMember) {
            return new AuditEntry(
                    definition.qualifiedName(),
                    definition.blueId(),
                    definition.resourcePath(),
                    result.outcome(),
                    result.diagnostic()
                            .orElse(null),
                    sourceEnvironmentIdentity,
                    cyclicMember);
        }

        String qualifiedName() {
            return qualifiedName;
        }

        String blueId() {
            return blueId;
        }

        String resourcePath() {
            return resourcePath;
        }

        NodeProviderOutcome outcome() {
            return outcome;
        }

        String diagnostic() {
            return diagnostic;
        }

        String sourceEnvironmentIdentity() {
            return sourceEnvironmentIdentity;
        }

        boolean cyclicMember() {
            return cyclicMember;
        }
    }

    private static String loadedRepositoryArtifactSha256() {
        String declared =
                System.getProperty(
                        "coordination.fixed.repository.artifact.sha256");
        String exactDeclared =
                declared == null
                        || declared.trim().isEmpty()
                        ? null
                        : requireSha256(
                                declared.trim());
        if (BlueRepository.class
                .getProtectionDomain()
                .getCodeSource() == null) {
            if (exactDeclared != null) {
                return exactDeclared;
            }
            throw repositoryArtifactBindingRequired(
                    "no code source");
        }
        URL location =
                BlueRepository.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation();
        final Path artifact;
        try {
            artifact =
                    Paths.get(
                            location.toURI());
        } catch (URISyntaxException invalid) {
            throw new IllegalStateException(
                    "Fixed Repository artifact location is invalid; "
                            + "set coordination.fixed.repository.artifact.sha256 "
                            + "to the exact same-run artifact digest.",
                    invalid);
        }
        if (!Files.isRegularFile(artifact)
                || !artifact.getFileName()
                .toString()
                .endsWith(".jar")) {
            if (exactDeclared != null) {
                return exactDeclared;
            }
            throw repositoryArtifactBindingRequired(
                    location.toString());
        }
        String observed = sha256(artifact);
        if (exactDeclared != null
                && !exactDeclared.equals(observed)) {
            throw new IllegalStateException(
                    "Declared fixed Repository artifact SHA-256 "
                            + exactDeclared
                            + " differs from the loaded JAR digest "
                            + observed);
        }
        return observed;
    }

    private static String requireSha256(
            String value) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "coordination.fixed.repository.artifact.sha256 "
                            + "must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    private static IllegalStateException
    repositoryArtifactBindingRequired(
            String observedLocation) {
        return new IllegalStateException(
                "Fixed Repository classes were not loaded from a JAR; "
                        + "set coordination.fixed.repository.artifact.sha256 "
                        + "to the exact same-run artifact digest. "
                        + "Observed location: " + observedLocation);
    }

    private static String sha256(
            Path artifact) {
        final MessageDigest digest;
        try {
            digest =
                    MessageDigest.getInstance(
                            "SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    impossible);
        }
        byte[] buffer =
                new byte[8192];
        try (InputStream input =
                     Files.newInputStream(
                             artifact)) {
            int count;
            while ((count = input.read(buffer))
                    >= 0) {
                digest.update(
                        buffer,
                        0,
                        count);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Fixed Repository artifact cannot be hashed: "
                            + artifact,
                    failure);
        }
        return hexadecimal(
                digest.digest());
    }

    private static String sha256(
            List<String> fields) {
        try {
            MessageDigest digest =
                    MessageDigest
                            .getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes =
                        field.getBytes(
                                StandardCharsets.UTF_8);
                digest.update(
                        Integer.toString(
                                bytes.length)
                                .getBytes(
                                        StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return hexadecimal(
                    digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    impossible);
        }
    }

    private static String hexadecimal(
            byte[] bytes) {
        StringBuilder value =
                new StringBuilder();
        for (byte item : bytes) {
            value.append(
                    String.format(
                            java.util.Locale.ROOT,
                            "%02x",
                            item & 0xff));
        }
        return value.toString();
    }
}
