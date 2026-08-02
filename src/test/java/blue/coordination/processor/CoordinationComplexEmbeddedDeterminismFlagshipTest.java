package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.CoordinationConfiguredProcessorFactory;
import blue.language.processor.CoordinationRoutingHarness;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coordination-owned executable flagship for a deep reactive PROCESS.
 *
 * <p>The fixture deliberately stays inside the one-Root Contracts boundary:
 * exact external evidence selects the leaf before its ancestors, while
 * Document Update and event routing propagate causality inside the same
 * atomic PROCESS. Feeder generations, CAS, outbox, and child-commit
 * orchestration are intentionally absent.</p>
 */
final class CoordinationComplexEmbeddedDeterminismFlagshipTest {

    private static final String ROOT = "/";
    private static final String EMB1 = "/emb1";
    private static final String EMB2 = "/emb1/emb2";
    private static final String EMB3 = "/emb1/emb2/emb3";
    private static final String TIMELINE = "timeline";
    private static final String PULSE_OPERATION = "pulse";
    private static final int TIMESTAMP = 4242;
    private static final int LARGE_DECOY_SIZE = 12_000;
    private static MatrixResult descendantsOnlyEvidence;
    private static MatrixResult rootD1D2Evidence;

    @AfterAll
    static void shouldWriteEvidenceOnlyAfterBothPublicEventVariantsComplete() {
        // Given
        MatrixResult descendantsOnly =
                descendantsOnlyEvidence;
        MatrixResult rootD1D2 =
                rootD1D2Evidence;
        String reportPath =
                System.getProperty(
                        "coordination.flagship.report");

        // When
        if (reportPath == null
                || descendantsOnly == null
                || rootD1D2 == null) {
            return;
        }
        writeObservedArtifact(
                descendantsOnly,
                rootD1D2,
                Paths.get(reportPath));

        // Then
        assertEquals(
                32,
                descendantsOnly.runs.size()
                        + rootD1D2.runs.size());
    }

    @Test
    void shouldKeepDescendantEventsInternalAcrossEveryRepresentationProviderVariant() {
        // Given
        Scenario descendantsOnlyScenario =
                Scenario.create(RootEmissionMode.DESCENDANTS_ONLY);

        // When
        MatrixResult descendantsOnly =
                executeMatrix(descendantsOnlyScenario);

        // Then
        assertDescendantsOnlyPublicEvents(
                descendantsOnly);
        descendantsOnlyEvidence = descendantsOnly;
    }

    @Test
    void shouldExposeOnlyOrderedRootEventsAcrossEveryRepresentationProviderVariant() {
        // Given
        Scenario rootD1D2Scenario =
                Scenario.create(RootEmissionMode.ROOT_D1_D2);

        // When
        MatrixResult rootD1D2 =
                executeMatrix(rootD1D2Scenario);

        // Then
        assertRootD1D2PublicEvents(
                rootD1D2Scenario,
                rootD1D2);
        rootD1D2Evidence = rootD1D2;
    }

    private static void assertDescendantsOnlyPublicEvents(
            MatrixResult matrix) {
        assertMatrixSemantics(matrix);
        assertEquals(16, matrix.runs.size());
        assertTrue(
                matrix.baseline
                        .rootEventBlueIds.isEmpty());
        assertEquals(
                ProcessorStatus.SUCCESS,
                matrix.baseline.status);
    }

    private static void assertRootD1D2PublicEvents(
            Scenario scenario,
            MatrixResult matrix) {
        assertMatrixSemantics(matrix);
        assertEquals(16, matrix.runs.size());
        assertEquals(
                Arrays.asList(
                        scenario.events.d1BlueId,
                        scenario.events.d2BlueId),
                matrix.baseline.rootEventBlueIds);
        assertEquals(ProcessorStatus.SUCCESS, matrix.baseline.status);
    }

    private static MatrixResult executeMatrix(
            Scenario scenario) {
        List<Run> runs = new ArrayList<>();
        for (Variant variant : Variant.matrix()) {
            Run run = execute(scenario, variant);
            runs.add(run);
        }
        return new MatrixResult(
                Collections.unmodifiableList(runs),
                SemanticProjection.of(
                        runs.get(0)));
    }

    private static void assertMatrixSemantics(
            MatrixResult matrix) {
        for (Run run : matrix.runs) {
            assertSuccessfulFinalState(run);
            assertDeterministicCausality(run);
            assertCheckpointOrder(run);
            assertStrictPhysicalLocality(run);
            assertEquals(
                    matrix.baseline,
                    SemanticProjection.of(
                            run),
                    "semantic drift for "
                            + run.scenario.emissionMode
                            + "/" + run.variant);
        }
    }

    private static void writeObservedArtifact(
            MatrixResult descendantsOnly,
            MatrixResult rootD1D2,
            Path report) {
        StringBuilder markdown =
                new StringBuilder();
        markdown.append(
                "# Coordination flagship observed trace\n\n");
        markdown.append(
                "Generated from one successful observed baseline for each "
                        + "public-event variant and all representation/provider "
                        + "runs verified against those baselines.\n\n");
        markdown.append("- Public-event variants: `2`\n");
        markdown.append("- Descendants-only PROCESS runs: `")
                .append(descendantsOnly.runs.size())
                .append("`\n");
        markdown.append("- Root D1,D2 PROCESS runs: `")
                .append(rootD1D2.runs.size())
                .append("`\n");
        markdown.append("- Total PROCESS runs: `")
                .append(
                        descendantsOnly.runs.size()
                                + rootD1D2.runs.size())
                .append("`\n\n");

        appendObservedVariant(
                markdown,
                descendantsOnly);
        appendObservedVariant(
                markdown,
                rootD1D2);

        markdown.append(
                "## Combined representation/provider matrix\n\n");
        markdown.append(
                "| Variant | Entry | Cache | Provider | Status | "
                        + "Requested | Backend loaded | "
                        + "Backend trips | Requested bytes | "
                        + "Backend-loaded bytes | Selected bodies | "
                        + "Selected bytes | Gas |\n");
        markdown.append(
                "|---|---|---|---|---|---:|---:|---:|"
                        + "---:|---:|---:|---:|---:|\n");
        appendMatrixRows(
                markdown,
                descendantsOnly);
        appendMatrixRows(
                markdown,
                rootD1D2);
        markdown.append('\n');

        try {
            Files.createDirectories(
                    report.getParent());
            Files.write(
                    report,
                    markdown.toString().getBytes(
                            StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new AssertionError(
                    "Could not write observed flagship trace",
                    failure);
        }
    }

    private static void appendObservedVariant(
            StringBuilder markdown,
            MatrixResult matrix) {
        Run baseline = matrix.runs.get(0);
        ProcessingConformanceTrace trace =
                baseline.debug.trace();
        markdown.append("## Variant: ")
                .append(
                        emissionModeLabel(
                                baseline.scenario
                                        .emissionMode))
                .append("\n\n");
        markdown.append("- Status: `")
                .append(matrix.baseline.status)
                .append("`\n");
        markdown.append("- Resulting Root BlueId: `")
                .append(
                        matrix.baseline
                                .resultingRootBlueId)
                .append("`\n");
        markdown.append("- Total gas: `")
                .append(matrix.baseline.totalGas)
                .append("`\n");
        markdown.append("- Selected body bytes: `")
                .append(
                        matrix.baseline
                                .selectedBodyBytes)
                .append("`\n");
        for (String selectedBody :
                matrix.baseline
                        .selectedBodyCanonicalBytes) {
            markdown.append(
                            "- Selected body canonical bytes: `")
                    .append(selectedBody)
                    .append("`\n");
        }
        markdown.append("- Total stored fragment bytes: `")
                .append(totalBytes(
                        baseline.scenario.fragmentBytes))
                .append("`\n");
        markdown.append("- Forbidden decoy fragment bytes: `")
                .append(bytesFor(
                        baseline.scenario.fragmentBytes,
                        baseline.scenario.forbiddenBlueIds))
                .append("`\n\n");

        appendObservedList(
                markdown,
                "External delivery order",
                externalDeliveryProjection(trace));
        appendObservedList(
                markdown,
                "Handler order",
                handlerProjection(trace));
        appendObservedList(
                markdown,
                "Effect order",
                effectProjection(trace));
        appendObservedList(
                markdown,
                "Event enqueue order",
                recordNodeBlueIds(
                        trace.records(
                                ProcessingTraceRecord.Kind
                                        .EVENT_ENQUEUED)));
        appendObservedList(
                markdown,
                "Event dequeue order",
                recordNodeBlueIds(
                        trace.records(
                                ProcessingTraceRecord.Kind
                                        .EVENT_DEQUEUED)));
        appendObservedList(
                markdown,
                "Event delivery order",
                eventDeliveryProjection(trace));
        appendObservedList(
                markdown,
                "Checkpoint order",
                checkpointProjection(trace));
        appendObservedList(
                markdown,
                "Root-only public events",
                observedRootEvents(
                        baseline.debug
                                .processResult()
                                .events()));
        appendObservedList(
                markdown,
                "Gas trace",
                matrix.baseline.gasTrace);
        appendObservedList(
                markdown,
                "Semantic demands",
                matrix.baseline.semanticDemands);
        appendObservedList(
                markdown,
                "Selected body BlueIds",
                matrix.baseline
                        .selectedBodyBlueIds);
        appendObservedList(
                markdown,
                "Provider requested BlueIds",
                providerIdentityUnion(
                        matrix, true));
        appendObservedList(
                markdown,
                "Provider backend-loaded BlueIds",
                providerIdentityUnion(
                        matrix, false));
        appendObservedList(
                markdown,
                "Forbidden BlueIds",
                sortedIdentities(
                        baseline.scenario
                                .forbiddenBlueIds));
    }

    private static void appendMatrixRows(
            StringBuilder markdown,
            MatrixResult matrix) {
        for (Run run : matrix.runs) {
            markdown.append("| ")
                    .append(
                            emissionModeLabel(
                                    run.scenario
                                            .emissionMode))
                    .append(" | ")
                    .append(run.variant.entryMode)
                    .append(" | ")
                    .append(run.variant.cacheMode)
                    .append(" | ")
                    .append(run.variant.providerMode)
                    .append(" | ")
                    .append(
                            run.debug.processResult()
                                    .status())
                    .append(" | ")
                    .append(
                            run.providerMetrics
                                    .requestedBlueIds
                                    .size())
                    .append(" | ")
                    .append(
                            run.providerMetrics
                                    .backendLoadedBlueIds
                                    .size())
                    .append(" | ")
                    .append(
                            run.providerMetrics
                                    .backendTrips)
                    .append(" | ")
                    .append(
                            bytesFor(
                                    run.scenario
                                            .fragmentBytes,
                                    run.providerMetrics
                                            .requestedBlueIds))
                    .append(" | ")
                    .append(
                            bytesFor(
                                    run.scenario
                                            .fragmentBytes,
                                    run.providerMetrics
                                            .backendLoadedBlueIds))
                    .append(" | ")
                    .append(
                            run.selectedBodies
                                    .blueIds.size())
                    .append(" | ")
                    .append(
                            run.selectedBodies
                                    .canonicalBytes)
                    .append(" | ")
                    .append(
                            run.debug.processResult()
                                    .totalGas())
                    .append(" |\n");
        }
    }

    private static List<String> providerIdentityUnion(
            MatrixResult matrix,
            boolean requested) {
        Set<String> identities =
                new LinkedHashSet<>();
        for (Run run : matrix.runs) {
            identities.addAll(
                    requested
                            ? run.providerMetrics
                            .requestedBlueIds
                            : run.providerMetrics
                            .backendLoadedBlueIds);
        }
        return sortedIdentities(identities);
    }

    private static List<String> sortedIdentities(
            Set<String> identities) {
        List<String> result =
                new ArrayList<>(identities);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static long totalBytes(
            Map<String, Long> bytesByBlueId) {
        long result = 0L;
        for (Long bytes : bytesByBlueId.values()) {
            result = Math.addExact(
                    result,
                    bytes.longValue());
        }
        return result;
    }

    private static long bytesFor(
            Map<String, Long> bytesByBlueId,
            Set<String> blueIds) {
        long result = 0L;
        for (String blueId : blueIds) {
            Long bytes = bytesByBlueId.get(blueId);
            if (bytes != null) {
                result = Math.addExact(
                        result,
                        bytes.longValue());
            }
        }
        return result;
    }

    private static String emissionModeLabel(
            RootEmissionMode emissionMode) {
        return emissionMode
                == RootEmissionMode.DESCENDANTS_ONLY
                ? "descendants-only"
                : "Root D1,D2";
    }

    private static void appendObservedList(
            StringBuilder markdown,
            String title,
            List<String> values) {
        markdown.append("### ")
                .append(title)
                .append("\n\n```text\n");
        if (values.isEmpty()) {
            markdown.append("(none)\n");
        } else {
            for (String value : values) {
                markdown.append(value)
                        .append('\n');
            }
        }
        markdown.append("```\n\n");
    }

    private static List<String> checkpointProjection(
            ProcessingConformanceTrace trace) {
        List<String> result =
                new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE)) {
            Node subject =
                    nodeAt(record.node(), "/subject");
            result.add(
                    record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.detail(
                            ProcessingTraceConstants
                                    .FIELD_SUBJECT)
                            + "|" + subject.get(
                            "/timestamp")
                            + "|" + subject.get(
                            "/entryBlueId"));
        }
        return Collections.unmodifiableList(
                result);
    }

    private static List<String> observedRootEvents(
            List<Node> events) {
        List<String> result =
                new ArrayList<>();
        for (Node event : events) {
            result.add(
                    BlueIdCalculator.calculateBlueId(
                            event)
                            + "|" + event.get(
                            "/message"));
        }
        return Collections.unmodifiableList(
                result);
    }

    private static Run execute(
            Scenario scenario,
            Variant variant) {
        StrictFragmentProvider fragments =
                new StrictFragmentProvider(
                        scenario.fragments,
                        scenario.forbiddenBlueIds,
                        variant.providerMode);
        if (variant.cacheMode == CacheMode.WARM) {
            fragments.warmAllowed();
        }
        fragments.resetMetrics();

        BexProcessingMetrics metrics =
                new BexProcessingMetrics();
        Blue blue =
                scenario.repository.configure(
                        new Blue());
        NodeProvider configuredRepositoryProvider =
                blue.getNodeProvider();
        blue.nodeProvider(
                new SequentialNodeProvider(
                        fragments,
                        configuredRepositoryProvider));
        CoordinationProcessors.registerWith(
                blue,
                CoordinationProcessorOptions.builder()
                        .processingMetrics(metrics)
                        .build());
        DocumentProcessor processor =
                CoordinationConfiguredProcessorFactory
                        .withExecutionEvidencePlan(
                                blue,
                                null,
                                scenario.evidence);
        try {
            ProcessingDebugResult debug =
                    processor.processDocumentWithTrace(
                            variant.document(scenario),
                            variant.event(scenario),
                            scenario.evidence);
            assertSuccessfulProcessingBeforeHandlerProjection(
                    scenario,
                    variant,
                    debug);
            return new Run(
                    scenario,
                    variant,
                    debug,
                    fragments.metrics(),
                    metrics);
        } finally {
            processor.close();
            blue.close();
        }
    }

    private static void assertSuccessfulProcessingBeforeHandlerProjection(
            Scenario scenario,
            Variant variant,
            ProcessingDebugResult debug) {
        DocumentProcessingResult result =
                debug.processResult();
        String context =
                scenario.emissionMode
                        + "/" + variant;
        String diagnostic =
                ProcessingResultTestSupport
                        .diagnosticMessage(result);
        String failureMessage =
                context + ": " + diagnostic;
        List<String> retainedDeliveries =
                new ArrayList<String>();
        scenario.evidence.deliveries()
                .forEach(delivery ->
                        retainedDeliveries.add(
                                delivery.scopePath()
                                        + "|"
                                        + delivery.channelKey()));
        boolean exactDrift =
                result.status()
                        == ProcessorStatus
                        .INVALID_PROCESSING_DOCUMENT
                        && ProcessingResultTestSupport
                        .diagnosticCategory(result)
                        == ProcessorErrorCategory
                        .InvalidExternalChannelSnapshot
                        && diagnostic.startsWith(
                        "External delivery changed during "
                                + "accepted-new preflight at ")
                        && diagnostic.endsWith(
                        "/" + TIMELINE)
                        && retainedDeliveries.equals(
                        Arrays.asList(
                                EMB3 + "|" + TIMELINE,
                                EMB2 + "|" + TIMELINE,
                                EMB1 + "|" + TIMELINE,
                                ROOT + "|" + TIMELINE))
                        && result.events().isEmpty();
        ExternalBlockerProbeAssertions.classify(
                "flagship-external-delivery-evidence-drift",
                "Language flagship external-delivery evidence drift:",
                exactDrift,
                result.status() == ProcessorStatus.SUCCESS,
                failureMessage
                        + ", retainedDeliveries="
                        + retainedDeliveries);
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                failureMessage);
    }

