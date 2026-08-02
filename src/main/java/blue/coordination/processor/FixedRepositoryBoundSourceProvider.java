package blue.coordination.processor;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
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
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import blue.repo.RepositoryDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        implements NodeProvider, CyclicAwareNodeProvider, AutoCloseable {

    static final String PROFILE =
            "blue.coordination/fixed-repository-bound-source/1.0";
    private static final String RELEASE_REPOSITORY_BASE_COORDINATE =
            "blue.repo:blue-repo-java:3.0.0-rc.17";
    private static final String CURRENT_SOURCE_STRATEGY =
            "blue-language-1.0/current-bound-source-content";
    private static final String IMMUTABLE_CLOSURE_BINDING_STRATEGY =
            "blue-repository/exact-immutable-head-closure-binding";

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
    private final boolean ownsVerificationRuntime;
    private final HistoricalSourceEvidenceProvider
            historicalEvidenceProvider;
    private final ClassLoader classLoader;
    private final Binding binding;
    private final String providerDomainIdentity;
    private final Map<String, RepositoryDefinition> definitionByBlueId =
            new LinkedHashMap<String, RepositoryDefinition>();
    private final Map<String, List<RepositoryDefinition>>
            definitionsByMasterBlueId;
    private final Map<String, NodeProviderResult> resultByBlueId =
            new LinkedHashMap<String, NodeProviderResult>();
    private final Map<String, AuditEntry> auditEntryByBlueId =
            new LinkedHashMap<String, AuditEntry>();
    private final Map<String, CyclicSetProofResult> proofByMasterBlueId =
            new LinkedHashMap<String, CyclicSetProofResult>();
    private final Set<String> loadedMasterBlueIds =
            new LinkedHashSet<String>();
    private final Set<String> loadingMasterBlueIds =
            new LinkedHashSet<String>();
    private volatile CatalogAudit audit;
    private volatile RequiredClosureAudit requiredClosureAudit;

    FixedRepositoryBoundSourceProvider(
            BlueRepository repository,
            Blue verificationRuntime,
            ClassLoader classLoader,
            Binding binding) {
        this(
                repository,
                verificationRuntime,
                classLoader,
                binding,
                false,
                null);
    }

    private FixedRepositoryBoundSourceProvider(
            BlueRepository repository,
            Blue verificationRuntime,
            ClassLoader classLoader,
            Binding binding,
            boolean ownsVerificationRuntime,
            HistoricalSourceEvidenceProvider
                    historicalEvidenceProvider) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.verificationRuntime = Objects.requireNonNull(
                verificationRuntime, "verificationRuntime");
        this.ownsVerificationRuntime =
                ownsVerificationRuntime;
        this.historicalEvidenceProvider =
                historicalEvidenceProvider;
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
                Map<String, NodeProviderResult> retainedResults =
                        new LinkedHashMap<String, NodeProviderResult>(
                                resultByBlueId);
                Map<String, AuditEntry> retainedEntries =
                        new LinkedHashMap<String, AuditEntry>(
                                auditEntryByBlueId);
                Map<String, CyclicSetProofResult> retainedProofs =
                        new LinkedHashMap<String, CyclicSetProofResult>(
                                proofByMasterBlueId);
                Set<String> retainedLoadedMasters =
                        new LinkedHashSet<String>(
                                loadedMasterBlueIds);
                Set<String> retainedLoadingMasters =
                        new LinkedHashSet<String>(
                                loadingMasterBlueIds);
                clearRetainedVerification();
                try {
                    audit =
                            inspectCatalog();
                } finally {
                    clearRetainedVerification();
                    resultByBlueId.putAll(
                            retainedResults);
                    auditEntryByBlueId.putAll(
                            retainedEntries);
                    proofByMasterBlueId.putAll(
                            retainedProofs);
                    loadedMasterBlueIds.addAll(
                            retainedLoadedMasters);
                    loadingMasterBlueIds.addAll(
                            retainedLoadingMasters);
                }
            }
            return audit;
        }
    }

    RequiredClosureAudit requiredClosureAudit() {
        RequiredClosureAudit snapshot =
                requiredClosureAudit;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (requiredClosureAudit == null) {
                requiredClosureAudit =
                        inspectRequiredClosure();
            }
            return requiredClosureAudit;
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
        Objects.requireNonNull(
                runtime, "runtime");
        FixedRepositoryBoundSourceProvider provider =
                inspect(
                        repository,
                        classLoader,
                        binding);
        RequiredClosureAudit requiredClosure =
                provider.requiredClosureAudit();
        if (!requiredClosure.eligible()) {
            provider.close();
            throw new IllegalStateException(
                    requiredClosureFailure(
                            requiredClosure));
        }
        runtime.typeClassResolver(
                repository.typeClassResolver());
        runtime.nodeProvider(
                new VerifyingNodeProvider(
                        provider));
        return provider;
    }

    static FixedRepositoryBoundSourceProvider inspect(
            BlueRepository repository,
            ClassLoader classLoader,
            Binding binding) {
        Blue verificationRuntime =
                Blue.withCachePolicy(
                        BlueCachePolicy.disabled());
        verificationRuntime.preprocessingAliases(
                CoordinationRequiredRepositoryClosure
                        .historicalPreprocessingAliases());
        try {
            HistoricalSourceEvidenceProvider historicalEvidence =
                    new HistoricalSourceEvidenceProvider(
                            verificationRuntime);
            FixedRepositoryBoundSourceProvider provider =
                    new FixedRepositoryBoundSourceProvider(
                            repository,
                            verificationRuntime,
                            classLoader,
                            binding,
                            true,
                            historicalEvidence);
            verificationRuntime.nodeProvider(
                    new SequentialNodeProvider(
                            provider,
                            historicalEvidence));
            historicalEvidence.verifyEveryEntry();
            return provider;
        } catch (RuntimeException failure) {
            verificationRuntime.close();
            throw failure;
        }
    }

    private static String requiredClosureFailure(
            RequiredClosureAudit audit) {
        StringBuilder diagnostic =
                new StringBuilder(
                        "Required immutable Repository closure did not "
                                + "verify: verified=")
                        .append(
                                audit.verified())
                        .append("/")
                        .append(
                                audit.total())
                        .append(", missing=")
                        .append(
                                audit.missing())
                        .append(", invalidEvidence=")
                        .append(
                                audit.invalidEvidence())
                        .append(", unavailable=")
                        .append(
                                audit.unavailable())
                        .append(", incompleteCyclicProof=")
                        .append(
                                audit.incompleteCyclicProof());
        if (!audit.incompatibilityProofs()
                .isEmpty()) {
            IncompatibilityProof first =
                    audit.incompatibilityProofs()
                            .get(0);
            diagnostic.append("; first=")
                    .append(
                            first.qualifiedName())
                    .append(" [")
                    .append(
                            first.publishedBlueId())
                    .append("] source=")
                    .append(
                            first.sourceResourceSha256())
                    .append(" environment=")
                    .append(
                            first.exactEnvironmentAttempted())
                    .append(" calculated=")
                    .append(
                            first.calculatedIdentity())
                    .append(" path=")
                    .append(
                            first.earliestFailingPath())
                    .append(" diagnostic=")
                    .append(
                            first.diagnostic());
        }
        return diagnostic.toString();
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
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_HEAD_COMMIT,
                loadedRepositoryArtifactSha256(),
                SourceProviderEnvironment
                        .LANGUAGE_1_0_RELEASE_IDENTITY,
                BlueRuntimeTypeRegistry
                        .getDefault()
                        .registryIdentity(),
                CoordinationRequiredRepositoryClosure
                        .HISTORICAL_ENVIRONMENT_IDENTITY);
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

    int verifiedHistoricalEvidenceCount() {
        return historicalEvidenceProvider == null
                ? 0
                : historicalEvidenceProvider
                .verifiedEntryCount();
    }

    int inspectedHistoricalEvidenceCount() {
        return historicalEvidenceProvider == null
                ? 0
                : historicalEvidenceProvider
                .inspectedEntryCount();
    }

    int invalidHistoricalEvidenceCount() {
        return historicalEvidenceProvider == null
                ? 0
                : historicalEvidenceProvider
                .invalidEntryCount();
    }

    String verifiedHistoricalEvidenceIdentity() {
        return historicalEvidenceProvider == null
                ? null
                : historicalEvidenceProvider
                .verifiedEvidenceIdentity();
    }

    boolean verificationRuntimeClosed() {
        return verificationRuntime.isClosed();
    }

    @Override
    public void close() {
        if (ownsVerificationRuntime) {
            verificationRuntime.close();
        }
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

    private RequiredClosureAudit inspectRequiredClosure() {
        String repositoryReleaseMismatch =
                null;
        if (!repository.repositoryVersion().equals(
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_VERSION)
                || !repository.repositoryVersionBlueId().equals(
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_MANIFEST_BLUE_ID)) {
            repositoryReleaseMismatch =
                    "Loaded Repository release "
                            + repository.repositoryVersion()
                            + " ["
                            + repository.repositoryVersionBlueId()
                            + "] differs from exact immutable HEAD closure "
                            + CoordinationRequiredRepositoryClosure
                            .REPOSITORY_VERSION
                            + " ["
                            + CoordinationRequiredRepositoryClosure
                            .REPOSITORY_MANIFEST_BLUE_ID
                            + "]";
        }
        if (repositoryReleaseMismatch != null) {
            return RequiredClosureAudit
                    .selectedReleaseMismatch(
                            CoordinationRequiredRepositoryClosure
                                    .CLOSURE_IDENTITY,
                            CoordinationRequiredRepositoryClosure
                                    .HISTORICAL_ENVIRONMENT_IDENTITY,
                            CoordinationRequiredRepositoryClosure
                                    .entries()
                                    .size(),
                            repositoryReleaseMismatch);
        }
        VerifyingNodeProvider independentVerifier =
                new VerifyingNodeProvider(
                        this);
        List<AuditEntry> entries =
                new ArrayList<AuditEntry>();
        Set<String> cyclicMasters =
                new LinkedHashSet<String>();
        Set<String> incompleteCyclicMasters =
                new LinkedHashSet<String>();
        for (CoordinationRequiredRepositoryClosure.Entry required
                : CoordinationRequiredRepositoryClosure.entries()) {
            RepositoryDefinition definition =
                    definitionByBlueId.get(
                            required.blueId());
            if (!sameDefinition(
                    required,
                    definition)) {
                entries.add(
                        definition == null
                                ? AuditEntry.missing(
                                required,
                                "Required definition is absent from "
                                        + "the loaded manifest")
                                : AuditEntry.invalidBinding(
                                required,
                                "Loaded definition metadata or source "
                                        + "resource SHA-256 differs from "
                                        + "the exact immutable HEAD "
                                        + "closure"));
                continue;
            }
            NodeProviderResult verified =
                    independentVerifier
                            .fetchResultByBlueId(
                                    required.blueId());
            AuditEntry retained =
                    auditEntryByBlueId.get(
                            required.blueId());
            AuditEntry entry =
                    retained == null
                            ? AuditEntry.from(
                            definition,
                            verified,
                            null,
                            required.cyclicMember(),
                            null,
                            null,
                            null,
                            earliestFailingPath(
                                    verified.diagnostic()
                                            .orElse(null)))
                            : retained.withResult(
                                    verified);
            entries.add(
                    entry);

            if (required.cyclicMember()
                    || required.blueId().indexOf('#') >= 0) {
                String master =
                        masterBlueId(
                                required.blueId());
                cyclicMasters.add(
                        master);
                List<RepositoryDefinition> completeSet =
                        definitionsByMasterBlueId.get(
                                master);
                if (completeSet == null
                        || !requiredClosureContainsAll(
                        completeSet)
                        || cyclicSetProofFor(
                        required.blueId()).outcome()
                        != NodeProviderOutcome.FOUND) {
                    incompleteCyclicMasters.add(
                            master);
                }
            }
        }
        Collections.sort(
                entries,
                AuditEntry.CANONICAL_ORDER);
        return new RequiredClosureAudit(
                CoordinationRequiredRepositoryClosure
                        .CLOSURE_IDENTITY,
                CoordinationRequiredRepositoryClosure
                        .HISTORICAL_ENVIRONMENT_IDENTITY,
                CoordinationRequiredRepositoryClosure
                        .entries()
                        .size(),
                cyclicMasters.size(),
                incompleteCyclicMasters.size(),
                entries,
                null);
    }

    private boolean sameDefinition(
            CoordinationRequiredRepositoryClosure.Entry required,
            RepositoryDefinition definition) {
        try {
            return definition != null
                    && required.qualifiedName().equals(
                    definition.qualifiedName())
                    && required.blueId().equals(
                    definition.blueId())
                    && required.resourcePath().equals(
                    definition.resourcePath())
                    && required.sourceResourceSha256().equals(
                    sourceResourceSha256(
                            definition.resourcePath()));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean requiredClosureContainsAll(
            List<RepositoryDefinition> definitions) {
        for (RepositoryDefinition definition
                : definitions) {
            if (!CoordinationRequiredRepositoryClosure
                    .containsBlueId(
                            definition.blueId())) {
                return false;
            }
        }
        return true;
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
        if (loadedMasterBlueIds.contains(master)
                || loadingMasterBlueIds.contains(master)) {
            return;
        }
        List<RepositoryDefinition> definitions =
                definitionsByMasterBlueId.get(
                        master);
        loadingMasterBlueIds.add(master);
        try {
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
            loadedMasterBlueIds.add(master);
        } finally {
            loadingMasterBlueIds.remove(master);
        }
    }

    private AuditEntry inspectPlainDefinition(
            RepositoryDefinition definition,
            boolean retainContent) {
        List<Node> source;
        String sourceResourceSha256;
        try {
            source = readSource(
                    definition.resourcePath());
            sourceResourceSha256 =
                    sourceResourceSha256(
                            definition.resourcePath());
        } catch (RuntimeException unavailable) {
            NodeProviderResult result =
                    NodeProviderResult.unavailable(
                            diagnostic(
                                    unavailable));
            AuditEntry entry =
                    AuditEntry.from(
                            definition,
                            result,
                            null,
                            false,
                            null,
                            null,
                            null,
                            "$");
            retainResult(
                    retainContent,
                    definition.blueId(),
                    result);
            retainAuditEntry(
                    retainContent,
                    entry);
            return entry;
        }

        String environmentIdentity = null;
        String verificationStrategy =
                CURRENT_SOURCE_STRATEGY;
        String calculatedIdentity = null;
        String earliestFailingPath = null;
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
            calculatedIdentity =
                    definition.blueId();
        } catch (RuntimeException invalid) {
            result =
                    NodeProviderResult.invalidEvidence(
                            diagnostic(invalid));
            calculatedIdentity =
                    calculatedIdentity(
                            definition.blueId(),
                            source);
            earliestFailingPath =
                    earliestFailingPath(
                            diagnostic(
                                    invalid));
        }
        AuditEntry entry =
                AuditEntry.from(
                        definition,
                        result,
                        environmentIdentity,
                        false,
                        sourceResourceSha256,
                        verificationStrategy,
                        calculatedIdentity,
                        earliestFailingPath);
        retainResult(
                retainContent,
                definition.blueId(),
                result);
        retainAuditEntry(
                retainContent,
                entry);
        return entry;
    }

    private List<AuditEntry> inspectCyclicSet(
            String masterBlueId,
            List<RepositoryDefinition> definitions,
            boolean retainContent) {
        List<AuditEntry> entries =
                new ArrayList<AuditEntry>();
        List<Node> exactSource =
                new ArrayList<Node>();
        Map<String, String> sourceResourceSha256ByBlueId =
                new LinkedHashMap<String, String>();
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
                sourceResourceSha256ByBlueId.put(
                        definition.blueId(),
                        sourceResourceSha256(
                                definition.resourcePath()));
            }
        } catch (RuntimeException unavailable) {
            String failure =
                    diagnostic(
                            unavailable);
            CyclicSetProofResult proof =
                    CyclicSetProofResult.unavailable(
                            failure);
            retainProof(
                    retainContent,
                    masterBlueId,
                    proof);
            for (RepositoryDefinition definition
                    : definitions) {
                NodeProviderResult result =
                        NodeProviderResult.unavailable(
                                failure);
                AuditEntry entry =
                        AuditEntry.from(
                                definition,
                                result,
                                null,
                                true,
                                sourceResourceSha256ByBlueId.get(
                                        definition.blueId()),
                                null,
                                null,
                                "$");
                retainResult(
                        retainContent,
                        definition.blueId(),
                        result);
                retainAuditEntry(
                        retainContent,
                        entry);
                entries.add(
                        entry);
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
                AuditEntry entry =
                        AuditEntry.from(
                                definition,
                                result,
                                environmentIdentity,
                                true,
                                sourceResourceSha256ByBlueId.get(
                                        definition.blueId()),
                                CURRENT_SOURCE_STRATEGY,
                                definition.blueId(),
                                null);
                retainResult(
                        retainContent,
                        definition.blueId(),
                        result);
                retainAuditEntry(
                        retainContent,
                        entry);
                entries.add(
                        entry);
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
                AuditEntry entry =
                        AuditEntry.from(
                                definition,
                                result,
                                environmentIdentity,
                                true,
                                sourceResourceSha256ByBlueId.get(
                                        definition.blueId()),
                                CURRENT_SOURCE_STRATEGY,
                                calculatedIdentity(
                                        masterBlueId,
                                        exactSource),
                                earliestFailingPath(
                                        failure));
                retainResult(
                        retainContent,
                        definition.blueId(),
                        result);
                retainAuditEntry(
                        retainContent,
                        entry);
                entries.add(
                        entry);
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

    private void retainAuditEntry(
            boolean retainContent,
            AuditEntry entry) {
        if (retainContent) {
            auditEntryByBlueId.put(
                    entry.blueId(),
                    entry);
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

    private void clearRetainedVerification() {
        resultByBlueId.clear();
        auditEntryByBlueId.clear();
        proofByMasterBlueId.clear();
        loadedMasterBlueIds.clear();
        loadingMasterBlueIds.clear();
    }

    private static List<Node> exactContentWithoutRootIdentity(
            String requestedBlueId,
            List<Node> exactSource) {
        List<Node> canonical =
                new ArrayList<Node>();
        for (Node source : exactSource) {
            Node item =
                    source.clone();
            if (item.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Exact extracted Repository source is a pure "
                                + "reference and supplies no content "
                                + "evidence for " + requestedBlueId);
            }
            String rootBlueId =
                    item.getBlueId();
            if (rootBlueId != null) {
                if (!requestedBlueId.equals(
                        rootBlueId)) {
                    throw new IllegalArgumentException(
                            "Exact extracted Repository source has root "
                                    + "BlueId " + rootBlueId
                                    + " instead of requested BlueId "
                                    + requestedBlueId);
                }
                item.blueId(
                        null);
            }
            canonical.add(
                    item);
        }
        return canonical;
    }

    private static String calculateIdentity(
            List<Node> source) {
        return source.size() == 1
                ? BlueIdCalculator.calculateBlueId(
                source.get(0))
                : BlueIdCalculator.calculateBlueId(
                source);
    }

    private static String calculatedIdentity(
            String requestedBlueId,
            List<Node> source) {
        try {
            return calculateIdentity(
                    exactContentWithoutRootIdentity(
                            requestedBlueId,
                            source));
        } catch (RuntimeException invalid) {
            return null;
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

    private String sourceResourceSha256(
            String resourcePath) {
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
                     classLoader
                             .getResourceAsStream(
                                     resourcePath)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Repository definition resource is unavailable: "
                                + resourcePath);
            }
            int count;
            while ((count = input.read(
                    buffer)) >= 0) {
                digest.update(
                        buffer,
                        0,
                        count);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Repository definition resource cannot be hashed: "
                            + resourcePath,
                    failure);
        }
        return hexadecimal(
                digest.digest());
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

    private static String earliestFailingPath(
            String diagnostic) {
        if (diagnostic == null
                || diagnostic.trim().isEmpty()) {
            return null;
        }
        int marker =
                diagnostic.indexOf(
                        " at path ");
        int start =
                marker < 0
                        ? diagnostic.indexOf('/')
                        : marker + " at path ".length();
        if (start < 0) {
            return "$";
        }
        int end =
                start;
        while (end < diagnostic.length()) {
            char current =
                    diagnostic.charAt(
                            end);
            if (Character.isWhitespace(
                    current)
                    || current == ','
                    || current == ';'
                    || current == ']'
                    || current == ')') {
                break;
            }
            end++;
        }
        String path =
                diagnostic.substring(
                        start,
                        end);
        return path.isEmpty()
                ? "$"
                : path;
    }

    /**
     * Verification-only provider for the exact Language tag that authored the
     * immutable Repository snapshot.
     *
     * <p>The provider is installed only on the private verification runtime.
     * Every generated source byte sequence is digest-checked, its authored
     * type aliases are normalized with the exact generated historical map,
     * and it is admitted only after {@link ProviderEvidenceVerifier}
     * independently derives its declared BlueId. It is never installed on the
     * caller's active processing runtime.</p>
     */
    private static final class HistoricalSourceEvidenceProvider
            implements NodeProvider {
        private final Blue verificationRuntime;
        private final String providerDomainIdentity;
        private final Map<String,
                CoordinationRequiredRepositoryClosure
                        .HistoricalEvidenceEntry> entryByBlueId =
                new LinkedHashMap<String,
                        CoordinationRequiredRepositoryClosure
                                .HistoricalEvidenceEntry>();
        private final Map<String, NodeProviderResult> resultByBlueId =
                new LinkedHashMap<String, NodeProviderResult>();
        private final Set<String> loadingBlueIds =
                new LinkedHashSet<String>();
        private boolean everyEntryInspected;

        private HistoricalSourceEvidenceProvider(
                Blue verificationRuntime) {
            this.verificationRuntime =
                    Objects.requireNonNull(
                            verificationRuntime,
                            "verificationRuntime");
            verifyHistoricalTransformReplay();
            List<String> evidenceIdentityFields =
                    new ArrayList<String>();
            for (CoordinationRequiredRepositoryClosure
                    .HistoricalEvidenceEntry entry
                    : CoordinationRequiredRepositoryClosure
                    .historicalEvidenceEntries()) {
                CoordinationRequiredRepositoryClosure
                        .HistoricalEvidenceEntry previous =
                        entryByBlueId.put(
                                entry.blueId(),
                                entry);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Historical registry evidence declares duplicate "
                                    + "BlueId " + entry.blueId());
                }
                String exactAliasIdentity =
                        CoordinationRequiredRepositoryClosure
                                .historicalPreprocessingAliases()
                                .get(
                                        entry.alias());
                if (!entry.blueId()
                        .equals(
                                exactAliasIdentity)) {
                    throw new IllegalStateException(
                            "Historical registry alias "
                                    + entry.alias()
                                    + " does not map to "
                                    + entry.blueId()
                                    + " in exact Default Blue evidence");
                }
                byte[] sourceBytes =
                        entry.sourceBytes();
                String observedSha256 =
                        sha256Bytes(
                                sourceBytes);
                if (!entry.sourceResourceSha256()
                        .equals(
                                observedSha256)) {
                    throw new IllegalStateException(
                            "Historical registry evidence digest mismatch "
                                    + "for " + entry.path()
                                    + ": expected "
                                    + entry.sourceResourceSha256()
                                    + ", observed "
                                    + observedSha256);
                }
                evidenceIdentityFields.add(
                        entry.registry());
                evidenceIdentityFields.add(
                        entry.key());
                evidenceIdentityFields.add(
                        entry.alias());
                evidenceIdentityFields.add(
                        entry.blueId());
                evidenceIdentityFields.add(
                        entry.path());
                evidenceIdentityFields.add(
                        entry.sourceResourceSha256());
            }
            int declaredCount;
            try {
                declaredCount =
                        Integer.parseInt(
                                CoordinationRequiredRepositoryClosure
                                        .HISTORICAL_REGISTRY_EVIDENCE_COUNT);
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException(
                        "Historical registry evidence count is invalid",
                        invalid);
            }
            if (declaredCount
                    != entryByBlueId.size()) {
                throw new IllegalStateException(
                        "Historical registry evidence count mismatch: "
                                + declaredCount + " declared, "
                                + entryByBlueId.size() + " embedded");
            }
            String calculatedEvidenceIdentity =
                    "sha256:" + sha256(
                            evidenceIdentityFields);
            if (!CoordinationRequiredRepositoryClosure
                    .HISTORICAL_REGISTRY_EVIDENCE_IDENTITY
                    .equals(
                            calculatedEvidenceIdentity)) {
                throw new IllegalStateException(
                        "Historical registry evidence identity mismatch: "
                                + calculatedEvidenceIdentity);
            }
            List<String> providerDomainFields =
                    new ArrayList<String>();
            Binding.addBoundField(
                    providerDomainFields,
                    "profile",
                    "blue.coordination/"
                            + "historical-source-evidence/1.0");
            Binding.addBoundField(
                    providerDomainFields,
                    "languageTagCommit",
                    CoordinationRequiredRepositoryClosure
                            .REPOSITORY_BUILD_DECLARED_LANGUAGE_TAG_COMMIT);
            Binding.addBoundField(
                    providerDomainFields,
                    "historicalRegistryEvidenceIdentity",
                    CoordinationRequiredRepositoryClosure
                            .HISTORICAL_REGISTRY_EVIDENCE_IDENTITY);
            Binding.addBoundField(
                    providerDomainFields,
                    "historicalEnvironmentIdentity",
                    CoordinationRequiredRepositoryClosure
                            .HISTORICAL_ENVIRONMENT_IDENTITY);
            Binding.addBoundField(
                    providerDomainFields,
                    "transformEquivalenceIdentity",
                    CoordinationRequiredRepositoryClosure
                            .TRANSFORM_EQUIVALENCE_IDENTITY);
            Binding.addBoundField(
                    providerDomainFields,
                    "coreSourceEquivalenceIdentity",
                    CoordinationRequiredRepositoryClosure
                            .CORE_SOURCE_EQUIVALENCE_IDENTITY);
            this.providerDomainIdentity =
                    "sha256:" + sha256(
                            providerDomainFields);
        }

        private void verifyEveryEntry() {
            for (CoordinationRequiredRepositoryClosure
                    .HistoricalEvidenceEntry entry
                    : CoordinationRequiredRepositoryClosure
                    .historicalEvidenceEntries()) {
                fetchResultByBlueId(
                        entry.blueId());
            }
            everyEntryInspected =
                    true;
        }

        private int verifiedEntryCount() {
            return countOutcome(
                    NodeProviderOutcome.FOUND);
        }

        private int inspectedEntryCount() {
            return everyEntryInspected
                    ? resultByBlueId.size()
                    : 0;
        }

        private int invalidEntryCount() {
            return countOutcome(
                    NodeProviderOutcome.INVALID_EVIDENCE);
        }

        private int countOutcome(
                NodeProviderOutcome expected) {
            if (!everyEntryInspected) {
                return 0;
            }
            int count = 0;
            for (NodeProviderResult result
                    : resultByBlueId.values()) {
                if (result.outcome()
                        == expected) {
                    count++;
                }
            }
            return count;
        }

        private String verifiedEvidenceIdentity() {
            return everyEntryInspected
                    && verifiedEntryCount()
                    == entryByBlueId.size()
                    ? CoordinationRequiredRepositoryClosure
                    .HISTORICAL_REGISTRY_EVIDENCE_IDENTITY
                    : null;
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            NodeProviderResult result =
                    fetchResultByBlueId(
                            blueId);
            return result.outcome()
                    == NodeProviderOutcome.FOUND
                    ? result.nodes()
                    : null;
        }

        @Override
        public synchronized NodeProviderResult fetchResultByBlueId(
                String blueId) {
            CoordinationRequiredRepositoryClosure
                    .HistoricalEvidenceEntry entry =
                    entryByBlueId.get(
                            blueId);
            if (entry == null) {
                return NodeProviderResult.notFound();
            }
            NodeProviderResult retained =
                    resultByBlueId.get(
                            blueId);
            if (retained != null) {
                return copy(
                        retained);
            }
            if (!loadingBlueIds.add(
                    blueId)) {
                return NodeProviderResult.invalidEvidence(
                        "Historical registry source dependency cycle "
                                + "encountered while verifying "
                                + blueId);
            }
            NodeProviderResult result;
            try {
                Node source =
                        normalizeHistoricalAliases(
                                readHistoricalSource(
                                        entry));
                SourceProviderEnvironment environment =
                        historicalEnvironment(
                                entry,
                                source);
                Node verified =
                        ProviderEvidenceVerifier.verify(
                                entry.blueId(),
                                source,
                                ProviderMode
                                        .BOUND_SOURCE_CONTENT,
                                verificationRuntime,
                                environment);
                result =
                        NodeProviderResult.found(
                                Collections.singletonList(
                                        verified));
            } catch (RuntimeException invalid) {
                result =
                        NodeProviderResult.invalidEvidence(
                                "Historical registry source "
                                        + entry.path()
                                        + " failed under environment "
                                        + attemptedEnvironmentIdentity(
                                        entry)
                                        + ": "
                                        + diagnostic(
                                        invalid));
            } finally {
                loadingBlueIds.remove(
                        blueId);
            }
            resultByBlueId.put(
                    blueId,
                    result);
            return copy(
                    result);
        }

        private Node readHistoricalSource(
                CoordinationRequiredRepositoryClosure
                        .HistoricalEvidenceEntry entry) {
            try {
                JsonNode source =
                        UncheckedObjectMapper
                                .YAML_MAPPER
                                .readTree(
                                        entry.sourceBytes());
                if (source == null
                        || !source.isObject()) {
                    throw new IllegalArgumentException(
                            "Historical registry source must contain "
                                    + "exactly one object node");
                }
                return UncheckedObjectMapper
                        .JSON_MAPPER
                        .convertValue(
                                source,
                                Node.class);
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                        "Historical registry source cannot be parsed: "
                                + entry.path(),
                        failure);
            }
        }

        private SourceProviderEnvironment historicalEnvironment(
                CoordinationRequiredRepositoryClosure
                        .HistoricalEvidenceEntry entry,
                Node source) {
            return new SourceProviderEnvironment(
                    verificationRuntime
                            .languageVersion(),
                    SourceProviderEnvironment
                            .LANGUAGE_1_0_RELEASE_IDENTITY,
                    ProviderEvidenceVerifier
                            .preprocessingEnvironmentIdentity(
                                    verificationRuntime),
                    BlueCoreTypeRegistry.INSTANCE
                            .packageIdentity(),
                    providerDomainIdentity,
                    ProviderMode.BOUND_SOURCE_CONTENT,
                    SourceProviderEnvironment
                            .LANGUAGE_CONTENT_STRATEGY_IDENTITY,
                    sourceEvidenceIdentity(
                            entry.blueId(),
                            Collections.singletonList(
                                    source)));
        }

        private String attemptedEnvironmentIdentity(
                CoordinationRequiredRepositoryClosure
                        .HistoricalEvidenceEntry entry) {
            try {
                Node source =
                        normalizeHistoricalAliases(
                                readHistoricalSource(
                                        entry));
                return ProviderEvidenceVerifier
                        .sourceEnvironmentIdentity(
                                historicalEnvironment(
                                        entry,
                                        source));
            } catch (RuntimeException invalid) {
                return CoordinationRequiredRepositoryClosure
                        .HISTORICAL_ENVIRONMENT_IDENTITY;
            }
        }

        private Node normalizeHistoricalAliases(
                Node exactSource) {
            return new ReplaceInlineValuesForTypeAttributesWithImports(
                    CoordinationRequiredRepositoryClosure
                            .historicalPreprocessingAliases())
                    .process(
                            exactSource);
        }

        private void verifyHistoricalTransformReplay() {
            if (!"proved-alias-table-only-delta"
                    .equals(
                            CoordinationRequiredRepositoryClosure
                                    .TRANSFORM_EQUIVALENCE_STATUS)) {
                throw new IllegalStateException(
                        "Historical preprocessing transform equivalence "
                                + "was not proved");
            }
            byte[] historicalDefaultBlue =
                    CoordinationRequiredRepositoryClosure
                            .historicalDefaultBlueSourceBytes();
            byte[] currentDefaultBlue =
                    readCurrentDefaultBlue();
            requireDigest(
                    "historical Default Blue",
                    historicalDefaultBlue,
                    CoordinationRequiredRepositoryClosure
                            .HISTORICAL_DEFAULT_BLUE_SHA256);
            requireDigest(
                    "current Default Blue",
                    currentDefaultBlue,
                    CoordinationRequiredRepositoryClosure
                            .CURRENT_DEFAULT_BLUE_SHA256);
            byte[] historicalNormalized =
                    normalizedDefaultBlueBody(
                            historicalDefaultBlue);
            byte[] currentNormalized =
                    normalizedDefaultBlueBody(
                            currentDefaultBlue);
            String historicalBodySha256 =
                    sha256Bytes(
                            historicalNormalized);
            String currentBodySha256 =
                    sha256Bytes(
                            currentNormalized);
            if (!historicalBodySha256.equals(
                    currentBodySha256)
                    || !historicalBodySha256.equals(
                    CoordinationRequiredRepositoryClosure
                            .NORMALIZED_DEFAULT_BLUE_BODY_SHA256)) {
                throw new IllegalStateException(
                        "Historical and current Default Blue differ "
                                + "outside the exact alias table");
            }
            Map<String, String> historicalAliases =
                    defaultBlueAliases(
                            historicalDefaultBlue);
            Map<String, String> currentAliases =
                    defaultBlueAliases(
                            currentDefaultBlue);
            if (!historicalAliases.equals(
                    CoordinationRequiredRepositoryClosure
                            .historicalPreprocessingAliases())) {
                throw new IllegalStateException(
                        "Embedded historical Default Blue aliases differ "
                                + "from generated registry evidence");
            }
            requireAliasIdentity(
                    "historical Default Blue",
                    historicalAliases,
                    CoordinationRequiredRepositoryClosure
                            .HISTORICAL_DEFAULT_BLUE_ALIAS_IDENTITY);
            requireAliasIdentity(
                    "current Default Blue",
                    currentAliases,
                    CoordinationRequiredRepositoryClosure
                            .CURRENT_DEFAULT_BLUE_ALIAS_IDENTITY);
        }

        private static void requireDigest(
                String description,
                byte[] source,
                String expected) {
            String observed =
                    sha256Bytes(
                            source);
            if (!expected.equals(
                    observed)) {
                throw new IllegalStateException(
                        description + " digest mismatch: expected "
                                + expected + ", observed "
                                + observed);
            }
        }

        private static void requireAliasIdentity(
                String description,
                Map<String, String> aliases,
                String expected) {
            List<String> fields =
                    new ArrayList<String>();
            for (Map.Entry<String, String> alias
                    : aliases.entrySet()) {
                fields.add(
                        alias.getKey());
                fields.add(
                        alias.getValue());
            }
            String observed =
                    "sha256:" + sha256(
                            fields);
            if (!expected.equals(
                    observed)) {
                throw new IllegalStateException(
                        description + " alias identity mismatch: expected "
                                + expected + ", observed "
                                + observed);
            }
        }

        private static Map<String, String> defaultBlueAliases(
                byte[] source) {
            try {
                JsonNode root =
                        UncheckedObjectMapper
                                .YAML_MAPPER
                                .readTree(
                                        source);
                if (root == null
                        || !root.isArray()
                        || root.size() < 2
                        || !root.get(0)
                        .path("mappings")
                        .isObject()) {
                    throw new IllegalArgumentException(
                            "Default Blue transform evidence has no "
                                    + "first-item mappings object");
                }
                Map<String, String> aliases =
                        new LinkedHashMap<String, String>();
                java.util.Iterator<Map.Entry<String, JsonNode>> fields =
                        root.get(0)
                                .path("mappings")
                                .fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field =
                            fields.next();
                    if (!field.getValue()
                            .isTextual()) {
                        throw new IllegalArgumentException(
                                "Default Blue alias is not textual: "
                                        + field.getKey());
                    }
                    String previous =
                            aliases.put(
                                    field.getKey(),
                                    field.getValue()
                                            .asText());
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "Default Blue alias is duplicated: "
                                        + field.getKey());
                    }
                }
                return aliases;
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                        "Default Blue transform evidence cannot be parsed",
                        failure);
            }
        }

        private static byte[] normalizedDefaultBlueBody(
                byte[] source) {
            String text =
                    new String(
                            source,
                            StandardCharsets.UTF_8);
            Matcher header =
                    Pattern.compile(
                                    "^  mappings:\\r?$",
                                    Pattern.MULTILINE)
                            .matcher(
                                    text);
            if (!header.find()) {
                throw new IllegalArgumentException(
                        "Default Blue transform evidence has no "
                                + "mappings block");
            }
            Matcher nextItem =
                    Pattern.compile(
                                    "^- type:\\r?$",
                                    Pattern.MULTILINE)
                            .matcher(
                                    text);
            if (!nextItem.find(
                    header.end())) {
                throw new IllegalArgumentException(
                        "Default Blue transform evidence has no "
                                + "post-mapping transform");
            }
            return (text.substring(
                    0,
                    header.start())
                    + "  mappings:\n"
                    + "    <exact-alias-table>\n"
                    + text.substring(
                    nextItem.start()))
                    .getBytes(
                            StandardCharsets.UTF_8);
        }

        private static byte[] readCurrentDefaultBlue() {
            try (InputStream input =
                         ProviderEvidenceVerifier.class
                                 .getClassLoader()
                                 .getResourceAsStream(
                                         "transformation/"
                                                 + "DefaultBlue.blue")) {
                if (input == null) {
                    throw new IllegalStateException(
                            "Current Default Blue resource is unavailable");
                }
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream();
                byte[] buffer =
                        new byte[8192];
                int count;
                while ((count = input.read(
                        buffer)) >= 0) {
                    output.write(
                            buffer,
                            0,
                            count);
                }
                return output.toByteArray();
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Current Default Blue resource cannot be read",
                        failure);
            }
        }
    }

    static final class Binding {
        private final String repositoryCoordinate;
        private final String repositoryVersion;
        private final String repositoryManifestBlueId;
        private final String repositoryHeadCommit;
        private final String repositoryArtifactSha256;
        private final String languageReleaseIdentity;
        private final String contractsRuntimeRegistryIdentity;
        private final String historicalEnvironmentIdentity;

        Binding(
                String repositoryCoordinate,
                String repositoryVersion,
                String repositoryManifestBlueId,
                String repositoryHeadCommit,
                String repositoryArtifactSha256,
                String languageReleaseIdentity,
                String contractsRuntimeRegistryIdentity,
                String historicalEnvironmentIdentity) {
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
            this.repositoryHeadCommit =
                    text(
                            repositoryHeadCommit,
                            "repositoryHeadCommit");
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
            this.historicalEnvironmentIdentity =
                    text(
                            historicalEnvironmentIdentity,
                            "historicalEnvironmentIdentity");
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
                    repositoryHeadCommit,
                    repositoryArtifactSha256,
                    languageReleaseIdentity,
                    contractsRuntimeRegistryIdentity,
                    historicalEnvironmentIdentity);
        }

        String providerDomainIdentity(
                Blue blue) {
            List<String> fields =
                    new ArrayList<String>();
            addBoundField(
                    fields,
                    "profile",
                    PROFILE);
            addBoundField(
                    fields,
                    "repositoryCoordinate",
                    repositoryCoordinate);
            addBoundField(
                    fields,
                    "repositoryVersion",
                    repositoryVersion);
            addBoundField(
                    fields,
                    "repositoryManifestBlueId",
                    repositoryManifestBlueId);
            addBoundField(
                    fields,
                    "immutableRepositoryHeadCommit",
                    repositoryHeadCommit);
            addBoundField(
                    fields,
                    "selectedRepositoryArtifactSha256",
                    repositoryArtifactSha256);
            addBoundField(
                    fields,
                    "languageReleaseIdentity",
                    languageReleaseIdentity);
            addBoundField(
                    fields,
                    "activeContractsRuntimeRegistryIdentity",
                    contractsRuntimeRegistryIdentity);
            addBoundField(
                    fields,
                    "historicalEnvironmentEvidenceIdentity",
                    historicalEnvironmentIdentity);
            addBoundField(
                    fields,
                    "runtimeLanguageVersion",
                    blue.languageVersion());
            addBoundField(
                    fields,
                    "runtimePreprocessingEnvironmentIdentity",
                    ProviderEvidenceVerifier
                            .preprocessingEnvironmentIdentity(
                                    blue));
            addBoundField(
                    fields,
                    "runtimeCoreRegistryIdentity",
                    BlueCoreTypeRegistry.INSTANCE
                            .packageIdentity());
            return "sha256:" + sha256(fields);
        }

        private static void addBoundField(
                List<String> fields,
                String label,
                String value) {
            fields.add(
                    label);
            fields.add(
                    value);
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

    static final class RequiredClosureAudit {
        private final String closureIdentity;
        private final String historicalEnvironmentIdentity;
        private final int requiredTotal;
        private final int cyclicSetCount;
        private final int incompleteCyclicProof;
        private final List<AuditEntry> entries;
        private final List<IncompatibilityProof> incompatibilityProofs;
        private final String selectedReleaseMismatch;

        private RequiredClosureAudit(
                String closureIdentity,
                String historicalEnvironmentIdentity,
                int requiredTotal,
                int cyclicSetCount,
                int incompleteCyclicProof,
                List<AuditEntry> entries,
                String selectedReleaseMismatch) {
            this.closureIdentity =
                    closureIdentity;
            this.historicalEnvironmentIdentity =
                    historicalEnvironmentIdentity;
            this.requiredTotal =
                    requiredTotal;
            this.cyclicSetCount =
                    cyclicSetCount;
            this.incompleteCyclicProof =
                    incompleteCyclicProof;
            this.selectedReleaseMismatch =
                    selectedReleaseMismatch;
            this.entries =
                    Collections.unmodifiableList(
                            new ArrayList<AuditEntry>(
                                    entries));
            List<IncompatibilityProof> failures =
                    new ArrayList<IncompatibilityProof>();
            for (AuditEntry entry : entries) {
                if (entry.outcome()
                        != NodeProviderOutcome.FOUND) {
                    failures.add(
                            IncompatibilityProof.from(
                                    entry));
                }
            }
            this.incompatibilityProofs =
                    Collections.unmodifiableList(
                            failures);
        }

        private static RequiredClosureAudit selectedReleaseMismatch(
                String closureIdentity,
                String historicalEnvironmentIdentity,
                int requiredTotal,
                String diagnostic) {
            return new RequiredClosureAudit(
                    closureIdentity,
                    historicalEnvironmentIdentity,
                    requiredTotal,
                    0,
                    0,
                    Collections.<AuditEntry>emptyList(),
                    diagnostic);
        }

        String closureIdentity() {
            return closureIdentity;
        }

        String historicalEnvironmentIdentity() {
            return historicalEnvironmentIdentity;
        }

        int cyclicSetCount() {
            return cyclicSetCount;
        }

        int incompleteCyclicProof() {
            return incompleteCyclicProof;
        }

        List<AuditEntry> entries() {
            return entries;
        }

        List<IncompatibilityProof> incompatibilityProofs() {
            return incompatibilityProofs;
        }

        int total() {
            return requiredTotal;
        }

        int audited() {
            return entries.size();
        }

        String selectedReleaseMismatch() {
            return selectedReleaseMismatch;
        }

        int verified() {
            return count(
                    NodeProviderOutcome.FOUND);
        }

        int missing() {
            return count(
                    NodeProviderOutcome.NOT_FOUND);
        }

        int invalidEvidence() {
            return count(
                    NodeProviderOutcome.INVALID_EVIDENCE);
        }

        int unavailable() {
            return count(
                    NodeProviderOutcome.UNAVAILABLE);
        }

        boolean eligible() {
            return selectedReleaseMismatch == null
                    && audited() == total()
                    && verified() == total()
                    && missing() == 0
                    && invalidEvidence() == 0
                    && unavailable() == 0
                    && incompleteCyclicProof == 0;
        }

        private int count(
                NodeProviderOutcome outcome) {
            int count = 0;
            for (AuditEntry entry : entries) {
                if (entry.outcome()
                        == outcome) {
                    count++;
                }
            }
            return count;
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
        private final String sourceResourceSha256;
        private final String verificationStrategy;
        private final String calculatedIdentity;
        private final String earliestFailingPath;

        private AuditEntry(
                String qualifiedName,
                String blueId,
                String resourcePath,
                NodeProviderOutcome outcome,
                String diagnostic,
                String sourceEnvironmentIdentity,
                boolean cyclicMember,
                String sourceResourceSha256,
                String verificationStrategy,
                String calculatedIdentity,
                String earliestFailingPath) {
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
            this.sourceResourceSha256 =
                    sourceResourceSha256;
            this.verificationStrategy =
                    verificationStrategy;
            this.calculatedIdentity =
                    calculatedIdentity;
            this.earliestFailingPath =
                    earliestFailingPath;
        }

        static AuditEntry from(
                RepositoryDefinition definition,
                NodeProviderResult result,
                String sourceEnvironmentIdentity,
                boolean cyclicMember,
                String sourceResourceSha256,
                String verificationStrategy,
                String calculatedIdentity,
                String earliestFailingPath) {
            return new AuditEntry(
                    definition.qualifiedName(),
                    definition.blueId(),
                    definition.resourcePath(),
                    result.outcome(),
                    result.diagnostic()
                            .orElse(null),
                    sourceEnvironmentIdentity,
                    cyclicMember,
                    sourceResourceSha256,
                    verificationStrategy,
                    calculatedIdentity,
                    earliestFailingPath);
        }

        static AuditEntry missing(
                CoordinationRequiredRepositoryClosure.Entry required,
                String diagnostic) {
            return requiredBindingFailure(
                    required,
                    NodeProviderOutcome.NOT_FOUND,
                    diagnostic);
        }

        static AuditEntry invalidBinding(
                CoordinationRequiredRepositoryClosure.Entry required,
                String diagnostic) {
            return requiredBindingFailure(
                    required,
                    NodeProviderOutcome.INVALID_EVIDENCE,
                    diagnostic);
        }

        private static AuditEntry requiredBindingFailure(
                CoordinationRequiredRepositoryClosure.Entry required,
                NodeProviderOutcome outcome,
                String diagnostic) {
            return new AuditEntry(
                    required.qualifiedName(),
                    required.blueId(),
                    required.resourcePath(),
                    outcome,
                    diagnostic,
                    CoordinationRequiredRepositoryClosure
                            .HISTORICAL_ENVIRONMENT_IDENTITY,
                    required.cyclicMember(),
                    required.sourceResourceSha256(),
                    IMMUTABLE_CLOSURE_BINDING_STRATEGY,
                    null,
                    "$");
        }

        AuditEntry withResult(
                NodeProviderResult result) {
            return new AuditEntry(
                    qualifiedName,
                    blueId,
                    resourcePath,
                    result.outcome(),
                    result.diagnostic()
                            .orElse(null),
                    sourceEnvironmentIdentity,
                    cyclicMember,
                    sourceResourceSha256,
                    verificationStrategy,
                    calculatedIdentity,
                    result.outcome()
                            == NodeProviderOutcome.FOUND
                            ? null
                            : FixedRepositoryBoundSourceProvider
                            .earliestFailingPath(
                                    result.diagnostic()
                                            .orElse(null)));
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

        String sourceResourceSha256() {
            return sourceResourceSha256;
        }

        String verificationStrategy() {
            return verificationStrategy;
        }

        String calculatedIdentity() {
            return calculatedIdentity;
        }

        String earliestFailingPath() {
            return earliestFailingPath;
        }
    }

    static final class IncompatibilityProof {
        private final String qualifiedName;
        private final String publishedBlueId;
        private final String sourceResourceSha256;
        private final String exactEnvironmentAttempted;
        private final String calculatedIdentity;
        private final String earliestFailingPath;
        private final String diagnostic;

        private IncompatibilityProof(
                String qualifiedName,
                String publishedBlueId,
                String sourceResourceSha256,
                String exactEnvironmentAttempted,
                String calculatedIdentity,
                String earliestFailingPath,
                String diagnostic) {
            this.qualifiedName =
                    qualifiedName;
            this.publishedBlueId =
                    publishedBlueId;
            this.sourceResourceSha256 =
                    sourceResourceSha256;
            this.exactEnvironmentAttempted =
                    exactEnvironmentAttempted;
            this.calculatedIdentity =
                    calculatedIdentity;
            this.earliestFailingPath =
                    earliestFailingPath;
            this.diagnostic =
                    diagnostic;
        }

        static IncompatibilityProof from(
                AuditEntry entry) {
            return new IncompatibilityProof(
                    entry.qualifiedName(),
                    entry.blueId(),
                    entry.sourceResourceSha256(),
                    entry.sourceEnvironmentIdentity(),
                    entry.calculatedIdentity(),
                    entry.earliestFailingPath(),
                    entry.diagnostic());
        }

        String qualifiedName() {
            return qualifiedName;
        }

        String publishedBlueId() {
            return publishedBlueId;
        }

        String sourceResourceSha256() {
            return sourceResourceSha256;
        }

        String exactEnvironmentAttempted() {
            return exactEnvironmentAttempted;
        }

        String calculatedIdentity() {
            return calculatedIdentity;
        }

        String earliestFailingPath() {
            return earliestFailingPath;
        }

        String diagnostic() {
            return diagnostic;
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

    private static String sha256Bytes(
            byte[] bytes) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");
            return hexadecimal(
                    digest.digest(
                            bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    impossible);
        }
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
