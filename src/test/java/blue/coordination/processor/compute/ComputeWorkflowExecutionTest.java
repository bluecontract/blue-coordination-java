package blue.coordination.processor.compute;

import blue.bex.api.BexEngine;
import blue.bex.api.BexMetricsSink;
import blue.bex.result.BexMetrics;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.coordination.processor.workflow.StepExecutionContext;
import blue.coordination.processor.workflow.WorkflowStepExecutor;
import blue.coordination.processor.workflow.WorkflowStepResult;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario:
 * Primary {@code Coordination/Compute} behavior is verified with direct patch and event effects.
 *
 * Main flow:
 * 1. Execute inline Compute programs and Compute Definition backed programs.
 * 2. Prove Compute can read {@code $document}, {@code $event}, {@code $steps}, and
 *    {@code $currentContract}.
 * 3. Prove Compute can apply returned changesets, emit events, return step results, and consume gas.
 * 4. Prove returned changesets remain readable as step result data after being applied.
 * 5. Keep Trigger Event and literal Update Document compatibility intact.
 *
 * Actors and operations:
 * - The owner timeline calls {@code run}.
 * - Compute steps build patches, data, and events.
 * - Later Compute steps read prior named step results.
 * - Compatibility cases ensure existing non-BEX workflow executors still work.
 */