    private static void assertSuccessfulFinalState(
            Run run) {
        DocumentProcessingResult result =
                run.debug.processResult();
        String context =
                run.scenario.emissionMode
                        + "/" + run.variant;
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                context + ": "
                        + ProcessingResultTestSupport
                        .diagnosticMessage(result));
        assertNull(
                result.diagnostic(),
                context);

        assertTrueAt(result.document(),
                EMB3 + "/state/pulseSeen", context);
        assertTrueAt(result.document(),
                EMB3 + "/audit/pulseUpdateHandled", context);
        assertTrueAt(result.document(),
                EMB3 + "/state/aHandledLocally", context);
        assertOriginalProcessingEventAt(
                run, EMB3, context);

        assertTrueAt(result.document(),
                EMB2 + "/state/sawEmb3PulseUpdate", context);
        assertTrueAt(result.document(),
                EMB2 + "/audit/emb3UpdateReactionHandled", context);
        assertTrueAt(result.document(),
                EMB2 + "/state/aReceived", context);
        assertTrueAt(result.document(),
                EMB2 + "/audit/aReceiveUpdateHandled", context);
        assertTrueAt(result.document(),
                EMB2 + "/state/bHandledLocally", context);
        assertTrueAt(result.document(),
                EMB2 + "/state/directPulseSeen", context);
        assertOriginalProcessingEventAt(
                run, EMB2, context);

        assertTrueAt(result.document(),
                EMB1 + "/state/sawDeepPulseUpdate", context);
        assertTrueAt(result.document(),
                EMB1 + "/state/aReceived", context);
        assertTrueAt(result.document(),
                EMB1 + "/state/sawEmb2AReceiptUpdate", context);
        assertTrueAt(result.document(),
                EMB1 + "/state/bReceived", context);
        assertTrueAt(result.document(),
                EMB1 + "/audit/bReceiveUpdateHandled", context);
        assertTrueAt(result.document(),
                EMB1 + "/state/cHandledLocally", context);
        assertTrueAt(result.document(),
                EMB1 + "/state/directPulseSeen", context);
        assertOriginalProcessingEventAt(
                run, EMB1, context);

        assertTrueAt(result.document(),
                "/observed/deepPulseUpdate", context);
        assertTrueAt(result.document(),
                "/observed/a", context);
        assertTrueAt(result.document(),
                "/observed/emb2AReceiptUpdate", context);
        assertTrueAt(result.document(),
                "/observed/b", context);
        assertTrueAt(result.document(),
                "/observed/emb1BReceiptUpdate", context);
        assertTrueAt(result.document(),
                "/observed/c", context);
        assertTrueAt(result.document(),
                "/audit/cObservationHandled", context);
        assertOriginalProcessingEventAt(
                run, ROOT, context);
        assertTrueAt(result.document(),
                "/state/directPulseSeen", context);

        boolean rootEmits =
                run.scenario.emissionMode
                        == RootEmissionMode.ROOT_D1_D2;
        assertEquals(
                rootEmits,
                result.document().get(
                        "/observed/d1Handled"),
                context);
        assertEquals(
                rootEmits,
                result.document().get(
                        "/observed/d2Handled"),
                context);
        assertEquals(
                rootEmits
                        ? Arrays.asList(
                        run.scenario.events.d1BlueId,
                        run.scenario.events.d2BlueId)
                        : Collections.<String>emptyList(),
                nodeBlueIds(result.events()),
                context);
        assertEquals(
                4L,
                run.metrics.computeStepsExecuted(),
                context);
        assertEquals(
                1L,
                run.metrics
                        .processEventSnapshotBuilds(),
                context);

