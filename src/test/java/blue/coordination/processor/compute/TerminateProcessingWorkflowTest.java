package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.coordination.processor.workflow.StepExecutionContext;
import blue.coordination.processor.workflow.TerminateProcessingStepExecutor;
import blue.coordination.processor.workflow.WorkflowStepExecutor;
import blue.coordination.processor.workflow.WorkflowStepResult;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminateProcessingWorkflowTest {
    @Test
    void shouldDeriveCauseWhenReasonIsOmitted() {
        // Given
        String reason = null;

        // When
        DocumentProcessingResult result = runDeclarative(null, reason);

        // Then
        assertDeclarativeTermination(result, null);
    }

    @Test
    void shouldPreserveStaticReason() {
        // Given
        String reason = "Workflow completed";

        // When
        DocumentProcessingResult result = runDeclarative(null, reason);

        // Then
        assertDeclarativeTermination(result, reason);
    }

    @Test
    void shouldOmitEmptyReason() {
        // Given
        String reason = "";

        // When
        DocumentProcessingResult result = runDeclarative(null, reason);

        // Then
        assertDeclarativeTermination(result, null);
    }

    @Test
    void shouldPreserveWhitespaceReason() {
        // Given
        String reason = "   ";

        // When
        DocumentProcessingResult result = runDeclarative(null, reason);

        // Then
        assertDeclarativeTermination(result, reason);
    }

    @Test
    void shouldRejectAuthoredCause() {
        // Given
        String steps = String.join("\n",
                "- name: Invalid Authored Cause",
                "  type: Coordination/Terminate Processing",
                "  cause: workflow-completed",
                "  reason: must-not-terminate");

        // When
        DocumentProcessingResult result = runSteps(null, steps);

        // Then
        assertRuntimeFailure(
                result,
                "Terminate Processing does not accept an authored cause");
    }

    @Test
    void shouldPreserveDocumentChangesBeforeTermination() {
        // Given
        String steps = terminatingSequence();

        // When
        DocumentProcessingResult result = runSteps(null, steps);

        // Then
        assertEquals("changed-before-stop", result.document().get("/status"));
    }

    @Test
    void shouldPreserveEventsBeforeTermination() {
        // Given
        String steps = terminatingSequence();

        // When
        DocumentProcessingResult result = runSteps(null, steps);

        // Then
        assertTrue(kinds(result, "before-stop").contains("before-stop"));
    }

    @Test
    void shouldSkipEventsAfterTermination() {
        // Given
        String steps = terminatingSequence();

        // When
        DocumentProcessingResult result = runSteps(null, steps);

        // Then
        assertFalse(kinds(result, "must-not-emit").contains("must-not-emit"));
    }

    @Test
    void shouldKeepTerminationLifecycleInternalAfterPrecedingEvents() {
        // Given
        String steps = terminatingSequence();

        // When
        DocumentProcessingResult result = runSteps(null, steps);

        // Then
        assertTrue(indexOfKind(result, "before-stop") >= 0);
        assertEquals(
                -1,
                indexOfType(
                        result,
                        RuntimeBlueIds
                                .DOCUMENT_PROCESSING_TERMINATED),
                "processor lifecycle events remain internal");
    }

    @Test
    void shouldStopExecutingLaterWorkflowSteps() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        runSteps(metrics, terminatingSequence());

        // Then
        assertEquals(3L, metrics.workflowStepsExecuted());
    }

    @Test
    void shouldCountDeclarativeTerminationStep() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        runSteps(metrics, terminatingSequence());

        // Then
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    @Test
    void shouldNotCountDeclarativeTerminationAsComputeTermination() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        runSteps(metrics, terminatingSequence());

        // Then
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
    }

    @Test
    void shouldRejectBexShapedReasonAtExecutionBoundary() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        String steps = String.join("\n",
                "- name: Invalid Dynamic Reason",
                "  type: Coordination/Terminate Processing",
                "  reason:",
                "    $document: /status");

        // When
        DocumentProcessingResult result =
                runSteps(metrics, steps);

        // Then
        assertInvalidProcessingDocument(
                result,
                "Terminate Processing reason must be Text");
        assertEquals(0L, metrics.declarativeTerminationSteps());
        assertEquals(0L, metrics.bexCompiledExecutions());
    }

    @Test
    void shouldRejectNonTextReason() {
        // Given
        String steps = String.join("\n",
                "- name: Invalid Numeric Reason",
                "  type: Coordination/Terminate Processing",
                "  reason: 7");
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        DocumentProcessingResult result =
                runSteps(metrics, steps);

        // Then
        assertInvalidProcessingDocument(
                result,
                "Terminate Processing reason must be Text");
        assertEquals(0L, metrics.declarativeTerminationSteps());
        assertEquals(0L, metrics.bexCompiledExecutions());
    }

    @Test
    void shouldRegisterInDefaultWorkflowRunner() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();

        // When
        DocumentProcessingResult defaultRunner = runDeclarativeWithSupport(
                support, null);

        // Then
        assertDeclarativeTermination(defaultRunner, null);
    }

    @Test
    void shouldRegisterInConfiguredWorkflowRunner() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        DocumentProcessingResult configuredRunner = runDeclarativeWithSupport(
                support(metrics), null);

        // Then
        assertDeclarativeTermination(configuredRunner, null);
    }

    @Test
    void shouldNameUnsupportedStepWithoutTerminateExecutor() {
        // Given
        SequentialWorkflowRunner runner = new SequentialWorkflowRunner(
                new ArrayList<WorkflowStepExecutor<? extends SequentialWorkflowStep>>());
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder().sequentialWorkflowRunner(runner).build());

        // When
        DocumentProcessingResult result = runDeclarativeWithSupport(
                support, null);

        // Then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(
                "Unsupported sequential workflow step: Coordination/Terminate Processing"));
    }

    @Test
    void shouldAddNoBexCompilation() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        runDeclarative(metrics, "static reason");

        // Then
        assertEquals(0L, metrics.bexCompiledExecutions());
        assertEquals(0L, metrics.bexCompileCacheHits());
        assertEquals(0L, metrics.bexCompileCacheMisses());
    }

    @Test
    void shouldSupportTerminateProcessingSteps() {
        // Given
        TerminateProcessingStepExecutor executor = new TerminateProcessingStepExecutor();

        // When
        boolean supported = executor.supports(new TerminateProcessing());

        // Then
        assertTrue(supported);
    }

    @Test
    void shouldNotSupportComputeSteps() {
        // Given
        TerminateProcessingStepExecutor executor = new TerminateProcessingStepExecutor();

        // When
        boolean supported = executor.supports(new Compute());

        // Then
        assertFalse(supported);
    }

    @Test
    void shouldReturnTerminalStepResult() {
        // Given
        TerminationInspection inspection = terminationInspection();

        // When
        runDeclarativeWithSupport(inspection.support, null);

        // Then
        assertTrue(inspection.observed.get().isTerminal());
    }

    @Test
    void shouldExportNoStepValue() {
        // Given
        TerminationInspection inspection = terminationInspection();

        // When
        runDeclarativeWithSupport(inspection.support, null);

        // Then
        assertFalse(inspection.observed.get().hasValue());
    }

    @Test
    void shouldProduceEquivalentRootEffectsForComputeAndDeclarativeTermination() {
        // Given
        String cause = TerminateProcessing.blueId();
        String reason = "same-reason";

        // When
        DocumentProcessingResult compute = runSteps(null, String.join("\n",
                "- name: Before Compute",
                "  type: Coordination/Update Document",
                "  changeset:",
                "    - op: replace",
                "      path: /status",
                "      val: completed",
                "- name: Compute Stop",
                "  type: Coordination/Compute",
                "  do:",
                "    - $return:",
                "        termination:",
                "          cause: " + cause,
                "          reason: " + reason));
        DocumentProcessingResult declarative = runSteps(null, String.join("\n",
                "- name: Before Declarative",
                "  type: Coordination/Update Document",
                "  changeset:",
                "    - op: replace",
                "      path: /status",
                "      val: completed",
                terminateStep(reason)));

        // Then
        assertEquals(ProcessorStatus.SUCCESS, compute.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(compute));
        assertEquals(ProcessorStatus.SUCCESS, declarative.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(declarative));
        assertEquals(compute.document().get("/status"), declarative.document().get("/status"));
        assertEquals(terminationValue(compute, "cause"), terminationValue(declarative, "cause"));
        assertEquals(terminationValue(compute, "reason"), terminationValue(declarative, "reason"));
        assertEquals(lifecycleCauses(compute), lifecycleCauses(declarative));
    }

    @Test
    void shouldNotReplaceFirstCoreReasonOnDuplicateTermination() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = support.initialize(support.yaml(String.join("\n",
                "name: Duplicate Termination Test",
                "contracts:",
                CoordinationTestResources.simpleTimelineChannelYaml("ownerChannel", "owner", 2),
                "  first:",
                "    type: Coordination/Sequential Workflow",
                "    channel: ownerChannel",
                "    steps:",
                "      - type: Coordination/Terminate Processing",
                "        reason: first-reason",
                "  second:",
                "    type: Coordination/Sequential Workflow",
                "    channel: ownerChannel",
                "    steps:",
                "      - type: Coordination/Terminate Processing",
                "        reason: second-reason"))).document();
        Node event = TestTimelineProvider.timelineEntry(support.blue,
                support.repository,
                "owner",
                1,
                TestTimelineProvider.chatMessage("stop"));

        // When
        DocumentProcessingResult result = support.process(document, event);

        // Then
        assertDeclarativeTermination(result, "first-reason");
    }

    @Test
    void shouldRollBackSourceCheckpointWhenDeclarativeTerminationCutsOffInvocation() {
        // Given
        String reason = "checkpoint-rollback";
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                indent(terminateStep(reason), 6)));
        Node event = support.operationRequest(
                "owner",
                17,
                "run",
                "ownerChannel",
                new Node().value("request"));

        // When
        DocumentProcessingResult result = support.process(document, event);

        // Then
        assertDeclarativeTermination(result, reason);
        assertNull(
                nodeOrNull(
                        result.document(),
                        "/contracts/checkpoint/entries/ownerChannel/subject"),
                "declarative termination must not persist the source checkpoint");
    }

    private static String terminatingSequence() {
        return String.join("\n",
                "- name: Before Termination Patch",
                "  type: Coordination/Update Document",
                "  changeset:",
                "    - op: replace",
                "      path: /status",
                "      val: changed-before-stop",
                "- name: Before Termination Event",
                "  type: Coordination/Trigger Event",
                "  event:",
                "    type: Coordination/Event",
                "    kind: before-stop",
                terminateStep("stop-now"),
                "- name: Later Patch",
                "  type: Coordination/Update Document",
                "  changeset:",
                "    - op: replace",
                "      path: /status",
                "      val: must-not-run",
                "- name: Later Event",
                "  type: Coordination/Trigger Event",
                "  event:",
                "    type: Coordination/Event",
                "    kind: must-not-emit");
    }

    private static TerminationInspection terminationInspection() {
        final AtomicReference<WorkflowStepResult> observed =
                new AtomicReference<WorkflowStepResult>();
        final TerminateProcessingStepExecutor delegate =
                new TerminateProcessingStepExecutor();
        WorkflowStepExecutor<TerminateProcessing> inspector =
                new WorkflowStepExecutor<TerminateProcessing>() {
                    @Override
                    public boolean supports(SequentialWorkflowStep step) {
                        return delegate.supports(step);
                    }

                    @Override
                    public WorkflowStepResult execute(TerminateProcessing step,
                                                      StepExecutionContext context) {
                        WorkflowStepResult result = delegate.execute(step, context);
                        observed.set(result);
                        return result;
                    }
                };
        SequentialWorkflowRunner runner = new SequentialWorkflowRunner(
                Arrays.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>asList(inspector));
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .sequentialWorkflowRunner(runner)
                        .build());
        return new TerminationInspection(support, observed);
    }

    private static DocumentProcessingResult runDeclarative(BexProcessingMetrics metrics,
                                                           String reason) {
        return runSteps(metrics, terminateStep(reason));
    }

    private static DocumentProcessingResult runDeclarativeWithSupport(ComputeWorkflowTestSupport support,
                                                                      String reason) {
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                indent(terminateStep(reason), 6)));
        return support.processRun(document);
    }

    private static DocumentProcessingResult runSteps(BexProcessingMetrics metrics, String steps) {
        ComputeWorkflowTestSupport support = metrics == null
                ? ComputeWorkflowTestSupport.create()
                : support(metrics);
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                indent(steps, 6)));
        return support.processRun(document);
    }

    private static ComputeWorkflowTestSupport support(BexProcessingMetrics metrics) {
        return ComputeWorkflowTestSupport.create(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
    }

    private static String terminateStep(String reason) {
        String step = String.join("\n",
                "- name: Stop Processing",
                "  type: Coordination/Terminate Processing");
        if (reason == null) {
            return step;
        }
        return step + "\n  reason: '" + reason.replace("'", "''") + "'";
    }

    private static String indent(String value, int spaces) {
        char[] indentation = new char[spaces];
        Arrays.fill(indentation, ' ');
        String prefix = new String(indentation);
        return prefix + value.replace("\n", "\n" + prefix);
    }

    private static void assertDeclarativeTermination(DocumentProcessingResult result,
                                                     String reason) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(TerminateProcessing.blueId(), terminationValue(result, "cause"));
        assertEquals(reason, terminationValue(result, "reason"));
    }

    private static void assertRuntimeFailure(DocumentProcessingResult result,
                                             String reasonFragment) {
        String diagnostic =
                blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), diagnostic);
        assertTrue(diagnostic != null && diagnostic.contains(reasonFragment), diagnostic);
    }

    private static void assertInvalidProcessingDocument(
            DocumentProcessingResult result,
            String reasonFragment) {
        String diagnostic =
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result);
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status(),
                diagnostic);
        assertTrue(
                diagnostic != null
                        && diagnostic.contains(
                                reasonFragment),
                diagnostic);
    }

    private static List<String> kinds(DocumentProcessingResult result, String... selected) {
        List<String> allowed = Arrays.asList(selected);
        List<String> actual = new ArrayList<String>();
        for (Node event : result.events()) {
            Object kind = scalarProperty(event, "kind");
            if (kind instanceof String && allowed.contains(kind)) {
                actual.add((String) kind);
            }
        }
        return actual;
    }

    private static List<Object> lifecycleCauses(DocumentProcessingResult result) {
        List<Object> causes = new ArrayList<Object>();
        for (Node event : result.events()) {
            Object cause = scalarProperty(event, "cause");
            if (cause != null) {
                causes.add(cause);
            }
        }
        return causes;
    }

    private static int indexOfKind(DocumentProcessingResult result, String kind) {
        for (int i = 0; i < result.events().size(); i++) {
            if (kind.equals(scalarProperty(result.events().get(i), "kind"))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfType(DocumentProcessingResult result, String blueId) {
        for (int i = 0; i < result.events().size(); i++) {
            Node event = result.events().get(i);
            if (event.getType() != null && blueId.equals(event.getType().getBlueId())) {
                return i;
            }
        }
        return -1;
    }

    private static Object scalarProperty(Node node, String key) {
        Node value = node.getProperties() != null ? node.getProperties().get(key) : null;
        return value != null ? value.getValue() : null;
    }

    private static Node terminationMarker(DocumentProcessingResult result) {
        Node contracts = result.document().getContracts();
        return contracts != null && contracts.getProperties() != null
                ? contracts.getProperties().get("terminated")
                : null;
    }

    private static Object terminationValue(DocumentProcessingResult result, String key) {
        Node marker = terminationMarker(result);
        return marker != null ? scalarProperty(marker, key) : null;
    }

    private static Node nodeOrNull(
            Node node,
            String pointer) {
        try {
            return node.getAsNode(pointer);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class TerminationInspection {
        private final ComputeWorkflowTestSupport support;
        private final AtomicReference<WorkflowStepResult> observed;

        private TerminationInspection(ComputeWorkflowTestSupport support,
                                      AtomicReference<WorkflowStepResult> observed) {
            this.support = support;
            this.observed = observed;
        }
    }
}
