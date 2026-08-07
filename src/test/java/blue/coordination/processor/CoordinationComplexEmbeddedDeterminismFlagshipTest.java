package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
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
import java.util.TreeMap;

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
    private static final String EMB1 =
            "/agreements/agreement-a";
    private static final String EMB2 =
            EMB1 + "/lessons/lesson-a";
    private static final String EMB3 =
            EMB2 + "/cancellations/cancel-a";
    private static final String LESSON_B =
            EMB1 + "/lessons/lesson-b";
    private static final String PAYMENT_A =
            EMB1 + "/payments/payment-a";
    private static final String AGREEMENT_B =
            "/agreements/agreement-b";
    private static final String LESSON_C =
            AGREEMENT_B + "/lessons/lesson-c";
    private static final String TIMELINE = "timeline";
    private static final String PULSE_OPERATION = "pulse";
    private static final int TIMESTAMP = 4242;
    private static final long ROOT_REVISION = 7L;
    private static final ExternalOrderKey ACTIVATION_ORDER =
            ExternalOrderKey.of(
                    Arrays.<Object>asList(
                            6L,
                            "coordination-flagship-activation",
                            0L));
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    Arrays.<Object>asList(
                            7L,
                            "coordination-flagship-event",
                            1L));
    private static final int LARGE_DECOY_SIZE = 64_000;
    private static MatrixResult descendantsOnlyEvidence;
    private static MatrixResult rootD1D2Evidence;

    @AfterAll
    static void shouldWriteEvidenceOnlyAfterBothPublicEventVariantsComplete() {
        // given
        MatrixResult descendantsOnly =
                descendantsOnlyEvidence;
        MatrixResult rootD1D2 =
                rootD1D2Evidence;
        String reportPath =
                System.getProperty(
                        "coordination.flagship.report");

        // when
        if (reportPath == null
                || descendantsOnly == null
                || rootD1D2 == null) {
            return;
        }
        writeObservedArtifact(
                descendantsOnly,
                rootD1D2,
                Paths.get(reportPath));

        // then
        assertEquals(
                32,
                descendantsOnly.runs.size()
                        + rootD1D2.runs.size());
    }

    @Test
    void shouldKeepDescendantEventsInternalAcrossEveryRepresentationProviderVariant() {
        // given
        Scenario descendantsOnlyScenario =
                Scenario.create(RootEmissionMode.DESCENDANTS_ONLY);

        // when
        MatrixResult descendantsOnly =
                executeMatrix(descendantsOnlyScenario);

        // then
        assertDescendantsOnlyPublicEvents(
                descendantsOnly);
        descendantsOnlyEvidence = descendantsOnly;
    }

    @Test
    void shouldExposeOnlyOrderedRootEventsAcrossEveryRepresentationProviderVariant() {
        // given
        Scenario rootD1D2Scenario =
                Scenario.create(RootEmissionMode.ROOT_D1_D2);

        // when
        MatrixResult rootD1D2 =
                executeMatrix(rootD1D2Scenario);

        // then
        assertRootD1D2PublicEvents(
                rootD1D2Scenario,
                rootD1D2);
        rootD1D2Evidence = rootD1D2;
    }

    @Test
    void shouldDeclareTheExecutableGraphAsStableKeyObjectCollections() {
        // given
        Scenario scenario =
                Scenario.create(
                        RootEmissionMode.DESCENDANTS_ONLY);

        // when
        Node root = scenario.exactRoot;

        // then
        assertCollectionMembers(
                root,
                "/agreements",
                "agreement-a",
                "agreement-b");
        assertCollectionMembers(
                root,
                EMB1 + "/lessons",
                "lesson-a",
                "lesson-b");
        assertCollectionMembers(
                root,
                EMB1 + "/payments",
                "payment-a");
        assertCollectionMembers(
                root,
                EMB2 + "/cancellations",
                "cancel-a");
        assertCollectionMembers(
                root,
                AGREEMENT_B + "/lessons",
                "lesson-c");
        assertCollectionPaths(
                root,
                ROOT,
                "/agreements");
        assertCollectionPaths(
                root,
                EMB1,
                "/lessons",
                "/payments");
        assertCollectionPaths(
                root,
                EMB2,
                "/cancellations");
        assertCollectionPaths(
                root,
                AGREEMENT_B,
                "/lessons");
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
                        baseline.platformResult
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
                            run.platformResult.processResult()
                                    .status())
                    .append(" | ")
                    .append(
                            run.platformProviderMetrics
                                    .requestedBlueIds
                                    .size())
                    .append(" | ")
                    .append(
                            run.platformProviderMetrics
                                    .backendLoadedBlueIds
                                    .size())
                    .append(" | ")
                    .append(
                            run.platformProviderMetrics
                                    .backendTrips)
                    .append(" | ")
                    .append(
                            bytesFor(
                                    run.scenario
                                            .fragmentBytes,
                                    run.platformProviderMetrics
                                            .requestedBlueIds))
                    .append(" | ")
                    .append(
                            bytesFor(
                                    run.scenario
                                            .fragmentBytes,
                                    run.platformProviderMetrics
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
                            run.platformResult.processResult()
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
                            ? run.platformProviderMetrics
                            .requestedBlueIds
                            : run.platformProviderMetrics
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
                    DirectBlueIdCalculator.calculateBlueId(
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
        PlatformExecution platformExecution =
                executePlatformCommit(
                        scenario,
                        variant);
        ProcessingDebugResult debug =
                scenario.traceOracle;
        assertSuccessfulProcessingBeforeHandlerProjection(
                scenario,
                variant,
                debug);
        assertPlatformCommitEquivalent(
                scenario,
                variant,
                debug,
                platformExecution.result);
        return new Run(
                scenario,
                variant,
                debug,
                platformExecution.result,
                platformExecution.providerMetrics,
                platformExecution.metrics);
    }

    private static PlatformExecution executePlatformCommit(
            Scenario scenario,
            Variant variant) {
        StrictFragmentProvider fragments =
                new StrictFragmentProvider(
                        scenario.fragments,
                        scenario.forbiddenBlueIds,
                        scenario.selectedPrefetchOrder,
                        scenario.selectedClosure,
                        variant.providerMode);
        if (variant.cacheMode == CacheMode.WARM) {
            fragments.warmSelectedClosure();
        }
        fragments.resetRequestMetrics();
        BexProcessingMetrics metrics =
                new BexProcessingMetrics();
        RepositoryIndependentCoordinationTestRuntime blue =
                RepositoryIndependentCoordinationTestRuntime.create();
        blue.addNodeProvider(fragments);
        blue.configure(
                CoordinationProcessorOptions.builder()
                        .processingMetrics(metrics)
                        .build());
        try {
            EvidenceBundle execution =
                    publicExecutionEvidence(
                            blue,
                            scenario.exactRoot,
                            scenario.exactEvent);
            fragments.resetRequestMetrics();
            PlatformProcessInvocation invocation =
                    PlatformProcessInvocation.builder()
                            .deliveryPlan(
                                    execution.deliveryPlan)
                            .nodeProvider(
                                    blue.nodeProvider())
                            .build();
            PlatformProcessingResult result =
                    blue.contracts()
                            .processForPlatformCommit(
                            variant.document(scenario),
                            variant.event(scenario),
                            invocation);
            return new PlatformExecution(
                    result,
                    fragments.metrics(),
                    metrics);
        } finally {
            blue.close();
        }
    }

    private static void assertPlatformCommitEquivalent(
            Scenario scenario,
            Variant variant,
            ProcessingDebugResult debug,
            PlatformProcessingResult committed) {
        String context = scenario.emissionMode
                + "/" + variant;
        DocumentProcessingResult traced =
                debug.processResult();
        DocumentProcessingResult platform =
                committed.processResult();
        assertEquals(
                traced.status(),
                platform.status(),
                context + ": platform status, traceDiagnostic="
                        + ProcessingResultTestSupport
                        .diagnosticMessage(traced)
                        + ", platformDiagnostic="
                        + ProcessingResultTestSupport
                        .diagnosticMessage(platform));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        traced.document()),
                DirectBlueIdCalculator.calculateBlueId(
                        platform.document()),
                context
                        + ": platform Root semantic value/identity");
        assertEquals(
                nodeBlueIds(traced.events()),
                nodeBlueIds(platform.events()),
                context + ": platform Root events");
        assertEquals(
                traced.totalGas(),
                platform.totalGas(),
                context + ": platform gas");
        assertEquals(
                ProcessingResultTestSupport
                        .diagnosticMessage(traced),
                ProcessingResultTestSupport
                        .diagnosticMessage(platform),
                context + ": platform diagnostic");
        assertEquals(
                scenario.evidence.rootBlueId(),
                committed.commitCompanion()
                        .expectedRootBlueId(),
                context);
        assertEquals(
                scenario.evidence.eventBlueId(),
                committed.commitCompanion()
                        .eventBlueId(),
                context);
        assertEquals(
                ROOT_REVISION,
                committed.commitCompanion()
                        .expectedRootRevision(),
                context);
        assertEquals(
                ROOT_REVISION + 1L,
                committed.commitCompanion()
                        .resultingRootRevision(),
                context);
        assertEquals(
                EVENT_ORDER,
                committed.commitCompanion()
                        .eventOrderKey(),
                context);
        assertTrue(
                committed.commitCompanion()
                        .commitsRootAndOutbox(),
                context);
        assertNotNull(
                committed.commitCompanion()
                        .subscriptionDelta(),
                context);
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
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                failureMessage);
    }

    private static void assertSuccessfulFinalState(
            Run run) {
        DocumentProcessingResult result =
                run.platformResult.processResult();
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
                0L,
                run.metrics
                        .processEventSnapshotBuilds(),
                context
                        + ": hosted BEX must borrow Language's immutable "
                        + "processing-event snapshot without rebuilding it");

        for (Map.Entry<String, String> sibling :
                run.scenario.coldSiblingBlueIds
                        .entrySet()) {
            assertEquals(
                    sibling.getValue(),
                    DirectBlueIdCalculator.calculateBlueId(
                            nodeAt(
                                    result.document(),
                                    sibling.getKey())),
                    context + ": cold sibling changed at "
                            + sibling.getKey());
        }
        for (String changedScope :
                Arrays.asList(
                        ROOT,
                        EMB1,
                        EMB2,
                        EMB3)) {
            String before =
                    DirectBlueIdCalculator.calculateBlueId(
                            nodeAt(
                                    run.scenario.exactRoot,
                                    changedScope));
            String after =
                    DirectBlueIdCalculator.calculateBlueId(
                            nodeAt(
                                    result.document(),
                                    changedScope));
            assertFalse(
                    before.equals(after),
                    context
                            + ": selected identity spine did not change at "
                            + changedScope);
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
                        ROOT, EMB1, EMB2, EMB3),
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
                    DirectBlueIdCalculator.calculateBlueId(
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
                    run.platformResult
                            .processResult().document(),
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
        Set<String> executableBodies =
                executableBodyBlueIds(
                        run.scenario.exactRoot);
        Set<String> selectedBodies =
                new LinkedHashSet<>(
                        run.selectedBodies.blueIds);
        assertEquals(
                run.scenario
                        .selectedExecutableBodyBlueIds,
                selectedBodies,
                context
                        + ": authored selected-body closure changed from observed handlers");
        Set<String> closureExecutableBodies =
                new LinkedHashSet<>(
                        run.scenario.selectedClosure);
        closureExecutableBodies.retainAll(
                executableBodies);
        assertEquals(
                selectedBodies,
                closureExecutableBodies,
                context
                        + ": prefetch closure includes an unselected executable body");
        String selectedOperationBody =
                DirectBlueIdCalculator.calculateBlueId(
                        nodeAt(
                                run.scenario.exactRoot,
                                EMB3
                                        + "/contracts/"
                                        + PULSE_OPERATION
                                        + "/steps"));
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
                        + ": forbidden large siblings must dominate stored bytes"
                        + " (forbidden=" + forbiddenStoredBytes
                        + ", total=" + totalStoredBytes + ")");
        assertTrue(
                Collections.disjoint(
                        new LinkedHashSet<>(
                                run.debug.trace()
                                        .semanticDemands()),
                        run.scenario
                                .forbiddenBlueIds),
                context);
        assertTrue(
                selectedBodies.contains(
                        selectedOperationBody),
                context
                        + ": selected operation body was not executed");
        assertProviderPhysicalLocality(
                run,
                "platform",
                run.platformProviderMetrics,
                executableBodies,
                selectedBodies,
                selectedOperationBody,
                forbiddenStoredBytes);
    }

    private static void assertProviderPhysicalLocality(
            Run run,
            String lane,
            ProviderMetrics provider,
            Set<String> executableBodies,
            Set<String> selectedBodies,
            String selectedOperationBody,
            long forbiddenStoredBytes) {
        String context =
                run.scenario.emissionMode
                        + "/" + run.variant
                        + "/" + lane;
        Set<String> requestedBodies =
                new LinkedHashSet<>(
                        provider.requestedBlueIds);
        requestedBodies.retainAll(
                executableBodies);
        Set<String> loadedBodies =
                new LinkedHashSet<>(
                        provider.backendLoadedBlueIds);
        loadedBodies.retainAll(
                executableBodies);

        assertTrue(
                run.scenario.selectedClosure
                        .containsAll(
                                provider.requestedBlueIds),
                context
                        + ": requests escaped the selected closure: "
                        + provider.requestedBlueIds);
        assertTrue(
                run.scenario.selectedClosure
                        .containsAll(
                                provider.backendLoadedBlueIds),
                context
                        + ": cumulative backend loads escaped the selected closure: "
                        + provider.backendLoadedBlueIds);
        assertTrue(
                Collections.disjoint(
                        provider.requestedBlueIds,
                        run.scenario.forbiddenBlueIds),
                context);
        assertTrue(
                Collections.disjoint(
                        provider.backendLoadedBlueIds,
                        run.scenario.forbiddenBlueIds),
                context);
        assertTrue(
                selectedBodies.containsAll(
                        requestedBodies),
                context
                        + ": an unselected executable body was requested: "
                        + requestedBodies);
        assertTrue(
                selectedBodies.containsAll(
                        loadedBodies),
                context
                        + ": an unselected executable body was loaded: "
                        + loadedBodies);
        assertTrue(
                bytesFor(
                        run.scenario.fragmentBytes,
                        provider.requestedBlueIds)
                        < forbiddenStoredBytes,
                context
                        + ": requested bytes must stay below cold decoy bytes");
        assertTrue(
                bytesFor(
                        run.scenario.fragmentBytes,
                        provider.backendLoadedBlueIds)
                        < forbiddenStoredBytes,
                context
                        + ": cumulative backend-loaded bytes must stay below cold decoy bytes");

        if (run.variant.cacheMode
                == CacheMode.WARM) {
            assertEquals(
                    run.scenario.selectedClosure,
                    provider.backendLoadedBlueIds,
                    context
                            + ": warm prefetch must expose the exact selected closure");
            assertEquals(
                    0L,
                    provider.backendTrips,
                    context);
        } else if (run.variant.entryMode
                == EntryMode.INLINE) {
            assertTrue(
                    provider.backendLoadedBlueIds.isEmpty(),
                    context
                            + ": cold inline execution must not load fragments");
            assertEquals(
                    0L,
                    provider.backendTrips,
                    context);
        } else {
            assertTrue(
                    provider.backendTrips > 0L,
                    context);
        }

        if (run.variant.entryMode
                != EntryMode.INLINE) {
            assertFalse(
                    provider.requestedBlueIds.isEmpty(),
                    context);
            assertTrue(
                    requestedBodies.contains(
                            selectedOperationBody),
                    context
                            + ": fragmented run did not request the selected operation body");
            assertTrue(
                    requestedBodies.size() > 1,
                    context
                            + ": causally reached listener bodies were not requested on demand");
        }
    }

    private static Set<String> executableBodyBlueIds(
            Node root) {
        Set<String> result =
                new LinkedHashSet<>();
        for (String scope :
                Arrays.asList(
                        ROOT,
                        EMB1,
                        EMB2,
                        EMB3,
                        LESSON_B,
                        PAYMENT_A,
                        AGREEMENT_B,
                        LESSON_C)) {
            Node contracts =
                    nodeAt(root, scope)
                            .getContracts();
            for (Node contract :
                    contracts.getProperties()
                            .values()) {
                Node steps = contract.getProperties()
                        == null
                        ? null
                        : contract.getProperties()
                        .get("steps");
                if (steps != null) {
                    result.add(
                            DirectBlueIdCalculator
                                    .calculateBlueId(
                                            steps));
                }
            }
        }
        return result;
    }

    private static Set<String> selectedExecutableBodyBlueIds(
            Node root,
            RootEmissionMode emissionMode) {
        Set<String> selected =
                new LinkedHashSet<>();
        for (String handler :
                expectedHandlerProjection(
                        emissionMode)) {
            String[] components =
                    handler.split("\\|", 3);
            if (components.length != 3) {
                throw new AssertionError(
                        "Invalid expected handler projection: "
                                + handler);
            }
            Node body = nodeAt(
                    root,
                    scopePointer(
                            components[0],
                            "/contracts/"
                                    + pointerSegment(
                                    components[1])
                                    + "/steps"));
            selected.add(
                    DirectBlueIdCalculator.calculateBlueId(
                            body));
        }
        if (selected.isEmpty()) {
            throw new AssertionError(
                    "Flagship selected executable-body closure is empty");
        }
        return immutableSet(selected);
    }

    private static List<String> selectedPrefetchOrder(
            Node exactRoot,
            DocumentFragmentGraph documentGraph,
            CoordinationDocumentSplitter.SplitGraph eventGraph,
            Set<String> selectedExecutableBodies) {
        LinkedHashSet<String> order =
                new LinkedHashSet<>();
        order.add(documentGraph.rootBlueId);
        order.add(eventGraph.rootBlueId());
        order.addAll(eventGraph.fragments().keySet());
        for (String selectedScope :
                Arrays.asList(
                        EMB1,
                        EMB2,
                        EMB3)) {
            order.add(
                    DirectBlueIdCalculator.calculateBlueId(
                            nodeAt(
                                    exactRoot,
                                    selectedScope)));
        }
        order.addAll(selectedExecutableBodies);
        return Collections.unmodifiableList(
                new ArrayList<>(order));
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
                        run.platformResult.processResult()
                                .document(),
                        eventPath);
        assertEquals(
                normalizedJson(
                        run.scenario.exactEvent),
                normalizedJson(captured),
                context + ": " + eventPath);
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        run.scenario.exactEvent),
                DirectBlueIdCalculator.calculateBlueId(
                        captured),
                context + ": " + eventPath);
        assertEquals(
                BigInteger.valueOf(TIMESTAMP),
                run.platformResult.processResult()
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
                    DirectBlueIdCalculator.calculateBlueId(
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
                    DirectBlueIdCalculator
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
                        DirectBlueIdCalculator.calculateBlueId(
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
                    DirectBlueIdCalculator.calculateBlueId(
                            record.node()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> nodeBlueIds(
            List<Node> nodes) {
        List<String> result = new ArrayList<>();
        for (Node node : nodes) {
            result.add(
                    DirectBlueIdCalculator.calculateBlueId(
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

    private static void assertCollectionMembers(
            Node root,
            String collectionPath,
            String... expectedMemberKeys) {
        Node collection =
                nodeAt(root, collectionPath);
        assertNotNull(
                collection.getProperties(),
                collectionPath
                        + " must be a stable-key object collection");
        assertNull(
                collection.getItems(),
                collectionPath
                        + " must not be a list-position collection");
        assertEquals(
                new LinkedHashSet<>(
                        Arrays.asList(
                                expectedMemberKeys)),
                new LinkedHashSet<>(
                        collection.getProperties()
                                .keySet()),
                collectionPath);
    }

    private static void assertCollectionPaths(
            Node root,
            String scope,
            String... expectedCollectionPaths) {
        Node processEmbedded =
                nodeAt(
                        root,
                        scopePointer(
                                scope,
                                "/contracts/embedded"));
        assertNull(
                processEmbedded.getProperties()
                        .get("paths"),
                scope + " must not declare legacy paths");
        Node collectionPaths =
                processEmbedded.getProperties()
                        .get("collectionPaths");
        assertNotNull(
                collectionPaths,
                scope + " collectionPaths");
        List<String> actual =
                new ArrayList<>();
        for (Node path : collectionPaths.getItems()) {
            actual.add(
                    Objects.toString(
                            path.getValue()));
        }
        assertEquals(
                Arrays.asList(
                        expectedCollectionPaths),
                actual,
                scope);
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
     * <p>The backing inventory remains the fixture's exact canonical graph,
     * while PROCESS-visible identities use the production splitter's
     * ephemeral header views. This preserves the physical-fragment proof and
     * gives Language the same selective materialization surface used by the
     * production Coordination path.</p>
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
                Node exactRoot,
                CoordinationDocumentSplitter.SplitGraph
                        productionGraph) {
            Set<String> scopes =
                    new LinkedHashSet<>(
                            Arrays.asList(
                                    ROOT,
                                    EMB1,
                                    EMB2,
                                    EMB3,
                                    LESSON_B,
                                    PAYMENT_A,
                                    AGREEMENT_B,
                                    LESSON_C));

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
                            owningScope(
                                    child,
                                    scopes))) {
                        continue;
                    }
                    String childBlueId =
                            DirectBlueIdCalculator
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
                            DirectBlueIdCalculator
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
                        addDescendantIdentities(
                                body,
                                forbidden);
                    }
                }
                String scopeBlueId =
                        DirectBlueIdCalculator
                                .calculateBlueId(
                                        exactScope);
                requireSameIdentity(
                        exactScope,
                        fragment,
                        "fragment at " + scope);
                scopeFragments.put(
                        scopeBlueId, fragment);
                if (isUnrelatedScope(scope)) {
                    addDescendantIdentities(
                            exactScope,
                            forbidden);
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
            for (String blueId :
                    productionGraph.fragments().keySet()) {
                List<Node> processViews =
                        productionGraph.provider()
                                .fetchByBlueId(blueId);
                if (processViews != null
                        && processViews.size() == 1) {
                    all.put(
                            blueId,
                            processViews.get(0).clone());
                }
            }
            Node headerRoot =
                    productionGraph.processingRootView();
            Map<String, Node> mutationViews =
                    selectedMutationViews(
                            exactRoot,
                            headerRoot,
                            scopes);
            all.putAll(mutationViews);
            String rootBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            exactRoot);
            Node rootFragment =
                    mutationViews.get(rootBlueId);
            if (rootFragment == null) {
                throw new AssertionError(
                        "Selected-chain PROCESS Root view is missing");
            }
            return new DocumentFragmentGraph(
                    rootBlueId,
                    rootFragment,
                    all,
                    forbidden);
        }

        private static void addDescendantIdentities(
                Node node,
                Set<String> identities) {
            if (node == null) {
                return;
            }
            identities.add(
                    DirectBlueIdCalculator.calculateBlueId(
                            node));
            if (node.isReferenceOnly()) {
                return;
            }
            /*
             * Type definitions are shared runtime dependencies, not physical
             * descendants owned by a cold document branch. Classifying their
             * references as forbidden would reject legitimate type
             * resolution while inspecting an otherwise body-free header.
             */
            addDescendantIdentities(
                    node.getContracts(),
                    identities);
            if (node.getProperties() != null) {
                for (Node child :
                        node.getProperties().values()) {
                    addDescendantIdentities(
                            child,
                            identities);
                }
            }
            if (node.getItems() != null) {
                for (Node child : node.getItems()) {
                    addDescendantIdentities(
                            child,
                            identities);
                }
            }
        }

        /**
         * Builds identity-equivalent PROCESS views with the complete selected
         * mutation spine inline. Ordinary state below the selected scopes is
         * therefore authored patch input rather than a provider-provenance
         * wrapper. Unrelated collection members retain only the splitter's
         * body-free PROCESS headers, and every executable body remains an
         * exact pure reference.
         */
        private static Map<String, Node> selectedMutationViews(
                Node exactRoot,
                Node headerRoot,
                Set<String> allScopes) {
            List<String> selectedScopes =
                    Arrays.asList(
                            ROOT,
                            EMB1,
                            EMB2,
                            EMB3);
            Map<String, Node> byPath =
                    new LinkedHashMap<>();
            for (int index = selectedScopes.size() - 1;
                 index >= 0;
                 index--) {
                String scope = selectedScopes.get(index);
                Node exactScope = nodeAt(
                        exactRoot,
                        scope);
                Node view = exactScope.clone();
                collapseExecutableBodies(view);
                for (String child : allScopes) {
                    if (!scope.equals(
                            owningScope(
                                    child,
                                    allScopes))) {
                        continue;
                    }
                    Node selectedChild =
                            byPath.get(child);
                    Node replacement =
                            selectedChild != null
                                    ? selectedChild.clone()
                                    : coldScopeHeader(
                                            nodeAt(
                                                    headerRoot,
                                                    child));
                    replaceAt(
                            view,
                            relativePointer(
                                    scope,
                                    child),
                            replacement);
                }
                requireSameIdentity(
                        exactScope,
                        view,
                        "selected mutation PROCESS view at "
                                + scope);
                byPath.put(scope, view);
            }
            Map<String, Node> byBlueId =
                    new LinkedHashMap<>();
            for (String scope : selectedScopes) {
                Node exactScope = nodeAt(
                        exactRoot,
                        scope);
                byBlueId.put(
                        DirectBlueIdCalculator
                                .calculateBlueId(
                                        exactScope),
                        byPath.get(scope).clone());
            }
            return byBlueId;
        }

        private static Node coldScopeHeader(
                Node header) {
            Node result = header.clone();
            collapseColdState(result);
            requireSameIdentity(
                    header,
                    result,
                    "cold scope PROCESS header");
            return result;
        }

        private static void collapseColdState(
                Node node) {
            if (node == null) {
                return;
            }
            Map<String, Node> properties =
                    node.getProperties();
            if (properties != null) {
                for (Map.Entry<String, Node> entry :
                        properties.entrySet()) {
                    Node child = entry.getValue();
                    if (child == null) {
                        continue;
                    }
                    if ("payload".equals(entry.getKey())
                            || "state".equals(entry.getKey())
                            || "audit".equals(entry.getKey())) {
                        if (!child.isReferenceOnly()) {
                            entry.setValue(
                                    new Node().blueId(
                                            DirectBlueIdCalculator
                                                    .calculateBlueId(
                                                            child)));
                        }
                        continue;
                    }
                    collapseColdState(child);
                }
            }
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    collapseColdState(item);
                }
            }
        }

        private static void collapseExecutableBodies(
                Node scopeView) {
            Node contracts = scopeView.getContracts();
            if (contracts == null
                    || contracts.getProperties() == null) {
                return;
            }
            for (Node contract :
                    contracts.getProperties().values()) {
                Node body = contract != null
                        && contract.getProperties() != null
                        ? contract.getProperties().get("steps")
                        : null;
                if (body == null
                        || body.isReferenceOnly()) {
                    continue;
                }
                contract.getProperties().put(
                        "steps",
                        new Node().blueId(
                                DirectBlueIdCalculator
                                        .calculateBlueId(
                                                body)));
            }
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

    private static String owningScope(
            String child,
            Set<String> scopes) {
        if (child == null
                || ROOT.equals(child)) {
            return null;
        }
        String owner = ROOT;
        for (String candidate : scopes) {
            if (ROOT.equals(candidate)
                    || candidate.equals(child)
                    || !child.startsWith(candidate + "/")) {
                continue;
            }
            if (owner.equals(ROOT)
                    || candidate.length() > owner.length()) {
                owner = candidate;
            }
        }
        return owner;
    }

    private static boolean isUnrelatedScope(
            String scope) {
        return LESSON_B.equals(scope)
                || PAYMENT_A.equals(scope)
                || AGREEMENT_B.equals(scope)
                || LESSON_C.equals(scope);
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
        private final ProcessingDebugResult traceOracle;
        private final Map<String, Node> fragments;
        private final Map<String, Long> fragmentBytes;
        private final Set<String> allowedBlueIds;
        private final Set<String> forbiddenBlueIds;
        private final List<String> selectedPrefetchOrder;
        private final Set<String> selectedClosure;
        private final Set<String>
                selectedExecutableBodyBlueIds;
        private final Map<String, String>
                coldSiblingBlueIds;

        private Scenario(
                RootEmissionMode emissionMode,
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
                ProcessingDebugResult traceOracle,
                Map<String, Node> fragments,
                Map<String, Long> fragmentBytes,
                Set<String> allowedBlueIds,
                Set<String> forbiddenBlueIds,
                List<String> selectedPrefetchOrder,
                Set<String> selectedClosure,
                Set<String> selectedExecutableBodyBlueIds,
                Map<String, String>
                        coldSiblingBlueIds) {
            this.emissionMode = emissionMode;
            this.events = events;
            this.exactRoot = exactRoot;
            this.exactEvent = exactEvent;
            this.partialRoot = partialRoot;
            this.partialEvent = partialEvent;
            this.documentGraph = documentGraph;
            this.eventGraph = eventGraph;
            this.evidence = evidence;
            this.traceOracle = Objects.requireNonNull(
                    traceOracle, "traceOracle");
            this.fragments = fragments;
            this.fragmentBytes = fragmentBytes;
            this.allowedBlueIds = allowedBlueIds;
            this.forbiddenBlueIds =
                    forbiddenBlueIds;
            this.selectedPrefetchOrder =
                    selectedPrefetchOrder;
            this.selectedClosure = selectedClosure;
            this.selectedExecutableBodyBlueIds =
                    selectedExecutableBodyBlueIds;
            this.coldSiblingBlueIds =
                    coldSiblingBlueIds;
        }

        private static Scenario create(
                RootEmissionMode emissionMode) {
            RepositoryIndependentCoordinationTestRuntime blue =
                    RepositoryIndependentCoordinationTestRuntime.create();
            try {
                Events events =
                        Events.create();
                Node authored = deepRoot(
                        events,
                        emissionMode);
                Node contractSurfaceRoot =
                        blue.preprocess(authored);
                CoordinationDocumentSplitter splitter =
                        new CoordinationDocumentSplitter(
                                blue.contracts());
                Node exactRoot =
                        initializedWithoutLifecycleHandlers(
                                contractSurfaceRoot);
                CoordinationDocumentSplitter.SplitGraph
                        productionDocumentGraph =
                        splitter.splitDocument(
                                exactRoot);
                Node exactEvent =
                        RepositoryIndependentCoordinationTypes
                                .operationRequestTimelineEntry(
                                        "flagship-timeline",
                                        "flagship-timeline",
                                        BigInteger.valueOf(
                                                TIMESTAMP),
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
                                exactRoot,
                                productionDocumentGraph);
                CoordinationDocumentSplitter.SplitGraph
                        eventGraph =
                        splitter.splitEvent(
                                exactEvent);
                EvidenceBundle execution =
                        publicExecutionEvidence(
                                blue,
                                exactRoot,
                                exactEvent);
                // Language currently exposes the conformance trace only on
                // the non-invocation debug facade. Capture one fully inline
                // observational oracle; every matrix cell still performs its
                // own public invocation and must equal this oracle.
                blue.configureDeliveryPlanDeriver(
                        (root, event) ->
                                execution.deliveryPlan);
                ProcessingDebugResult traceOracle =
                        blue.processor()
                                .processDocumentWithTrace(
                                        exactRoot.clone(),
                                        exactEvent.clone(),
                                        execution.evidence);
                assertEquals(
                        ProcessorStatus.SUCCESS,
                        traceOracle.processResult()
                                .status(),
                        ProcessingResultTestSupport
                                .diagnosticMessage(
                                        traceOracle
                                                .processResult()));

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
                Set<String> selectedExecutableBodies =
                        selectedExecutableBodyBlueIds(
                                exactRoot,
                                emissionMode);
                List<String> selectedPrefetchOrder =
                        selectedPrefetchOrder(
                                exactRoot,
                                documentGraph,
                                eventGraph,
                                selectedExecutableBodies);
                Set<String> selectedClosure =
                        new LinkedHashSet<>(
                                selectedPrefetchOrder);
                if (!fragments.keySet().containsAll(
                        selectedClosure)) {
                    Set<String> missing =
                            new LinkedHashSet<>(
                                    selectedClosure);
                    missing.removeAll(
                            fragments.keySet());
                    throw new AssertionError(
                            "Selected prefetch closure is missing exact fragments: "
                                    + missing);
                }
                if (!Collections.disjoint(
                        selectedClosure,
                        forbidden)) {
                    throw new AssertionError(
                            "Selected prefetch closure contains forbidden decoys");
                }
                if (!allowed.containsAll(
                        selectedClosure)) {
                    throw new AssertionError(
                            "Selected prefetch closure escaped the allowed inventory");
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
                        DirectBlueIdCalculator.calculateBlueId(
                                nodeAt(
                                        exactRoot, EMB3));
                replaceAt(
                        partialRoot,
                        EMB3,
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
                        DirectBlueIdCalculator.calculateBlueId(
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
                                LESSON_B,
                                PAYMENT_A,
                                AGREEMENT_B)) {
                    cold.put(
                            path,
                            DirectBlueIdCalculator
                                    .calculateBlueId(
                                            nodeAt(
                                                    exactRoot,
                                                    path)));
                }
                return new Scenario(
                        emissionMode,
                        events,
                        exactRoot.clone(),
                        exactEvent.clone(),
                        partialRoot,
                        partialEvent,
                        documentGraph,
                        eventGraph,
                        execution.evidence,
                        traceOracle,
                        immutableNodeMap(fragments),
                        Collections.unmodifiableMap(
                                fragmentBytes),
                        immutableSet(allowed),
                        immutableSet(forbidden),
                        Collections.unmodifiableList(
                                new ArrayList<>(
                                        selectedPrefetchOrder)),
                        immutableSet(selectedClosure),
                        immutableSet(
                                selectedExecutableBodies),
                        Collections.unmodifiableMap(
                                cold));
            } finally {
                blue.close();
            }
        }
    }

    private static EvidenceBundle publicExecutionEvidence(
            RepositoryIndependentCoordinationTestRuntime runtime,
            Node exactRoot,
            Node exactEvent) {
        SubscriptionDelta initial =
                runtime.subscriptionSurfaceProjection().projectInitial(
                        exactRoot,
                        ROOT_REVISION,
                        ACTIVATION_ORDER);
        assertTrue(initial.removed().isEmpty());
        assertFalse(initial.added().isEmpty());
        for (SubscriptionDelta.Entry interval
                : initial.added()) {
            assertEquals(
                    Long.valueOf(ROOT_REVISION),
                    interval.activationRootRevision());
            assertEquals(
                    ACTIVATION_ORDER,
                    interval.startAfterExternalOrderKey());
            assertNull(interval.endAtRootRevision());
        }
        assertTrue(
                EVENT_ORDER.compareTo(
                        ACTIVATION_ORDER) > 0);

        SubscriptionDelta contractsInitial = runtime.contracts()
                .subscriptionSurfaceProjection()
                .projectInitial(
                        exactRoot,
                        ROOT_REVISION,
                        ACTIVATION_ORDER);
        ExternalDeliveryPlan currentRoot =
                runtime.currentRootDeliveryPlanDeriver(
                                ROOT_REVISION,
                                EVENT_ORDER,
                                contractsInitial.added())
                        .derive(exactRoot, exactEvent);
        List<ExternalSubscriptionOccurrenceKey> candidates =
                new ArrayList<>();
        List<String> selectedOccurrences =
                new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : currentRoot.deliveries()) {
            candidates.add(
                    ExternalSubscriptionOccurrenceKey.of(
                            delivery.scopePath(),
                            delivery.channelKey()));
            selectedOccurrences.add(
                    delivery.scopePath()
                            + "|"
                            + delivery.channelKey());
        }
        assertEquals(
                Arrays.asList(
                        EMB3 + "|" + TIMELINE,
                        EMB2 + "|" + TIMELINE,
                        EMB1 + "|" + TIMELINE,
                        ROOT + "|" + TIMELINE),
                selectedOccurrences);

        IndexedDeliveryPreparation indexed =
                runtime.indexedDeliveryEvaluator().prepare(
                        exactRoot,
                        exactEvent,
                        ROOT_REVISION,
                        EVENT_ORDER,
                        initial.added(),
                        candidates);
        assertEquivalentDeliveryPlans(
                currentRoot,
                indexed.deliveryPlan());
        VerifiedExecutionEvidence evidence =
                runtime.executionEvidence(
                        exactRoot,
                        exactEvent,
                        indexed.deliveryPlan());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        exactRoot),
                evidence.rootBlueId());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        exactEvent),
                evidence.eventBlueId());
        assertEquals(
                selectedOccurrences,
                evidenceDeliveryProjection(evidence));
        return new EvidenceBundle(
                indexed.deliveryPlan(),
                evidence);
    }

    private static final class EvidenceBundle {
        private final ExternalDeliveryPlan deliveryPlan;
        private final VerifiedExecutionEvidence evidence;

        private EvidenceBundle(
                ExternalDeliveryPlan deliveryPlan,
                VerifiedExecutionEvidence evidence) {
            this.deliveryPlan = deliveryPlan;
            this.evidence = evidence;
        }
    }

    private static void assertEquivalentDeliveryPlans(
            ExternalDeliveryPlan currentRoot,
            ExternalDeliveryPlan indexed) {
        assertEquals(
                currentRoot.managedRootRevision(),
                indexed.managedRootRevision());
        assertEquals(
                currentRoot.indexedRootRevision(),
                indexed.indexedRootRevision());
        assertEquals(
                currentRoot.eventOrderKey(),
                indexed.eventOrderKey());
        assertEquals(
                currentRoot.hasActiveSubscriptionIntervals(),
                indexed.hasActiveSubscriptionIntervals());
        assertEquals(
                currentRoot.availableExactNodeBlueIds(),
                indexed.availableExactNodeBlueIds());
        assertEquals(
                currentRoot.requiredExactNodeBlueIds(),
                indexed.requiredExactNodeBlueIds());
        assertEquals(
                currentRoot.exactRuntimeState(),
                indexed.exactRuntimeState());
        assertEquals(
                currentRoot.deliveries().size(),
                indexed.deliveries().size());
        for (int index = 0;
                index < currentRoot.deliveries().size();
                index++) {
            assertEquivalentDelivery(
                    currentRoot.deliveries().get(index),
                    indexed.deliveries().get(index));
        }
    }

    private static void assertEquivalentDelivery(
            ExternalDeliverySnapshot currentRoot,
            ExternalDeliverySnapshot indexed) {
        assertEquals(
                currentRoot.scopePath(),
                indexed.scopePath());
        assertEquals(
                currentRoot.channelKey(),
                indexed.channelKey());
        assertEquals(
                currentRoot.order(),
                indexed.order());
        assertEquals(
                currentRoot.sourceContributionNodeBlueIds(),
                indexed.sourceContributionNodeBlueIds());
        assertEquals(
                currentRoot.effectiveTypeBlueId(),
                indexed.effectiveTypeBlueId());
        assertEquals(
                currentRoot.subscriptionKeys(),
                indexed.subscriptionKeys());
        assertEquals(
                currentRoot.checkpointDomainBlueId(),
                indexed.checkpointDomainBlueId());
        assertEquals(
                currentRoot.checkpointSubjectBlueId(),
                indexed.checkpointSubjectBlueId());
        assertEquals(
                currentRoot.activationStartExclusive(),
                indexed.activationStartExclusive());
        assertEquals(
                currentRoot.activationEndInclusive(),
                indexed.activationEndInclusive());
    }

    private static List<String> evidenceDeliveryProjection(
            VerifiedExecutionEvidence evidence) {
        List<String> result = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : evidence.deliveries()) {
            result.add(
                    delivery.scopePath()
                            + "|"
                            + delivery.channelKey());
        }
        return Collections.unmodifiableList(result);
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
                        EMB2,
                        LESSON_B,
                        PAYMENT_A,
                        EMB1,
                        LESSON_C,
                        AGREEMENT_B,
                        ROOT)) {
            Node selected =
                    nodeAt(result, scope);
            String initialBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
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
                DirectBlueIdCalculator.calculateBlueId(
                        expected);
        String actualBlueId =
                DirectBlueIdCalculator.calculateBlueId(
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
                    DirectBlueIdCalculator.calculateBlueId(a);
            this.bBlueId =
                    DirectBlueIdCalculator.calculateBlueId(b);
            this.cBlueId =
                    DirectBlueIdCalculator.calculateBlueId(c);
            this.repeatedBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            repeated);
            this.d1BlueId =
                    DirectBlueIdCalculator.calculateBlueId(d1);
            this.d2BlueId =
                    DirectBlueIdCalculator.calculateBlueId(d2);
        }

        private static Events create() {
            return new Events(
                    exactChat("A"),
                    exactChat("B"),
                    exactChat("C"),
                    exactChat("identical-occurrence"),
                    exactChat("D1"),
                    exactChat("D2"));
        }
    }

    private static Node exactChat(String message) {
        return RepositoryIndependentCoordinationTypes
                .chatMessage(message);
    }

    private static Node deepRoot(
            Events events,
            RootEmissionMode emissionMode) {
        Node emb3 = emb3(events);
        Node emb2 = emb2(events, emb3);
        Node lessonB = coldSibling("lesson-b");
        Node paymentA = coldSibling("payment-a");
        Node emb1 = emb1(
                events,
                emb2,
                lessonB,
                paymentA);
        Node lessonC = coldSibling("lesson-c");
        Node agreementB = unrelatedAgreementB(lessonC);
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "embedded",
                processEmbeddedCollections(
                        "/agreements"));
        contracts.put(
                TIMELINE,
                RepositoryIndependentCoordinationTypes
                        .timelineChannel(
                                "flagship-timeline",
                                "flagship-timeline"));
        contracts.put(
                "deepPulseUpdates",
                documentUpdateChannel(
                        EMB3 + "/state/pulseSeen"));
        contracts.put(
                "emb2AReceiptUpdates",
                documentUpdateChannel(
                        EMB2 + "/state/aReceived"));
        contracts.put(
                "emb1BReceiptUpdates",
                documentUpdateChannel(
                        EMB1 + "/state/bReceived"));
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
                        EMB3));
        contracts.put(
                "emb2Events",
                embeddedChannel(
                        EMB2));
        contracts.put(
                "emb1Events",
                embeddedChannel(
                        EMB1));
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
                .properties(
                        "agreements",
                        object(
                                "agreement-a",
                                emb1,
                                "agreement-b",
                                agreementB))
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static Node emb1(
            Events events,
            Node emb2,
            Node lessonB,
            Node paymentA) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "embedded",
                processEmbeddedCollections(
                        "/lessons",
                        "/payments"));
        contracts.put(
                TIMELINE,
                RepositoryIndependentCoordinationTypes
                        .timelineChannel(
                                "flagship-timeline",
                                "flagship-timeline"));
        contracts.put(
                "deepPulseUpdates",
                documentUpdateChannel(
                        "/lessons/lesson-a/cancellations/cancel-a/state/pulseSeen"));
        contracts.put(
                "emb2AReceiptUpdates",
                documentUpdateChannel(
                        "/lessons/lesson-a/state/aReceived"));
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
                        "/lessons/lesson-a/cancellations/cancel-a"));
        contracts.put(
                "emb2Events",
                embeddedChannel(
                        "/lessons/lesson-a"));
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
                .properties(
                        "lessons",
                        object(
                                "lesson-a",
                                emb2,
                                "lesson-b",
                                lessonB))
                .properties(
                        "payments",
                        object(
                                "payment-a",
                                paymentA))
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
                processEmbeddedCollections(
                        "/cancellations"));
        contracts.put(
                TIMELINE,
                RepositoryIndependentCoordinationTypes
                        .timelineChannel(
                                "flagship-timeline",
                                "flagship-timeline"));
        contracts.put(
                "leafPulseUpdates",
                documentUpdateChannel(
                        "/cancellations/cancel-a/state/pulseSeen"));
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
                        "/cancellations/cancel-a"));
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
                .properties(
                        "cancellations",
                        object(
                                "cancel-a",
                                emb3))
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
                RepositoryIndependentCoordinationTypes
                        .timelineChannel(
                                "flagship-timeline",
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
                        RepositoryIndependentCoordinationTypes
                                .chatMessage(
                                        "never-" + label),
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

    private static Node unrelatedAgreementB(
            Node lessonC) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "embedded",
                processEmbeddedCollections(
                        "/lessons"));
        contracts.put(
                "triggered",
                triggeredChannel());
        contracts.put(
                "zzDecoyCold",
                workflow(
                        "triggered",
                        RepositoryIndependentCoordinationTypes
                                .chatMessage(
                                        "never-agreement-b"),
                        largeDecoyStep(
                                "agreement-b")));
        return new Node()
                .properties(
                        "payload",
                        new Node().value(
                                repeated(
                                        "agreement-b",
                                        LARGE_DECOY_SIZE)))
                .properties(
                        "state",
                        object(
                                "untouched",
                                true))
                .properties(
                        "lessons",
                        object(
                                "lesson-c",
                                lessonC))
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
                .type(new Node().blueId(
                        RepositoryIndependentCoordinationTypes
                                .UPDATE_DOCUMENT_BLUE_ID))
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

    private static Node processEmbeddedCollections(
            String... collectionPaths) {
        List<Node> values =
                new ArrayList<>();
        for (String path : collectionPaths) {
            values.add(
                    new Node().value(path));
        }
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(values));
    }

    private static Node documentUpdateChannel(
            String path) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL))
                .properties(
                        "path",
                        new Node().value(path));
    }

    private static Node triggeredChannel() {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL));
    }

    private static Node embeddedChannel(
            String sourcePath) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                .properties(
                        "sourcePath",
                        new Node().value(
                                sourcePath));
    }

    private static Node operationWorkflow(
            Node... steps) {
        return new Node()
                .type(new Node().blueId(
                        RepositoryIndependentCoordinationTypes
                                .SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID))
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
                .type(new Node().blueId(
                        RepositoryIndependentCoordinationTypes
                                .SEQUENTIAL_WORKFLOW_BLUE_ID))
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
                .type(new Node().blueId(
                        RepositoryIndependentCoordinationTypes
                                .UPDATE_DOCUMENT_BLUE_ID))
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
                .type(new Node().blueId(
                        RepositoryIndependentCoordinationTypes
                                .TRIGGER_EVENT_BLUE_ID))
                .properties(
                        "event", event.clone());
    }

    private static Node captureProcessingEvent(
            String eventPath,
            String timestampPath) {
        return new Node()
                .type(new Node().blueId(
                        RepositoryIndependentCoordinationTypes
                                .COMPUTE_BLUE_ID))
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
                        canonicalWireValue(
                                NodeWireForm.get(
                                        node)));
    }

    private static Object canonicalWireValue(
            Object value) {
        if (value instanceof Map) {
            Map<String, Object> canonical =
                    new TreeMap<>();
            for (Map.Entry<?, ?> entry :
                    ((Map<?, ?>) value).entrySet()) {
                canonical.put(
                        Objects.toString(
                                entry.getKey()),
                        canonicalWireValue(
                                entry.getValue()));
            }
            return canonical;
        }
        if (value instanceof List) {
            List<Object> canonical =
                    new ArrayList<>();
            for (Object item : (List<?>) value) {
                canonical.add(
                        canonicalWireValue(item));
            }
            return canonical;
        }
        return value;
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
        private final Set<String> forbidden;
        private final List<String>
                selectedPrefetchOrder;
        private final Set<String> selectedClosure;
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
                List<String> selectedPrefetchOrder,
                Set<String> selectedClosure,
                ProviderMode providerMode) {
            this.backing =
                    new LinkedHashMap<>(backing);
            this.forbidden =
                    new LinkedHashSet<>(
                            forbidden);
            this.selectedPrefetchOrder =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    selectedPrefetchOrder));
            this.selectedClosure =
                    immutableSet(
                            selectedClosure);
            this.providerMode =
                    Objects.requireNonNull(
                            providerMode,
                            "providerMode");
            if (!new LinkedHashSet<>(
                    this.selectedPrefetchOrder)
                    .equals(this.selectedClosure)) {
                throw new IllegalArgumentException(
                        "Selected prefetch order must enumerate the exact closure");
            }
            if (!this.backing.keySet().containsAll(
                    this.selectedClosure)) {
                throw new IllegalArgumentException(
                        "Selected prefetch closure contains unavailable fragments");
            }
            if (!Collections.disjoint(
                    this.selectedClosure,
                    this.forbidden)) {
                throw new IllegalArgumentException(
                        "Selected prefetch closure contains forbidden fragments");
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
            if (!selectedClosure.contains(
                    blueId)) {
                throw new AssertionError(
                        "PROCESS demanded fragment outside the selected closure "
                                + blueId);
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
                            selectedPrefetchOrder) {
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
            if (!selectedClosure.contains(
                    blueId)) {
                throw new AssertionError(
                        "Prefetch escaped the selected closure "
                                + blueId);
            }
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

        private synchronized void warmSelectedClosure() {
            for (String blueId :
                    selectedPrefetchOrder) {
                load(blueId);
            }
        }

        private synchronized void resetRequestMetrics() {
            requests.clear();
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

    private static final class PlatformExecution {
        private final PlatformProcessingResult result;
        private final ProviderMetrics providerMetrics;
        private final BexProcessingMetrics metrics;

        private PlatformExecution(
                PlatformProcessingResult result,
                ProviderMetrics providerMetrics,
                BexProcessingMetrics metrics) {
            this.result = Objects.requireNonNull(
                    result, "result");
            this.providerMetrics = Objects.requireNonNull(
                    providerMetrics,
                    "providerMetrics");
            this.metrics = Objects.requireNonNull(
                    metrics, "metrics");
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
        private final PlatformProcessingResult
                platformResult;
        private final ProviderMetrics
                platformProviderMetrics;
        private final BexProcessingMetrics metrics;
        private final SelectedBodies
                selectedBodies;

        private Run(
                Scenario scenario,
                Variant variant,
                ProcessingDebugResult debug,
                PlatformProcessingResult platformResult,
                ProviderMetrics platformProviderMetrics,
                BexProcessingMetrics metrics) {
            this.scenario = scenario;
            this.variant = variant;
            this.debug = debug;
            this.platformResult = Objects.requireNonNull(
                    platformResult,
                    "platformResult");
            this.platformProviderMetrics =
                    platformProviderMetrics;
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
        private final List<SubscriptionDelta.Entry>
                subscriptionAdditions;
        private final List<SubscriptionDelta.Entry>
                subscriptionRemovals;
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
                List<SubscriptionDelta.Entry>
                        subscriptionAdditions,
                List<SubscriptionDelta.Entry>
                        subscriptionRemovals,
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
            this.subscriptionAdditions =
                    subscriptionAdditions;
            this.subscriptionRemovals =
                    subscriptionRemovals;
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
                    run.platformResult
                            .processResult();
            SubscriptionDelta subscriptionDelta =
                    run.platformResult
                            .commitCompanion()
                            .subscriptionDelta();
            List<String> checkpoints =
                    new ArrayList<>();
            for (ProcessingTraceRecord record :
                    debug.trace().records(
                            ProcessingTraceRecord.Kind
                                    .CHECKPOINT_WRITE)) {
                checkpoints.add(
                        DirectBlueIdCalculator
                                .calculateBlueId(
                                        record.node()));
            }
            return new SemanticProjection(
                    result.status(),
                    /*
                     * The public fragmented lane deliberately retains cold
                     * exact references in its result. Its Root BlueId plus
                     * the selected-state assertions establish equality with
                     * this fully inline canonical control value without
                     * opening those cold references merely for reporting.
                     */
                    normalizedJson(
                            debug.processResult()
                                    .document()),
                    DirectBlueIdCalculator.calculateBlueId(
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
                    subscriptionDelta.added(),
                    subscriptionDelta.removed(),
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
                    && subscriptionAdditions.equals(
                    that.subscriptionAdditions)
                    && subscriptionRemovals.equals(
                    that.subscriptionRemovals)
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
                    subscriptionAdditions,
                    subscriptionRemovals,
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
                            ? DirectBlueIdCalculator
                            .calculateBlueId(node)
                            : null));
        }
        return Collections.unmodifiableList(
                result);
    }
}
