package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identity-policy regression coverage without changing draft semantics. */
final class ContentDerivedManagedDraftIdentityTest {
    @Test
    void exactFullDraftBlueIdWinsForEveryDocumentIdContentShape() {
        // given
        List<Node> authored = List.of(
                new Node().properties(
                        "name", new Node().value("absent")),
                new Node().properties(
                        "name", new Node().value("string"),
                        "documentId", new Node().value("arbitrary")),
                new Node().properties(
                        "name", new Node().value("nested"),
                        "documentId", new Node().properties(
                                "value", new Node().value("ordinary"))),
                new Node().properties(
                        "name", new Node().value("misleading"),
                        "documentId", new Node().value("wrong-lineage"),
                        "metadata", new Node().properties(
                                "documentId", new Node().value("nested"))));

        // when
        for (Node node : authored) {
            ExactValue exact = ExactValue.verified(node);
            DocumentId exactId = DocumentId.of(exact.blueId());

            ContractsManagedDraftPlan.ManagedDraft draft =
                    new ContractsManagedDraftPlan.ManagedDraft(
                            exactId, exact, null, true);

            // then
            assertEquals(exactId, draft.documentId());
            assertTrue(exact.sameExactValue(draft.initial()));
            assertEquals(exact.blueId(), draft.initial().blueId());
        }
    }

    @Test
    void contentDerivedDraftRejectsAnyCallerLineageLabel() {
        // given
        ExactValue exact = ExactValue.verified(new Node().properties(
                "documentId", new Node().value("ordinary-content")));

        // when
        Runnable constructWithCallerLineage = () ->
                new ContractsManagedDraftPlan.ManagedDraft(
                        DocumentId.of("caller-label"),
                        exact,
                        null,
                        true);

        // then
        assertThrows(IllegalArgumentException.class,
                constructWithCallerLineage::run);
    }
}
