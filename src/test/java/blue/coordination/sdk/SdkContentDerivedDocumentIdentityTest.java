package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Public opt-in identity policy coverage for ordinary explicit admission. */
final class SdkContentDerivedDocumentIdentityTest {
    @Test
    void exactAuthoredBlueIdWinsWithoutInterpretingDocumentIdContent() {
        // given
        List<String> authoredValues = List.of(
                "name: absent-document-id\n",
                "name: arbitrary-string\ndocumentId: caller-label\n",
                """
                        name: nested-value
                        documentId:
                          nested: ordinary-content
                        """,
                """
                        name: misleading-value
                        documentId: another-managed-lineage
                        metadata:
                          documentId: nested-label
                        """);

        // when
        for (String authored : authoredValues) {
            ExactBlueValue exact = exact(authored);
            DocumentId exactId = DocumentId.of(exact.blueId());
            try (BlueCoordination blue = BlueCoordination.builder()
                    .contentDerivedDocumentIds()
                    .build()) {
                DocumentHandle document = blue.documents().admit(
                        ManagedDocument.yaml(exactId, authored)
                                .publicRoot()
                                .fromNow());

                // then
                assertEquals(exactId, document.id());
                Node expectedDocumentId = NodePathEditor.getOrNull(
                        exact.copyNode(), "/documentId");
                Node retainedDocumentId = NodePathEditor.getOrNull(
                        document.snapshot().exact().copyNode(),
                        "/documentId");
                if (expectedDocumentId == null) {
                    assertNull(retainedDocumentId);
                } else {
                    assertEquals(
                            ExactValue.verified(expectedDocumentId).blueId(),
                            ExactValue.verified(retainedDocumentId).blueId());
                }
            }
        }
    }

    @Test
    void callerLabelCannotOverrideExactAuthoredIdentity() {
        // given
        String authored = "documentId: caller-label\nname: exact wins\n";

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .contentDerivedDocumentIds()
                .build()) {
            // then
            assertThrows(IllegalArgumentException.class,
                    () -> blue.documents().admit(
                            ManagedDocument.yaml(
                                            DocumentId.of("caller-label"),
                                            authored)
                                    .publicRoot()
                                    .fromNow()));
        }
    }

    private static ExactBlueValue exact(String yaml) {
        try (BlueCoordination verifier = BlueCoordination.inMemory()) {
            return verifier.values().yaml(yaml);
        }
    }
}
