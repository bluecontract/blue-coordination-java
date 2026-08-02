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
        // given
        Class<?> languageType = Blue.class;
        Class<?> bexType = BexEngine.class;
        Class<?> repositoryType = BlueRepository.class;

        // when
        Path languageLocation = codeSourceLocation(languageType);
        Path bexLocation = codeSourceLocation(bexType);
        Path repositoryLocation = codeSourceLocation(repositoryType);

        // then
        assertLocalBuild(
                languageType,
                languageLocation,
                "blue-language-java");
        assertLocalBuild(
                bexType,
                bexLocation,
                "blue-bex-java");
        Path immutableLocalRepository =
                Paths.get(
                                System.getProperty(
                                        "user.dir"))
                        .toAbsolutePath()
                        .normalize()
                        .resolve(
                                ".gradle/immutable-local-repository/"
                                        + CoordinationRequiredRepositoryClosure
                                        .REPOSITORY_HEAD_COMMIT)
                        .normalize()
                        .toRealPath();
        assertLocalBuildRoot(
                repositoryType,
                repositoryLocation,
                immutableLocalRepository,
                "the exact immutable local blue-repository-java HEAD");
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
