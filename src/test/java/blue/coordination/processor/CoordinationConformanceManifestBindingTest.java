package blue.coordination.processor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoordinationConformanceManifestBindingTest {
    private static final Path PROJECT_DIRECTORY =
            Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize();

    @Test
    void shouldBindConformancePackageToExactPortableGasManifestBytes()
            throws Exception {
        // Given
        String manifest =
                read(
                        "src/test/resources/coordination/conformance/"
                                + "manifest.yaml");
        Path portableGas =
                PROJECT_DIRECTORY.resolve(
                        "src/main/resources/blue/coordination/processor/"
                                + "coordination-gas-1.0.yaml");

        // When
        String declared =
                scalar(
                        manifest,
                        "portableGasRawSha256");
        String observed =
                sha256(Files.readAllBytes(portableGas));

        // Then
        assertEquals(declared, observed);
    }

    @Test
    void shouldBindConformancePackageToExactHostQuotaManifestBytes()
            throws Exception {
        // Given
        String manifest =
                read(
                        "src/test/resources/coordination/conformance/"
                                + "manifest.yaml");
        Path hostQuota =
                PROJECT_DIRECTORY.resolve(
                        "src/main/resources/blue/coordination/processor/"
                                + "coordination-host-quotas-1.0.yaml");

        // When
        String declared =
                scalar(
                        manifest,
                        "hostQuotaRawSha256");
        String observed =
                sha256(Files.readAllBytes(hostQuota));

        // Then
        assertEquals(declared, observed);
    }

    private static String read(String relative)
            throws Exception {
        return new String(
                Files.readAllBytes(
                        PROJECT_DIRECTORY.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String scalar(
            String yaml,
            String key) {
        List<String> lines =
                java.util.Arrays.asList(
                        yaml.split("\\r?\\n"));
        String prefix = key + ":";
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(
                        prefix.length()).trim();
            }
        }
        throw new IllegalArgumentException(
                "Missing manifest key: " + key);
    }

    private static String sha256(byte[] bytes)
            throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(bytes);
        StringBuilder value =
                new StringBuilder(
                        digest.length * 2);
        for (byte item : digest) {
            value.append(
                    String.format(
                            Locale.ROOT,
                            "%02x",
                            item & 0xff));
        }
        return value.toString();
    }
}
