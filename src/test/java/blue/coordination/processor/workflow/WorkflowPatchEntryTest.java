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
        // given
        Node callerOwned = new Node().properties("status", new Node().value("before"));

        // when
        WorkflowPatchEntry entry = new WorkflowPatchEntry("add", "/payload", callerOwned);
        callerOwned.getProperties().get("status").value("after");

        // then
        assertTrue(entry.val().isStrictCanonical());
        assertEquals("before", entry.val().getProperties().get("status").getValue());
    }

    @Test
    void shouldRetainStrictFrozenValueWithoutMaterialization() {
        // given
        FrozenNode authored = FrozenNode.fromNode(new Node().value("authored"));

        // when
        WorkflowPatchEntry entry = new WorkflowPatchEntry("replace", "/payload", authored);

        // then
        assertSame(authored, entry.val());
    }

    @Test
    void shouldCanonicalizeResolvedFrozenCompatibilityValueAtConstruction() {
        // given
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node()
                .properties("status", new Node().value("resolved-shape")));

        // when
        WorkflowPatchEntry entry = new WorkflowPatchEntry("add", "/payload", resolved);

        // then
        assertTrue(entry.val().isStrictCanonical());
        assertEquals("resolved-shape", entry.val().getProperties().get("status").getValue());
    }

    @Test
    void shouldPreserveRemoveValueForExactShapeValidation() {
        // given
        Node forbiddenValue = new Node()
                .properties("expanded", new Node().value("forbidden"));

        // when
        WorkflowPatchEntry entry = new WorkflowPatchEntry(
                "remove", "/payload", forbiddenValue);
        forbiddenValue.getProperties().get("expanded").value("mutated");

        // then
        assertEquals("remove", entry.op());
        assertTrue(entry.val().isStrictCanonical());
        assertEquals("forbidden",
                entry.val().getProperties().get("expanded").getValue());
    }
}
