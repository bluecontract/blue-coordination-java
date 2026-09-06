package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedSourceReferenceRewriteTest {
    private static final String POLICY = "sha256:" + "1".repeat(64);
    private static final Node OLD = new Node().name("old exact child");
    private static final Node NEW = new Node().name("new exact child");
    private static final String OLD_ID = FrozenNode.fromNode(OLD).blueId();
    private static final String NEW_ID = FrozenNode.fromNode(NEW).blueId();

    @Test void acceptsOnlyAuthenticatedSameLineageSubstitution() {
        // given
        Node before = parent(new Node().blueId(OLD_ID));
        Node after = parent(NEW.clone());
        // when
        boolean accepted = verifies(before, after, row(OLD_ID), row(NEW_ID));
        // then
        assertTrue(accepted);
    }

    @Test void rejectsChangedOwnedField() {
        // given
        Node after = parent(NEW.clone()).name("changed parent");
        // when
        boolean accepted = verifies(parent(OLD.clone()), after, row(OLD_ID), row(NEW_ID));
        // then
        assertFalse(accepted);
    }

    @Test void rejectsUnboundChildPayloadMutation() {
        // given
        Node after = parent(NEW.clone().name("forged child"));
        // when
        boolean accepted = verifies(parent(OLD.clone()), after, row(OLD_ID), row(NEW_ID));
        // then
        assertFalse(accepted);
    }

    @Test void rejectsWrongPredecessorValue() {
        // given
        Node before = parent(NEW.clone());
        // when
        boolean accepted = verifies(before, parent(NEW.clone()), row(OLD_ID), row(NEW_ID));
        // then
        assertFalse(accepted);
    }

    @Test void rejectsRetargetedLineageAndActivation() {
        // given
        ManagedOccurrenceBinding retargeted = ManagedOccurrenceBinding.derived(POLICY,
                new DocumentId("parent"), ScopeAddress.embedded("/child", 1),
                new DocumentId("other-child"), NEW_ID, true, null);
        ManagedOccurrenceBinding reactivated = ManagedOccurrenceBinding.derived(POLICY,
                new DocumentId("parent"), ScopeAddress.embedded("/child", 2),
                new DocumentId("child"), NEW_ID, true, null);
        // when
        boolean retarget = verifies(parent(OLD.clone()), parent(NEW.clone()), row(OLD_ID), retargeted);
        boolean reactivate = verifies(parent(OLD.clone()), parent(NEW.clone()), row(OLD_ID), reactivated);
        // then
        assertFalse(retarget);
        assertFalse(reactivate);
    }

    @Test void rejectsMissingOrAdditionalOccurrence() {
        // given
        Node before = parent(OLD.clone());
        Node after = parent(NEW.clone());
        // when
        boolean removed = ManagedSourceReferenceRewrite.verifies(before, after, List.of(row(OLD_ID)), List.of());
        boolean noRows = ManagedSourceReferenceRewrite.verifies(before, after, List.of(), List.of());
        // then
        assertFalse(removed);
        assertFalse(noRows);
    }

    private static Node parent(Node child) {
        return new Node().name("unchanged parent").properties(Map.of("child", child));
    }
    private static ManagedOccurrenceBinding row(String exact) {
        return ManagedOccurrenceBinding.derived(POLICY, new DocumentId("parent"),
                ScopeAddress.embedded("/child", 1), new DocumentId("child"), exact, true, null);
    }
    private static boolean verifies(Node before, Node after, ManagedOccurrenceBinding old,
            ManagedOccurrenceBinding next) {
        return ManagedSourceReferenceRewrite.verifies(before, after, List.of(old), List.of(next));
    }
}
