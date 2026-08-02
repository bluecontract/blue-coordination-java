package blue.coordination.processor;

import blue.language.Blue;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed smoke for the exact local Repository/Language integration.
 *
 * <p>The required inventory is generated from actual Coordination usage and
 * immutable manifest edges. No test-owned type allow list can silently drift
 * away from production, public API, or fixture usage.</p>
 */
final class LocalFixedRepositoryCompatibilityTest {
    @Test
    void shouldExposeTheExactFixedRepositoryManifestIdentity() {
        // given
        BlueRepository repository =
                BlueRepository.latest();

        // when
        String version =
                repository.repositoryVersion();
        String versionBlueId =
                repository.repositoryVersionBlueId();

        // then
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_VERSION,
                version);
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_MANIFEST_BLUE_ID,
                versionBlueId);
        assertEquals(
                "exact-local-immutable-git-head",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_SOURCE_PROVENANCE);
        assertEquals(
                "true",
                CoordinationRequiredRepositoryClosure
                        .REPOSITORY_SOURCE_MATCHES_HEAD);
    }

    @Test
    void shouldVerifyRequiredClosureOrExposeExactIncompatibilities() {
        // given
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                new Blue();
        FixedRepositoryBoundSourceProvider provider =
                FixedRepositoryBoundSourceProvider.inspect(
                        repository,
                        LocalFixedRepositoryCompatibilityTest.class
                                .getClassLoader(),
                        FixedRepositoryBoundSourceProvider
                                .releaseBinding(
                                        repository));

        // when
        FixedRepositoryBoundSourceProvider.RequiredClosureAudit audit;
        try {
            audit =
                    provider.requiredClosureAudit();
        } finally {
            provider.close();
            blue.close();
        }

        // then
        assertFalse(
                CoordinationRequiredRepositoryClosure
                        .entries()
                        .isEmpty());
        assertEquals(
                CoordinationRequiredRepositoryClosure
                        .entries()
                        .size(),
                audit.total());
        if (CoordinationRequiredRepositoryClosure
                .REPOSITORY_MANIFEST_BLUE_ID
                .equals(
                        repository.repositoryVersionBlueId())) {
            assertEquals(
                    audit.total(),
                    audit.audited());
            assertEquals(
                    audit.total(),
                    audit.verified()
                            + audit.missing()
                            + audit.invalidEvidence()
                            + audit.unavailable());
            if (audit.eligible()) {
                assertEquals(
                        audit.total(),
                        audit.verified());
                assertTrue(
                        audit.incompatibilityProofs()
                                .isEmpty());
            } else {
                assertFalse(
                        audit.incompatibilityProofs()
                                .isEmpty(),
                        incompatibilityMessage(
                                audit));
                assertEquals(
                        audit.total() - audit.verified(),
                        audit.incompatibilityProofs()
                                .size());
                for (FixedRepositoryBoundSourceProvider
                        .IncompatibilityProof proof
                        : audit.incompatibilityProofs()) {
                    assertTrue(
                            proof.sourceResourceSha256()
                                    .matches("[0-9a-f]{64}"));
                    assertTrue(
                            proof.exactEnvironmentAttempted()
                                    .matches("sha256:[0-9a-f]{64}"));
                    assertTrue(
                            proof.calculatedIdentity()
                                    .matches(
                                            "[1-9A-HJ-NP-Za-km-z]+"
                                                    + "(#[0-9]+)?"));
                    assertFalse(
                            proof.earliestFailingPath()
                                    .trim()
                                    .isEmpty());
                    assertFalse(
                            proof.diagnostic()
                                    .trim()
                                    .isEmpty());
                }
            }
        } else {
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
                    audit.incompatibilityProofs()
                            .size());
            assertTrue(
                    audit.selectedReleaseMismatch()
                            .contains(
                                    "differs from exact immutable "
                                            + "HEAD closure"));
        }
    }

    private static String incompatibilityMessage(
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
}
