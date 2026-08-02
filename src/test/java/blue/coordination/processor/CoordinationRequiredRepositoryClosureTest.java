package blue.coordination.processor;

import blue.language.utils.UncheckedObjectMapper;
import blue.repo.mandate.DocumentResponderMandate;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.OperationMandate;
import blue.repo.myos.MyOSDocumentBootstrapMandate;
import blue.repo.myos.MyOSDocumentOperationMandate;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationRequiredRepositoryClosureTest {
    private static final Path PROJECT_DIRECTORY =
            Paths.get(
                    System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize();

    @Test
    void shouldExposeCanonicalImmutableTransitiveClosure() {
        // given
        List<CoordinationRequiredRepositoryClosure.Entry> entries =
                CoordinationRequiredRepositoryClosure.entries();
        Set<String> seenBlueIds =
                new HashSet<String>();
        List<String> canonicalKeys =
                new ArrayList<String>();
        int rootCount = 0;

        // when
        for (CoordinationRequiredRepositoryClosure.Entry entry : entries) {
            canonicalKeys.add(
                    entry.qualifiedName()
                            + "\u0000"
                            + entry.blueId());
            assertTrue(
                    seenBlueIds.add(
                            entry.blueId()),
                    entry.blueId());
            assertTrue(
                    entry.sourceResourceSha256()
                            .matches("[0-9a-f]{64}"),
                    entry.qualifiedName());
            for (String reference : entry.directReferences()) {
                assertTrue(
                        CoordinationRequiredRepositoryClosure
                                .containsBlueId(
                                        reference),
                        entry.qualifiedName()
                                + " -> "
                                + reference);
            }
            if (entry.root()) {
                rootCount++;
            }
        }
        List<String> sorted =
                new ArrayList<String>(
                        canonicalKeys);
        java.util.Collections.sort(
                sorted);

        // then
        assertFalse(
                entries.isEmpty());
        assertEquals(
                sorted,
                canonicalKeys);
        assertTrue(
                rootCount > 0);
        assertTrue(
                rootCount < entries.size(),
                "The generated inventory must contain transitive members");
        assertThrows(
                UnsupportedOperationException.class,
                entries::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> entries.get(0)
                        .directReferences()
                        .clear());
    }

    @Test
    void shouldRecordEveryRuntimeRegistrationAsAnExplicitRoot()
            throws IOException {
        // given
        Path registrations =
                PROJECT_DIRECTORY.resolve(
                        "src/test/resources/coordination/conformance/"
                                + "runtime-registrations.yaml");
        List<String> lines =
                Files.readAllLines(
                        registrations,
                        StandardCharsets.UTF_8);
        List<String> qualifiedNames =
                new ArrayList<String>();
        for (String line : lines) {
            String trimmed =
                    line.trim();
            if (trimmed.startsWith(
                    "- type:")) {
                qualifiedNames.add(
                        trimmed.substring(
                                "- type:".length())
                                .trim());
            }
        }

        // when
        int explicitRoots = 0;
        for (String qualifiedName : qualifiedNames) {
            for (CoordinationRequiredRepositoryClosure.Entry entry
                    : CoordinationRequiredRepositoryClosure.entries()) {
                if (qualifiedName.equals(
                        entry.qualifiedName())
                        && entry.root()) {
                    explicitRoots++;
                    break;
                }
            }
        }

        // then
        assertFalse(
                qualifiedNames.isEmpty());
        assertEquals(
                qualifiedNames.size(),
                explicitRoots);
    }

    @Test
    void shouldBindGeneratedReportToImmutableHeadEvidence()
            throws IOException {
        // given
        Path report =
                PROJECT_DIRECTORY.resolve(
                        "build/reports/coordination-release/"
                                + "required-repository-closure-generation.json");

        // when
        JsonNode evidence =
                UncheckedObjectMapper.JSON_MAPPER
                        .readTree(
                                Files.readAllBytes(
                                        report));

        // then
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_HEAD_COMMIT,
                evidence.path("repository")
                        .path("headCommit")
                        .asText());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_SOURCE_STATE_IDENTITY,
                evidence.path("repository")
                        .path("sourceStateIdentity")
                        .asText());
        assertTrue(
                evidence.path("repository")
                        .path("sourceMatchesHead")
                        .asBoolean());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .CLOSURE_IDENTITY,
                evidence.path("closure")
                        .path("identity")
                        .asText());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .RUNTIME_REGISTRATIONS_IDENTITY,
                evidence.path("usage")
                        .path("runtimeRegistrations")
                        .path("identity")
                        .asText());
        assertEquals(
                Integer.parseInt(
                        CoordinationRequiredRepositoryClosure
                                .RUNTIME_REGISTRATION_COUNT),
                evidence.path("usage")
                        .path("runtimeRegistrations")
                        .path("total")
                        .asInt());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .entries()
                        .size(),
                evidence.path("closure")
                        .path("total")
                        .asInt());
        assertEquals(
                evidence.path("externalReferences")
                        .path("total")
                        .asInt(),
                evidence.path("externalReferences")
                        .path("resolved")
                        .asInt());
        assertEquals(
                0,
                evidence.path("externalReferences")
                        .path("unresolved")
                        .asInt());
        assertEquals(
                "evidence-only-not-installed",
                evidence.path("historicalEnvironment")
                        .path("runtimeRoleRegistryUse")
                        .asText());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .HISTORICAL_REGISTRY_EVIDENCE_IDENTITY,
                evidence.path("historicalRegistryEvidence")
                        .path("identity")
                        .asText());
        assertEquals(
                Integer.parseInt(
                        CoordinationRequiredRepositoryClosure
                                .HISTORICAL_REGISTRY_EVIDENCE_COUNT),
                evidence.path("historicalRegistryEvidence")
                        .path("total")
                        .asInt());
        assertFalse(
                evidence.path("historicalRegistryEvidence")
                        .path("activeRuntimeUse")
                        .asBoolean());
        assertEquals(
                "proved-alias-table-only-delta",
                evidence.path("historicalTransformReplay")
                        .path("status")
                        .asText());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .TRANSFORM_EQUIVALENCE_IDENTITY,
                evidence.path("historicalTransformReplay")
                        .path("identity")
                        .asText());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .HISTORICAL_DEFAULT_BLUE_SHA256,
                evidence.path("historicalTransformReplay")
                        .path("historicalDefaultBlueSha256")
                        .asText());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .CORE_SOURCE_EQUIVALENCE_IDENTITY,
                evidence.path("historicalCoreReplay")
                        .path("identity")
                        .asText());
        assertTrue(
                CoordinationRequiredRepositoryClosure
                        .historicalPreprocessingAliases()
                        .size()
                        > CoordinationRequiredRepositoryClosure
                        .historicalEvidenceEntries()
                        .size());
        for (CoordinationRequiredRepositoryClosure
                .HistoricalEvidenceEntry entry
                : CoordinationRequiredRepositoryClosure
                .historicalEvidenceEntries()) {
            assertEquals(
                    entry.sourceResourceSha256(),
                    sha256(
                            entry.sourceBytes()),
                    entry.path());
            assertEquals(
                    entry.blueId(),
                    CoordinationRequiredRepositoryClosure
                            .historicalPreprocessingAliases()
                            .get(
                                    entry.alias()));
        }
    }

    @Test
    void shouldIncludeMandateBaseAndSupportedSubtypeEvidence() {
        // given
        String[] requiredMandateBlueIds = {
                Mandate.blueId(),
                OperationMandate.blueId(),
                DocumentResponderMandate.blueId(),
                MyOSDocumentOperationMandate.blueId(),
                MyOSDocumentBootstrapMandate.blueId()
        };

        // when
        List<CoordinationRequiredRepositoryClosure.Entry> mandateEntries =
                new ArrayList<CoordinationRequiredRepositoryClosure.Entry>();
        for (String blueId : requiredMandateBlueIds) {
            mandateEntries.add(
                    CoordinationRequiredRepositoryClosure.entry(
                            blueId));
        }

        // then
        assertEquals(
                requiredMandateBlueIds.length,
                mandateEntries.size());
        for (CoordinationRequiredRepositoryClosure.Entry entry
                : mandateEntries) {
            assertNotNull(
                    entry);
            assertTrue(
                    entry.sourceResourceSha256()
                            .matches("[0-9a-f]{64}"));
        }
    }

    private static String sha256(
            byte[] bytes) {
        try {
            byte[] digest =
                    MessageDigest.getInstance(
                            "SHA-256")
                            .digest(
                                    bytes);
            StringBuilder result =
                    new StringBuilder();
            for (byte value : digest) {
                result.append(
                        String.format(
                                java.util.Locale.ROOT,
                                "%02x",
                                value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    impossible);
        }
    }
}
