package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.SequentialNodeProvider;
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
    void inheritedStaticPatchWritesItsAuthoredValueFromTheResolvedContractView() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = new Blue()
                .nodeProvider(repository.nodeProvider())
                .typeClassResolver(repository.typeClassResolver());
        NodeProvider repositoryProvider = blue.getNodeProvider();
        BasicNodeProvider documentTypes = new BasicNodeProvider();
        documentTypes.addSingleNodes(documentType(new Node()
                .name("Authored status")
                .type(reference(StatusInProgress.blueId()))));
        String documentTypeId = documentTypes.getBlueIdByName("Inherited Static Update Document");
        blue.nodeProvider(new SequentialNodeProvider(
                documentTypes,
                repositoryProvider));
        CoordinationProcessors.registerWith(blue);

        DocumentProcessingResult result = blue.initializeDocument(
                blue.resolveToSnapshot(new Node().type(reference(documentTypeId))));

        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertNull(result.diagnostic());
        Node canonicalStatus = result.document().getProperties().get("status");
        assertEquals(StatusInProgress.blueId(), canonicalStatus.getType().getBlueId());
        assertEquals("Authored status", canonicalStatus.getName());
        assertEquals("active", ProcessingResultTestSupport
                .resolvedDocument(blue, result).getAsText("/status/mode"));
        assertNull(canonicalStatus.getDescription(),
                "metadata inherited by Json Patch Entry.val must not become document content");
    }

    @Test
    void authoredReferenceWithSiblingPayloadRemainsInvalid() {
        BasicNodeProvider documentTypes = new BasicNodeProvider();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> documentTypes.addSingleNodes(documentType(new Node()
                        .blueId(StatusInProgress.blueId())
                        .properties("mode", new Node().value("tampered")))));

        assertTrue(failure.getMessage().contains(
                "\"blueId\" nodes must be reference-only and cannot contain sibling fields"));
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
}
