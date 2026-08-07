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
    void shouldContinueWorkflowWhenTerminationIsAbsent() {
        // given
        String returnedFields = "approved: true";

        // when
        DocumentProcessingResult result = runCompute(
                returnedFields, "", updateStatusStep("continued"));

        // then
        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void shouldContinueWorkflowWhenTerminationIsNull() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  $null: true");

        // when
        DocumentProcessingResult result = runCompute(
                returnedFields, "", updateStatusStep("continued"));

        // then
        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void shouldRejectEmptyTerminationWithoutCause() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  $emptyObject: true");

        // when
        DocumentProcessingResult result = runCompute(
                returnedFields, "", updateStatusStep("must-not-run"));

        // then
        assertRuntimeFailure(result, "termination cause must be non-empty Text");
        assertEquals("idle", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void shouldPassApplicationCauseAndTextReasonUnchanged() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  cause: mandate-completed",
                "  reason: Mandate terminated");

        // when
        DocumentProcessingResult result = runCompute(returnedFields, "");

        // then
        assertApplicationTermination(result, "mandate-completed", "Mandate terminated");
    }

    @Test
    void shouldTreatMissingApplicationReasonAsOptional() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  cause: mandate-completed");

        // when
        DocumentProcessingResult result = runCompute(returnedFields, "");

        // then
        assertApplicationTermination(result, "mandate-completed", null);
    }

    @Test
    void shouldOmitEmptyTerminationReason() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  cause: mandate-completed",
                "  reason: ''");

        // when
        DocumentProcessingResult result = runCompute(returnedFields, "");

        // then
        assertApplicationTermination(result, "mandate-completed", null);
    }

    @Test
    void shouldPreserveWhitespaceTerminationReason() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  cause: mandate-completed",
                "  reason: '   '");

        // when
        DocumentProcessingResult result = runCompute(returnedFields, "");

        // then
        assertApplicationTermination(result, "mandate-completed", "   ");
    }

    @Test
    void shouldTreatNullTerminationReasonAsAbsent() {
        // given
        String returnedFields = String.join("\n",
                "termination:",
                "  cause: mandate-completed",
                "  reason:",
                "    $null: true");

        // when
        DocumentProcessingResult result = runCompute(returnedFields, "");

        // then
        assertApplicationTermination(result, "mandate-completed", null);
    }

    @Test
    void shouldRejectScalarAndListTerminationResults() {
        // given
        List<String> invalidResults = Arrays.asList(
                "termination: stop",
                "termination: []");

        // when
        for (String invalidResult : invalidResults) {
            DocumentProcessingResult result = runCompute(invalidResult, "");

            // then
            assertRuntimeFailure(result, "termination must be an object", invalidResult);
            assertNoTerminationMarker(result);
        }
    }

    @Test
    void shouldRejectMissingEmptyAndNonTextCauses() {
        // given
        List<String> invalidResults = Arrays.asList(
                String.join("\n", "termination:", "  reason: reason-only"),
                String.join("\n", "termination:", "  cause:", "    $null: true"),
                String.join("\n", "termination:", "  cause: ''"),
                String.join("\n", "termination:", "  cause: 7"),
                String.join("\n", "termination:", "  cause: true"),
                String.join("\n", "termination:", "  cause: []"),
                String.join("\n", "termination:", "  cause:", "    $emptyObject: true"));

        // when
        for (String invalidResult : invalidResults) {
            DocumentProcessingResult result = runCompute(invalidResult, "");

            // then
            assertRuntimeFailure(result,
                    "termination cause must be non-empty Text",
                    invalidResult);
            assertNoTerminationMarker(result);
        }
    }

    @Test
    void shouldRejectNonTextReasonsWithValidCause() {
        // given
        List<String> invalidResults = Arrays.asList(
                String.join("\n", "termination:", "  cause: completed", "  reason: 7"),
                String.join("\n", "termination:", "  cause: completed", "  reason: true"),
                String.join("\n", "termination:", "  cause: completed", "  reason: []"),
                String.join("\n",
                        "termination:",
                        "  cause: completed",
                        "  reason:",
                        "    $emptyObject: true"));

        // when
        for (String invalidResult : invalidResults) {
            DocumentProcessingResult result = runCompute(invalidResult, "");

            // then
            assertRuntimeFailure(result, "termination reason must be Text", invalidResult);
            assertNoTerminationMarker(result);
        }
    }

    @Test
    void shouldRejectUnknownTerminationFields() {
        // given
        List<String> properties = Arrays.asList(
                "other", "mode", "scope", "document", "delay");

        // when
        for (String property : properties) {
            DocumentProcessingResult result = runCompute(String.join("\n",
                    "termination:",
                    "  cause: completed",
                    "  " + property + ": forbidden"), "");

            // then
            assertRuntimeFailure(result, "unsupported properties");
        }
    }

    @Test
    void shouldTerminateAndStopWhenReturnResultIsFalse() {
        // given
        String options = "returnResult: false";

        // when
        DocumentProcessingResult result = runCompute(String.join("\n",
                        "termination:",
                        "  cause: hidden-result-returned",
                        "  reason: hidden-result"),
                options,
                updateStatusStep("must-not-run"));

        // then
        assertApplicationTermination(result, "hidden-result-returned", "hidden-result");
        assertEquals("idle", result.document().get("/status"));
    }

    @Test
    void shouldIgnoreMalformedInactiveEventsWhenEmissionIsDisabled() {
        // given
        String options = "emitEvents: false";

        // when
        DocumentProcessingResult result = runCompute(String.join("\n",
                        "events: malformed-but-inactive",
                        "termination:",
                        "  cause: events-disabled-request",
                        "  reason: events-disabled"),
                options);

        // then
        assertApplicationTermination(result, "events-disabled-request", "events-disabled");
        assertEquals(0, countKind(result, "must-not-emit"));
    }

    @Test
    void shouldPreventEffectsWhenActiveEventsAreInvalid() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status",
                "    val: changed",
                "events: malformed",
                "termination:",
                "  cause: must-not-buffer",
                "  reason: must-not-buffer"), "");

        // then
        assertRuntimeFailure(result, "events must be a list");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldPreventChangesetAndEventsWhenTerminationIsInvalid() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status",
                "    val: changed",
                "events:",
                "  - type: Coordination/Event",
                "    kind: planned",
                "termination:",
                "  cause: must-not-buffer",
                "  reason: 99"), "");

        // then
        assertRuntimeFailure(result, "reason must be Text");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldPreventEventsAndTerminationWhenChangesetIsInvalid() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset: invalid",
                "events:",
                "  - type: Coordination/Event",
                "    kind: planned",
                "termination:",
                "  cause: must-not-buffer",
                "  reason: must-not-buffer"), "");

        // then
        assertRuntimeFailure(result, "changeset must be a list");
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldPreventEveryEffectForInvalidChangesetEntryFields() {
        // given
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

        // when
        for (String changeset : invalidChangesets) {
            BexProcessingMetrics metrics = new BexProcessingMetrics();
            DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                    changeset,
                    "events:",
                    "  - type: Coordination/Event",
                    "    kind: planned",
                    "termination:",
                    "  cause: must-not-buffer",
                    "  reason: must-not-buffer"), "");

            // then
            assertRuntimeFailure(result, "Invalid Compute result", changeset);
            assertEquals("idle", result.document().get("/status"), changeset);
            assertEquals(0, countKind(result, "planned"), changeset);
            assertEquals(0L, metrics.eventsEmitted(), changeset);
            assertEquals(0L, metrics.successfulComputeTerminationRequests(), changeset);
            assertEquals(1L, metrics.computeResultValidationFailures(), changeset);
        }
    }

    @Test
    void shouldPreventEveryEffectForExplicitNullEventEntry() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status",
                "    val: changed",
                "events:",
                "  - $null: true",
                "termination:",
                "  cause: must-not-buffer",
                "  reason: must-not-buffer"), "");

        // then
        assertRuntimeFailure(result, "events cannot contain undefined/null entries");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldBufferValidEffectsOnceInSourceOrder() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
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
                        "  cause: effects-complete",
                        "  reason: complete"),
                "",
                updateStatusStep("must-not-run"));

        // then
        assertApplicationTermination(result, "effects-complete", "complete");
        assertEquals("changed", result.document().get("/status"));
        assertEquals("planned", result.document().get("/added"));
        assertEquals(Arrays.asList("first", "second"), kinds(result, "first", "second"));
        assertEquals(
                -1,
                indexOfType(
                        result,
                        RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED),
                "processor lifecycle events remain internal");
        assertEquals(2L, metrics.eventsEmitted());
        assertEquals(1L, metrics.successfulComputeTerminationRequests());
        assertEquals(0L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldBufferNoEffectsWhenPatchPreviewFails() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, String.join("\n",
                "changeset:",
                "  - op: replace",
                "    path: /status/child",
                "    val: changed",
                "events:",
                "  - type: Coordination/Event",
                "    kind: planned",
                "termination:",
                "  cause: must-not-buffer",
                "  reason: must-not-buffer"), "");

        // then
        assertRuntimeFailure(result, "Working document preview failed");
        assertEquals("idle", result.document().get("/status"));
        assertEquals(0, countKind(result, "planned"));
        assertEquals(0L, metrics.eventsEmitted());
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(0L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldUseAccumulatedEffectsAsFallbackWithReturnedTermination() {
        // given
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
                "                cause: fallback-complete",
                "                reason: fallback"));

        // when
        DocumentProcessingResult result = support.processRun(document);

        // then
        assertApplicationTermination(result, "fallback-complete", "fallback");
        assertEquals("accumulated", result.document().get("/status"));
        assertNull(result.document().getProperties().get("temporary"));
        assertEquals(1, countKind(result, "accumulated"));
        assertEquals(1L, metrics.successfulComputeTerminationRequests());
    }

    @Test
    void shouldPreferReturnedEffectsOverAccumulators() {
        // given
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

        // when
        DocumentProcessingResult result = support.processRun(document);

        // then
        assertSuccess(result);
        assertEquals("returned", result.document().get("/status"));
        assertEquals(1, countKind(result, "returned"));
        assertEquals(0, countKind(result, "accumulated"));
    }

    @Test
    void shouldChargeBexEvaluationGasForInvalidResult() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, "termination: invalid", "");

        // then
        assertRuntimeFailure(result, "termination must be an object");
        assertTrue(result.totalGas() > 0L);
        assertEquals(1L, metrics.bexCompiledExecutions());
        assertEquals(1L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldNotIncrementTerminationCountersForOrdinaryCompute() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // when
        DocumentProcessingResult result = runCompute(metrics, "ordinary: data", "");

        // then
        assertSuccess(result);
        assertEquals(0L, metrics.successfulComputeTerminationRequests());
        assertEquals(0L, metrics.declarativeTerminationSteps());
        assertEquals(0L, metrics.computeResultValidationFailures());
    }

    @Test
    void shouldNotRequestTerminationForLifecycleEventAlone() {
        // given
        String eventType = "Document Processing Terminated";

        // when
        DocumentProcessingResult result = runSteps(String.join("\n",
                "- name: Domain-looking lifecycle event",
                "  type: Coordination/Trigger Event",
                "  event:",
                "    type: " + eventType,
                "    cause: domain-completed",
                updateStatusStep("continued")));

        // then
        assertSuccess(result);
        assertEquals("continued", result.document().get("/status"));
        assertNoTerminationMarker(result);
    }

    @Test
    void shouldNotRequestTerminationForDomainMessageAlone() {
        // given
        String eventType = "Mandate/Mandate Terminated";

        // when
        DocumentProcessingResult result = runSteps(String.join("\n",
                "- name: Domain termination message",
                "  type: Coordination/Trigger Event",
                "  event:",
                "    type: " + eventType,
                "    reason: ordinary data",
                updateStatusStep("continued")));

        // then
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
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static void assertRuntimeFailure(DocumentProcessingResult result, String reasonFragment) {
        assertRuntimeFailure(result,
                reasonFragment,
                blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static void assertRuntimeFailure(DocumentProcessingResult result,
                                             String reasonFragment,
                                             String message) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), message);
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result) != null && blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(reasonFragment),
                message + ": " + blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static void assertApplicationTermination(DocumentProcessingResult result,
                                                     String expectedCause,
                                                     String expectedReason) {
        assertSuccess(result);
        assertEquals(expectedCause, terminationValue(result, "cause"));
        assertEquals(expectedReason, terminationValue(result, "reason"));
    }

    private static void assertNoTerminationMarker(DocumentProcessingResult result) {
        assertNull(terminationMarker(result));
    }

    private static int countKind(DocumentProcessingResult result, String kind) {
        int count = 0;
        for (Node event : result.events()) {
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
        for (Node event : result.events()) {
            Node kindNode = event.getProperties() != null ? event.getProperties().get("kind") : null;
            Object kind = kindNode != null ? kindNode.getValue() : null;
            if (kind instanceof String && allowed.contains(kind)) {
                actual.add((String) kind);
            }
        }
        return actual;
    }

    private static int indexOfKind(DocumentProcessingResult result, String kind) {
        for (int i = 0; i < result.events().size(); i++) {
            Node event = result.events().get(i);
            Node value = event.getProperties() != null ? event.getProperties().get("kind") : null;
            if (value != null && kind.equals(value.getValue())) {
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
