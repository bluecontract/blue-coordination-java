package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.BlueContracts;
import blue.language.runtime.BlueLanguage;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.DocumentTransitionEvidence;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Maps authoritative Contracts generalization facts into stable SDK values. */
final class SdkManagedGeneralizationEvidenceTest {
    private static final long GAS_LIMIT = 100_000L;
    private static final blue.language.processor.closure.DocumentId DOCUMENT =
            new blue.language.processor.closure.DocumentId(
                    "sdk-generalization-evidence");
    private static final Node HANDLER_TYPE = new Node()
            .name("SDK generalization evidence handler");
    private static final String HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);

    @Test
    void mapsExactEffectiveTypesAndGeneratedWritesFromContracts() {
        // given

        BasicNodeProvider applicationTypes = new BasicNodeProvider();
        applicationTypes.addSingleDocs(
                "name: Price\n"
                        + "amount:\n"
                        + "  type: Integer\n"
                        + "currency:\n"
                        + "  type: Text");
        applicationTypes.addSingleDocs(
                "name: Price in EUR\n"
                        + "type:\n"
                        + "  blueId: "
                        + applicationTypes.getBlueIdByName("Price")
                        + "\ncurrency: EUR");
        applicationTypes.addSingleDocs(
                "name: Global Product\n"
                        + "price:\n"
                        + "  type:\n"
                        + "    blueId: "
                        + applicationTypes.getBlueIdByName("Price"));
        applicationTypes.addSingleDocs(
                "name: European Product\n"
                        + "type:\n"
                        + "  blueId: "
                        + applicationTypes.getBlueIdByName(
                                "Global Product")
                        + "\nprice:\n"
                        + "  type:\n"
                        + "    blueId: "
                        + applicationTypes.getBlueIdByName(
                                "Price in EUR"));
        String requiredType = applicationTypes.getBlueIdByName(
                "European Product");
        String optionalType = applicationTypes.getBlueIdByName(
                "Global Product");
        String genericPriceType = applicationTypes.getBlueIdByName("Price");
        NodeProvider provider = new SequentialNodeProvider(
                applicationTypes,
                new RuntimeAndHandlerProvider());
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                new GeneralizationProcessor())
                        .build();

        try (BlueLanguage language = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .cachePolicy(BlueCachePolicy.disabled()).build();
             blue.language.conformance.ConformanceEngine conformance = language.processing().newConformanceEngine();
             BlueContracts contracts = BlueContracts.builder(language.processing())
                    .runtimeRegistry(registry).build();
             DocumentProcessor processor = DocumentProcessor.builder()
                    .runtimeAccess(contracts.runtimeAccess())
                    .conformanceEngine(conformance)
                    .registerContractProcessor(HANDLER_BLUE_ID, HANDLER_TYPE,
                            new GeneralizationProcessor())
                    .runtimeRegistryIdentity(registry.generationIdentity()).build()) {
            // when

            ClosureProcessResult result = execute(
                    processor,
                    generalizingDocument(requiredType));

            // then

            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertTrue(result.commits());
            DocumentTransitionEvidence exact = result
                    .documentTransitionEvidence()
                    .stream()
                    .filter(transition -> transition
                            .generatedGeneralizationWrites()
                            .stream()
                            .anyMatch(write -> "/type".equals(write.path())))
                    .findFirst()
                    .orElseThrow();
            ManagedSurfaceEvidence.DocumentTransition mapped =
                    SdkDrainResultMapper.documentTransition(exact);

            assertEquals(DocumentId.of(DOCUMENT.value()),
                    mapped.documentId());
            assertEquals(Optional.of(requiredType),
                    mapped.beforeEffectiveTypeBlueId());
            assertEquals(Optional.of(optionalType),
                    mapped.afterEffectiveTypeBlueId());
            assertFalse(mapped.beforeDocumentBlueId().equals(
                    mapped.afterDocumentBlueId()));
            assertTrue(mapped.authoredContractPatches().isEmpty());
            Map<String, ManagedSurfaceEvidence.GeneralizationWrite> writes =
                    mapped.generatedGeneralizationWrites().stream()
                            .collect(Collectors.toMap(
                                    ManagedSurfaceEvidence.GeneralizationWrite
                                            ::path,
                                    write -> write));
            assertEquals(Set.of("/price/type", "/type"), writes.keySet());
            assertEquals(genericPriceType,
                    writes.get("/price/type").valueBlueId());
            assertEquals(optionalType, writes.get("/type").valueBlueId());
            writes.values().forEach(write -> assertEquals(
                    0, write.requiringPatchIndex()));
        }
    }

    private static Node generalizingDocument(String requiredType) {
        return new Node()
                .name("Generated generalization evidence")
                .type(new Node().blueId(requiredType))
                .properties("price", new Node()
                        .properties("amount", new Node().value(150))
                        .properties("currency", new Node().value("EUR")))
                .contracts(new Node()
                        .properties("generalization", typed(
                                RuntimeBlueIds.TYPE_GENERALIZATION_POLICY)
                                .properties("defaultMode", new Node()
                                        .value("nearest-valid-ancestor")))
                        .properties("lifecycle", typed(
                                RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                        .properties("generalizePrice", typed(HANDLER_BLUE_ID)
                                .properties("channel", new Node()
                                        .value("lifecycle"))));
    }

    private static ClosureProcessResult execute(
            DocumentProcessor processor,
            Node document) {
        ClosureEnvironment environment = ClosureEvidenceFactory.environment(
                processor,
                hash('a'),
                hash('b'),
                "sdk-generalization-document-lineage-v1",
                "sdk-generalization-binding-lineage-v1",
                "sdk-generalization-provider-domain-v1",
                "sdk-generalization-external-order-v1",
                "sdk-generalization-portable-limits-v1",
                GasSchedule.contracts10().portableLimits());
        AffectedClosureSnapshot snapshot = snapshot(document);
        ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                GAS_LIMIT,
                Collections.emptyMap(),
                "sdk-generalization-shared-gas-v1");
        ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        "sdk-generalization-evidence",
                        null,
                        null,
                        "sdk-generalization-admission-policy-v1"),
                null,
                policy,
                environment);
        ClosureAttemptResult attempt;
        try (BlueClosureContracts contracts =
                     new BlueClosureContracts(processor)) {
            attempt = contracts.admitClosureWithLifecycleQueue(input);
        }
        assertTrue(attempt.isComplete());
        return attempt.processResult();
    }

    private static AffectedClosureSnapshot snapshot(Node document) {
        List<ManagedOccurrenceBinding> bindings = List.of();
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        generations.put(DOCUMENT, 1L);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                documents = new LinkedHashMap<>();
        documents.put(DOCUMENT, document);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        List.of(DOCUMENT),
                                        bindings),
                                generations,
                                documents,
                                bindings));
        FinalizedDocumentEvidence exact = finalized.document(DOCUMENT);
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                DOCUMENT,
                exact.blueId(),
                exact.document(),
                false,
                false,
                true,
                0L,
                exact.componentGeneration());
        return ClosureEvidenceFactory.affectedClosure(
                1L,
                List.of(managed),
                finalized.finalizedGraph().bindings(),
                finalized.components().stream()
                        .map(FinalizedComponentEvidence::component)
                        .toList(),
                List.of(DOCUMENT));
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static String hash(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    /** Test-only handler representation selected by exact type identity. */
    public static final class GeneralizationHandler extends HandlerContract {
    }

    private static final class GeneralizationProcessor
            implements HandlerProcessor<GeneralizationHandler> {
        @Override
        public Class<GeneralizationHandler> contractType() {
            return GeneralizationHandler.class;
        }

        @Override
        public String deriveChannel(
                GeneralizationHandler contract,
                HandlerRegistrationContext context) {
            return null;
        }

        @Override
        public void execute(
                GeneralizationHandler contract,
                ProcessorExecutionContext context) {
            context.applyPatch(JsonPatch.replace(
                    "/price/currency",
                    new Node().value("USD")));
        }
    }

    private static final class RuntimeAndHandlerProvider
            implements NodeProvider {
        private final NodeProvider runtime =
                BlueRuntimeTypeRegistry.getDefault().asProvider();

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            if (HANDLER_BLUE_ID.equals(blueId)) {
                return List.of(HANDLER_TYPE.clone());
            }
            return runtime.fetchByBlueId(blueId);
        }
    }

}
