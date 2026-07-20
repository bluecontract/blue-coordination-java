package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPatchEntryTest {

    @Test
    void legacyMutableValueIsDefensivelyFrozenOnceAtTheBoundary() {
        Node callerOwned = new Node().properties("status", new Node().value("before"));

        WorkflowPatchEntry entry = new WorkflowPatchEntry("add", "/payload", callerOwned);
        callerOwned.getProperties().get("status").value("after");

        assertTrue(entry.val().isStrictCanonical());
        assertEquals("before", entry.val().getProperties().get("status").getValue());
    }

    @Test
    void strictFrozenValueIsRetainedWithoutMaterialization() {
        FrozenNode authored = FrozenNode.fromNode(new Node().value("authored"));

        WorkflowPatchEntry entry = new WorkflowPatchEntry("replace", "/payload", authored);

        assertSame(authored, entry.val());
    }

    @Test
    void resolvedFrozenCompatibilityValueIsCanonicalizedAtConstruction() {
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node()
                .properties("status", new Node().value("resolved-shape")));

        WorkflowPatchEntry entry = new WorkflowPatchEntry("add", "/payload", resolved);

        assertTrue(entry.val().isStrictCanonical());
        assertEquals("resolved-shape", entry.val().getProperties().get("status").getValue());
    }

    @Test
    void removeIgnoresCallerOwnedValueWithoutFreezingIt() {
        Node irrelevantValue = new Node()
                .blueId("GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC")
                .properties("expanded", new Node().value("ignored"));

        WorkflowPatchEntry entry = new WorkflowPatchEntry(
                " REMOVE ", "/payload", irrelevantValue);

        assertNull(entry.val());
    }
}
