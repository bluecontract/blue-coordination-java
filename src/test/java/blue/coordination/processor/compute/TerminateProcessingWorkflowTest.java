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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminateProcessingWorkflowTest {
    @Test
    void terminateProcessingWithoutReasonTerminatesGracefully() {
        DocumentProcessingResult result = runDeclarative(null, null);

        assertGracefulTermination(result, null);
    }

    @Test
    void terminateProcessingPassesReasonUnchanged() {
        DocumentProcessingResult result = runDeclarative(null, "Workflow completed");

        assertGracefulTermination(result, "Workflow completed");
    }

    @Test
    void terminateProcessingEmptyReasonUsesCoreOmissionSemantics() {
        DocumentProcessingResult result = runDeclarative(null, "");

        assertGracefulTermination(result, null);
    }

    @Test
    void terminateProcessingPreservesWhitespaceReason() {
        DocumentProcessingResult result = runDeclarative(null, "   ");

        assertGracefulTermination(result, "   ");
    }

    @Test
    void terminateProcessingStopsEveryLaterStepAndPreservesPrecedingEffects() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runSteps(metrics, String.join("\n",
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
                "    kind: must-not-emit"));

        assertGracefulTermination(result, "stop-now");
        assertEquals("changed-before-stop", result.document().get("/status"));
        assertEquals(Arrays.asList("before-stop"), kinds(result, "before-stop", "must-not-emit"));
        assertTrue(indexOfKind(result, "before-stop")
                < indexOfType(result, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
        assertEquals(3L, metrics.workflowStepsExecuted());
        assertEquals(1L, metrics.declarativeTerminationSteps());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
    }

    @Test
    void terminateProcessingBexShapedReasonFailsTypeResolutionBeforeExecution() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> runSteps(metrics, String.join("\n",
                        "- name: Invalid Dynamic Reason",
                        "  type: Coordination/Terminate Processing",
                        "  reason:",
                        "    $document: /status")));

        assertTrue(failure.getMessage().contains("must not have items or properties"));
        assertEquals(0L, metrics.declarativeTerminationSteps());
        assertEquals(0L, metrics.bexCompiledExecutions());
    }

    @Test
    void terminateProcessingIsRegisteredInDefaultAndConfiguredBexRunners() {
        DocumentProcessingResult defaultRunner = runDeclarativeWithSupport(
                ComputeWorkflowTestSupport.create(), "default");
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult configuredRunner = runDeclarativeWithSupport(
                support(metrics), "configured");

        assertGracefulTermination(defaultRunner, "default");
        assertGracefulTermination(configuredRunner, "configured");
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    @Test
    void runnerWithoutTerminateExecutorNamesUnsupportedStepPrecisely() {
        SequentialWorkflowRunner runner = new SequentialWorkflowRunner(
                new ArrayList<WorkflowStepExecutor<? extends SequentialWorkflowStep>>());
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder().sequentialWorkflowRunner(runner).build());

        DocumentProcessingResult result = runDeclarativeWithSupport(support, "unsupported");

        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTrue(result.failureReason().contains(
                "Unsupported sequential workflow step: Coordination/Terminate Processing"));
    }

    @Test
    void terminateProcessingAddsNoBexCompilationOrEvaluation() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runDeclarative(metrics, "static reason");

        assertGracefulTermination(result, "static reason");
        assertEquals(0L, metrics.bexCompiledExecutions());
        assertEquals(0L, metrics.bexCompileCacheHits());
        assertEquals(0L, metrics.bexCompileCacheMisses());
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    @Test
    void terminateProcessingExportsNoStepResult() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        AtomicReference<WorkflowStepResult> observed = new AtomicReference<WorkflowStepResult>();
        final TerminateProcessingStepExecutor delegate = new TerminateProcessingStepExecutor(metrics);
        WorkflowStepExecutor<TerminateProcessing> inspector = new WorkflowStepExecutor<TerminateProcessing>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return delegate.supports(step);
            }

            @Override
            public WorkflowStepResult execute(TerminateProcessing step, StepExecutionContext context) {
                WorkflowStepResult result = delegate.execute(step, context);
                observed.set(result);
                return result;
            }
        };
        assertTrue(inspector.supports(new TerminateProcessing()));
        assertFalse(inspector.supports(new Compute()));
        SequentialWorkflowRunner runner = new SequentialWorkflowRunner(
                Arrays.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>asList(inspector));
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder().sequentialWorkflowRunner(runner).build());

        DocumentProcessingResult result = runDeclarativeWithSupport(support, "no-result");

        assertGracefulTermination(result, "no-result");
        assertTrue(observed.get().isTerminal());
        assertFalse(observed.get().hasValue());
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    @Test
    void computeAndDeclarativeTerminationProduceEquivalentRootEffects() {
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
                "          reason: same-reason"));
        DocumentProcessingResult declarative = runSteps(null, String.join("\n",
                "- name: Before Declarative",
                "  type: Coordination/Update Document",
                "  changeset:",
                "    - op: replace",
                "      path: /status",
                "      val: completed",
                terminateStep("same-reason")));

        assertEquals(ProcessorStatus.SUCCESS, compute.status(), compute.failureReason());
        assertEquals(ProcessorStatus.SUCCESS, declarative.status(), declarative.failureReason());
        assertEquals(compute.document().get("/status"), declarative.document().get("/status"));
        assertEquals(terminationValue(compute, "cause"), terminationValue(declarative, "cause"));
        assertEquals(terminationValue(compute, "reason"), terminationValue(declarative, "reason"));
        assertEquals(lifecycleCauses(compute), lifecycleCauses(declarative));
    }

    @Test
    void duplicateTerminationCannotReplaceFirstCoreReason() {
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

        DocumentProcessingResult result = support.process(document, event);

        assertGracefulTermination(result, "first-reason");
        assertEquals(1L, metrics.declarativeTerminationSteps());
    }

    private static DocumentProcessingResult runDeclarative(BexProcessingMetrics metrics, String reason) {
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

    private static void assertGracefulTermination(DocumentProcessingResult result, String reason) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertEquals("graceful", terminationValue(result, "cause"));
        assertEquals(reason, terminationValue(result, "reason"));
    }

    private static List<String> kinds(DocumentProcessingResult result, String... selected) {
        List<String> allowed = Arrays.asList(selected);
        List<String> actual = new ArrayList<String>();
        for (Node event : result.triggeredEvents()) {
            Object kind = scalarProperty(event, "kind");
            if (kind instanceof String && allowed.contains(kind)) {
                actual.add((String) kind);
            }
        }
        return actual;
    }

    private static List<Object> lifecycleCauses(DocumentProcessingResult result) {
        List<Object> causes = new ArrayList<Object>();
        for (Node event : result.triggeredEvents()) {
            Object cause = scalarProperty(event, "cause");
            if (cause != null) {
                causes.add(cause);
            }
        }
        return causes;
    }

    private static int indexOfKind(DocumentProcessingResult result, String kind) {
        for (int i = 0; i < result.triggeredEvents().size(); i++) {
            if (kind.equals(scalarProperty(result.triggeredEvents().get(i), "kind"))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfType(DocumentProcessingResult result, String blueId) {
        for (int i = 0; i < result.triggeredEvents().size(); i++) {
            Node event = result.triggeredEvents().get(i);
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
}
