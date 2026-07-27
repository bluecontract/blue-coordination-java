package blue.coordination.processor.compute;

import blue.bex.api.BexEngine;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.RepositoryTypeAliasPreprocessor;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.coordination.StatusPending;
import blue.repo.mandate.Mandate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Produces deterministic machine-readable evidence for the Language-adoption scenarios. */
class LanguageAdoptionMetricsArtifactTest {
    private static final String PAYNOTE_RESOURCE =
            "/processor-delay/paynote-resale-reduced-bex.yaml";
    private static final Path REPORT_DIRECTORY = Paths.get(System.getProperty("user.dir"),
            "build", "reports", "language-adoption");

    @Test
    void writesJsonAndCsvForRequiredRepresentativeScenarios() throws Exception {
        List<LanguageAdoptionMetricsArtifactWriter.Scenario> scenarios = Arrays.asList(
                staticUpdateDocumentScenario(),
                multiPatchComputeScenario(),
                payNoteFixtureScenario(),
                mandateFixtureScenario());

        LanguageAdoptionMetricsArtifactWriter.write(REPORT_DIRECTORY, scenarios);

        Path json = REPORT_DIRECTORY.resolve(LanguageAdoptionMetricsArtifactWriter.JSON_FILE_NAME);
        Path csv = REPORT_DIRECTORY.resolve(LanguageAdoptionMetricsArtifactWriter.CSV_FILE_NAME);
        assertTrue(Files.isRegularFile(json));
        assertTrue(Files.isRegularFile(csv));
        assertJsonScenarios(json);
        assertCsvScenarios(csv);
    }

    private static LanguageAdoptionMetricsArtifactWriter.Scenario staticUpdateDocumentScenario() {
        OwnedScenario fixture = new OwnedScenario();
        try {
            DocumentProcessingResult initialized = fixture.support.initialize(fixture.support.yaml(
                    fixture.support.operationWorkflowDocumentWithStatus("count: 0", String.join("\n",
                            "    steps:",
                            "      - name: ApplyStaticChanges",
                            "        type: Coordination/Update Document",
                            "        changeset:",
                            "          - op: replace",
                            "            path: /status",
                            "            val: static-updated",
                            "          - op: replace",
                            "            path: /count",
                            "            val: 1"))));
            Node document = initialized.document();
            BexProcessingMetrics.Snapshot baseline = fixture.metrics.snapshot();

            DocumentProcessingResult result = fixture.support.processRun(document);
            assertSuccess(fixture.support.blue, result);
            assertEquals("static-updated", result.document().get("/status"));
            assertEquals(BigInteger.ONE, result.document().get("/count"));
            assertEquals(2L, fixture.metrics.patchesApplied());
            assertTrue(metric(fixture.metrics, "frozenPatchesHandedToLanguage") >= 2L);
            return LanguageAdoptionMetricsArtifactWriter.capture(
                    "static-update-document",
                    "static-update-document",
                    "inline Coordination/Update Document with two authored patches",
                    result,
                    fixture.metrics,
                    baseline);
        } finally {
            fixture.close();
        }
    }

    private static LanguageAdoptionMetricsArtifactWriter.Scenario multiPatchComputeScenario() {
        OwnedScenario fixture = new OwnedScenario();
        try {
            DocumentProcessingResult initialized = fixture.support.initialize(fixture.support.yaml(
                    fixture.support.operationWorkflowDocumentWithStatus("count: 0", String.join("\n",
                            "    steps:",
                            "      - name: BuildMultiPatchChangeset",
                            "        type: Coordination/Compute",
                            "        do:",
                            "          - $appendChange:",
                            "              op: replace",
                            "              path: /status",
                            "              val: first",
                            "          - $appendChange:",
                            "              op: replace",
                            "              path: /count",
                            "              val: 2",
                            "          - $appendChange:",
                            "              op: replace",
                            "              path: /status",
                            "              val: computed",
                            "          - $return:",
                            "              changeset:",
                            "                $changeset: true"))));
            Node document = initialized.document();
            BexProcessingMetrics.Snapshot baseline = fixture.metrics.snapshot();

            DocumentProcessingResult result = fixture.support.processRun(document);
            assertSuccess(fixture.support.blue, result);
            assertEquals("computed", result.document().get("/status"));
            assertEquals(BigInteger.valueOf(2L), result.document().get("/count"));
            assertEquals(3L, fixture.metrics.patchesApplied());
            assertEquals(1L, fixture.metrics.updateBatchPatchApplications());
            assertEquals(0L, fixture.metrics.updateIndividualPatchApplications());
            return LanguageAdoptionMetricsArtifactWriter.capture(
                    "multi-patch-compute",
                    "multi-patch-compute",
                    "inline Coordination/Compute accumulated three-patch BEX changeset",
                    result,
                    fixture.metrics,
                    baseline);
        } finally {
            fixture.close();
        }
    }

