package blue.coordination.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Contracts10ConfigurationTest {
    private static final String LANGUAGE_ID =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CONTRACTS_ID =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void retainsCanonicalPublicRootsAndExplicitArtifactIdentities() {
        // given
        Set<DocumentId> publicRoots = new LinkedHashSet<>(List.of(
                DocumentId.of("z-root"),
                DocumentId.of("a-root")));

        // when
        Contracts10Configuration configuration =
                new Contracts10Configuration(
                        LANGUAGE_ID,
                        CONTRACTS_ID,
                        publicRoots);

        // then
        assertEquals(LANGUAGE_ID,
                configuration.blueLanguageSpecificationIdentity());
        assertEquals(CONTRACTS_ID,
                configuration.contractsSpecificationIdentity());
        assertEquals(List.of(
                        DocumentId.of("a-root"),
                        DocumentId.of("z-root")),
                List.copyOf(configuration.publicRootDocumentIds()));
    }

    @Test
    void rejectsPlaceholderOrMissingReleaseInputs() {
        // given
        Set<DocumentId> publicRoots = Set.of(DocumentId.of("root"));

        // when
        Runnable placeholderRelease = () -> new Contracts10Configuration(
                "language-latest", CONTRACTS_ID, publicRoots);
        Runnable missingRoots = () -> new Contracts10Configuration(
                LANGUAGE_ID, CONTRACTS_ID, Set.of());

        // then
        assertThrows(IllegalArgumentException.class, placeholderRelease::run);
        assertThrows(IllegalArgumentException.class, missingRoots::run);
    }

    @Test
    void publicFactoryOwnsTheOptInContractsRuntime() {
        // given
        Contracts10Configuration configuration =
                new Contracts10Configuration(
                        LANGUAGE_ID,
                        CONTRACTS_ID,
                        Set.of(DocumentId.of("root")));
        try (CoordinationEngine engine =
                CoordinationEngine.inMemoryContracts10(configuration)) {
            // when
            Timeline registered = engine.registerTimeline(
                    "root-timeline", "root-actor");

            // then
            assertEquals(
                    new Timeline("root-timeline", "root-actor"),
                    registered);
        }
    }
}
