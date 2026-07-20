package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeProgramPlanIntegrationTest {
    @Test
    void unchangedInlineComputeMissesOnceThenReusesItsFrozenPlan() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              value: warm"));

        DocumentProcessingResult first = support.processRun(document);
        DocumentProcessingResult second = support.processRun(first.document());

        assertFalse(first.capabilityFailure(), first.failureReason());
        assertFalse(second.capabilityFailure(), second.failureReason());
        assertEquals(1L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlanCacheHits());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(1L, metrics.computeProgramNormalizations());
        assertEquals(1L, metrics.computeProgramSourceBuilds());
        assertEquals(0L, metrics.computeDefinitionNormalizations());
        assertTrue(metrics.computePlanWeightBytes() > 0L);
    }

    @Test
    void referencedDefinitionIsReadFrozenEveryTimeButNormalizedOnlyOnMiss() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = definitionDocument(support, "Warm Definition");

        DocumentProcessingResult first = support.processRun(document);
        DocumentProcessingResult second = support.processRun(first.document());

        assertEquals("Warm Definition", onlyEvent(first).get("/kind"));
        assertEquals("Warm Definition", onlyEvent(second).get("/kind"));
        assertEquals(1L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlanCacheHits());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(1L, metrics.computeProgramNormalizations());
        assertEquals(1L, metrics.computeDefinitionNormalizations());
        assertEquals(1L, metrics.computeDefinitionMaterializations());
        assertEquals(2L, metrics.computeDefinitionFrozenDirectHits());
        assertEquals(1L, metrics.computeProgramSourceBuilds());
    }

    @Test
    void sameContractAndStepNamesAcrossDocumentsUseExactDefinitionIdentity() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node documentA = definitionDocument(support, "Definition A");
        Node documentB = definitionDocument(support, "Definition B");

        DocumentProcessingResult firstA = support.processRun(documentA);
        DocumentProcessingResult firstB = support.processRun(documentB);
        DocumentProcessingResult warmA = support.processRun(firstA.document());
        DocumentProcessingResult warmB = support.processRun(firstB.document());

        assertEquals("Definition A", onlyEvent(firstA).get("/kind"));
        assertEquals("Definition B", onlyEvent(firstB).get("/kind"));
        assertEquals("Definition A", onlyEvent(warmA).get("/kind"));
        assertEquals("Definition B", onlyEvent(warmB).get("/kind"));
        assertEquals(2L, metrics.computePlanCacheMisses());
        assertEquals(2L, metrics.computePlanCacheHits());
        assertEquals(2L, metrics.computePlansBuilt());
        assertEquals(2L, metrics.computeDefinitionNormalizations());
        assertEquals(2L, metrics.computeProgramSourceBuilds());
    }

    @Test
    void changedStepContentBuildsASeparatePlan() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node documentA = inlineDocument(support, "A");
        Node documentB = inlineDocument(support, "B");

        assertFalse(support.processRun(documentA).capabilityFailure());
        assertFalse(support.processRun(documentB).capabilityFailure());

        assertEquals(2L, metrics.computePlanCacheMisses());
        assertEquals(0L, metrics.computePlanCacheHits());
        assertEquals(2L, metrics.computePlansBuilt());
        assertEquals(2L, metrics.computeProgramNormalizations());
        assertEquals(2L, metrics.computeProgramSourceBuilds());
    }

    @Test
    void malformedProgramAndFatalResultNeverPoisonPlanCache() {
        BexProcessingMetrics malformedMetrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport malformedSupport = support(malformedMetrics);
        Node malformed = malformedSupport.initialize(malformedSupport.yaml(
                malformedSupport.operationWorkflowDocumentWithContracts(String.join("\n",
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

        assertRuntimeFatal(malformedSupport.processRun(malformed), "Unknown entry function");
        assertRuntimeFatal(malformedSupport.processRun(malformed), "Unknown entry function");
        assertEquals(2L, malformedMetrics.computePlanCacheMisses());
        assertEquals(0L, malformedMetrics.computePlanCacheHits());
        assertEquals(2L, malformedMetrics.computePlansBuilt());

        BexProcessingMetrics fatalMetrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport fatalSupport = support(fatalMetrics);
        Node fatal = fatalSupport.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              events: malformed"));

        assertRuntimeFatal(fatalSupport.processRun(fatal), "Compute result events must be a list");
        assertRuntimeFatal(fatalSupport.processRun(fatal), "Compute result events must be a list");
        assertEquals(2L, fatalMetrics.computePlanCacheMisses());
        assertEquals(0L, fatalMetrics.computePlanCacheHits());
        assertEquals(2L, fatalMetrics.computePlansBuilt());
    }

    private static ComputeWorkflowTestSupport support(BexProcessingMetrics metrics) {
        return ComputeWorkflowTestSupport.create(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
    }

    private static Node inlineDocument(ComputeWorkflowTestSupport support, String value) {
        return support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              value: " + value));
    }

    private static Node definitionDocument(ComputeWorkflowTestSupport support, String kind) {
        return support.initialize(support.yaml(support.operationWorkflowDocumentWithContracts(
                String.join("\n",
                        "  computeLogic:",
                        "    type: Coordination/Compute Definition",
                        "    constants:",
                        "      kind: " + kind,
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
    }

    private static Node onlyEvent(DocumentProcessingResult result) {
        assertEquals(1, result.triggeredEvents().size(), result.failureReason());
        return result.triggeredEvents().get(0);
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result,
                                           String expectedMessage) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), result.failureReason());
        assertTrue(result.failureReason() != null
                        && result.failureReason().contains(expectedMessage),
                result.failureReason());
    }
}
