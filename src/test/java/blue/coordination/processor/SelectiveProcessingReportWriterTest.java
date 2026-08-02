package blue.coordination.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectiveProcessingReportWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWriteDeterministicSortedEvidenceAndPreserveNativeStreamOrder()
            throws Exception {
        // Given
        Path firstDirectory = temporaryDirectory.resolve("first");
        Path secondDirectory = temporaryDirectory.resolve("second");

        // When
        SelectiveProcessingReportWriter.write(
                firstDirectory, report(false));
        SelectiveProcessingReportWriter.write(
                secondDirectory, report(true));

        byte[] first = Files.readAllBytes(
                firstDirectory.resolve(
                        SelectiveProcessingReportWriter.FILE_NAME));
        byte[] second = Files.readAllBytes(
                secondDirectory.resolve(
                        SelectiveProcessingReportWriter.FILE_NAME));

        // Then
        assertArrayEquals(first, second);
        assertTrue(
                new String(first, StandardCharsets.UTF_8)
                        .endsWith("\n"));

        JsonNode root = new ObjectMapper().readTree(first);
        assertEquals(
                SelectiveProcessingReportWriter.SCHEMA_ID,
                root.path("schema").asText());
        assertEquals(
                SelectiveProcessingReportWriter.SCHEMA_VERSION,
                root.path("schemaVersion").asInt());
        assertEquals("complete", root.path("status").asText());
        assertEquals(
                "fixture-report",
                root.path("testCountScope").asText());
        assertEquals(3, root.path("testCounts").path("total").asInt());
        assertFalse(root.has("generatedAt"));
        assertFalse(root.has("elapsedTime"));

        JsonNode routing = root.path("sections").get(0);
        assertEquals("routing", routing.path("id").asText());
        assertEquals(2, routing.path("caseCount").asInt());
        assertEquals(
                "cross-channel",
                routing.path("cases").get(0).asText());
        assertEquals(
                "replay",
                routing.path("cases").get(1).asText());

        JsonNode causalTrace = routing
                .path("orderedStreams")
                .path("causalTrace");
        assertEquals("step-2", causalTrace.get(0).asText());
        assertEquals("step-1", causalTrace.get(1).asText());

        JsonNode demanded = routing
                .path("identitySets")
                .path("demandedBlueIds");
        assertEquals("blue-a", demanded.get(0).asText());
        assertEquals("blue-z", demanded.get(1).asText());

        assertEquals(
                0,
                root.path("unavailableSuites").size());
    }

    @Test
    void shouldMatchSchemaResourceToWriterIdentity()
            throws Exception {
        // Given
        InputStream stream = getClass().getResourceAsStream(
                "/coordination/selective-processing-report.schema.json");

        // When
        assertNotNull(stream);

        // Then
        try {
            JsonNode schema = new ObjectMapper().readTree(stream);
            assertEquals(
                    SelectiveProcessingReportWriter.SCHEMA_ID,
                    schema.path("$id").asText());
            assertEquals(
                    SelectiveProcessingReportWriter.SCHEMA_ID,
                    schema.path("properties")
                            .path("schema")
                            .path("const")
                            .asText());
            assertEquals(
                    SelectiveProcessingReportWriter.SCHEMA_VERSION,
                    schema.path("properties")
                            .path("schemaVersion")
                            .path("const")
                            .asInt());
        } finally {
            stream.close();
        }
    }

    @Test
    void shouldRejectInconsistentTestCounts() {
        // Given
        int total = 2;
        int passed = 1;

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SelectiveProcessingReportWriter.TestCounts(
                                total, passed, 0, 0));

        // Then
        assertEquals(
                "total must equal passed + failed + skipped",
                failure.getMessage());
    }

    @Test
    void shouldRejectDuplicateReportSections() {
        // Given
        final SelectiveProcessingReportWriter.Section routing =
                section("routing");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SelectiveProcessingReportWriter.Report(
                                "failed",
                                identities(),
                                "fixture-report",
                                new SelectiveProcessingReportWriter.TestCounts(
                                        1, 1, 0, 0),
                                Arrays.asList(routing, routing),
                                Collections.<SelectiveProcessingReportWriter
                                        .UnavailableSuite>emptyList()));

        // Then
        assertEquals(
                "Duplicate section id: routing",
                failure.getMessage());
    }

    @Test
    void shouldRejectUnavailableSuitesFromACompleteReport() {
        // Given
        SelectiveProcessingReportWriter.UnavailableSuite unavailable =
                new SelectiveProcessingReportWriter.UnavailableSuite(
                        "final-registry",
                        "Final Coordination registry absent");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> completeReport(
                                new SelectiveProcessingReportWriter.TestCounts(
                                        1, 1, 0, 0),
                                Collections.singletonList(
                                        section("routing")),
                                Collections.singletonList(unavailable)));

        // Then
        assertEquals(
                "A complete report cannot name unavailable suites",
                failure.getMessage());
    }

    @Test
    void shouldRejectFailedTestsFromACompleteReport() {
        // Given
        SelectiveProcessingReportWriter.TestCounts counts =
                new SelectiveProcessingReportWriter.TestCounts(
                        1, 0, 1, 0);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> completeReport(
                                counts,
                                Collections.singletonList(
                                        section("routing")),
                                Collections.<SelectiveProcessingReportWriter
                                        .UnavailableSuite>emptyList()));

        // Then
        assertEquals(
                "A complete report cannot contain failed or skipped tests",
                failure.getMessage());
    }

    @Test
    void shouldRejectSkippedTestsFromACompleteReport() {
        // Given
        SelectiveProcessingReportWriter.TestCounts counts =
                new SelectiveProcessingReportWriter.TestCounts(
                        1, 0, 0, 1);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> completeReport(
                                counts,
                                Collections.singletonList(
                                        section("routing")),
                                Collections.<SelectiveProcessingReportWriter
                                        .UnavailableSuite>emptyList()));

        // Then
        assertEquals(
                "A complete report cannot contain failed or skipped tests",
                failure.getMessage());
    }

    @Test
    void shouldRejectZeroExecutedTestsFromACompleteReport() {
        // Given
        SelectiveProcessingReportWriter.TestCounts counts =
                new SelectiveProcessingReportWriter.TestCounts(
                        0, 0, 0, 0);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> completeReport(
                                counts,
                                Collections.singletonList(
                                        section("routing")),
                                Collections.<SelectiveProcessingReportWriter
                                        .UnavailableSuite>emptyList()));

        // Then
        assertEquals(
                "A complete report must contain executed tests",
                failure.getMessage());
    }

    @Test
    void shouldRejectANonPassedSectionFromACompleteReport() {
        // Given
        SelectiveProcessingReportWriter.Section notRun =
                new SelectiveProcessingReportWriter.Section(
                        "routing",
                        "not-run",
                        Collections.singletonList("routing-case"),
                        Collections.<String, String>emptyMap(),
                        Collections.<String, Long>emptyMap(),
                        Collections.<String, List<String>>emptyMap(),
                        Collections.<String, List<String>>emptyMap());

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> completeReport(
                                new SelectiveProcessingReportWriter.TestCounts(
                                        1, 1, 0, 0),
                                Collections.singletonList(notRun),
                                Collections.<SelectiveProcessingReportWriter
                                        .UnavailableSuite>emptyList()));

        // Then
        assertEquals(
                "A complete report cannot contain a not-run section: routing",
                failure.getMessage());
    }

    @Test
    void shouldRejectAnEmptyPassedSectionFromACompleteReport() {
        // Given
        SelectiveProcessingReportWriter.Section empty =
                new SelectiveProcessingReportWriter.Section(
                        "routing",
                        "passed",
                        Collections.<String>emptyList(),
                        Collections.<String, String>emptyMap(),
                        Collections.<String, Long>emptyMap(),
                        Collections.<String, List<String>>emptyMap(),
                        Collections.<String, List<String>>emptyMap());

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> completeReport(
                                new SelectiveProcessingReportWriter.TestCounts(
                                        1, 1, 0, 0),
                                Collections.singletonList(empty),
                                Collections.<SelectiveProcessingReportWriter
                                        .UnavailableSuite>emptyList()));

        // Then
        assertEquals(
                "A complete report cannot contain an empty passed section: routing",
                failure.getMessage());
    }

    private static SelectiveProcessingReportWriter.Report report(
            boolean reverseInputOrder) {
        Map<String, String> identities =
                new LinkedHashMap<String, String>();
        if (reverseInputOrder) {
            identities.put(
                    "repositoryLocalProject",
                    ".gradle/immutable-local-repository/"
                            + "63be6b7d8d2752b5a8c90f38e672859e9b3949a1"
                            + "@exact-local-composite");
            identities.put(
                    "languageGitCommit",
                    "0000000000000000000000000000000000000001");
        } else {
            identities.put(
                    "languageGitCommit",
                    "0000000000000000000000000000000000000001");
            identities.put(
                    "repositoryLocalProject",
                    ".gradle/immutable-local-repository/"
                            + "63be6b7d8d2752b5a8c90f38e672859e9b3949a1"
                            + "@exact-local-composite");
        }

        SelectiveProcessingReportWriter.Section routing =
                routingSection(reverseInputOrder);
        SelectiveProcessingReportWriter.Section scale =
                section("scale");
        List<SelectiveProcessingReportWriter.Section> sections =
                reverseInputOrder
                        ? Arrays.asList(routing, scale)
                        : Arrays.asList(scale, routing);

        return new SelectiveProcessingReportWriter.Report(
                "complete",
                identities,
                "fixture-report",
                new SelectiveProcessingReportWriter.TestCounts(
                        3, 3, 0, 0),
                sections,
                Collections.<SelectiveProcessingReportWriter
                        .UnavailableSuite>emptyList());
    }

    private static SelectiveProcessingReportWriter.Section routingSection(
            boolean reverseInputOrder) {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        if (reverseInputOrder) {
            facts.put("targetChannel", "bobChannel");
            facts.put("sourceChannel", "aliceChannel");
            metrics.put("providerCalls", Long.valueOf(4L));
            metrics.put("forbiddenDemands", Long.valueOf(0L));
        } else {
            facts.put("sourceChannel", "aliceChannel");
            facts.put("targetChannel", "bobChannel");
            metrics.put("forbiddenDemands", Long.valueOf(0L));
            metrics.put("providerCalls", Long.valueOf(4L));
        }

        Map<String, List<String>> orderedStreams =
                new LinkedHashMap<String, List<String>>();
        orderedStreams.put(
                "causalTrace",
                Arrays.asList("step-2", "step-1"));
        orderedStreams.put(
                "providerRequests",
                Arrays.asList("blue-z", "blue-a"));

        Map<String, List<String>> identitySets =
                new LinkedHashMap<String, List<String>>();
        identitySets.put(
                "demandedBlueIds",
                reverseInputOrder
                        ? Arrays.asList("blue-a", "blue-z")
                        : Arrays.asList("blue-z", "blue-a"));

        return new SelectiveProcessingReportWriter.Section(
                "routing",
                "passed",
                reverseInputOrder
                        ? Arrays.asList("cross-channel", "replay")
                        : Arrays.asList("replay", "cross-channel"),
                facts,
                metrics,
                orderedStreams,
                identitySets);
    }

    private static SelectiveProcessingReportWriter.Section section(
            String id) {
        return new SelectiveProcessingReportWriter.Section(
                id,
                "passed",
                Collections.singletonList(id + "-case"),
                Collections.<String, String>emptyMap(),
                Collections.<String, Long>emptyMap(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Report completeReport(
            SelectiveProcessingReportWriter.TestCounts counts,
            Collection<SelectiveProcessingReportWriter.Section> sections,
            Collection<SelectiveProcessingReportWriter.UnavailableSuite>
                    unavailableSuites) {
        return new SelectiveProcessingReportWriter.Report(
                "complete",
                identities(),
                "fixture-report",
                counts,
                sections,
                unavailableSuites);
    }

    private static Map<String, String> identities() {
        return Collections.singletonMap(
                "languageGitCommit",
                "9706b604d54d59e843f2d0540c1a892470d1aa5c");
    }
}