    private static LanguageAdoptionMetricsArtifactWriter.Scenario payNoteFixtureScenario() {
        OwnedScenario fixture = new OwnedScenario();
        try {
            DocumentProcessingResult initialized = fixture.support.blue.initializeDocument(
                    fixture.support.yamlResource(PAYNOTE_RESOURCE));
            assertSuccess(fixture.support.blue, initialized);
            BexProcessingMetrics.Snapshot baseline = fixture.metrics.snapshot();

            Node event = fixture.support.operationRequest(
                    "hotel-participant",
                    1_700_000_100,
                    "hotelResaleOrderPlaced",
                    "hotelParticipantChannel",
                    subscriptionUpdate());
            DocumentProcessingResult result = fixture.support.blue.processDocument(
                    blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                            fixture.support.blue, initialized),
                    event);

            assertSuccess(fixture.support.blue, result);
            assertEquals(Boolean.TRUE,
                    result.document().get("/orders/package-order-a/hotelOrder/resalePlaced"));
            return LanguageAdoptionMetricsArtifactWriter.capture(
                    "paynote-resale-fixture",
                    "paynote-fixture",
                    "classpath:" + PAYNOTE_RESOURCE,
                    result,
                    fixture.metrics,
                    baseline);
        } finally {
            fixture.close();
        }
    }

    private static LanguageAdoptionMetricsArtifactWriter.Scenario mandateFixtureScenario() {
        OwnedScenario fixture = new OwnedScenario();
        try {
            Node mandate = mandateDocument();
            mandate.blue(fixture.support.repository.typeAliasBlue());
            Node aliasesResolved = new RepositoryTypeAliasPreprocessor(
                    fixture.support.repository).preprocess(mandate);
            ResolvedSnapshot resolved = fixture.support.blue.resolveToSnapshot(
                    fixture.support.blue.preprocess(aliasesResolved));
            DocumentProcessingResult initialized =
                    fixture.support.blue.initializeDocument(resolved);
            assertSuccess(fixture.support.blue, initialized);
            assertEquals(StatusPending.blueId(),
                    initialized.document().getAsText("/status/type/blueId"));
            BexProcessingMetrics.Snapshot baseline = fixture.metrics.snapshot();

            Node event = TestTimelineProvider.timelineEntry(
                    fixture.support.blue,
                    fixture.support.repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.valueOf(7_000_001L),
                    CoordinationTestResources.operationRequest(
                            "confirmMandateAuthority",
                            "mandateGuarantorChannel",
                            new Node()));
            DocumentProcessingResult result = fixture.support.blue.processDocument(
                    blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                            fixture.support.blue, initialized),
                    event);

            assertSuccess(fixture.support.blue, result);
            assertEquals(BigInteger.valueOf(7_000_001L),
                    result.document().get("/authorityConfirmedAt"));
            return LanguageAdoptionMetricsArtifactWriter.capture(
                    "mandate-authority-confirmation",
                    "mandate-fixture",
                    "generated Mandate authority-confirmation lifecycle fixture",
                    result,
                    fixture.metrics,
                    baseline);
        } finally {
            fixture.close();
        }
    }

    private static Node subscriptionUpdate() {
        return new Node()
                .type("Sample/Subscription Update")
                .properties("subscriptionId", new Node().value("hotel-resale-agreement"))
                .properties("targetSessionId", new Node().value("hotel-agreement-session"))
                .properties("update", new Node()
                        .properties("kind", new Node().value("Resale Order Placed"))
                        .properties("inResponseTo", new Node()
                                .properties("requestId", new Node().value("hotel-request-a")))
                        .properties("orderSessionId", new Node().value("hotel-order-session-a")));
    }

    private static Node mandateDocument() {
        return new Node()
                .name("Metrics artifact mandate")
                .type(Mandate.qualifiedName())
                .properties("activateOnAuthorityConfirmation", new Node().value(false))
                .properties("contracts", new Node()
                        .properties("mandateGuarantorChannel",
                                TestTimelineProvider.channel("guarantor"))
                        .properties("authorityHolderChannel",
                                TestTimelineProvider.channel("holder"))
                        .properties("authorizedActorChannel",
                                TestTimelineProvider.channel("authorized")));
    }

    private static long metric(BexProcessingMetrics metrics, String name) {
        Long value = metrics.languageCounters().get(name);
        return value != null ? value.longValue() : 0L;
    }

    private static void assertSuccess(Blue language, DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNotNull(blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                language, result));
        assertNotNull(blue.coordination.processor.ProcessingResultTestSupport.blueId(
                result));
    }

    private static void assertJsonScenarios(Path json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.newInputStream(json));
        assertEquals(2, root.path("schemaVersion").asInt());
        assertEquals("after-scenario-before-runtime-and-runner-close",
                root.path("capturePhase").asText());
        assertEquals("runtime-construction-through-scenario",
                root.path("cumulativeMetricScope").asText());
        assertEquals("after-document-initialization-before-scenario",
                root.path("proofCounterDeltaBaseline").asText());
        assertTrue(root.path("coordinationElapsedTimingMetricsExcluded").asBoolean());
        assertTrue(root.path("genericElapsedTimingMetricsExcluded").asBoolean());
        assertEquals(4, root.path("scenarios").size());

        Set<String> ids = new HashSet<String>();
        for (JsonNode scenario : root.path("scenarios")) {
            ids.add(scenario.path("scenarioId").asText());
            assertEquals("SUCCESS", scenario.path("result").path("status").asText());
            assertTrue(scenario.path("result").path("totalGas").asLong() > 0L);
            assertTrue(scenario.path("metrics").path("coordinationStrong").size() > 0);
            assertTrue(scenario.path("metrics").path("language").path("counters").size() > 0);
            assertTrue(scenario.path("metrics").path("language").path("gauges").size() > 0);
            assertTrue(scenario.path("metrics").path("language")
                    .path("highWaterMarks").size() > 0);
            assertNoTimingMetrics(scenario.path("metrics").path("language").path("counters"));
            assertNoTimingMetrics(scenario.path("metrics").path("language").path("gauges"));
            assertNoTimingMetrics(scenario.path("metrics").path("language")
                    .path("highWaterMarks"));

            JsonNode proof = scenario.path("metrics")
                    .path("proofCounterDeltasSinceInitialization");
            assertEquals(6, proof.size());
            assertEquals(expectedPatches(scenario.path("scenarioId").asText()),
                    proof.path("frozenPatchesHandedToLanguage").asLong());
            assertTrue(proof.path("frozenPatchValuesHandedToLanguage").asLong() > 0L);
            assertEquals(
                    proof.path("frozenPatchValuesHandedToLanguage").asLong() * 2L,
                    proof.path("frozenPatchValuesAccepted").asLong());
            assertEquals(0L, proof.path("mutablePatchesHandedToLanguage").asLong());
            assertEquals(0L, proof.path("mutablePatchValuesFrozen").asLong());
            assertEquals(0L, proof.path("frozenPatchValuesMaterialized").asLong());
        }
        assertEquals(new HashSet<String>(Arrays.asList(
                "static-update-document",
                "multi-patch-compute",
                "paynote-resale-fixture",
                "mandate-authority-confirmation")), ids);
    }

    private static void assertCsvScenarios(Path csv) throws IOException {
        String content = new String(Files.readAllBytes(csv), StandardCharsets.UTF_8);
        assertTrue(content.startsWith("scenario_id,scenario_kind,fixture,status"));
        assertTrue(content.contains("\"static-update-document\""));
        assertTrue(content.contains("\"multi-patch-compute\""));
        assertTrue(content.contains("\"paynote-resale-fixture\""));
        assertTrue(content.contains("\"mandate-authority-confirmation\""));
        assertTrue(content.contains("\"coordination-cumulative\",\"strong\""));
        assertTrue(content.contains("\"language-cumulative\",\"counter\""));
        assertTrue(content.contains("\"language-cumulative\",\"gauge\""));
        assertTrue(content.contains("\"language-cumulative\",\"high_water\""));
        assertTrue(content.contains("\"workflow-since-initialization\",\"counter_delta\","
                + "\"frozenPatchValuesMaterialized\",\"0\""));
        assertFalse(content.contains("Nanos"));
    }

    private static void assertNoTimingMetrics(JsonNode metrics) {
        Iterator<String> names = metrics.fieldNames();
        while (names.hasNext()) {
            assertFalse(names.next().endsWith("Nanos"));
        }
    }

    private static long expectedPatches(String scenarioId) {
        if ("static-update-document".equals(scenarioId)) {
            return 2L;
        }
        if ("multi-patch-compute".equals(scenarioId)) {
            return 3L;
        }
        if ("paynote-resale-fixture".equals(scenarioId)) {
            return 6L;
        }
        if ("mandate-authority-confirmation".equals(scenarioId)) {
            return 2L;
        }
        throw new AssertionError("Unexpected scenario id: " + scenarioId);
    }

    private static final class OwnedScenario implements AutoCloseable {
        private final BexProcessingMetrics metrics = new BexProcessingMetrics();
        private final BexEngine engine = BexEngine.builder().build();
        private final SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                engine, 100_000L, metrics);
        private final ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .bexEngine(engine)
                        .sequentialWorkflowRunner(runner)
                        .defaultComputeGasLimit(100_000L)
                        .processingMetrics(metrics)
                        .build());

        @Override
        public void close() {
            try {
                support.blue.close();
            } finally {
                runner.close();
            }
        }
    }
}
