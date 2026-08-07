package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.repo.BlueRepository;
import blue.repo.coordination.DocumentStatus;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.StatusInProgress;
import blue.repo.coordination.UpdateDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InheritedStaticUpdateDocumentTest {

    @Test
    void shouldWriteInheritedStaticPatchValueFromResolvedContractView() {
        // given
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime blue =
                CoordinationTestResources.configuredBlue(repository);
        BasicNodeProvider documentTypes = new BasicNodeProvider();
        documentTypes.addSingleNodes(documentType(new Node()
                .name("Authored status")
                .type(reference(StatusInProgress.blueId()))));
        String documentTypeId = documentTypes.getBlueIdByName("Inherited Static Update Document");
        blue.addNodeProvider(documentTypes);

        // when
        DocumentProcessingResult result = blue.initializeDocument(
                blue.resolveToSnapshot(new Node().type(reference(documentTypeId))));

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertNull(result.diagnostic());
        Node canonicalStatus = result.document().getProperties().get("status");
        assertEquals(StatusInProgress.blueId(), canonicalStatus.getType().getBlueId());
        assertEquals("Authored status", canonicalStatus.getName());
        assertEquals(
                "active",
                blue.resolveToSnapshot(result.document())
                        .resolvedRoot().getAsText("/status/mode"));
        assertNull(canonicalStatus.getDescription(),
                "metadata inherited by Json Patch Entry.val must not become document content");
        blue.close();
    }

    @Test
    void shouldRejectAuthoredReferenceWithSiblingPayload() {
        // given
        BasicNodeProvider documentTypes = new BasicNodeProvider();

        // when
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> documentTypes.addSingleNodes(documentType(new Node()
                        .blueId(StatusInProgress.blueId())
                        .properties("mode", new Node().value("tampered")))));

        // then
        String diagnostic = messageChain(failure);
        assertTrue(diagnostic.contains(
                "must be a pure reference"), diagnostic);
    }

    private static Node documentType(Node patchValue) {
        return new Node()
                .name("Inherited Static Update Document")
                .properties("status", new Node().type(reference(DocumentStatus.blueId())))
                .contracts(new Node()
                        .properties("lifecycle", new Node()
                                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                                .properties("event", new Node()
                                        .type(reference(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED))))
                        .properties("initializeStatus", new Node()
                                .type(reference(SequentialWorkflow.blueId()))
                                .properties("channel", new Node().value("lifecycle"))
                                .properties("steps", new Node().items(new Node()
                                        .type(reference(UpdateDocument.blueId()))
                                        .properties("changeset", new Node().items(new Node()
                                                .properties("op", new Node().value("replace"))
                                                .properties("path", new Node().value("/status"))
                                                .properties("val", patchValue)))))));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
