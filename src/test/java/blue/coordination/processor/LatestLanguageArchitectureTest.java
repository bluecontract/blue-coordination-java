package blue.coordination.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed source and lock checks for the current focused Language and
 * modular BEX dependency boundary.
 */
final class LatestLanguageArchitectureTest {

    private static final Path PRODUCTION_ROOT =
            Paths.get("src", "main", "java");
    private static final Path SIBLING_LOCK =
            Paths.get("gradle", "blue-sibling-lock.properties");
    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+([^;]+);");
    private static final Pattern IMPORT_DECLARATION =
            Pattern.compile("(?m)^\\s*import\\s+([^;]+);");

    @Test
    void shouldKeepEveryProductionClassOutsideLanguageNamespaces()
            throws IOException {
        // given
        List<Path> sources = javaSources(PRODUCTION_ROOT);

        // when
        List<String> violations = new ArrayList<String>();
        for (Path source : sources) {
            String content = read(source);
            Matcher declaration = PACKAGE_DECLARATION.matcher(content);
            if (declaration.find()
                    && declaration.group(1).startsWith("blue.language")) {
                violations.add(
                        portable(source) + " -> " + declaration.group(1));
            }
        }

        // then
        assertTrue(
                violations.isEmpty(),
                "Coordination production classes must not occupy a "
                        + "Language namespace:\n"
                        + String.join("\n", violations));
    }

    @Test
    void shouldUseOnlyCurrentLanguageImportsInProduction()
            throws IOException {
        // given
        List<String> forbiddenPrefixes = Arrays.asList(
                "blue.language.utils.",
                "blue.language.processor.Coordination");
        List<String> forbiddenExact = Arrays.asList(
                "blue.language.Blue",
                "blue.language.NodeProvider");

        // when
        List<String> violations = new ArrayList<String>();
        for (Path source : javaSources(PRODUCTION_ROOT)) {
            Matcher imported = IMPORT_DECLARATION.matcher(read(source));
            while (imported.find()) {
                String type = imported.group(1);
                if (forbiddenExact.contains(type)
                        || startsWithOneOf(type, forbiddenPrefixes)) {
                    violations.add(portable(source) + " -> " + type);
                }
            }
        }

        // then
        assertTrue(
                violations.isEmpty(),
                "Legacy Language imports remain:\n"
                        + String.join("\n", violations));
    }

    @Test
    void shouldUseOnlyModularBexApisInProduction()
            throws IOException {
        // given
        Pattern legacyBuilderAdapter =
                Pattern.compile("\\.blue\\s*\\(");
        List<String> forbiddenExact = Arrays.asList(
                "blue.bex.BexEngine",
                "blue.bex.BexNode",
                "blue.bex.BexResult");

        // when
        List<String> violations = new ArrayList<String>();
        for (Path source : javaSources(PRODUCTION_ROOT)) {
            String content = read(source);
            Matcher imported = IMPORT_DECLARATION.matcher(content);
            while (imported.find()) {
                if (forbiddenExact.contains(imported.group(1))) {
                    violations.add(
                            portable(source) + " -> " + imported.group(1));
                }
            }
            if (content.contains("BexEngine")
                    && legacyBuilderAdapter.matcher(content).find()) {
                violations.add(
                        portable(source)
                                + " -> removed BexEngine.Builder.blue adapter");
            }
        }

        // then
        assertTrue(
                violations.isEmpty(),
                "Pre-modular BEX adapters remain:\n"
                        + String.join("\n", violations));
    }

    @Test
    void shouldLockExactCurrentSiblingCommitsAndPackageIdentities()
            throws IOException {
        // given
        Properties lock = new Properties();
        try (InputStream input = Files.newInputStream(SIBLING_LOCK)) {
            lock.load(input);
        }

        // when
        List<String> actual = Arrays.asList(
                lock.getProperty("blueLanguageCommit"),
                lock.getProperty(
                        "blueLanguageVerifiedImplementationCommit"),
                lock.getProperty("blueBexCommit"),
                lock.getProperty("blueRepositoryCommit"),
                lock.getProperty("blueContractsCoreJarSha256"),
                lock.getProperty("blueBexWorkingReceiptSha256"),
                lock.getProperty("blueRepositoryJarSha256"),
                lock.getProperty("blueLanguageRegistrySha256"),
                lock.getProperty("blueLanguageFixturesSha256"),
                lock.getProperty("blueContractsRegistrySha256"),
                lock.getProperty("blueContractsFixturesSha256"),
                lock.getProperty("blueContractsGasSha256"),
                lock.getProperty("processEmbeddedBlueId"),
                lock.getProperty("blueBexRuntimeRegistrySha256"),
                lock.getProperty("blueBexGasManifestSha256"),
                lock.getProperty("blueBexFixturePackageSha256"));

        // then
        assertEquals(
                Arrays.asList(
                        "c3d58561220e6de6be6e302cb16799c1a1b5159f",
                        "c3d58561220e6de6be6e302cb16799c1a1b5159f",
                        "09f89f0b63a84007fcf7ae13b7439bc24dbb1d03",
                        "63be6b7d8d2752b5a8c90f38e672859e9b3949a1",
                        "5845c6bead274dffd8d22afcb323f7cdf6e53b5656e0070bd241a1a660516280",
                        "d64f99979e18a50f379389ca15579d6cad3b2e9e1238fecce599474d3d371c02",
                        "da6b6e1d2bc6e3e2892d707b46f064d9419a9fe389312cb2f003c81a5dcb8907",
                        "b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e",
                        "44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55",
                        "46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1",
                        "16392301655431695df6a7cc142a7e388e426c382bf4e3c5f06ddfafb8efecdc",
                        "88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5",
                        "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e",
                        "23d282ec1c0bb016263922b1b49c369fdd537efdcf23e005eceeb888d7763fe1",
                        "41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d",
                        "a1b7bb2b3687389409bc9d0aa450c734f7856d2bcb818c95f4d7ecb19095d20e"),
                actual);
    }

    private static boolean startsWithOneOf(
            String value,
            List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> javaSources(
            Path root) throws IOException {
        try (Stream<Path> walked = Files.walk(root)) {
            return walked
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString().endsWith(".java"))
                    .sorted(Comparator.comparing(
                            LatestLanguageArchitectureTest::portable))
                    .collect(Collectors.toList());
        }
    }

    private static String read(Path source) throws IOException {
        return new String(
                Files.readAllBytes(source),
                StandardCharsets.UTF_8);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
