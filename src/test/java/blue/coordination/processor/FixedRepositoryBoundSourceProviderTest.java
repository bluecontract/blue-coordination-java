package blue.coordination.processor;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FixedRepositoryBoundSourceProviderTest {
    private static final String REPOSITORY_BASE_COORDINATE =
            "blue.repo:blue-repo-java:3.0.0-rc.17";
    private static final String REPOSITORY_COMMIT =
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
                FixedRepositoryBoundSourceProvider.configure(
                        repository,
                        blue,
                        FixedRepositoryBoundSourceProviderTest.class
                                .getClassLoader(),
                        binding);
    }

    @AfterAll
    static void closeRuntime() {
        if (blue != null) {
            blue.close();
        }
    }

    @Test
    void shouldVerifyEveryFixedRepositoryDefinitionUnderBoundSourceContent()
            throws IOException {
        // Given
        FixedRepositoryBoundSourceProvider.CatalogAudit audit =
                provider.audit();

        // When
        writeAudit(
                audit);
        List<FixedRepositoryBoundSourceProvider.AuditEntry> failures =
                failures(audit);

        // Then
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
                1107,
                audit.verified(),
                failureMessage(failures));
        assertEquals(
                0,
                audit.failed(),
                failureMessage(failures));
    }

    @Test
    void shouldPreserveTypedMissesAndReturnDefensiveProviderValues() {
        // Given
        String verifiedBlueId =
                repository.blueId(
                        "Coordination/API Call");
        NodeProviderResult first =
                blue.getNodeProvider()
                        .fetchResultByBlueId(
                                verifiedBlueId);
        Node mutable =
                first.nodes().get(0);

        // When
        mutable.name("mutated-by-caller");
        NodeProviderResult second =
                blue.getNodeProvider()
                        .fetchResultByBlueId(
                                verifiedBlueId);
        NodeProviderResult missing =
                blue.getNodeProvider()
                        .fetchResultByBlueId(
                                "FG4LidzBiMCyVt53aP8kJXjcZXZ97mVfnv7N92zueGzr");

        // Then
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
    void shouldExposeCompleteProofForEveryVerifiedCyclicMember() {
        // Given
        String cyclicBlueId =
                "4CbQ8TBSptAuoovUmWPoYLPUFd5YV6vbnByMeq8La9rw#0";

        // When
        NodeProviderOutcome proofOutcome =
                provider.cyclicSetProofFor(
                                cyclicBlueId)
                        .outcome();
        NodeProviderOutcome contentOutcome =
                blue.getNodeProvider()
                        .fetchResultByBlueId(
                                cyclicBlueId)
                        .outcome();

        // Then
        assertEquals(
                NodeProviderOutcome.FOUND,
                proofOutcome);
        assertEquals(
                NodeProviderOutcome.FOUND,
                contentOutcome);
    }

    @Test
    void shouldRejectARepositoryManifestThatDiffersFromItsBinding() {
        // Given
        FixedRepositoryBoundSourceProvider.Binding wrongBinding =
                binding.withRepositoryManifestBlueId(
                        "wrong-fixed-repository-manifest-identity");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FixedRepositoryBoundSourceProvider(
                                repository,
                                blue,
                                FixedRepositoryBoundSourceProviderTest.class
                                        .getClassLoader(),
                                wrongBinding));

        // Then
        assertTrue(
                failure.getMessage()
                        .contains(
                                "manifest identity"));
    }

    @Test
    void shouldRejectMismatchedDeclaredRepositoryArtifactShaAndRestoreProperty() {
        // Given
        String propertyName =
                "coordination.fixed.repository.artifact.sha256";
        String previous =
                System.getProperty(
                        propertyName);
        IllegalStateException failure;

        // When
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

        // Then
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

    private static void writeAudit(
            FixedRepositoryBoundSourceProvider.CatalogAudit audit)
            throws IOException {
        Map<String, Object> report =
                new LinkedHashMap<String, Object>();
        report.put(
                "schema",
                "blue.coordination/fixed-repository-catalog-audit/1.0");
        report.put(
                "status",
                audit.failed() == 0
                        && audit.verified() == audit.total()
                        ? "verified"
                        : "failed");
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
                "repositoryManifestSha256",
                repositoryManifestSha256());
        report.put(
                "repositoryCommit",
                REPOSITORY_COMMIT);
        report.put(
                "repositoryArtifactSha256",
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

    private static String repositoryCoordinate() {
        return REPOSITORY_BASE_COORDINATE
                + (System.getenv("CI") == null
                ? "-SNAPSHOT"
                : "");
    }

    private static String repositoryManifestSha256()
            throws IOException {
        Path source =
                Paths.get(
                                System.getProperty(
                                        "user.dir"))
                        .resolve(
                                "../blue-repository-java/"
                                        + "src/main/resources/blue/repo/"
                                        + "manifest.json")
                        .normalize();
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
        digest.update(
                Files.readAllBytes(
                        source));
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
                    "outcome",
                    entry.outcome().name());
            serialized.put(
                    "diagnostic",
                    entry.diagnostic());
            serialized.put(
                    "sourceEnvironmentIdentity",
                    entry.sourceEnvironmentIdentity());
            serialized.put(
                    "cyclicMember",
                    entry.cyclicMember());
            entries.add(serialized);
        }
        assertFalse(entries.isEmpty());
        return entries;
    }
}
