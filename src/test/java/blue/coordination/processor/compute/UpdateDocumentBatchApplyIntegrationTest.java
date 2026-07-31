package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario:
 * BEX-produced changesets flow through the language batch patch API.
 *
 * Main flow:
 * 1. Compute builds patch data, including duplicate paths where order matters.
 * 2. Compute applies returned changesets directly through batch apply.
 * 3. Additional cases prove later Compute steps see patched state, and
 *    literal Update Document changesets still use batch apply.
 *
 * Actors and operations:
 * - The owner timeline calls {@code run}.
 * - Compute creates changesets and events.
 * - Update Document remains supported for literal or separately authored patches.
 */
class UpdateDocumentBatchApplyIntegrationTest {
    @Test
    void shouldUseBatchApplyAndPreserveComputePatchOrder() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .processingMetrics(metrics)
                        .build());
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithStatus("count: 0",
                String.join("\n",
                        "    steps:",
                        "      - name: BuildPatch",
                        "        type: Coordination/Compute",
                        "        do:",
                        "          - $appendChange:",
                        "              op: replace",
                        "              path: /status",
                        "              val: first",
                        "          - $appendChange:",
                        "              op: replace",
                        "              path: /count",
                        "              val: 1",
                        "          - $appendChange:",
                        "              op: replace",
                        "              path: /status",
                        "              val: second",
                        "          - $return:",
                        "              changeset:",
                        "                $changeset: true",
                        "              events:",
                        "                $events: true")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals("second", result.document().getAsText("/status"));
        assertEquals(BigInteger.ONE, result.document().get("/count"));
        assertEquals(3L, metrics.patchesApplied());
        assertEquals(1L, metrics.updateBatchPatchApplications());
        assertEquals(0L, metrics.updateIndividualPatchApplications());
        assertEquals(1L, metrics.directBexChangesetHits());
        assertEquals(3L, metrics.bexPatchNodeMaterializations());
        assertEquals(0L, metrics.bexPatchFrozenDirectConversions(),
                "newly computed scalar values still require the single measured BEX boundary conversion");
    }

    @Test
    void shouldUseBatchApplyForPureBexComputeEvent() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .processingMetrics(metrics)
                        .build());
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: BuildPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val:",
                "                $binding:",
                "                  name: event",
                "                  path: /message/request/status",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true",
                "      - name: BuildEvent",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Status Applied",
                "              status:",
                "                $document: /status",
                "          - $return:",
                "              events:",
                "                $events: true"));

        // When
        DocumentProcessingResult result = support.processRun(document,
                new Node().properties("status", new Node().value("active")));

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals("active", result.document().get("/status"));
        assertEquals(1, result.events().size());
        assertEquals("Status Applied", result.events().get(0).get("/kind"));
        assertEquals("active", result.events().get(0).get("/status"));
        assertEquals(1L, metrics.patchesApplied());
        assertEquals(1L, metrics.updateBatchPatchApplications());
        assertEquals(0L, metrics.updateIndividualPatchApplications());
        assertEquals(1L, metrics.directBexChangesetHits());
        assertEquals(1L, metrics.eventsEmitted());
    }

    @Test
    void shouldUseBatchApplyForLiteralUpdateDocumentChangesets() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .processingMetrics(metrics)
                        .build());
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: ApplyLiteral",
                "        type: Coordination/Update Document",
                "        changeset:",
                "          - op: replace",
                "            path: /status",
                "            val: literal",
                "      - name: ApplySecondLiteral",
                "        type: Coordination/Update Document",
                "        changeset:",
                "          - op: replace",
                "            path: /status",
                "            val: existing"));
        long frozenHandedBefore = metric(metrics, "frozenPatchesHandedToLanguage");
        long frozenAcceptedBefore = metric(metrics, "frozenPatchValuesAccepted");
        long mutableFrozenBefore = metric(metrics, "mutablePatchValuesFrozen");
        long frozenMaterializedBefore = metric(metrics, "frozenPatchValuesMaterialized");

        // When
        DocumentProcessingResult result = support.processRun(document,
                new Node()
                        .properties("detail", new Node().value("detail"))
                        .properties("status", new Node().value("existing")));

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals("existing", result.document().get("/status"));
        assertEquals(2L, metrics.patchesApplied());
        assertEquals(2L, metrics.updateBatchPatchApplications());
        assertEquals(0L, metrics.updateIndividualPatchApplications());
        assertEquals(2L, metrics.updateStaticTemplatesBuilt());
        assertEquals(2L, metrics.updateStaticTemplateHits());
        assertEquals(0L, metrics.updateReflectionFallbacks());
        assertEquals(2L, metric(metrics, "frozenPatchesHandedToLanguage") - frozenHandedBefore);
        assertTrue(metric(metrics, "frozenPatchValuesAccepted") - frozenAcceptedBefore >= 2L);
        assertEquals(0L, metric(metrics, "mutablePatchValuesFrozen") - mutableFrozenBefore);
        assertEquals(0L, metric(metrics, "frozenPatchValuesMaterialized") - frozenMaterializedBefore);
    }

    @Test
    void shouldPreserveDollarPrefixedLiteralValuesInUpdateDocument() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: ApplyPatch",
                "        type: Coordination/Update Document",
                "        changeset:",
                "          - op: replace",
                "            path: /status",
                "            val:",
                "              $binding:",
                "                name: event",
                "                path: /message/request/status"));

        // When
        DocumentProcessingResult result = support.processRun(document,
                new Node().properties("status", new Node().value("existing")));

        // Then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals("event", result.document().get("/status/$binding/name"));
        assertEquals("/message/request/status",
                result.document().get("/status/$binding/path"));
    }

    private static long metric(BexProcessingMetrics metrics, String name) {
        Long value = metrics.languageCounters().get(name);
        return value != null ? value.longValue() : 0L;
    }
}
