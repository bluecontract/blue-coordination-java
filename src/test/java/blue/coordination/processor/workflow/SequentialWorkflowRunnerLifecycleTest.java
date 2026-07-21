package blue.coordination.processor.workflow;

import blue.bex.api.BexEngine;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;
import java.math.BigInteger;
import java.util.Arrays;
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
    void normalWorkflowCreatesAndClosesOneFrozenWorkingDocument() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = runner(metrics, frozenObservingExecutor());
        Fixture fixture = fixture(runner, triggerStep());

        DocumentProcessingResult result = fixture.process();

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        fixture.assertOneWorkflowScopeReleased();
        assertEquals(1L, metrics.workflowDocumentViewsFromFrozen());
        assertEquals(0L, metrics.workflowDocumentViewsFromDocument());
    }

    @Test
    void zeroStepWorkflowStillClosesItsWorkingDocument() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = runner(metrics);
        Fixture fixture = fixture(runner);

        DocumentProcessingResult result = fixture.process();

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        fixture.assertOneWorkflowScopeReleased();
        assertEquals(1L, metrics.workflowDocumentViewsFromFrozen());
        assertEquals(0L, metrics.workflowDocumentViewsFromDocument());
    }

    @Test
    void executorExceptionClosesWorkingDocumentAndStillRecordsRunnerTiming() {
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

        DocumentProcessingResult result = fixture.process();

        assertRuntimeFatal(result, "executor exploded");
        fixture.assertOneWorkflowScopeReleased();
        assertTrue(metrics.workflowRunnerNanos() > 0L,
                "the outer timing finally must run when an executor throws");
    }

    @Test
    void throwFatalClosesWorkingDocument() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        WorkflowStepExecutor<TriggerEvent> fatal = new WorkflowStepExecutor<TriggerEvent>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return step instanceof TriggerEvent;
            }

            @Override
            public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
                context.processorContext().throwFatal("requested fatal");
                return WorkflowStepResult.none();
            }
        };
        Fixture fixture = fixture(runner(metrics, fatal), triggerStep());

        DocumentProcessingResult result = fixture.process();

        assertRuntimeFatal(result, "requested fatal");
        fixture.assertOneWorkflowScopeReleased();
    }

    @Test
    void declarativeGracefulTerminationClosesAndSkipsLaterPatchStep() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        AtomicInteger patchExecutions = new AtomicInteger();
        WorkflowStepExecutor<UpdateDocument> forbiddenPatch = new WorkflowStepExecutor<UpdateDocument>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
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

        DocumentProcessingResult result = fixture.process();

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertEquals(0, patchExecutions.get(),
                "no patch-producing step may execute after terminal scope work");
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
        fixture.assertOneWorkflowScopeReleased();
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    @Test
    void computeResultValidationFailureClosesWorkingDocument() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner, invalidComputeResultStep());

        DocumentProcessingResult result = fixture.process();

        assertRuntimeFatal(result, "changeset must be a list");
        fixture.assertNoTransientSequenceLeak();
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void patchPreviewFailureClosesWorkingDocument() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner,
                updateStep("add", "/counter/child", new Node().value(1)));

        DocumentProcessingResult result = fixture.process();

        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), result.failureReason());
        fixture.assertNoTransientSequenceLeak();
        assertEquals(BigInteger.ZERO, result.document().get("/counter"));
    }

    @Test
    void patchApplicationFailureAfterPreviewReleasesEverySequenceScope() {
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
                        snapshotManager.failNextCacheSnapshot = true;
                        context.processorContext().applyPreviewedPatches(patches, preview);
                        return WorkflowStepResult.none();
                    }
                };
        Fixture fixture = fixture(runner(metrics, previewThenFail),
                snapshotManager,
                triggerStep());

        DocumentProcessingResult result = fixture.process();

        assertRuntimeFatal(result, "simulated final cache failure");
        fixture.assertNoTransientSequenceLeak();
        assertTrue(fixture.snapshotManager.openCalls() >= 2,
                "preview preparation and transferred application both own scopes");
    }

    @Test
    void transferredPreviewRemainsValidAfterWorkflowWorkingDocumentCloses() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                BexEngine.builder().build(), 100_000L, metrics);
        Fixture fixture = fixture(runner,
                updateStep("replace", "/counter", new Node().value(7)));

        DocumentProcessingResult result = fixture.process();

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertEquals(BigInteger.valueOf(7), result.document().get("/counter"),
                "the processor must consume the transferred preview after runner closure");
        fixture.assertNoTransientSequenceLeak();
        assertEquals(fixture.snapshotManager.openCalls(), fixture.snapshotManager.releaseCalls());
    }

    @Test
    void tenThousandShortWorkflowsDoNotAccumulateTransientSequenceState() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = runner(metrics, noOpExecutor());
        Fixture fixture = fixture(runner, triggerStep());

        for (int i = 0; i < 10_000; i++) {
            DocumentProcessingResult result = fixture.process();
            assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
            assertEquals(0, fixture.snapshotManager.activeScopes(),
                    "transient scope leak after repetition " + i);
        }

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
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .withSnapshotManager(snapshotManager);
        CoordinationProcessors.configure(builder,
                CoordinationProcessorOptions.builder()
                        .sequentialWorkflowRunner(runner)
                        .build());
        DocumentProcessor processor = builder
                .registerContractProcessor(new LifecycleChannelProcessor())
                .build();
        DocumentProcessingResult initialized = processor.initializeDocument(document(steps));
        assertEquals(ProcessorStatus.SUCCESS, initialized.status(), initialized.failureReason());
        snapshotManager.resetLifecycleCounters();
        return new Fixture(processor, initialized.document(), snapshotManager);
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

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result, String message) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), result.failureReason());
        assertTrue(result.failureReason() != null && result.failureReason().contains(message),
                result.failureReason());
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
                    .properties("id", new Node().value("run")));
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
        private boolean failNextCacheSnapshot;

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
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(patched.root().toNode()),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            if (failNextCacheSnapshot) {
                failNextCacheSnapshot = false;
                throw new IllegalStateException("simulated final cache failure");
            }
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
