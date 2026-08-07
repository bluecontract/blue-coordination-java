package blue.coordination.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the current-stack documentation remains complete and bound. */
final class LatestLanguageDocumentationTest {

    @Test
    void shouldKeepEveryRequiredCurrentStackDocumentPresent() {
        // given
        List<String> required = Arrays.asList(
                "README.md",
                "START-HERE.md",
                "docs/architecture/runtime-registration.md",
                "docs/architecture/subscription-projection-and-indexed-delivery.md",
                "docs/architecture/fragmentation-and-reconstruction.md",
                "docs/architecture/embedded-collections.md",
                "docs/architecture/one-root-processing.md",
                "docs/architecture/quality-exceptions.md",
                "docs/guides/reusing-timelines-across-process-occurrences.md",
                "docs/guides/adding-a-channel.md",
                "docs/guides/adding-a-workflow-step.md",
                "docs/guides/migrating-from-the-previous-language-api.md",
                "docs/examples/nested-agreement-lesson-cancellation.md",
                "docs/examples/nested-agreement-lesson-cancellation-trace.json",
                "docs/examples/nested-agreement-lesson-cancellation-trace.md");

        // when
        List<String> missing = new ArrayList<String>();
        for (String value : required) {
            if (!Files.isRegularFile(Paths.get(value))) {
                missing.add(value);
            }
        }

        // then
        assertTrue(
                missing.isEmpty(),
                "Required current-stack documents are missing: " + missing);
    }

    @Test
    void shouldExplainEveryEmbeddedCollectionIdentityBoundary()
            throws IOException {
        // given
        Path guide = Paths.get(
                "docs", "architecture", "embedded-collections.md");

        // when
        String text = read(guide);

        // then
        assertContainsAll(
                text,
                "Stable keys, not positions",
                "Not a wildcard",
                "same child BlueId",
                "same Timeline",
                "added by event",
                "Channel-specific targeting",
                "Root-only events",
                "Slicing preserves identity");
    }

    @Test
    void shouldBindTheNestedWalkthroughToObservedTestEvidence()
            throws IOException {
        // given
        Path example = Paths.get(
                "docs", "examples",
                "nested-agreement-lesson-cancellation.md");
        Path generated = Paths.get(
                "docs", "examples",
                "nested-agreement-lesson-cancellation-trace.md");

        // when
        String exampleText = read(example);
        String generatedText = read(generated);

        // then
        assertContainsAll(
                exampleText,
                "CoordinationNestedEmbeddedCollectionFlagshipStructuralTest",
                "CoordinationComplexEmbeddedDeterminismFlagshipTest",
                "publish-nested-agreement-trace.js",
                "It is representation",
                "evidence only.",
                "not evidence that scenarios A–I",
                "### A",
                "### I");
        assertContainsAll(
                generatedText,
                "Structural results and PROCESS runtime results are separate",
                "PROCESS runtime result boundary",
                "notExecuted");
        assertTrue(
                generatedText.startsWith(
                        "<!-- GENERATED FILE: "
                                + "tools/publish-nested-agreement-trace.js -->"),
                "The observed walkthrough must remain generator-owned");
    }

    @Test
    void shouldKeepSameRunReportSchemasAndGeneratorsSourceControlled() {
        // given
        List<String> evidenceContracts = Arrays.asList(
                "tools/generate-latest-language-embedded-collections-reports.js",
                "tools/test-generate-latest-language-embedded-collections-reports.js",
                "tools/capture-latest-language-embedded-collections-blocked-run.js",
                "tools/test-capture-latest-language-embedded-collections-blocked-run.js",
                "tools/publish-nested-agreement-trace.js",
                "tools/test-publish-nested-agreement-trace.js",
                "src/test/resources/coordination/latest-language-embedded-collections-run.schema.json",
                "src/test/resources/coordination/latest-language-embedded-collections-final.schema.json",
                "src/test/resources/coordination/nested-agreement-flagship-trace.schema.json",
                "docs/examples/nested-agreement-lesson-cancellation-trace.json");

        // when
        List<String> missing = new ArrayList<String>();
        for (String value : evidenceContracts) {
            if (!Files.isRegularFile(Paths.get(value))) {
                missing.add(value);
            }
        }

        // then
        assertTrue(
                missing.isEmpty(),
                "Source-controlled report contracts are missing: " + missing);
    }

    private static String read(Path source) throws IOException {
        return new String(
                Files.readAllBytes(source),
                StandardCharsets.UTF_8);
    }

    private static void assertContainsAll(
            String source,
            String... values) {
        List<String> missing = new ArrayList<String>();
        for (String value : values) {
            if (!source.contains(value)) {
                missing.add(value);
            }
        }
        assertTrue(
                missing.isEmpty(),
                "Documentation is missing required concepts: " + missing);
    }
}
