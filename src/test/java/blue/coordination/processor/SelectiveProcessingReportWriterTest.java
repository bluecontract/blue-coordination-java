package blue.coordination.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
    void writesDeterministicSortedEvidenceAndPreservesNativeStreamOrder()
            throws Exception {
        Path firstDirectory = temporaryDirectory.resolve("first");
        Path secondDirectory = temporaryDirectory.resolve("second");

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
        assertEquals("partial", root.path("status").asText());
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
                "compute-runtime",
                root.path("unavailableSuites")
                        .get(0)
                        .path("id")
                        .asText());
        assertEquals(
                "final-registry",
                root.path("unavailableSuites")
                        .get(1)
                        .path("id")
                        .asText());
    }

    @Test
    void schemaResourceMatchesWriterIdentity() throws Exception {
        InputStream stream = getClass().getResourceAsStream(
                "/coordination/selective-processing-report.schema.json");
        assertNotNull(stream);
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
    void rejectsInconsistentCountsAndInconsistentPassedReports() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectiveProcessingReportWriter.TestCounts(
                        2, 1, 0, 0));

        final SelectiveProcessingReportWriter.Section section =
                section("routing");
        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectiveProcessingReportWriter.Report(
                        "partial",
                        Collections.singletonMap(
                                "languageGitCommit", "0a6a40d18578"),
                        "fixture-report",
                        new SelectiveProcessingReportWriter.TestCounts(
                                1, 1, 0, 0),
                        Arrays.asList(section, section),
                        Collections.<SelectiveProcessingReportWriter
                                .UnavailableSuite>emptyList()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectiveProcessingReportWriter.Report(
                        "passed",
                        Collections.singletonMap(
                                "languageGitCommit", "0a6a40d18578"),
                        "fixture-report",
                        new SelectiveProcessingReportWriter.TestCounts(
                                1, 1, 0, 0),
                        Collections.singletonList(section),
                        Collections.singletonList(
                                new SelectiveProcessingReportWriter
                                        .UnavailableSuite(
                                        "final-registry",
                                        "Final Coordination registry absent"))));

        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectiveProcessingReportWriter.Report(
                        "passed",
                        Collections.singletonMap(
                                "languageGitCommit", "0a6a40d18578"),
                        "fixture-report",
                        new SelectiveProcessingReportWriter.TestCounts(
                                1, 0, 1, 0),
                        Collections.singletonList(
                                new SelectiveProcessingReportWriter.Section(
                                        "routing",
                                        "passed",
                                        Collections.singletonList(
                                                "cross-channel"),
                                        Collections.<String, String>emptyMap(),
                                        Collections.<String, Long>emptyMap(),
                                        Collections.<String, List<String>>
                                                emptyMap(),
                                        Collections.<String, List<String>>
                                                emptyMap())),
                        Collections.<SelectiveProcessingReportWriter
                                .UnavailableSuite>emptyList()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectiveProcessingReportWriter.Report(
                        "passed",
                        Collections.singletonMap(
                                "languageGitCommit", "0a6a40d18578"),
                        "fixture-report",
                        new SelectiveProcessingReportWriter.TestCounts(
                                1, 1, 0, 0),
                        Collections.singletonList(section("routing")),
                        Collections.<SelectiveProcessingReportWriter
                                .UnavailableSuite>emptyList()));
    }

    private static SelectiveProcessingReportWriter.Report report(
            boolean reverseInputOrder) {
        Map<String, String> identities =
                new LinkedHashMap<String, String>();
        if (reverseInputOrder) {
            identities.put(
                    "repositoryDependency",
                    "blue-repo-java:3.0.0-rc.10");
            identities.put(
                    "languageGitCommit",
                    "0a6a40d18578df784f674148d1e8b6a4319bfe49");
        } else {
            identities.put(
                    "languageGitCommit",
                    "0a6a40d18578df784f674148d1e8b6a4319bfe49");
            identities.put(
                    "repositoryDependency",
                    "blue-repo-java:3.0.0-rc.10");
        }

        SelectiveProcessingReportWriter.Section routing =
                routingSection(reverseInputOrder);
        SelectiveProcessingReportWriter.Section scale =
                section("scale");
        List<SelectiveProcessingReportWriter.Section> sections =
                reverseInputOrder
                        ? Arrays.asList(routing, scale)
                        : Arrays.asList(scale, routing);

        SelectiveProcessingReportWriter.UnavailableSuite registry =
                new SelectiveProcessingReportWriter.UnavailableSuite(
                        "final-registry",
                        "Final Coordination registry absent");
        SelectiveProcessingReportWriter.UnavailableSuite compute =
                new SelectiveProcessingReportWriter.UnavailableSuite(
                        "compute-runtime",
                        "Manifest-bound BEX counter stream absent");
        List<SelectiveProcessingReportWriter.UnavailableSuite> unavailable =
                reverseInputOrder
                        ? Arrays.asList(registry, compute)
                        : Arrays.asList(compute, registry);

        return new SelectiveProcessingReportWriter.Report(
                "partial",
                identities,
                "fixture-report",
                new SelectiveProcessingReportWriter.TestCounts(
                        3, 2, 0, 1),
                sections,
                unavailable);
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
                "not-run",
                Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<String, Long>emptyMap(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }
}
