package blue.coordination.processor;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import blue.repo.RepositoryDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FixedRepositoryBoundSourceProviderTest {
    private static final String REPOSITORY_BASE_COORDINATE =
            "blue.repo:blue-repo-java:3.0.0-rc.17";
    private static final String IMMUTABLE_REPOSITORY_HEAD_COMMIT =
            "63be6b7d8d2752b5a8c90f38e672859e9b3949a1";
    private static Blue blue;
    private static BlueRepository repository;
    private static FixedRepositoryBoundSourceProvider provider;
    private static FixedRepositoryBoundSourceProvider.Binding binding;
    private static String repositoryArtifactSha256;

    @BeforeAll
    static void createProviderAndWriteAudit() throws IOException {
        repository =
                BlueRepository.latest();
        binding =
                FixedRepositoryBoundSourceProvider
                        .releaseBinding(
                                repository);
        repositoryArtifactSha256 =
                binding.repositoryArtifactSha256();
        blue =
                Blue.withCachePolicy(
                        BlueCachePolicy.disabled());
        provider =
                FixedRepositoryBoundSourceProvider.inspect(
                        repository,
                        FixedRepositoryBoundSourceProviderTest.class
                                .getClassLoader(),
                        binding);
    }

    @AfterAll
    static void closeRuntime() {
        if (provider != null) {
            provider.close();
        }
        if (blue != null) {
            blue.close();
        }
    }

    @Test
    void shouldVerifyEveryFixedRepositoryDefinitionUnderBoundSourceContent()
            throws IOException {
        // given
        FixedRepositoryBoundSourceProvider.CatalogAudit audit =
                provider.audit();
        FixedRepositoryBoundSourceProvider.RequiredClosureAudit
                requiredClosure =
                provider.requiredClosureAudit();

        // when
        writeAudit(
                audit,
                requiredClosure);
        List<FixedRepositoryBoundSourceProvider.AuditEntry> failures =
                failures(audit);

        // then
        assertEquals(
                1107,
                audit.total());
        assertEquals(
                10,
                audit.cyclicSetCount());
        assertEquals(
                27,
                cyclicMemberCount(audit));
        assertEquals(
                audit.total(),
                audit.verified() + audit.failed(),
                failureMessage(failures));
    }

    @Test
    void shouldVerifyRequiredClosureOrEmitExactIncompatibilityProof()
            throws IOException {
        // given
        FixedRepositoryBoundSourceProvider.RequiredClosureAudit audit =
                provider.requiredClosureAudit();

        // when
        writeAudit(
                provider.audit(),
                audit);

        // then
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .entries()
                        .size(),
                audit.total());
        if (audit.eligible()) {
            assertEquals(
                    audit.total(),
                    audit.verified());
            assertEquals(
                    0,
                    audit.incompatibilityProofs()
                            .size());
        } else if (audit.selectedReleaseMismatch()
                != null) {
            assertFalse(
                    audit.eligible());
            assertEquals(
                    0,
                    audit.audited());
            assertEquals(
                    0,
                    audit.missing());
            assertEquals(
                    0,
                    audit.invalidEvidence());
            assertEquals(
                    0,
                    audit.incompatibilityProofs()
                            .size());
            assertTrue(
                    audit.selectedReleaseMismatch()
                            .contains(
                                    "differs from exact immutable "
                                            + "HEAD closure"));
        } else {
            assertEquals(
                    audit.total(),
                    audit.audited());
            assertFalse(
                    audit.incompatibilityProofs()
                            .isEmpty(),
                    requiredFailureMessage(
                            audit));
            for (FixedRepositoryBoundSourceProvider.IncompatibilityProof
                    proof : audit.incompatibilityProofs()) {
                assertNotNull(
                        proof.qualifiedName());
                assertNotNull(
                        proof.publishedBlueId());
                assertTrue(
                        proof.sourceResourceSha256()
                                .matches("[0-9a-f]{64}"));
                assertNotNull(
                        proof.exactEnvironmentAttempted());
                assertNotNull(
                        proof.earliestFailingPath());
                assertNotNull(
                        proof.diagnostic());
            }
        }
    }

    @Test
    void shouldPreserveTypedMissesAndReturnDefensiveProviderValues() {
        // given
        String verifiedBlueId = null;
        for (FixedRepositoryBoundSourceProvider.AuditEntry entry
                : provider.audit()
                .entries()) {
            if (entry.outcome()
                    == NodeProviderOutcome.FOUND) {
                verifiedBlueId =
                        entry.blueId();
                break;
            }
        }
        assertNotNull(
                verifiedBlueId);
        NodeProviderResult first =
                provider
                        .fetchResultByBlueId(
                                verifiedBlueId);
        assertEquals(
                NodeProviderOutcome.FOUND,
                first.outcome());
        Node mutable =
                first.nodes().get(0);

        // when
        mutable.name("mutated-by-caller");
        NodeProviderResult second =
                provider
                        .fetchResultByBlueId(
                                verifiedBlueId);
        NodeProviderResult missing =
                provider
                        .fetchResultByBlueId(
                                "FG4LidzBiMCyVt53aP8kJXjcZXZ97mVfnv7N92zueGzr");

        // then
        assertEquals(
                NodeProviderOutcome.FOUND,
                second.outcome());
        assertNotEquals(
                "mutated-by-caller",
                second.nodes().get(0).getName());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                missing.outcome());
        assertTrue(
                missing.nodes().isEmpty());
    }

    @Test
    void shouldRetainVerifiedResultsAcrossDifferentRepositoryMasters() {
        // given
        String firstBlueId =
                repository.blueId(
                        "Coordination/API Call");
        String secondBlueId =
                repository.blueId(
                        "Coordination/Sequential Workflow");
        NodeProviderResult first =
                provider.fetchResultByBlueId(
                        firstBlueId);

        // when
        NodeProviderResult second =
                provider.fetchResultByBlueId(
                        secondBlueId);
        NodeProviderResult firstAgain =
                provider.fetchResultByBlueId(
                        firstBlueId);

        // then
        assertNotEquals(
                NodeProviderOutcome.NOT_FOUND,
                first.outcome());
        assertNotEquals(
                NodeProviderOutcome.NOT_FOUND,
                second.outcome());
        assertEquals(
                first.outcome(),
                firstAgain.outcome());
        assertEquals(
                first.diagnostic(),
                firstAgain.diagnostic());
        if (first.outcome()
                == NodeProviderOutcome.FOUND) {
            assertEquals(
                    blue.nodeToJson(
                            first.nodes().get(0)),
                    blue.nodeToJson(
                            firstAgain.nodes().get(0)));
        }
    }

    @Test
    void shouldExposeCompleteProofForEveryVerifiedCyclicMember() {
        // given
        Map<String, List<String>> membersByMaster =
                new TreeMap<String, List<String>>();
        for (RepositoryDefinition definition
                : repository.manifest().definitions()) {
            int separator =
                    definition.blueId()
                            .indexOf('#');
            if (separator < 0) {
                continue;
            }
            String master =
                    definition.blueId()
                            .substring(
                                    0,
                                    separator);
            List<String> members =
                    membersByMaster.get(
                            master);
            if (members == null) {
                members =
                        new ArrayList<String>();
                membersByMaster.put(
                        master,
                        members);
            }
            members.add(
                    definition.blueId());
        }

        // when / then
        assertFalse(
                membersByMaster.isEmpty());
        for (Map.Entry<String, List<String>> group
                : membersByMaster.entrySet()) {
            List<String> members =
                    group.getValue();
            Collections.sort(
                    members,
                    (left, right) -> Integer.compare(
                            cyclicMemberIndex(
                                    left),
                            cyclicMemberIndex(
                                    right)));
            for (int index = 0;
                 index < members.size();
                 index++) {
                String member =
                        members.get(
                                index);
                assertEquals(
                        group.getKey()
                                + "#" + index,
                        member);
                NodeProviderOutcome contentOutcome =
                        blue.getNodeProvider()
                                .fetchResultByBlueId(
                                        member)
                                .outcome();
                NodeProviderOutcome proofOutcome =
                        provider.cyclicSetProofFor(
                                        member)
                                .outcome();
                if (contentOutcome
                        == NodeProviderOutcome.FOUND) {
                    assertEquals(
                            NodeProviderOutcome.FOUND,
                            proofOutcome,
                            member);
                } else {
                    assertNotEquals(
                            NodeProviderOutcome.FOUND,
                            proofOutcome,
                            member);
                }
            }
        }
    }

    @Test
    void shouldKeepHistoricalRoleEvidenceOutsideTheActiveRuntime() {
        // given
        List<CoordinationRequiredRepositoryClosure
                .HistoricalEvidenceEntry> historicalEntries =
                CoordinationRequiredRepositoryClosure
                        .historicalEvidenceEntries();

        // when
        int inspected =
                provider.inspectedHistoricalEvidenceCount();
        int verified =
                provider.verifiedHistoricalEvidenceCount();
        int invalid =
                provider.invalidHistoricalEvidenceCount();

        // then
        assertFalse(
                historicalEntries.isEmpty());
        assertEquals(
                historicalEntries.size(),
                inspected);
        assertEquals(
                0,
                verified);
        assertEquals(
                historicalEntries.size(),
                invalid);
        assertEquals(
                null,
                provider.verifiedHistoricalEvidenceIdentity());
        for (CoordinationRequiredRepositoryClosure
                .HistoricalEvidenceEntry entry
                : historicalEntries) {
            assertEquals(
                    NodeProviderOutcome.NOT_FOUND,
                    blue.getNodeProvider()
                            .fetchResultByBlueId(
                                    entry.blueId())
                            .outcome(),
                    entry.key() + " ["
                            + entry.blueId() + "]");
            assertEquals(
                    NodeProviderOutcome.NOT_FOUND,
                    BlueRuntimeTypeRegistry
                            .getDefault()
                            .asProvider()
                            .fetchResultByBlueId(
                                    entry.blueId())
                            .outcome(),
                    entry.key() + " ["
                            + entry.blueId() + "]");
        }
    }

    @Test
    void shouldCloseTheOwnedVerificationRuntimeIdempotently() {
        // given
        FixedRepositoryBoundSourceProvider ownedProvider =
                FixedRepositoryBoundSourceProvider.inspect(
                        repository,
                        FixedRepositoryBoundSourceProviderTest.class
                                .getClassLoader(),
                        binding);

        // when
        ownedProvider.close();
        ownedProvider.close();

        // then
        assertTrue(
                ownedProvider.verificationRuntimeClosed());
    }

    @Test
    void shouldLeaveActiveRuntimeUnchangedWhenRequiredClosureCannotVerify() {
        // given
        Blue activeRuntime =
                Blue.withCachePolicy(
                        BlueCachePolicy.disabled());
        blue.language.NodeProvider originalProvider =
                activeRuntime.getNodeProvider();

        // when
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> FixedRepositoryBoundSourceProvider
                                .configure(
                                        repository,
                                        activeRuntime,
                                        FixedRepositoryBoundSourceProviderTest
                                                .class
                                                .getClassLoader(),
                                        binding));

        // then
        assertEquals(
                originalProvider,
                activeRuntime.getNodeProvider());
        assertTrue(
                failure.getMessage()
                        .startsWith(
                                "Required immutable Repository closure "
                                        + "did not verify:"));
        assertTrue(
                failure.getMessage()
                        .contains(
                                "calculated="));
        assertFalse(
                activeRuntime.isClosed());
        activeRuntime.close();
    }

    @Test
    void shouldRejectARepositoryManifestThatDiffersFromItsBinding() {
        // given
        FixedRepositoryBoundSourceProvider.Binding wrongBinding =
                binding.withRepositoryManifestBlueId(
                        "wrong-fixed-repository-manifest-identity");

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FixedRepositoryBoundSourceProvider(
                                repository,
                                blue,
                                FixedRepositoryBoundSourceProviderTest.class
                                        .getClassLoader(),
                                wrongBinding));

        // then
        assertTrue(
                failure.getMessage()
                        .contains(
                                "manifest identity"));
    }

    @Test
    void shouldRejectMismatchedDeclaredRepositoryArtifactShaAndRestoreProperty() {
        // given
        String propertyName =
                "coordination.fixed.repository.artifact.sha256";
        String previous =
                System.getProperty(
                        propertyName);
        IllegalStateException failure;

        // when
        try {
            System.setProperty(
                    propertyName,
                    "000000000000000000000000000000000000000000000000"
                            + "0000000000000000");
            failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> FixedRepositoryBoundSourceProvider
                                    .releaseBinding(
                                            repository));
        } finally {
            if (previous == null) {
                System.clearProperty(
                        propertyName);
            } else {
                System.setProperty(
                        propertyName,
                        previous);
            }
        }

        // then
        assertTrue(
                failure.getMessage()
                        .contains(
                                "differs from the loaded JAR digest"));
        assertEquals(
                previous,
                System.getProperty(
                        propertyName));
    }

    private static List<FixedRepositoryBoundSourceProvider.AuditEntry>
    failures(
            FixedRepositoryBoundSourceProvider.CatalogAudit audit) {
        List<FixedRepositoryBoundSourceProvider.AuditEntry> failures =
                new ArrayList<FixedRepositoryBoundSourceProvider.AuditEntry>();
        for (FixedRepositoryBoundSourceProvider.AuditEntry entry
                : audit.entries()) {
            if (entry.outcome()
                    != NodeProviderOutcome.FOUND) {
                failures.add(entry);
            }
        }
        return failures;
    }

    private static int cyclicMemberCount(
            FixedRepositoryBoundSourceProvider.CatalogAudit audit) {
        int count = 0;
        for (FixedRepositoryBoundSourceProvider.AuditEntry entry
                : audit.entries()) {
            if (entry.cyclicMember()) {
                count++;
            }
        }
        return count;
    }

    private static String failureMessage(
            List<FixedRepositoryBoundSourceProvider.AuditEntry> failures) {
        StringBuilder message =
                new StringBuilder(
                        "Fixed Repository BOUND_SOURCE_CONTENT "
                                + "incompatibilities:");
        int displayed =
                Math.min(
                        failures.size(),
                        20);
        for (int index = 0;
             index < displayed;
             index++) {
            FixedRepositoryBoundSourceProvider.AuditEntry entry =
                    failures.get(index);
            message.append("\n")
                    .append(entry.qualifiedName())
                    .append(" [")
                    .append(entry.blueId())
                    .append("]: ")
                    .append(entry.outcome())
                    .append(" ")
                    .append(entry.diagnostic());
        }
        if (failures.size() > displayed) {
            message.append("\n... and ")
                    .append(failures.size() - displayed)
                    .append(" more");
        }
        return message.toString();
    }

    private static String requiredFailureMessage(
            FixedRepositoryBoundSourceProvider.RequiredClosureAudit audit) {
        StringBuilder message =
                new StringBuilder(
                        "Required fixed Repository closure "
                                + "incompatibilities:");
        for (FixedRepositoryBoundSourceProvider.IncompatibilityProof proof
                : audit.incompatibilityProofs()) {
            message.append("\n")
                    .append(proof.qualifiedName())
                    .append(" [")
                    .append(proof.publishedBlueId())
                    .append("] source=")
                    .append(proof.sourceResourceSha256())
                    .append(" environment=")
                    .append(proof.exactEnvironmentAttempted())
                    .append(" calculated=")
                    .append(proof.calculatedIdentity())
                    .append(" path=")
                    .append(proof.earliestFailingPath())
                    .append(" diagnostic=")
                    .append(proof.diagnostic());
        }
        return message.toString();
    }

    private static void writeAudit(
            FixedRepositoryBoundSourceProvider.CatalogAudit audit,
            FixedRepositoryBoundSourceProvider.RequiredClosureAudit
                    requiredClosure)
            throws IOException {
        Map<String, Object> report =
                new LinkedHashMap<String, Object>();
        report.put(
                "schema",
                "blue.coordination/fixed-repository-catalog-audit/1.0");
        report.put(
                "status",
                "informative");
        report.put(
                "releaseEligibilityBasis",
                "requiredClosure");
        report.put(
                "releaseEligible",
                requiredClosure.eligible());
        report.put(
                "repositoryCoordinate",
                repositoryCoordinate());
        report.put(
                "repositoryVersion",
                audit.repositoryVersion());
        report.put(
                "repositoryManifestBlueId",
                audit.repositoryManifestBlueId());
        report.put(
                "observedLoadedManifestSha256",
                loadedRepositoryManifestSha256());
        report.put(
                "immutableHeadExpectedManifestSha256",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_MANIFEST_SHA256);
        report.put(
                "loadedManifestMatchesImmutableHead",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_MANIFEST_SHA256
                        .equals(
                                loadedRepositoryManifestSha256()));
        report.put(
                "immutableHeadCommit",
                IMMUTABLE_REPOSITORY_HEAD_COMMIT);
        report.put(
                "selectedRepositoryArtifactSha256",
                repositoryArtifactSha256);
        report.put(
                "languageReleaseIdentity",
                SourceProviderEnvironment
                        .LANGUAGE_1_0_RELEASE_IDENTITY);
        report.put(
                "contractsRuntimeRegistryIdentity",
                binding.contractsRuntimeRegistryIdentity());
        report.put(
                "providerDomainIdentity",
                audit.providerDomainIdentity());
        report.put(
                "providerMode",
                "BOUND_SOURCE_CONTENT");
        Map<String, Object> historicalEvidence =
                new LinkedHashMap<String, Object>();
        historicalEvidence.put(
                "identity",
                CoordinationRequiredRepositoryClosure
                        .HISTORICAL_REGISTRY_EVIDENCE_IDENTITY);
        historicalEvidence.put(
                "total",
                CoordinationRequiredRepositoryClosure
                        .historicalEvidenceEntries()
                        .size());
        historicalEvidence.put(
                "inspected",
                provider.inspectedHistoricalEvidenceCount());
        historicalEvidence.put(
                "verified",
                provider.verifiedHistoricalEvidenceCount());
        historicalEvidence.put(
                "invalidEvidence",
                provider.invalidHistoricalEvidenceCount());
        historicalEvidence.put(
                "verifiedIdentity",
                provider.verifiedHistoricalEvidenceIdentity());
        historicalEvidence.put(
                "activeRuntimeUse",
                false);
        report.put(
                "historicalRegistryEvidence",
                historicalEvidence);
        report.put(
                "total",
                audit.total());
        report.put(
                "verified",
                audit.verified());
        report.put(
                "failed",
                audit.failed());
        report.put(
                "cyclicSetCount",
                audit.cyclicSetCount());
        report.put(
                "cyclicMemberCount",
                cyclicMemberCount(audit));
        report.put(
                "entries",
                reportEntries(audit));
        report.put(
                "requiredClosure",
                requiredClosureReport(
                        requiredClosure));

        Path destination =
                Paths.get(
                        System.getProperty(
                                "coordination.fixed.repository.report",
                                "build/reports/coordination-release/"
                                        + "fixed-repository.json"));
        Path parent =
                destination.toAbsolutePath()
                        .getParent();
        assertNotNull(parent);
        Files.createDirectories(parent);
        UncheckedObjectMapper.JSON_MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        destination.toFile(),
                        report);
        assertTrue(
                Files.isRegularFile(
                        destination));
    }

    private static Map<String, Object> requiredClosureReport(
            FixedRepositoryBoundSourceProvider.RequiredClosureAudit audit) {
        Map<String, Object> report =
                new LinkedHashMap<String, Object>();
        report.put(
                "schema",
                CoordinationRequiredRepositoryClosure
                        .SCHEMA);
        report.put(
                "status",
                audit.eligible()
                        ? "verified"
                        : "incompatible");
        report.put(
                "eligible",
                audit.eligible());
        report.put(
                "closureIdentity",
                audit.closureIdentity());
        report.put(
                "repositoryVersion",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_VERSION);
        report.put(
                "repositoryManifestBlueId",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_MANIFEST_BLUE_ID);
        report.put(
                "repositoryManifestSha256",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_MANIFEST_SHA256);
        report.put(
                "repositorySourceProvenance",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_SOURCE_PROVENANCE);
        report.put(
                "repositoryHeadCommit",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_HEAD_COMMIT);
        report.put(
                "repositorySourceStateIdentity",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_SOURCE_STATE_IDENTITY);
        report.put(
                "exactEnvironmentAttempted",
                audit.historicalEnvironmentIdentity());
        report.put(
                "total",
                audit.total());
        report.put(
                "audited",
                audit.audited());
        report.put(
                "verified",
                audit.verified());
        report.put(
                "missing",
                audit.missing());
        report.put(
                "invalidEvidence",
                audit.invalidEvidence());
        report.put(
                "unavailable",
                audit.unavailable());
        report.put(
                "cyclicSetCount",
                audit.cyclicSetCount());
        report.put(
                "incompleteCyclicProof",
                audit.incompleteCyclicProof());
        report.put(
                "selectedReleaseMismatch",
                audit.selectedReleaseMismatch());
        report.put(
                "entries",
                requiredReportEntries(
                        audit));
        report.put(
                "incompatibilityProofs",
                incompatibilityProofs(
                        audit));
        return report;
    }

    private static List<Map<String, Object>> requiredReportEntries(
            FixedRepositoryBoundSourceProvider.RequiredClosureAudit audit) {
        List<Map<String, Object>> entries =
                new ArrayList<Map<String, Object>>();
        for (FixedRepositoryBoundSourceProvider.AuditEntry entry
                : audit.entries()) {
            entries.add(
                    reportEntry(
                            entry));
        }
        return entries;
    }

    private static List<Map<String, Object>> incompatibilityProofs(
            FixedRepositoryBoundSourceProvider.RequiredClosureAudit audit) {
        List<Map<String, Object>> proofs =
                new ArrayList<Map<String, Object>>();
        for (FixedRepositoryBoundSourceProvider.IncompatibilityProof proof
                : audit.incompatibilityProofs()) {
            Map<String, Object> serialized =
                    new LinkedHashMap<String, Object>();
            serialized.put(
                    "qualifiedName",
                    proof.qualifiedName());
            serialized.put(
                    "publishedBlueId",
                    proof.publishedBlueId());
            serialized.put(
                    "sourceResourceSha256",
                    proof.sourceResourceSha256());
            serialized.put(
                    "exactEnvironmentAttempted",
                    proof.exactEnvironmentAttempted());
            serialized.put(
                    "calculatedIdentity",
                    proof.calculatedIdentity());
            serialized.put(
                    "earliestFailingPath",
                    proof.earliestFailingPath());
            serialized.put(
                    "diagnostic",
                    proof.diagnostic());
            proofs.add(
                    serialized);
        }
        return proofs;
    }

    private static int cyclicMemberIndex(
            String blueId) {
        return Integer.parseInt(
                blueId.substring(
                        blueId.indexOf('#') + 1));
    }

    private static String repositoryCoordinate() {
        return REPOSITORY_BASE_COORDINATE
                + (System.getenv("CI") == null
                ? "-SNAPSHOT"
                : "");
    }

    private static String loadedRepositoryManifestSha256()
            throws IOException {
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
        try (InputStream input =
                     BlueRepository.class
                             .getClassLoader()
                             .getResourceAsStream(
                                     "blue/repo/manifest.json")) {
            assertNotNull(
                    input);
            byte[] buffer =
                    new byte[8192];
            int count;
            while ((count = input.read(
                    buffer)) >= 0) {
                digest.update(
                        buffer,
                        0,
                        count);
            }
        }
        StringBuilder hex =
                new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(
                    String.format(
                            java.util.Locale.ROOT,
                            "%02x",
                            value & 0xff));
        }
        return hex.toString();
    }

    private static List<Map<String, Object>> reportEntries(
            FixedRepositoryBoundSourceProvider.CatalogAudit audit) {
        List<Map<String, Object>> entries =
                new ArrayList<Map<String, Object>>();
        for (FixedRepositoryBoundSourceProvider.AuditEntry entry
                : audit.entries()) {
            entries.add(
                    reportEntry(
                            entry));
        }
        assertFalse(entries.isEmpty());
        return entries;
    }

    private static Map<String, Object> reportEntry(
            FixedRepositoryBoundSourceProvider.AuditEntry entry) {
        Map<String, Object> serialized =
                new LinkedHashMap<String, Object>();
        serialized.put(
                "qualifiedName",
                entry.qualifiedName());
        serialized.put(
                "blueId",
                entry.blueId());
        serialized.put(
                "resourcePath",
                entry.resourcePath());
        serialized.put(
                "sourceResourceSha256",
                entry.sourceResourceSha256());
        serialized.put(
                "outcome",
                entry.outcome().name());
        serialized.put(
                "diagnostic",
                entry.diagnostic());
        serialized.put(
                "sourceEnvironmentIdentity",
                entry.sourceEnvironmentIdentity());
        serialized.put(
                "verificationStrategy",
                entry.verificationStrategy());
        serialized.put(
                "calculatedIdentity",
                entry.calculatedIdentity());
        serialized.put(
                "earliestFailingPath",
                entry.earliestFailingPath());
        serialized.put(
                "cyclicMember",
                entry.cyclicMember());
        return serialized;
    }
}
