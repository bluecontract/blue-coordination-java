package blue.coordination.processor.workflow;

import blue.bex.api.BexEngine;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;
import blue.repo.BlueRepository;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lifecycle regressions for the workflow-owned Language working document. */
class SequentialWorkflowRunnerLifecycleTest {

    private static final String CHANNEL_BLUE_ID =
            "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";

    @Test
    void shouldCreateAndCloseOneFrozenWorkingDocumentForNormalWorkflow() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = runner(metrics, frozenObservingExecutor());
        Fixture fixture = fixture(runner, triggerStep());

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        fixture.assertOneWorkflowScopeReleased();
        assertEquals(1L, metrics.workflowDocumentViewsFromFrozen());
        assertEquals(0L, metrics.workflowDocumentViewsFromDocument());
    }

    @Test
    void shouldCloseWorkingDocumentForZeroStepWorkflow() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = runner(metrics);
        Fixture fixture = fixture(runner);

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        fixture.assertOneWorkflowScopeReleased();
        assertEquals(1L, metrics.workflowDocumentViewsFromFrozen());
        assertEquals(0L, metrics.workflowDocumentViewsFromDocument());
    }

    @Test
    void shouldCloseWorkingDocumentAndRecordTimingWhenExecutorThrows() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        WorkflowStepExecutor<TriggerEvent> throwing = new WorkflowStepExecutor<TriggerEvent>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return step instanceof TriggerEvent;
            }

            @Override
            public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
                throw new IllegalStateException("executor exploded");
            }
        };
        Fixture fixture = fixture(runner(metrics, throwing), triggerStep());

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertRuntimeFatal(result, "executor exploded");
        fixture.assertOneWorkflowScopeReleased();
        assertTrue(metrics.workflowRunnerNanos() > 0L,
                "the outer timing finally must run when an executor throws");
    }

    @Test
    void shouldCloseWorkingDocumentWhenExecutorRequestsFatalFailure() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        WorkflowStepExecutor<TriggerEvent> fatal = new WorkflowStepExecutor<TriggerEvent>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return step instanceof TriggerEvent;
            }

            @Override
            public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
                context.throwFatal("requested fatal");
                return WorkflowStepResult.none();
            }
        };
        Fixture fixture = fixture(runner(metrics, fatal), triggerStep());

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertRuntimeFatal(result, "requested fatal");
        fixture.assertOneWorkflowScopeReleased();
    }

    @Test
    void shouldCloseAndSkipLaterPatchAfterDeclarativeTermination() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        AtomicInteger patchSelections = new AtomicInteger();
        AtomicInteger patchExecutions = new AtomicInteger();
        WorkflowStepExecutor<UpdateDocument> forbiddenPatch = new WorkflowStepExecutor<UpdateDocument>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                patchSelections.incrementAndGet();
                return step instanceof UpdateDocument;
            }

            @Override
            public WorkflowStepResult execute(UpdateDocument step, StepExecutionContext context) {
                patchExecutions.incrementAndGet();
                context.processorContext().applyPatch(
                        JsonPatch.replace("/counter", new Node().value(99)));
                return WorkflowStepResult.none();
            }
        };
        SequentialWorkflowRunner runner = runner(metrics,
                new TerminateProcessingStepExecutor(metrics), forbiddenPatch);
        Fixture fixture = fixture(runner,
                terminateStep("finished"),
                updateStep("replace", "/counter", new Node().value(99)));

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(0, patchExecutions.get(),
                "no patch-producing step may execute after terminal scope work");
        assertEquals(0, patchSelections.get(),
                "a step after termination must not select an executor");
        assertEquals(0L, metrics.updateStaticTemplatesBuilt(),
                "a step after termination must not compile a static plan");
        assertEquals(1L, metrics.workflowExecutorLookups(),
                "only the reached termination step may be planned");
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        assertEquals(TerminateProcessing.blueId(),
                result.document().get("/contracts/terminated/cause"));
        assertEquals("finished",
                result.document().get("/contracts/terminated/reason"));
        fixture.assertOneWorkflowScopeReleased();
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    @Test
    void shouldNotPopulateStepPlanCacheWhenGasRejectsBeforePlanning() {
        // Given
        WorkflowStepExecutor<UpdateDocument> referenceExecutor =
                noOpUpdateExecutor(new AtomicInteger());
        ProcessingDebugResult reference =
                fixture(
                        runner(
                                new BexProcessingMetrics(),
                                referenceExecutor),
                        updateStep(
                                "replace",
                                "/counter",
                                new Node().value(1)))
                        .processWithTrace();
        long admittedBeforeExecution =
                admittedBefore(
                        reference,
                        "workflowStepExecuted");
        BexProcessingMetrics metrics =
                new BexProcessingMetrics();
        AtomicInteger supportsCalls = new AtomicInteger();
        SequentialWorkflowRunner limitedRunner =
                runner(
                        metrics,
                        noOpUpdateExecutor(supportsCalls));
        Fixture limited =
                fixtureWithGasLimit(
                        limitedRunner,
                        admittedBeforeExecution,
                        updateStep(
                                "replace",
                                "/counter",
                                new Node().value(1)));

        // When
        ProcessingDebugResult rejected =
                limited.processWithTrace();

        // Then
        assertEquals(
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                rejected.processResult().status(),
                ProcessingResultTestSupport
                        .diagnosticMessage(
                                rejected.processResult()));
        assertEquals(0, supportsCalls.get());
        assertEquals(0L, metrics.workflowExecutorLookups());
        assertEquals(0L, metrics.updateStaticTemplatesBuilt());
        assertEquals(0L, metrics.workflowPlansBuilt());
        assertEquals(
                0,
                limitedRunner.workflowPlanCacheSize());
        assertTrue(
                hasCoordinationCounter(
                        rejected,
                        "workflowStepVisited"));
        assertFalse(
                hasCoordinationCounter(
                        rejected,
                        "workflowStepExecuted"));
    }

    @Test
    void shouldProduceIdenticalGasTraceForColdAndWarmedStepPlans() {
        // Given
        BexProcessingMetrics metrics =
                new BexProcessingMetrics();
        AtomicInteger supportsCalls = new AtomicInteger();
        SequentialWorkflowRunner runner =
                runner(
                        metrics,
                        noOpUpdateExecutor(supportsCalls));
        Node update = updateStep(
                "replace",
                "/counter",
                new Node().value(1));
        Fixture coldFixture =
                fixture(runner, update);
        Fixture warmFixture =
                fixture(runner, update);

        // When
        ProcessingDebugResult cold =
                coldFixture.processWithTrace();
        ProcessingDebugResult warmed =
                warmFixture.processWithTrace();

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                cold.processResult().status(),
                ProcessingResultTestSupport
                        .diagnosticMessage(
                                cold.processResult()));
        assertEquals(
                ProcessorStatus.SUCCESS,
                warmed.processResult().status(),
                ProcessingResultTestSupport
                        .diagnosticMessage(
                                warmed.processResult()));
        assertEquals(
                gasProjection(cold),
                gasProjection(warmed));
        assertEquals(1, supportsCalls.get());
        assertEquals(
                1L,
                metrics.updateStaticTemplatesBuilt());
        assertEquals(
                1,
                runner.workflowPlanCacheSize());
    }

    @Test
    void shouldValidateComputeResultAndCloseWorkingDocumentWhenCapabilityIsAvailable() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner, invalidComputeResultStep());

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertRuntimeFatal(result,
                "Invalid Compute result: Compute result changeset must be a list");
        fixture.assertNoTransientSequenceLeak();
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldMergeOneDistinctHostedLedgerPerComputeStep() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner,
                returningComputeStep(1),
                returningComputeStep(2));

        // When
        ProcessingDebugResult debug =
                fixture.processWithTrace();
        DocumentProcessingResult result =
                debug.processResult();

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(2L, metrics.computeStepsExecuted());
        assertTrue(result.totalGas() > 0L,
                "every workflow-owned BEX child ledger must reach Contracts");
        assertEquals(
                Arrays.asList(
                        "bex.workflow.00000000.compute.00000000",
                        "bex.workflow.00000000.compute.00000001"),
                distinctBexNamespaces(debug));
        fixture.assertNoTransientSequenceLeak();
    }

    @Test
    void shouldMergeAdmittedLedgerPrefixOnceWhenSecondComputeFails() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner,
                returningComputeStep(1),
                failingComputeStep("synthetic-boom"));

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertRuntimeFatal(result, "Compute failed: synthetic-boom");
        assertEquals(2L, metrics.computeStepsExecuted());
        assertTrue(result.totalGas() > 0L,
                "deterministically admitted BEX gas must survive invocation rollback");
        fixture.assertNoTransientSequenceLeak();
    }

    @Test
    void shouldRetainEarlierComputeLedgerWhenLaterStepFails() {
        // Given
        DocumentProcessingResult updateOnly = fixture(
                SequentialWorkflowRunner.withBexEngine(
                        BexEngine.builder().build(), 100_000L),
                updateStep("unsupported", "/counter", new Node().value(7)))
                .process();
        Fixture fixture = fixture(
                SequentialWorkflowRunner.withBexEngine(
                        BexEngine.builder().build(), 100_000L),
                returningComputeStep(1),
                updateStep("unsupported", "/counter", new Node().value(7)));

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertRuntimeFatal(result,
                "Unsupported Update Document patch operation");
        assertTrue(result.totalGas() > updateOnly.totalGas(),
                "a later authored-step failure must retain the earlier BEX "
                        + "child-ledger prefix");
        fixture.assertNoTransientSequenceLeak();
    }

    @Test
    void shouldCloseWorkingDocumentWhenPatchPreviewFails() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner,
                updateStep("add", "/counter/child", new Node().value(1)));

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        fixture.assertNoTransientSequenceLeak();
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
    }

    @Test
    void shouldReleaseEverySequenceScopeWhenProcessorFailsAfterPreview() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        final TrackingSnapshotManager snapshotManager = new TrackingSnapshotManager();
        WorkflowStepExecutor<TriggerEvent> previewThenFail =
                new WorkflowStepExecutor<TriggerEvent>() {
                    @Override
                    public boolean supports(SequentialWorkflowStep step) {
                        return step instanceof TriggerEvent;
                    }

                    @Override
                    public WorkflowStepResult execute(TriggerEvent step,
                                                      StepExecutionContext context) {
                        List<JsonPatch> patches = Collections.singletonList(
                                JsonPatch.replace("/counter", new Node().value(7)));
                        WorkingDocument.Preview preview = context.advanceWorkingDocument(patches);
                        context.processorContext().applyPreviewedPatches(patches, preview);
                        throw new IllegalStateException(
                                "simulated post-preview failure");
                    }
                };
        Fixture fixture = fixture(runner(metrics, previewThenFail),
                snapshotManager,
                triggerStep());

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertRuntimeFatal(result, "simulated post-preview failure");
        fixture.assertNoTransientSequenceLeak();
        assertTrue(fixture.snapshotManager.openCalls() >= 2,
                "preview preparation and transferred application both own scopes");
    }

    @Test
    void shouldKeepTransferredPreviewValidAfterWorkflowDocumentCloses() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner,
                updateStep("replace", "/counter", new Node().value(7)));

        // When
        DocumentProcessingResult result = fixture.process();

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(BigInteger.valueOf(7), result.document().get("/counter"),
                "the processor must consume the transferred preview after runner closure");
        fixture.assertNoTransientSequenceLeak();
        assertEquals(fixture.snapshotManager.openCalls(), fixture.snapshotManager.releaseCalls());
    }

    @Test
    void shouldNotAccumulateTransientSequenceStateAcrossTenThousandWorkflows() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = runner(metrics, noOpExecutor());
        Fixture fixture = fixture(runner, triggerStep());

        // When
        for (int i = 0; i < 10_000; i++) {
            DocumentProcessingResult result = fixture.process();
            assertEquals(ProcessorStatus.SUCCESS, result.status(),
                    ProcessingResultTestSupport.diagnosticMessage(result));
            assertEquals(0, fixture.snapshotManager.activeScopes(),
                    "transient scope leak after repetition " + i);
        }

        // Then
        assertEquals(10_000, fixture.snapshotManager.openCalls());
        assertEquals(10_000, fixture.snapshotManager.releaseCalls());
        assertEquals(10_000L, metrics.workflowDocumentViewsFromFrozen());
        assertEquals(0L, metrics.workflowDocumentViewsFromDocument());
    }

    private static WorkflowStepExecutor<TriggerEvent> frozenObservingExecutor() {
        return new WorkflowStepExecutor<TriggerEvent>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return step instanceof TriggerEvent;
            }

            @Override
            public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
                assertFalse(context.workingDocument().usedMaterializedFallback());
                return WorkflowStepResult.none();
            }
        };
    }

    private static WorkflowStepExecutor<TriggerEvent> noOpExecutor() {
        return new WorkflowStepExecutor<TriggerEvent>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return step instanceof TriggerEvent;
            }

            @Override
            public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
                return WorkflowStepResult.none();
            }
        };
    }

    private static WorkflowStepExecutor<UpdateDocument> noOpUpdateExecutor(
            AtomicInteger supportsCalls) {
        return new WorkflowStepExecutor<UpdateDocument>() {
            @Override
            public boolean supports(
                    SequentialWorkflowStep step) {
                supportsCalls.incrementAndGet();
                return step instanceof UpdateDocument;
            }

            @Override
            public WorkflowStepResult execute(
                    UpdateDocument step,
                    StepExecutionContext context) {
                return WorkflowStepResult.none();
            }
        };
    }

    @SafeVarargs
    private static SequentialWorkflowRunner runner(
            BexProcessingMetrics metrics,
            WorkflowStepExecutor<? extends SequentialWorkflowStep>... executors) {
        return new SequentialWorkflowRunner(Arrays.asList(executors), metrics, 32, 1_000_000L);
    }

    private static Fixture fixture(SequentialWorkflowRunner runner, Node... steps) {
        return fixture(runner, new TrackingSnapshotManager(), steps);
    }

    private static Fixture fixture(SequentialWorkflowRunner runner,
                                   TrackingSnapshotManager snapshotManager,
                                   Node... steps) {
        DocumentProcessor processor = processor(
                runner,
                snapshotManager,
                null);
        DocumentProcessingResult initialized =
                initialize(
                        processor,
                        document(steps));
        snapshotManager.resetLifecycleCounters();
        return new Fixture(
                processor,
                initialized.document(),
                snapshotManager);
    }

    private static Fixture fixtureWithGasLimit(
            SequentialWorkflowRunner runner,
            long gasLimit,
            Node... steps) {
        TrackingSnapshotManager snapshotManager =
                new TrackingSnapshotManager();
        Node authored = document(steps);
        DocumentProcessor initializer = processor(
                runner,
                snapshotManager,
                null);
        DocumentProcessingResult initialized =
                initialize(initializer, authored);
        snapshotManager.resetLifecycleCounters();
        DocumentProcessor limited = processor(
                runner,
                snapshotManager,
                Long.valueOf(gasLimit));
        return new Fixture(
                limited,
                initialized.document(),
                snapshotManager);
    }

    private static DocumentProcessor processor(
            SequentialWorkflowRunner runner,
            TrackingSnapshotManager snapshotManager,
            Long gasLimit) {
        Blue blue = BlueRepository.latest().configure(new Blue());
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .withSnapshotManager(snapshotManager)
                .withMatchingService(new ContractMatchingService(blue))
                .withExternalDeliveryPlanDeriver(
                        SequentialWorkflowRunnerLifecycleTest::deliveryPlan);
        CoordinationProcessors.configure(builder,
                CoordinationProcessorOptions.builder()
                        .sequentialWorkflowRunner(runner)
                        .build());
        if (gasLimit != null) {
            builder.withGasLimit(gasLimit.longValue());
        }
        return builder
                .registerContractProcessor(new LifecycleChannelProcessor())
                .build();
    }

    private static DocumentProcessingResult initialize(
            DocumentProcessor processor,
            Node document) {
        DocumentProcessingResult initialized =
                processor.initializeDocument(document);
        assertEquals(ProcessorStatus.SUCCESS, initialized.status(),
                ProcessingResultTestSupport.diagnosticMessage(initialized));
        return initialized;
    }

    private static Node document(Node... steps) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("channel", typed(CHANNEL_BLUE_ID));
        contracts.put("workflow", typed(SequentialWorkflow.blueId())
                .properties("channel", new Node().value("channel"))
                .properties("steps", new Node().items(Arrays.asList(steps))));
        return new Node()
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static ExternalDeliveryPlan deliveryPlan(Node root, Node event) {
        Node channel = root.getContracts().getProperties().get("channel");
        String contributionBlueId =
                BlueIdCalculator.calculateBlueId(channel);
        String checkpointDomainBlueId = CheckpointDomain.derive(
                CHANNEL_BLUE_ID,
                Collections.singletonList(contributionBlueId),
                "lifecycle-test");
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder("/", "channel")
                        .sourceContribution(contributionBlueId)
                        .effectiveTypeBlueId(CHANNEL_BLUE_ID)
                        .subscriptionKey("channel")
                        .checkpointDomainBlueId(checkpointDomainBlueId)
                        .checkpointSubjectBlueId(
                                BlueIdCalculator.calculateBlueId(event))
                        .build();
        SubscriptionDelta.Entry activeInterval =
                new SubscriptionDelta.Entry(
                        "/",
                        "channel",
                        CHANNEL_BLUE_ID,
                        Collections.singletonList(contributionBlueId),
                        0,
                        Collections.singletonList("channel"),
                        checkpointDomainBlueId,
                        0L,
                        null,
                        null);
        return ExternalDeliveryPlan.builder()
                .revisions(0L, 0L)
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.singletonList(
                                BlueIdCalculator.calculateBlueId(event))))
                .delivery(delivery)
                .activeSubscriptionInterval(activeInterval)
                .exactRuntimeState()
                .build();
    }

    private static Node triggerStep() {
        return typed(TriggerEvent.blueId());
    }

    private static Node terminateStep(String reason) {
        return typed(TerminateProcessing.blueId())
                .properties("reason", new Node().value(reason));
    }

    private static Node updateStep(String op, String path, Node value) {
        return typed(UpdateDocument.blueId())
                .properties("changeset", new Node().items(new Node()
                        .properties("op", new Node().value(op))
                        .properties("path", new Node().value(path))
                        .properties("val", value)));
    }

    private static Node invalidComputeResultStep() {
        return typed(Compute.blueId())
                .properties("do", new Node().items(new Node()
                        .properties("$return", new Node()
                                .properties("changeset", new Node().value("not-a-list")))));
    }

    private static Node returningComputeStep(int value) {
        return typed(Compute.blueId())
                .properties("do", new Node().items(new Node()
                        .properties("$return", new Node().value(value))));
    }

    private static Node failingComputeStep(String reason) {
        return typed(Compute.blueId())
                .properties("do", new Node().items(new Node()
                        .properties("$fail", new Node().value(reason))));
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result, String message) {
        String diagnostic = ProcessingResultTestSupport.diagnosticMessage(result);
        String evidence = result.diagnostic() != null
                ? diagnostic + " details=" + result.diagnostic().details()
                : diagnostic;
        assertEquals(
                ProcessorStatus.RUNTIME_FATAL,
                result.status(),
                evidence);
        assertTrue(
                diagnostic.contains(message),
                evidence);
    }

    private static long admittedBefore(
            ProcessingDebugResult result,
            String counter) {
        long admitted = 0L;
        for (GasTraceEntry entry
                : result.trace().gas()) {
            if (entry.namespace().startsWith(
                    "coordination.")
                    && counter.equals(
                    entry.counter())) {
                return admitted;
            }
            admitted = Math.addExact(
                    admitted,
                    entry.subtotal());
        }
        throw new AssertionError(
                "Missing Coordination gas counter "
                        + counter);
    }

    private static boolean hasCoordinationCounter(
            ProcessingDebugResult result,
            String counter) {
        for (GasTraceEntry entry
                : result.trace().gas()) {
            if (entry.namespace().startsWith(
                    "coordination.")
                    && counter.equals(
                    entry.counter())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gasProjection(
            ProcessingDebugResult result) {
        List<String> projection =
                new ArrayList<String>();
        for (GasTraceEntry entry
                : result.trace().gas()) {
            projection.add(
                    entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.reason());
        }
        return projection;
    }

    private static List<String> distinctBexNamespaces(
            ProcessingDebugResult result) {
        List<String> namespaces =
                new ArrayList<String>();
        for (GasTraceEntry entry
                : result.trace().gas()) {
            if (entry.namespace().startsWith(
                    "bex.workflow.")
                    && !namespaces.contains(
                    entry.namespace())) {
                namespaces.add(
                        entry.namespace());
            }
        }
        return namespaces;
    }

    private static final class Fixture {
        private final DocumentProcessor processor;
        private final Node initializedDocument;
        private final TrackingSnapshotManager snapshotManager;

        private Fixture(DocumentProcessor processor,
                        Node initializedDocument,
                        TrackingSnapshotManager snapshotManager) {
            this.processor = processor;
            this.initializedDocument = initializedDocument;
            this.snapshotManager = snapshotManager;
        }

        private DocumentProcessingResult process() {
            return processor.processDocument(initializedDocument, new Node()
                    .properties("id", new Node().value("run"))
                    .properties("subscriptionKey",
                            new Node().value("channel")));
        }

        private ProcessingDebugResult processWithTrace() {
            return processor.processDocumentWithTrace(
                    initializedDocument,
                    new Node()
                            .properties(
                                    "id",
                                    new Node().value("run"))
                            .properties(
                                    "subscriptionKey",
                                    new Node().value(
                                            "channel")));
        }

        private void assertOneWorkflowScopeReleased() {
            assertEquals(1, snapshotManager.openCalls());
            assertEquals(1, snapshotManager.releaseCalls());
            assertNoTransientSequenceLeak();
        }

        private void assertNoTransientSequenceLeak() {
            assertEquals(0, snapshotManager.activeScopes());
            assertEquals(snapshotManager.openCalls(), snapshotManager.releaseCalls());
        }
    }

    @TypeBlueId(CHANNEL_BLUE_ID)
    public static final class LifecycleChannel extends ChannelContract {
    }

    private static final class LifecycleChannelProcessor
            implements ChannelProcessor<LifecycleChannel> {
        @Override
        public Class<LifecycleChannel> contractType() {
            return LifecycleChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<LifecycleChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<LifecycleChannel>() {
                @Override
                public List<String> channelKeys(
                        LifecycleChannel immutableContractSnapshot) {
                    return Collections.singletonList("channel");
                }

                @Override
                public String checkpointDomainDiscriminator(
                        LifecycleChannel immutableContractSnapshot) {
                    return "lifecycle-test";
                }
            };
        }

        @Override
        public boolean matches(LifecycleChannel contract, ChannelEvaluationContext context) {
            return context.event() != null;
        }

        @Override
        public String eventId(LifecycleChannel contract, ChannelEvaluationContext context) {
            Object id = context.event().get("/id");
            return id != null ? String.valueOf(id) : "run";
        }
    }

    /** Real Language snapshot transaction seam with deterministic scope accounting. */
    private static final class TrackingSnapshotManager implements ProcessingSnapshotManager {
        private int openCalls;
        private int releaseCalls;
        private int activeScopes;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(document.clone());
            return new ResolvedSnapshot(canonical,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return fromDocument(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(patched.root().toNode()),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return snapshot;
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            openCalls++;
            activeScopes++;
            return new TrackingScope(this);
        }

        private int openCalls() {
            return openCalls;
        }

        private int releaseCalls() {
            return releaseCalls;
        }

        private int activeScopes() {
            return activeScopes;
        }

        private void resetLifecycleCounters() {
            assertEquals(openCalls, releaseCalls, "initialization must release transient scopes");
            assertEquals(0, activeScopes, "initialization must leave no transient scope");
            openCalls = 0;
            releaseCalls = 0;
        }
    }

    private static final class TrackingScope implements ProcessingSnapshotManager {
        private final TrackingSnapshotManager owner;
        private boolean released;

        private TrackingScope(TrackingSnapshotManager owner) {
            this.owner = owner;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return owner.fromDocumentPreservingPaths(document, preservedPaths);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            return owner.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return owner.cacheSnapshot(snapshot);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return this;
        }

        @Override
        public ProcessingSnapshotManager forkTransientSequence() {
            return owner.transientSequence();
        }

        @Override
        public void releaseTransientState() {
            if (!released) {
                released = true;
                owner.releaseCalls++;
                owner.activeScopes--;
            }
        }
    }
}
