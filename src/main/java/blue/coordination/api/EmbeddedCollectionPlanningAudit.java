package blue.coordination.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable diagnostic projection for one effective Process Embedded collection.
 *
 * <p>Complete states are retained only after the Contracts planner has
 * authenticated the effective declaration and its exact collection value.
 * Incomplete and invalid-kind states describe typed, noncommitting planning
 * failures and are never substituted with an empty member set.</p>
 */
public record EmbeddedCollectionPlanningAudit(
        String collectionPath,
        State state,
        int currentMemberCount,
        String detail) {

    /** Exhaustive semantic state of one declared collection at planning. */
    public enum State {
        /** The declaration is effective but its selected path is absent. */
        ABSENT,
        /** The selected exact object is present and has no members. */
        PRESENT_EMPTY,
        /** The selected exact object is present and has members. */
        PRESENT_MEMBERS,
        /** Exact collection evidence is not currently available. */
        INCOMPLETE,
        /** The selected present value is not object-compatible. */
        INVALID_KIND
    }

    /** Validates one immutable audit row. */
    public EmbeddedCollectionPlanningAudit {
        collectionPath = requirePath(collectionPath);
        state = Objects.requireNonNull(state, "state");
        detail = requireText(detail, "detail");
        boolean countable = state == State.ABSENT
                || state == State.PRESENT_EMPTY
                || state == State.PRESENT_MEMBERS;
        if ((countable && currentMemberCount < 0)
                || (!countable && currentMemberCount != -1)) {
            throw new IllegalArgumentException(
                    "Member count must be non-negative for complete states "
                            + "and -1 otherwise");
        }
        if (state == State.ABSENT && currentMemberCount != 0) {
            throw new IllegalArgumentException(
                    "An absent collection cannot have current members");
        }
        if (state == State.PRESENT_EMPTY && currentMemberCount != 0) {
            throw new IllegalArgumentException(
                    "A present empty collection cannot have current members");
        }
        if (state == State.PRESENT_MEMBERS && currentMemberCount == 0) {
            throw new IllegalArgumentException(
                    "A present member collection must have current members");
        }
    }

    /** Creates an authenticated absent, empty, or member-bearing row. */
    public static EmbeddedCollectionPlanningAudit completeObject(
            String collectionPath,
            boolean present,
            int currentMemberCount) {
        if (!present && currentMemberCount != 0) {
            throw new IllegalArgumentException(
                    "An absent collection cannot have current members");
        }
        return new EmbeddedCollectionPlanningAudit(
                collectionPath,
                !present
                        ? State.ABSENT
                        : currentMemberCount == 0
                                ? State.PRESENT_EMPTY
                                : State.PRESENT_MEMBERS,
                currentMemberCount,
                !present
                        ? "declared collection is absent"
                        : currentMemberCount == 0
                                ? "declared collection is present and empty"
                                : "declared collection has current members");
    }

    /** Creates a retryable evidence-unavailable planning diagnostic. */
    public static EmbeddedCollectionPlanningAudit incomplete(
            String collectionPath,
            String detail) {
        return new EmbeddedCollectionPlanningAudit(
                collectionPath, State.INCOMPLETE, -1, detail);
    }

    /** Creates a deterministic wrong-kind planning diagnostic. */
    public static EmbeddedCollectionPlanningAudit invalidKind(
            String collectionPath,
            String detail) {
        return new EmbeddedCollectionPlanningAudit(
                collectionPath, State.INVALID_KIND, -1, detail);
    }

    /** Returns deterministic insertion-ordered fields for reports and logs. */
    public Map<String, Object> reportFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("collectionPath", collectionPath);
        fields.put("state", state.name());
        fields.put("currentMemberCount", currentMemberCount);
        fields.put("detail", detail);
        return Collections.unmodifiableMap(fields);
    }

    private static String requirePath(String value) {
        String path = requireText(value, "collectionPath");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "collectionPath must be an absolute pointer");
        }
        return path;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
