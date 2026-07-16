package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeTerminationWorkflowTest {
    @Test
    void absentTerminationContinuesWorkflow() {
        DocumentProcessingResult result = runCompute("approved: true", "", updateStatusStep("continued"));

        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void nullTerminationContinuesWorkflow() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                "termination:",
                "  $null: true"), "", updateStatusStep("continued"));

        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void emptyTerminationRequestsGracefulStopWithoutReason() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                "termination:",
                "  $emptyObject: true"), "", updateStatusStep("must-not-run"));

        assertGracefulTermination(result, null);
        assertEquals("idle", result.document().get("/status"));
    }

    @Test
    void textReasonIsPassedUnchanged() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                "termination:",
                "  reason: Mandate terminated"), "");

        assertGracefulTermination(result, "Mandate terminated");
    }

    @Test
    void emptyReasonUsesCoreOmissionSemantics() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                "termination:",
                "  reason: ''"), "");

        assertGracefulTermination(result, null);
    }

    @Test
    void whitespaceReasonIsPreserved() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                "termination:",
                "  reason: '   '"), "");

        assertGracefulTermination(result, "   ");
    }

    @Test
    void nullReasonMeansNoReason() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                "termination:",
                "  reason:",
                "    $null: true"), "");

        assertGracefulTermination(result, null);
    }

    @Test
    void scalarListAndNonTextReasonAreRejected() {
        List<String> invalidResults = Arrays.asList(
                "termination: stop",
                "termination: []",
                String.join("\n", "termination:", "  reason: 7"),
                String.join("\n", "termination:", "  reason: true"),
                String.join("\n", "termination:", "  reason: []"),
                String.join("\n", "termination:", "  reason:", "    $emptyObject: true"));

        for (String invalidResult : invalidResults) {
            DocumentProcessingResult result = runCompute(invalidResult, "");
            assertFatalResult(result, "Invalid Compute result", invalidResult);
            assertEquals("fatal", terminationValue(result, "cause"), invalidResult);
        }
    }

    @Test
    void unknownFatalScopeAndDelayFieldsAreRejected() {
        for (String property : Arrays.asList("other", "cause", "fatal", "scope", "document", "delay")) {
            DocumentProcessingResult result = runCompute(String.join("\n",
                    "termination:",
                    "  " + property + ": forbidden"), "");
            assertFatalResult(result, "unsupported properties");
        }
    }

    @Test
    void returnResultFalseStillTerminatesAndStops() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                        "termination:",
                        "  reason: hidden-result"),
                "returnResult: false",
                updateStatusStep("must-not-run"));

        assertGracefulTermination(result, "hidden-result");
        assertEquals("idle", result.document().get("/status"));
    }

    @Test
    void emitEventsFalseDoesNotInterpretMalformedEvents() {
        DocumentProcessingResult result = runCompute(String.join("\n",
                        "events: malformed-but-inactive",
                        "termination:",
                        "  reason: events-disabled"),
                "emitEvents: false");

        assertGracefulTermination(result, "events-disabled");
        assertEquals(0, countKind(result, "must-not-emit"));
    }

    @Test
    void invalidActiveEventsPreventComputeChangesetAndTermination() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status",
                "    val: changed",
                "events: malformed",
                "termination:",
                "  reason: must-not-buffer"), "");

        assertFatalResult(result, "events must be a list");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void invalidTerminationPreventsComputeChangesetAndEvents() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status",
                "    val: changed",
                "events:",
                "  - type: Coordination/Event",
                "    kind: planned",
                "termination:",
                "  reason: 99"), "");

        assertFatalResult(result, "reason must be Text");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void invalidChangesetPreventsComputeEventsAndTermination() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset: invalid",
                "events:",
                "  - type: Coordination/Event",
                "    kind: planned",
                "termination:",
                "  reason: must-not-buffer"), "");

        assertFatalResult(result, "changeset must be a list");
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void invalidChangesetEntryFieldsPreventEveryPlannedEffect() {
        List<String> invalidChangesets = Arrays.asList(
                String.join("\n",
                        "changeset:",
                        "  - op: copy",
                        "    path: /status",
                        "    val: changed"),
                String.join("\n",
                        "changeset:",
                        "  - op: replace",
                        "    val: changed"),
                String.join("\n",
                        "changeset:",
                        "  - op: replace",
                        "    path: '   '",
                        "    val: changed"),
                String.join("\n",
                        "changeset:",
                        "  - op: add",
                        "    path: /added"));

        for (String changeset : invalidChangesets) {
            BexProcessingMetrics metrics = new BexProcessingMetrics();
            DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                    changeset,
                    "events:",
                    "  - type: Coordination/Event",
                    "    kind: planned",
                    "termination:",
                    "  reason: must-not-buffer"), "");

            assertFatalResult(result, "Invalid Compute result", changeset);
            assertEquals("idle", result.document().get("/status"), changeset);
            assertEquals(0, countKind(result, "planned"), changeset);
            assertEquals(0L, metrics.eventsEmitted(), changeset);
            assertEquals(0L, metrics.successfulComputeTerminationRequests(), changeset);
            assertEquals(1L, metrics.computeResultValidationFailures(), changeset);
        }
    }

    @Test
    void explicitNullEventEntryPreventsEveryPlannedEffect() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status",
                "    val: changed",
                "events:",
                "  - $null: true",
                "termination:",
                "  reason: must-not-buffer"), "");

        assertFatalResult(result, "events cannot contain undefined/null entries");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void validPlanBuffersChangesetEventsAndTerminationOnceInSourceOrder() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                        "changeset:",
                        "  - op: add",
                        "    path: /added",
                        "    val: planned",
                        "  - op: replace",
                        "    path: /status",
                        "    val: changed",
                        "events:",
                        "  - type: Coordination/Event",
                        "    kind: first",
                        "  - type: Coordination/Event",
                        "    kind: second",
                        "termination:",
                        "  reason: complete"),
                "",
                updateStatusStep("must-not-run"));

        assertGracefulTermination(result, "complete");
        assertEquals("changed", result.document().get("/status"));
        assertEquals("planned", result.document().get("/added"));
        assertEquals(Arrays.asList("first", "second"), kinds(result, "first", "second"));
        assertTrue(indexOfKind(result, "second")
                < indexOfType(result, RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
        assertEquals(2L, metrics.eventsEmitted());
        assertEquals(1L, metrics.successfulComputeTerminationRequests());
        assertEquals(0L, metrics.computeResultValidationFailures());
    }

    @Test
    void patchPreviewFailureBuffersNoComputeEventOrGracefulTermination() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status/child",
                "    val: changed",
                "events:",
                "  - type: Coordination/Event",
                "    kind: planned",
                "termination:",
                "  reason: must-not-buffer"), "");

        assertFatalResult(result, "Working document preview failed");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(0L, metrics.computeResultValidationFailures());
    }

    @Test
    void accumulatedChangesetAndEventsRemainFallbackWhenTerminationIsReturned() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Accumulate",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: add",
                "              path: /temporary",
                "              val: temporary",
                "          - $appendChange:",
                "              op: remove",
                "              path: /temporary",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: accumulated",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: accumulated",
                "          - $return:",
                "              termination:",
                "                reason: fallback"));

        DocumentProcessingResult result = support.processRun(document);

        assertGracefulTermination(result, "fallback");
        assertEquals("accumulated", result.document().get("/status"));
        assertNull(result.document().getProperties().get("temporary"));
        assertEquals(1, countKind(result, "accumulated"));
        assertEquals(1L, metrics.successfulComputeTerminationRequests());
    }

    @Test
    void returnedChangesetAndEventsRetainPrecedenceOverAccumulators() {
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Explicit Effects",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: accumulated",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: accumulated",
                "          - $return:",
                "              changeset:",
                "                - op: replace",
                "                  path: /status",
                "                  val: returned",
                "              events:",
                "                - type: Coordination/Event",
                "                  kind: returned"));

        DocumentProcessingResult result = support.processRun(document);

        assertSuccess(result);
        assertEquals("returned", result.document().get("/status"));
        assertEquals(1, countKind(result, "returned"));
        assertEquals(0, countKind(result, "accumulated"));
    }

    @Test
    void invalidResultStillChargesBexEvaluationGas() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, "termination: invalid", "");

        assertFatalResult(result, "termination must be an object");
        assertTrue(result.totalGas() > 0L);
        assertEquals(1L, metrics.bexCompiledExecutions());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void ordinaryNonTerminationComputeDoesNotIncrementTerminationCounters() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        DocumentProcessingResult result = runCompute(metrics, "ordinary: data", "");

        assertSuccess(result);
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(0L, metrics.declarativeTerminationSteps());
        assertEquals(0L, metrics.computeResultValidationFailures());
    }

    @Test
    void documentProcessingTerminatedEventAloneDoesNotRequestTermination() {
        DocumentProcessingResult result = runSteps(String.join("\n",
                "- name: Domain-looking lifecycle event",
                "  type: Coordination/Trigger Event",
                "  event:",
                "    type: Document Processing Terminated",
                "    cause: graceful",
                updateStatusStep("continued")));

        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void domainTerminatedMessageAloneDoesNotRequestTermination() {
        DocumentProcessingResult result = runSteps(String.join("\n",
                "- name: Domain termination message",
                "  type: Coordination/Trigger Event",
                "  event:",
                "    type: Mandate/Mandate Terminated",
                "    reason: ordinary data",
                updateStatusStep("continued")));

        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    private static DocumentProcessingResult runCompute(String returnedFields,
                                                       String options,
                                                       String... laterSteps) {
        return runCompute(null, returnedFields, options, laterSteps);
    }

    private static DocumentProcessingResult runCompute(BexProcessingMetrics metrics,
                                                       String returnedFields,
                                                       String options,
                                                       String... laterSteps) {
        StringBuilder steps = new StringBuilder();
        steps.append("- name: Compute Effects\n")
                .append("  type: Coordination/Compute\n");
        if (options != null && !options.trim().isEmpty()) {
            steps.append(indent(options, 2)).append('\n');
        }
        steps.append("  do:\n")
                .append("    - $return:");
        if (returnedFields == null || returnedFields.trim().isEmpty()) {
            steps.append(" {}\n");
        } else {
            steps.append('\n').append(indent(returnedFields, 8)).append('\n');
        }
        for (String laterStep : laterSteps) {
            steps.append(laterStep).append('\n');
        }
        return runSteps(metrics, steps.toString());
    }

    private static DocumentProcessingResult runSteps(String steps) {
        return runSteps(null, steps);
    }

    private static DocumentProcessingResult runSteps(BexProcessingMetrics metrics, String steps) {
        ComputeWorkflowTestSupport support = metrics != null ? support(metrics) : ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                indent(steps.trim(), 6)));
        return support.processRun(document);
    }

    private static ComputeWorkflowTestSupport support(BexProcessingMetrics metrics) {
        return ComputeWorkflowTestSupport.create(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
    }

    private static String updateStatusStep(String status) {
        return String.join("\n",
                "- name: Later Step",
                "  type: Coordination/Update Document",
                "  changeset:",
                "    - op: replace",
                "      path: /status",
                "      val: " + status);
    }

    private static String indent(String value, int spaces) {
        String prefix = repeat(' ', spaces);
        return prefix + value.replace("\n", "\n" + prefix);
    }

    private static String repeat(char character, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, character);
        return new String(characters);
    }

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
    }

    private static void assertFatalResult(DocumentProcessingResult result, String reasonFragment) {
        assertFatalResult(result, reasonFragment, result.failureReason());
    }

    private static void assertFatalResult(DocumentProcessingResult result,
                                          String reasonFragment,
                                          String message) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), message);
        assertTrue(result.failureReason() != null && result.failureReason().contains(reasonFragment),
                message + ": " + result.failureReason());
    }

    private static void assertGracefulTermination(DocumentProcessingResult result, String expectedReason) {
        assertSuccess(result);
        assertEquals("graceful", terminationValue(result, "cause"));
        assertEquals(expectedReason, terminationValue(result, "reason"));
    }

    private static void assertNoTerminationMarker(DocumentProcessingResult result) {
        assertNull(terminationMarker(result));
    }

    private static int countKind(DocumentProcessingResult result, String kind) {
        int count = 0;
        for (Node event : result.triggeredEvents()) {
            Node kindNode = event.getProperties() != null ? event.getProperties().get("kind") : null;
            if (kindNode != null && kind.equals(kindNode.getValue())) {
                count++;
            }
        }
        return count;
    }

    private static List<String> kinds(DocumentProcessingResult result, String... selected) {
        List<String> allowed = Arrays.asList(selected);
        List<String> actual = new ArrayList<String>();
        for (Node event : result.triggeredEvents()) {
            Node kindNode = event.getProperties() != null ? event.getProperties().get("kind") : null;
            Object kind = kindNode != null ? kindNode.getValue() : null;
            if (kind instanceof String && allowed.contains(kind)) {
                actual.add((String) kind);
            }
        }
        return actual;
    }

    private static int indexOfKind(DocumentProcessingResult result, String kind) {
        for (int i = 0; i < result.triggeredEvents().size(); i++) {
            Node event = result.triggeredEvents().get(i);
            Node value = event.getProperties() != null ? event.getProperties().get("kind") : null;
            if (value != null && kind.equals(value.getValue())) {
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

    private static Node terminationMarker(DocumentProcessingResult result) {
        Node contracts = result.document().getContracts();
        return contracts != null && contracts.getProperties() != null
                ? contracts.getProperties().get("terminated")
                : null;
    }

    private static Object terminationValue(DocumentProcessingResult result, String key) {
        Node marker = terminationMarker(result);
        if (marker == null || marker.getProperties() == null) {
            return null;
        }
        Node value = marker.getProperties().get(key);
        return value != null ? value.getValue() : null;
    }
}