class ComputeWorkflowExecutionTest {
    @Test
    void shouldEmitEventWithoutMutatingDocumentForInlineCompute() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Compute Event",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("idle", result.document().get("/status"));
        assertEquals(1, result.events().size());
        assertEquals("Compute Event", result.events().get(0).get("/kind"));
    }

    @Test
    void shouldExposeInlineComputeResultToLaterSteps() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              approved: true",
                "              reason: ok",
                "      - name: ReadPrior",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Prior Result",
                "              approved:",
                "                $steps: Build.approved",
                "              reason:",
                "                $steps: Build.reason",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);
        Node event = onlyEvent(result);

        // Then
        assertEquals("Prior Result", event.get("/kind"));
        assertEquals(Boolean.TRUE, event.get("/approved"));
        assertEquals("ok", event.get("/reason"));
    }

    @Test
    void shouldSuppressComputedEventsWhenEmissionIsDisabled() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        emitEvents: false",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Should Not Emit",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertTrue(result.events().isEmpty());
    }

    @Test
    void shouldExportStepResultWhenEventEmissionIsDisabled() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        emitEvents: false",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Should Not Emit",
                "          - $return:",
                "              approved: true",
                "      - name: Read",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Exported Result",
                "              approved:",
                "                $steps: Build.approved",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("Exported Result", onlyEvent(result).get("/kind"));
        assertEquals(Boolean.TRUE, onlyEvent(result).get("/approved"));
    }

    @Test
    void shouldSuppressStepResultWhenReturnResultIsFalse() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        returnResult: false",
                "        do:",
                "          - $return:",
                "              approved: true",
                "      - name: ReadPrior",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Missing Prior",
                "              approved:",
                "                $coalesce:",
                "                  - $steps: Build.approved",
                "                  - missing",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("missing", onlyEvent(result).get("/approved"));
    }

    @Test
    void shouldEmitEventsWhenReturnResultIsFalse() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        returnResult: false",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Event Still Emits",
                "          - $return:",
                "              approved: true"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("Event Still Emits", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldExportUnnamedComputeStepByIndexKey() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              value: abc",
                "      - name: Read",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind:",
                "                $steps: Step1.value",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("abc", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldApplyComputeChangesetAndRetainStepData() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: BuildPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: active",
                "          - $return: {}",
                "      - name: VerifyPatchData",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Patch Data",
                "              patchPath:",
                "                $steps:",
                "                  step: BuildPatch",
                "                  path: /changeset/0/path",
                "              patchValue:",
                "                $steps:",
                "                  step: BuildPatch",
                "                  path: /changeset/0/val",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("active", result.document().get("/status"));
        assertEquals("/status", onlyEvent(result).get("/patchPath"));
        assertEquals("active", onlyEvent(result).get("/patchValue"));
    }

    @Test
    void shouldSuppressAccumulatedChangesWithExplicitEmptyChangeset() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: BuildPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: active",
                "          - $return:",
                "              changeset: []"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("idle", result.document().get("/status"));
    }

    @Test
    void shouldApplyChangesetWhenReturnResultIsFalse() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: BuildPatch",
                "        type: Coordination/Compute",
                "        returnResult: false",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: active",
                "          - $return:",
                "              ignored: true"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("active", result.document().get("/status"));
    }

    @Test
    void shouldExportScalarResultFromInlineExpression() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: ReadStatus",
                "        type: Coordination/Compute",
                "        expr:",
                "          $document: /status",
                "      - name: EmitStatus",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Status",
                "              status:",
                "                $steps: ReadStatus",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        Node event = support.blue.resolveToSnapshot(
                onlyEvent(result)).resolvedRoot();
        assertEquals("idle", event.get("/status"));
    }

    @Test
    void shouldReadEventDocumentAndCurrentContract() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Inputs",
                "              request:",
                "                $event: /message/request",
                "              status:",
                "                $document: /status",
                "              channel:",
                "                $currentContract: /channel",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document, new Node().value("hello"));
        Node event = support.blue.resolveToSnapshot(
                onlyEvent(result)).resolvedRoot();

        // Then
        assertEquals("hello", event.get("/request"));
        assertEquals("idle", event.get("/status"));
        assertEquals("ownerChannel", event.get("/channel"));
    }

    @Test
    void shouldPreserveAuthoredCurrentContractChannelBinding() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(String.join("\n",
                "name: Compute Authored Channel Test",
                "status: idle",
                "contracts:",
                CoordinationTestResources.simpleTimelineChannelYaml("manualChannel", "owner", 2),
                "  run:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: manualChannel",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Authored Channel",
                "              channel:",
                "                $currentContract: /channel",
                "          - $return: {}"))).document();

        // When
        DocumentProcessingResult result = support.process(
                document,
                support.operationRequest("run", "manualChannel", new Node().value("request")));

        // Then
        assertEquals("manualChannel", onlyEvent(result).get("/channel"));
    }

    @Test
    void shouldResolveComputeDefinitionBySiblingContractKey() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(String.join("\n",
                "  computeLogic:",
                "    type: Coordination/Compute Definition",
                "    constants:",
                "      kind: From Definition",
                "    functions:",
                "      build:",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind:",
                "                $const: kind",
                "          - $return: {}"),
                String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition: computeLogic",
                "        entry: build")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("From Definition", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldResolveComputeDefinitionByAbsolutePointer() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(String.join("\n",
                "  computeLogic:",
                "    type: Coordination/Compute Definition",
                "    functions:",
                "      build:",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Absolute Definition",
                "          - $return: {}"),
                String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition: /contracts/computeLogic",
                "        entry: build")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("Absolute Definition", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldExecuteInlineObjectComputeDefinition() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition:",
                "          constants:",
                "            kind: Inline Definition",
                "          functions:",
                "            build:",
                "              do:",
                "                - $appendEvent:",
                "                    type: Coordination/Event",
                "                    kind:",
                "                      $const: kind",
                "                - $return: {}",
                "        entry: build"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("Inline Definition", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldNotExecuteComputeDefinitionMarkerByItself() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(String.join("\n",
                "  computeLogic:",
                "    type: Coordination/Compute Definition",
                "    functions:",
                "      build:",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Should Not Happen",
                "          - $return: {}"),
                String.join("\n",
                "    steps: []")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertTrue(result.events().isEmpty());
    }

    @Test
    void shouldFailClosedForMissingDefinition() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition: missingCompute",
                "        entry: build"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertRuntimeFatal(result, "Compute definition not found");
    }

    @Test
    void shouldFailClosedForMissingEntry() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(String.join("\n",
                "  computeLogic:",
                "    type: Coordination/Compute Definition",
                "    functions:",
                "      build:",
                "        do:",
                "          - $return: {}"),
                String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition: computeLogic",
                "        entry: missing")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertRuntimeFatal(result, "Unknown entry function");
    }

    @Test
    void shouldOverrideDefinitionConstantsWithStepConstants() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(String.join("\n",
                "  computeLogic:",
                "    type: Coordination/Compute Definition",
                "    constants:",
                "      kind: From Definition",
                "    functions:",
                "      build:",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind:",
                "                $const: kind",
                "          - $return: {}"),
                String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition: computeLogic",
                "        entry: build",
                "        constants:",
                "          kind: From Step")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("From Step", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldEscapeJsonPointerSegmentsInDefinitionReference() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(String.join("\n",
                "  \"compute/logic~v1\":",
                "    type: Coordination/Compute Definition",
                "    functions:",
                "      build:",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Escaped Definition",
                "          - $return: {}"),
                String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        definition: compute/logic~v1",
                "        entry: build")))).document();

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("Escaped Definition", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldExecuteLocalFunctionsWithoutDefinition() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        entry: build",
                "        functions:",
                "          build:",
                "            do:",
                "              - $appendEvent:",
                "                  type: Coordination/Event",
                "                  kind: Local Function",
                "              - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("Local Function", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldReportExplicitBexGasExhaustionAsGasLimitExceeded() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        gasLimit: 1",
                "        do:",
                "          - $return:",
                "              ok: true"));

        // When
        DocumentProcessingResult explicit = support.processRun(document);

        // Then
        assertGasLimitExceeded(explicit);
    }

    @Test
    void shouldReportDefaultBexGasExhaustionAsGasLimitExceeded() {
        // Given
        ComputeWorkflowTestSupport lowDefault = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder().defaultComputeGasLimit(1L).build());
        Node lowDefaultDocument = lowDefault.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              ok: true"));

        // When
        DocumentProcessingResult defaultFailure = lowDefault.processRun(lowDefaultDocument);

        // Then
        assertGasLimitExceeded(defaultFailure);
    }

    @Test
    void shouldRunComputeWithSufficientDefaultGasLimit() {
        // Given
        ComputeWorkflowTestSupport normalDefault = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder().defaultComputeGasLimit(100_000L).build());
        Node normalDocument = normalDefault.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              ok: true"));

        // When
        DocumentProcessingResult result = normalDefault.processRun(
                normalDocument);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport
                .isCapabilityFailure(result));
    }

    @Test
    void shouldRequirePositiveDefaultComputeGasLimit() {
        // Given
        long[] invalidLimits = {0L, -1L};

        // When
        for (long invalidLimit : invalidLimits) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> CoordinationProcessorOptions.builder()
                            .defaultComputeGasLimit(invalidLimit));

            // Then
            assertTrue(failure.getMessage().contains(
                    "defaultComputeGasLimit must be positive"));
        }
    }

    @Test
    void shouldEmitExplicitAndAccumulatedResultEvents() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Explicit",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              events:",
                "                - type: Coordination/Event",
                "                  kind: Explicit Events",
                "              changeset: []",
                "      - name: Accumulator",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Accumulator Event",
                "          - $return:",
                "              approved: true"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals(2, result.events().size());
        assertEquals("Explicit Events", result.events().get(0).get("/kind"));
        assertEquals("Accumulator Event", result.events().get(1).get("/kind"));
    }

    @Test
    void shouldFailClosedForInvalidEventsField() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              events: not-a-list"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertRuntimeFatal(result, "Compute result events must be a list");
    }

    @Test
    void shouldFailClosedForInvalidChangesetField() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              changeset: not-a-list"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertRuntimeFatal(result, "Compute result changeset must be a list");
    }

    @Test
    void shouldFailClosedForScalarChangesetEntries() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              changeset:",
                "                - hello"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertRuntimeFatal(result, "Compute result changeset entry 0 must be an object");
    }

    @Test
    void shouldEmitScalarEventEntriesAsBlueNodes() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              events:",
                "                - hello"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result));
        assertEquals("hello", onlyEvent(result).getValue());
    }

    @Test
    void shouldEvaluateNullYamlEventPlaceholderAsBexEmptyPredicate() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              events:",
                "                - null"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result));
        assertEquals(
                Boolean.FALSE,
                onlyEvent(result).getValue());
    }

    @Test
    void shouldRunPureComputeWorkflowWithBexOnlyRunner() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .sequentialWorkflowRunner(SequentialWorkflowRunner.withBexEngine(
                                BexEngine.builder().build(),
                                100_000L))
                        .build());
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: BEX Only",
                "          - $return: {}"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals("BEX Only", onlyEvent(result).get("/kind"));
    }

    @Test
    void shouldRunLiteralTriggerAndUpdateDocumentSteps() {
        // Given
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create();
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Apply",
                "        type: Coordination/Update Document",
                "        changeset:",
                "          - op: replace",
                "            path: /status",
                "            val: 42",
                "      - name: Trigger",
                "        type: Coordination/Trigger Event",
                "        event:",
                "          type: Coordination/Event",
                "          kind: Existing Trigger",
                "          status: static"));

        // When
        DocumentProcessingResult result = support.processRun(document);

        // Then
        assertEquals(BigInteger.valueOf(42), result.document().get("/status"));
        assertEquals("Existing Trigger", onlyEvent(result).get("/kind"));
        assertEquals("static", onlyEvent(result).get("/status"));
    }

    @Test
    void shouldUseBexEngineCompileCacheAcrossRuns() {
        // Given
        final List<BexMetrics> metrics = new ArrayList<BexMetrics>();
        BexEngine engine = BexEngine.builder().metrics(new BexMetricsSink() {
            @Override
            public void accept(BexMetrics item) {
                metrics.add(item.copy());
            }
        }).build();
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder().bexEngine(engine).build());
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        expr:",
                "          $document: /status"));

        // When
        Node afterFirst = support.processRun(document).document();
        long hitsAfterWarmup = 0L;
        long missesAfterWarmup = 0L;
        for (BexMetrics item : metrics) {
            hitsAfterWarmup += item.compileCacheHits();
            missesAfterWarmup += item.compileCacheMisses();
        }

        support.processRun(afterFirst);

        long totalHits = 0L;
        long totalMisses = 0L;
        for (BexMetrics item : metrics) {
            totalHits += item.compileCacheHits();
            totalMisses += item.compileCacheMisses();
        }
        // Then
        assertTrue(totalHits - hitsAfterWarmup > 0L);
        assertEquals(0L, totalMisses - missesAfterWarmup);
    }

    @Test
    void shouldProvideFrozenStepAndContractNodesToExecutors() {
        // Given
        final AtomicBoolean sawFrozenStep = new AtomicBoolean(false);
        final AtomicBoolean sawFrozenContract = new AtomicBoolean(false);
        WorkflowStepExecutor<Compute> executor = new WorkflowStepExecutor<Compute>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                return step instanceof Compute;
            }

            @Override
            public WorkflowStepResult execute(Compute step, StepExecutionContext context) {
                sawFrozenStep.set(context.stepFrozenNode() != null);
                sawFrozenContract.set(context.currentContractFrozenNode() != null);
                return WorkflowStepResult.none();
            }
        };
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .sequentialWorkflowRunner(new SequentialWorkflowRunner(
                                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(executor)))
                        .build());
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    largePayload:",
                "      item000: value000",
                "      item001: value001",
                "      item002: value002",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return: {}"));

        // When
        support.processRun(document);

        // Then
        assertTrue(sawFrozenStep.get());
        assertTrue(sawFrozenContract.get());
    }

    private static Node onlyEvent(DocumentProcessingResult result) {
        assertEquals(
                1,
                result.events().size(),
                result.status()
                        + ": "
                        + blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result));
        return result.events().get(0);
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result, String expectedMessage) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result) != null && blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(expectedMessage),
                blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }

    private static void assertGasLimitExceeded(
            DocumentProcessingResult result) {
        String diagnostic =
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result);
        assertEquals(
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                result.status(),
                diagnostic);
        assertTrue(
                diagnostic != null
                        && diagnostic.toLowerCase(
                        java.util.Locale.ROOT)
                        .contains("gas"),
                diagnostic);
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
    }
}