        for (Map.Entry<String, String> sibling :
                run.scenario.coldSiblingBlueIds
                        .entrySet()) {
            assertEquals(
                    sibling.getValue(),
                    BlueIdCalculator.calculateBlueId(
                            nodeAt(
                                    result.document(),
                                    sibling.getKey())),
                    context + ": cold sibling changed at "
                            + sibling.getKey());
        }
    }

    private static void assertDeterministicCausality(
            Run run) {
        ProcessingConformanceTrace trace =
                run.debug.trace();
        String context =
                run.scenario.emissionMode
                        + "/" + run.variant;
        assertEquals(
                Arrays.asList(
                        EMB3 + "|" + TIMELINE,
                        EMB2 + "|" + TIMELINE,
                        EMB1 + "|" + TIMELINE,
                        ROOT + "|" + TIMELINE),
                externalDeliveryProjection(trace),
                context);
        assertEquals(
                expectedHandlerProjection(
                        run.scenario.emissionMode),
                handlerProjection(trace),
                context);
        assertEquals(
                expectedEffectProjection(
                        run.scenario.emissionMode),
                effectProjection(trace),
                context);

        List<String> expectedEventIds =
                new ArrayList<>(Arrays.asList(
                        run.scenario.events.aBlueId,
                        run.scenario.events
                                .repeatedBlueId,
                        run.scenario.events
                                .repeatedBlueId,
                        run.scenario.events.bBlueId,
                        run.scenario.events.cBlueId));
        if (run.scenario.emissionMode
                == RootEmissionMode.ROOT_D1_D2) {
            expectedEventIds.add(
                    run.scenario.events.d1BlueId);
            expectedEventIds.add(
                    run.scenario.events.d2BlueId);
        }
        List<String> enqueued =
                recordNodeBlueIds(
                        trace.records(
                                ProcessingTraceRecord.Kind
                                        .EVENT_ENQUEUED));
        List<String> dequeued =
                recordNodeBlueIds(
                        trace.records(
                                ProcessingTraceRecord.Kind
                                        .EVENT_DEQUEUED));
        assertEquals(
                expectedEventIds, enqueued, context);
        assertEquals(
                expectedEventIds, dequeued, context);
        assertEquals(
                2,
                Collections.frequency(
                        enqueued,
                        run.scenario.events
                                .repeatedBlueId),
                context
                        + ": identical event enqueue occurrences");
        assertEquals(
                2,
                Collections.frequency(
                        dequeued,
                        run.scenario.events
                                .repeatedBlueId),
                context
                        + ": identical event dequeue occurrences");
        assertEquals(
                8,
                deliveryOccurrenceCount(
                        eventDeliveryProjection(trace),
                        run.scenario.events
                                .repeatedBlueId),
                context
                        + ": identical event delivery occurrences");
        assertEquals(
                expectedEventDeliveryProjection(
                        run.scenario),
                eventDeliveryProjection(trace),
                context);
    }

    private static void assertCheckpointOrder(
            Run run) {
        List<ProcessingTraceRecord> writes =
                run.debug.trace().records(
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE);
        String context =
                run.scenario.emissionMode
                        + "/" + run.variant;
        assertEquals(4, writes.size(), context);
        assertEquals(
                Arrays.asList(
                        EMB3, EMB2, EMB1, ROOT),
                scopeProjection(writes),
                context);
        String expectedSubject =
                run.scenario.evidence
                        .deliveries().get(0)
                        .checkpointSubjectBlueId();
        String expectedEntry =
                TimelineProviderSupport.eventId(
                        run.scenario.exactEvent);
        for (ProcessingTraceRecord write : writes) {
            assertEquals(TIMELINE,
                    write.contractKey(), context);
            assertEquals(
                    expectedSubject,
                    write.detail(
                            ProcessingTraceConstants
                                    .FIELD_SUBJECT),
                    context);
            Node subject = nodeAt(
                    write.node(), "/subject");
            assertEquals(
                    expectedSubject,
                    BlueIdCalculator.calculateBlueId(
                            subject),
                    context);
            assertEquals(
                    BigInteger.valueOf(TIMESTAMP),
                    subject.get("/timestamp"),
                    context);
            assertEquals(
                    expectedEntry,
                    subject.get("/entryBlueId"),
                    context);
        }
        for (String scope :
                Arrays.asList(
                        EMB3, EMB2, EMB1, ROOT)) {
            Node entries = nodeAt(
                    run.debug.processResult().document(),
                    scopePointer(
                            scope,
                            "/contracts/checkpoint/entries"));
            assertNotNull(entries, context);
            assertEquals(
                    Collections.singleton(TIMELINE),
                    entries.getProperties().keySet(),
                    context);
        }
    }

    private static void assertStrictPhysicalLocality(
            Run run) {
        String context =
                run.scenario.emissionMode
                        + "/" + run.variant;
        long totalStoredBytes =
                totalBytes(
                        run.scenario.fragmentBytes);
        long forbiddenStoredBytes =
                bytesFor(
                        run.scenario.fragmentBytes,
                        run.scenario.forbiddenBlueIds);
        assertTrue(
                forbiddenStoredBytes
                        > totalStoredBytes
                        - forbiddenStoredBytes,
                context
                        + ": forbidden large siblings must dominate stored bytes");
        assertTrue(
                bytesFor(
                        run.scenario.fragmentBytes,
                        run.providerMetrics
                                .requestedBlueIds)
                        < forbiddenStoredBytes,
                context
                        + ": selected provider bytes must stay below cold decoy bytes");
        assertTrue(
                Collections.disjoint(
                        run.providerMetrics
                                .requestedBlueIds,
                        run.scenario
                                .forbiddenBlueIds),
                context);
        assertTrue(
                Collections.disjoint(
                        new LinkedHashSet<>(
                                run.debug.trace()
                                        .semanticDemands()),
                        run.scenario
                                .forbiddenBlueIds),
                context);
        assertTrue(
                run.scenario.allowedBlueIds
                        .containsAll(
                                run.providerMetrics
                                        .requestedBlueIds),
                context);
        assertTrue(
                run.scenario.allowedBlueIds
                        .containsAll(
                                run.providerMetrics
                                        .backendLoadedBlueIds),
                context);
        if (run.variant.entryMode
                != EntryMode.INLINE) {
            assertFalse(
                    run.providerMetrics
                            .requestedBlueIds.isEmpty(),
                    context);
            if (run.variant.cacheMode
                    == CacheMode.WARM) {
                assertEquals(
                        0L,
                        run.providerMetrics
                                .backendTrips,
                        context);
            } else {
                assertTrue(
                        run.providerMetrics
                                .backendTrips > 0L,
                        context);
            }
        }
    }

    private static void assertTrueAt(
            Node document,
            String path,
            String context) {
        assertEquals(
                Boolean.TRUE,
                document.get(path),
                context + ": " + path);
    }

    private static void assertOriginalProcessingEventAt(
            Run run,
            String scope,
            String context) {
        String eventPath =
                scopePointer(
                        scope,
                        "/audit/processingEvent");
        String timestampPath =
                scopePointer(
                        scope,
                        "/audit/processingTimestamp");
        Node captured =
                nodeAt(
                        run.debug.processResult()
                                .document(),
                        eventPath);
        assertEquals(
                normalizedJson(
                        run.scenario.exactEvent),
                normalizedJson(captured),
                context + ": " + eventPath);
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        run.scenario.exactEvent),
                BlueIdCalculator.calculateBlueId(
                        captured),
                context + ": " + eventPath);
        assertEquals(
                BigInteger.valueOf(TIMESTAMP),
                run.debug.processResult()
                        .document().get(
                                timestampPath),
                context + ": " + timestampPath);
    }

    private static List<String> expectedHandlerProjection(
            RootEmissionMode mode) {
        List<String> expected =
                new ArrayList<>(Arrays.asList(
                        handler(EMB3, "pulse", TIMELINE),
                        handler(EMB3, "onPulseUpdate", "pulseUpdates"),
                        handler(EMB2, "onLeafPulseUpdate", "leafPulseUpdates"),
                        handler(EMB2, "onSawLeafPulseUpdate", "sawLeafPulseUpdates"),
                        handler(EMB1, "onDeepPulseUpdate", "deepPulseUpdates"),
                        handler(ROOT, "onDeepPulseUpdate", "deepPulseUpdates"),
                        handler(EMB3, "onA", "triggered"),
                        handler(EMB2, "onAFromLeaf", "leafEvents"),
                        handler(EMB2, "onAReceiptUpdate", "aReceiptUpdates"),
                        handler(EMB1, "onEmb2AReceiptUpdate", "emb2AReceiptUpdates"),
                        handler(ROOT, "onEmb2AReceiptUpdate", "emb2AReceiptUpdates"),
                        handler(EMB1, "onAFromLeaf", "leafEvents"),
                        handler(ROOT, "onAFromLeaf", "leafEvents"),
                        handler(EMB3, "onRepeated", "triggered"),
                        handler(EMB2, "onRepeatedFromLeaf", "leafEvents"),
                        handler(EMB1, "onRepeatedFromLeaf", "leafEvents"),
                        handler(ROOT, "onRepeatedFromLeaf", "leafEvents"),
                        handler(EMB3, "onRepeated", "triggered"),
                        handler(EMB2, "onRepeatedFromLeaf", "leafEvents"),
                        handler(EMB1, "onRepeatedFromLeaf", "leafEvents"),
                        handler(ROOT, "onRepeatedFromLeaf", "leafEvents"),
                        handler(EMB2, "onB", "triggered"),
                        handler(EMB1, "onBFromEmb2", "emb2Events"),
                        handler(EMB1, "onBReceiptUpdate", "bReceiptUpdates"),
                        handler(ROOT, "onEmb1BReceiptUpdate", "emb1BReceiptUpdates"),
                        handler(ROOT, "onBFromEmb2", "emb2Events"),
                        handler(EMB1, "onC", "triggered"),
                        handler(ROOT, "onCFromEmb1", "emb1Events"),
                        handler(ROOT, "onCObservationUpdate", "cUpdates")));
        if (mode == RootEmissionMode.ROOT_D1_D2) {
            expected.add(
                    handler(ROOT, "onD1", "triggered"));
            expected.add(
                    handler(ROOT, "onD2", "triggered"));
        }
        expected.add(
                handler(EMB2, "pulse", TIMELINE));
        expected.add(
                handler(EMB1, "pulse", TIMELINE));
        expected.add(
                handler(ROOT, "pulse", TIMELINE));
        return Collections.unmodifiableList(expected);
    }

    private static List<String> expectedEffectProjection(
            RootEmissionMode mode) {
        List<String> expected =
                new ArrayList<>(Arrays.asList(
                        patch(EMB3, EMB3 + "/state/pulseSeen"),
                        patch(EMB3, EMB3 + "/audit/pulseUpdateHandled"),
                        patch(EMB2, EMB2 + "/state/sawEmb3PulseUpdate"),
                        patch(EMB2, EMB2 + "/audit/emb3UpdateReactionHandled"),
                        patch(EMB1, EMB1 + "/state/sawDeepPulseUpdate"),
                        patch(ROOT, "/observed/deepPulseUpdate"),
                        emit(EMB3, "A"),
                        emit(EMB3, "identical-occurrence"),
                        emit(EMB3, "identical-occurrence"),
                        patch(EMB3, EMB3 + "/state/aHandledLocally"),
                        patch(EMB3, EMB3 + "/audit/processingEvent"),
                        patch(EMB3, EMB3 + "/audit/processingTimestamp"),
                        patch(EMB2, EMB2 + "/state/aReceived"),
                        patch(EMB2, EMB2 + "/audit/aReceiveUpdateHandled"),
                        patch(EMB1, EMB1 + "/state/sawEmb2AReceiptUpdate"),
                        patch(ROOT, "/observed/emb2AReceiptUpdate"),
                        patch(EMB2, EMB2 + "/audit/processingEvent"),
                        patch(EMB2, EMB2 + "/audit/processingTimestamp"),
                        emit(EMB2, "B"),
                        patch(EMB1, EMB1 + "/state/aReceived"),
                        patch(ROOT, "/observed/a"),
                        patch(EMB2, EMB2 + "/state/bHandledLocally"),
                        patch(EMB1, EMB1 + "/state/bReceived"),
                        patch(EMB1, EMB1 + "/audit/bReceiveUpdateHandled"),
                        patch(ROOT, "/observed/emb1BReceiptUpdate"),
                        patch(EMB1, EMB1 + "/audit/processingEvent"),
                        patch(EMB1, EMB1 + "/audit/processingTimestamp"),
                        emit(EMB1, "C"),
                        patch(ROOT, "/observed/b"),
                        patch(EMB1, EMB1 + "/state/cHandledLocally"),
                        patch(ROOT, "/observed/c"),
                        patch(ROOT, "/audit/cObservationHandled"),
                        patch(ROOT, "/audit/processingEvent"),
                        patch(ROOT, "/audit/processingTimestamp")));
        if (mode == RootEmissionMode.ROOT_D1_D2) {
            expected.add(emit(ROOT, "D1"));
            expected.add(emit(ROOT, "D2"));
            expected.add(
                    patch(ROOT, "/observed/d1Handled"));
            expected.add(
                    patch(ROOT, "/observed/d2Handled"));
        }
        expected.add(
                patch(EMB2, EMB2 + "/state/directPulseSeen"));
        expected.add(
                patch(EMB1, EMB1 + "/state/directPulseSeen"));
        expected.add(
                patch(ROOT, "/state/directPulseSeen"));
        return Collections.unmodifiableList(expected);
    }

    private static List<String>
    expectedEventDeliveryProjection(
            Scenario scenario) {
        List<String> expected =
                new ArrayList<>(Arrays.asList(
                        delivery(
                                "triggered",
                                EMB3,
                                EMB3,
                                scenario.events.aBlueId),
                        delivery(
                                "embedded",
                                EMB2,
                                EMB3,
                                scenario.events.aBlueId),
                        delivery(
                                "embedded",
                                EMB1,
                                EMB3,
                                scenario.events.aBlueId),
                        delivery(
                                "embedded",
                                ROOT,
                                EMB3,
                                scenario.events.aBlueId),
                        delivery(
                                "triggered",
                                EMB3,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "embedded",
                                EMB2,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "embedded",
                                EMB1,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "embedded",
                                ROOT,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "triggered",
                                EMB3,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "embedded",
                                EMB2,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "embedded",
                                EMB1,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "embedded",
                                ROOT,
                                EMB3,
                                scenario.events
                                        .repeatedBlueId),
                        delivery(
                                "triggered",
                                EMB2,
                                EMB2,
                                scenario.events.bBlueId),
                        delivery(
                                "embedded",
                                EMB1,
                                EMB2,
                                scenario.events.bBlueId),
                        delivery(
                                "embedded",
                                ROOT,
                                EMB2,
                                scenario.events.bBlueId),
                        delivery(
                                "triggered",
                                EMB1,
                                EMB1,
                                scenario.events.cBlueId),
                        delivery(
                                "embedded",
                                ROOT,
                                EMB1,
                                scenario.events.cBlueId)));
        if (scenario.emissionMode
                == RootEmissionMode.ROOT_D1_D2) {
            expected.add(delivery(
                    "triggered",
                    ROOT,
                    ROOT,
                    scenario.events.d1BlueId));
            expected.add(delivery(
                    "triggered",
                    ROOT,
                    ROOT,
                    scenario.events.d2BlueId));
        }
        return Collections.unmodifiableList(expected);
    }

    private static int deliveryOccurrenceCount(
            List<String> deliveries,
            String eventBlueId) {
        int count = 0;
        String suffix = "|" + eventBlueId;
        for (String delivery : deliveries) {
            if (delivery.endsWith(suffix)) {
                count++;
            }
        }
        return count;
    }

    private static String handler(
            String scope,
            String contract,
            String channel) {
        return scope + "|" + contract + "|" + channel;
    }

    private static String patch(
            String scope,
            String path) {
        return "PATCH|" + scope + "|" + path;
    }

    private static String emit(
            String scope,
            String message) {
        return "EMIT|" + scope + "|" + message;
    }

    private static String delivery(
            String mode,
            String receivingScope,
            String sourceScope,
            String eventBlueId) {
        return mode + "|" + receivingScope
                + "|" + sourceScope
                + "|" + eventBlueId;
    }

    private static List<String>
    externalDeliveryProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY)) {
            result.add(
                    record.scopePath()
                            + "|" + record.contractKey());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> handlerProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .HANDLER_EXECUTION)) {
            result.add(handler(
                    record.scopePath(),
                    record.contractKey(),
                    record.detail(
                            ProcessingTraceConstants
                                    .FIELD_CHANNEL_KEY)));
        }
        return Collections.unmodifiableList(result);
    }

    private static SelectedBodies
    observedSelectedBodies(
            ProcessingConformanceTrace trace,
            Scenario scenario) {
        Set<String> selectedBlueIds =
                new LinkedHashSet<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .HANDLER_EXECUTION)) {
            String scope = record.scopePath();
            String contractKey =
                    record.contractKey();
            if (scope == null
                    || scope.isEmpty()
                    || contractKey == null
                    || contractKey.isEmpty()) {
                throw new AssertionError(
                        "Observed handler selection is missing "
                                + "scope or contract key");
            }
            String bodyPointer =
                    scopePointer(
                            scope,
                            "/contracts/"
                                    + pointerSegment(
                                            contractKey)
                                    + "/steps");
            Node body;
            try {
                body = nodeAt(
                        scenario.exactRoot,
                        bodyPointer);
            } catch (IllegalArgumentException
                     missingBody) {
                throw new AssertionError(
                        "Observed handler does not map to an "
                                + "exact authored body at "
                                + bodyPointer,
                        missingBody);
            }
            String bodyBlueId =
                    BlueIdCalculator.calculateBlueId(
                            body);
            Node storedBody =
                    scenario.fragments.get(
                            bodyBlueId);
            Long canonicalBytes =
                    scenario.fragmentBytes.get(
                            bodyBlueId);
            if (!scenario.allowedBlueIds.contains(
                    bodyBlueId)
                    || scenario.forbiddenBlueIds
                    .contains(bodyBlueId)
                    || storedBody == null
                    || canonicalBytes == null
                    || canonicalBytes.longValue()
                    <= 0L
                    || !bodyBlueId.equals(
                    BlueIdCalculator
                            .calculateBlueId(
                                    storedBody))) {
                throw new AssertionError(
                        "Observed selected body is not an exact "
                                + "allowed canonical fragment: "
                                + bodyPointer + " -> "
                                + bodyBlueId);
            }
            selectedBlueIds.add(bodyBlueId);
        }
        if (selectedBlueIds.isEmpty()) {
            throw new AssertionError(
                    "Successful flagship run selected no handler bodies");
        }

        List<String> sortedBlueIds =
                sortedIdentities(
                        selectedBlueIds);
        long selectedBytes = 0L;
        List<String> selectedCanonicalBytes =
                new ArrayList<>();
        for (String blueId : sortedBlueIds) {
            Long canonicalBytes =
                    scenario.fragmentBytes.get(
                            blueId);
            if (canonicalBytes == null) {
                throw new AssertionError(
                        "Selected body has no canonical byte size: "
                                + blueId);
            }
            selectedBytes = Math.addExact(
                    selectedBytes,
                    canonicalBytes.longValue());
            selectedCanonicalBytes.add(
                    blueId + "|"
                            + canonicalBytes.longValue());
        }
        return new SelectedBodies(
                sortedBlueIds,
                Collections.unmodifiableList(
                        selectedCanonicalBytes),
                selectedBytes);
    }

    private static List<String> effectProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records()) {
            if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .DOCUMENT_UPDATE
                    && Objects.equals(
                    record.scopePath(),
                    record.detail(
                            ProcessingTraceConstants
                                    .FIELD_SOURCE_SCOPE_PATH))) {
                result.add(patch(
                        record.scopePath(),
                        record.logicalPath()));
            } else if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .EVENT_ENQUEUED) {
                result.add(emit(
                        record.scopePath(),
                        Objects.toString(
                                record.node()
                                        .get("/message"))));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String>
    eventDeliveryProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .EVENT_DELIVERED)) {
            String mode = record.detail(
                    ProcessingTraceConstants
                            .FIELD_MODE);
            Node traced = record.node();
            String eventBlueId;
            if (ProcessingTraceConstants.MODE_EMBEDDED
                    .equals(mode)) {
                Node eventReference =
                        nodeAt(traced, "/event");
                eventBlueId =
                        eventReference.getBlueId();
            } else {
                eventBlueId =
                        BlueIdCalculator.calculateBlueId(
                                traced);
            }
            result.add(delivery(
                    mode,
                    record.scopePath(),
                    record.detail(
                            ProcessingTraceConstants
                                    .FIELD_SOURCE_SCOPE_PATH),
                    eventBlueId));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> scopeProjection(
            List<ProcessingTraceRecord> records) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record : records) {
            result.add(record.scopePath());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> recordNodeBlueIds(
            List<ProcessingTraceRecord> records) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record : records) {
            result.add(
                    BlueIdCalculator.calculateBlueId(
                            record.node()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> nodeBlueIds(
            List<Node> nodes) {
        List<String> result = new ArrayList<>();
        for (Node node : nodes) {
            result.add(
                    BlueIdCalculator.calculateBlueId(
                            node));
        }
        return Collections.unmodifiableList(result);
    }

    private static String scopePointer(
            String scope,
            String relativePointer) {
        return ROOT.equals(scope)
                ? relativePointer
                : scope + relativePointer;
    }

    private static Node nodeAt(
            Node root,
            String pointer) {
        Node current =
                Objects.requireNonNull(root, "root");
        if (pointer == null
                || pointer.isEmpty()
                || ROOT.equals(pointer)) {
            return current;
        }
        for (String raw :
                pointer.substring(1).split("/")) {
            String segment = raw
                    .replace("~1", "/")
                    .replace("~0", "~");
            if ("contracts".equals(segment)) {
                current = current.getContracts();
                if (current == null) {
                    throw new IllegalArgumentException(
                            "Missing contracts at "
                                    + pointer);
                }
                continue;
            }
            if (current.getProperties() == null) {
                throw new IllegalArgumentException(
                        "Missing object at "
                                + pointer);
            }
            current =
                    current.getProperties().get(
                            segment);
            if (current == null) {
                throw new IllegalArgumentException(
                        "Missing node at "
                                + pointer);
            }
        }
        return current;
    }

    private enum RootEmissionMode {
        DESCENDANTS_ONLY,
        ROOT_D1_D2
    }

    private enum EntryMode {
        INLINE,
        REFERENCES,
        PARTIAL,
        SPLITTER
    }

    private enum CacheMode {
        COLD,
        WARM
    }

    private enum ProviderMode {
        ONE_FRAGMENT,
        BOUNDED_BATCH
    }

    private static final class Variant {
        private final EntryMode entryMode;
        private final CacheMode cacheMode;
        private final ProviderMode providerMode;

        private Variant(
                EntryMode entryMode,
                CacheMode cacheMode,
                ProviderMode providerMode) {
            this.entryMode = entryMode;
            this.cacheMode = cacheMode;
            this.providerMode = providerMode;
        }

        private static List<Variant> matrix() {
            List<Variant> result =
                    new ArrayList<>();
            for (EntryMode entry :
                    EntryMode.values()) {
                for (CacheMode cache :
                        CacheMode.values()) {
                    for (ProviderMode provider :
                            ProviderMode.values()) {
                        result.add(new Variant(
                                entry, cache, provider));
                    }
                }
            }
            return Collections.unmodifiableList(
                    result);
        }

        private Node document(
                Scenario scenario) {
            switch (entryMode) {
                case INLINE:
                    return scenario.exactRoot.clone();
                case REFERENCES:
                    return scenario.documentGraph
                            .pureReference();
                case PARTIAL:
                    return scenario.partialRoot.clone();
                case SPLITTER:
                    return scenario.documentGraph
                            .fragmentedRoot();
                default:
                    throw new IllegalStateException(
                            "Unhandled entry mode");
            }
        }

        private Node event(
                Scenario scenario) {
            switch (entryMode) {
                case INLINE:
                    return scenario.exactEvent.clone();
                case REFERENCES:
                    return scenario.eventGraph
                            .pureReference();
                case PARTIAL:
                    return scenario.partialEvent.clone();
                case SPLITTER:
                    return scenario.eventGraph
                            .fragmentedRoot();
                default:
                    throw new IllegalStateException(
                            "Unhandled entry mode");
            }
        }

        @Override
        public String toString() {
            return entryMode + "/"
                    + cacheMode + "/"
                    + providerMode;
        }
    }

    /**
     * Ordinary BlueId fragments rebuilt from the fixture's explicit
     * participating scopes and executable bodies after processor-owned
     * initialization markers are inserted.
     *
     * <p>The production splitter is covered independently. This flagship's
     * proof surface is the real provider-backed PROCESS matrix, so fixture
     * assembly deliberately does not depend on a second effective-catalog
     * pass over processor markers.</p>
     */
    private static final class DocumentFragmentGraph {
        private final String rootBlueId;
        private final Node fragmentedRoot;
        private final Map<String, Node> fragments;
        private final Set<String>
                forbiddenBlueIds;

        private DocumentFragmentGraph(
                String rootBlueId,
                Node fragmentedRoot,
                Map<String, Node> fragments,
                Set<String> forbiddenBlueIds) {
            this.rootBlueId = rootBlueId;
            this.fragmentedRoot =
                    fragmentedRoot.clone();
            this.fragments =
                    immutableNodeMap(fragments);
            this.forbiddenBlueIds =
                    immutableSet(
                            forbiddenBlueIds);
        }

        private static DocumentFragmentGraph create(
                Node exactRoot) {
            Set<String> scopes =
                    new LinkedHashSet<>(
                            Arrays.asList(
                                    ROOT,
                                    "/coldRoot",
                                    EMB1,
                                    EMB1 + "/coldEmb1",
                                    EMB2,
                                    EMB2 + "/coldEmb2",
                                    EMB3));

            Map<String, Node> scopeFragments =
                    new LinkedHashMap<>();
            Map<String, Node> bodyFragments =
                    new LinkedHashMap<>();
            Set<String> forbidden =
                    new LinkedHashSet<>();
            for (String scope : scopes) {
                Node exactScope =
                        nodeAt(exactRoot, scope);
                Node fragment =
                        exactScope.clone();
                for (String child : scopes) {
                    if (!scope.equals(
                            parentScope(child))) {
                        continue;
                    }
                    String childBlueId =
                            BlueIdCalculator
                                    .calculateBlueId(
                                            nodeAt(
                                                    exactRoot,
                                                    child));
                    replaceAt(
                            fragment,
                            relativePointer(
                                    scope, child),
                            new Node().blueId(
                                    childBlueId));
                }
                Node contracts =
                        exactScope.getContracts();
                for (Map.Entry<String, Node> contract :
                        contracts.getProperties().entrySet()) {
                    Node body = contract.getValue()
                            .getProperties() != null
                            ? contract.getValue()
                            .getProperties().get("steps")
                            : null;
                    if (body == null) {
                        continue;
                    }
                    String pointer =
                            scopePointer(
                                    scope,
                                    "/contracts/"
                                            + pointerSegment(
                                                    contract
                                                            .getKey())
                                            + "/steps");
                    String bodyBlueId =
                            BlueIdCalculator
                                    .calculateBlueId(body);
                    bodyFragments.put(
                            bodyBlueId,
                            body.clone());
                    replaceAt(
                            fragment,
                            relativePointer(
                                    scope,
                                    pointer),
                            new Node().blueId(
                                    bodyBlueId));
                    if (pointer
                            .contains("zzDecoy")) {
                        forbidden.add(
                                bodyBlueId);
                    }
                }
                String scopeBlueId =
                        BlueIdCalculator
                                .calculateBlueId(
                                        exactScope);
                requireSameIdentity(
                        exactScope,
                        fragment,
                        "fragment at " + scope);
                scopeFragments.put(
                        scopeBlueId, fragment);
                if (scope.contains("/cold")) {
                    forbidden.add(
                            scopeBlueId);
                }
            }
            Map<String, Node> all =
                    new LinkedHashMap<>(
                            scopeFragments);
            /*
             * A complete executable body wins if its identity happens to
             * coincide with a shallower structural fragment.
             */
            all.putAll(bodyFragments);
            String rootBlueId =
                    BlueIdCalculator.calculateBlueId(
                            exactRoot);
            Node rootFragment =
                    all.get(rootBlueId);
            if (rootFragment == null) {
                throw new AssertionError(
                        "Initialized Root fragment is missing");
            }
            return new DocumentFragmentGraph(
                    rootBlueId,
                    rootFragment,
                    all,
                    forbidden);
        }

        private Node pureReference() {
            return new Node().blueId(
                    rootBlueId);
        }

        private Node fragmentedRoot() {
            return fragmentedRoot.clone();
        }

        private Map<String, Node> fragments() {
            return immutableNodeMap(
                    fragments);
        }
    }

    private static String pointerSegment(
            String value) {
        return value.replace("~", "~0")
                .replace("/", "~1");
    }

    private static String parentScope(
            String scope) {
        if (scope == null
                || ROOT.equals(scope)) {
            return null;
        }
        int separator =
                scope.lastIndexOf('/');
        return separator == 0
                ? ROOT
                : scope.substring(
                        0, separator);
    }

    private static String relativePointer(
            String scope,
            String absolutePointer) {
        if (ROOT.equals(scope)) {
            return absolutePointer;
        }
        if (!absolutePointer.startsWith(
                scope + "/")) {
            throw new IllegalArgumentException(
                    absolutePointer
                            + " is outside " + scope);
        }
        return absolutePointer.substring(
                scope.length());
    }

    private static void replaceAt(
            Node root,
            String pointer,
            Node replacement) {
        String[] segments =
                pointer.substring(1).split("/");
        Node current = root;
        for (int index = 0;
             index < segments.length - 1;
             index++) {
            String segment = segments[index]
                    .replace("~1", "/")
                    .replace("~0", "~");
            current = "contracts".equals(segment)
                    ? current.getContracts()
                    : current.getProperties().get(
                    segment);
            if (current == null) {
                throw new IllegalArgumentException(
                        "Missing fragment path "
                                + pointer);
            }
        }
        String finalSegment =
                segments[segments.length - 1]
                        .replace("~1", "/")
                        .replace("~0", "~");
        if ("contracts".equals(finalSegment)) {
            root.contracts(
                    replacement);
        } else {
            current.properties(
                    finalSegment,
                    replacement);
        }
    }

    private static final class Scenario {
        private final RootEmissionMode emissionMode;
        private final BlueRepository repository;
        private final Events events;
        private final Node exactRoot;
        private final Node exactEvent;
        private final Node partialRoot;
        private final Node partialEvent;
        private final DocumentFragmentGraph
                documentGraph;
        private final CoordinationDocumentSplitter.SplitGraph
                eventGraph;
        private final VerifiedExecutionEvidence evidence;
        private final Map<String, Node> fragments;
        private final Map<String, Long> fragmentBytes;
        private final Set<String> allowedBlueIds;
        private final Set<String> forbiddenBlueIds;
        private final Map<String, String>
                coldSiblingBlueIds;

        private Scenario(
                RootEmissionMode emissionMode,
                BlueRepository repository,
                Events events,
                Node exactRoot,
                Node exactEvent,
                Node partialRoot,
                Node partialEvent,
                DocumentFragmentGraph
                        documentGraph,
                CoordinationDocumentSplitter.SplitGraph
                        eventGraph,
                VerifiedExecutionEvidence evidence,
                Map<String, Node> fragments,
                Map<String, Long> fragmentBytes,
                Set<String> allowedBlueIds,
                Set<String> forbiddenBlueIds,
                Map<String, String>
                        coldSiblingBlueIds) {
            this.emissionMode = emissionMode;
            this.repository = repository;
            this.events = events;
            this.exactRoot = exactRoot;
            this.exactEvent = exactEvent;
            this.partialRoot = partialRoot;
            this.partialEvent = partialEvent;
            this.documentGraph = documentGraph;
            this.eventGraph = eventGraph;
            this.evidence = evidence;
            this.fragments = fragments;
            this.fragmentBytes = fragmentBytes;
            this.allowedBlueIds = allowedBlueIds;
            this.forbiddenBlueIds =
                    forbiddenBlueIds;
            this.coldSiblingBlueIds =
                    coldSiblingBlueIds;
        }

        private static Scenario create(
                RootEmissionMode emissionMode) {
            BlueRepository repository =
                    BlueRepository.latest();
            Blue blue =
                    CoordinationTestResources
                            .configuredBlue(repository);
            CoordinationProcessors.registerWith(blue);
            try {
                Events events =
                        Events.create(
                                blue, repository);
                Node authored = deepRoot(
                        repository,
                        events,
                        emissionMode);
                Node contractSurfaceRoot =
                        blue.preprocess(authored);
                CoordinationDocumentSplitter splitter =
                        new CoordinationDocumentSplitter(
                                blue.getDocumentProcessor());
                Node exactRoot =
                        initializedWithoutLifecycleHandlers(
                                contractSurfaceRoot);
                Node exactEvent =
                        CoordinationTestResources
                                .operationRequestEvent(
                                        blue,
                                        repository,
                                        "flagship-timeline",
                                        TIMESTAMP,
                                        PULSE_OPERATION,
                                        TIMELINE,
                                        new Node()
                                                .properties(
                                                        "kind",
                                                        new Node()
                                                                .value(
                                                                        "Pulse")));

                DocumentFragmentGraph
                        documentGraph =
                        DocumentFragmentGraph.create(
                                exactRoot);
                CoordinationDocumentSplitter.SplitGraph
                        eventGraph =
                        splitter.splitEvent(
                                exactEvent);
                VerifiedExecutionEvidence evidence =
                        CoordinationRoutingHarness.evidence(
                                blue.getDocumentProcessor(),
                                contractSurfaceRoot,
                                exactRoot,
                                exactEvent,
                                CoordinationRoutingHarness
                                        .DeliveryOccurrence
                                        .at(EMB3, TIMELINE),
                                CoordinationRoutingHarness
                                        .DeliveryOccurrence
                                        .at(EMB2, TIMELINE),
                                CoordinationRoutingHarness
                                        .DeliveryOccurrence
                                        .at(EMB1, TIMELINE),
                                CoordinationRoutingHarness
                                        .DeliveryOccurrence
                                        .at(ROOT, TIMELINE));

                Map<String, Node> fragments =
                        new LinkedHashMap<>();
                fragments.putAll(
                        documentGraph.fragments());
                fragments.putAll(
                        eventGraph.fragments());
                Set<String> forbidden =
                        documentGraph
                                .forbiddenBlueIds;
                Set<String> allowed =
                        new LinkedHashSet<>(
                                fragments.keySet());
                allowed.removeAll(forbidden);
                if (forbidden.isEmpty()) {
                    throw new AssertionError(
                            "Flagship has no forbidden decoy fragments");
                }
                Map<String, Long> fragmentBytes =
                        new LinkedHashMap<>();
                for (Map.Entry<String, Node> fragment :
                        fragments.entrySet()) {
                    fragmentBytes.put(
                            fragment.getKey(),
                            Long.valueOf(
                                    blue.nodeToJson(
                                                    fragment.getValue())
                                            .getBytes(
                                                    StandardCharsets.UTF_8)
                                            .length));
                }

                Node partialRoot =
                        exactRoot.clone();
                String leafBlueId =
                        BlueIdCalculator.calculateBlueId(
                                nodeAt(
                                        exactRoot, EMB3));
                nodeAt(partialRoot, EMB2)
                        .properties(
                                "emb3",
                                new Node().blueId(
                                        leafBlueId));
                requireSameIdentity(
                        exactRoot, partialRoot,
                        "partial Root");

                Node partialEvent =
                        exactEvent.clone();
                Node exactRequest =
                        nodeAt(
                                exactEvent,
                                "/message/request");
                String requestBlueId =
                        BlueIdCalculator.calculateBlueId(
                                exactRequest);
                nodeAt(partialEvent, "/message")
                        .properties(
                                "request",
                                new Node().blueId(
                                        requestBlueId));
                requireSameIdentity(
                        exactEvent, partialEvent,
                        "partial Event");
                if (!fragments.containsKey(
                        requestBlueId)) {
                    throw new AssertionError(
                            "Split Event omitted partial request fragment");
                }

                Map<String, String> cold =
                        new LinkedHashMap<>();
                for (String path :
                        Arrays.asList(
                                "/coldRoot",
                                EMB1 + "/coldEmb1",
                                EMB2 + "/coldEmb2")) {
                    cold.put(
                            path,
                            BlueIdCalculator
                                    .calculateBlueId(
                                            nodeAt(
                                                    exactRoot,
                                                    path)));
                }
                return new Scenario(
                        emissionMode,
                        repository,
                        events,
                        exactRoot.clone(),
                        exactEvent.clone(),
                        partialRoot,
                        partialEvent,
                        documentGraph,
                        eventGraph,
                        evidence,
                        immutableNodeMap(fragments),
                        Collections.unmodifiableMap(
                                fragmentBytes),
                        immutableSet(allowed),
                        immutableSet(forbidden),
                        Collections.unmodifiableMap(
                                cold));
            } finally {
                blue.close();
            }
        }
    }

    private static Set<String> forbiddenBlueIds(
            CoordinationDocumentSplitter.SplitGraph
                    graph) {
        Set<String> result =
                new LinkedHashSet<>();
        for (CoordinationDocumentSplitter
                .FragmentMetadata metadata :
                graph.metadata()) {
            String scope =
                    metadata.scopePath();
            String pointer =
                    metadata.pointer();
            if (scope != null
                    && scope.contains("/cold")
                    || pointer != null
                    && pointer.contains("zzDecoy")) {
                result.add(metadata.blueId());
            }
        }
        return result;
    }

    /**
     * Produces the exact marker state of recursive INITIALIZE for this
     * lifecycle-free fixture. Children are captured before their parents,
     * matching Process Embedded initialization order.
     */
    private static Node initializedWithoutLifecycleHandlers(
            Node preprocessedRoot) {
        Node result =
                preprocessedRoot.clone();
        for (String scope :
                Arrays.asList(
                        EMB3,
                        EMB2 + "/coldEmb2",
                        EMB2,
                        EMB1 + "/coldEmb1",
                        EMB1,
                        "/coldRoot",
                        ROOT)) {
            Node selected =
                    nodeAt(result, scope);
            String initialBlueId =
                    BlueIdCalculator.calculateBlueId(
                            selected);
            selected.getContracts().properties(
                    "initialized",
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds
                                            .PROCESSING_INITIALIZED_MARKER))
                            .properties(
                                    "document",
                                    new Node().blueId(
                                            initialBlueId)));
        }
        return result;
    }

    private static void requireSameIdentity(
            Node expected,
            Node actual,
            String label) {
        String expectedBlueId =
                BlueIdCalculator.calculateBlueId(
                        expected);
        String actualBlueId =
                BlueIdCalculator.calculateBlueId(
                        actual);
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new AssertionError(
                    label + " changed identity from "
                            + expectedBlueId + " to "
                            + actualBlueId);
        }
    }

    private static Map<String, Node>
    immutableNodeMap(
            Map<String, Node> source) {
        Map<String, Node> result =
                new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry :
                source.entrySet()) {
            result.put(
                    entry.getKey(),
                    entry.getValue().clone());
        }
        return Collections.unmodifiableMap(
                result);
    }

    private static Set<String> immutableSet(
            Set<String> source) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(source));
    }

    private static final class Events {
        private final Node a;
        private final Node b;
        private final Node c;
        private final Node repeated;
        private final Node d1;
        private final Node d2;
        private final String aBlueId;
        private final String bBlueId;
        private final String cBlueId;
        private final String repeatedBlueId;
        private final String d1BlueId;
        private final String d2BlueId;

        private Events(
                Node a,
                Node b,
                Node c,
                Node repeated,
                Node d1,
                Node d2) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.repeated = repeated;
            this.d1 = d1;
            this.d2 = d2;
            this.aBlueId =
                    BlueIdCalculator.calculateBlueId(a);
            this.bBlueId =
                    BlueIdCalculator.calculateBlueId(b);
            this.cBlueId =
                    BlueIdCalculator.calculateBlueId(c);
            this.repeatedBlueId =
                    BlueIdCalculator.calculateBlueId(
                            repeated);
            this.d1BlueId =
                    BlueIdCalculator.calculateBlueId(d1);
            this.d2BlueId =
                    BlueIdCalculator.calculateBlueId(d2);
        }

        private static Events create(
                Blue blue,
                BlueRepository repository) {
            return new Events(
                    exactChat(
                            blue, repository, "A"),
                    exactChat(
                            blue, repository, "B"),
                    exactChat(
                            blue, repository, "C"),
                    exactChat(
                            blue, repository,
                            "identical-occurrence"),
                    exactChat(
                            blue, repository, "D1"),
                    exactChat(
                            blue, repository, "D2"));
        }
    }

    private static Node exactChat(
            Blue blue,
            BlueRepository repository,
            String message) {
        return blue.preprocess(
                new Node()
                        .blue(repository
                                .typeAliasBlue())
                        .type(ChatMessage
                                .qualifiedName())
                        .properties(
                                "message",
                                new Node().value(
                                        message)))
                .blue(null);
    }

    private static Node deepRoot(
            BlueRepository repository,
            Events events,
            RootEmissionMode emissionMode) {
        Node emb3 = emb3(events);
        Node emb2 = emb2(events, emb3);
        Node emb1 = emb1(events, emb2);
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "embedded",
                processEmbedded(
                        "/emb1",
                        "/coldRoot"));
        contracts.put(
                TIMELINE,
                TestTimelineProvider.channel(
                        "flagship-timeline"));
        contracts.put(
                "deepPulseUpdates",
                documentUpdateChannel(
                        "/emb1/emb2/emb3/state/pulseSeen"));
        contracts.put(
                "emb2AReceiptUpdates",
                documentUpdateChannel(
                        "/emb1/emb2/state/aReceived"));
        contracts.put(
                "emb1BReceiptUpdates",
                documentUpdateChannel(
                        "/emb1/state/bReceived"));
        contracts.put(
                "cUpdates",
                documentUpdateChannel(
                        "/observed/c"));
        contracts.put(
                "triggered",
                triggeredChannel());
        contracts.put(
                "leafEvents",
                embeddedChannel(
                        "/emb1/emb2/emb3"));
        contracts.put(
                "emb2Events",
                embeddedChannel(
                        "/emb1/emb2"));
        contracts.put(
                "emb1Events",
                embeddedChannel(
                        "/emb1"));
        contracts.put(
                PULSE_OPERATION,
                operationWorkflow(
                        updateStep(
                                "/state/directPulseSeen",
                                true)));
        contracts.put(
                "onDeepPulseUpdate",
                workflow(
                        "deepPulseUpdates",
                        null,
                        updateStep(
                                "/observed/deepPulseUpdate",
                                true)));
        contracts.put(
                "onEmb2AReceiptUpdate",
                workflow(
                        "emb2AReceiptUpdates",
                        null,
                        updateStep(
                                "/observed/emb2AReceiptUpdate",
                                true)));
        contracts.put(
                "onEmb1BReceiptUpdate",
                workflow(
                        "emb1BReceiptUpdates",
                        null,
                        updateStep(
                                "/observed/emb1BReceiptUpdate",
                                true)));
        contracts.put(
                "onCObservationUpdate",
                workflow(
                        "cUpdates",
                        null,
                        updateStep(
                                "/audit/cObservationHandled",
                                true)));
        contracts.put(
                "onAFromLeaf",
                workflow(
                        "leafEvents",
                        events.a,
                        updateStep(
                                "/observed/a",
                                true)));
        contracts.put(
                "onRepeatedFromLeaf",
                workflow(
                        "leafEvents",
                        events.repeated));
        contracts.put(
                "onBFromEmb2",
                workflow(
                        "emb2Events",
                        events.b,
                        updateStep(
                                "/observed/b",
                                true)));
        List<Node> cSteps =
                new ArrayList<>();
        cSteps.add(updateStep(
                "/observed/c", true));
        cSteps.add(
                captureProcessingEvent(
                        "/audit/processingEvent",
                        "/audit/processingTimestamp"));
        if (emissionMode
                == RootEmissionMode.ROOT_D1_D2) {
            cSteps.add(
                    triggerStep(events.d1));
            cSteps.add(
                    triggerStep(events.d2));
        }
        contracts.put(
                "onCFromEmb1",
                workflow(
                        "emb1Events",
                        events.c,
                        cSteps.toArray(
                                new Node[cSteps.size()])));
        contracts.put(
                "onD1",
                workflow(
                        "triggered",
                        events.d1,
                        updateStep(
                                "/observed/d1Handled",
                                true)));
        contracts.put(
                "onD2",
                workflow(
                        "triggered",
                        events.d2,
                        updateStep(
                                "/observed/d2Handled",
                                true)));
        addDecoyOperations(
                contracts, "root");

        return new Node()
                .blue(repository.typeAliasBlue())
                .properties(
                        "state",
                        object(
                                "directPulseSeen",
                                false))
                .properties(
                        "observed",
                        object(
                                "deepPulseUpdate",
                                false,
                                "a",
                                false,
                                "emb2AReceiptUpdate",
                                false,
                                "b",
                                false,
                                "emb1BReceiptUpdate",
                                false,
                                "c",
                                false,
                                "d1Handled",
                                false,
                                "d2Handled",
                                false))
                .properties(
                        "audit",
                        object(
                                "cObservationHandled",
                                false,
                                "processingEvent",
                                emptyObject(),
                                "processingTimestamp",
                                0))
                .properties("emb1", emb1)
                .properties(
                        "coldRoot",
                        coldSibling(
                                "root-cold"))
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static Node emb1(
            Events events,
            Node emb2) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "embedded",
                processEmbedded(
                        "/emb2",
                        "/coldEmb1"));
        contracts.put(
                TIMELINE,
                TestTimelineProvider.channel(
                        "flagship-timeline"));
        contracts.put(
                "deepPulseUpdates",
                documentUpdateChannel(
                        "/emb2/emb3/state/pulseSeen"));
        contracts.put(
                "emb2AReceiptUpdates",
                documentUpdateChannel(
                        "/emb2/state/aReceived"));
        contracts.put(
                "bReceiptUpdates",
                documentUpdateChannel(
                        "/state/bReceived"));
        contracts.put(
                "triggered",
                triggeredChannel());
        contracts.put(
                "leafEvents",
                embeddedChannel(
                        "/emb2/emb3"));
        contracts.put(
                "emb2Events",
                embeddedChannel(
                        "/emb2"));
        contracts.put(
                PULSE_OPERATION,
                operationWorkflow(
                        updateStep(
                                "/state/directPulseSeen",
                                true)));
        contracts.put(
                "onDeepPulseUpdate",
                workflow(
                        "deepPulseUpdates",
                        null,
                        updateStep(
                                "/state/sawDeepPulseUpdate",
                                true)));
        contracts.put(
                "onEmb2AReceiptUpdate",
                workflow(
                        "emb2AReceiptUpdates",
                        null,
                        updateStep(
                                "/state/sawEmb2AReceiptUpdate",
                                true)));
        contracts.put(
                "onBReceiptUpdate",
                workflow(
                        "bReceiptUpdates",
                        null,
                        updateStep(
                                "/audit/bReceiveUpdateHandled",
                                true)));
        contracts.put(
                "onAFromLeaf",
                workflow(
                        "leafEvents",
                        events.a,
                        updateStep(
                                "/state/aReceived",
                                true)));
        contracts.put(
                "onRepeatedFromLeaf",
                workflow(
                        "leafEvents",
                        events.repeated));
        contracts.put(
                "onBFromEmb2",
                workflow(
                        "emb2Events",
                        events.b,
                        updateStep(
                                "/state/bReceived",
                                true),
                        captureProcessingEvent(
                                "/audit/processingEvent",
                                "/audit/processingTimestamp"),
                        triggerStep(events.c)));
        contracts.put(
                "onC",
                workflow(
                        "triggered",
                        events.c,
                        updateStep(
                                "/state/cHandledLocally",
                                true)));
        addDecoyOperations(
                contracts, "emb1");
        return new Node()
                .properties(
                        "state",
                        object(
                                "sawDeepPulseUpdate",
                                false,
                                "aReceived",
                                false,
                                "sawEmb2AReceiptUpdate",
                                false,
                                "bReceived",
                                false,
                                "cHandledLocally",
                                false,
                                "directPulseSeen",
                                false))
                .properties(
                        "audit",
                        object(
                                "bReceiveUpdateHandled",
                                false,
                                "processingEvent",
                                emptyObject(),
                                "processingTimestamp",
                                0))
                .properties("emb2", emb2)
                .properties(
                        "coldEmb1",
                        coldSibling(
                                "emb1-cold"))
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static Node emb2(
            Events events,
            Node emb3) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "embedded",
                processEmbedded(
                        "/emb3",
                        "/coldEmb2"));
        contracts.put(
                TIMELINE,
                TestTimelineProvider.channel(
                        "flagship-timeline"));
        contracts.put(
                "leafPulseUpdates",
                documentUpdateChannel(
                        "/emb3/state/pulseSeen"));
        contracts.put(
                "sawLeafPulseUpdates",
                documentUpdateChannel(
                        "/state/sawEmb3PulseUpdate"));
        contracts.put(
                "aReceiptUpdates",
                documentUpdateChannel(
                        "/state/aReceived"));
        contracts.put(
                "triggered",
                triggeredChannel());
        contracts.put(
                "leafEvents",
                embeddedChannel(
                        "/emb3"));
        contracts.put(
                PULSE_OPERATION,
                operationWorkflow(
                        updateStep(
                                "/state/directPulseSeen",
                                true)));
        contracts.put(
                "onLeafPulseUpdate",
                workflow(
                        "leafPulseUpdates",
                        null,
                        updateStep(
                                "/state/sawEmb3PulseUpdate",
                                true)));
        contracts.put(
                "onSawLeafPulseUpdate",
                workflow(
                        "sawLeafPulseUpdates",
                        null,
                        updateStep(
                                "/audit/emb3UpdateReactionHandled",
                                true)));
        contracts.put(
                "onAReceiptUpdate",
                workflow(
                        "aReceiptUpdates",
                        null,
                        updateStep(
                                "/audit/aReceiveUpdateHandled",
                                true)));
        contracts.put(
                "onAFromLeaf",
                workflow(
                        "leafEvents",
                        events.a,
                        updateStep(
                                "/state/aReceived",
                                true),
                        captureProcessingEvent(
                                "/audit/processingEvent",
                                "/audit/processingTimestamp"),
                        triggerStep(events.b)));
        contracts.put(
                "onRepeatedFromLeaf",
                workflow(
                        "leafEvents",
                        events.repeated));
        contracts.put(
                "onB",
                workflow(
                        "triggered",
                        events.b,
                        updateStep(
                                "/state/bHandledLocally",
                                true)));
        addDecoyOperations(
                contracts, "emb2");
        return new Node()
                .properties(
                        "state",
                        object(
                                "sawEmb3PulseUpdate",
                                false,
                                "aReceived",
                                false,
                                "bHandledLocally",
                                false,
                                "directPulseSeen",
                                false))
                .properties(
                        "audit",
                        object(
                                "emb3UpdateReactionHandled",
                                false,
                                "aReceiveUpdateHandled",
                                false,
                                "processingEvent",
                                emptyObject(),
                                "processingTimestamp",
                                0))
                .properties("emb3", emb3)
                .properties(
                        "coldEmb2",
                        coldSibling(
                                "emb2-cold"))
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static Node emb3(
            Events events) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                TIMELINE,
                TestTimelineProvider.channel(
                        "flagship-timeline"));
        contracts.put(
                "pulseUpdates",
                documentUpdateChannel(
                        "/state/pulseSeen"));
        contracts.put(
                "triggered",
                triggeredChannel());
        contracts.put(
                PULSE_OPERATION,
                operationWorkflow(
                        updateStep(
                                "/state/pulseSeen",
                                true),
                        triggerStep(events.a),
                        triggerStep(events.repeated),
                        triggerStep(events.repeated)));
        contracts.put(
                "onPulseUpdate",
                workflow(
                        "pulseUpdates",
                        null,
                        updateStep(
                                "/audit/pulseUpdateHandled",
                                true)));
        contracts.put(
                "onA",
                workflow(
                        "triggered",
                        events.a,
                        updateStep(
                                "/state/aHandledLocally",
                                true),
                        captureProcessingEvent(
                                "/audit/processingEvent",
                                "/audit/processingTimestamp")));
        contracts.put(
                "onRepeated",
                workflow(
                        "triggered",
                        events.repeated));
        addDecoyOperations(
                contracts, "emb3");
        return new Node()
                .properties(
                        "state",
                        object(
                                "pulseSeen",
                                false,
                                "aHandledLocally",
                                false))
                .properties(
                        "audit",
                        object(
                                "pulseUpdateHandled",
                                false,
                                "processingEvent",
                                emptyObject(),
                                "processingTimestamp",
                                0))
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static Node coldSibling(
            String label) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "triggered",
                triggeredChannel());
        contracts.put(
                "zzDecoyCold",
                workflow(
                        "triggered",
                        new Node()
                                .type(ChatMessage
                                        .qualifiedName())
                                .properties(
                                        "message",
                                        new Node().value(
                                                "never-"
                                                        + label)),
                        largeDecoyStep(
                                label)));
        return new Node()
                .properties(
                        "payload",
                        new Node().value(
                                repeated(
                                        label,
                                        LARGE_DECOY_SIZE)))
                .properties(
                        "state",
                        object(
                                "untouched",
                                true))
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static void addDecoyOperations(
            Map<String, Node> contracts,
            String label) {
        contracts.put(
                "zzDecoyOne",
                operationWorkflow(
                        largeDecoyStep(
                                label + "-one")));
        contracts.put(
                "zzDecoyTwo",
                operationWorkflow(
                        largeDecoyStep(
                                label + "-two")));
    }

    private static Node largeDecoyStep(
            String label) {
        return new Node()
                .type("Coordination/Update Document")
                .properties(
                        "changeset",
                        new Node().items(
                                new Node()
                                        .properties(
                                                "op",
                                                new Node()
                                                        .value(
                                                                "replace"))
                                        .properties(
                                                "path",
                                                new Node()
                                                        .value(
                                                                "/payload"))
                                        .properties(
                                                "val",
                                                new Node()
                                                        .value(
                                                                repeated(
                                                                        label,
                                                                        LARGE_DECOY_SIZE)))));
    }

    private static String repeated(
            String seed,
            int minimumLength) {
        StringBuilder result =
                new StringBuilder(
                        minimumLength
                                + seed.length());
        while (result.length()
                < minimumLength) {
            result.append(seed).append('|');
        }
        return result.toString();
    }

    private static Node processEmbedded(
            String... paths) {
        List<Node> values =
                new ArrayList<>();
        for (String path : paths) {
            values.add(
                    new Node().value(path));
        }
        return new Node()
                .type("Process Embedded")
                .properties(
                        "paths",
                        new Node().items(values));
    }

    private static Node documentUpdateChannel(
            String path) {
        return new Node()
                .type("Document Update Channel")
                .properties(
                        "path",
                        new Node().value(path));
    }

    private static Node triggeredChannel() {
        return new Node()
                .type("Triggered Event Channel");
    }

    private static Node embeddedChannel(
            String sourcePath) {
        return new Node()
                .type("Embedded Node Channel")
                .properties(
                        "sourcePath",
                        new Node().value(
                                sourcePath));
    }

    private static Node operationWorkflow(
            Node... steps) {
        return new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties(
                        "channel",
                        new Node().value(
                                TIMELINE))
                .properties(
                        "steps",
                        new Node().items(
                                steps));
    }

    private static Node workflow(
            String channel,
            Node event,
            Node... steps) {
        Node workflow = new Node()
                .type("Coordination/Sequential Workflow")
                .properties(
                        "channel",
                        new Node().value(
                                channel))
                .properties(
                        "steps",
                        new Node().items(
                                steps));
        if (event != null) {
            workflow.properties(
                    "event", event.clone());
        }
        return workflow;
    }

    private static Node updateStep(
            String path,
            boolean value) {
        return new Node()
                .type("Coordination/Update Document")
                .properties(
                        "changeset",
                        new Node().items(
                                new Node()
                                        .properties(
                                                "op",
                                                new Node()
                                                        .value(
                                                                "replace"))
                                        .properties(
                                                "path",
                                                new Node()
                                                        .value(
                                                                path))
                                        .properties(
                                                "val",
                                                new Node()
                                                        .value(
                                                                value))));
    }

    private static Node triggerStep(
            Node event) {
        return new Node()
                .type("Coordination/Trigger Event")
                .properties(
                        "event", event.clone());
    }

    private static Node captureProcessingEvent(
            String eventPath,
            String timestampPath) {
        return new Node()
                .type("Coordination/Compute")
                .properties(
                        "do",
                        new Node().items(
                                operation(
                                        "$appendChange",
                                        new Node()
                                                .properties(
                                                        "op",
                                                        new Node()
                                                                .value(
                                                                        "replace"))
                                                .properties(
                                                        "path",
                                                        new Node()
                                                                .value(
                                                                        eventPath))
                                                .properties(
                                                        "val",
                                                        binding(
                                                                "processingEvent"))),
                                operation(
                                        "$appendChange",
                                        new Node()
                                                .properties(
                                                        "op",
                                                        new Node()
                                                                .value(
                                                                        "replace"))
                                                .properties(
                                                        "path",
                                                        new Node()
                                                                .value(
                                                                        timestampPath))
                                                .properties(
                                                        "val",
                                                        binding(
                                                                "processingEvent/timestamp"))),
                                operation(
                                        "$return",
                                        new Node()
                                                .properties(
                                                        "changeset",
                                                        operation(
                                                                "$changeset",
                                                                new Node()
                                                                        .value(
                                                                                true))))));
    }

    private static Node operation(
            String name,
            Node value) {
        return new Node().properties(
                name, value);
    }

    private static Node binding(
            String path) {
        return operation(
                "$binding",
                new Node().value(path));
    }

    private static Node emptyObject() {
        return new Node().properties(
                new LinkedHashMap<String, Node>());
    }

    private static String normalizedJson(
            Node node) {
        return UncheckedObjectMapper.JSON_MAPPER
                .writeValueAsString(
                        NodeToMapListOrValue.get(
                                node));
    }

    private static Node object(
            Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Object entries must be key/value pairs");
        }
        Map<String, Node> properties =
                new LinkedHashMap<>();
        for (int index = 0;
             index < entries.length;
             index += 2) {
            properties.put(
                    Objects.toString(
                            entries[index]),
                    entries[index + 1]
                            instanceof Node
                            ? ((Node) entries[index + 1])
                            .clone()
                            : new Node().value(
                                    entries[index + 1]));
        }
        return new Node().properties(
                properties);
    }

    private static final class StrictFragmentProvider
            implements NodeProvider {
        private static final int BATCH_SIZE = 8;

        private final Map<String, Node> backing;
        private final List<String> allowedOrder;
        private final Set<String> forbidden;
        private final ProviderMode providerMode;
        private final Map<String, Node> cache =
                new LinkedHashMap<>();
        private final List<String> requests =
                new ArrayList<>();
        private final Set<String> backendLoaded =
                new LinkedHashSet<>();
        private long backendTrips;

        private StrictFragmentProvider(
                Map<String, Node> backing,
                Set<String> forbidden,
                ProviderMode providerMode) {
            this.backing =
                    new LinkedHashMap<>(backing);
            this.forbidden =
                    new LinkedHashSet<>(
                            forbidden);
            this.providerMode =
                    Objects.requireNonNull(
                            providerMode,
                            "providerMode");
            this.allowedOrder =
                    new ArrayList<>();
            for (String blueId :
                    backing.keySet()) {
                if (!forbidden.contains(
                        blueId)) {
                    allowedOrder.add(blueId);
                }
            }
        }

        @Override
        public synchronized List<Node>
        fetchByBlueId(
                String blueId) {
            if (forbidden.contains(blueId)) {
                throw new AssertionError(
                        "PROCESS demanded forbidden decoy "
                                + blueId);
            }
            Node exact =
                    backing.get(blueId);
            if (exact == null) {
                return null;
            }
            requests.add(blueId);
            Node cached =
                    cache.get(blueId);
            if (cached == null) {
                backendTrips++;
                load(blueId);
                if (providerMode
                        == ProviderMode.BOUNDED_BATCH) {
                    int loaded = 1;
                    for (String candidate :
                            allowedOrder) {
                        if (loaded
                                >= BATCH_SIZE) {
                            break;
                        }
                        if (!cache.containsKey(
                                candidate)) {
                            load(candidate);
                            loaded++;
                        }
                    }
                }
                cached = cache.get(blueId);
            }
            return Collections.singletonList(
                    cached.clone());
        }

        private void load(String blueId) {
            Node exact =
                    backing.get(blueId);
            if (exact == null
                    || cache.containsKey(blueId)) {
                return;
            }
            cache.put(
                    blueId, exact.clone());
            backendLoaded.add(blueId);
        }

        private synchronized void warmAllowed() {
            for (String blueId :
                    allowedOrder) {
                load(blueId);
            }
        }

        private synchronized void resetMetrics() {
            requests.clear();
            backendLoaded.clear();
            backendTrips = 0L;
        }

        private synchronized ProviderMetrics metrics() {
            return new ProviderMetrics(
                    new LinkedHashSet<>(
                            requests),
                    new LinkedHashSet<>(
                            backendLoaded),
                    backendTrips);
        }
    }

    private static final class ProviderMetrics {
        private final Set<String>
                requestedBlueIds;
        private final Set<String>
                backendLoadedBlueIds;
        private final long backendTrips;

        private ProviderMetrics(
                Set<String> requestedBlueIds,
                Set<String> backendLoadedBlueIds,
                long backendTrips) {
            this.requestedBlueIds =
                    Collections.unmodifiableSet(
                            requestedBlueIds);
            this.backendLoadedBlueIds =
                    Collections.unmodifiableSet(
                            backendLoadedBlueIds);
            this.backendTrips = backendTrips;
        }
    }

    private static final class SelectedBodies {
        private final List<String> blueIds;
        private final List<String>
                canonicalBytesByBlueId;
        private final long canonicalBytes;

        private SelectedBodies(
                List<String> blueIds,
                List<String> canonicalBytesByBlueId,
                long canonicalBytes) {
            this.blueIds = blueIds;
            this.canonicalBytesByBlueId =
                    canonicalBytesByBlueId;
            this.canonicalBytes =
                    canonicalBytes;
        }
    }

    private static final class Run {
        private final Scenario scenario;
        private final Variant variant;
        private final ProcessingDebugResult debug;
        private final ProviderMetrics
                providerMetrics;
        private final BexProcessingMetrics metrics;
        private final SelectedBodies
                selectedBodies;

        private Run(
                Scenario scenario,
                Variant variant,
                ProcessingDebugResult debug,
                ProviderMetrics providerMetrics,
                BexProcessingMetrics metrics) {
            this.scenario = scenario;
            this.variant = variant;
            this.debug = debug;
            this.providerMetrics =
                    providerMetrics;
            this.metrics = metrics;
            this.selectedBodies =
                    observedSelectedBodies(
                            debug.trace(),
                            scenario);
        }
    }

    private static final class MatrixResult {
        private final List<Run> runs;
        private final SemanticProjection baseline;

        private MatrixResult(
                List<Run> runs,
                SemanticProjection baseline) {
            this.runs = runs;
            this.baseline = baseline;
        }
    }

    private static final class SemanticProjection {
        private final ProcessorStatus status;
        private final String resultingRootValue;
        private final String resultingRootBlueId;
        private final List<String>
                rootEventBlueIds;
        private final String diagnostic;
        private final long totalGas;
        private final List<String> gasTrace;
        private final List<String>
                processingTrace;
        private final List<String>
                semanticDemands;
        private final List<String>
                checkpointBlueIds;
        private final List<String>
                selectedBodyBlueIds;
        private final List<String>
                selectedBodyCanonicalBytes;
        private final long selectedBodyBytes;

        private SemanticProjection(
                ProcessorStatus status,
                String resultingRootValue,
                String resultingRootBlueId,
                List<String> rootEventBlueIds,
                String diagnostic,
                long totalGas,
                List<String> gasTrace,
                List<String> processingTrace,
                List<String> semanticDemands,
                List<String> checkpointBlueIds,
                List<String> selectedBodyBlueIds,
                List<String> selectedBodyCanonicalBytes,
                long selectedBodyBytes) {
            this.status = status;
            this.resultingRootValue =
                    resultingRootValue;
            this.resultingRootBlueId =
                    resultingRootBlueId;
            this.rootEventBlueIds =
                    rootEventBlueIds;
            this.diagnostic = diagnostic;
            this.totalGas = totalGas;
            this.gasTrace = gasTrace;
            this.processingTrace =
                    processingTrace;
            this.semanticDemands =
                    semanticDemands;
            this.checkpointBlueIds =
                    checkpointBlueIds;
            this.selectedBodyBlueIds =
                    selectedBodyBlueIds;
            this.selectedBodyCanonicalBytes =
                    selectedBodyCanonicalBytes;
            this.selectedBodyBytes =
                    selectedBodyBytes;
        }

        private static SemanticProjection of(
                Run run) {
            ProcessingDebugResult debug =
                    run.debug;
            DocumentProcessingResult result =
                    debug.processResult();
            List<String> checkpoints =
                    new ArrayList<>();
            for (ProcessingTraceRecord record :
                    debug.trace().records(
                            ProcessingTraceRecord.Kind
                                    .CHECKPOINT_WRITE)) {
                checkpoints.add(
                        BlueIdCalculator
                                .calculateBlueId(
                                        record.node()));
            }
            return new SemanticProjection(
                    result.status(),
                    normalizedJson(
                            result.document()),
                    BlueIdCalculator.calculateBlueId(
                            result.document()),
                    nodeBlueIds(result.events()),
                    ProcessingResultTestSupport
                            .diagnosticMessage(result),
                    result.totalGas(),
                    gasProjection(
                            debug.trace()),
                    traceProjection(
                            debug.trace()),
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    debug.trace()
                                            .semanticDemands())),
                    Collections.unmodifiableList(
                            checkpoints),
                    run.selectedBodies.blueIds,
                    run.selectedBodies
                            .canonicalBytesByBlueId,
                    run.selectedBodies
                            .canonicalBytes);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other
                    instanceof SemanticProjection)) {
                return false;
            }
            SemanticProjection that =
                    (SemanticProjection) other;
            return status == that.status
                    && totalGas == that.totalGas
                    && resultingRootValue.equals(
                            that.resultingRootValue)
                    && resultingRootBlueId.equals(
                    that.resultingRootBlueId)
                    && rootEventBlueIds.equals(
                    that.rootEventBlueIds)
                    && Objects.equals(
                    diagnostic, that.diagnostic)
                    && gasTrace.equals(
                    that.gasTrace)
                    && processingTrace.equals(
                    that.processingTrace)
                    && semanticDemands.equals(
                    that.semanticDemands)
                    && checkpointBlueIds.equals(
                    that.checkpointBlueIds)
                    && selectedBodyBlueIds.equals(
                    that.selectedBodyBlueIds)
                    && selectedBodyCanonicalBytes.equals(
                            that.selectedBodyCanonicalBytes)
                    && selectedBodyBytes
                    == that.selectedBodyBytes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    status,
                    resultingRootValue,
                    resultingRootBlueId,
                    rootEventBlueIds,
                    diagnostic,
                    totalGas,
                    gasTrace,
                    processingTrace,
                    semanticDemands,
                    checkpointBlueIds,
                    selectedBodyBlueIds,
                    selectedBodyCanonicalBytes,
                    selectedBodyBytes);
        }

        @Override
        public String toString() {
            return "SemanticProjection{"
                    + "status=" + status
                    + ", root="
                    + resultingRootBlueId
                    + ", events="
                    + rootEventBlueIds
                    + ", selectedBodies="
                    + selectedBodyBlueIds
                    + ", selectedBytes="
                    + selectedBodyBytes
                    + ", gas=" + totalGas
                    + '}';
        }
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> result =
                new ArrayList<>();
        for (GasTraceEntry entry :
                trace.gas()) {
            result.add(
                    entry.sequence()
                            + "|" + entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.logicalPath()
                            + "|" + entry.reason());
        }
        return Collections.unmodifiableList(
                result);
    }

    private static List<String> traceProjection(
            ProcessingConformanceTrace trace) {
        List<String> result =
                new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records()) {
            Node node = record.node();
            result.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (node != null
                            ? BlueIdCalculator
                            .calculateBlueId(node)
                            : null));
        }
        return Collections.unmodifiableList(
                result);
    }
}
