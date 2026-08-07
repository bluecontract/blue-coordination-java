package blue.coordination.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed source convention checks for the complete Java test tree.
 */
final class TestStyleConventionsTest {

    private static final Pattern TEST_ANNOTATION =
            Pattern.compile(
                    "(?m)^\\s*@(?:(?:org\\.junit\\.jupiter\\.api\\.)?Test"
                            + "|(?:org\\.junit\\.jupiter\\.params\\.)?"
                            + "ParameterizedTest)\\b");
    private static final Pattern TEST_METHOD =
            Pattern.compile(
                    "(?m)^\\s*(?:(?:public|protected|private|static|final"
                            + "|synchronized|abstract|native|strictfp)\\s+)*"
                            + "void\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern TOP_LEVEL_TYPE =
            Pattern.compile(
                    "(?m)^(?:public\\s+)?"
                            + "(?:final\\s+|abstract\\s+)?"
                            + "(?:class|interface|enum)\\s+"
                            + "([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final String GIVEN = "// given";
    private static final String WHEN = "// when";
    private static final String THEN = "// then";

    @Test
    void shouldRequireReadableNamesAndOrderedGivenWhenThenSections()
            throws IOException {
        // given
        Path testRoot = Paths.get(
                "src", "test", "java");

        // when
        ScanReport report = scan(testRoot);

        // then
        assertTrue(
                report.javaFileCount > 0,
                "No Java test sources were scanned under "
                        + portable(testRoot));
        assertTrue(
                report.testMethodCount > 0,
                "No @Test or @ParameterizedTest methods were found under "
                        + portable(testRoot));
        assertTrue(
                report.issues.isEmpty(),
                "Test style convention violations:\n"
                        + String.join(
                        "\n", report.issues));
    }

    @Test
    void shouldDocumentEveryProductionType()
            throws IOException {
        // given
        Path productionRoot = Paths.get(
                "src", "main", "java");
        List<String> issues = new ArrayList<>();

        // when
        for (Path source : javaSources(productionRoot)) {
            String content = new String(
                    Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
            Matcher type = TOP_LEVEL_TYPE.matcher(content);
            while (type.find()) {
                int commentEnd = content.lastIndexOf(
                        "*/", type.start());
                int commentStart = commentEnd < 0
                        ? -1
                        : content.lastIndexOf(
                        "/**", commentEnd);
                boolean immediatelyDocumented =
                        commentStart >= 0
                                && commentEnd >= commentStart
                                && content.substring(
                                commentEnd + 2,
                                type.start())
                                .trim()
                                .isEmpty();
                if (!immediatelyDocumented) {
                    issues.add(
                            portable(source)
                                    + ":"
                                    + lineNumber(
                                    content,
                                    type.start())
                                    + ": public type "
                                    + type.group(1)
                                    + " requires class-level Javadoc");
                }
            }
        }

        // then
        assertTrue(
                issues.isEmpty(),
                "Production documentation convention violations:\n"
                        + String.join("\n", issues));
    }

    private static ScanReport scan(
            Path testRoot) throws IOException {
        if (!Files.isDirectory(testRoot)) {
            return new ScanReport(
                    0,
                    0,
                    Collections.singletonList(
                            portable(testRoot)
                                    + ": test source root is missing"));
        }

        List<Path> sources = javaSources(testRoot);

        List<String> issues =
                new ArrayList<>();
        int testMethodCount = 0;
        for (Path source : sources) {
            String content = new String(
                    Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
            List<Integer> annotations =
                    annotationOffsets(content);
            testMethodCount +=
                    annotations.size();
            String displayPath =
                    portable(testRoot)
                            + "/"
                            + portable(
                            testRoot.relativize(
                                    source));
            for (int index = 0;
                 index < annotations.size();
                 index++) {
                int start =
                        annotations.get(index);
                int end = index + 1
                        < annotations.size()
                        ? annotations.get(index + 1)
                        : content.length();
                inspectTestSlice(
                        displayPath,
                        content,
                        start,
                        end,
                        issues);
            }
        }
        return new ScanReport(
                sources.size(),
                testMethodCount,
                issues);
    }

    private static List<Path> javaSources(
            Path root) throws IOException {
        try (Stream<Path> walked =
                     Files.walk(root)) {
            return walked
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".java"))
                    .sorted(Comparator.comparing(
                            TestStyleConventionsTest
                                    ::portable))
                    .collect(Collectors.toList());
        }
    }

    private static List<Integer>
    annotationOffsets(
            String content) {
        List<Integer> offsets =
                new ArrayList<>();
        Matcher matcher =
                TEST_ANNOTATION.matcher(content);
        while (matcher.find()) {
            offsets.add(matcher.start());
        }
        return offsets;
    }

    private static void inspectTestSlice(
            String displayPath,
            String content,
            int start,
            int end,
            List<String> issues) {
        String slice =
                content.substring(start, end);
        int annotationLine =
                lineNumber(content, start);
        Matcher method =
                TEST_METHOD.matcher(slice);
        if (!method.find()) {
            issues.add(
                    displayPath + ":"
                            + annotationLine
                            + ": test annotation has no following void "
                            + "method before the next test annotation");
            return;
        }

        String methodName =
                method.group(1);
        int methodLine = lineNumber(
                content,
                start + method.start(1));
        if (!methodName.startsWith("should")) {
            issues.add(
                    displayPath + ":"
                            + methodLine + ": "
                            + methodName
                            + " must start with 'should'");
        }

        int given = slice.indexOf(GIVEN);
        int when = slice.indexOf(WHEN);
        int then = slice.indexOf(THEN);
        int givenCount = countOccurrences(
                slice, GIVEN);
        int whenCount = countOccurrences(
                slice, WHEN);
        int thenCount = countOccurrences(
                slice, THEN);
        if (given < 0
                || when < 0
                || then < 0) {
            List<String> missing =
                    new ArrayList<>();
            if (given < 0) {
                missing.add(GIVEN);
            }
            if (when < 0) {
                missing.add(WHEN);
            }
            if (then < 0) {
                missing.add(THEN);
            }
            issues.add(
                    displayPath + ":"
                            + methodLine + ": "
                            + methodName
                            + " is missing "
                            + String.join(
                            ", ", missing));
        } else if (!(given < when
                && when < then)) {
            issues.add(
                    displayPath + ":"
                            + methodLine + ": "
                            + methodName
                            + " must order "
                            + GIVEN + ", "
                            + WHEN + ", "
                            + THEN);
        } else if (givenCount != 1
                || whenCount != 1
                || thenCount != 1) {
            issues.add(
                    displayPath + ":"
                            + methodLine + ": "
                            + methodName
                            + " must contain exactly one "
                            + GIVEN + ", "
                            + WHEN + ", and "
                            + THEN + " section; found "
                            + givenCount + "/"
                            + whenCount + "/"
                            + thenCount);
        }
    }

    private static int countOccurrences(
            String value,
            String target) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(
                target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private static int lineNumber(
            String content,
            int offset) {
        int line = 1;
        for (int index = 0;
             index < offset;
             index++) {
            if (content.charAt(index)
                    == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String portable(
            Path path) {
        return path.toString()
                .replace('\\', '/');
    }

    private static final class ScanReport {
        private final int javaFileCount;
        private final int testMethodCount;
        private final List<String> issues;

        private ScanReport(
                int javaFileCount,
                int testMethodCount,
                List<String> issues) {
            this.javaFileCount =
                    javaFileCount;
            this.testMethodCount =
                    testMethodCount;
            this.issues =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    issues));
        }
    }
}
