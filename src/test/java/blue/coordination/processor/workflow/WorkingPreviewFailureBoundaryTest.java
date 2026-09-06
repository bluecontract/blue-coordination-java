package blue.coordination.processor.workflow;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflow;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real handler/context preview failures must reach their owning classification boundary intact. */
class WorkingPreviewFailureBoundaryTest {
    private static final Node HANDLER = new Node().name("Working preview failure control");
    private static final String HANDLER_ID = DirectBlueIdCalculator.calculateBlueId(HANDLER);

    @Test void unknownFrozenPreviewFailureIsNoncommitting() { run(true, true); }
    @Test void unknownMutablePreviewFailureIsNoncommitting() { run(false, true); }
    @Test void invalidFrozenCanonicalPatchRemainsASemanticFailure() { run(true, false); }
    @Test void invalidMutableCanonicalPatchRemainsASemanticFailure() { run(false, false); }

    private static void run(boolean frozen, boolean closeBeforePreview) {
        int[] executions = {0};
        var registry = ContractProcessorRegistryBuilder.create().registerDefaults()
                .register(HANDLER_ID, HANDLER, new HandlerProcessor<PreviewHandler>() {
                    @Override public Class<PreviewHandler> contractType() { return PreviewHandler.class; }
                    @SuppressWarnings("try") // Deliberately exercise an already-closed preview handle.
                    @Override public void execute(PreviewHandler handler, ProcessorExecutionContext context) {
                        executions[0]++;
                        context.applyPatch(JsonPatch.replace("/counter", new Node().value(BigInteger.valueOf(999))));
                        StepExecutionContext step = new StepExecutionContext(context, new SequentialWorkflow(), null,
                                (FrozenNode) null, null, 0, Map.of());
                        try (WorkingDocument working = step.workingDocument()) {
                            // A lifecycle/implementation mistake, not a deterministic authored error.
                            if (closeBeforePreview) working.close();
                            if (frozen) step.advanceWorkingDocumentFrozen(List.of(FrozenJsonPatch.add("/counter/invalid",
                                    FrozenNode.fromNode(new Node().value(BigInteger.ONE)))));
                            else step.advanceWorkingDocument(List.of(JsonPatch.add("/counter/invalid", new Node().value(BigInteger.ONE))));
                        }
                    }
                }).build();
        NodeProvider provider = key -> HANDLER_ID.equals(key) ? List.of(HANDLER.clone())
                : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(key);
        try (DocumentProcessor processor = DocumentProcessor.builder().nodeProvider(provider).runtimeRegistry(registry).build()) {
            Node authored = new Node().name("Preview boundary control").properties("counter", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("lifecycle", new Node().type(new Node().blueId(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                            .properties("initialize", new Node().type(new Node().blueId(HANDLER_ID)).properties("channel", new Node().value("lifecycle"))));
            if (closeBeforePreview) {
                UnclassifiedProcessingException failure = assertThrows(UnclassifiedProcessingException.class,
                        () -> processor.initializeDocument(authored));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("closed"));
            } else {
                DocumentProcessingResult result = processor.initializeDocument(authored);
                assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
                assertEquals(ProcessorErrorCategory.InvalidPatch, result.diagnostic().category());
                assertFalse(result.commits());
                assertTrue(result.events().isEmpty());
                assertEquals(BigInteger.ZERO, result.document().get("/counter"));
            }
            assertEquals(1, executions[0]);
            assertEquals(BigInteger.ZERO, authored.get("/counter"));
        }
    }

    public static final class PreviewHandler extends HandlerContract { }
}
