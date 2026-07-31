package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.language.Blue;
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
    void shouldLoadEveryBlueDependencyFromItsSiblingCompositeBuild()
            throws IOException, URISyntaxException {
        // Given
        Class<?> languageType = Blue.class;
        Class<?> bexType = BexEngine.class;
        Class<?> repositoryType = BlueRepository.class;

        // When
        Path languageLocation = codeSourceLocation(languageType);
        Path bexLocation = codeSourceLocation(bexType);
        Path repositoryLocation = codeSourceLocation(repositoryType);

        // Then
        assertLocalBuild(
                languageType,
                languageLocation,
                "blue-language-java");
        assertLocalBuild(
                bexType,
                bexLocation,
                "blue-bex-java");
        assertLocalBuild(
                repositoryType,
                repositoryLocation,
                "blue-repository-java");
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
        assertTrue(
                actual.startsWith(expectedSibling),
                type.getName() + " did not load from ../" + siblingName
                        + ": " + actual);
    }
}
