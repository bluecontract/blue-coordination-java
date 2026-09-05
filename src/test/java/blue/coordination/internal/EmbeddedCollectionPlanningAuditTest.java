package blue.coordination.internal;

import blue.coordination.api.EmbeddedCollectionPlanningAudit;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EmbeddedCollectionPlanningAuditTest {

    @Test
    void distinguishesAbsentAndPresentEmptyEvenThoughBothCountZero() {
        // given
        EmbeddedCollectionPlanningAudit absent =
                EmbeddedCollectionPlanningAudit.completeObject(
                        "/games", false, 0);
        EmbeddedCollectionPlanningAudit empty =
                EmbeddedCollectionPlanningAudit.completeObject(
                        "/games", true, 0);

        // when
        // then

        assertEquals(EmbeddedCollectionPlanningAudit.State.ABSENT,
                absent.state());
        assertEquals(0, absent.currentMemberCount());
        assertEquals(EmbeddedCollectionPlanningAudit.State.PRESENT_EMPTY,
                empty.state());
        assertEquals(0, empty.currentMemberCount());
        assertEquals(Map.of(
                        "collectionPath", "/games",
                        "state", "PRESENT_EMPTY",
                        "currentMemberCount", 0,
                        "detail", "declared collection is present and empty"),
                empty.reportFields());
        assertEquals(List.of(
                        "collectionPath",
                        "state",
                        "currentMemberCount",
                        "detail"),
                List.copyOf(empty.reportFields().keySet()));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddedCollectionPlanningAudit.completeObject(
                        "/games", false, 1));
    }

    @Test
    void distinguishesIncompleteAndInvalidKindAsNonCountable() {
        // given
        EmbeddedCollectionPlanningAudit incomplete =
                EmbeddedCollectionPlanningAudit.incomplete(
                        "/games", "provider offline");
        EmbeddedCollectionPlanningAudit invalid =
                EmbeddedCollectionPlanningAudit.invalidKind(
                        "/games", "present value is not an object");

        // when
        // then

        assertEquals(EmbeddedCollectionPlanningAudit.State.INCOMPLETE,
                incomplete.state());
        assertEquals(-1, incomplete.currentMemberCount());
        assertEquals(EmbeddedCollectionPlanningAudit.State.INVALID_KIND,
                invalid.state());
        assertEquals(-1, invalid.currentMemberCount());
    }
}
