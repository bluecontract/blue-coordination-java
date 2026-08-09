package blue.coordination.basic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fails when the active compact lane regresses to generic host slicing. */
final class ArchitectureGuardTest {
    private static final List<String> FORBIDDEN = List.of(
            "Coordination" + "DocumentSplitter",
            "ExactNode" + "GraphFragments",
            "Coordination" + "FragmentInventory",
            "Coordination" + "FragmentStore",
            "Coordination" + "SubscriptionProjector",
            "data" + "Only(",
            "materializeEmbedded" + "Revisions(",
            ".to" + "Map()",
            "rehydrate" + "(");

    @Test
    void activeBasicLaneContainsNoGenericFragmentOrProjectionPipeline()
            throws IOException {
        Path source = Path.of(System.getProperty("user.dir"))
                .resolve("src/basicTest/java");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(source)) {
            for (Path path : paths
                    .filter(candidate -> candidate.toString()
                            .endsWith(".java"))
                    .sorted()
                    .toList()) {
                if (path.getFileName().toString()
                        .equals("ArchitectureGuardTest.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(
                        path, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    for (String forbidden : FORBIDDEN) {
                        if (lines.get(index).contains(forbidden)) {
                            violations.add(source.relativize(path)
                                    + ":" + (index + 1)
                                    + " contains " + forbidden);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "Forbidden active basicTest architecture: "
                        + violations);
    }
    @Test
    void compactEngineStaysWithinAHardComplexityBudget() throws IOException {
        Path engine = Path.of(System.getProperty("user.dir"))
                .resolve("src/basicTest/java/blue/coordination/basic/engine");
        long files;
        long lines = 0L;
        try (var paths = Files.list(engine)) {
            List<Path> sources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            files = sources.size();
            for (Path source : sources) {
                try (var sourceLines = Files.lines(
                        source, StandardCharsets.UTF_8)) {
                    lines += sourceLines.count();
                }
            }
        }
        assertTrue(files <= 35L,
                "compact engine class budget exceeded: " + files);
        assertTrue(lines <= 5_200L,
                "compact engine line budget exceeded: " + lines);
    }

}
