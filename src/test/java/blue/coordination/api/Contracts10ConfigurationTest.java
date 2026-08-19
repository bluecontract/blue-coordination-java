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
        Contracts10Configuration configuration =
                new Contracts10Configuration(
                        LANGUAGE_ID,
                        CONTRACTS_ID,
                        new LinkedHashSet<>(List.of(
                                DocumentId.of("z-root"),
                                DocumentId.of("a-root"))));

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
        assertThrows(IllegalArgumentException.class,
                () -> new Contracts10Configuration(
                        "language-latest", CONTRACTS_ID,
                        Set.of(DocumentId.of("root"))));
        assertThrows(IllegalArgumentException.class,
                () -> new Contracts10Configuration(
                        LANGUAGE_ID, CONTRACTS_ID, Set.of()));
    }

    @Test
    void publicFactoryOwnsTheOptInContractsRuntime() {
        Contracts10Configuration configuration =
                new Contracts10Configuration(
                        LANGUAGE_ID,
                        CONTRACTS_ID,
                        Set.of(DocumentId.of("root")));

        try (CoordinationEngine engine =
                CoordinationEngine.inMemoryContracts10(configuration)) {
            assertEquals(
                    new Timeline("root-timeline", "root-actor"),
                    engine.registerTimeline(
                            "root-timeline", "root-actor"));
        }
    }
}
