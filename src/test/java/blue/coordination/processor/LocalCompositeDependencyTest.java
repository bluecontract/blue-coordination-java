package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.language.runtime.BlueLanguage;
import blue.repo.BlueRepository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCompositeDependencyTest {
    @Test
    void shouldUsePublishedLanguageWithLocalBexAndRepository()
            throws IOException, URISyntaxException {
        // given
        Class<?> languageType = BlueLanguage.class;
        Class<?> bexType = BexEngine.class;
        Class<?> repositoryType = BlueRepository.class;

        // when
        Path languageLocation = codeSourceLocation(languageType);
        Path bexLocation = codeSourceLocation(bexType);
        Path repositoryLocation = codeSourceLocation(repositoryType);

        // then
        assertPublishedLanguage(languageType, languageLocation);
        assertLocalBuild(
                bexType,
                bexLocation,
                "blue-bex-java");
        Path lockedLocalRepositoryArtifacts =
                Paths.get(
                                System.getProperty(
                                        "user.dir"))
                        .toAbsolutePath()
                        .normalize()
                        .resolve(
                                ".gradle/current-local-artifacts")
                        .normalize();
        assertLocalBuildRoot(
                repositoryType,
                repositoryLocation,
                lockedLocalRepositoryArtifacts,
                "the exact digest-locked JAR materialized from the local "
                        + "blue-repository-java HEAD");
    }

    private static void assertPublishedLanguage(
            Class<?> type,
            Path actual) {
        String normalized = actual.toString().replace('\\', '/');
        assertTrue(
                normalized.contains(
                        "/caches/modules-2/files-2.1/blue.language/"
                                + "blue-language-core/3.1.0-rc.20/"),
                type.getName()
                        + " did not load from published Language 3.1.0-rc.20: "
                        + actual);
        assertTrue(
                !normalized.contains("/blue-language-java/blue-language-core/"),
                type.getName()
                        + " unexpectedly loaded from the adjacent Language checkout: "
                        + actual);
    }

    private static Path codeSourceLocation(
            Class<?> type)
            throws IOException, URISyntaxException {
        URL location =
                type.getProtectionDomain()
                        .getCodeSource()
                        .getLocation();
        assertNotNull(
                location,
                type.getName()
                        + " has no code-source location");
        assertEquals(
                "file",
                location.getProtocol(),
                type.getName()
                        + " has a non-file code-source location");
        return Paths.get(location.toURI()).toRealPath();
    }

    private static void assertLocalBuild(
            Class<?> type,
            Path actual,
            String siblingName)
            throws IOException {
        Path expectedSibling = Paths.get(
                        System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve("../" + siblingName)
                .normalize()
                .toRealPath();
        assertLocalBuildRoot(
                type,
                actual,
                expectedSibling,
                "../" + siblingName);
    }

    private static void assertLocalBuildRoot(
            Class<?> type,
            Path actual,
            Path expectedRoot,
            String sourceDescription) {
        assertTrue(
                actual.startsWith(
                        expectedRoot),
                type.getName() + " did not load from "
                        + sourceDescription
                        + ": " + actual);
    }
}
