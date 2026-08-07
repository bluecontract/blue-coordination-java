package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.ExternalBlockerProbeAssertions;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeProgramPlanIntegrationTest {
    @Test
    void shouldReuseFrozenPlanForUnchangedInlineCompute() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              value: warm"));

        // when
        DocumentProcessingResult first = support.processRun(document);
        DocumentProcessingResult second = support.processRun(first.document());

        // then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(first), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(first));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(second), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(second));
        assertEquals(1L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlanCacheHits());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(1L, metrics.computeProgramNormalizations());
        assertEquals(1L, metrics.computeProgramSourceBuilds());
        assertEquals(0L, metrics.computeDefinitionNormalizations());
        assertTrue(metrics.computePlanWeightBytes() > 0L);
    }

    @Test
    void shouldNormalizeReferencedDefinitionOnlyOnCacheMiss() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = definitionDocument(support, "Warm Definition");

        // when
        DocumentProcessingResult first = support.processRun(document);
        DocumentProcessingResult second = support.processRun(first.document());

        // then
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
    void shouldUseExactDefinitionIdentityAcrossDocuments() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node documentA = definitionDocument(support, "Definition A");
        Node documentB = definitionDocument(support, "Definition B");

        // when
        DocumentProcessingResult firstA = support.processRun(documentA);
        DocumentProcessingResult firstB = support.processRun(documentB);
        DocumentProcessingResult warmA = support.processRun(firstA.document());
        DocumentProcessingResult warmB = support.processRun(firstB.document());

        // then
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
    void shouldMaterializePureBlueIdDefinitionThroughSelectedWorkflowProvider() {
        // given
        BexProcessingMetrics metrics =
                new BexProcessingMetrics();
        Node exactDefinition =
                exactProviderDefinition();
        BasicNodeProvider definitionProvider =
                new BasicNodeProvider(exactDefinition);
        String definitionBlueId =
                definitionProvider.getBlueIdByName(
                        exactDefinition.getName());
        ComputeWorkflowTestSupport support =
                ComputeWorkflowTestSupport.create(
                        CoordinationProcessorOptions.builder()
                                .processingMetrics(metrics)
                                .build(),
                        definitionProvider);
        Node document = referencedDefinitionDocument(
                support,
                definitionBlueId);

        // when
        DocumentProcessingResult cold =
                support.processRun(document);
        DocumentProcessingResult warm =
                support.processRun(cold.document());

        // then
        assertEquals(
                "Provider Definition",
                onlyEvent(cold).get("/kind"));
        assertEquals(
                "Provider Definition",
                onlyEvent(warm).get("/kind"));
        assertEquals(1L, metrics.computePlanCacheMisses());
        assertEquals(1L, metrics.computePlanCacheHits());
        assertEquals(1L, metrics.computePlansBuilt());
        assertEquals(
                1L,
                metrics.computeDefinitionNormalizations());
    }

    @Test
    void shouldKeepInvalidDefinitionProviderEvidenceOutOfRuntimeFatal() {
        // given
        Node exactDefinition =
                exactProviderDefinition();
        BasicNodeProvider identityProvider =
                new BasicNodeProvider(exactDefinition);
        String definitionBlueId =
                identityProvider.getBlueIdByName(
                        exactDefinition.getName());
        NodeProvider invalidProvider =
                invalidEvidenceProvider(
                        definitionBlueId);
        ComputeWorkflowTestSupport support =
                ComputeWorkflowTestSupport.create(
                        CoordinationProcessorOptions.builder()
                                .build(),
                        invalidProvider);
        Node document = referencedDefinitionDocument(
                support,
                definitionBlueId);

        // when
        DocumentProcessingResult result =
                support.processRun(document);

        // then
        NodeProviderResult providerEvidence =
                invalidProvider.fetchResultByBlueId(
                        definitionBlueId);
        boolean exactProviderRejection =
                providerEvidence.outcome()
                        == NodeProviderOutcome.INVALID_EVIDENCE
                        && providerEvidence.diagnostic()
                        .isPresent()
                        && "forged definition evidence"
                        .equals(
                                providerEvidence
                                        .diagnostic()
                                        .get());
        boolean exactMisclassification =
                exactProviderRejection
                        && ExternalBlockerProbeAssertions
                        .exactDiagnostic(
                                result,
                                ProcessorStatus.RUNTIME_FATAL,
                                ProcessorErrorCategory
                                        .InvalidExternalChannelSnapshot,
                                "forged definition evidence")
                        && result.events().isEmpty()
                        && DirectBlueIdCalculator.calculateBlueId(
                        document).equals(
                        DirectBlueIdCalculator.calculateBlueId(
                                result.document()));
        ExternalBlockerProbeAssertions.classify(
                "invalid-execution-evidence-classification",
                "Language invalid-execution-evidence classification defect:",
                exactMisclassification,
                ExternalBlockerProbeAssertions
                        .exactDiagnostic(
                                result,
                                ProcessorStatus
                                        .INVALID_PROCESSING_DOCUMENT,
                                ProcessorErrorCategory
                                        .InvalidExternalChannelSnapshot,
                                "forged definition evidence"),
                ExternalBlockerProbeAssertions
                        .resultTuple(result)
                        + ", providerOutcome="
                        + providerEvidence.outcome()
                        + ", providerDiagnostic="
                        + providerEvidence.diagnostic()
                        + ", rolledBack="
                        + DirectBlueIdCalculator.calculateBlueId(
                        document).equals(
                        DirectBlueIdCalculator.calculateBlueId(
                                result.document())));
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status(),
                "Language invalid-execution-evidence classification defect: "
                        + ProcessingResultTestSupport
                                .diagnosticMessage(result));
        assertEquals(
                ProcessorErrorCategory
                        .InvalidExternalChannelSnapshot,
                ProcessingResultTestSupport
                        .diagnosticCategory(result));
        assertTrue(
                ProcessingResultTestSupport
                        .diagnosticMessage(result)
                        .contains(
                                "forged definition evidence"));
    }

    @Test
    void shouldBuildSeparatePlanForChangedStepContent() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node documentA = inlineDocument(support, "A");
        Node documentB = inlineDocument(support, "B");

        // when
        DocumentProcessingResult resultA = support.processRun(documentA);
        DocumentProcessingResult resultB = support.processRun(documentB);

        // then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport
                .isCapabilityFailure(resultA));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport
                .isCapabilityFailure(resultB));
        assertEquals(2L, metrics.computePlanCacheMisses());
        assertEquals(0L, metrics.computePlanCacheHits());
        assertEquals(2L, metrics.computePlansBuilt());
        assertEquals(2L, metrics.computeProgramNormalizations());
        assertEquals(2L, metrics.computeProgramSourceBuilds());
    }

    @Test
    void shouldNotCacheMalformedProgramPlan() {
        // given
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

        // when
        DocumentProcessingResult first = malformedSupport.processRun(malformed);
        DocumentProcessingResult second = malformedSupport.processRun(malformed);

        // then
        assertRuntimeFatal(first, "Unknown entry function");
        assertRuntimeFatal(second, "Unknown entry function");
        assertEquals(2L, malformedMetrics.computePlanCacheMisses());
        assertEquals(0L, malformedMetrics.computePlanCacheHits());
        assertEquals(2L, malformedMetrics.computePlansBuilt());
    }

    @Test
    void shouldNotCachePlanAfterFatalComputeResult() {
        // given
        BexProcessingMetrics fatalMetrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport fatalSupport = support(fatalMetrics);
        Node fatal = fatalSupport.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: Build",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              events: malformed"));

        // when
        DocumentProcessingResult first = fatalSupport.processRun(fatal);
        DocumentProcessingResult second = fatalSupport.processRun(fatal);

        // then
        assertRuntimeFatal(first, "Compute result events must be a list");
        assertRuntimeFatal(second, "Compute result events must be a list");
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

    private static Node referencedDefinitionDocument(
            ComputeWorkflowTestSupport support,
            String definitionBlueId) {
        return support.initialize(
                support.yaml(
                        support.operationWorkflowDocument(
                                String.join(
                                        "\n",
                                        "    steps:",
                                        "      - name: Build",
                                        "        type: Coordination/Compute",
                                        "        definition:",
                                        "          blueId: "
                                                + definitionBlueId,
                                        "        entry: build"))))
                .document();
    }

    private static Node exactProviderDefinition() {
        Node returnedEvent =
                new Node().properties(
                        "kind",
                        new Node().properties(
                                "$const",
                                new Node().value("kind")));
        Node returnedResult =
                new Node().properties(
                        "events",
                        new Node().items(returnedEvent));
        Node buildFunction =
                new Node().properties(
                        "do",
                        new Node().items(
                                new Node().properties(
                                        "$return",
                                        returnedResult)));
        return new Node()
                .name("Exact Provider Compute Definition")
                .description(
                        "Metadata retained across hosted normalization")
                .properties(
                        "constants",
                        new Node().properties(
                                "kind",
                                new Node().value(
                                        "Provider Definition")))
                .properties(
                        "functions",
                        new Node().properties(
                                "build",
                                buildFunction));
    }

    private static NodeProvider invalidEvidenceProvider(
            final String definitionBlueId) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                if (definitionBlueId.equals(blueId)) {
                    return NodeProviderResult.invalidEvidence(
                            "forged definition evidence");
                }
                return NodeProviderResult.notFound();
            }
        };
    }

    private static Node onlyEvent(DocumentProcessingResult result) {
        assertEquals(1, result.events().size(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        return result.events().get(0);
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result,
                                           String expectedMessage) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result) != null
                        && blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(expectedMessage),
                blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
    }
}
