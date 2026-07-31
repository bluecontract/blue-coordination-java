package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPatchEntryTest {

    @Test
    void shouldDefensivelyFreezeLegacyMutableValueAtTheBoundary() {
        // Given
        Node callerOwned = new Node().properties("status", new Node().value("before"));

        // When
        WorkflowPatchEntry entry = new WorkflowPatchEntry("add", "/payload", callerOwned);
        callerOwned.getProperties().get("status").value("after");

        // Then
        assertTrue(entry.val().isStrictCanonical());
        assertEquals("before", entry.val().getProperties().get("status").getValue());
    }

    @Test
    void shouldRetainStrictFrozenValueWithoutMaterialization() {
        // Given
        FrozenNode authored = FrozenNode.fromNode(new Node().value("authored"));

        // When
        WorkflowPatchEntry entry = new WorkflowPatchEntry("replace", "/payload", authored);

        // Then
        assertSame(authored, entry.val());
    }

    @Test
    void shouldCanonicalizeResolvedFrozenCompatibilityValueAtConstruction() {
        // Given
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node()
                .properties("status", new Node().value("resolved-shape")));

        // When
        WorkflowPatchEntry entry = new WorkflowPatchEntry("add", "/payload", resolved);

        // Then
        assertTrue(entry.val().isStrictCanonical());
        assertEquals("resolved-shape", entry.val().getProperties().get("status").getValue());
    }

    @Test
    void shouldPreserveRemoveValueForExactShapeValidation() {
        // Given
        Node forbiddenValue = new Node()
                .properties("expanded", new Node().value("forbidden"));

        // When
        WorkflowPatchEntry entry = new WorkflowPatchEntry(
                "remove", "/payload", forbiddenValue);
        forbiddenValue.getProperties().get("expanded").value("mutated");

        // Then
        assertEquals("remove", entry.op());
        assertTrue(entry.val().isStrictCanonical());
        assertEquals("forbidden",
                entry.val().getProperties().get("expanded").getValue());
    }
}
